package cloud.xuantong.solon.integration;

import cloud.xuantong.client.XuantongClient;
import cloud.xuantong.client.XuantongConfig;
import cloud.xuantong.client.annotation.ConfigValue;
import cloud.xuantong.client.enums.ValueType;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.serializer.Serializer;
import org.noear.solon.core.BeanInjector;
import org.noear.solon.core.VarHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * author 封于修
 * date 2025/12/14 17:11
 * ConfigValue注解注入器
 */
public class XuantongConfigValueInjector implements BeanInjector<ConfigValue> {
    private static final Logger logger = LoggerFactory.getLogger(XuantongConfigValueInjector.class);

    @Override
    public void doInject(VarHolder varH, ConfigValue anno) {
        try {
            // 构建配置键：使用注解值或字段全名
            String dataId = anno.value().isEmpty() ? varH.getFullName() : anno.value();

            // 获取配置值
            String value = XuantongConfig.get(dataId, null);

            // 校验必需性
            if (anno.required() && value == null) {
                throw new XuantongException("Required config '" + dataId + "' not found");
            }

            // 设置默认值
            if (value == null && !anno.defaultValue().isEmpty()) {
                value = anno.defaultValue();
            }

            if (value != null) {
                // 类型转换并直接设置到 VarHolder
                Object convertedValue = convertValue(value, varH);
                varH.setValue(convertedValue);
            }

            // 注册自动刷新监听器
            if (anno.autoRefresh()) {
                registerAutoRefreshListener(varH, anno, dataId);
            }

            logger.debug("Injected @ConfigValue field: {}", dataId);
        } catch (Exception e) {
            logger.error("Failed to inject @ConfigValue field: {}", anno.value(), e);
            throw e;
        }
    }

    private Object convertValue(String value, VarHolder varH) {
        try {
            if (value == null) return null;

            Class<?> targetType = varH.getDependencyType();
            ValueType valueType = ValueType.inferFromClass(targetType);
            // 只有 String 类型允许空值，其他类型空串必须校验失败
            if (value.isEmpty() && valueType != ValueType.STRING) {
                throw new XuantongException("Empty config value is not allowed for non-String type: " + targetType);
            }

            switch (valueType) {
                case BOOLEAN:
                    return Boolean.parseBoolean(value);
                case NUMBER:
                    if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(value);
                    if (targetType == long.class || targetType == Long.class) return Long.parseLong(value);
                    if (targetType == float.class || targetType == Float.class) return Float.parseFloat(value);
                    if (targetType == double.class || targetType == Double.class) return Double.parseDouble(value);
                    if (targetType == short.class || targetType == Short.class) return Short.parseShort(value);
                    if (targetType == byte.class || targetType == Byte.class) return Byte.parseByte(value);
                    return Long.parseLong(value);
                case JSON:
                    if (List.class.isAssignableFrom(targetType)) {
                        Class<?> componentType = getComponentType(varH);
                        return Serializer.defaultSerializer().deserializeToList(value, componentType);
                    }
                    if (Map.class.isAssignableFrom(targetType)) {
                        Type[] mapTypes = getMapGenericTypes(varH);
                        return Serializer.defaultSerializer().deserializeMap(value, mapTypes[0], mapTypes[1]);
                    }
                    return Serializer.defaultSerializer().deserialize(value, targetType);
                case STRING:
                default:
                    return value;
            }
        } catch (Exception e) {
            throw new XuantongException("Failed to convert config value", e);
        }
    }

    private Class<?> getComponentType(VarHolder varH) {
        Type genericType = varH.getGenericType();
        if (genericType instanceof ParameterizedType) {
            Type[] typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
            if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                return (Class<?>) typeArguments[0];
            }
        }
        return Object.class;
    }

    /**
     * 提取 Map 的泛型类型 [keyType, valueType]
     * 如 Map<MyEnum, SomeObject> → [MyEnum.class, SomeObject.class]
     */
    private Type[] getMapGenericTypes(VarHolder varH) {
        Type genericType = varH.getGenericType();
        if (genericType instanceof ParameterizedType) {
            Type[] typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
            if (typeArguments.length == 2) {
                return typeArguments;
            }
        }
        return new Type[]{String.class, Object.class};
    }

    private void registerAutoRefreshListener(VarHolder varH, ConfigValue configValue, String dataId) {
        try {
            XuantongClient client = XuantongClient.getDefault();
            if (client == null) {
                logger.warn("XuantongClient not initialized, skip auto-refresh for: {}", dataId);
                return;
            }
            client.addListener(dataId, event -> {
                try {
                    String newValue = event.getNewValue();

                    // newValue="" 是"配置更新为空串"，不是删除，应直接设置
                    String effectiveValue;
                    if (newValue != null) {
                        effectiveValue = newValue; // 包括 ""（空串也是有效配置值，仅 String 类型允许）
                    } else {
                        // 配置已删除，回退到注解默认值
                        effectiveValue = configValue.defaultValue();
                        if (effectiveValue.isEmpty()) {
                            effectiveValue = null; // 空默认值 → 无默认值
                        }
                    }

                    if (effectiveValue == null) {
                        if (configValue.required()) {
                            logger.warn("Config deleted but field is required, keeping old value: {}", dataId);
                            return;
                        }
                        varH.setValue(null);
                        logger.info("Auto-refreshed field: {} to null (config deleted)", dataId);
                        return;
                    }

                    Object convertedValue = convertValue(effectiveValue, varH);
                    varH.setValue(convertedValue);
                    logger.info("Auto-refreshed field: {} to new value", dataId);
                } catch (Exception e) {
                    logger.error("Failed to auto-refresh field: {}", dataId, e);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to register auto-refresh listener for field: {}", dataId, e);
        }
    }
}
