package com.nimbus.client.transport;

import com.nimbus.client.serializer.Serializer;

import java.util.Map;

/**
 * 配置传输层接口
 */
public interface ConfigTransport {
    /**
     * 连接到配置服务器
     */
    void connect();

    /**
     * 获取所有配置
     */
    Map<String, String> fetchAll();

    /**
     * 获取配置变更
     */
    Map<String, String> fetchChanges();

    /**
     * 获取单个配置值
     */
    String fetch(String key);

    /**
     * 获取序列化器
     */
    Serializer getSerializer();

    /**
     * 关闭连接
     */
    void close();
}