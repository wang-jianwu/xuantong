package com.xuantong.core.listener;

import com.xuantong.core.cluster.ClusterConfig;
import com.xuantong.core.cluster.SocketDClusterManager;
import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Listener;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.impl.ConfigDefault;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.socketd.ToSocketdWebSocketListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集群内部通信SocketD监听器
 */
@Slf4j
@ServerEndpoint("/cluster")
public class ClusterSocketdListener extends ToSocketdWebSocketListener {

    @Inject
    private SocketDClusterManager clusterManager;

    @Inject
    private ClusterConfig clusterConfig;

    // 维护活跃的集群节点会话
    private final Map<String, Session> clusterSessions = new ConcurrentHashMap<>();

    public ClusterSocketdListener() {
        super(new ConfigDefault(true));
    }

    @Init
    public void init() {
        setListener(buildClusterListener());
        log.info("集群通信端点启动，监听路径: /cluster");
    }

    private Listener buildClusterListener() {
        return new EventListener()
                .doOnOpen(session -> {
                    String nodeId = session.param("nodeId");
                    if (nodeId != null) {
                        clusterSessions.put(nodeId, session);
                        log.info("集群节点连接: {} -> {}", nodeId, session.sessionId());
                    } else {
                        log.warn("集群节点连接缺少nodeId参数");
                        session.close();
                    }
                })
                .doOn("/cluster_message", (s, m) -> {
                    // 处理集群内部消息
                    Entity entity = m.entity();
                    String messageJson = entity.dataAsString();
                    log.debug("收到集群消息: {}", messageJson);

                    // 转发给集群管理器处理
                    if (clusterManager != null) {
                        // 这里需要根据实际消息格式进行解析
                        // clusterManager.handleClusterMessage(parsedMessage);
                    }
                })
                .doOn("/node_register", (s, m) -> {
                    // 处理节点注册消息
                    Entity entity = m.entity();
                    String registerJson = entity.dataAsString();
                    log.info("收到节点注册: {}", registerJson);
                })
                .doOn("/heartbeat", (s, m) -> {
                    // 处理心跳消息
                    Entity entity = m.entity();
                    String nodeId = entity.meta("nodeId");
                    if (nodeId != null) {
                        Session session = clusterSessions.get(nodeId);
                        if (session != null && session.isValid()) {
                            // 更新心跳时间
                            if (m.isRequest()) {
                                s.reply(m, new StringEntity("{\"status\":\"ok\"}"));
                            }
                        }
                    }
                })
                .doOnClose(s -> {
                    // 移除断开的集群节点会话
                    clusterSessions.values().removeIf(session -> session.sessionId().equals(s.sessionId()));
                    log.info("集群节点断开: {}", s.sessionId());
                })
                .doOnError((s, err) -> {
                    log.error("集群会话错误: {}", s.sessionId(), err);
                    clusterSessions.values().removeIf(session -> session.sessionId().equals(s.sessionId()));
                });
    }

    /**
     * 获取活跃的集群会话数量
     */
    public int getActiveClusterSessions() {
        return clusterSessions.size();
    }
}
