package cloud.xuantong.server.security;

import cloud.xuantong.security.service.ClientAccessTokenService;
import cloud.xuantong.gateway.socketd.ControlPlaneAuthenticationException;
import cloud.xuantong.gateway.socketd.ControlPlaneAuthenticator;
import cloud.xuantong.gateway.socketd.ControlPlanePrincipal;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

@Component
public class ClientAccessTokenControlPlaneAuthenticator
        implements ControlPlaneAuthenticator {
    @Inject
    private ClientAccessTokenService tokenService;

    public ClientAccessTokenControlPlaneAuthenticator() {
    }

    ClientAccessTokenControlPlaneAuthenticator(ClientAccessTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public ControlPlanePrincipal authenticate(
            String credential,
            String tenant,
            String namespaceId,
            String groupName) {
        return principal(tokenService.authenticateAndRecord(
                credential, tenant, namespaceId, groupName));
    }

    @Override
    public ControlPlanePrincipal revalidate(ControlPlanePrincipal principal) {
        return principal(tokenService.authenticateFingerprint(
                principal.credentialFingerprint(),
                principal.tenant(),
                principal.namespaceId(),
                principal.groupName()));
    }

    private ControlPlanePrincipal principal(
            ClientAccessTokenService.AuthenticatedToken authenticated) {
        if (authenticated == null) {
            throw new ControlPlaneAuthenticationException(
                    "Control-plane credential is invalid, expired, revoked, or outside its scope");
        }
        return new ControlPlanePrincipal(
                authenticated.principalId(),
                authenticated.tenant(),
                authenticated.namespaceId(),
                authenticated.groupName(),
                authenticated.fingerprint(),
                authenticated.expiresAtEpochMs(),
                authenticated.anonymous());
    }
}
