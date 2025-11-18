package com.nimbus.client;

import com.nimbus.client.cache.ConfigCacheManager;
import com.nimbus.client.circuitbreaker.CircuitBreaker;
import com.nimbus.client.listener.ConfigListener;
import com.nimbus.client.listener.ConfigListenerManager;
import com.nimbus.client.model.ConfigChangeEvent;
import com.nimbus.client.snapshot.LocalSnapshotManager;
import com.nimbus.client.transport.ConfigTransport;
import com.nimbus.client.transport.SocketDTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 配置客户端工厂，负责连接管理和刷新逻辑
 */
public class ConfigClientFactory {
    private static final Logger logger = LoggerFactory.getLogger(ConfigClientFactory.class);
    private static final ConcurrentHashMap<String, ConfigClientFactory> instances = new ConcurrentHashMap<>();

    private final ConfigTransport transport;
    private final CircuitBreaker circuitBreaker;
    private final LocalSnapshotManager snapshotManager;
    private final ScheduledExecutorService scheduler;
    private volatile boolean useSnapshotMode = false;
    private SimpleConfigClient activeClient; // 当前活动的客户端实例
    private final ConfigListenerManager listenerManager = new ConfigListenerManager();

    private ConfigClientFactory(List<String> serverAddrs, String appName, String env) {
        this.snapshotManager = new LocalSnapshotManager(appName, env);
        this.circuitBreaker = new CircuitBreaker(5, 30000, 3);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.transport = new SocketDTransport(serverAddrs, appName, env, this);
    }


    /**
     * 全局初始化所有配置客户端（支持多个服务器地址）
     *
     * @return ConfigClientFactory实例，可用于链式调用
     */
    public static ConfigClientFactory init(List<String> serverAddrs, String appName, String env) {
        ConfigClientFactory instance = getInstance(serverAddrs, appName, env);
        instance.init();
        return instance;
    }

    /**
     * 检查是否已初始化（用于框架健康检查）
     */
    public static boolean isInitialized(String appName, String env) {
        String key = appName + ":" + env;
        return instances.containsKey(key) && instances.get(key).isInitialized();
    }

    /**
     * 全局关闭所有配置客户端
     */
    public static void closeAll() {
        instances.values().forEach(ConfigClientFactory::close);
        instances.clear();
    }

    private static ConfigClientFactory getInstance(List<String> serverAddrs, String appName, String env) {
        String key = appName + ":" + env + ":" + String.join(",", serverAddrs);
        return instances.computeIfAbsent(key, k -> new ConfigClientFactory(serverAddrs, appName, env));
    }

    private volatile boolean initializing = false;
    private volatile boolean initialized = false;
    private final CountDownLatch initializationLatch = new CountDownLatch(1);

