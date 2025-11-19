package com.nimbus.client.transport;

import java.util.List;

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
     */
    void connect(List<String> serverAddress, String appName, String env, ConfigChangeListener listener);

    /**
     * 获取所有配置
     */
    String fetchAll(String appName, String env);

    /**
     * 获取配置变更
     */
    String fetchChanges(String appName, String env);

    /**
     * 获取单个配置值
     */
    String fetch(String appName, String env, String key);

    /**
     * 关闭连接
     */
    void close();
}