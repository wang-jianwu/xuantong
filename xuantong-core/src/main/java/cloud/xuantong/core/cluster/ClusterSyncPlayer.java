package cloud.xuantong.core.cluster;

import cloud.xuantong.core.listener.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Init;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 集群同步 Player
 * <p>
 * 以 Player 身份连接到其他配置中心节点的 Broker，接收集群同步消息
 * 命名规则: @=config-node-{nodeId}
 * <p>
 * 职责：
 * 1. 连接到其他配置中心节点的 Broker
 * 2. 接收 /cluster-sync 消息
 * 3. 触发本地 ConfigClusterBroadcaster 处理
 */
@Slf4j
@Component
public class ClusterSyncPlayer {

    @Inject
    private ClusterConfig clusterConfig;

    @Inject
    private ConfigClusterBroadcaster broadcaster;

    private final String nodeId = UUID.randomUUID().toString().substring(0, 8);
    private final CopyOnWriteArrayList<ClientSession> sessions = new CopyOnWriteArrayList<>();

    @Init
    public void init() {
        if (!clusterConfig.isClusterEnabled()) {
            log.info("Cluster not enabled, skipping ClusterSyncPlayer init");
            return;
        }

        log.info("ClusterSyncPlayer starting, nodeId: {}", nodeId);

        new Thread(() -> {
            try {
                // 延迟启动，等待 Broker 就绪
                Thread.sleep(3000);
                connectToClusterNodes();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("ClusterSyncPlayer init interrupted");
            }
        }, "cluster-sync-init").start();
    }

    /**
     * 连接到所有配置的集群节点
     */
    private void connectToClusterNodes() {
        clusterConfig.getClusterNodeAddresses().forEach(address -> {
            try {
                connectToNode(address);
            } catch (Exception e) {
                log.error("Failed to connect to cluster node: {}", address, e);
            }
        });
    }

    /**
     * 连接到单个集群节点
     * 以 config-node-{nodeId} 身份注册到目标节点的 Broker
     */
    private void connectToNode(String address) {
        try {
            // 构建连接 URL: sd:ws://host:port/path?@=config-node-{nodeId}
            String url = buildConnectionUrl(address);

            ClientSession session = SocketD.createClient(url)
                    .config(c -> c.heartbeatInterval(20_000)
                            .connectTimeout(10_000)
                            .autoReconnect(true))
                    .listen(new EventListener()
                            .doOn("/cluster-sync", (s, m) -> {
                                try {
                                    String data = m.dataAsString();
                                    log.debug("Received cluster sync: {}", data);
                                    ConfigChangeEvent event = ONode.deserialize(data, ConfigChangeEvent.class);
                                    if (event != null) {
                                        broadcaster.handleClusterSync(event);
                                    }
                                } catch (Exception e) {
                                    log.error("Failed to process cluster sync message", e);
                                }
                            })
                            .doOnOpen(s -> log.info("Connected to cluster node: {}", address))
                            .doOnClose(s -> log.warn("Disconnected from cluster node: {}", address))
                            .doOnError((s, e) -> log.error("Cluster node connection error: {}", address, e)))
                    .openOrThow();

            sessions.add(session);
            log.info("ClusterSyncPlayer connected to: {}", address);

        } catch (Exception e) {
            log.error("Failed to connect to cluster node: {}", address, e);
        }
    }

    /**
     * 构建连接 URL
     * 支持两种格式：
     * - sd:ws://host:port（完整 URL）
     * - sd:ws://host:port/path（带路径的 URL）
     */
    private String buildConnectionUrl(String address) {
        // 确保地址格式正确
        if (!address.startsWith("sd:")) {
            address = "sd:ws://" + address;
        }

        // 添加 @=name 参数
        String separator = address.contains("?") ? "&" : "?";
        return address + separator + "@=config-node-" + nodeId;
    }

    /**
     * 获取活跃的集群连接数
     */
    public int getActiveConnectionCount() {
        return (int) sessions.stream().filter(ClientSession::isValid).count();
    }

    /**
     * 关闭所有连接（应用关闭时自动调用）
     */
    @Destroy
    public void close() {
        sessions.forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Error closing cluster session", e);
            }
        });
        sessions.clear();
    }
}
