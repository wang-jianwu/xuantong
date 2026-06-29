package cloud.xuantong.core.listener;

import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.broker.BrokerFragmentHandler;
import org.noear.socketd.transport.core.impl.ConfigDefault;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.socketd.ToSocketdWebSocketListener;

/**
 * 配置中心 Broker 端点（复用 HTTP 端口）
 * <p>
 * 参考 Socket.D 官方文档: https://socketd.noear.org/article/783
 * <p>
 * 采用"常规应用参考"模式：
 * 1. ConfigDefault(false) + BrokerFragmentHandler
 * 2. @Inject 注入 ConfigBrokerListener（让 Solon 管理 Bean，@Inject 生效）
 * 3. @Init 调用 setListener() 设置监听器
 * <p>
 * 客户端连接 URL: sd:ws://host:port?@=project:env
 */
@Slf4j
@ServerEndpoint("/config")
public class ConfigBrokerEndpoint extends ToSocketdWebSocketListener {

    @Inject
    private ConfigBrokerListener brokerListener;

    public ConfigBrokerEndpoint() {
        // 服务端模式 + Broker 分片处理器
        super(new ConfigDefault(false).fragmentHandler(new BrokerFragmentHandler()));
    }

    @Init
    public void init() {
        // 注入后再设置，让 @Inject 生效
        setListener(brokerListener);
        log.info("Config Broker endpoint started at /config");
    }
}
