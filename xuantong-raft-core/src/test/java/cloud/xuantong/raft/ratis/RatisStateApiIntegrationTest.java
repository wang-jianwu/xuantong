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
import cloud.xuantong.state.api.WatchEvent;
import cloud.xuantong.state.api.WatchRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.SocketException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisStateApiIntegrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void stateContractsRoundTripThroughRealRatisServer() throws Exception {
        StateGroupId groupId = StateGroupId.config("config-api-test");
        RatisPeerDefinition peer = new RatisPeerDefinition(
                "state-api-1", "127.0.0.1", freePort());
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, List.of(peer));
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                tempDirectory.resolve("state-api-1"),
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofSeconds(2),
                10_000,
                false);

        try (RatisStateNode node = new RatisStateNode(
                options, ignored -> new RatisStateMachineAdapter(
                        new CounterStateMachine(groupId)))) {
            assertFalse(node.isRunning());
            node.start();
            assertTrue(node.isRunning());
            assertEquals(java.util.Set.of(groupId), node.hostedGroups());
            node.awaitReady(List.of(groupId), Duration.ofSeconds(5));
            assertTrue(node.isReady(List.of(groupId)));

            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                ApplyResult applied = client.submit(new StateCommand(
                                groupId, "op-1", "counter.increment", 1, new byte[0]))
                        .get(5, TimeUnit.SECONDS);
                assertEquals(ApplyStatus.APPLIED, applied.status());
                assertEquals(1L, decodeLong(applied.payload()));
                assertEquals(1L, applied.revisions().getFirst().value());

                QueryResult query = client.query(new StateQuery(
                                groupId,
                                "counter.get",
                                1,
                                new byte[0],
                                ReadOptions.linearizable()))
                        .get(5, TimeUnit.SECONDS);
                assertFalse(query.stale());
                assertEquals(1L, decodeLong(query.payload()));

                WatchBatch watch = client.watch(new WatchRequest(
                                StateRevision.configEvent(groupId, 0),
                                "counter.changes",
                                1,
                                new byte[0],
                                100,
                                ReadOptions.linearizable()))
                        .get(5, TimeUnit.SECONDS);
                assertEquals(1L, watch.coveredThrough().value());
                assertEquals(1L, watch.events().getFirst().revision().value());

                ExecutionException unsupported = assertThrows(
                        ExecutionException.class,
                        () -> client.query(new StateQuery(
                                        groupId,
                                        "counter.get",
                                        1,
                                        new byte[0],
                                        ReadOptions.boundedStale(
                                                StateRevision.configEvent(groupId, 1),
                                                Duration.ofSeconds(1))))
                                .get(5, TimeUnit.SECONDS));
                assertInstanceOf(RatisOperationException.class, unsupported.getCause());
            }
        }
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
                    List.of(StateRevision.configEvent(groupId, value)));
        }

        @Override
        public synchronized QueryResult query(StateQuery query) {
            if (!"counter.get".equals(query.queryType())) {
                throw new IllegalArgumentException("Unsupported query");
            }
            return new QueryResult(
                    groupId,
                    appliedIndex,
                    false,
                    "counter.value",
                    encodeLong(value),
                    List.of(StateRevision.configEvent(groupId, value)));
        }

        @Override
        public synchronized WatchBatch watch(WatchRequest request) {
            StateRevision covered = StateRevision.configEvent(groupId, value);
            List<WatchEvent> events = request.afterRevision().value() < value
                    ? List.of(new WatchEvent(
                            covered, "counter.changed", 1, encodeLong(value)))
                    : List.of();
            return new WatchBatch(
                    request.afterRevision(),
                    covered,
                    StateRevision.configEvent(groupId, 0),
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
    }
}
