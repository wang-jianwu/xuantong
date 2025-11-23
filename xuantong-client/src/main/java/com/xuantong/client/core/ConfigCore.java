package com.xuantong.client.core;

import com.xuantong.client.cache.ConfigCacheManager;
import com.xuantong.client.exception.XuantongException;
import com.xuantong.client.listener.ConfigListener;
import com.xuantong.client.listener.ConfigListenerManager;
import com.xuantong.client.model.ConfigChangeEvent;
import com.xuantong.client.serializer.Serializer;
import com.xuantong.client.transport.ConfigTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 配置核心实现
 */
public class ConfigCore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCore.class);

    private final List<String> serverAddress;
    private final List<String> subscribedApps;  // 订阅的应用列表
    private final String env;
    private final ConfigTransport transport;
    private final ConfigCacheManager cacheManager;
    private final ConfigListenerManager listenerManager;
    private final Serializer serializer;
    private volatile boolean initialized = false;

    /**
     * 构造函数 - 支持多应用订阅
     */
    public ConfigCore(List<String> serverAddress, List<String> subscribedApps, String env, ConfigTransport transport) {
        this.serverAddress = serverAddress;
        this.subscribedApps = subscribedApps != null ? subscribedApps : Collections.emptyList();
        this.env = env;
        this.cacheManager = new ConfigCacheManager(env);
        this.listenerManager = new ConfigListenerManager();
        this.transport = transport;
        this.serializer = Serializer.defaultSerializer();
        initialize();
    }

    /**
     * 同步初始化
     */
    private void initialize() {
        if (initialized) {
            return;
        }

        try {
            logger.info("Initializing ConfigCore for{} with subscribed apps: {}", env, subscribedApps);

            // 注册配置变更监听器到传输层（监听主应用）
            transport.connect(serverAddress, subscribedApps, env, configData -> {
                logger.info("监听到配置变更 {}/{}/{}", subscribedApps, env, configData);
                // 处理配置变更通知
                Map<String, String> newConfigs = serializer.deserializeMap(configData);
                if (newConfigs != null && !newConfigs.isEmpty()) {
                    newConfigs.forEach((key, value) -> listenerManager.fireEvent(new ConfigChangeEvent(key, value)));
                    handleConfigPush(newConfigs);
                }
            });

            // 加载初始配置（只加载当前应用和订阅应用的配置）
            loadInitialConfigs();

            initialized = true;
            logger.info("ConfigCore initialized successfully for {} apps: {}",
                    subscribedApps.size() + 1, subscribedApps);
        } catch (Exception e) {
            logger.error("ConfigCore initialization failed", e);
            throw new XuantongException("Failed to initialize ConfigCore", e);
        }
    }

    /**
     * 获取配置值
     */
    public String get(String key, String defaultValue) {
        if (!initialized) {
            throw new IllegalStateException("ConfigCore not initialized");
        }

        // 1. 检查缓存
        String value = cacheManager.get(key);
        if (value != null) {
            return value;
        }

        // 2. 远程获取
        try {
            value = transport.fetch(key, env);
            if (value != null) {
                cacheManager.batchUpdate(Collections.singletonMap(key, value));
                return value;
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch config '{}' from env {}", key, env, e);
        }
        return defaultValue;
    }

    /**
     * 加载初始配置
     */
    private void loadInitialConfigs() {
        int retryCount = 0;
        final int maxRetries = 2; // 减少到2次重试
        final long[] retryIntervals = {500, 1000}; // 更短的重试间隔：0.5秒, 1秒

        while (retryCount <= maxRetries) {
            try {
                // 多应用订阅模式 - 批量获取所有应用的配置
                String data = transport.fetchAllForApps(subscribedApps, env);
                if (data != null) {
                    Map<String, String> allConfigs = serializer.deserializeMap(data);
                    cacheManager.batchUpdate(allConfigs);
                    logger.info("Loaded {} initial configs from {} apps: {}",
                            allConfigs.size(), subscribedApps.size(), subscribedApps);
                    return;
                }
                // 配置为空但仍然成功获取到响应
                logger.info("No configs found (appears to be first startup)");
                return;

            } catch (Exception e) {
                retryCount++;
                if (retryCount <= maxRetries) {
                    logger.info("Config server not ready (attempt {}/{}), retrying in {}ms: {}",
                            retryCount, maxRetries, retryIntervals[retryCount - 1], e.getMessage());
                    try {
                        Thread.sleep(retryIntervals[retryCount - 1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Config loading interrupted");
                        return;
                    }
                } else {
                    logger.warn("Proceeding without initial configs (server unavailable): {}",
                            e.getMessage());
                }
            }
        }
    }

    /**
     * 处理配置推送更新
     */
    public void handleConfigPush(Map<String, String> newConfigs) {
        if (newConfigs == null || newConfigs.isEmpty()) {
            return;
        }

        // 直接更新缓存
        cacheManager.batchUpdate(newConfigs);
        logger.info("Processed config push with {} configs", newConfigs.size());
    }

    /**
     * 关闭资源
     */
    @Override
    public void close() {
        try {
            transport.close();
            // 已移除未使用的调度器关闭
            cacheManager.shutdown();
            listenerManager.shutdown();
            logger.info("ConfigCore closed");
        } catch (Exception e) {
            logger.error("Error closing ConfigCore", e);
        }
    }

    /**
     * 添加配置变更监听器
     */
    public void addConfigListener(String key, ConfigListener listener) {
        listenerManager.addListener(key, listener);
    }

    /**
     * 移除配置变更监听器
     */
    public void removeConfigListener(String key, ConfigListener listener) {
        listenerManager.removeListener(key, listener);
    }

    public String getEnv() {
        return env;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取传输层实例（供内部使用）
     */
    public ConfigTransport getTransport() {
        return transport;
    }
}