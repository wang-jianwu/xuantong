package cloud.xuantong.server.state.management;

import cloud.xuantong.config.management.model.ConfigRolloutPolicy;
import cloud.xuantong.config.management.model.ReleaseType;
import cloud.xuantong.config.state.ConfigClientIdentity;
import cloud.xuantong.config.state.ConfigDecisionState;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigReleaseSelector;
import cloud.xuantong.config.state.ConfigValueState;
import cloud.xuantong.config.state.ReleaseDecision;
import cloud.xuantong.config.state.RolloutRule;
import cloud.xuantong.config.state.RolloutRuleStatus;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ControlPlaneConnectionView;
import cloud.xuantong.server.cluster.GatewayClusterView;
import cloud.xuantong.server.cluster.GatewayClusterViewProvider;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Cluster-wide rollout preview evaluated with the authoritative selector implementation. */
@Component
public final class ConfigRolloutPreviewService {
    private static final String CONFIG_FETCH_CAPABILITY = "config-fetch-v1";
    private static final int MAX_RETURNED_INSTANCES = 1_000;

    @Inject
    private GatewayClusterViewProvider clusterViewProvider;
    @Inject
    private ConfigStateAccess stateAccess;
    private boolean requireCompleteClusterView = true;

    public ConfigRolloutPreviewService() {
    }

    public ConfigRolloutPreviewService(
            ControlPlaneGatewayRuntime gatewayRuntime,
            ConfigStateAccess stateAccess) {
        this.clusterViewProvider = () -> GatewayClusterView.local(
                gatewayRuntime.localSnapshot(20_000));
        this.stateAccess = stateAccess;
        this.requireCompleteClusterView = false;
    }

    public ConfigRolloutPreviewService(
            GatewayClusterViewProvider clusterViewProvider,
            ConfigStateAccess stateAccess) {
        this.clusterViewProvider = clusterViewProvider;
        this.stateAccess = stateAccess;
    }

    public ConfigRolloutPreview preview(
            String namespaceId,
            String groupName,
            String dataId,
            ConfigRolloutPolicy policy,
            String requestedRolloutKey) {
        requireStateAvailable();
        ReleaseDecision decision = stateAccess.currentDecision(
                new ConfigKey(namespaceId, groupName, dataId));
        if (decision == null || !decision.active()) {
            throw new IllegalStateException(
                    "Config must have an active authoritative release before rollout preview");
        }
        String rolloutKey = requestedRolloutKey == null || requestedRolloutKey.isBlank()
                ? UUID.randomUUID().toString()
                : ConfigRolloutRuleFactory.requireRolloutKey(requestedRolloutKey);
        RolloutRule rule = ConfigRolloutRuleFactory.previewRule(rolloutKey, policy);

        GatewayClusterView clusterView = clusterViewProvider.currentView();
        if (requireCompleteClusterView
                && (!clusterView.clusterAggregated()
                || !clusterView.clusterViewComplete())) {
            throw new IllegalStateException(
                    "Cluster connection view is incomplete; retry after all Gateway "
                            + "snapshots are current and untruncated");
        }
        Map<String, ControlPlaneConnectionView> visible = clusterView.logicalConnections(
                namespaceId, groupName, CONFIG_FETCH_CAPABILITY);

        List<ConfigRolloutPreviewInstance> instances = new ArrayList<>(visible.size());
        int matched = 0;
        for (ControlPlaneConnectionView connection : visible.values()) {
            ConfigClientIdentity identity = new ConfigClientIdentity(
                    connection.clientInstanceId(),
                    connection.applicationName(),
                    connection.remoteAddress(),
                    Map.of());
            boolean selected = ConfigReleaseSelector.matches(rule, identity);
            Integer bucket = policy.type() == ReleaseType.GRAY_PERCENTAGE
                    ? ConfigReleaseSelector.percentageBucket(
                            rule, connection.clientInstanceId())
                    : null;
            if (selected) matched++;
            instances.add(new ConfigRolloutPreviewInstance(
                    connection.clientInstanceId(),
                    connection.applicationName(),
                    connection.remoteAddress(),
                    connection.gatewayId(),
                    selected,
                    bucket,
                    reason(policy, selected, bucket)));
        }
        instances.sort(Comparator
                .comparing(ConfigRolloutPreviewInstance::matched).reversed()
                .thenComparing(ConfigRolloutPreviewInstance::applicationName,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ConfigRolloutPreviewInstance::clientInstanceId));

        Set<String> targetedButNotVisible = new LinkedHashSet<>();
        if (policy.type() == ReleaseType.GRAY_CLIENT_INSTANCE) {
            targetedButNotVisible.addAll(policy.clientInstanceTargets());
            targetedButNotVisible.removeAll(visible.keySet());
        }
        double expectedMatched = policy.type() == ReleaseType.GRAY_PERCENTAGE
                ? visible.size() * policy.percentage() / 100D
                : matched;
        boolean smallSampleWarning = policy.type() == ReleaseType.GRAY_PERCENTAGE
                && expectedMatched < 1D;
        boolean instancesTruncated = instances.size() > MAX_RETURNED_INSTANCES;
        List<ConfigRolloutPreviewInstance> returnedInstances = limitedCopy(instances);
        return new ConfigRolloutPreview(
                clusterView.scope(),
                clusterView.clusterAggregated(),
                clusterView.clusterViewComplete(),
                clusterView.clusterId(),
                clusterView.activeGatewayCount(),
                clusterView.staleGatewayCount(),
                clusterView.truncatedGatewayCount(),
                rolloutKey,
                policy.type().name(),
                visible.size(),
                matched,
                returnedInstances.size(),
                instancesTruncated,
                MAX_RETURNED_INSTANCES,
                expectedMatched,
                smallSampleWarning,
                List.copyOf(targetedButNotVisible),
                returnedInstances);
    }

