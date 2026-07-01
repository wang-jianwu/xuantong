package cloud.xuantong.client.cache;

import cloud.xuantong.client.util.FileKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 多级缓存管理器（支持缓存同步、过期策略和监控）
 */
public class ConfigCacheManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCacheManager.class);
    private static final long DEFAULT_CACHE_EXPIRE_TIME = 24 * 60 * 60 * 1000; // 24小时
    private static final long CLEANUP_INTERVAL = 60 * 60 * 1000; // 1小时

    // 内存缓存
    private final ConcurrentHashMap<String, String> memoryCache = new ConcurrentHashMap<>();
    // 缓存目录
    private final Path cacheDir;
    // 缓存访问时间记录
    private final ConcurrentHashMap<String, Long> lastAccessTimes = new ConcurrentHashMap<>();
    // 清理调度器（定时任务使用单线程）
    private final ScheduledExecutorService cleanupScheduler;
    // 文件操作执行器（并发文件操作使用多线程）
    private final ExecutorService fileOpsExecutor;
    private static final String DEFAULT_APP = "default";

    public ConfigCacheManager(String env) {
        this.cacheDir = Paths.get(System.getProperty("user.dir"), ".xuantong-cache", env);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            logger.error("Failed to create cache directory", e);
        }

        // 清理任务使用单线程（确保顺序执行）
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cache-cleanup-thread");
            thread.setDaemon(true);
            return thread;
        });

        // 文件操作使用固定大小线程池（提高IO并发性能）
        this.fileOpsExecutor = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "cache-file-ops-thread");
            thread.setDaemon(true);
            return thread;
        });

        loadFromFileCache();
        startCleanupTask();
    }

    // 启动时从文件缓存加载
    private void loadFromFileCache() {
        try {
            Map<String, String> fileConfigs = loadAllFromCacheFiles();
            memoryCache.putAll(fileConfigs);
            logger.info("Loaded {} configs from local file cache", fileConfigs.size());
        } catch (Exception e) {
            logger.error("Failed to load configs from file cache", e);
        }
    }

    // 获取配置（支持默认值）
    public String get(String key) {
        // 1. 检查内存缓存（ConcurrentHashMap 线程安全，无需 synchronized）
        String value = memoryCache.get(key);
        if (value != null) {
            lastAccessTimes.put(key, System.currentTimeMillis());
            return value;
        }

        // 2. 检查文件快照（异步写入不影响读取）
        value = getFromCacheFile(key);
        if (value != null) {
            lastAccessTimes.put(key, System.currentTimeMillis());
            memoryCache.put(key, value); // 回填内存缓存
            return value;
        }
        // 3. 远程获取由上层处理
        return null;
    }

    // 批量更新缓存 - 内存同步，快照异步
    public void batchUpdate(Map<String, String> configs) {
        if (configs == null || configs.isEmpty()) {
            return;
        }

        // 同步更新内存缓存（确保实时性）
        synchronized (memoryCache) {
            memoryCache.putAll(configs);
            configs.keySet().forEach(key -> lastAccessTimes.put(key, System.currentTimeMillis()));
        }

        // 异步更新文件快照（最终一致性）
        fileOpsExecutor.execute(() -> {
            try {
                updateCacheFiles(configs);
                logger.debug("Async snapshot updated with {} configs", configs.size());
            } catch (Exception e) {
                logger.error("Failed to update snapshot", e);
            }
        });
    }

    // 清除指定配置 - 内存同步，快照异步
    public void remove(String key) {
        // 同步移除内存缓存
        synchronized (memoryCache) {
            memoryCache.remove(key);
            lastAccessTimes.remove(key);
        }

        // 异步移除文件快照
        fileOpsExecutor.execute(() -> {
            try {
                removeFromCacheFile(key);
            } catch (Exception e) {
                logger.error("Failed to remove snapshot for key: {}", key, e);
            }
        });
    }

    // 清理过期缓存
    private void cleanupExpiredCache() {
        long now = System.currentTimeMillis();
        memoryCache.keySet().removeIf(key -> {
            Long lastAccess = lastAccessTimes.get(key);
            if (lastAccess == null || now - lastAccess > DEFAULT_CACHE_EXPIRE_TIME) {
                lastAccessTimes.remove(key);
                return true;
            }
            return false;
        });
    }

    // 启动清理任务
    private void startCleanupTask() {
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredCache();
                logger.debug("Cache cleanup completed, remaining items: {}", memoryCache.size());
            } catch (Exception e) {
                logger.error("Cache cleanup failed", e);
            }
        }, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }

    // 关闭所有线程池
    public void shutdown() {
        // 关闭清理调度器
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 关闭文件操作线程池
        fileOpsExecutor.shutdown();
        try {
            if (!fileOpsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fileOpsExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            fileOpsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // 清除所有配置
    public void clear() {
        memoryCache.clear();
        clearAllCacheFiles();
        lastAccessTimes.clear();
    }

    // 获取所有配置
    public Map<String, String> getAll() {
        return new ConcurrentHashMap<>(memoryCache);
    }

    // ========== 文件缓存实现 ==========

    private Path getCachePath(String appName) {
        return cacheDir.resolve(appName + ".properties");
    }

    private String extractAppName(String key) {
        int dotIndex = key.indexOf('.');
        return dotIndex > 0 ? key.substring(0, dotIndex) : "default";
    }

    private Map<String, String> loadAllFromCacheFiles() {
        Map<String, String> allConfigs = new ConcurrentHashMap<>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.properties")) {
            for (Path path : stream) {
                try {
                    Map<String, String> appCache = loadAppCache(path);
                    allConfigs.putAll(appCache);
                } catch (Exception e) {
                    logger.warn("Failed to load cache from: {}", path.getFileName(), e);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load all cache files", e);
        }
        return allConfigs;
    }

    private String getFromCacheFile(String key) {
        String appName = extractAppName(key);
        if (DEFAULT_APP.equals(appName)) {
            logger.debug("No app prefix for key, skip file cache: {}", key);
            return null;
        }
        Path cacheFile = getCachePath(appName);
        try {
            Map<String, String> cache = loadAppCache(cacheFile);
            return cache.get(key);
        } catch (Exception e) {
            logger.warn("Failed to get cache for key: {}", key, e);
            return null;
        }
    }

    private void updateCacheFiles(Map<String, String> configs) {
        // 按应用名分组配置
        Map<String, Map<String, String>> appConfigs = new ConcurrentHashMap<>();
        configs.forEach((key, value) -> {
            String appName = extractAppName(key);
            if (DEFAULT_APP.equals(appName)) {
                logger.warn("Skipping cache for key without app prefix: {}", key);
                return;
            }
            appConfigs.computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                    .put(key, value);
        });

        // 调试日志：显示分组结果
        logger.debug("Cache file grouping: {}", appConfigs.keySet());

        // 为每个应用更新缓存文件
        appConfigs.forEach((appName, appConfig) -> {
            Path cacheFile = getCachePath(appName);
            try {
                // 确保缓存目录存在
                Files.createDirectories(cacheDir);

                // 直接使用传入的配置，不合并现有配置（避免旧配置污染）
                List<String> lines = new ArrayList<>(appConfig.size());
                for (Map.Entry<String, String> entry : appConfig.entrySet()) {
                    lines.add(entry.getKey() + "=" + entry.getValue());
                }
                FileKit.atomicWriteLines(cacheFile, lines);

                logger.info("Saved {} configs for app {}", appConfig.size(), appName);
            } catch (Exception e) {
                logger.error("Failed to update cache for app {}", appName, e);
            }
        });
    }

    private void removeFromCacheFile(String key) {
        String appName = extractAppName(key);
        if (DEFAULT_APP.equals(appName)) {
            logger.debug("No app prefix for key, skip file remove: {}", key);
            return;
        }
        Path cacheFile = getCachePath(appName);
        try {
            // 加载现有配置并移除指定key
            Map<String, String> currentConfig = loadAppCache(cacheFile);
            currentConfig.remove(key);

            // 保存更新后的配置
            List<String> lines = new ArrayList<>(currentConfig.size());
            for (Map.Entry<String, String> entry : currentConfig.entrySet()) {
                lines.add(entry.getKey() + "=" + entry.getValue());
            }
            FileKit.atomicWriteLines(cacheFile, lines);

        } catch (Exception e) {
            logger.error("Failed to remove cache for key: {}", key, e);
        }
    }

    private void clearAllCacheFiles() {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.properties")) {
            for (Path path : stream) {
                try {
                    Files.delete(path);
                    logger.info("Deleted cache file: {}", path.getFileName());
                } catch (IOException e) {
                    logger.warn("Failed to delete cache file: {}", path.getFileName(), e);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to clear cache files", e);
        }
    }

    private Map<String, String> loadAppCache(Path filePath) {
        Map<String, String> cacheMap = new ConcurrentHashMap<>();
        try {
            if (Files.exists(filePath)) {
                FileKit.processLines(filePath, line -> {
                    if (line.contains("=")) {
                        int index = line.indexOf('=');
                        if (index > 0) {
                            String key = line.substring(0, index);
                            String value = line.substring(index + 1);
                            cacheMap.put(key, value);
                        }
                    }
                });
            }
        } catch (Exception e) {
            logger.error("Load app cache failed: {}", filePath.getFileName(), e);
        }
        return cacheMap;
    }
}