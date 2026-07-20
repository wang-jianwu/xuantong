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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RatisRollingUpgradeIntegrationTest {
    private static final String OLD = "2.0.0-old";
    private static final String NEW = "2.1.0-new";

    @TempDir
    Path tempDirectory;

    private final Map<String, RatisStateNode> nodes = new LinkedHashMap<>();

    @AfterEach
    void closeNodes() {
        closeAll();
    }

    @Test
    void rollsThreeVotersAllowsMidUpgradeRollbackAndRestoresSnapshot()
            throws Exception {
        StateGroupId groupId = StateGroupId.config("rolling-upgrade-test");
        List<RatisPeerDefinition> peers = List.of(
                peer("state-1"), peer("state-2"), peer("state-3"));
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, peers);
        for (RatisPeerDefinition peer : peers) {
            restart(peer, group, OLD);
        }

        long expected = 0;
        try (RatisStateClient client = client(group)) {
            expected = increment(client, groupId, ++expected);
        }

        // First node moves forward, then rolls back before any new format is activated.
        restart(peers.get(0), group, NEW);
        try (RatisStateClient client = client(group)) {
            expected = increment(client, groupId, ++expected);
        }
        restart(peers.get(0), group, OLD);
        try (RatisStateClient client = client(group)) {
            expected = increment(client, groupId, ++expected);
        }
        restart(peers.get(0), group, NEW);

        restart(peers.get(1), group, NEW);
        try (RatisStateClient client = client(group)) {
            expected = increment(client, groupId, ++expected);
        }
        restart(peers.get(2), group, NEW);
        try (RatisStateClient client = client(group)) {
            expected = increment(client, groupId, ++expected);
            for (RatisPeerDefinition peer : peers) {
                assertEquals(NEW, capabilityEventually(client, peer.nodeId())
                        .implementationVersion());
                client.forceSnapshot(Duration.ofSeconds(5), peer.nodeId());
            }
        }

        closeAll();
        for (RatisPeerDefinition peer : peers) {
            restart(peer, group, NEW);
        }
        try (RatisStateClient client = client(group)) {
            QueryResult query = queryEventually(client, groupId);
            assertEquals(expected, longValue(query.payload()));
        }
    }

    private long increment(
            RatisStateClient client, StateGroupId groupId, long operationNumber)
            throws Exception {
        StateCommand command = new StateCommand(
                groupId,
                "op-" + operationNumber,
                "counter.increment",
                1,
                new byte[0]);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                ApplyResult result = client.submit(command).get(3, TimeUnit.SECONDS);
                return longValue(result.payload());
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft write did not complete") : last;
    }

    private QueryResult queryEventually(
            RatisStateClient client, StateGroupId groupId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.query(new StateQuery(
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

    private RatisStateNodeCapability capabilityEventually(
            RatisStateClient client, String nodeId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.capability(nodeId);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IOException("State capability query did not complete") : last;
    }

    private void restart(
            RatisPeerDefinition peer,
            RatisGroupDefinition group,
            String implementationVersion) throws Exception {
        RatisStateNode previous = nodes.remove(peer.nodeId());
        if (previous != null) {
            previous.close();
        }
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                tempDirectory.resolve(peer.nodeId()),
                Duration.ofMillis(300),
                Duration.ofMillis(600),
                Duration.ofSeconds(2),
                2,
                true);
        RatisStateNode next = new RatisStateNode(
                options,
                ignored -> new RatisStateMachineAdapter(
                        new SnapshotCounter(group.groupId()), implementationVersion));
        next.start();
        nodes.put(peer.nodeId(), next);
    }

    private RatisStateClient client(RatisGroupDefinition group) {
        return new RatisStateClient(group, Duration.ofSeconds(2), 10);
    }

    private void closeAll() {
        List<RatisStateNode> values = new ArrayList<>(nodes.values());
        for (int i = values.size() - 1; i >= 0; i--) {
            try {
                values.get(i).close();
            } catch (Exception ignored) {
            }
        }
        nodes.clear();
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

    private static byte[] bytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long longValue(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    private static final class SnapshotCounter implements StateMachine {
        private final StateGroupId groupId;
        private long appliedIndex;
        private long value;

        private SnapshotCounter(StateGroupId groupId) {
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
                    List.of(StateRevision.configEvent(groupId, value)));
        }

        @Override
        public synchronized QueryResult query(StateQuery query) {
            return new QueryResult(
                    groupId,
                    appliedIndex,
                    false,
                    "counter.value",
                    bytes(value),
                    List.of(StateRevision.configEvent(groupId, value)));
        }

        @Override
        public synchronized WatchBatch watch(WatchRequest request) {
            return new WatchBatch(
                    request.afterRevision(),
                    StateRevision.configEvent(groupId, value),
                    StateRevision.configEvent(groupId, 0),
                    false,
                    List.of());
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
                throw new IOException("Unsupported snapshot schema " + schemaVersion);
            }
            DataInputStream data = new DataInputStream(input);
            appliedIndex = data.readLong();
            value = data.readLong();
        }
    }
}
