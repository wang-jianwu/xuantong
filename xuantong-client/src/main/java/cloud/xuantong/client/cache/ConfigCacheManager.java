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
 * 配置缓存管理器
 * <p>
 * 使用单文件 all.properties 存储所有配置，不再按 key 前缀拆分文件。
 * 原因：key 前缀不等于 project 名（如 key="zl.new.hand" 属于 social 项目），
 * 按前缀拆分会导致缓存文件分组错误。
 */
public class ConfigCacheManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCacheManager.class);
    private static final long DEFAULT_CACHE_EXPIRE_TIME = 24 * 60 * 60 * 1000; // 24小时
    private static final long CLEANUP_INTERVAL = 60 * 60 * 1000; // 1小时

    // 内存缓存
    private final ConcurrentHashMap<String, String> memoryCache = new ConcurrentHashMap<>();
    // 缓存目录
    private final Path cacheDir;
    // 统一缓存文件
    private final Path cacheFile;
    // 缓存访问时间记录
    private final ConcurrentHashMap<String, Long> lastAccessTimes = new ConcurrentHashMap<>();
    // 清理调度器（定时任务使用单线程）
    private final ScheduledExecutorService cleanupScheduler;
    // 文件操作执行器
    private final ExecutorService fileOpsExecutor;

    public ConfigCacheManager(String env) {
        this.cacheDir = Paths.get(System.getProperty("user.dir"), ".xuantong-cache", env);
        this.cacheFile = cacheDir.resolve("all.properties");
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

        // 文件操作使用固定大小线程池
        this.fileOpsExecutor = Executors.newFixedThreadPool(2, r -> {
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
            Map<String, String> fileConfigs = loadCacheFile();
            if (!fileConfigs.isEmpty()) {
                memoryCache.putAll(fileConfigs);
            }
            logger.info("Loaded {} configs from local file cache", memoryCache.size());
        } catch (Exception e) {
            logger.error("Failed to load configs from file cache", e);
        }
    }

    // 获取配置
    public String get(String key) {
        String value = memoryCache.get(key);
        if (value != null) {
            lastAccessTimes.put(key, System.currentTimeMillis());
            return value;
        }
        return null;
    }

    // 批量更新缓存 - 内存同步，快照异步（合并模式：推送增量更新时使用）
    public void batchUpdate(Map<String, String> configs) {
        batchUpdate(configs, false);
    }

    /**
     * 批量更新缓存
     * @param configs 配置数据
     * @param replace true=全量替换（初始加载/重连），false=合并到现有（推送增量）
     */
    public void batchUpdate(Map<String, String> configs, boolean replace) {
        if (configs == null || configs.isEmpty()) {
            return;
        }

        // 同步更新内存缓存（确保实时性）
        synchronized (memoryCache) {
            if (replace) {
                memoryCache.clear();
            }
            memoryCache.putAll(configs);
            configs.keySet().forEach(key -> lastAccessTimes.put(key, System.currentTimeMillis()));
        }

        // 异步更新文件快照（最终一致性）
        fileOpsExecutor.execute(() -> {
            try {
                writeCacheFile(configs, replace);
                logger.debug("Async snapshot updated with {} configs (replace={})", configs.size(), replace);
            } catch (Exception e) {
                logger.error("Failed to update snapshot", e);
            }
        });
    }

    // 清除指定配置 - 内存同步，快照异步
    public void remove(String key) {
        synchronized (memoryCache) {
            memoryCache.remove(key);
            lastAccessTimes.remove(key);
        }

        // 异步从文件中移除
        fileOpsExecutor.execute(() -> {
            try {
                removeKeyFromFile(key);
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
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

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
        lastAccessTimes.clear();
        try {
            Files.deleteIfExists(cacheFile);
            logger.info("Deleted cache file: {}", cacheFile.getFileName());
        } catch (IOException e) {
            logger.warn("Failed to delete cache file", e);
        }
    }

    // 获取所有配置
    public Map<String, String> getAll() {
        return new ConcurrentHashMap<>(memoryCache);
    }

    // ========== 单文件缓存实现 ==========

    /**
     * 加载统一缓存文件 all.properties
     */
    private Map<String, String> loadCacheFile() {
        Map<String, String> cacheMap = new ConcurrentHashMap<>();
        if (Files.exists(cacheFile)) {
            FileKit.processLines(cacheFile, line -> {
                int index = line.indexOf('=');
                if (index > 0) {
                    String key = line.substring(0, index);
                    String value = line.substring(index + 1);
                    cacheMap.put(key, value);
                }
            });
        }
        return cacheMap;
    }

    /**
     * 写入缓存文件
     * @param configs 新配置
     * @param replace true=全量替换，false=增量合并
     */
    private void writeCacheFile(Map<String, String> configs, boolean replace) {
        try {
            Files.createDirectories(cacheDir);

            Map<String, String> merged;
            if (replace) {
                merged = new ConcurrentHashMap<>(configs);
            } else {
                // 增量合并：先加载现有文件数据，再合并新配置
                merged = loadCacheFile();
                merged.putAll(configs);
            }

            List<String> lines = new ArrayList<>(merged.size());
            for (Map.Entry<String, String> entry : merged.entrySet()) {
                lines.add(entry.getKey() + "=" + entry.getValue());
            }
            FileKit.atomicWriteLines(cacheFile, lines);

            logger.info("Saved {} configs to cache file ({} new, replace={})", merged.size(), configs.size(), replace);
        } catch (Exception e) {
            logger.error("Failed to write cache file", e);
        }
    }

    /**
     * 从缓存文件中移除指定 key
     */
    private void removeKeyFromFile(String key) {
        try {
            Map<String, String> currentConfig = loadCacheFile();
            currentConfig.remove(key);

            List<String> lines = new ArrayList<>(currentConfig.size());
            for (Map.Entry<String, String> entry : currentConfig.entrySet()) {
                lines.add(entry.getKey() + "=" + entry.getValue());
            }
            FileKit.atomicWriteLines(cacheFile, lines);
        } catch (Exception e) {
            logger.error("Failed to remove key from cache file: {}", key, e);
        }
    }
}
