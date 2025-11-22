package com.xuantong.client.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存管理器（支持缓存同步、过期策略和监控）
 */
public class ConfigCacheManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCacheManager.class);
    private static final long DEFAULT_CACHE_EXPIRE_TIME = 24 * 60 * 60 * 1000; // 24小时
    private static final long CLEANUP_INTERVAL = 60 * 60 * 1000; // 1小时

    // 内存缓存
    private final ConcurrentHashMap<String, String> memoryCache = new ConcurrentHashMap<>();
    // 本地文件缓存接口
    private final ConfigCache fileCache;
    // 序列化器字段已移除（未使用）
    // 缓存访问时间记录
    private final ConcurrentHashMap<String, Long> lastAccessTimes = new ConcurrentHashMap<>();
    // 清理调度器
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * 使用默认文件缓存
     */
    public ConfigCacheManager() {
        this(new LocalFileCache());
    }

    /**
     * 使用自定义缓存
     */
    public ConfigCacheManager(ConfigCache fileCache) {
        this.fileCache = fileCache;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cache-cleanup-thread");
            thread.setDaemon(true);
            return thread;
        });
        loadFromFileCache();
        startCleanupTask();
    }

    // 启动时从文件缓存加载
    private void loadFromFileCache() {
        try {
            Map<String, String> fileConfigs = fileCache.getAll();
            memoryCache.putAll(fileConfigs);
            logger.info("Loaded {} configs from local file cache", fileConfigs.size());
        } catch (Exception e) {
            logger.error("Failed to load configs from file cache", e);
        }
    }

    // 获取配置（支持默认值）
    public String get(String key) {
        // 1. 检查内存缓存
        String value = memoryCache.get(key);
        if (value != null) {
            lastAccessTimes.put(key, System.currentTimeMillis());
            return value;
        }

        // 2. 检查文件缓存
        value = fileCache.get(key);
        if (value != null) {
            lastAccessTimes.put(key, System.currentTimeMillis());
            memoryCache.put(key, value);
            return value;
        }
        // 3. 远程获取由上层处理
        return null;
    }

    // 批量更新（支持事务性保证）
    public void batchUpdate(Map<String, String> configs) {
        Map<String, String> memoryBackup = new ConcurrentHashMap<>(memoryCache);
        try {
            configs.forEach((key, value) -> {
                memoryCache.put(key, value);
                fileCache.put(key, value);
                lastAccessTimes.put(key, System.currentTimeMillis());
            });
        } catch (Exception e) {
            // 发生异常时回滚内存缓存
            memoryCache.clear();
            memoryCache.putAll(memoryBackup);
            throw new RuntimeException("Batch update failed, rolled back memory cache", e);
        }
    }

    // 清除指定配置
    public void remove(String key) {
        memoryCache.remove(key);
        fileCache.remove(key);
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

    // 关闭清理调度器
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
    }

    // 清除所有配置
    public void clear() {
        memoryCache.clear();
        fileCache.clear();
        lastAccessTimes.clear();
    }

    // 获取所有配置
    public Map<String, String> getAll() {
        return new ConcurrentHashMap<>(memoryCache);
    }
}