    private void init() {
        if (initializing || initialized) {
            return;
        }

        initializing = true;
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                if (circuitBreaker.allowRequest()) {
                    logger.info("Starting async initialization...");
                    transport.connect();

                    startRefreshThread();
                    circuitBreaker.recordSuccess();

                    NimBusClient.setDefaultInstance(createConfigClient());
                    initialized = true;
                    logger.info("Async initialization completed successfully");
                } else {
                    fallbackToSnapshot();
                    initialized = true;
                }
            } catch (Exception e) {
                logger.error("Async initialization failed", e);
                circuitBreaker.recordFailure();
                fallbackToSnapshot();
                initialized = true; // 即使失败也标记为完成
            } finally {
                initializing = false;
                initializationLatch.countDown(); // Signal completion here
            }
        });
    }


    /**
     * 等待初始化完成（可选阻塞调用）
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否初始化成功
     */
    private boolean waitForInitialization(long timeoutMs) {
        try {
            if (initialized) {
                return !useSnapshotMode;
            }
            return initializationLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && !useSnapshotMode;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }


    private void fallbackToSnapshot() {
        logger.warn("Falling back to local snapshot due to connection issues");
        useSnapshotMode = true;
    }

    private void startRefreshThread() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (useSnapshotMode && circuitBreaker.allowRequest()) {
                    tryRecovery();
                } else if (!useSnapshotMode && circuitBreaker.allowRequest()) {
                    checkForChanges();
                }
            } catch (Exception e) {
                logger.error("Config refresh error", e);
                circuitBreaker.recordFailure();
                if (circuitBreaker.isOpen()) {
                    fallbackToSnapshot();
                }
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void tryRecovery() {
        try {
            transport.connect();
            useSnapshotMode = false;
            circuitBreaker.recordSuccess();
            logger.info("Successfully recovered from snapshot mode");
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            logger.warn("Recovery attempt failed, staying in snapshot mode");
        }
    }

    private void checkForChanges() {
        try {
            Map<String, String> changes = transport.fetchChanges();
            if (!changes.isEmpty()) {
                logger.info("Detected {} potential config changes: {}", changes.size(), changes.keySet());

                // 过滤出实际发生变化的配置
                Map<String, String> realChanges = new HashMap<>();
                changes.forEach((key, newValue) -> {
                    String oldValue = getActiveClientLocalCacheValue(key);
                    if (!newValue.equals(oldValue)) {
                        realChanges.put(key, newValue);
                    }
                });

                if (realChanges.isEmpty()) {
                    logger.debug("No actual config value changes, skip update");
                    return;
                }

                logger.info("Processing {} actual config changes: {}", realChanges.size(), realChanges.keySet());

                // 记录变更详情（DEBUG级别）
                if (logger.isDebugEnabled()) {
                    realChanges.forEach((key, newValue) -> {
                        String oldValue = getActiveClientLocalCacheValue(key);
                        logger.debug("Config changed - Key: {}, Old: {}, New: {}",
                                key, oldValue, newValue);
                    });
                }

                activeClient.handleConfigPush(realChanges);
            } else {
                logger.debug("No config changes detected");
            }
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            logger.warn("Failed to check for changes", e);
        }
    }

    public NimBusClient createConfigClient() {
        this.activeClient = new SimpleConfigClient(this);
        return activeClient;
    }

    public boolean isInitialized() {
        return initialized && (!useSnapshotMode || snapshotManager.hasValidSnapshot());
    }

    /**
     * 更新本地快照
     *
     * @param configs 最新的配置集合
     */
    public void updateSnapshot(Map<String, String> configs) {
        try {
            snapshotManager.saveSnapshot(configs);
            logger.info("Snapshot updated with {} configs", configs.size());
        } catch (Exception e) {
            logger.error("Failed to update snapshot", e);
        }
    }

    /**
     * 处理配置推送更新
     *
     * @param newConfigs 新的配置集合
     */
    public void handleConfigPush(Map<String, String> newConfigs) {
        if (newConfigs == null || newConfigs.isEmpty()) {
            return;
        }

        // 过滤出实际发生变化的配置
        Map<String, String> realChanges = new HashMap<>();
        newConfigs.forEach((key, newValue) -> {
            String oldValue = getActiveClientLocalCacheValue(key);
            if (!newValue.equals(oldValue)) {
                realChanges.put(key, newValue);
            }
        });

        if (realChanges.isEmpty()) {
            logger.debug("No actual config changes detected, skip update");
            return;
        }

        if (activeClient != null) {
            activeClient.handleConfigPush(realChanges);
            triggerConfigChangeEvents(realChanges);
        } else {
            logger.warn("No active client available to handle config push");
            updatePartialSnapshot(realChanges);
        }
    }

    /**
     * 触发配置变更事件
     *
     * @param changedConfigs 变更的配置集合
     */
    private void triggerConfigChangeEvents(Map<String, String> changedConfigs) {
        changedConfigs.forEach((key, newValue) -> {
            String oldValue = getActiveClientLocalCacheValue(key);
            ConfigChangeEvent event = new ConfigChangeEvent(key, newValue, oldValue, new Date(), "system");
            listenerManager.fireEvent(event);

            // 增强日志：区分新增和修改
            if (oldValue == null) {
                logger.info("Config added - Key: {}, Value: {}", key, newValue);
            } else {
                logger.info("Config updated - Key: {}, Old: {}, New: {}",
                        key, oldValue, newValue);
            }
        });
    }

    /**
     * 获取活跃客户端本地缓存中的配置值
     *
     * @param key 配置键
     * @return 配置值，如果不存在返回null
     */
    private String getActiveClientLocalCacheValue(String key) {
        if (activeClient != null) {
            return activeClient.cacheManager.get(key);
        }
        return null;
    }

    /**
     * 添加配置变更监听器
     *
     * @param key      配置键
     * @param listener 监听器实例
     */
    public void addConfigListener(String key, ConfigListener listener) {
        listenerManager.addListener(key, listener);
    }
    /**
     * 移除配置变更监听器
     * @param key 配置键
     * @param listener 监听器实例
     */
    public void removeConfigListener(String key, ConfigListener listener) {
        listenerManager.removeListener(key, listener);
    }

    /**
     * 静态方法移除配置变更监听器
     * @param appName 应用名
     * @param env 环境
     * @param key 配置键
     * @param listener 监听器实例
     * @throws IllegalStateException 如果客户端未初始化
     */
    public static void removeStaticConfigListener(String appName, String env, String key, ConfigListener listener) {
        String instanceKey = appName + ":" + env;
        ConfigClientFactory instance = instances.get(instanceKey);
        if (instance == null) {
            throw new IllegalStateException("ConfigClient not initialized for app: " + appName + ", env: " + env);
        }
        instance.removeConfigListener(key, listener);
    }

    /**
     * 获取监听器管理器（用于监控和统计）
     * @return 监听器管理器实例
     */
    public ConfigListenerManager getListenerManager() {
        return listenerManager;
    }

    /**
     * 静态方法添加配置变更监听器
     * @param appName 应用名
     * @param env 环境
     * @param key 配置键
     * @param listener 监听器实例
     * @throws IllegalStateException 如果客户端未初始化
     */
    public static void addStaticConfigListener(String appName, String env, String key, ConfigListener listener) {
        String instanceKey = appName + ":" + env;
        ConfigClientFactory instance = instances.get(instanceKey);
        if (instance == null) {
            throw new IllegalStateException("ConfigClient not initialized for app: " + appName + ", env: " + env);
        }
        instance.addConfigListener(key, listener);
    }

    /**
     * 静态方法等待指定应用的配置客户端初始化完成
     * @param appName 应用名
     * @param env 环境
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否初始化成功（非快照模式）
     * @throws IllegalStateException 如果客户端未找到
     */
    public static boolean waitForStaticInitialization(String appName, String env, long timeoutMs) {
        String instanceKey = appName + ":" + env;
        ConfigClientFactory instance = instances.get(instanceKey);
        if (instance == null) {
            throw new IllegalStateException("ConfigClient not found for app: " + appName + ", env: " + env);
        }
        return instance.waitForInitialization(timeoutMs);
    }

    /**
     * 增量更新本地快照，只更新变更的配置
     *
     * @param changedConfigs 变更的配置集合
     */
    public void updatePartialSnapshot(Map<String, String> changedConfigs) {
        try {
            // 直接调用snapshotManager的增量更新方法
            snapshotManager.updateSnapshot(changedConfigs);
            logger.info("Snapshot partially updated with {} changed configs", changedConfigs.size());
        } catch (Exception e) {
            logger.error("Failed to partially update snapshot", e);
            // 失败时回退到全量更新
            try {
                Map<String, String> fullConfigs = snapshotManager.loadSnapshot();
                fullConfigs.putAll(changedConfigs);
                snapshotManager.saveSnapshot(fullConfigs);
                logger.warn("Fallback to full snapshot update after partial update failed");
            } catch (Exception fallbackError) {
                logger.error("Fallback snapshot update also failed", fallbackError);
                throw new RuntimeException("Partial snapshot update failed and fallback failed", e);
            }
        }
    }

    public void close() {
        try {
            // 1. 关闭传输层
            transport.close();

            // 2. 关闭调度器
            scheduler.shutdown();

            // 3. 关闭监听器管理器
            listenerManager.shutdown();

            // 4. 清理资源
            listenerManager.clear();

            logger.info("ConfigClientFactory closed successfully");
        } catch (Exception e) {
            logger.error("Error while closing ConfigClientFactory", e);
        }
    }

    // 内部简单配置客户端实现
    private static class SimpleConfigClient extends NimBusClient {
        private final ConfigClientFactory factory;
        private final ConfigCacheManager cacheManager;

        SimpleConfigClient(ConfigClientFactory factory) {
            this.factory = factory;
            this.cacheManager = new ConfigCacheManager();
            // 初始化时加载配置
            loadInitialConfigs();
        }

        private void loadInitialConfigs() {
            try {
                if (factory.useSnapshotMode) {
                    Map<String, String> snapshotConfigs = factory.snapshotManager.loadSnapshot();
                    cacheManager.batchUpdate(snapshotConfigs);
                    logger.info("Loaded {} configs from snapshot", snapshotConfigs.size());
                } else {
                    Map<String, String> remoteConfigs = factory.transport.fetchAll();
                    cacheManager.batchUpdate(remoteConfigs);
                    logger.info("Loaded {} configs from remote server", remoteConfigs.size());

                    // 保存快照以便后续使用
                    factory.snapshotManager.saveSnapshot(remoteConfigs);
                }
            } catch (Exception e) {
                logger.warn("Failed to load initial configs", e);
            }
        }

        // 处理配置推送更新并同步快照
        public void handleConfigPush(Map<String, String> newConfigs) {
            // 使用CacheManager批量更新
            cacheManager.batchUpdate(newConfigs);

            // 通知factory进行增量快照更新
            try {
                factory.updatePartialSnapshot(newConfigs);
                logger.info("Config push handled and snapshot updated key:{}", newConfigs.entrySet());
            } catch (Exception e) {
                logger.error("Failed to update snapshot after config push", e);
            }
        }

        @Override
        protected String getDirect(String key, String defaultValue) {
            // 首先尝试从缓存获取
            String value = cacheManager.get(key, defaultValue);
            if (value == null && !factory.useSnapshotMode) {
                try {
                    // 缓存没有，且不在快照模式，尝试远程获取单个key
                    value = factory.transport.fetch(key);
                    if (value != null && !value.trim().isEmpty()) {
                        cacheManager.batchUpdate(Collections.singletonMap(key, value)); // 更新缓存
                        logger.debug("Fetched '{}' from remote server", key);
                    } else {
                        logger.debug("Key '{}' not found in remote server", key);
                    }
                } catch (Exception e) {
                    logger.error("Failed to fetch config '{}' from remote server", key, e);
                }
            }
            return value;
        }

        @Override
        public void close() {
            // 关闭缓存管理器
            cacheManager.shutdown();
        }
    }
}