package cloud.xuantong.config.autoconfigure;

import cloud.xuantong.client.XuantongClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class XuantongConfigValueProcessor implements BeanPostProcessor {

    private final ObjectProvider<XuantongClient> nimBusClientProvider;
    private final Map<String, List<FieldValueHolder>> configKeyToFields = new ConcurrentHashMap<>();

    public XuantongConfigValueProcessor(ObjectProvider<XuantongClient> nimBusClientProvider) {
        this.nimBusClientProvider = nimBusClientProvider;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();
        ReflectionUtils.doWithFields(clazz, field -> {
            org.springframework.beans.factory.annotation.Value valueAnnotation =
                    AnnotationUtils.getAnnotation(field, org.springframework.beans.factory.annotation.Value.class);
            if (valueAnnotation != null) {
                String value = valueAnnotation.value();
                if (value.startsWith("${") && value.endsWith("}")) {
                    String configKey = extractConfigKey(value);

                    // 注册配置键和字段的映射
                    configKeyToFields.computeIfAbsent(configKey, k -> new ArrayList<>())
                            .add(new FieldValueHolder(bean, field, valueAnnotation.value()));

                    // 添加监听器
                    XuantongClient client = nimBusClientProvider.getIfAvailable();
                    if (client != null) {
                        client.addListener(configKey, event -> {
                            refreshFieldValue(bean, field, event.getNewValue());
                        });
                    }
                }
            }
        });
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 在Bean初始化完成后，设置字段的初始值
        Class<?> clazz = bean.getClass();
        ReflectionUtils.doWithFields(clazz, field -> {
            org.springframework.beans.factory.annotation.Value valueAnnotation =
                    AnnotationUtils.getAnnotation(field, org.springframework.beans.factory.annotation.Value.class);
            if (valueAnnotation != null) {
                String value = valueAnnotation.value();
                if (value.startsWith("${") && value.endsWith("}")) {
                    String configKey = extractConfigKey(value);
                    String defaultValue = extractDefaultValue(value);

                    // 设置字段的初始值
                    setFieldInitialValue(bean, field, configKey, defaultValue);
                }
            }
        });
        return bean;
    }

    private void setFieldInitialValue(Object bean, Field field, String configKey, String defaultValue) {
        try {
            field.setAccessible(true);
            Class<?> fieldType = field.getType();

            // 先尝试从配置中心获取值
            XuantongClient client = nimBusClientProvider.getIfAvailable();
            if (client != null) {
                try {
                    if (fieldType.isArray() || java.util.List.class.isAssignableFrom(fieldType)) {
                        // 处理数组或列表类型
                        Class<?> componentType = getComponentType(fieldType);
                        if (componentType != null) {
                            List<?> result = client.getObjectList(configKey, componentType);
                            if (result != null) {
                                if (fieldType.isArray()) {
                                    Object[] array = result.toArray();
                                    field.set(bean, java.util.Arrays.copyOf(array, array.length, (Class<? extends Object[]>) fieldType));
                                } else {
                                    field.set(bean, result);
                                }
                                return;
                            }
                        }
                    } else if (fieldType != String.class &&
                            fieldType != Integer.class && fieldType != int.class &&
                            fieldType != Long.class && fieldType != long.class &&
                            fieldType != Boolean.class && fieldType != boolean.class &&
                            fieldType != Double.class && fieldType != double.class &&
                            fieldType != Float.class && fieldType != float.class) {
                        // 处理复杂对象类型
                        Object result = client.getObject(configKey, fieldType);
                        if (result != null) {
                            field.set(bean, result);
                            return;
                        }
                    } else {
                        // 处理基本类型
                        String configValue = client.get(configKey, null);
                        if (configValue != null) {
                            setFieldValueByType(bean, field, fieldType, configValue);
                            return;
                        }
                    }
                } catch (Exception e) {
                    // 配置中心获取失败，继续使用默认值
                }
            }

            // 使用默认值（如果有）
            if (!defaultValue.isEmpty()) {
                setFieldValueByType(bean, field, fieldType, defaultValue);
            }
            // 如果没有默认值且配置中心无值，保持字段原值（通常为null）
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set field initial value", e);
        }
    }

    private void setFieldValueByType(Object bean, Field field, Class<?> fieldType, String value) throws IllegalAccessException {
        if (fieldType == String.class) {
            field.set(bean, value);
        } else if (fieldType == Integer.class || fieldType == int.class) {
            field.set(bean, Integer.parseInt(value));
        } else if (fieldType == Long.class || fieldType == long.class) {
            field.set(bean, Long.parseLong(value));
        } else if (fieldType == Boolean.class || fieldType == boolean.class) {
            field.set(bean, Boolean.parseBoolean(value));
        } else if (fieldType == Double.class || fieldType == double.class) {
            field.set(bean, Double.parseDouble(value));
        } else if (fieldType == Float.class || fieldType == float.class) {
            field.set(bean, Float.parseFloat(value));
        }
        // 可以添加更多类型转换支持
    }

    private Class<?> getComponentType(Class<?> fieldType) {
        if (fieldType.isArray()) {
            return fieldType.getComponentType();
        } else if (java.util.List.class.isAssignableFrom(fieldType)) {
            // 这里简化处理，实际可能需要更复杂的类型推断
            // 对于List类型，可以通过其他方式获取泛型参数类型
            return Object.class;
        }
        return null;
    }

    private String extractDefaultValue(String valueExpression) {
        if (valueExpression.startsWith("${") && valueExpression.endsWith("}")) {
            String content = valueExpression.substring(2, valueExpression.length() - 1);
            int colonIndex = content.indexOf(':');
            if (colonIndex != -1) {
                return content.substring(colonIndex + 1);
            }
        }
        return "";
    }

    private String extractConfigKey(String valueExpression) {
        if (valueExpression.startsWith("${") && valueExpression.endsWith("}")) {
            String content = valueExpression.substring(2, valueExpression.length() - 1);
            // 去除默认值部分
            int colonIndex = content.indexOf(':');
            if (colonIndex != -1) {
                return content.substring(0, colonIndex);
            }
            return content;
        }
        return valueExpression;
    }

    private void refreshFieldValue(Object bean, Field field, String newValue) {
        try {
            field.setAccessible(true);
            Class<?> fieldType = field.getType();

            if (fieldType == String.class) {
                field.set(bean, newValue);
            } else if (fieldType == Integer.class || fieldType == int.class) {
                field.set(bean, Integer.parseInt(newValue));
            } else if (fieldType == Long.class || fieldType == long.class) {
                field.set(bean, Long.parseLong(newValue));
            } else if (fieldType == Boolean.class || fieldType == boolean.class) {
                field.set(bean, Boolean.parseBoolean(newValue));
            } else if (fieldType == Double.class || fieldType == double.class) {
                field.set(bean, Double.parseDouble(newValue));
            } else if (fieldType == Float.class || fieldType == float.class) {
                field.set(bean, Float.parseFloat(newValue));
            }
            // 可以添加更多类型转换支持
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to refresh field value", e);
        }
    }

    private static class FieldValueHolder {
        final Object bean;
        final Field field;
        final String originalValue;

        FieldValueHolder(Object bean, Field field, String originalValue) {
            this.bean = bean;
            this.field = field;
            this.originalValue = originalValue;
        }
    }
}