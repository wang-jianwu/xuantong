package cloud.xuantong.server.state.management;

import cloud.xuantong.raft.ratis.RatisGroupMembershipView;
import cloud.xuantong.raft.ratis.RatisMembershipChangeResult;
import cloud.xuantong.raft.ratis.RatisMembershipManager;
import cloud.xuantong.raft.ratis.RatisMembershipPolicy;
import cloud.xuantong.raft.ratis.RatisPeerDefinition;
import cloud.xuantong.raft.ratis.RatisStateNodeCapability;
import cloud.xuantong.raft.ratis.RatisVersionRequirement;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import cloud.xuantong.state.api.StateGroupId;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** System-admin operations for inspecting and changing the compact State topology. */
@Component
public final class StateClusterManagementService {
    @Inject
    private ConfigStatePlaneProperties configProperties;
    @Inject
    private RegistryStatePlaneProperties registryProperties;
    @Inject
    private ControlStatePlaneRuntime runtime;

    public StateClusterStatus status() throws IOException {
        List<RatisPeerDefinition> seeds = configProperties.groupDefinition().peers();
        RatisMembershipManager manager = manager();
        List<RatisGroupMembershipView> groups = new ArrayList<>();
        List<RatisStateNodeCapability> capabilities = new ArrayList<>();
        RatisGroupMembershipView configGroup = manager.inspect(
                configProperties.stateGroupId(), seeds);
        groups.add(configGroup);
        capabilities.addAll(manager.capabilities(
                configGroup.groupId(), seeds, configGroup.voters()));
        if (registryProperties.isEnabled()) {
            RatisGroupMembershipView registryGroup = manager.inspect(
                    registryProperties.stateGroupId(), seeds);
            groups.add(registryGroup);
            capabilities.addAll(manager.capabilities(
                    registryGroup.groupId(), seeds, registryGroup.voters()));
        }
        return new StateClusterStatus(
                runtime.isRunning(),
                configProperties.joinExisting(),
                configProperties.localNodeId(),
                groups,
                capabilities,
                List.copyOf(versionRequirements().values()));
    }

    public synchronized RatisMembershipChangeResult change(
            MembershipChangeRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        String operationId = required("operationId", request.operationId());
        if (operationId.length() > 256) {
            throw new IllegalArgumentException("operationId must not exceed 256 characters");
        }
        List<RatisPeerDefinition> current = List.copyOf(
                request.expectedCurrentVoters() == null
                        ? List.of() : request.expectedCurrentVoters());
        List<RatisPeerDefinition> target = List.copyOf(
                request.targetVoters() == null ? List.of() : request.targetVoters());
        RatisMembershipChangeResult result = manager().change(
                groupIds(), current, target, versionRequirements());
        runtime.refreshTopology(target);
        return result;
    }

    private RatisMembershipManager manager() {
        return new RatisMembershipManager(
                configProperties.requestTimeout(),
                configProperties.clientMaxAttempts(),
                new RatisMembershipPolicy(
                        configProperties.allowSingleNode(),
                        configProperties.membershipCatchUpTimeout(),
                        configProperties.membershipMaximumCatchUpGap()));
    }

    private List<StateGroupId> groupIds() {
        List<StateGroupId> groups = new ArrayList<>();
        groups.add(configProperties.stateGroupId());
        if (registryProperties.isEnabled()) {
            groups.add(registryProperties.stateGroupId());
        }
        return List.copyOf(groups);
    }

    private Map<StateGroupId, RatisVersionRequirement> versionRequirements() {
        Map<StateGroupId, RatisVersionRequirement> requirements = new LinkedHashMap<>();
        RatisVersionRequirement config = configProperties.versionRequirement();
        requirements.put(config.groupId(), config);
        if (registryProperties.isEnabled()) {
            RatisVersionRequirement registry = registryProperties.versionRequirement();
            requirements.put(registry.groupId(), registry);
        }
        return Map.copyOf(requirements);
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record MembershipChangeRequest(
            String operationId,
            List<RatisPeerDefinition> expectedCurrentVoters,
            List<RatisPeerDefinition> targetVoters) {
    }

    public record StateClusterStatus(
            boolean locallyReady,
            boolean joiningExistingCluster,
            String localNodeId,
            List<RatisGroupMembershipView> groups,
            List<RatisStateNodeCapability> capabilities,
            List<RatisVersionRequirement> activeVersions) {
        public StateClusterStatus {
            groups = List.copyOf(groups);
            capabilities = List.copyOf(capabilities);
            activeVersions = List.copyOf(activeVersions);
        }
    }
}
