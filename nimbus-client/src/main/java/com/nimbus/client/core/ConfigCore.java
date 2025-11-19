package com.nimbus.client.core;

import com.nimbus.client.cache.ConfigCacheManager;
import com.nimbus.client.listener.ConfigListener;
import com.nimbus.client.listener.ConfigListenerManager;
import com.nimbus.client.model.ConfigChangeEvent;
import com.nimbus.client.serializer.Serializer;
import com.nimbus.client.transport.ConfigTransport;
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
    private final String appName;
    private final String env;
    private final ConfigTransport transport;
    private final ConfigCacheManager cacheManager;
    private final ConfigListenerManager listenerManager;
    private final Serializer serializer;
    private volatile boolean initialized = false;

    /**
     * 构造函数 - 支持依赖注入传输实现
     */
    public ConfigCore(List<String> serverAddress, String appName, String env, ConfigTransport transport) {
        this.serverAddress = serverAddress;
        this.appName = appName;
        this.env = env;
        this.cacheManager = new ConfigCacheManager();
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
            logger.info("Initializing ConfigCore for {}/{}", appName, env);
            // 注册配置变更监听器到传输层
            transport.connect(serverAddress, appName, env, configData -> {
                // 处理配置变更通知
                Map<String, String> newConfigs = serializer.deserializeMap(configData);
                if (newConfigs != null && !newConfigs.isEmpty()) {
                    newConfigs.forEach((key, value) -> listenerManager.fireEvent(new ConfigChangeEvent(key, value)));
                    handleConfigPush(newConfigs);
                }
            });
            // 加载初始配置
            loadInitialConfigs();

            initialized = true;
            logger.info("ConfigCore initialized successfully");
        } catch (Exception e) {
            logger.error("ConfigCore initialization failed", e);
            throw new RuntimeException("Failed to initialize ConfigCore", e);
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
            value = transport.fetch(appName, env, key);
            if (value != null) {
                cacheManager.batchUpdate(Collections.singletonMap(key, value));
                return value;
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch config '{}' from remote", key, e);
        }

        return defaultValue;
    }

    /**
     * 加载初始配置
     */
    private void loadInitialConfigs() {
        try {
            String data = transport.fetchAll(appName, env);
            Map<String, String> allConfigs = serializer.deserializeMap(data);
            cacheManager.batchUpdate(allConfigs);
            logger.info("Loaded {} initial configs", allConfigs.size());
        } catch (Exception e) {
            logger.error("Failed to load initial configs", e);
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

    // 保持接口完整性和未来扩展性
    public String getAppName() {
        return appName;
    }

    public String getEnv() {
        return env;
    }

    public boolean isInitialized() {
        return initialized;
    }
}