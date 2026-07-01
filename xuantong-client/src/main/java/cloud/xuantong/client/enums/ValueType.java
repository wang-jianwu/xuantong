package cloud.xuantong.client.enums;

/**
 * author 封于修
 * date 2025/12/11 23:11
 */
public enum ValueType {
    STRING,
    BOOLEAN,
    NUMBER,
    JSON;

    /**
     * 根据字段的 Java 类型自动推断 ValueType
     * <ul>
     *     <li>boolean/Boolean → BOOLEAN</li>
     *     <li>int/long/float/double/short/byte 及包装类 → NUMBER</li>
     *     <li>String → STRING</li>
     *     <li>List/Map/数组/其他对象 → JSON</li>
     * </ul>
     */
    public static ValueType inferFromClass(Class<?> fieldType) {
        if (fieldType == boolean.class || fieldType == Boolean.class) {
            return BOOLEAN;
        }
        if (fieldType == int.class || fieldType == Integer.class
                || fieldType == long.class || fieldType == Long.class
                || fieldType == float.class || fieldType == Float.class
                || fieldType == double.class || fieldType == Double.class
                || fieldType == short.class || fieldType == Short.class
                || fieldType == byte.class || fieldType == Byte.class) {
            return NUMBER;
        }
        if (fieldType == String.class) {
            return STRING;
        }
        return JSON;
    }
}
