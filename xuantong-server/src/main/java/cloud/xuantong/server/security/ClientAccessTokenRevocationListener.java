package cloud.xuantong.server.security;

import cloud.xuantong.security.event.ClientAccessTokenRevokedEvent;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventListener;

@Slf4j
@Component
public class ClientAccessTokenRevocationListener
        implements EventListener<ClientAccessTokenRevokedEvent> {
    @Inject
    private ControlPlaneGatewayEndpoint gatewayEndpoint;

    @Override
    public void onEvent(ClientAccessTokenRevokedEvent event) {
        int closed = gatewayEndpoint.revokeCredential(event.tokenHash());
        if (closed > 0) {
            log.info("Closed {} control-plane Session(s) for a revoked credential", closed);
        }
    }
}
