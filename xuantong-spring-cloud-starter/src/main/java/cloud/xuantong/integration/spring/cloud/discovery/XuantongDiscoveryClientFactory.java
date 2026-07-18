package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.XuantongDiscoveryClient;
import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;

/** Creates service-scoped Discovery Agents over the shared Xuantong control-plane contract. */
public class XuantongDiscoveryClientFactory {
    private final XuantongSpringCloudProperties properties;
    private final ClientIdentity clientIdentity;

    public XuantongDiscoveryClientFactory(
            XuantongSpringCloudProperties properties, ClientIdentity clientIdentity) {
        this.properties = properties;
        this.clientIdentity = clientIdentity;
    }

    public XuantongDiscoveryOperations create(String serviceName) {
        long heartbeatIntervalMs = properties.getDiscovery()
                .getHeartbeatInterval().toMillis();
        if (heartbeatIntervalMs <= 0L) {
            throw new IllegalArgumentException(
                    XuantongSpringCloudProperties.PREFIX
                            + ".discovery.heartbeat-interval must be positive");
        }
        return new XuantongDiscoveryAgent(new XuantongDiscoveryClient(
                properties.getServerAddresses(),
                properties.getNamespace(),
                properties.getGroup(),
                serviceName,
                properties.getAccessToken(),
                heartbeatIntervalMs,
                clientIdentity,
                properties.discoveryControlPlaneOptions()));
    }
}
