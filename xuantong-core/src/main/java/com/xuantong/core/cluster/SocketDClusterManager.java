package com.xuantong.core.cluster;

import com.xuantong.core.listener.ClusterClientListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SocketD集群管理器 - 支持配置中心集群部署
 */
@Slf4j
@Component
public class SocketDClusterManager {

    // 集群节点映射: <nodeId, ClusterNode>
    private final Map<String, SocketDClusterNode> clusterNodes = new ConcurrentHashMap<>();
    /**
     * -- GETTER --
     *  获取当前节点ID
     */
    @Getter
    private final String currentNodeId;

    // 集群消息监听器
    private final List<ClusterMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    @Inject
    private ClusterConfig clusterConfig;


    public SocketDClusterManager() {
        this.currentNodeId = generateNodeId();
        log.info("SocketD集群管理器启动, 节点ID: {}", currentNodeId);
    }

    @Init
    public void init() {
        if (clusterConfig == null) {
            log.warn("ClusterConfig未注入，集群功能将禁用");
            return;
        }

        if (clusterConfig.isClusterEnabled()) {
            log.info("延迟5秒初始化集群节点连接...");
            // 延迟连接，避免启动顺序问题
            new Thread(() -> {
                try {
                    Thread.sleep(5000); // 延迟5秒
                    log.info("开始初始化集群节点连接...");
                    clusterConfig.getClusterNodeAddresses().forEach(this::addClusterNode);
                    log.info("集群节点连接初始化完成");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("集群连接初始化被中断");
                }
            }, "cluster-connect-thread").start();
        }
    }

    /**
     * 添加集群节点
     */
    public void addClusterNode(String nodeAddress) {
        try {
            SocketDClusterNode node = new SocketDClusterNode();
            node.setNodeId(generateNodeIdForAddress(nodeAddress));
            node.setAddress(nodeAddress);
            node.setLastHeartbeat(System.currentTimeMillis());
            node.setActive(false);

            // 建立到该节点的连接
            connectToNode(node);

            clusterNodes.put(node.getNodeId(), node);
            log.info("已添加集群节点: {} -> {}", node.getNodeId(), nodeAddress);

        } catch (Exception e) {
            log.error("添加集群节点失败: {}", nodeAddress, e);
        }
    }

    /**
     * 连接到集群节点（带重试机制）
     */
    private void connectToNode(SocketDClusterNode node) {
        int maxRetries = 3;
        long retryInterval = 5000; // 5秒

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // 确保连接到正确的/cluster端点
                String address = node.getAddress();
                if (!address.contains("/cluster")) {
                    address = address + "/cluster";
                }

                // 添加当前节点ID作为连接参数
                String finalAddress = address + "?nodeId=" + currentNodeId;

                // 创建到目标节点的客户端会话
                ClientSession clientSession = SocketD.createClient(finalAddress)
                        .config(c -> c.heartbeatInterval(20_000)
                                .connectTimeout(10000)) // 10秒连接超时
                        .listen(new ClusterClientListener(node, this))
                        .open();

                node.setSession(clientSession);
                node.setActive(true);
                node.setLastHeartbeat(System.currentTimeMillis());

                // 发送节点注册消息
                sendNodeRegister(clientSession);

                log.info("成功连接到集群节点: {}", node.getAddress());
                return; // 连接成功，退出重试循环

            } catch (Exception e) {
                if (attempt == maxRetries) {
                    log.error("连接到集群节点失败(尝试{}/{}): {}", attempt, maxRetries, node.getAddress(), e);
                    node.setActive(false);
                } else {
                    log.warn("连接到集群节点失败(尝试{}/{}): {}, {}秒后重试",
                            attempt, maxRetries, node.getAddress(), e.getMessage(), retryInterval/1000);
                    try {
                        Thread.sleep(retryInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("集群连接重试被中断");
                        break;
                    }
                }
            }
        }
    }

    /**
     * 广播消息到所有集群节点
     */
    public void broadcastToCluster(ClusterMessage message) {
        message.setSourceNodeId(currentNodeId);
        message.setTimestamp(System.currentTimeMillis());

        int successCount = 0;
        for (SocketDClusterNode node : clusterNodes.values()) {
            if (node.isActive() && node.getSession() != null) {
                try {
                    node.getSession().send("cluster_message",
                            new StringEntity(ONode.serialize(message)));
                    successCount++;
                } catch (Exception e) {
                    log.warn("向节点 {} 发送消息失败", node.getNodeId(), e);
                    node.setActive(false);
                }
            }
        }

        log.debug("集群广播完成, 成功: {}, 总数: {}", successCount, clusterNodes.size());
    }

    /**
     * 发送节点注册消息
     */
    private void sendNodeRegister(ClientSession session) throws IOException {
        Map<String, Object> registerMsg = new HashMap<>();
        registerMsg.put("type", "NODE_REGISTER");
        registerMsg.put("nodeId", currentNodeId);
        registerMsg.put("timestamp", System.currentTimeMillis());

        session.send("cluster_control", new StringEntity(ONode.serialize(registerMsg)));
    }

    /**
     * 添加消息监听器
     */
    public void addMessageListener(ClusterMessageListener listener) {
        messageListeners.add(listener);
    }

    /**
     * 获取集群状态
     */
    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("currentNodeId", currentNodeId);
        status.put("totalNodes", clusterNodes.size() + 1); // 包括自己

        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(createNodeInfo(currentNodeId, "localhost", true, "self"));

        for (SocketDClusterNode node : clusterNodes.values()) {
            nodes.add(createNodeInfo(node.getNodeId(), node.getAddress(),
                    node.isActive(), "remote"));
        }

        status.put("nodes", nodes);
        return status;
    }

    private Map<String, Object> createNodeInfo(String nodeId, String address,
                                               boolean active, String type) {
        Map<String, Object> info = new HashMap<>();
        info.put("nodeId", nodeId);
        info.put("address", address);
        info.put("active", active);
        info.put("type", type);
        return info;
    }

    private String generateNodeId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host + "-" + System.currentTimeMillis();
        } catch (Exception e) {
            return "node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private String generateNodeIdForAddress(String address) {
        return "node-" + Math.abs(address.hashCode());
    }


    /**
     * 处理收到的集群消息
     */
    public void handleClusterMessage(ClusterMessage message) {
        // 忽略自己发送的消息
        if (currentNodeId.equals(message.getSourceNodeId())) {
            return;
        }

        messageListeners.forEach(listener -> {
            try {
                listener.onClusterMessage(message);
            } catch (Exception e) {
                log.error("处理集群消息失败", e);
            }
        });
    }

    /**
     * 集群消息监听器接口
     */
    public interface ClusterMessageListener {
        void onClusterMessage(ClusterMessage message);
    }
}