package com.xuantong.client.serializer;

import java.util.List;
import java.util.Map;

/**
 * 配置序列化接口
 */
public interface Serializer {
    /**
     * 序列化对象为字符串
     */
    String serialize(Object obj);

    /**
     * 反序列化字符串为对象
     */
    <T> T deserialize(String str, Class<T> clazz);
    <T> List<T> deserializeTolist(String str, Class<T> clazz);

    <T> T toBean(Object object, Class<T> clazz);
    /**
     * 反序列化为Map
     */
    <K, V> Map<K, V> deserializeMap(String str);

    /**
     * 默认JSON实现
     */
    static Serializer defaultSerializer() {
        return new JsonSerializer();
    }
}