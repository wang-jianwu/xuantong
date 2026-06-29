package cloud.xuantong.core.listener;

import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.transport.core.impl.ConfigDefault;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.socketd.ToSocketdWebSocketListener;

/**
 * 配置中心 Broker 端点
 * <p>
 * 将 BrokerListener 包装为 WebSocket 端点，复用 HTTP 端口（8088）
 * 客户端连接 URL: sd:ws://host:8088/xuantong-admin/config?@=project:env
 * <p>
 * Player 命名规则：
 * - 客户端: @=project:env（如 @=myapp:prod）
 * - 集群节点: @=config-node-{nodeId}
 */
@Slf4j
@ServerEndpoint("/config")
public class ConfigBrokerEndpoint extends ToSocketdWebSocketListener {

    @Inject
    private ConfigBrokerListener brokerListener;

    public ConfigBrokerEndpoint() {
        // clientMode=true，表示此端点作为 Broker 接受 Player 连接
        super(new ConfigDefault(true));
    }

    @Init
    public void init() {
        setListener(brokerListener);
        log.info("Config Broker endpoint started at /config");
    }
}
