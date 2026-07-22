package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ClusterQuotaAllocation;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ControlPlaneConnectionView;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayClusterViewTest {
    @Test
    void aggregatesActiveGatewaysDropsExpiredSnapshotsAndDeduplicatesLogicalClients() {
        long now = 10_000L;
        GatewayRuntimeSnapshot gatewayA = snapshot(
                "gateway-a", 2, false,
                List.of(connection("gateway-a", "session-a", "client-1", 100L, 1L),
                        connection("gateway-a", "session-a2", "client-2", 200L, 1L)));
        GatewayRuntimeSnapshot gatewayB = snapshot(
                "gateway-b", 2, false,
                List.of(connection("gateway-b", "session-b", "client-1", 300L, 2L),
                        connection("gateway-b", "session-b2", "client-3", 250L, 1L)));
        GatewayRuntimeSnapshot expired = snapshot(
                "gateway-expired", 1, false,
                List.of(connection("gateway-expired", "session-x", "client-x", 500L, 1L)));

        GatewayClusterView view = GatewayClusterView.aggregate(
                "cluster-test", "gateway-a", now, true,
                List.of(
                        stored(gatewayA, now + 5_000L),
                        stored(gatewayB, now + 5_000L),
                        stored(expired, now - 1L)),
                gatewayA, now + 5_000L);

        assertEquals("CLUSTER_AGGREGATED", view.scope());
        assertTrue(view.clusterAggregated());
        assertTrue(view.clusterViewComplete());
        assertEquals(2, view.activeGatewayCount());
        assertEquals(1, view.staleGatewayCount());
        assertEquals(4, view.sessions());
        assertEquals(3, view.logicalClients());
        assertEquals(4, view.connections().size());
        assertEquals("gateway-b", view.logicalConnections(
                "public", "DEFAULT_GROUP", "config-fetch-v1")
                .get("client-1").gatewayId());
    }

    @Test
    void marksClusterViewIncompleteWhenAnyActiveGatewayTruncatesDetails() {
        long now = 20_000L;
        GatewayRuntimeSnapshot local = snapshot("gateway-a", 20, true, List.of());
        GatewayClusterView view = GatewayClusterView.aggregate(
                "cluster-test", "gateway-a", now, true,
                List.of(stored(local, now + 5_000L)),
                local, now + 5_000L);

        assertFalse(view.clusterViewComplete());
        assertEquals(1, view.truncatedGatewayCount());
        assertEquals(20, view.sessions());
        assertEquals(0, view.logicalClients());
    }

    @Test
    void localViewUsesExactSnapshotCountersWithoutReaggregatingConnectionDetails() {
        GatewayRuntimeSnapshot local = snapshot(
                "gateway-a", 20, true,
                List.of(connection(
                        "gateway-a", "session-a", "client-1", 100L, 1L)));

        GatewayClusterView view = GatewayClusterView.local(local);

        assertFalse(view.clusterAggregated());
        assertEquals("CURRENT_GATEWAY", view.scope());
        assertFalse(view.clusterViewComplete());
        assertEquals(1, view.activeGatewayCount());
        assertEquals(20, view.sessions());
        assertEquals(20L, view.logicalClients());
        assertEquals(1, view.connections().size());
        assertEquals(1, view.truncatedGatewayCount());
    }

    private GatewayClusterStore.StoredGatewaySnapshot stored(
            GatewayRuntimeSnapshot snapshot, long leaseExpiresAt) {
        return new GatewayClusterStore.StoredGatewaySnapshot(
                snapshot.clusterId(), snapshot.gatewayId(),
                "runtime-" + snapshot.gatewayId(), leaseExpiresAt, snapshot);
    }

    private GatewayRuntimeSnapshot snapshot(
            String gatewayId,
            int totalConnections,
            boolean truncated,
            List<ControlPlaneConnectionView> connections) {
        return new GatewayRuntimeSnapshot(
                "cluster-test", gatewayId, 1L, 1_000L,
                totalConnections, truncated, connections,
                Map.of("tenant-a", totalConnections),
                Map.of("credential-a", totalConnections),
                Map.of("tenant-a", 1),
                1, 1, 0, totalConnections,
                2L, 3L, 0L,
                allocation(gatewayId));
    }

    private ClusterQuotaAllocation allocation(String gatewayId) {
        return new ClusterQuotaAllocation(
                true, true, "cluster-test", 2, 5,
                1_000L, 20_000L,
                9_500, 4_750, 475, 4_750, 950, 950, 1_900);
    }

    private ControlPlaneConnectionView connection(
            String gatewayId,
            String sessionId,
            String clientInstanceId,
            long lastActiveAt,
            long generation) {
        return new ControlPlaneConnectionView(
                sessionId, clientInstanceId, "orders", "principal", "tenant-a",
                "public", "DEFAULT_GROUP", "2.0.0", "xuantong-client-java",
                "tcp-default", List.of("config-fetch-v1"), "10.0.0.1",
                gatewayId, generation, null, 50L, lastActiveAt);
    }
}
