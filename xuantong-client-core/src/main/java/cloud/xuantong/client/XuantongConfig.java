package cloud.xuantong.client;

import cloud.xuantong.client.exception.XuantongException;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * 静态门面类 - 提供简单的静态API访问方式
 */
public final class XuantongConfig {
    private static XuantongClient defaultClient = null;
    private static volatile boolean initialized = false;

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
            String applicationName,
            String clientId) {
        init(serverAddresses, namespace, group, accessToken,
                new ClientIdentity(applicationName, clientId));
    }

    private static synchronized void init(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken,
            ClientIdentity identity) {
        if (initialized) {
            throw new XuantongException("XuantongConfig already initialized. Use close() before reinitializing.");
        }
        if (defaultClient != null) {
            throw new XuantongException("XuantongConfig singleton instance already exists");
        }
        defaultClient = new XuantongClient(serverAddresses, namespace, group, accessToken, identity);
        initialized = true;
    }

    /**
     * 获取字符串配置值
     */
    public static String get(String dataId) {
        checkInitialized();
        return defaultClient.get(dataId);
    }

    /**
     * 获取字符串配置值（带默认值）
     */
    public static String get(String dataId, String defaultValue) {
        checkInitialized();
        return defaultClient.get(dataId, defaultValue);
    }

    /**
     * 获取对象配置值
     */
    public static <T> T getObject(String dataId, Class<T> clazz) {
        checkInitialized();
        return defaultClient.getObject(dataId, clazz);
    }

    /**
     * 获取对象列表配置值
     */
    public static <T> List<T> getObjectList(String dataId, Class<T> clazz) {
        checkInitialized();
        return defaultClient.getObjectList(dataId, clazz);
    }
    /**
     * 获取 Map 配置值（支持 Enum key 等泛型类型）
     * @param keyType 键类型，如 MyEnum.class
     * @param valueType 值类型，如 SomeObject.class
     */
    public static <K, V> Map<K, V> getObjectMap(String dataId, Type keyType, Type valueType) {
        checkInitialized();
        return defaultClient.getObjectMap(dataId, keyType, valueType);
    }
    /**
     * 设置默认客户端实例（供XuantongClient内部使用）
     */
    static synchronized void setClient(XuantongClient client) {
        if (defaultClient != null && defaultClient != client) {
            throw new IllegalStateException("Default client already set");
        }
        defaultClient = client;
        initialized = true;
    }

    static synchronized void clearClient(XuantongClient client) {
        if (defaultClient == client) {
            defaultClient = null;
            initialized = false;
        }
    }

    /**
     * 关闭配置客户端并清理单例实例
     */
    public static synchronized void close() {
        if (defaultClient != null) {
            defaultClient.close();
            defaultClient = null;
            initialized = false;
        }
    }

    /**
     * 检查是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    private static void checkInitialized() {
        if (!initialized || defaultClient == null) {
            throw new XuantongException("XuantongConfig not initialized. Please call XuantongConfig.init() first.");
        }
    }
}
