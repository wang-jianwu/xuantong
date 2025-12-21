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
                Object convertedValue = convertValue(value, varH.getDependencyType(), anno.type());
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

    private Object convertValue(String value, Class<?> targetType, ValueType valueType) {
        try {
            if (value == null) return null;

            switch (valueType) {
                case INTEGER:
                    return Integer.parseInt(value);
                case LONG:
                    return Long.parseLong(value);
                case DOUBLE:
                    return Double.parseDouble(value);
                case BOOLEAN:
                    return Boolean.parseBoolean(value);
                case MAP:
                    return Serializer.defaultSerializer().deserializeMap(value);
                case LIST:
                    return Serializer.defaultSerializer().deserializeToList(value, targetType);
                case JSON:
                    return Serializer.defaultSerializer().deserialize(value, targetType);
                case YAML:
                    //return parseYamlValue(value, targetType, prefix);
                case STRING:
                default:
                    return value;
            }
        } catch (Exception e) {
            throw new XuantongException("Failed to convert config value", e);
        }
    }

    private void registerAutoRefreshListener(VarHolder varH, ConfigValue configValue, String key) {
        if (configValue.autoRefresh()) {
            try {
                XuantongClient.getDefault().addListener(key, event -> {
                    try {
                        String newValue = event.getNewValue();
                        Object convertedValue = convertValue(
                                newValue != null ? newValue : configValue.defaultValue(),
                                varH.getDependencyType(),
                                configValue.type());
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

//    private Object parseYamlValue(String yamlContent, Class<?> targetType, String prefix) {
//        try {
//            Yaml yaml = new Yaml();
//            Object yamlObj = yaml.load(yamlContent);
//
//            if (prefix != null && !prefix.isEmpty()) {
//                yamlObj = extractByPrefix(yamlObj, prefix);
//            }
//
//            return convertToTargetType(yamlObj, targetType);
//        } catch (Exception e) {
//            throw new XuantongException("Failed to parse YAML content", e);
//        }
//    }

    private Object extractByPrefix(Object data, String prefix) {
        String[] paths = prefix.split("\\.");
        Object current = data;

        for (String path : paths) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(path);
                if (current == null) {
                    throw new XuantongException("No data found at prefix path: " + prefix);
                }
            } else {
                throw new XuantongException("Invalid prefix path for non-map data: " + prefix);
            }
        }
        return current;
    }

    private Object convertToTargetType(Object value, Class<?> targetType) {
        if (targetType.isInstance(value)) {
            return value;
        }
        // 简单类型转换
        if (value instanceof String) {
            String strValue = (String) value;
            if (targetType == Integer.class || targetType == int.class) return Integer.parseInt(strValue);
            if (targetType == Long.class || targetType == long.class) return Long.parseLong(strValue);
            if (targetType == Double.class || targetType == double.class) return Double.parseDouble(strValue);
            if (targetType == Boolean.class || targetType == boolean.class) return Boolean.parseBoolean(strValue);
        }
        return value;
    }//
}
