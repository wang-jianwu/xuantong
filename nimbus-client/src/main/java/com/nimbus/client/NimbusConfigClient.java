package com.nimbus.client;

import com.nimbus.client.listener.ConfigListener;

import java.util.List;
import java.util.function.Consumer;

/**
 * Nimbus配置客户端统一集成接口
 * 为框架集成提供简单的静态API
 */
public class NimbusConfigClient {

    private NimbusConfigClient() {
        // 工具类，禁止实例化
    }

    /**
     * 初始化配置客户端（多服务器地址）
     *
     * @param serverAddrs 服务器地址列表
     * @param appName     应用名称
     * @param env         环境
     * @return ConfigClientFactory实例
     */
    public static ConfigClientFactory init(List<String> serverAddrs, String appName, String env) {
        return ConfigClientFactory.init(serverAddrs, appName, env);
    }

    /**
     * 获取配置值
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public static String get(String key, String defaultValue) {
        return NimBusClient.get(key, defaultValue);
    }

    /**
     * 获取配置值（无默认值）
     *
     * @param key 配置键
     * @return 配置值，不存在返回null
     */
    public static String get(String key) {
        return get(key, null);
    }

    /**
     * 获取对象配置
     *
     * @param key   配置键
     * @param clazz 对象类型
     * @return 配置对象
     */
    public static <T> T getObject(String key, Class<T> clazz) {
        return NimBusClient.getObject(key, clazz);
    }

    /**
     * 获取对象列表配置
     *
     * @param key   配置键
     * @param clazz 对象类型
     * @return 对象列表
     */
    public static <T> List<T> getObjectList(String key, Class<T> clazz) {
        return NimBusClient.getObjectList(key, clazz);
    }

    /**
     * 添加配置变更监听器
     *
     * @param appName  应用名
     * @param env      环境
     * @param key      配置键
     * @param listener 监听器
     */
    public static void addListener(String appName, String env, String key, ConfigListener listener) {
        ConfigClientFactory.addStaticConfigListener(appName, env, key, listener);
    }

    /**
     * 添加配置变更监听器（Lambda表达式）
     *
     * @param appName  应用名
     * @param env      环境
     * @param key      配置键
     * @param listener 监听器函数
     */
    public static void addListener(String appName, String env, String key, Consumer<String> listener) {
        addListener(appName, env, key, (ConfigListener)
                event -> listener.accept(event.getNewValue()));
    }

    /**
     * 移除配置变更监听器
     *
     * @param appName  应用名
     * @param env      环境
     * @param key      配置键
     * @param listener 监听器
     */
    public static void removeListener(String appName, String env, String key, ConfigListener listener) {
        ConfigClientFactory.removeStaticConfigListener(appName, env, key, listener);
    }

    /**
     * 检查客户端是否已初始化
     *
     * @param appName 应用名
     * @param env     环境
     * @return 是否已初始化
     */
    public static boolean isInitialized(String appName, String env) {
        return ConfigClientFactory.isInitialized(appName, env);
    }

    /**
     * 关闭所有配置客户端
     */
    public static void closeAll() {
        ConfigClientFactory.closeAll();
    }
}