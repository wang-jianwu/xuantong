package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ClusterQuotaAllocation;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ControlPlaneConnectionView;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record GatewayClusterView(
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
        int inFlightRequests,
        int subscriptions,
        int pendingWatchAcknowledgements,
        long sessionQuotaRejectedTotal,
        long rateLimitedTotal,
        long clusterCoordinationRejectedTotal,
        Map<String, Integer> tenantSessionCounts,
        Map<String, Integer> credentialSessionCounts,
        Map<String, Integer> tenantSubscriptionCounts,
        ClusterQuotaAllocation localQuotaAllocation,
        List<GatewaySummary> gateways,
        List<ControlPlaneConnectionView> connections) {

    public static GatewayClusterView local(GatewayRuntimeSnapshot snapshot) {
        return aggregate(
                snapshot.clusterId(), snapshot.gatewayId(),
                System.currentTimeMillis(), false, List.of(), snapshot, Long.MAX_VALUE);
    }

    static GatewayClusterView aggregate(
            String clusterId,
            String localGatewayId,
            long nowEpochMs,
            boolean coordinationReady,
            List<GatewayClusterStore.StoredGatewaySnapshot> storedSnapshots,
            GatewayRuntimeSnapshot localSnapshot,
            long localLeaseExpiresAtEpochMs) {
        Map<String, GatewayClusterStore.StoredGatewaySnapshot> active = new LinkedHashMap<>();
        int stale = 0;
        for (GatewayClusterStore.StoredGatewaySnapshot stored : storedSnapshots) {
            if (stored.leaseExpiresAtEpochMs() > nowEpochMs) {
                active.put(stored.gatewayId(), stored);
            } else {
                stale++;
            }
        }
        if (coordinationReady && localLeaseExpiresAtEpochMs > nowEpochMs) {
            active.put(localGatewayId, new GatewayClusterStore.StoredGatewaySnapshot(
                    clusterId, localGatewayId, "local-live",
                    localLeaseExpiresAtEpochMs, localSnapshot));
        }
        if (active.isEmpty()) {
            active.put(localGatewayId, new GatewayClusterStore.StoredGatewaySnapshot(
                    clusterId, localGatewayId, "local-fallback",
                    Long.MAX_VALUE, localSnapshot));
        }

        List<GatewaySummary> gateways = new ArrayList<>();
        Map<String, ControlPlaneConnectionView> physicalSessions = new LinkedHashMap<>();
        Map<String, ControlPlaneConnectionView> logicalClients = new LinkedHashMap<>();
        Map<String, Integer> tenantSessions = new LinkedHashMap<>();
        Map<String, Integer> credentialSessions = new LinkedHashMap<>();
        Map<String, Integer> tenantSubscriptions = new LinkedHashMap<>();
        int sessions = 0;
        int inFlight = 0;
        int subscriptions = 0;
        int pendingAcks = 0;
        int truncated = 0;
        long sessionRejects = 0L;
        long rateLimits = 0L;
        long coordinationRejects = 0L;
        for (GatewayClusterStore.StoredGatewaySnapshot stored : active.values()) {
            GatewayRuntimeSnapshot snapshot = stored.snapshot();
            sessions += snapshot.totalConnectionCount();
            inFlight += snapshot.inFlightRequests();
            subscriptions += snapshot.activeSubscriptions();
            pendingAcks += snapshot.pendingWatchAcknowledgements();
            sessionRejects += snapshot.sessionQuotaRejectedTotal();
            rateLimits += snapshot.rateLimitedTotal();
            coordinationRejects += snapshot.clusterCoordinationRejectedTotal();
            mergeCounts(tenantSessions, snapshot.tenantSessionCounts());
            mergeCounts(credentialSessions, snapshot.credentialSessionCounts());
            mergeCounts(tenantSubscriptions, snapshot.tenantSubscriptionCounts());
            if (snapshot.connectionDetailsTruncated()) {
                truncated++;
            }
            gateways.add(new GatewaySummary(
                    stored.gatewayId(), snapshot.transportGeneration(),
                    snapshot.capturedAt(), stored.leaseExpiresAtEpochMs(),
                    snapshot.totalConnectionCount(), snapshot.logicalClients(),
                    snapshot.activeSubscriptions(), snapshot.connectionDetailsTruncated(),
                    snapshot.quotaAllocation()));
            for (ControlPlaneConnectionView connection : snapshot.connections()) {
                String physicalKey = stored.gatewayId() + '\u0000' + connection.sessionId();
                physicalSessions.merge(physicalKey, connection,
                        GatewayClusterView::newerConnection);
                if (connection.clientInstanceId() != null
                        && !connection.clientInstanceId().isBlank()) {
                    logicalClients.merge(connection.clientInstanceId(), connection,
                            GatewayClusterView::newerConnection);
                }
            }
        }
        gateways.sort(Comparator.comparing(GatewaySummary::gatewayId));
        List<ControlPlaneConnectionView> connections = new ArrayList<>(
                physicalSessions.values());
        connections.sort(Comparator
                .comparingLong(ControlPlaneConnectionView::lastActiveAt).reversed()
                .thenComparing(ControlPlaneConnectionView::gatewayId)
                .thenComparing(ControlPlaneConnectionView::sessionId));
        boolean aggregated = coordinationReady;
        return new GatewayClusterView(
                aggregated ? "CLUSTER_AGGREGATED" : "CURRENT_GATEWAY_FALLBACK",
                aggregated,
                aggregated && truncated == 0,
                clusterId,
                nowEpochMs,
                active.size(),
                stale,
                truncated,
                sessions,
                logicalClients.size(),
                inFlight,
                subscriptions,
                pendingAcks,
                sessionRejects,
                rateLimits,
                coordinationRejects,
                Map.copyOf(tenantSessions),
                Map.copyOf(credentialSessions),
                Map.copyOf(tenantSubscriptions),
                localSnapshot.quotaAllocation(),
                List.copyOf(gateways),
                List.copyOf(connections));
    }

    public Map<String, ControlPlaneConnectionView> logicalConnections(
            String namespaceId, String groupName, String requiredCapability) {
        Map<String, ControlPlaneConnectionView> result = new LinkedHashMap<>();
        for (ControlPlaneConnectionView connection : connections) {
            if (!namespaceId.equals(connection.namespaceId())
                    || !groupName.equals(connection.groupName())
                    || connection.clientInstanceId() == null
                    || connection.clientInstanceId().isBlank()
                    || !connection.capabilities().contains(requiredCapability)) {
                continue;
            }
            result.merge(connection.clientInstanceId(), connection,
                    GatewayClusterView::newerConnection);
        }
        return result;
    }

    public Set<String> gatewayIds() {
        Set<String> values = new LinkedHashSet<>();
        for (GatewaySummary gateway : gateways) {
            values.add(gateway.gatewayId());
        }
        return Set.copyOf(values);
    }

    private static void mergeCounts(
            Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static ControlPlaneConnectionView newerConnection(
            ControlPlaneConnectionView left, ControlPlaneConnectionView right) {
        int active = Long.compare(left.lastActiveAt(), right.lastActiveAt());
        if (active != 0) {
            return active > 0 ? left : right;
        }
        int generation = Long.compare(
                left.connectionGeneration(), right.connectionGeneration());
        if (generation != 0) {
            return generation > 0 ? left : right;
        }
        return left.gatewayId().compareTo(right.gatewayId()) <= 0 ? left : right;
    }

    public record GatewaySummary(
            String gatewayId,
            long transportGeneration,
            long capturedAtEpochMs,
            long leaseExpiresAtEpochMs,
            int sessions,
            long logicalClients,
            int subscriptions,
            boolean connectionDetailsTruncated,
            ClusterQuotaAllocation quotaAllocation) {
    }
}
