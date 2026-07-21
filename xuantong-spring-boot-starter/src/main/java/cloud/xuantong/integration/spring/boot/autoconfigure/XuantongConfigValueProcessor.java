package cloud.xuantong.integration.spring.boot.autoconfigure;

import cloud.xuantong.client.XuantongConfigClient;
import cloud.xuantong.client.annotation.ConfigValue;
import cloud.xuantong.client.enums.ValueType;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.listener.ListenerRegistration;
import cloud.xuantong.client.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * @author 封于修
 * 处理@ConfigValue注解的Bean后处理器
 */
public class XuantongConfigValueProcessor
        implements BeanPostProcessor, DestructionAwareBeanPostProcessor, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(XuantongConfigValueProcessor.class);

    private final Supplier<ConfigClientAccess> clientProvider;
    private final ConfigurableListableBeanFactory beanFactory;
    private final Map<String, List<TrackedRegistration>> registrations =
            new ConcurrentHashMap<>();

    public XuantongConfigValueProcessor(
            ObjectProvider<XuantongConfigClient> xuantongConfigClientProvider,
            ConfigurableListableBeanFactory beanFactory) {
        this(() -> {
            XuantongConfigClient client = xuantongConfigClientProvider.getIfAvailable();
            return client == null ? null : new ConfigClientAdapter(client);
        }, beanFactory);
    }

    XuantongConfigValueProcessor(
            Supplier<ConfigClientAccess> clientProvider,
            ConfigurableListableBeanFactory beanFactory) {
        this.clientProvider = clientProvider;
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // 注册配置监听器
        registerConfigListeners(bean, beanName);
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 注入配置值
        injectConfigValues(bean);
        return bean;
    }

    private void registerConfigListeners(Object bean, String beanName) {
        if (isPrototype(beanName)) {
            if (hasAutoRefreshField(bean.getClass())) {
                logger.warn("Skip @ConfigValue auto-refresh for prototype bean '{}'; "
                        + "prototype beans have no container-managed destruction callback", beanName);
            }
            return;
        }
        Class<?> clazz = bean.getClass();
        ReflectionUtils.doWithFields(clazz, field -> {
            ConfigValue configValue = AnnotationUtils.getAnnotation(field, ConfigValue.class);
            if (configValue != null && configValue.autoRefresh()) {
                String configKey = configValue.value();
                ConfigClientAccess client = clientProvider.get();
                if (client != null) {
                    AutoRefreshListener listener = new AutoRefreshListener(
                            bean, field, configValue, configKey);
                    ListenerRegistration registration = client.listen(configKey, listener);
                    listener.bind(registration);
                    registrations.computeIfAbsent(
                                    beanName, ignored -> new CopyOnWriteArrayList<>())
                            .add(new TrackedRegistration(bean, registration));
                }
            }
        });
    }

    private boolean isPrototype(String beanName) {
        try {
            return beanFactory.isPrototype(beanName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasAutoRefreshField(Class<?> type) {
        AtomicBoolean found = new AtomicBoolean();
        ReflectionUtils.doWithFields(type, field -> {
            ConfigValue value = AnnotationUtils.getAnnotation(field, ConfigValue.class);
            if (value != null && value.autoRefresh()) {
                found.set(true);
            }
        });
        return found.get();
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

            ConfigClientAccess client = clientProvider.get();
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

    private Object getConfigValue(
            ConfigClientAccess client,
            String configKey,
            Field field,
            String defaultValue,
            ValueType valueType) {
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
                Class<?> fieldType = field.getType();
                if (List.class.isAssignableFrom(fieldType)) {
                    convertedValue = Serializer.defaultSerializer().deserializeToList(
                            effectiveValue, getComponentType(field));
                } else if (Map.class.isAssignableFrom(fieldType)) {
                    Type[] mapTypes = getMapGenericTypes(field);
                    convertedValue = Serializer.defaultSerializer().deserializeMap(
                            effectiveValue, mapTypes[0], mapTypes[1]);
                } else {
                    convertedValue = Serializer.defaultSerializer().deserialize(
                            effectiveValue, fieldType);
                }
            } else {
                convertedValue = convertStringToType(effectiveValue, field.getType(), resolvedType);
            }
            field.set(bean, convertedValue);
        } catch (IllegalAccessException e) {
            throw new XuantongException("Failed to refresh field value", e);
        }
    }

    @Override
    public void postProcessBeforeDestruction(Object bean, String beanName) {
        List<TrackedRegistration> beanRegistrations = registrations.get(beanName);
        if (beanRegistrations == null) {
            return;
        }
        beanRegistrations.removeIf(tracked -> {
            Object registeredBean = tracked.bean().get();
            if (registeredBean == null || registeredBean == bean) {
                tracked.registration().close();
                return true;
            }
            return false;
        });
        if (beanRegistrations.isEmpty()) {
            registrations.remove(beanName, beanRegistrations);
        }
    }

    @Override
    public boolean requiresDestruction(Object bean) {
        for (List<TrackedRegistration> values : registrations.values()) {
            for (TrackedRegistration tracked : values) {
                if (tracked.bean().get() == bean) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        List<ListenerRegistration> toClose = new ArrayList<>();
        for (List<TrackedRegistration> values : registrations.values()) {
            for (TrackedRegistration tracked : values) {
                toClose.add(tracked.registration());
            }
        }
        registrations.clear();
        for (ListenerRegistration registration : toClose) {
            registration.close();
        }
    }

    private final class AutoRefreshListener implements ConfigListener {
        private final WeakReference<Object> bean;
        private final Field field;
        private final ConfigValue configValue;
        private final String configKey;
        private final AtomicReference<ListenerRegistration> registration =
                new AtomicReference<>();

        private AutoRefreshListener(
                Object bean, Field field, ConfigValue configValue, String configKey) {
            this.bean = new WeakReference<>(bean);
            this.field = field;
            this.configValue = configValue;
            this.configKey = configKey;
        }

        private void bind(ListenerRegistration value) {
            registration.set(value);
        }

        @Override
        public void onConfigChange(cloud.xuantong.client.model.ConfigChangeEvent event) {
            Object target = bean.get();
            if (target == null) {
                ListenerRegistration value = registration.getAndSet(null);
                if (value != null) {
                    value.close();
                }
                return;
            }
            try {
                refreshFieldValue(target, field, configValue, event.getNewValue());
            } catch (Exception e) {
                throw new XuantongException(
                        "Failed to refresh config value for key: " + configKey, e);
            }
        }
    }

    private record TrackedRegistration(
            WeakReference<Object> bean, ListenerRegistration registration) {
        private TrackedRegistration(Object bean, ListenerRegistration registration) {
            this(new WeakReference<>(bean), registration);
        }
    }

    interface ConfigClientAccess {
        String get(String dataId, String defaultValue);

        <T> T getObject(String dataId, Class<T> type);

        <T> List<T> getObjectList(String dataId, Class<T> type);

        <K, V> Map<K, V> getObjectMap(String dataId, Type keyType, Type valueType);

        ListenerRegistration listen(String dataId, ConfigListener listener);
    }

    private record ConfigClientAdapter(XuantongConfigClient delegate)
            implements ConfigClientAccess {
        @Override
        public String get(String dataId, String defaultValue) {
            return delegate.get(dataId, defaultValue);
        }

        @Override
        public <T> T getObject(String dataId, Class<T> type) {
            return delegate.getObject(dataId, type);
        }

        @Override
        public <T> List<T> getObjectList(String dataId, Class<T> type) {
            return delegate.getObjectList(dataId, type);
        }

        @Override
        public <K, V> Map<K, V> getObjectMap(
                String dataId, Type keyType, Type valueType) {
            return delegate.getObjectMap(dataId, keyType, valueType);
        }

        @Override
        public ListenerRegistration listen(String dataId, ConfigListener listener) {
            return delegate.listen(dataId, listener);
        }
    }
}
