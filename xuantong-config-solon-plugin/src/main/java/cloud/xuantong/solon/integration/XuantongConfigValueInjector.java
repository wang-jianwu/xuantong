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
            String key = anno.value().isEmpty() ? varH.getFullName() : anno.value();

            // 获取配置值
            String value = XuantongConfig.get(key, null);

            // 校验必需性
            if (anno.required() && value == null) {
                throw new XuantongException("Required config '" + key + "' not found");
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
                registerAutoRefreshListener(varH, anno, key);
            }

            logger.debug("Injected @ConfigValue field: {}", key);
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

    private void registerAutoRefreshListener(VarHolder varH, ConfigValue configValue, String key) {
        try {
            XuantongClient.getDefault().addListener(key, event -> {
                try {
                    String newValue = event.getNewValue();
                    Object convertedValue = convertValue(
                            newValue != null ? newValue : configValue.defaultValue(),
                            varH);
                    varH.setValue(convertedValue);
                    logger.info("Auto-refreshed field: {} to new value: {}", key, newValue);
                } catch (Exception e) {
                    logger.error("Failed to auto-refresh field: {}", key, e);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to register auto-refresh listener for field: {}", key, e);
        }
    }
}
