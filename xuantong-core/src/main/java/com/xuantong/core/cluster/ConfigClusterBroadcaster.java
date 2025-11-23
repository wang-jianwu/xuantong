package com.xuantong.core.cluster;

import com.xuantong.core.listener.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventBus;

import java.util.UUID;

/**
 * 配置变更集群广播器
 */
@Slf4j
@Component
public class ConfigClusterBroadcaster {

    @Inject
    private SocketDClusterManager clusterManager;

    /**
     * 广播配置变更到整个集群
     * 每个节点收到广播后会推送给连接本地的客户端
     */
    public void broadcastConfigChange(ConfigChangeEvent event) {
        // 创建集群消息（包含操作节点信息）
        ClusterMessage message = new ClusterMessage();
        message.setMessageId(UUID.randomUUID().toString());
        message.setType("CONFIG_CHANGE");
        message.setPayload(event);
        message.setSourceNodeId(clusterManager.getCurrentNodeId());

        // 广播到集群其他节点
        clusterManager.broadcastToCluster(message);

        log.debug("配置变更已广播到集群: {}={}", event.getKey(), event.getValue());

        // 本地也触发推送（推送给连接本节点的客户端）
        EventBus.publishAsync(event);
    }

    /**
     * 处理来自集群的配置变更消息
     */
    public void handleClusterConfigChange(ClusterMessage message) {
        if (!"CONFIG_CHANGE".equals(message.getType())) {
            return;
        }

        ConfigChangeEvent event = (ConfigChangeEvent) message.getPayload();
        log.debug("收到集群配置变更: {} from {}", event.getKey(), message.getSourceNodeId());

        // 触发本地监听器
        EventBus.publishAsync(event);
    }

    @Init
    public void init() {
        // 注册集群消息监听器
        clusterManager.addMessageListener(message -> {
            if ("CONFIG_CHANGE".equals(message.getType())) {
                handleClusterConfigChange(message);
            }
        });
    }
}