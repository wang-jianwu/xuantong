package cloud.xuantong.core.v2.listener;

import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.broker.BrokerFragmentHandler;
import org.noear.socketd.transport.core.impl.ConfigDefault;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.socketd.ToSocketdWebSocketListener;

@Slf4j
@ServerEndpoint("/config-v2")
public class ConfigBrokerV2Endpoint extends ToSocketdWebSocketListener {
    @Inject
    private ConfigBrokerV2Listener brokerListener;

    public ConfigBrokerV2Endpoint() {
        super(new ConfigDefault(false)
                .fragmentHandler(new BrokerFragmentHandler()));
    }

    @Init
    public void init() {
        setListener(brokerListener);
        log.info("Config Broker V2 endpoint started at /config-v2");
    }
}
