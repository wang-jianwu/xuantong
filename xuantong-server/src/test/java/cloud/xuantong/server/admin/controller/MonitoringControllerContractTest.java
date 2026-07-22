package cloud.xuantong.server.admin.controller;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
import cloud.xuantong.security.service.ClientAccessTokenService;
import cloud.xuantong.server.cluster.GatewayClusterProperties;
import cloud.xuantong.server.cluster.GatewayClusterSummary;
import cloud.xuantong.server.cluster.GatewayClusterView;
import cloud.xuantong.server.cluster.GatewayClusterViewProvider;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.server.state.StateStorageTelemetry;
import cloud.xuantong.server.state.management.ConfigStateManagementService;
import cloud.xuantong.server.state.management.RegistryStateManagementService;
import org.apache.ratis.util.MD5FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.handle.ContextEmpty;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringControllerContractTest {
    @TempDir
    Path tempDirectory;

    @Test
    void healthIsNotReadyWhenEnabledStatePlaneHasNoHealthyDivision() throws Exception {
        MonitoringController controller = controller(true);
        CapturingContext context = new CapturingContext();

        Map<String, Object> health = controller.health(context);

        assertEquals(503, context.status());
        assertEquals("DOWN", health.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) health.get("components");
        assertEquals("DOWN", components.get("configStatePlane"));
        assertEquals("UP", components.get("stateStorage"));
        assertEquals("UP", components.get("database"));
        assertEquals("UP", components.get("controlPlaneGateway"));
    }

    @Test
    void healthIsNotReadyWhenStateStorageFallsBelowReserve() throws Exception {
        MonitoringController controller = controller(true, Long.MAX_VALUE);
        CapturingContext context = new CapturingContext();

        Map<String, Object> health = controller.health(context);

        assertEquals(503, context.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) health.get("components");
        assertEquals("DOWN", components.get("stateStorage"));
    }

    @Test
    void metricsExposeSnapshotChecksumContract() throws Exception {
        Path snapshots = Files.createDirectories(tempDirectory.resolve("state/sm"));
        Path snapshot = snapshots.resolve("snapshot.1_2");
        Files.write(snapshot, new byte[]{1, 2, 3});
        MD5FileUtil.computeAndSaveMd5ForFile(snapshot.toFile());
        MonitoringController controller = controller(false);
        CapturingContext context = new CapturingContext();

        controller.metrics(context);

        String metrics = context.outputText();
        assertEquals("text/plain; version=0.0.4; charset=utf-8", context.contentType());
        assertTrue(metrics.contains("xuantong_state_snapshot_checksum_verified 1\n"));
        assertTrue(metrics.contains("xuantong_state_snapshot_checksum_mismatches 0\n"));
        assertTrue(metrics.contains("xuantong_state_snapshot_checksum_unverified 0\n"));
        assertTrue(metrics.contains("xuantong_state_snapshot_checksum_failure_total 0\n"));
        assertTrue(metrics.contains("xuantong_state_storage_free_minimum_bytes 0\n"));
        assertTrue(metrics.contains("xuantong_state_storage_free_above_minimum 1\n"));
        assertTrue(metrics.contains(
                "xuantong_control_plane_request_duration_seconds_bucket{le=\"0.005\"} 0\n"));
        assertTrue(metrics.contains(
                "xuantong_control_plane_request_duration_seconds_bucket{le=\"+Inf\"} 0\n"));
        assertTrue(metrics.contains(
                "xuantong_control_plane_watch_ack_duration_seconds_count 0\n"));
    }

    @Test
    void dashboardOverviewUsesLightweightSummaryAndExplicitHeapValues() throws Exception {
        MonitoringController controller = controller(false);

        Map<String, Object> overview = controller.overview();

        assertEquals("UP", overview.get("status"));
        assertEquals("test", overview.get("version"));
        assertEquals(0L, overview.get("controlPlaneClients"));
        assertEquals(0, overview.get("controlPlaneSessions"));
        assertTrue(((Number) overview.get("heapUsedBytes")).longValue() > 0L);
        assertTrue(((Number) overview.get("heapCommittedBytes")).longValue() > 0L);
    }

    private MonitoringController controller(boolean configStateEnabled) throws Exception {
        return controller(configStateEnabled, 0L);
    }

    private MonitoringController controller(
            boolean configStateEnabled, long storageFreeSpaceMinBytes) throws Exception {
        Path stateDirectory = Files.createDirectories(tempDirectory.resolve("state"));
        ConfigStatePlaneProperties configProperties = new ConfigStatePlaneProperties(
                configStateEnabled,
                "state-1",
                "config-default",
                "state-1@127.0.0.1:9101",
                stateDirectory,
                true);
        setField(configProperties, "storageFreeSpaceMinBytes", storageFreeSpaceMinBytes);
        RegistryStatePlaneProperties registryProperties =
                new RegistryStatePlaneProperties(false, "registry-default", 3_000, 120_000);
        RegistryStateManagementService registryState =
                new RegistryStateManagementService();
        setField(registryState, "properties", registryProperties);

        ControlPlaneGatewayRuntime gatewayRuntime = new ControlPlaneGatewayRuntime();
        GatewayClusterProperties clusterProperties = new GatewayClusterProperties();
        setField(clusterProperties, "deployment", "standalone");

        StateStorageTelemetry telemetry = new StateStorageTelemetry();
        setField(telemetry, "properties", configProperties);

        MonitoringController controller = new MonitoringController();
        setField(controller, "dataSource", healthyDataSource());
        setField(controller, "configStateManagementService",
                new ConfigStateManagementService());
        setField(controller, "registryState", registryState);
        setField(controller, "registryStateProperties", registryProperties);
        setField(controller, "tokenService", new ClientAccessTokenService());
        setField(controller, "gatewayRuntime", gatewayRuntime);
        setField(controller, "gatewayClusterProperties", clusterProperties);
        GatewayClusterSummary summary = GatewayClusterSummary.local(
                gatewayRuntime.localSummary());
        setField(controller, "gatewayClusterViewProvider",
                new GatewayClusterViewProvider() {
                    @Override
                    public GatewayClusterView currentView() {
                        throw new AssertionError(
                                "monitoring endpoints must not build a connection view");
                    }

                    @Override
                    public GatewayClusterSummary currentSummary() {
                        return summary;
                    }
                });
        setField(controller, "configStateProperties", configProperties);
        setField(controller, "configStateRuntime", new ControlStatePlaneRuntime());
        setField(controller, "stateStorageTelemetry", telemetry);
        setField(controller, "applicationVersion", "test");
        return controller;
    }

    private DataSource healthyDataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isValid" -> true;
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> "getConnection".equals(method.getName())
                        ? connection : defaultValue(method.getReturnType()));
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class CapturingContext extends ContextEmpty {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private String responseContentType;

        @Override
        public String contentType() {
            return responseContentType;
        }

        @Override
        protected void contentTypeDoSet(String contentType) {
            responseContentType = contentType;
        }

        @Override
        public void output(byte[] bytes) {
            output.writeBytes(bytes);
        }

        private String outputText() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
