package cloud.xuantong.gateway.socketd;

/** Authenticates one control-plane session without coupling the Gateway to a token store. */
public interface ControlPlaneAuthenticator {
    ControlPlanePrincipal authenticate(
            String credential,
            String tenant,
            String namespaceId,
            String groupName);

    ControlPlanePrincipal revalidate(ControlPlanePrincipal principal);
}
