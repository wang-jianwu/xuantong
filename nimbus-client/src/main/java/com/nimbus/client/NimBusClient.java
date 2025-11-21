package com.nimbus.client;

import com.nimbus.client.core.ConfigCore;
import com.nimbus.client.listener.ConfigListener;
import com.nimbus.client.serializer.Serializer;
import com.nimbus.client.transport.impl.SocketDTransport;
import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * 配置客户端接口 - 实例化客户端（用于依赖注入）
 */
public class NimBusClient implements AutoCloseable {

    private final ConfigCore configCore;
    private static NimBusClient defaultInstance = null;

    /**
     * 构造函数 - 自动注册为默认实例（如果还没有默认实例）
     */
    public NimBusClient(List<String> serverAddrs, String appName, String env) {
        this.configCore = new ConfigCore(serverAddrs, appName, env, new SocketDTransport());
        registerAsDefault();
    }

    /**
     * 注册当前实例为默认实例
     */
    private synchronized void registerAsDefault() {
        if (defaultInstance == null) {
            defaultInstance = this;
            NimbusConfig.setClient(this);
        }
    }

    /**
     * 获取默认实例（如果存在）
     */
    public static NimBusClient getDefault() {
        return defaultInstance;
    }

    /**
     * 获取字符串配置值
     */
    public String get(String key) {
        return configCore.get(key, null);
    }

    /**
     * 获取字符串配置值（带默认值）
     */
    public String get(String key, String defaultValue) {
        return configCore.get(key, defaultValue);
    }

    /**
     * 获取对象配置值
     */
    public <T> T getObject(String key, Class<T> clazz) {
        String json = get(key, null);
        return Serializer.defaultSerializer().deserialize(json, clazz);
    }

    /**
     * 获取对象列表配置值
     */
    public <T> List<T> getObjectList(String key, Class<T> clazz) {
        String json = get(key, null);
        return Serializer.defaultSerializer().deserializeTolist(json, clazz);
        }

    /**
     * 添加配置变更监听器
     */
    public void addListener(String key, ConfigListener listener) {
        configCore.addConfigListener(key, listener);
    }

    /**
     * 移除配置变更监听器
     */
    public void removeListener(String key, ConfigListener listener) {
        configCore.removeConfigListener(key, listener);
    }

    /**
     * 关闭客户端
     */
    @Override
    public void close() {
        configCore.close();
    }
}