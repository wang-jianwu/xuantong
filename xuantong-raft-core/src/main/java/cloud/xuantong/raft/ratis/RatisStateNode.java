package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateNode;
import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.TimeDuration;
import org.apache.ratis.util.SizeInBytes;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * One physical State Node hosting a bootstrap Raft Group. Additional groups are
 * added later through Ratis group management while reusing the same server process.
 */
public final class RatisStateNode implements StateNode {
    private final RatisNodeOptions options;
    private final RaftServer server;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<RaftGroupId, StateGroupId> knownGroups;

    public RatisStateNode(
            RatisNodeOptions options,
            Function<RaftGroupId, StateMachine> stateMachineFactory) throws IOException {
        this(options, stateMachineFactory, bootstrapMapping(options));
    }

    public RatisStateNode(
            RatisNodeOptions options,
            RatisGroupCatalog catalog,
            Function<StateGroupId, cloud.xuantong.state.api.StateMachine>
                    stateMachineFactory) throws IOException {
        this(requireCatalog(options, catalog),
                raftGroupId -> new RatisStateMachineAdapter(
                        requireStateMachine(stateMachineFactory,
                                catalog.requireStateGroup(raftGroupId))),
                catalog.raftGroupMappings());
    }

    private RatisStateNode(
            RatisNodeOptions options,
            Function<RaftGroupId, StateMachine> stateMachineFactory,
            Map<RaftGroupId, StateGroupId> knownGroups) throws IOException {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (stateMachineFactory == null) {
            throw new IllegalArgumentException("stateMachineFactory must not be null");
        }
        this.options = options;
        this.knownGroups = new ConcurrentHashMap<>(knownGroups);
        Files.createDirectories(options.storageDirectory());

        RaftProperties properties = new RaftProperties();
        RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
        GrpcConfigKeys.Server.setHost(properties, options.rpcBindHost());
        GrpcConfigKeys.Server.setPort(properties, options.rpcBindPort());
        RaftServerConfigKeys.setStorageDir(properties,
                List.of(options.storageDirectory().toFile()));
        RaftServerConfigKeys.setStorageFreeSpaceMin(
                properties, SizeInBytes.valueOf(options.storageFreeSpaceMinBytes()));
        RaftServerConfigKeys.Rpc.setTimeoutMin(properties,
                duration(options.electionTimeoutMin()));
        RaftServerConfigKeys.Rpc.setTimeoutMax(properties,
                duration(options.electionTimeoutMax()));
        RaftServerConfigKeys.Rpc.setRequestTimeout(properties,
                duration(options.requestTimeout()));
        RaftServerConfigKeys.Read.setOption(
                properties, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
        RaftServerConfigKeys.Read.setTimeout(properties, duration(options.requestTimeout()));
        RaftServerConfigKeys.Log.setUseMemory(properties, false);
        RaftServerConfigKeys.Log.setPreallocatedSize(
                properties,
                SizeInBytes.valueOf(options.logPreallocatedSizeBytes()));
        RaftServerConfigKeys.Log.setCorruptionPolicy(
                properties,
                RaftServerConfigKeys.Log.CorruptionPolicy.EXCEPTION);
        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(properties, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(
                properties, options.snapshotAutoTriggerThreshold());
        RaftServerConfigKeys.Snapshot.setTriggerWhenStopEnabled(
                properties, options.snapshotOnShutdown());
        RaftServerConfigKeys.Snapshot.setCreationGap(properties, 0L);
        RaftServerConfigKeys.Snapshot.setRetentionFileNum(
                properties, options.snapshotRetentionFileCount());

        Map<RaftGroupId, StateMachine> stateMachines = new ConcurrentHashMap<>();
        StateMachine.Registry registry = groupId -> stateMachines.computeIfAbsent(
                groupId, stateMachineFactory);
        RaftServer.Builder builder = RaftServer.newBuilder()
                .setServerId(RaftPeerId.valueOf(options.localNodeId()))
                .setStateMachineRegistry(registry)
                .setProperties(properties)
                .setOption(RaftStorage.StartupOption.RECOVER);
        if (options.startupMode() == RatisStartupMode.BOOTSTRAP_OR_RECOVER) {
            builder.setGroup(options.bootstrapGroup().toRaftGroup());
        }
        this.server = builder.build();
    }

    @Override
    public void start() throws IOException {
        server.start();
        running.set(true);
        if (options.startupMode() == RatisStartupMode.BOOTSTRAP_OR_RECOVER
                && !isHealthy()) {
            IOException failure = new IOException(
                    "Ratis State Node started without a healthy bootstrap Division: "
                            + options.bootstrapGroup().groupId());
            try {
                server.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                running.set(false);
            }
            throw failure;
        }
    }

    @Override
    public String nodeId() {
        return options.localNodeId();
    }

    @Override
    public Set<StateGroupId> hostedGroups() {
        java.util.HashSet<StateGroupId> hosted = new java.util.HashSet<>();
        for (RaftGroupId raftGroupId : server.getGroupIds()) {
            StateGroupId stateGroupId = knownGroups.get(raftGroupId);
            if (stateGroupId != null) {
                hosted.add(stateGroupId);
            }
        }
        return Set.copyOf(hosted);
    }

    @Override
    public boolean isRunning() {
        return running.get() && server.getLifeCycleState().isRunning();
    }

    /** Process liveness plus the lifecycle state of every hosted Raft Division. */
    public boolean isHealthy() {
        if (!isRunning()) {
            return false;
        }
        try {
            boolean found = false;
            for (RaftGroupId groupId : server.getGroupIds()) {
                found = true;
                if (!server.getDivision(groupId).getInfo().isAlive()) {
                    return false;
                }
            }
            return found;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public synchronized void addGroup(RatisGroupDefinition group) throws IOException {
        if (!isRunning()) {
            throw new IllegalStateException("State node is not running: " + nodeId());
        }
        group.requirePeer(nodeId());
        knownGroups.putIfAbsent(group.toRaftGroupId(), group.groupId());

        RaftProperties properties = new RaftProperties();
        RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
        RaftClientConfigKeys.Rpc.setRequestTimeout(
                properties, duration(options.requestTimeout()));
        try (RaftClient client = RaftClient.newBuilder()
                .setRaftGroup(group.toRaftGroup())
                .setProperties(properties)
                .build()) {
            RaftPeerId localPeerId = RaftPeerId.valueOf(nodeId());
            var management = client.getGroupManagementApi(localPeerId);
            if (management.list().getGroupIds().contains(group.toRaftGroupId())) {
                return;
            }
            var reply = management.add(group.toRaftGroup(), false);
            if (!reply.isSuccess()) {
                throw new RatisOperationException(
                        "Failed to add State Group " + group.groupId()
                                + " to node " + nodeId(),
                        reply.getException());
            }
        }
    }

    public boolean isLeader() throws IOException {
        return isLeaderReady(options.bootstrapGroup().groupId());
    }

    public boolean isLeaderReady(StateGroupId groupId) throws IOException {
        return server.getDivision(raftGroupId(groupId)).getInfo().isLeaderReady();
    }

    /**
     * Returns true only after every requested Division is alive and has observed a
     * usable leader. A local leader must also have completed its leader-ready phase;
     * followers become ready after applying the leader's current-term startup entry.
     */
    public boolean isReady(Collection<StateGroupId> groupIds) {
        if (!isRunning() || groupIds == null || groupIds.isEmpty()) {
            return false;
        }
        try {
            for (StateGroupId groupId : groupIds) {
                var division = server.getDivision(raftGroupId(groupId));
                var info = division.getInfo();
                if (!info.isAlive() || info.getLeaderId() == null) {
                    return false;
                }
                if (info.isLeader()) {
                    if (!info.isLeaderReady()) {
                        return false;
                    }
                    continue;
                }
                var applied = division.getStateMachine().getLastAppliedTermIndex();
                if (applied == null || applied.getTerm() < info.getCurrentTerm()) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Prevents callers from opening the State API during the normal election window.
     * This does not issue a Raft client request, so startup does not manufacture
     * NotLeader/LeaderNotReady failures while the cluster is still converging.
     */
    public void awaitReady(
            Collection<StateGroupId> groupIds, Duration timeout) throws IOException {
        if (groupIds == null || groupIds.isEmpty()) {
            throw new IllegalArgumentException("groupIds must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        List<StateGroupId> expected = List.copyOf(groupIds);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isReady(expected)) {
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException(
                        "Interrupted while waiting for Ratis State Groups to become ready");
                interrupted.initCause(e);
                throw interrupted;
            }
        }
        throw new IOException(
                "Ratis State Groups did not observe a ready leader within "
                        + timeout.toMillis() + "ms: " + expected);
    }

    public RaftServer server() {
        return server;
    }

    @Override
    public void close() throws IOException {
        try {
            server.close();
        } finally {
            running.set(false);
        }
    }

    private static TimeDuration duration(java.time.Duration duration) {
        return TimeDuration.valueOf(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private RaftGroupId raftGroupId(StateGroupId groupId) {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        return knownGroups.entrySet().stream()
                .filter(entry -> groupId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "State node does not host Group " + groupId));
    }

    private static RatisNodeOptions requireCatalog(
            RatisNodeOptions options, RatisGroupCatalog catalog) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        if (!options.bootstrapGroup().groupId()
                .equals(catalog.bootstrapGroup().groupId())) {
            throw new IllegalArgumentException(
                    "Node bootstrap Group does not match catalog bootstrap Group");
        }
        return options;
    }

    private static Map<RaftGroupId, StateGroupId> bootstrapMapping(
            RatisNodeOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        return Map.of(
                options.bootstrapGroup().toRaftGroupId(),
                options.bootstrapGroup().groupId());
    }

    private static cloud.xuantong.state.api.StateMachine requireStateMachine(
            Function<StateGroupId, cloud.xuantong.state.api.StateMachine> factory,
            StateGroupId groupId) {
        if (factory == null) {
            throw new IllegalArgumentException("stateMachineFactory must not be null");
        }
        cloud.xuantong.state.api.StateMachine stateMachine = factory.apply(groupId);
        if (stateMachine == null) {
            throw new IllegalArgumentException(
                    "No State Machine configured for " + groupId);
        }
        if (!groupId.equals(stateMachine.groupId())) {
            throw new IllegalArgumentException(
                    "State Machine Group does not match catalog: " + groupId);
        }
        return stateMachine;
    }
}
