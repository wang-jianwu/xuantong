package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ClusterQuotaAllocation;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ControlPlaneConnectionView;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSummary;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lightweight cluster counters for health, metrics and the administration overview. */
public record GatewayClusterSummary(
        String scope,
        boolean clusterAggregated,
        boolean clusterViewComplete,
        String clusterId,
        long generatedAtEpochMs,
        int activeGatewayCount,
        int staleGatewayCount,
        int truncatedGatewayCount,
        int sessions,
        long logicalClients,
        ClusterQuotaAllocation localQuotaAllocation) {

    public static GatewayClusterSummary local(GatewayRuntimeSummary summary) {
        return new GatewayClusterSummary(
                "CURRENT_GATEWAY_FALLBACK",
                false,
                false,
                summary.clusterId(),
                summary.capturedAt(),
                1,
                0,
                0,
                summary.totalConnectionCount(),
                summary.logicalClients(),
                summary.quotaAllocation());
    }

    public static GatewayClusterSummary from(GatewayClusterView view) {
        return new GatewayClusterSummary(
                view.scope(),
                view.clusterAggregated(),
                view.clusterViewComplete(),
                view.clusterId(),
                view.generatedAtEpochMs(),
                view.activeGatewayCount(),
                view.staleGatewayCount(),
                view.truncatedGatewayCount(),
                view.sessions(),
                view.logicalClients(),
                view.localQuotaAllocation());
    }

    static GatewayClusterSummary aggregate(
            String clusterId,
            long nowEpochMs,
            boolean coordinationReady,
            List<GatewayClusterStore.StoredGatewaySnapshot> storedSnapshots,
            GatewayRuntimeSummary localSummary,
            ClusterQuotaAllocation localQuotaAllocation) {
        int activeGateways = 0;
        int staleGateways = 0;
        int truncatedGateways = 0;
        int sessions = 0;
        Set<String> logicalClients = new HashSet<>();
        for (GatewayClusterStore.StoredGatewaySnapshot stored : storedSnapshots) {
            if (stored.leaseExpiresAtEpochMs() <= nowEpochMs) {
                staleGateways++;
                continue;
            }
            activeGateways++;
            GatewayRuntimeSnapshot snapshot = stored.snapshot();
            sessions += snapshot.totalConnectionCount();
            if (snapshot.connectionDetailsTruncated()) {
                truncatedGateways++;
            }
            for (ControlPlaneConnectionView connection : snapshot.connections()) {
                if (connection.clientInstanceId() != null
                        && !connection.clientInstanceId().isBlank()) {
                    logicalClients.add(connection.clientInstanceId());
                }
            }
        }
        if (activeGateways == 0) {
            return local(localSummary);
        }
        return new GatewayClusterSummary(
                coordinationReady ? "CLUSTER_AGGREGATED" : "CURRENT_GATEWAY_FALLBACK",
                coordinationReady,
                coordinationReady && truncatedGateways == 0,
                clusterId,
                nowEpochMs,
                activeGateways,
                staleGateways,
                truncatedGateways,
                sessions,
                logicalClients.size(),
                localQuotaAllocation);
    }

    GatewayClusterSummary withCoordinationReady(boolean ready, long nowEpochMs) {
        return new GatewayClusterSummary(
                ready ? "CLUSTER_AGGREGATED" : "CURRENT_GATEWAY_FALLBACK",
                ready,
                ready && truncatedGatewayCount == 0,
                clusterId,
                nowEpochMs,
                activeGatewayCount,
                staleGatewayCount,
                truncatedGatewayCount,
                sessions,
                logicalClients,
                localQuotaAllocation);
    }
}
