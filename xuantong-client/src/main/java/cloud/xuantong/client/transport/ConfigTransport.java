package cloud.xuantong.client.transport;

import java.util.List;
import java.util.Set;

/**
 * 配置传输层接口
 */
public interface ConfigTransport {

    /**
     * 配置变更监听器接口
     */
    interface ConfigChangeListener {
        void onChanged(String configData);
    }

    /**
     * 连接到配置服务器并注册变更监听器
     * @param secretKey Broker 鉴权密钥，为空则不校验
     */
    void connect(List<String> serverAddress, List<String> appNames, String env, String secretKey, ConfigChangeListener listener);

    /**
     * 获取配置变更
     */
    @Deprecated
    String fetchChanges(String appName, String env);

    /**
     * 获取单个配置值
     */
    String fetch(String key, String env);

    /**
     * 批量获取多个应用的配置
     */
    String fetchAllForApps(List<String> appNames, String env);

    /**
     * 批量获取特定配置键的值
     * @param keys 需要获取的配置键集合
     * @param env 环境名称
     * @return 配置键值对，格式为JSON字符串
     */
    String fetchSpecificKeys(String keys, String env);

    /**
     * 关闭连接
     */
    void close();
}