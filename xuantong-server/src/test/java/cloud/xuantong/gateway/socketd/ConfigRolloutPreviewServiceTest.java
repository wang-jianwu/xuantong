package cloud.xuantong.gateway.socketd;

import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigDecisionState;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigValueState;
import cloud.xuantong.config.state.ReleaseDecision;
import cloud.xuantong.config.state.ResolvedConfigOperation;
import cloud.xuantong.config.state.RolloutRule;
import cloud.xuantong.config.state.RolloutRuleStatus;
import cloud.xuantong.config.state.RolloutSelectorType;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.server.state.management.ConfigRolloutPreviewService;
import cloud.xuantong.server.state.management.ConfigStateAccess;
import cloud.xuantong.server.cluster.GatewayClusterView;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigRolloutPreviewServiceTest {
    @Test
    void rejectsPreviewWhenAuthoritativeConfigStateIsUnavailable() {
        ConfigRolloutPreviewService service = new ConfigRolloutPreviewService(
                runtimeWithClients(), null);

        assertThrows(IllegalStateException.class, () -> service.preview(
                "public",
                "DEFAULT_GROUP",
                "app.yml",
                ConfigRolloutPolicy.percentage(10),
                "preview-key"));
    }

    @Test
    void previewsExactTargetsWithinCurrentGatewayScope() {
        ControlPlaneGatewayRuntime runtime = runtimeWithClients();
        ConfigRolloutPreviewService service = previewService(runtime);

        var preview = service.preview(
                "public",
                "DEFAULT_GROUP",
                "app.yml",
                ConfigRolloutPolicy.clientInstances(List.of("client-b")),
                "preview-key");

        assertEquals("CURRENT_GATEWAY", preview.scope());
        assertFalse(preview.clusterAggregated());
        assertEquals("cluster-test", preview.clusterId());
        assertEquals(2, preview.visibleInstanceCount());
        assertEquals(1, preview.matchedInstanceCount());
        assertTrue(preview.instances().stream()
                .filter(x -> x.clientInstanceId().equals("client-b"))
                .findFirst().orElseThrow().matched());
    }

    @Test
    void percentagePreviewWarnsWhenExpectedMatchesAreBelowOne() {
        ConfigRolloutPreviewService service = previewService(runtimeWithClients());

        var preview = service.preview(
                "public", "DEFAULT_GROUP",
                "app.yml",
                ConfigRolloutPolicy.percentage(10), "stable-preview-key");

        assertEquals(0.2D, preview.expectedMatchedInstanceCount(), 0.0001D);
        assertTrue(preview.smallSampleWarning());
        assertTrue(preview.instances().stream()
                .allMatch(x -> x.percentageBucket() != null));
    }

    @Test
    void clusterPreviewUsesConnectionsFromAllGatewaysWithTheAuthoritativeSelector() {
        ControlPlaneGatewayRuntime gatewayA = new ControlPlaneGatewayRuntime(
                new ControlPlaneGatewayProperties(
                        "cluster-test", "gateway-a", 1L, 5_000L));
        ControlPlaneGatewayRuntime gatewayB = new ControlPlaneGatewayRuntime(
                new ControlPlaneGatewayProperties(
                        "cluster-test", "gateway-b", 1L, 5_000L));
        addClient(gatewayA, "session-a", "client-a", "orders", "10.0.0.1");
        addClient(gatewayB, "session-b", "client-b", "orders", "10.0.0.2");
        GatewayClusterView clusterView = new GatewayClusterView(
                "CLUSTER_AGGREGATED", true, true, "cluster-test",
                System.currentTimeMillis(), 2, 0, 0,
                2, 2, 0, 0, 0,
                0L, 0L, 0L,
                Map.of("public", 2), Map.of(), Map.of(),
                ControlPlaneGatewayRuntime.ClusterQuotaAllocation.disabled(),
                List.of(),
                List.of(gatewayA.connections().getFirst(),
                        gatewayB.connections().getFirst()));
        ConfigRolloutPreviewService service = new ConfigRolloutPreviewService(
                () -> clusterView,
                new FixedStateAccess(new ReleaseDecision(
                        new ConfigKey("public", "DEFAULT_GROUP", "app.yml"),
                        1, 1, List.of())));

        var preview = service.preview(
                "public", "DEFAULT_GROUP", "app.yml",
                ConfigRolloutPolicy.clientInstances(List.of("client-b")),
                "preview-key");

        assertEquals("CLUSTER_AGGREGATED", preview.scope());
        assertTrue(preview.clusterAggregated());
        assertEquals(2, preview.activeGatewayCount());
        assertEquals(2, preview.visibleInstanceCount());
        assertEquals(1, preview.matchedInstanceCount());
        assertTrue(preview.instances().stream()
                .anyMatch(instance -> instance.clientInstanceId().equals("client-b")
                        && instance.gatewayId().equals("gateway-b")
                        && instance.matched()));
    }

    @Test
    void rejectsGrayPreviewWhenClusterConnectionViewIsIncomplete() {
        GatewayClusterView incomplete = new GatewayClusterView(
                "CURRENT_GATEWAY_FALLBACK", false, false, "cluster-test",
                System.currentTimeMillis(), 1, 1, 0,
                0, 0, 0, 0, 0,
                0L, 0L, 0L,
                Map.of(), Map.of(), Map.of(),
                ControlPlaneGatewayRuntime.ClusterQuotaAllocation.disabled(),
                List.of(), List.of());
        ConfigRolloutPreviewService service = new ConfigRolloutPreviewService(
                () -> incomplete,
                new FixedStateAccess(new ReleaseDecision(
                        new ConfigKey("public", "DEFAULT_GROUP", "app.yml"),
                        1, 1, List.of())));

        assertThrows(IllegalStateException.class, () -> service.preview(
                "public", "DEFAULT_GROUP", "app.yml",
                ConfigRolloutPolicy.percentage(10), "preview-key"));
    }

    @Test
    void limitsReturnedRowsButKeepsFullGatewayCountsAndMatchedTargets() {
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(
                new ControlPlaneGatewayProperties(
                        "cluster-test", "gateway-test", 1L, 5_000L));
        for (int i = 0; i < 1_005; i++) {
            addClient(runtime,
                    "session-" + i,
                    "client-" + String.format("%04d", i),
                    "orders",
                    "10.0." + (i / 255) + "." + (i % 255));
        }
        ConfigRolloutPreviewService service = previewService(runtime);

        var preview = service.preview(
                "public",
                "DEFAULT_GROUP",
                "app.yml",
                ConfigRolloutPolicy.clientInstances(List.of("client-1004")),
                "preview-key");

        assertEquals(1_005, preview.visibleInstanceCount());
        assertEquals(1, preview.matchedInstanceCount());
        assertEquals(1_000, preview.returnedInstanceCount());
        assertEquals(1_000, preview.instances().size());
        assertTrue(preview.instancesTruncated());
        assertTrue(preview.instances().stream()
                .anyMatch(x -> x.clientInstanceId().equals("client-1004") && x.matched()));
    }

    @Test
    void currentSelectionUsesTheSameAuthoritativeRuleMatcher() {
        ControlPlaneGatewayRuntime runtime = runtimeWithClients();
        ReleaseDecision decision = new ReleaseDecision(
                new ConfigKey("public", "DEFAULT_GROUP", "app.yml"),
                8,
                3,
                List.of(new RolloutRule(
                        "rule-client-b",
                        1,
                        1,
                        100,
                        4,
                        "rollout-key",
                        RolloutSelectorType.CLIENT_INSTANCE_ID,
                        "",
                        List.of("client-b"),
                        0,
                        7,
                        RolloutRuleStatus.ACTIVE,
                        8)));
        ConfigRolloutPreviewService service = new ConfigRolloutPreviewService(
                runtime, new FixedStateAccess(decision));

        var current = service.currentSelections(
                "public", "DEFAULT_GROUP", "app.yml");

        assertEquals(8, current.decisionRevision());
        assertEquals(ConfigDecisionState.ACTIVE, current.decisionState());
        assertEquals(3, current.stableContentRevision());
        var selected = current.instances().stream()
                .filter(x -> x.clientInstanceId().equals("client-b"))
                .findFirst().orElseThrow();
        var baseline = current.instances().stream()
                .filter(x -> x.clientInstanceId().equals("client-a"))
                .findFirst().orElseThrow();
        assertEquals("rule-client-b", selected.matchedRuleId());
        assertEquals(ConfigValueState.ACTIVE, selected.valueState());
        assertEquals(4, selected.contentRevision());
        assertEquals("", baseline.matchedRuleId());
        assertEquals(3, baseline.contentRevision());
    }

    @Test
    void currentSelectionExplicitlyReportsAuthoritativeTombstone() {
        ControlPlaneGatewayRuntime runtime = runtimeWithClients();
        ReleaseDecision decision = new ReleaseDecision(
                new ConfigKey("public", "DEFAULT_GROUP", "app.yml"),
                9,
                ConfigDecisionState.TOMBSTONE,
                0,
                List.of());
        ConfigRolloutPreviewService service = new ConfigRolloutPreviewService(
                runtime, new FixedStateAccess(decision));

        var current = service.currentSelections(
                "public", "DEFAULT_GROUP", "app.yml");

        assertEquals(ConfigDecisionState.TOMBSTONE, current.decisionState());
        assertEquals(0, current.stableContentRevision());
        assertEquals(2, current.visibleInstanceCount());
        assertTrue(current.instances().stream()
                .allMatch(x -> x.valueState() == ConfigValueState.TOMBSTONE
                        && x.contentRevision() == 0
                        && x.matchedRuleId().isBlank()));
    }

    @Test
    void rejectsRolloutPreviewForAuthoritativeTombstone() {
        ReleaseDecision decision = new ReleaseDecision(
                new ConfigKey("public", "DEFAULT_GROUP", "app.yml"),
                9,
                ConfigDecisionState.TOMBSTONE,
                0,
                List.of());
        ConfigRolloutPreviewService service = new ConfigRolloutPreviewService(
                runtimeWithClients(), new FixedStateAccess(decision));

        assertThrows(IllegalStateException.class, () -> service.preview(
                "public",
                "DEFAULT_GROUP",
                "app.yml",
                ConfigRolloutPolicy.percentage(10),
                "preview-key"));
    }

    private ControlPlaneGatewayRuntime runtimeWithClients() {
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(
                new ControlPlaneGatewayProperties(
                        "cluster-test", "gateway-test", 1L, 5_000L));
        addClient(runtime, "session-a", "client-a", "orders", "10.0.0.1");
        addClient(runtime, "session-b", "client-b", "orders", "10.0.0.2");
        return runtime;
    }

    private ConfigRolloutPreviewService previewService(ControlPlaneGatewayRuntime runtime) {
        return new ConfigRolloutPreviewService(runtime, new FixedStateAccess(
                new ReleaseDecision(
                        new ConfigKey("public", "DEFAULT_GROUP", "app.yml"),
                        1,
                        1,
                        List.of())));
    }

    private void addClient(
            ControlPlaneGatewayRuntime runtime,
            String sessionId,
            String clientInstanceId,
            String applicationName,
            String remoteIp) {
        runtime.sessionOpened(sessionId, 1L, remoteIp);
        runtime.sessionIdentified(sessionId, HelloRequest.newBuilder()
                .setClientInstanceId(clientInstanceId)
                .setApplicationName(applicationName)
                .setGroupName("DEFAULT_GROUP")
                .setClientVersion("2.0.0")
                .setSdkName("xuantong-client-java")
                .setTransportPool("tcp-default")
                .addCapabilities("config-fetch-v1")
                .build());
        runtime.sessionAuthenticated(sessionId, new ControlPlanePrincipal(
                clientInstanceId,
                "public",
                "public",
                "DEFAULT_GROUP",
                clientInstanceId,
                0L,
                false));
    }

    private record FixedStateAccess(ReleaseDecision decision) implements ConfigStateAccess {
        @Override public boolean available() { return true; }
        @Override public StateGroupId groupId() { return StateGroupId.config("config-test"); }
        @Override public ReleaseDecision currentDecision(ConfigKey key) { return decision; }
        @Override public ApplyResult submit(StateCommand command) {
            throw new UnsupportedOperationException();
        }
        @Override public ResolvedConfigOperation resolve(ConfigActor actor, String operationId) {
            throw new UnsupportedOperationException();
        }
    }
}
