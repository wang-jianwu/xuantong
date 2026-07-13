package cloud.xuantong.core.v2.listener;

import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.broker.BrokerFragmentHandler;
import org.noear.socketd.transport.core.impl.ConfigDefault;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.net.annotation.ServerEndpoint;
import org.noear.solon.net.websocket.socketd.ToSocketdWebSocketListener;

@Slf4j
@ServerEndpoint("/discovery-v2")
public class DiscoveryBrokerV2Endpoint extends ToSocketdWebSocketListener {
    @Inject
    private DiscoveryBrokerV2Listener brokerListener;

    public DiscoveryBrokerV2Endpoint() {
        super(new ConfigDefault(false).fragmentHandler(new BrokerFragmentHandler()));
    }

    @Init
    public void init() {
        setListener(brokerListener);
        log.info("Discovery Broker V2 endpoint started at /discovery-v2");
    }
}
