package com.nimbus.client;


import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * 配置客户端接口 - 纯配置获取功能
 */
public abstract class NimBusClient implements AutoCloseable {

    private static volatile NimBusClient defaultInstance;

    /**
     * 设置默认配置客户端实例
     */
    static void setDefaultInstance(NimBusClient instance) {
        defaultInstance = instance;
    }

    /**
     * 获取字符串配置值
     */
    public static String get(String key, String defaultValue) {
        if (defaultInstance == null) {
            throw new IllegalStateException("ConfigClient not initialized. Please call ConfigClientFactory.init() first.");
        }
        // 直接访问本地缓存
        return defaultInstance.getDirect(key, defaultValue);
    }

    /**
     * 直接从本地缓存获取配置值（避免递归）
     */
    protected abstract String getDirect(String key, String defaultValue);

    /**
     * 获取对象配置值
     */
    public static <T> T getObject(String key, Class<T> clazz) {
        String json = get(key, null);
        return json != null ? ONode.deserialize(json, clazz) : null;
    }

    /**
     * 获取对象列表配置值
     */
    public static <T> List<T> getObjectList(String key, Class<T> clazz) {
        String json = get(key, null);
        return json != null ? ONode.deserialize(json, new TypeRef<List<T>>() {
            @Override
            public Type getType() {
                return new ParameterizedType() {


                    @Override
                    public Type[] getActualTypeArguments() {
                        return new Type[]{clazz};
                    }

                    @Override
                    public Type getRawType() {
                        return List.class;
                    }

                    @Override
                    public Type getOwnerType() {
                        return null;
                    }
                };
            }
        }) : Collections.emptyList();
    }
}