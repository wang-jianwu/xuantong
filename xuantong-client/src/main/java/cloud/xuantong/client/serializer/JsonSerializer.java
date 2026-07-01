package cloud.xuantong.client.serializer;

import cloud.xuantong.client.exception.XuantongException;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.codec.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JSON序列化实现 (基于Snack4 v4.x)
 */
public class JsonSerializer implements Serializer {
    private static final Logger logger = LoggerFactory.getLogger(JsonSerializer.class);
    private final Options options = Options.of();

    @Override
    public String serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return ONode.ofBean(obj, options).toJson();
        } catch (Exception e) {
            logger.error("Serialize failed", e);
            throw new XuantongException("Serialize failed", e);
        }
    }

    @Override
    public <T> T deserialize(String str, Class<T> clazz) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        try {
            return ONode.ofJson(str, options).toBean(clazz);
        } catch (Exception e) {
            logger.error("Deserialize failed, str: {}", str, e);
            throw new XuantongException("Deserialize failed", e);
        }
    }

    @Override
    public <T> List<T> deserializeToList(String str, Class<T> clazz) {
        return str != null && !str.trim().isEmpty() ? ONode.deserialize(str, new TypeRef<List<T>>() {
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

    @Override
    public <T> T toBean(Object object, Class<T> clazz) {
        if (object == null) {
            return null;
        }
        return ONode.ofBean(object, options).toBean(clazz);
    }

    @Override
    public <K, V> Map<K, V> deserializeMap(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        try {
            // 处理可能的双引号转义JSON字符串（当 JSON 被字符串包裹时）
            String jsonStr = str.trim();
            if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                // 用 JSON 解析器解开外层字符串，而不是手动字符串替换
                jsonStr = ONode.ofJson(jsonStr, options).toJson();
            }

            // 使用TypeRef 确保正确的泛型类型反序列化
            TypeRef<Map<K, V>> typeRef = new TypeRef<Map<K, V>>() {
            };
            return ONode.deserialize(jsonStr, typeRef);
        } catch (Exception e) {
            logger.error("Deserialize map failed, str: \"{}\"", str, e);
            throw new XuantongException("Deserialize map failed", e);
        }
    }

}