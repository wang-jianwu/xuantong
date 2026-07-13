package cloud.xuantong.client;

import cloud.xuantong.client.core.ConfigCore;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.serializer.Serializer;
import cloud.xuantong.client.transport.impl.SocketDTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * 配置客户端接口 - 实例化客户端（用于依赖注入）
 */
public class XuantongClient implements AutoCloseable {

    private final ConfigCore configCore;
    private static volatile XuantongClient defaultInstance = null;
    private static final Object LOCK = new Object();

    public XuantongClient(List<String> serverAddresses, String namespace, String group) {
        this(serverAddresses, namespace, group, "");
    }

    public XuantongClient(List<String> serverAddresses, String namespace, String group, String accessToken) {
        this(serverAddresses, namespace, group, accessToken, ClientIdentity.defaultIdentity());
    }

    public XuantongClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken,
            String applicationName,
            String clientId) {
        this(serverAddresses, namespace, group, accessToken,
                new ClientIdentity(applicationName, clientId));
    }

    public XuantongClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken,
            ClientIdentity identity) {
        this.configCore = new ConfigCore(
                serverAddresses, namespace, group, accessToken, new SocketDTransport(identity));
        registerAsDefault();
    }

    /**
     * 注册当前实例为默认实例（双重检查锁定，静态锁保护静态字段）
     */
    private void registerAsDefault() {
        if (defaultInstance == null) {
            synchronized (LOCK) {
                if (defaultInstance == null) {
                    defaultInstance = this;
                    XuantongConfig.setClient(this);
                }
            }
        }
    }

    /**
     * 获取默认实例（如果存在）
     */
    public static XuantongClient getDefault() {
        return defaultInstance;
    }

    /**
     * 获取字符串配置值
     */
    public String get(String dataId) {
        return configCore.get(dataId, null);
    }

    /**
     * 获取字符串配置值（带默认值）
     */
    public String get(String dataId, String defaultValue) {
        return configCore.get(dataId, defaultValue);
    }

    /**
     * 获取对象配置值
     */
    public <T> T getObject(String dataId, Class<T> clazz) {
        String json = get(dataId, null);
        return Serializer.defaultSerializer().deserialize(json, clazz);
    }

    /**
     * 获取对象列表配置值
     */
    public <T> List<T> getObjectList(String dataId, Class<T> clazz) {
        String json = get(dataId, null);
        return Serializer.defaultSerializer().deserializeToList(json, clazz);
    }

    /**
     * 获取 Map 配置值（支持 Enum key 等泛型类型）
     * @param keyType 键类型，如 MyEnum.class
     * @param valueType 值类型，如 SomeObject.class
     */
    public <K, V> Map<K, V> getObjectMap(String dataId, Type keyType, Type valueType) {
        String json = get(dataId, null);
        return Serializer.defaultSerializer().deserializeMap(json, keyType, valueType);
    }

    /**
     * 添加配置变更监听器
     */
    public void addListener(String dataId, ConfigListener listener) {
        configCore.addConfigListener(dataId, listener);
    }

    /**
     * 移除配置变更监听器
     */
    public void removeListener(String dataId, ConfigListener listener) {
        configCore.removeConfigListener(dataId, listener);
    }

    /**
     * 关闭客户端
     */
    @Override
    public void close() {
        configCore.close();
        synchronized (LOCK) {
            if (defaultInstance == this) {
                defaultInstance = null;
                XuantongConfig.clearClient(this);
            }
        }
    }
}
