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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 配置核心实现
 */
public class ConfigCore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCore.class);

    private final List<String> serverAddress;
    private final String primaryAppName;  // 主应用名称
    private final List<String> subscribedApps;  // 订阅的应用列表
    private final String env;
    private final ConfigTransport transport;
    private final ConfigCacheManager cacheManager;
    private final ConfigListenerManager listenerManager;
    private final Serializer serializer;
    private volatile boolean initialized = false;

    /**
     * 构造函数 - 单应用模式
     */
    public ConfigCore(List<String> serverAddress, String appName, String env, ConfigTransport transport) {
        this(serverAddress, appName, Collections.emptyList(), env, transport);
    }

    /**
     * 构造函数 - 支持多应用订阅
     */
    public ConfigCore(List<String> serverAddress, String primaryAppName, List<String> subscribedApps, String env, ConfigTransport transport) {
        this.serverAddress = serverAddress;
        this.primaryAppName = primaryAppName;
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
            logger.info("Initializing ConfigCore for {}/{} with subscribed apps: {}", primaryAppName, env, subscribedApps);

            // 注册配置变更监听器到传输层（监听主应用）
            List<String> allAppNames = getAllAppNames();
            transport.connect(serverAddress, allAppNames, env, configData -> {
                logger.info("监听到配置变更 {}/{}/{}", allAppNames, env, configData);
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
                    subscribedApps.size() + 1, getAllAppNames());
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

        // 2. 远程获取（从主应用）
        try {
            value = transport.fetch(primaryAppName, env, key);
            if (value != null) {
                cacheManager.batchUpdate(Collections.singletonMap(key, value));
                return value;
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch config '{}' from primary app {}", key, primaryAppName, e);
        }

        // 3. 从订阅应用中查找
        for (String appName : subscribedApps) {
            try {
                value = transport.fetch(appName, env, key);
                if (value != null) {
                    cacheManager.batchUpdate(Collections.singletonMap(key, value));
                    return value;
                }
            } catch (Exception e) {
                logger.debug("Failed to fetch config '{}' from subscribed app {}", key, appName, e);
            }
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
                List<String> allApps = getAllAppNames();
                // 多应用订阅模式 - 批量获取所有应用的配置
                String data = transport.fetchAllForApps(allApps, env);
                if (data != null) {
                    Map<String, String> allConfigs = serializer.deserializeMap(data);
                    cacheManager.batchUpdate(allConfigs);
                    logger.info("Loaded {} initial configs from {} apps: {}",
                            allConfigs.size(), allApps.size(), allApps);
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

    /**
     * 获取所有应用名称（主应用+订阅应用）
     */
    private List<String> getAllAppNames() {
        List<String> allApps = new ArrayList<>();
        allApps.add(primaryAppName);
        allApps.addAll(subscribedApps);
        return allApps;
    }
}