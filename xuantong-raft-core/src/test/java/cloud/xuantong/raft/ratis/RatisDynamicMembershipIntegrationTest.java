package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateMachine;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchBatch;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisDynamicMembershipIntegrationTest {
    @TempDir
    Path tempDirectory;

    private final List<RatisStateNode> openNodes = new ArrayList<>();

    @AfterEach
    void closeNodes() {
        for (int i = openNodes.size() - 1; i >= 0; i--) {
            try {
                openNodes.get(i).close();
            } catch (Exception ignored) {
            }
        }
        openNodes.clear();
    }

    @Test
    void listenerCatchUpCapabilityGateAndCasReplaceOneVoter() throws Exception {
        StateGroupId configGroupId = StateGroupId.config("membership-config");
        StateGroupId registryGroupId = StateGroupId.registry("membership-registry");
        RatisPeerDefinition state1 = peer("state-1");
        RatisPeerDefinition state2 = peer("state-2");
        RatisPeerDefinition state3 = peer("state-3");
        RatisPeerDefinition state4 = peer("state-4");
        List<RatisPeerDefinition> current = List.of(state1, state2, state3);
        List<RatisPeerDefinition> target = List.of(state2, state3, state4);
        RatisGroupDefinition currentConfig = new RatisGroupDefinition(
                configGroupId, current);
        RatisGroupDefinition currentRegistry = new RatisGroupDefinition(
                registryGroupId, current);
        RatisGroupDefinition targetConfig = new RatisGroupDefinition(
                configGroupId, target);
        RatisGroupDefinition targetRegistry = new RatisGroupDefinition(
                registryGroupId, target);
        RatisGroupCatalog currentCatalog = new RatisGroupCatalog(
                currentConfig, List.of(currentConfig, currentRegistry));
        RatisGroupCatalog targetCatalog = new RatisGroupCatalog(
                targetConfig, List.of(targetConfig, targetRegistry));

        for (RatisPeerDefinition peer : current) {
            startNode(peer, currentCatalog, RatisStartupMode.BOOTSTRAP_OR_RECOVER);
        }
        RatisStateNode joining = startNode(
                state4, targetCatalog, RatisStartupMode.JOIN_EXISTING);
        assertTrue(joining.hostedGroups().isEmpty());

        try (RatisStateRouter router = new RatisStateRouter(
                currentCatalog.groups(), Duration.ofSeconds(2), 10)) {
            assertEquals(1L, longValue(submitEventually(
                    router, command(configGroupId, "config-op-1")).payload()));
            assertEquals(1L, longValue(submitEventually(
                    router, command(registryGroupId, "registry-op-1")).payload()));
        }

        RatisMembershipManager manager = new RatisMembershipManager(
                Duration.ofSeconds(2),
                10,
                new RatisMembershipPolicy(false, Duration.ofSeconds(30), 0));
        RatisMembershipChangeResult changed = manager.change(
                List.of(configGroupId, registryGroupId),
                current,
                target,
                Map.of(
                        configGroupId,
                        new RatisVersionRequirement(configGroupId, 1, 1, 1),
                        registryGroupId,
                        new RatisVersionRequirement(registryGroupId, 1, 1, 1)));

        assertEquals(List.of("state-4"), changed.addedNodeIds());
        assertEquals(List.of("state-1"), changed.removedNodeIds());
        assertEquals(2, changed.groups().size());
        for (RatisGroupMembershipView group : changed.groups()) {
            assertEquals(target.stream().map(RatisPeerDefinition::nodeId).sorted().toList(),
                    group.voters().stream().map(RatisPeerDefinition::nodeId)
                            .sorted().toList());
        }
        assertTrue(joining.hostedGroups().containsAll(
                List.of(configGroupId, registryGroupId)));

        RatisStateNode removed = openNodes.stream()
                .filter(node -> node.nodeId().equals("state-1"))
                .findFirst().orElseThrow();
        removed.close();
        openNodes.remove(removed);

        try (RatisStateRouter router = new RatisStateRouter(
                targetCatalog.groups(), Duration.ofSeconds(2), 10)) {
            assertEquals(2L, longValue(submitEventually(
                    router, command(configGroupId, "config-op-2")).payload()));
            assertEquals(2L, longValue(submitEventually(
                    router, command(registryGroupId, "registry-op-2")).payload()));
            assertEquals(2L, longValue(queryEventually(
                    router, configGroupId).payload()));
            assertEquals(2L, longValue(queryEventually(
                    router, registryGroupId).payload()));
        }
    }

    private RatisStateNode startNode(
            RatisPeerDefinition peer,
            RatisGroupCatalog catalog,
            RatisStartupMode startupMode) throws Exception {
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                catalog.bootstrapGroup(),
                tempDirectory.resolve(peer.nodeId()),
                Duration.ofMillis(300),
                Duration.ofMillis(600),
                Duration.ofSeconds(2),
                10_000,
                false,
                startupMode);
        RatisStateNode node = new RatisStateNode(
                options,
                catalog,
                CounterStateMachine::new);
        node.start();
        if (startupMode == RatisStartupMode.BOOTSTRAP_OR_RECOVER) {
            for (RatisGroupDefinition group : catalog.groups()) {
                if (!group.groupId().equals(catalog.bootstrapGroup().groupId())) {
                    node.addGroup(group);
                }
            }
        }
        openNodes.add(node);
        return node;
    }

    private ApplyResult submitEventually(
            RatisStateRouter router, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return router.submit(command).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft submit did not complete") : last;
    }

    private QueryResult queryEventually(
            RatisStateRouter router, StateGroupId groupId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
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
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft query did not complete") : last;
    }

    private RatisPeerDefinition peer(String nodeId) throws Exception {
        return new RatisPeerDefinition(nodeId, "127.0.0.1", freePort());
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

    private static StateCommand command(StateGroupId groupId, String operationId) {
        return new StateCommand(
                groupId, operationId, "counter.increment", 1, new byte[0]);
    }

    private static byte[] bytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long longValue(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    private static final class CounterStateMachine implements StateMachine {
        private final StateGroupId groupId;
        private long appliedIndex;
        private long value;

        private CounterStateMachine(StateGroupId groupId) {
            this.groupId = groupId;
        }

        @Override
        public StateGroupId groupId() {
            return groupId;
        }

        @Override
        public synchronized ApplyResult apply(
                StateCommand command, ApplyContext context) {
            value++;
            appliedIndex = context.logIndex();
            return new ApplyResult(
                    groupId,
                    command.operationId(),
                    ApplyStatus.APPLIED,
                    appliedIndex,
                    "counter.value",
                    bytes(value),
                    List.of(revision(value)));
        }

        @Override
        public synchronized QueryResult query(StateQuery query) {
            return new QueryResult(
                    groupId,
                    appliedIndex,
                    false,
                    "counter.value",
                    bytes(value),
                    List.of(revision(value)));
        }

        @Override
        public WatchBatch watch(WatchRequest request) {
            return new WatchBatch(
                    request.afterRevision(),
                    revision(value),
                    revision(0),
                    false,
                    List.of());
        }

        @Override
        public int snapshotSchemaVersion() {
            return 1;
        }

        private StateRevision revision(long revision) {
            return groupId.type() == cloud.xuantong.state.api.StateGroupType.CONFIG
                    ? StateRevision.configEvent(groupId, revision)
                    : StateRevision.registry(groupId, revision);
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
                throw new IOException("Unsupported snapshot schema " + schemaVersion);
            }
            DataInputStream data = new DataInputStream(input);
            appliedIndex = data.readLong();
            value = data.readLong();
        }
    }
}