    public ConfigCurrentSelectionView currentSelections(
            String namespaceId, String groupName, String dataId) {
        requireStateAvailable();
        ReleaseDecision decision = stateAccess.currentDecision(
                new ConfigKey(namespaceId, groupName, dataId));
        if (decision == null) {
            throw new IllegalArgumentException("Config has no authoritative release decision");
        }
        List<ConfigCurrentSelectionInstance> instances = new ArrayList<>();
        GatewayClusterView clusterView = clusterViewProvider.currentView();
        for (ControlPlaneConnectionView connection : clusterView.logicalConnections(
                namespaceId, groupName, CONFIG_FETCH_CAPABILITY).values()) {
            ConfigClientIdentity identity = new ConfigClientIdentity(
                    connection.clientInstanceId(),
                    connection.applicationName(),
                    connection.remoteAddress(),
                    Map.of());
            ConfigValueState valueState = decision.tombstone()
                    ? ConfigValueState.TOMBSTONE
                    : ConfigValueState.ACTIVE;
            long contentRevision = decision.stableContentRevision();
            String matchedRuleId = "";
            if (decision.active()) {
                for (RolloutRule rule : decision.rules()) {
                    if (rule.status() == RolloutRuleStatus.ACTIVE
                            && ConfigReleaseSelector.matches(rule, identity)) {
                        contentRevision = rule.targetContentRevision();
                        matchedRuleId = rule.ruleId();
                        break;
                    }
                }
            }
            instances.add(new ConfigCurrentSelectionInstance(
                    connection.clientInstanceId(),
                    connection.applicationName(),
                    connection.remoteAddress(),
                    connection.gatewayId(),
                    decision.decisionRevision(),
                    valueState,
                    contentRevision,
                    matchedRuleId));
        }
        instances.sort(Comparator
                .comparing((ConfigCurrentSelectionInstance instance) ->
                        !instance.matchedRuleId().isBlank()).reversed()
                .thenComparing(ConfigCurrentSelectionInstance::applicationName,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ConfigCurrentSelectionInstance::clientInstanceId));
        boolean instancesTruncated = instances.size() > MAX_RETURNED_INSTANCES;
        List<ConfigCurrentSelectionInstance> returnedInstances = limitedCopy(instances);
        return new ConfigCurrentSelectionView(
                clusterView.scope(),
                clusterView.clusterAggregated(),
                clusterView.clusterViewComplete(),
                clusterView.clusterId(),
                clusterView.activeGatewayCount(),
                clusterView.staleGatewayCount(),
                clusterView.truncatedGatewayCount(),
                decision.decisionRevision(),
                decision.state(),
                decision.stableContentRevision(),
                instances.size(),
                returnedInstances.size(),
                instancesTruncated,
                MAX_RETURNED_INSTANCES,
                returnedInstances);
    }

    private String reason(ConfigRolloutPolicy policy, boolean matched, Integer bucket) {
        return switch (policy.type()) {
            case GRAY_CLIENT_INSTANCE -> matched
                    ? "clientInstanceId 精确命中" : "clientInstanceId 未被选择";
            case GRAY_IP -> matched ? "远端 IP 命中" : "远端 IP 未命中";
            case GRAY_PERCENTAGE -> "bucket=" + bucket + (matched ? "，命中" : "，未命中");
            default -> throw new IllegalArgumentException(
                    "Unsupported rollout type: " + policy.type());
        };
    }

    private void requireStateAvailable() {
        if (stateAccess == null || !stateAccess.available()) {
            throw new IllegalStateException("Config State Plane is disabled or unavailable");
        }
    }

    private static <T> List<T> limitedCopy(List<T> values) {
        return List.copyOf(values.subList(0, Math.min(values.size(), MAX_RETURNED_INSTANCES)));
    }

    public record ConfigRolloutPreview(
            String scope,
            boolean clusterAggregated,
            boolean clusterViewComplete,
            String clusterId,
            int activeGatewayCount,
            int staleGatewayCount,
            int truncatedGatewayCount,
            String rolloutKey,
            String rolloutType,
            int visibleInstanceCount,
            int matchedInstanceCount,
            int returnedInstanceCount,
            boolean instancesTruncated,
            int maxReturnedInstances,
            double expectedMatchedInstanceCount,
            boolean smallSampleWarning,
            List<String> targetedButNotVisibleClientInstanceIds,
            List<ConfigRolloutPreviewInstance> instances) {
    }

    public record ConfigRolloutPreviewInstance(
            String clientInstanceId,
            String applicationName,
            String remoteIp,
            String gatewayId,
            boolean matched,
            Integer percentageBucket,
            String reason) {
    }

    public record ConfigCurrentSelectionView(
            String scope,
            boolean clusterAggregated,
            boolean clusterViewComplete,
            String clusterId,
            int activeGatewayCount,
            int staleGatewayCount,
            int truncatedGatewayCount,
            long decisionRevision,
            ConfigDecisionState decisionState,
            long stableContentRevision,
            int visibleInstanceCount,
            int returnedInstanceCount,
            boolean instancesTruncated,
            int maxReturnedInstances,
            List<ConfigCurrentSelectionInstance> instances) {
    }

    public record ConfigCurrentSelectionInstance(
            String clientInstanceId,
            String applicationName,
            String remoteIp,
            String gatewayId,
            long decisionRevision,
            ConfigValueState valueState,
            long contentRevision,
            String matchedRuleId) {
    }
}
