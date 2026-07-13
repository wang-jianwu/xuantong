package cloud.xuantong.config.autoconfigure;

import cloud.xuantong.client.XuantongClient;
import cloud.xuantong.client.annotation.ConfigValue;
import cloud.xuantong.client.enums.ValueType;
import cloud.xuantong.client.exception.XuantongException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * @author 封于修
 * 处理@ConfigValue注解的Bean后处理器
 */
public class XuantongConfigValueProcessor implements BeanPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(XuantongConfigValueProcessor.class);

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
                ValueType resolvedType = ValueType.inferFromClass(field.getType());
                Object configValueObj = getConfigValue(client, configKey, field, defaultValue, resolvedType);
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

    private Object getConfigValue(XuantongClient client, String configKey, Field field, String defaultValue, ValueType valueType) {
        Class<?> fieldType = field.getType();
        return switch (valueType) {
            case JSON -> {
                if (List.class.isAssignableFrom(fieldType)) {
                    yield client.getObjectList(configKey, getComponentType(field));
                }
                if (Map.class.isAssignableFrom(fieldType)) {
                    Type[] mapTypes = getMapGenericTypes(field);
                    yield client.getObjectMap(configKey, mapTypes[0], mapTypes[1]);
                }
                yield client.getObject(configKey, fieldType);
            }
            case BOOLEAN, NUMBER -> {
                String value = client.get(configKey, defaultValue);
                yield convertStringToType(value, fieldType, valueType);
            }
            default -> client.get(configKey, defaultValue);
        };
    }

    private Object convertStringToType(String value, Class<?> fieldType, ValueType valueType) {
        if (value == null) return null;
        // 只有 String 类型允许空值，其他类型空串必须校验失败
        if (value.isEmpty() && valueType != ValueType.STRING) {
            throw new XuantongException("Empty config value is not allowed for non-String type: " + fieldType);
        }

        try {
            if (valueType == ValueType.BOOLEAN) {
                return Boolean.parseBoolean(value);
            }
            if (valueType == ValueType.NUMBER) {
                if (fieldType == int.class || fieldType == Integer.class) return Integer.parseInt(value);
                if (fieldType == long.class || fieldType == Long.class) return Long.parseLong(value);
                if (fieldType == float.class || fieldType == Float.class) return Float.parseFloat(value);
                if (fieldType == double.class || fieldType == Double.class) return Double.parseDouble(value);
                if (fieldType == short.class || fieldType == Short.class) return Short.parseShort(value);
                if (fieldType == byte.class || fieldType == Byte.class) return Byte.parseByte(value);
                return Long.parseLong(value);
            }
            return value;
        } catch (Exception e) {
            throw new XuantongException("Failed to convert config value: " + value + " to type: " + fieldType, e);
        }
    }

    private Class<?> getComponentType(Field field) {
        Class<?> fieldType = field.getType();
        if (fieldType.isArray()) {
            return fieldType.getComponentType();
        } else if (List.class.isAssignableFrom(fieldType)) {
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType) {
                Type[] typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
                if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                    return (Class<?>) typeArguments[0];
                }
            }
            return Object.class;
        }
        return null;
    }

    /**
     * 提取 Map 的泛型类型 [keyType, valueType]
     * 如 Map<MyEnum, SomeObject> → [MyEnum.class, SomeObject.class]
     */
    private Type[] getMapGenericTypes(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType) {
            Type[] typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
            if (typeArguments.length == 2) {
                return typeArguments;
            }
        }
        return new Type[]{String.class, Object.class};
    }

    private void refreshFieldValue(Object bean, Field field, ConfigValue configValue, String newValue) {
        try {
            field.setAccessible(true);
            ValueType resolvedType = ValueType.inferFromClass(field.getType());
            Object convertedValue;

            // 配置被删除（newValue=null）时，回退到注解的默认值
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
                // 无默认值可回退：required 字段保留旧值并警告，非 required 字段置 null
                if (configValue.required()) {
                    logger.warn("Config deleted but field is required, keeping old value: {}",
                            configValue.value());
                    return;
                }
                field.set(bean, null);
                return;
            }

            if (resolvedType == ValueType.JSON) {
                XuantongClient client = xuantongClientProvider.getIfAvailable();
                if (client == null) return;
                Class<?> fieldType = field.getType();
                if (List.class.isAssignableFrom(fieldType)) {
                    convertedValue = client.getObjectList(configValue.value(), getComponentType(field));
                } else if (Map.class.isAssignableFrom(fieldType)) {
                    Type[] mapTypes = getMapGenericTypes(field);
                    convertedValue = client.getObjectMap(configValue.value(), mapTypes[0], mapTypes[1]);
                } else {
                    convertedValue = client.getObject(configValue.value(), fieldType);
                }
            } else {
                convertedValue = convertStringToType(effectiveValue, field.getType(), resolvedType);
            }
            field.set(bean, convertedValue);
        } catch (IllegalAccessException e) {
            throw new XuantongException("Failed to refresh field value", e);
        }
    }
}