package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;

import java.util.List;

interface GatewayClusterStore {
    void publish(
            String clusterId,
            String gatewayId,
            String runtimeId,
            long nowEpochMs,
            long leaseExpiresAtEpochMs,
            GatewayRuntimeSnapshot snapshot);

    List<StoredGatewaySnapshot> findRecent(
            String clusterId, long minimumLeaseExpiryEpochMs);

    long maxRevocationEventId();

    List<CredentialRevocation> findRevocationsAfter(long eventId, int limit);

    void cleanup(long snapshotExpiryCutoffEpochMs, long revocationCutoffEpochMs);

    record StoredGatewaySnapshot(
            String clusterId,
            String gatewayId,
            String runtimeId,
            long leaseExpiresAtEpochMs,
            GatewayRuntimeSnapshot snapshot) {
    }

    record CredentialRevocation(long eventId, String tokenHash, long revokedAtEpochMs) {
    }
}
