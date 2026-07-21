package cloud.xuantong.client;

import cloud.xuantong.client.exception.XuantongException;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * 静态门面类 - 提供简单的静态API访问方式
 */
public final class XuantongConfig {
    private static XuantongConfigClient defaultClient = null;
    private static boolean ownsDefaultClient;

    private XuantongConfig() {
        // 防止实例化
    }

    public static synchronized void init(
            List<String> serverAddresses, String namespace, String group) {
        init(serverAddresses, namespace, group, "");
    }

    /**
     * 初始化配置客户端（静态方式）
     */
    public static synchronized void init(
            List<String> serverAddresses, String namespace, String group, String accessToken) {
        init(serverAddresses, namespace, group, accessToken, ClientIdentity.defaultIdentity());
    }

    public static synchronized void init(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken,
            String applicationName) {
        init(serverAddresses, namespace, group, accessToken,
                new ClientIdentity(applicationName, null));
    }

    public static synchronized void init(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken,
            String applicationName,
            String clientInstanceId) {
        init(serverAddresses, namespace, group, accessToken,
                new ClientIdentity(applicationName, clientInstanceId));
    }

    private static synchronized void init(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken,
            ClientIdentity identity) {
        if (defaultClient != null) {
            throw new XuantongException("XuantongConfig already initialized. Use close() before reinitializing.");
        }
        defaultClient = new XuantongConfigClient(
                serverAddresses, namespace, group, accessToken, identity);
        ownsDefaultClient = true;
    }

    /**
     * 获取字符串配置值
     */
    public static String get(String dataId) {
        return requiredDefault().get(dataId);
    }

    /**
     * 获取字符串配置值（带默认值）
     */
    public static String get(String dataId, String defaultValue) {
        return requiredDefault().get(dataId, defaultValue);
    }

    /**
     * 获取对象配置值
     */
    public static <T> T getObject(String dataId, Class<T> clazz) {
        return requiredDefault().getObject(dataId, clazz);
    }

    /**
     * 获取对象列表配置值
     */
    public static <T> List<T> getObjectList(String dataId, Class<T> clazz) {
        return requiredDefault().getObjectList(dataId, clazz);
    }
    /**
     * 获取 Map 配置值（支持 Enum key 等泛型类型）
     * @param keyType 键类型，如 MyEnum.class
     * @param valueType 值类型，如 SomeObject.class
     */
    public static <K, V> Map<K, V> getObjectMap(String dataId, Type keyType, Type valueType) {
        return requiredDefault().getObjectMap(dataId, keyType, valueType);
    }
    /**
     * 显式绑定一个由调用方管理生命周期的默认客户端。
     * 普通客户端构造不会再修改全局默认实例。
     */
    public static synchronized void setDefault(XuantongConfigClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (defaultClient != null && defaultClient != client) {
            throw new IllegalStateException("Default client already set");
        }
        if (defaultClient == client) {
            return;
        }
        defaultClient = client;
        ownsDefaultClient = false;
    }

    /** 返回显式配置的默认客户端；未配置时返回 null。 */
    public static synchronized XuantongConfigClient getDefault() {
        return defaultClient;
    }

    /** 仅当当前默认实例与参数相同时解除绑定，不关闭客户端。 */
    public static synchronized void clearDefault(XuantongConfigClient client) {
        if (defaultClient == client) {
            defaultClient = null;
            ownsDefaultClient = false;
        }
    }

    /**
     * 清理默认实例。通过 {@link #init(List, String, String)} 创建的客户端会被关闭；
     * 通过 {@link #setDefault(XuantongConfigClient)} 绑定的客户端仍由调用方关闭。
     */
    public static synchronized void close() {
        XuantongConfigClient client = defaultClient;
        boolean closeClient = ownsDefaultClient;
        defaultClient = null;
        ownsDefaultClient = false;
        if (client != null && closeClient) {
            client.close();
        }
    }

    /**
     * 检查是否已初始化
     */
    public static synchronized boolean isInitialized() {
        return defaultClient != null;
    }

    private static synchronized XuantongConfigClient requiredDefault() {
        if (defaultClient == null) {
            throw new XuantongException("XuantongConfig not initialized. Please call XuantongConfig.init() first.");
        }
        return defaultClient;
    }
}
