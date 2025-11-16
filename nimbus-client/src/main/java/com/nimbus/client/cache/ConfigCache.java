package com.nimbus.client.cache;

import java.util.Map;

/**
 * 配置缓存接口
 */
public interface ConfigCache {
    /**
     * 获取指定key的缓存值
     */
    String get(String key);

    /**
     * 设置key-value缓存
     */
    void put(String key, String value);

    /**
     * 移除指定key的缓存
     */
    void remove(String key);

    /**
     * 清空所有缓存
     */
    void clear();

    /**
     * 获取所有缓存
     */
    Map<String, String> getAll();
}