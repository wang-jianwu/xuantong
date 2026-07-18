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

import java.io.IOException;
import java.nio.file.Files;
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

        RatisPeerDefinition localPeer = options.bootstrapGroup().requirePeer(options.localNodeId());
        RaftProperties properties = new RaftProperties();
        RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
        GrpcConfigKeys.Server.setHost(properties, localPeer.host());
        GrpcConfigKeys.Server.setPort(properties, localPeer.port());
        RaftServerConfigKeys.setStorageDir(properties,
                List.of(options.storageDirectory().toFile()));
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
        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(properties, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(
                properties, options.snapshotAutoTriggerThreshold());
        RaftServerConfigKeys.Snapshot.setTriggerWhenStopEnabled(
                properties, options.snapshotOnShutdown());
        RaftServerConfigKeys.Snapshot.setCreationGap(properties, 0L);

        Map<RaftGroupId, StateMachine> stateMachines = new ConcurrentHashMap<>();
        StateMachine.Registry registry = groupId -> stateMachines.computeIfAbsent(
                groupId, stateMachineFactory);
        this.server = RaftServer.newBuilder()
                .setServerId(RaftPeerId.valueOf(options.localNodeId()))
                .setGroup(options.bootstrapGroup().toRaftGroup())
                .setStateMachineRegistry(registry)
                .setProperties(properties)
                .setOption(RaftStorage.StartupOption.RECOVER)
                .build();
    }

    @Override
    public void start() throws IOException {
        server.start();
        running.set(true);
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
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        RaftGroupId raftGroupId = knownGroups.entrySet().stream()
                .filter(entry -> groupId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "State node does not host Group " + groupId));
        return server.getDivision(raftGroupId).getInfo().isLeaderReady();
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
