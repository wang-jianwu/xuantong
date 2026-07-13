package cloud.xuantong.client.serializer;

import java.lang.reflect.Type;
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
    <T> List<T> deserializeToList(String str, Class<T> clazz);

    <T> T toBean(Object object, Class<T> clazz);
    /**
     * 反序列化为Map
     */
    <K, V> Map<K, V> deserializeMap(String str);

    /**
     * 反序列化为Map（带泛型类型信息，支持 Enum key）
     * @param keyType 键类型（如 MyEnum.class）
     * @param valueType 值类型（如 SomeObject.class）
     */
    <K, V> Map<K, V> deserializeMap(String str, Type keyType, Type valueType);

    /**
     * 默认JSON实现
     */
    static Serializer defaultSerializer() {
        return new JsonSerializer();
    }
}