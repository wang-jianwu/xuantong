package com.xuantong.client;

import java.util.List;

/**
 * 静态门面类 - 提供简单的静态API访问方式
 */
public final class XuantongConfig {
    private static XuantongClient defaultClient = null;
    private static volatile boolean initialized = false;

    private XuantongConfig() {
        // 防止实例化
    }

    /**
     * 初始化配置客户端（静态方式）- 单例门面（单应用模式）
     */
    public static synchronized void init(List<String> serverAddrs, String appName, String env) {
        if (initialized) {
            throw new IllegalStateException("NimbusConfig already initialized. Use close() before reinitializing.");
        }
        if (defaultClient != null) {
            throw new IllegalStateException("NimbusConfig singleton instance already exists");
        }
        defaultClient = new XuantongClient(serverAddrs, appName, env);
        initialized = true;
    }

    /**
     * 初始化配置客户端（静态方式）- 支持多应用订阅
     */
    public static synchronized void init(List<String> serverAddrs, String primaryAppName, List<String> subscribedApps, String env) {
        if (initialized) {
            throw new IllegalStateException("NimbusConfig already initialized. Use close() before reinitializing.");
        }
        if (defaultClient != null) {
            throw new IllegalStateException("NimbusConfig singleton instance already exists");
        }
        defaultClient = new XuantongClient(serverAddrs, primaryAppName, subscribedApps, env);
        initialized = true;
    }

    /**
     * 获取字符串配置值
     */
    public static String get(String key) {
        checkInitialized();
        return defaultClient.get(key);
    }

    /**
     * 获取字符串配置值（带默认值）
     */
    public static String get(String key, String defaultValue) {
        checkInitialized();
        return defaultClient.get(key, defaultValue);
    }

    /**
     * 获取对象配置值
     */
    public static <T> T getObject(String key, Class<T> clazz) {
        checkInitialized();
        return defaultClient.getObject(key, clazz);
    }

    /**
     * 获取对象列表配置值
     */
    public static <T> List<T> getObjectList(String key, Class<T> clazz) {
        checkInitialized();
        return defaultClient.getObjectList(key, clazz);
    }

    /**
     * 设置默认客户端实例（供NimBusClient内部使用）
     */
    static synchronized void setClient(XuantongClient client) {
        if (defaultClient != null && defaultClient != client) {
            throw new IllegalStateException("Default client already set");
        }
        defaultClient = client;
        initialized = true;
    }

    /**
     * 关闭配置客户端并清理单例实例
     */
    public static synchronized void close() {
        if (defaultClient != null) {
            defaultClient.close();
            defaultClient = null;
            initialized = false;
        }
    }

    /**
     * 检查是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    private static void checkInitialized() {
        if (!initialized || defaultClient == null) {
            throw new IllegalStateException("NimbusConfig not initialized. Please call NimbusConfig.init() first.");
        }
    }
}