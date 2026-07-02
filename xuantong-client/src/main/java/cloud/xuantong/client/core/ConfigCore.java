package cloud.xuantong.client.core;

import cloud.xuantong.client.cache.ConfigCacheManager;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.listener.ConfigListenerManager;
import cloud.xuantong.client.model.ConfigChangeEvent;
import cloud.xuantong.client.serializer.Serializer;
import cloud.xuantong.client.transport.ConfigTransport;
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
    private final String secretKey;
    private final ConfigTransport transport;
    private final ConfigCacheManager cacheManager;
    private final ConfigListenerManager listenerManager;
    private final Serializer serializer;
    private volatile boolean initialized = false;

    /**
     * 构造函数 - 支持多应用订阅
     */
    public ConfigCore(List<String> serverAddress, List<String> subscribedApps, String env, String secretKey, ConfigTransport transport) {
        this.serverAddress = serverAddress;
        this.subscribedApps = subscribedApps != null ? subscribedApps : Collections.emptyList();
        this.env = env;
        this.secretKey = secretKey != null ? secretKey : "";
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
            logger.info("Initializing ConfigCore for {} with subscribed apps: {}", env, subscribedApps);

            // 注册配置变更监听器到传输层（监听主应用）
            transport.connect(serverAddress, subscribedApps, env, secretKey, configData -> {
                // 处理配置变更通知
                Map<String, String> newConfigs = serializer.deserializeMap(configData);
                if (newConfigs != null && !newConfigs.isEmpty()) {
                    logger.info("收到配置变更: {} 个配置项", newConfigs.size());
                    // handleConfigPush 内部做 diff 对比并触发监听器，无需在此重复触发
                    handleConfigPush(newConfigs);
                }
            });

            // 注册重连回调：重连成功后重新拉取全量配置，防止断线期间的变更丢失
            transport.setOnReconnect(this::reloadConfigs);

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
     * <p>
     * value 为 null 表示配置已被删除，需从缓存中移除而非写入 null
     * （ConcurrentHashMap 不允许 null value，直接 put 会抛 NPE）
     */
    public void handleConfigPush(Map<String, String> newConfigs) {
        if (newConfigs == null || newConfigs.isEmpty()) {
            return;
        }

        // 分离：更新 vs 删除
        Map<String, String> updates = new java.util.HashMap<>();
        java.util.List<String> deletions = new java.util.ArrayList<>();

        for (Map.Entry<String, String> entry : newConfigs.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue();
            String oldValue = cacheManager.get(key);

            if (newValue == null) {
                // 服务端删除：如果本地有这个 key，需要移除
                if (oldValue != null) {
                    deletions.add(key);
                }
            } else {
                // 服务端更新：值不同才视为变更
                if (!java.util.Objects.equals(oldValue, newValue)) {
                    updates.put(key, newValue);
                }
            }
        }

        if (updates.isEmpty() && deletions.isEmpty()) {
            logger.debug("Received push but no actual changes detected");
            return;
        }

        // 更新缓存
        if (!updates.isEmpty()) {
            cacheManager.batchUpdate(updates);
        }
        // 删除缓存
        for (String key : deletions) {
            cacheManager.remove(key);
        }

        logger.info("Processed config push: {} updated, {} deleted / {} received",
                updates.size(), deletions.size(), newConfigs.size());

        // 对更新的 key 触发监听器
        updates.forEach((key, value) -> listenerManager.fireEvent(new ConfigChangeEvent(key, value)));
        // 对删除的 key 也触发监听器（value 为 null，让订阅者感知删除）
        deletions.forEach(key -> listenerManager.fireEvent(new ConfigChangeEvent(key, null)));
    }

    /**
     * 重连后重新拉取全量配置（与 loadInitialConfigs 不同：检测变更并触发监听器）
     * <p>
     * 断线期间服务端可能删除了某些配置，全量拉取结果中不包含这些 key。
     * 需要对比本地缓存，将本地有但服务端没有的 key 主动删除，防止过期缓存残留。
     */
    private void reloadConfigs() {
        try {
            String data = transport.fetchAllForApps(subscribedApps, env);
            if (data == null) return;
            Map<String, String> allConfigs = serializer.deserializeMap(data);
            if (allConfigs == null) return;

            // 检测断线期间被删除的 key：本地有但服务端全量中没有
            Map<String, String> localCache = cacheManager.getAll();
            for (String localKey : localCache.keySet()) {
                if (!allConfigs.containsKey(localKey)) {
                    cacheManager.remove(localKey);
                    listenerManager.fireEvent(new ConfigChangeEvent(localKey, null));
                    logger.info("Detected deleted config after reconnect: {}", localKey);
                }
            }

            // 通过 handleConfigPush 统一做 diff + 缓存更新 + 监听器触发
            handleConfigPush(allConfigs);
            logger.info("Config reload after reconnect completed: {} configs", allConfigs.size());
        } catch (Exception e) {
            logger.warn("Failed to reload configs after reconnect", e);
        }
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