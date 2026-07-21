package cloud.xuantong.client.cache;

import cloud.xuantong.client.util.FileKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置缓存管理器
 * <p>
 * 每个 namespace/group 使用独立目录，并用单文件 all.properties 保存该 Group 的全部配置。
 */
public class ConfigCacheManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCacheManager.class);
    // 内存缓存
    private final ConcurrentHashMap<String, String> memoryCache = new ConcurrentHashMap<>();
    // 缓存目录
    private final Path cacheDir;
    // 统一缓存文件
    private final Path cacheFile;
    // 文件操作使用单线程执行器（确保顺序执行，避免 batchUpdate 和 remove 的竞态）
    private final ExecutorService fileOpsExecutor;
    // 仅保留最新待落盘快照，连续推送时合并磁盘写入
    private final AtomicReference<Map<String, String>> pendingSnapshot = new AtomicReference<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ConfigCacheManager(String namespace, String group) {
        this(namespace, group, Paths.get(System.getProperty("user.dir"), ".xuantong-cache"));
    }

    ConfigCacheManager(String namespace, String group, Path cacheRoot) {
        this.cacheDir = cacheRoot.resolve(namespace).resolve(group);
        this.cacheFile = cacheDir.resolve("all.properties");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            logger.error("Failed to create cache directory", e);
        }

        // 文件操作使用单线程（确保 batchUpdate 和 remove 的顺序执行，避免竞态导致文件损坏）
        this.fileOpsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "cache-file-ops-thread");
            thread.setDaemon(true);
            return thread;
        });

        loadFromFileCache();
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
        return memoryCache.get(key);
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
        ensureOpen();
        if (configs == null || (!replace && configs.isEmpty())) {
            return;
        }

        // 同步更新内存缓存（确保实时性）
        Map<String, String> snapshot;
        synchronized (memoryCache) {
            if (replace) {
                memoryCache.clear();
            }
            memoryCache.putAll(configs);
            snapshot = new HashMap<>(memoryCache);
        }

        persistSnapshotAsync(snapshot);
    }

    // 清除指定配置 - 内存同步，快照异步
    public void remove(String key) {
        ensureOpen();
        Map<String, String> snapshot;
        synchronized (memoryCache) {
            memoryCache.remove(key);
            snapshot = new HashMap<>(memoryCache);
        }
        persistSnapshotAsync(snapshot);
    }

    /**
     * Last-known-good config does not expire by wall clock. This method remains
     * package-visible only so older cache tests fail explicitly by behavior rather
     * than by linkage while the 2.0 cache contract is exercised.
     */
    void cleanupExpiredCache(long now) {
        // Intentionally no-op: only an authoritative higher decision may replace LKG.
    }

    // 关闭所有线程池
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
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
        ensureOpen();
        synchronized (memoryCache) {
            memoryCache.clear();
        }
        persistSnapshotAsync(Collections.emptyMap());
    }

    // 获取所有配置
    public Map<String, String> getAll() {
        return new ConcurrentHashMap<>(memoryCache);
    }

    // ========== 单文件缓存实现 ==========

    /**
     * 加载统一缓存文件 all.properties（使用 Properties 解析以正确处理转义字符）
     */
    private Map<String, String> loadCacheFile() {
        Map<String, String> cacheMap = new ConcurrentHashMap<>();
        if (Files.exists(cacheFile)) {
            try {
                String content = FileKit.readFile(cacheFile);
                if (!content.isEmpty() && !"{}".equals(content)) {
                    Properties props = new Properties();
                    props.load(new StringReader(content));
                    for (String key : props.stringPropertyNames()) {
                        cacheMap.put(key, props.getProperty(key));
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to load cache file, file may be corrupted", e);
                // 文件损坏则返回空 map，下次推送会全量覆盖
            }
        }
        return cacheMap;
    }

    private void persistSnapshotAsync(Map<String, String> snapshot) {
        pendingSnapshot.set(Map.copyOf(snapshot));
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            fileOpsExecutor.execute(this::drainPendingSnapshots);
        } catch (RejectedExecutionException e) {
            flushScheduled.set(false);
            if (!closed.get()) {
                throw e;
            }
        }
    }

    private void drainPendingSnapshots() {
        try {
            Map<String, String> snapshot;
            while ((snapshot = pendingSnapshot.getAndSet(null)) != null) {
                writeCacheFile(snapshot);
            }
        } catch (Exception e) {
            logger.error("Failed to update cache snapshot", e);
        } finally {
            flushScheduled.set(false);
            if (pendingSnapshot.get() != null) {
                scheduleFlush();
            }
        }
    }

    /**
     * 使用临时文件和原子替换写入完整快照，避免进程异常留下半文件。
     */
    private void writeCacheFile(Map<String, String> snapshot) {
        Path tempFile = null;
        try {
            Files.createDirectories(cacheDir);
            if (snapshot.isEmpty()) {
                Files.deleteIfExists(cacheFile);
                logger.debug("Deleted empty cache snapshot: {}", cacheFile);
                return;
            }

            Properties props = new Properties();
            props.putAll(snapshot);
            StringWriter sw = new StringWriter();
            props.store(sw, null);
            String content = sw.toString();
            int firstNewline = content.indexOf('\n');
            if (firstNewline >= 0) {
                content = content.substring(firstNewline + 1);
            }

            tempFile = Files.createTempFile(cacheDir, "all.properties.", ".tmp");
            FileKit.writeFile(tempFile, content);
            try {
                Files.move(tempFile, cacheFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile = null;
            logger.debug("Saved {} configs to cache snapshot", props.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write cache file", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    logger.debug("Failed to delete temporary cache file: {}", tempFile, e);
                }
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Cache manager is already shut down");
        }
    }
}
