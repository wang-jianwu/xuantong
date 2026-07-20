package cloud.xuantong.server.state.management;

import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisResult;
import cloud.xuantong.raft.ratis.RatisStateClient;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.RegistryStatePlaneProperties;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Coordinates an explicit Snapshot fence before an offline State-node backup. */
@Component
public final class StateSnapshotService {
    @Inject
    private ConfigStatePlaneProperties configProperties;
    @Inject
    private RegistryStatePlaneProperties registryProperties;

    private SnapshotInvoker snapshotInvoker = StateSnapshotService::forceSnapshot;

    public StateSnapshotService() {
    }

    StateSnapshotService(
            ConfigStatePlaneProperties configProperties,
            RegistryStatePlaneProperties registryProperties,
            SnapshotInvoker snapshotInvoker) {
        if (configProperties == null || registryProperties == null
                || snapshotInvoker == null) {
            throw new IllegalArgumentException(
                    "Snapshot service dependencies must not be null");
        }
        this.configProperties = configProperties;
        this.registryProperties = registryProperties;
        this.snapshotInvoker = snapshotInvoker;
    }

    public SnapshotBatchResult force(SnapshotBatchRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        String operationId = required("operationId", request.operationId());
        String targetNodeId = required("targetNodeId", request.targetNodeId());
        if (operationId.length() > 256) {
            throw new IllegalArgumentException(
                    "operationId must not exceed 256 characters");
        }

        RatisGroupDefinition configGroup = configProperties.groupDefinition();
        configGroup.requirePeer(targetNodeId);
        List<RatisGroupDefinition> groups = new ArrayList<>();
        groups.add(configGroup);
        if (registryProperties.isEnabled()) {
            groups.add(registryProperties.groupDefinition(configGroup.peers()));
        }

        Duration timeout = configProperties.snapshotManagementTimeout();
        List<GroupSnapshot> snapshots = new ArrayList<>(groups.size());
        for (RatisGroupDefinition group : groups) {
            try {
                snapshots.add(snapshotInvoker.force(group, timeout, targetNodeId));
            } catch (IOException | RuntimeException e) {
                throw new IOException(
                        "Failed to force Snapshot for State Group " + group.groupId()
                                + " on node " + targetNodeId,
                        e);
            }
        }
        return new SnapshotBatchResult(
                operationId,
                targetNodeId,
                System.currentTimeMillis(),
                snapshots);
    }

    private static GroupSnapshot forceSnapshot(
            RatisGroupDefinition group,
            Duration timeout,
            String targetNodeId) throws IOException {
        try (RatisStateClient client = new RatisStateClient(group, timeout, 1)) {
            RatisResult result = client.forceSnapshot(timeout, targetNodeId);
            return new GroupSnapshot(
                    group.groupId().type().name(),
                    group.groupId().value(),
                    result.serverId(),
                    result.logIndex());
        }
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @FunctionalInterface
    interface SnapshotInvoker {
        GroupSnapshot force(
                RatisGroupDefinition group,
                Duration timeout,
                String targetNodeId) throws IOException;
    }

    public record SnapshotBatchRequest(String operationId, String targetNodeId) {
    }

    public record SnapshotBatchResult(
            String operationId,
            String targetNodeId,
            long capturedAtEpochMs,
            List<GroupSnapshot> groups) {
        public SnapshotBatchResult {
            groups = List.copyOf(groups == null ? List.of() : groups);
            if (capturedAtEpochMs < 1 || groups.isEmpty()) {
                throw new IllegalArgumentException(
                        "Snapshot result requires capture time and Groups");
            }
        }
    }

    public record GroupSnapshot(
            String groupType,
            String groupName,
            String serverId,
            long logIndex) {
        public GroupSnapshot {
            if (groupType == null || groupType.isBlank()
                    || groupName == null || groupName.isBlank()
                    || serverId == null || serverId.isBlank()
                    || logIndex < 0) {
                throw new IllegalArgumentException("Invalid Group Snapshot result");
            }
        }
    }
}
