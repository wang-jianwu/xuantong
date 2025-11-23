package com.xuantong.core.listener;

import com.xuantong.core.cluster.ClusterMessage;
import com.xuantong.core.cluster.SocketDClusterManager;
import com.xuantong.core.cluster.SocketDClusterNode;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.socketd.transport.core.Listener;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;

/**
 * 集群客户端监听器
 */
@Slf4j
public class ClusterClientListener implements Listener {
    private final SocketDClusterNode targetNode;
    private final SocketDClusterManager clusterManager;

    public ClusterClientListener(SocketDClusterNode targetNode, SocketDClusterManager clusterManager) {
        this.targetNode = targetNode;
        this.clusterManager = clusterManager;
    }

    @Override
    public void onOpen(Session session) {
        log.info("连接到集群节点成功: {}", targetNode.getAddress());
        targetNode.setActive(true);
    }

    @Override
    public void onMessage(Session session, Message message) {
        try {
            String data = message.dataAsString();
            ClusterMessage clusterMessage = ONode.deserialize(data, ClusterMessage.class);
            clusterManager.handleClusterMessage(clusterMessage);
        } catch (Exception e) {
            log.error("处理集群消息失败", e);
        }
    }

    @Override
    public void onClose(Session session) {
        log.warn("到集群节点的连接关闭: {}", targetNode.getAddress());
        targetNode.setActive(false);
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.error("到集群节点的连接错误: {}", targetNode.getAddress(), error);
        targetNode.setActive(false);
    }
}
