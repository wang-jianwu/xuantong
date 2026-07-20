package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateGroupType;
import cloud.xuantong.state.api.StateMachine;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchEvent;
import cloud.xuantong.state.api.WatchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisMultiGroupIntegrationTest {
    @TempDir
    Path tempDirectory;

    private final List<RatisStateNode> openNodes = new ArrayList<>();

    @AfterEach
    void closeNodes() {
        closeAllNodes();
    }

    @Test
    void configAndRegistryUseIndependentDivisionsSnapshotsAndRecovery() throws Exception {
        List<RatisPeerDefinition> peers = List.of(
                new RatisPeerDefinition("state-1", "127.0.0.1", freePort()),
                new RatisPeerDefinition("state-2", "127.0.0.1", freePort()),
                new RatisPeerDefinition("state-3", "127.0.0.1", freePort()));
        RatisGroupDefinition configGroup = new RatisGroupDefinition(
                StateGroupId.config("config-default"), peers);
        RatisGroupDefinition registryGroup = new RatisGroupDefinition(
                StateGroupId.registry("registry-default"), peers);
        RatisGroupCatalog catalog = RatisGroupCatalog.compact(
                configGroup, registryGroup);
        List<Path> storageDirectories = peers.stream()
                .map(peer -> tempDirectory.resolve(peer.nodeId()))
                .toList();

        List<RatisStateNode> nodes = startCluster(catalog, peers, storageDirectories);
        installAdditionalGroups(nodes, catalog);
        assertEquals(1, readyLeaderCount(
                nodes, configGroup.groupId(), Duration.ofSeconds(10)));
        assertEquals(1, readyLeaderCount(
                nodes, registryGroup.groupId(), Duration.ofSeconds(10)));
        for (RatisStateNode node : nodes) {
            assertEquals(Set.of(configGroup.groupId(), registryGroup.groupId()),
                    node.hostedGroups());
            assertNotSame(
                    node.server().getDivision(configGroup.toRaftGroupId()).getStateMachine(),
                    node.server().getDivision(registryGroup.toRaftGroupId()).getStateMachine());
        }

        ApplyResult config;
        ApplyResult registry;
        try (RatisStateRouter router = new RatisStateRouter(
                catalog.groups(), Duration.ofSeconds(2), 5)) {
            config = submitEventually(router, new StateCommand(
                    configGroup.groupId(),
                    "config-op-1",
                    "counter.increment",
                    1,
                    new byte[0]));
            registry = submitEventually(router, new StateCommand(
                    registryGroup.groupId(),
                    "registry-op-1",
                    "counter.increment",
                    1,
                    new byte[0]));

            assertEquals(1L, decodeLong(config.payload()));
            assertEquals(1L, decodeLong(registry.payload()));
            assertEquals(Set.of(configGroup.groupId(), registryGroup.groupId()),
                    router.groups());
        }

        waitForAppliedOnAllNodes(
                nodes, configGroup, config.appliedIndex(), Duration.ofSeconds(30));
        waitForAppliedOnAllNodes(
                nodes, registryGroup, registry.appliedIndex(), Duration.ofSeconds(30));
        forceSnapshot(configGroup);
        forceSnapshot(registryGroup);
        waitForSnapshots(catalog, storageDirectories, Duration.ofSeconds(30));

        closeAllNodes();
        List<RatisStateNode> restarted = startCluster(catalog, peers, storageDirectories);
        installAdditionalGroups(restarted, catalog);
        try (RatisStateRouter router = new RatisStateRouter(
                catalog.groups(), Duration.ofSeconds(2), 5)) {
            assertEquals(1L, decodeLong(queryEventually(
                    router, configGroup.groupId()).payload()));
            assertEquals(1L, decodeLong(queryEventually(
                    router, registryGroup.groupId()).payload()));
        }
    }

    private List<RatisStateNode> startCluster(
            RatisGroupCatalog catalog,
            List<RatisPeerDefinition> peers,
            List<Path> storageDirectories) throws Exception {
        List<RatisStateNode> nodes = new ArrayList<>();
        for (int i = 0; i < peers.size(); i++) {
            RatisPeerDefinition peer = peers.get(i);
            RatisNodeOptions options = new RatisNodeOptions(
                    peer.nodeId(),
                    catalog.bootstrapGroup(),
                    storageDirectories.get(i),
                    Duration.ofMillis(300),
                    Duration.ofMillis(600),
                    Duration.ofSeconds(2),
                    10_000,
                    false);
            RatisStateNode node = new RatisStateNode(
                    options, catalog, GroupCounterStateMachine::new);
            node.start();
            nodes.add(node);
            openNodes.add(node);
        }
        return nodes;
    }

    private void installAdditionalGroups(
            List<RatisStateNode> nodes, RatisGroupCatalog catalog) throws Exception {
        for (RatisGroupDefinition group : catalog.groups()) {
            if (group.groupId().equals(catalog.bootstrapGroup().groupId())) {
                continue;
            }
            for (RatisStateNode node : nodes) {
                node.addGroup(group);
            }
        }
    }

    private int readyLeaderCount(
            List<RatisStateNode> nodes,
            StateGroupId groupId,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int leaders = 0;
        while (System.nanoTime() < deadline) {
            leaders = 0;
            for (RatisStateNode node : nodes) {
                if (node.isLeaderReady(groupId)) {
                    leaders++;
                }
            }
            if (leaders == 1) {
                return leaders;
            }
            Thread.sleep(50L);
        }
        return leaders;
    }

    private ApplyResult submitEventually(
            RatisStateRouter router, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return router.submit(command).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last == null ? new IOException("State submit did not complete") : last;
    }

    private QueryResult queryEventually(
            RatisStateRouter router, StateGroupId groupId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return router.query(new StateQuery(
                                groupId,
                                "counter.get",
                                1,
                                new byte[0],
                                ReadOptions.linearizable()))
                        .get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last == null ? new IOException("State query did not complete") : last;
    }

    private void forceSnapshot(RatisGroupDefinition group) throws Exception {
        try (RatisStateClient client = new RatisStateClient(
                group, Duration.ofSeconds(2), 5)) {
            for (RatisPeerDefinition peer : group.peers()) {
                client.forceSnapshot(Duration.ofSeconds(5), peer.nodeId());
            }
        }
    }

    private void waitForAppliedOnAllNodes(
            List<RatisStateNode> nodes,
            RatisGroupDefinition group,
            long requiredIndex,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean complete = true;
            for (RatisStateNode node : nodes) {
                long applied = node.server()
                        .getDivision(group.toRaftGroupId())
                        .getInfo()
                        .getLastAppliedIndex();
                if (applied < requiredIndex) {
                    complete = false;
                }
            }
            if (complete) {
                return;
            }
            Thread.sleep(100L);
        }
        List<String> lagging = new ArrayList<>();
        for (RatisStateNode node : nodes) {
            long applied = node.server()
                    .getDivision(group.toRaftGroupId())
                    .getInfo()
                    .getLastAppliedIndex();
            if (applied < requiredIndex) {
                lagging.add(node.nodeId() + "=" + applied);
            }
        }
        throw new AssertionError("State Group " + group.groupId()
                + " did not apply index " + requiredIndex
                + " on nodes " + lagging);
    }

    private void waitForSnapshots(
            RatisGroupCatalog catalog,
            List<Path> storageDirectories,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean complete = true;
            for (Path storageDirectory : storageDirectories) {
                for (RatisGroupDefinition group : catalog.groups()) {
                    Path groupDirectory = storageDirectory.resolve(
                            group.toRaftGroupId().getUuid().toString());
                    if (!containsSnapshot(groupDirectory)) {
                        complete = false;
                    }
                }
            }
            if (complete) {
                return;
            }
            Thread.sleep(100);
        }
        List<String> missing = new ArrayList<>();
        for (Path storageDirectory : storageDirectories) {
            for (RatisGroupDefinition group : catalog.groups()) {
                Path groupDirectory = storageDirectory.resolve(
                        group.toRaftGroupId().getUuid().toString());
                if (!containsSnapshot(groupDirectory)) {
                    missing.add(storageDirectory.getFileName()
                            + "/" + group.groupId().canonicalName());
                }
            }
        }
        throw new AssertionError(
                "Config and Registry snapshots were not created: " + missing);
    }

    private boolean containsSnapshot(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return false;
        }
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString().startsWith("snapshot"));
        }
    }

    private void closeAllNodes() {
        for (int i = openNodes.size() - 1; i >= 0; i--) {
            try {
                openNodes.get(i).close();
            } catch (Exception ignored) {
            }
        }
        openNodes.clear();
    }

    private int freePort() throws Exception {
        try {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }

    private static byte[] encodeLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long decodeLong(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    private static final class GroupCounterStateMachine implements StateMachine {
        private final StateGroupId groupId;
        private long appliedIndex;
        private long value;

        private GroupCounterStateMachine(StateGroupId groupId) {
            this.groupId = groupId;
        }

        @Override
        public StateGroupId groupId() {
            return groupId;
        }

        @Override
        public synchronized ApplyResult apply(
                StateCommand command, ApplyContext context) {
            if (!"counter.increment".equals(command.commandType())) {
                throw new IllegalArgumentException("Unsupported command");
            }
            value++;
            appliedIndex = context.logIndex();
            return new ApplyResult(
                    groupId,
                    command.operationId(),
                    ApplyStatus.APPLIED,
                    appliedIndex,
                    "counter.value",
                    encodeLong(value),
                    List.of(revision(value)));
        }

        @Override
        public synchronized QueryResult query(StateQuery query) {
            return new QueryResult(
                    groupId,
                    appliedIndex,
                    false,
                    "counter.value",
                    encodeLong(value),
                    List.of(revision(value)));
        }

        @Override
        public synchronized WatchBatch watch(WatchRequest request) {
            StateRevision covered = revision(value);
            List<WatchEvent> events = request.afterRevision().value() < value
                    ? List.of(new WatchEvent(
                            covered, "counter.changed", 1, encodeLong(value)))
                    : List.of();
            return new WatchBatch(
                    request.afterRevision(),
                    covered,
                    revision(0),
                    false,
                    events);
        }

        @Override
        public int snapshotSchemaVersion() {
            return 1;
        }

        @Override
        public synchronized void writeSnapshot(OutputStream output) throws IOException {
            DataOutputStream data = new DataOutputStream(output);
            data.writeLong(appliedIndex);
            data.writeLong(value);
        }

        @Override
        public synchronized void installSnapshot(
                int schemaVersion, InputStream input) throws IOException {
            if (schemaVersion != 1) {
                throw new IOException("Unsupported counter snapshot schema: " + schemaVersion);
            }
            DataInputStream data = new DataInputStream(input);
            appliedIndex = data.readLong();
            value = data.readLong();
        }

        private StateRevision revision(long revision) {
            return groupId.type() == StateGroupType.CONFIG
                    ? StateRevision.configEvent(groupId, revision)
                    : StateRevision.registry(groupId, revision);
        }
    }
}
