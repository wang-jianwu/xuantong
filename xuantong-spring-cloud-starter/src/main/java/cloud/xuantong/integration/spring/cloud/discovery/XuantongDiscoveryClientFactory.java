package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.XuantongDiscoveryClient;
import cloud.xuantong.client.transport.impl.SharedDiscoveryConnection;
import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;

/** Creates service-scoped Discovery Agents over the shared Xuantong control-plane contract. */
public class XuantongDiscoveryClientFactory implements AutoCloseable {
    private final XuantongSpringCloudProperties properties;
    private final long heartbeatIntervalMs;
    private final SharedDiscoveryConnection sharedConnection;

    public XuantongDiscoveryClientFactory(
            XuantongSpringCloudProperties properties, ClientIdentity clientIdentity) {
        this.properties = properties;
        this.heartbeatIntervalMs = properties.getDiscovery()
                .getHeartbeatInterval().toMillis();
        if (heartbeatIntervalMs <= 0L) {
            throw new IllegalArgumentException(
                    XuantongSpringCloudProperties.PREFIX
                            + ".discovery.heartbeat-interval must be positive");
        }
        long leaseTtlMs;
        try {
            leaseTtlMs = Math.max(
                    30_000L, Math.multiplyExact(heartbeatIntervalMs, 3L));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    XuantongSpringCloudProperties.PREFIX
                            + ".discovery.heartbeat-interval is too large", e);
        }
        this.sharedConnection = new SharedDiscoveryConnection(
                clientIdentity,
                properties.discoveryControlPlaneOptions(),
                leaseTtlMs);
    }

    public XuantongDiscoveryOperations create(String serviceName) {
        return new XuantongDiscoveryAgent(new XuantongDiscoveryClient(
                properties.getServerAddresses(),
                properties.getNamespace(),
                properties.getGroup(),
                serviceName,
                properties.getAccessToken(),
                heartbeatIntervalMs,
                sharedConnection.newServiceTransport()));
    }

    @Override
    public void close() {
        sharedConnection.close();
    }
}
