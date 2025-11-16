package com.nimbus.client.serializer;

import com.nimbus.client.metrics.ConfigMetrics;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.codec.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * JSON序列化实现 (基于Snack4 v4.x - 正确使用方式)
 */
public class JsonSerializer implements Serializer {
    private static final Logger logger = LoggerFactory.getLogger(JsonSerializer.class);
    private final Options options = Options.of();

    @Override
    public String serialize(Object obj) {
        long startTime = System.currentTimeMillis();
        try {
            String result = ONode.ofBean(obj, options).toJson();
            long duration = System.currentTimeMillis() - startTime;
            ConfigMetrics.getInstance().recordResponseTime("serialize", duration);
            return result;
        } catch (Exception e) {
            logger.error("Serialize failed", e);
            ConfigMetrics.getInstance().recordParseError();
            throw new RuntimeException("Serialize failed", e);
        }
    }

    @Override
    public <T> T deserialize(String str, Class<T> clazz) {
        long startTime = System.currentTimeMillis();
        try {
            // Snack4正确反序列化方式
            T result = ONode.ofJson(str, options).toBean(clazz);
            long duration = System.currentTimeMillis() - startTime;
            ConfigMetrics.getInstance().recordResponseTime("deserialize", duration);
            return result;
        } catch (Exception e) {
            logger.error("Deserialize failed, str: {}", str, e);
            ConfigMetrics.getInstance().recordParseError();
            throw new RuntimeException("Deserialize failed", e);
        }
    }

    @Override
    public <K, V> Map<K, V> deserializeMap(String str) {
        long startTime = System.currentTimeMillis();
        try {
            // 处理可能的双引号转义JSON字符串
            String jsonStr = str.trim();
            if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                jsonStr = jsonStr.substring(1, jsonStr.length() - 1)
                              .replace("\\\"", "\"");
            }

            // 使用TypeRef确保正确的泛型类型反序列化
            TypeRef<Map<K, V>> typeRef = new TypeRef<Map<K, V>>() {};
            Map<K, V> result = ONode.deserialize(jsonStr, typeRef);

            long duration = System.currentTimeMillis() - startTime;
            ConfigMetrics.getInstance().recordResponseTime("deserializeMap", duration);
            return result;
        } catch (Exception e) {
            logger.error("Deserialize map failed, str: \"{}\"", str, e);
            ConfigMetrics.getInstance().recordParseError();
            throw new RuntimeException("Deserialize map failed", e);
        }
    }

}