package cloud.xuantong.config.autoconfigure;

import cloud.xuantong.client.XuantongClient;
import cloud.xuantong.client.annotation.ConfigValue;
import cloud.xuantong.client.enums.ValueType;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.serializer.Serializer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.List;

/**
 * @author 封于修
 * 处理@ConfigValue注解的Bean后处理器
 */
public class XuantongConfigValueProcessor implements BeanPostProcessor {

    private final ObjectProvider<XuantongClient> xuantongClientProvider;

    public XuantongConfigValueProcessor(ObjectProvider<XuantongClient> xuantongClientProvider) {
        this.xuantongClientProvider = xuantongClientProvider;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // 注册配置监听器
        registerConfigListeners(bean);
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 注入配置值
        injectConfigValues(bean);
        return bean;
    }

    private void registerConfigListeners(Object bean) {
        Class<?> clazz = bean.getClass();
        ReflectionUtils.doWithFields(clazz, field -> {
            ConfigValue configValue = AnnotationUtils.getAnnotation(field, ConfigValue.class);
            if (configValue != null && configValue.autoRefresh()) {
                String configKey = configValue.value();
                XuantongClient client = xuantongClientProvider.getIfAvailable();
                if (client != null) {
                    // 注册配置变更监听器
                    client.addListener(configKey, event -> {
                        try {
                            refreshFieldValue(bean, field, configValue, event.getNewValue());
                        } catch (Exception e) {
                            throw new XuantongException("Failed to refresh config value for key: " + configKey, e);
                        }
                    });
                }
            }
        });
    }

    private void injectConfigValues(Object bean) {
        Class<?> clazz = bean.getClass();
        ReflectionUtils.doWithFields(clazz, field -> {
            ConfigValue configValue = AnnotationUtils.getAnnotation(field, ConfigValue.class);
            if (configValue != null) {
                injectConfigValue(bean, field, configValue);
            }
        });
    }

    private void injectConfigValue(Object bean, Field field, ConfigValue configValue) {
        try {
            field.setAccessible(true);
            String configKey = configValue.value();
            String defaultValue = configValue.defaultValue();

            XuantongClient client = xuantongClientProvider.getIfAvailable();
            if (client != null) {
                Object configValueObj = getConfigValue(client, configKey, field.getType(), defaultValue, configValue.type());
                if (configValueObj != null) {
                    field.set(bean, configValueObj);
                } else if (configValue.required()) {
                    throw new XuantongException("Required configuration missing: " + configKey);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject config value for key: " + configValue.value(), e);
        }
    }

    private Object getConfigValue(XuantongClient client, String configKey, Class<?> fieldType, String defaultValue, ValueType valueType) {
        // 优先使用type属性指定的类型
        switch (valueType) {
            case JSON:
                return client.getObject(configKey, fieldType);
            case LIST:
                return client.getObjectList(configKey, getComponentType(fieldType));
            case MAP:
                return client.getObject(configKey, fieldType);
            case YAML:
                String yamlValue = client.get(configKey, defaultValue);
                return parseYamlConfig(yamlValue, fieldType);
            default:
                // 基本类型
                String value = client.get(configKey, defaultValue);
                return convertStringToType(value, fieldType, valueType);
        }
    }

    private Object convertStringToType(String value, Class<?> fieldType, ValueType valueType) {
        if (value == null) return null;

        try {
            switch (valueType) {
                case INTEGER:
                    if (fieldType == int.class || fieldType == Integer.class) {
                        return Integer.parseInt(value);
                    }
                    break;
                case LONG:
                    if (fieldType == long.class || fieldType == Long.class) {
                        return Long.parseLong(value);
                    }
                    break;
                case DOUBLE:
                    if (fieldType == double.class || fieldType == Double.class) {
                        return Double.parseDouble(value);
                    }
                    break;
                case BOOLEAN:
                    if (fieldType == boolean.class || fieldType == Boolean.class) {
                        return Boolean.parseBoolean(value);
                    }
                    break;
                case LIST:
                case MAP:
                case JSON:
                    return Serializer.defaultSerializer().deserialize(value, fieldType);
                case STRING:
                default:
                    if (fieldType == String.class) {
                        return value;
                    }
                    break;
            }
        } catch (NumberFormatException e) {
            throw new XuantongException("Failed to convert config value: " + value + " to type: " + fieldType, e);
        }

        return value;
    }

    private Class<?> getComponentType(Class<?> fieldType) {
        if (fieldType.isArray()) {
            return fieldType.getComponentType();
        } else if (List.class.isAssignableFrom(fieldType)) {
            // 返回Object类型，todo 处理泛型
            return Object.class;
        }
        return null;
    }

    private Object parseYamlConfig(String yamlValue, Class<?> fieldType) {
        // 简化实现，实际项目应集成YAML解析库
        if (yamlValue != null && !yamlValue.trim().isEmpty()) {
            if (fieldType == String.class) {
                return yamlValue;
            }
            // todo 复杂YAML解析
        }
        return null;
    }

    private void refreshFieldValue(Object bean, Field field, ConfigValue configValue, String newValue) {
        try {
            field.setAccessible(true);
            Object convertedValue = convertStringToType(newValue, field.getType(), configValue.type());
            if (convertedValue != null) {
                field.set(bean, convertedValue);
            }
        } catch (IllegalAccessException e) {
            throw new XuantongException("Failed to refresh field value", e);
        }
    }
}