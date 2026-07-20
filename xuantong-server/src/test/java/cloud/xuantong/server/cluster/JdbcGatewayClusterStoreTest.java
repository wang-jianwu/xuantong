package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ClusterQuotaAllocation;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ControlPlaneConnectionView;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcGatewayClusterStoreTest {
    @Test
    void persistsRecordSnapshotsAndFencesDuplicateGatewayIdentity() {
        try (HikariDataSource dataSource = dataSource("cluster_store")) {
            migrate(dataSource);
            JdbcGatewayClusterStore store = new JdbcGatewayClusterStore(dataSource);
            GatewayRuntimeSnapshot snapshot = snapshot("gateway-a");

            store.publish("cluster-test", "gateway-a", "runtime-a",
                    1_000L, 5_000L, snapshot);
            var loaded = store.findRecent("cluster-test", 0L);

            assertEquals(1, loaded.size());
            assertEquals("client-a", loaded.getFirst().snapshot()
                    .connections().getFirst().clientInstanceId());
            assertEquals(9_500, loaded.getFirst().snapshot()
                    .quotaAllocation().maxSessions());
            assertThrows(IllegalStateException.class, () -> store.publish(
                    "cluster-test", "gateway-a", "runtime-b",
                    2_000L, 6_000L, snapshot));

            store.publish("cluster-test", "gateway-a", "runtime-b",
                    5_001L, 10_000L, snapshot);
            assertEquals("runtime-b", store.findRecent(
                    "cluster-test", 0L).getFirst().runtimeId());
        }
    }

    private GatewayRuntimeSnapshot snapshot(String gatewayId) {
        ControlPlaneConnectionView connection = new ControlPlaneConnectionView(
                "session-a", "client-a", "orders", "principal-a", "tenant-a",
                "public", "DEFAULT_GROUP", "2.0.0", "xuantong-client-java",
                "tcp-default", List.of("config-fetch-v1"), "10.0.0.1",
                gatewayId, 1L, null, 900L, 1_000L);
        return new GatewayRuntimeSnapshot(
                "cluster-test", gatewayId, 1L, 1_000L,
                1, false, List.of(connection),
                Map.of("tenant-a", 1), Map.of("credential-a", 1),
                Map.of("tenant-a", 1), 0, 1, 0, 1,
                0L, 0L, 0L,
                new ClusterQuotaAllocation(
                        true, true, "cluster-test", 2, 5,
                        1_000L, 5_000L,
                        9_500, 4_750, 475, 4_750, 950, 950, 1_900));
    }

    private void migrate(HikariDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .table("test_schema_history")
                .load()
                .migrate();
    }

    private HikariDataSource dataSource(String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        return new HikariDataSource(config);
    }
}
