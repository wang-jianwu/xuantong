package com.xuantong.client.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配置中心性能指标采集器
 */
public class ConfigMetrics {
    private static final ConfigMetrics INSTANCE = new ConfigMetrics();

    // 缓存命中统计
    private final AtomicLong memoryCacheHits = new AtomicLong(0);
    private final AtomicLong fileCacheHits = new AtomicLong(0);
    private final AtomicLong remoteFetchCount = new AtomicLong(0);

    // 错误统计
    private final AtomicLong fetchErrors = new AtomicLong(0);
    private final AtomicLong parseErrors = new AtomicLong(0);

    // 响应时间统计
    private final ConcurrentHashMap<String, Long> responseTimes = new ConcurrentHashMap<>();

    private ConfigMetrics() {}

    public static ConfigMetrics getInstance() {
        return INSTANCE;
    }

    /**
     * 记录内存缓存命中次数
     *
     * <p>该方法用于统计配置项从内存缓存中成功获取的次数，
     * 每次调用会将内存缓存命中计数器加1。
     */
    public void recordMemoryCacheHit() {
        memoryCacheHits.incrementAndGet();
    }

    public void recordFileCacheHit() {
        fileCacheHits.incrementAndGet();
    }

    // 记录远程获取
    public void recordRemoteFetch() {
        remoteFetchCount.incrementAndGet();
    }

    public void recordParseError() {
        parseErrors.incrementAndGet();
    }

    // 记录响应时间
    public void recordResponseTime(String operation, long millis) {
        responseTimes.put(operation, millis);
    }
}