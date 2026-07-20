package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateMachine;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisSnapshotCorruptionIntegrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void corruptedSnapshotBytesFailTheDivisionAndRemainUnready() throws Exception {
        assertSnapshotRejected("content", (snapshot, checksum) -> {
            byte[] damaged = Files.readAllBytes(snapshot);
            assertTrue(damaged.length > 0);
            damaged[damaged.length - 1] ^= 0x5A;
            Files.write(snapshot, damaged);
        }, "Snapshot checksum mismatch");
    }

    @Test
    void missingSnapshotChecksumFailsTheDivisionAndRemainsUnready() throws Exception {
        assertSnapshotRejected("missing-md5", (snapshot, checksum) ->
                Files.delete(checksum), "Snapshot checksum file does not exist");
    }

    @Test
    void malformedSnapshotChecksumFailsTheDivisionAndRemainsUnready() throws Exception {
        assertSnapshotRejected("malformed-md5", (snapshot, checksum) ->
                Files.writeString(checksum, "not-an-md5"),
                "Snapshot checksum file is invalid");
    }

    private void assertSnapshotRejected(
            String caseName,
            SnapshotDamage damage,
            String expectedMessage) throws Exception {
        StateGroupId groupId = StateGroupId.config("snapshot-corruption-" + caseName);
        RatisPeerDefinition peer = new RatisPeerDefinition(
                "state-1", "127.0.0.1", freePort());
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, List.of(peer));
        Path storage = tempDirectory.resolve(caseName).resolve("state-1");
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                storage,
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofSeconds(2),
                10_000L,
                false,
                2,
                RatisStartupMode.BOOTSTRAP_OR_RECOVER);

        RatisStateNode first = node(options, groupId);
        try {
            first.start();
            assertTrue(first.isHealthy());
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                ApplyResult applied = submitEventually(client, new StateCommand(
                        groupId,
                        "snapshot-corruption-" + caseName + "-op-1",
                        "counter.increment",
                        1,
                        new byte[0]));
                assertEquals(ApplyStatus.APPLIED, applied.status());
                client.forceSnapshot(Duration.ofSeconds(5), peer.nodeId());
            }
        } finally {
            first.close();
        }
        assertFalse(first.isHealthy());

        Path snapshot = latestSnapshot(storage);
        Path checksum = snapshot.resolveSibling(snapshot.getFileName() + ".md5");
        assertTrue(Files.isRegularFile(checksum));
        damage.apply(snapshot, checksum);
        assertThrows(IOException.class, () -> RatisSnapshotChecksum.verify(snapshot));

        try (RatisStateNode corrupted = node(options, groupId)) {
            Exception failure = assertThrows(Exception.class, corrupted::start);
            assertTrue(hasChecksumFailure(failure, expectedMessage),
                    () -> "Expected failure containing '" + expectedMessage
                            + "' but got " + failure);
            assertFalse(corrupted.isHealthy());
        }
    }

    private boolean hasChecksumFailure(Throwable failure, String expectedMessage) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().contains(expectedMessage)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @FunctionalInterface
    private interface SnapshotDamage {
        void apply(Path snapshot, Path checksum) throws Exception;
    }

    private RatisStateNode node(
            RatisNodeOptions options, StateGroupId groupId) throws IOException {
        return new RatisStateNode(
                options,
                ignored -> new RatisStateMachineAdapter(
                        new CounterStateMachine(groupId)));
    }

    private ApplyResult submitEventually(
            RatisStateClient client, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.submit(command).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException("State submit did not complete") : last;
    }

    private Path latestSnapshot(Path storage) throws IOException {
        try (var files = Files.walk(storage)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("snapshot."))
                    .filter(path -> !path.getFileName().toString().endsWith(".md5"))
                    .max(Path::compareTo)
                    .orElseThrow(() -> new AssertionError("Snapshot was not created"));
        }
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: "
                            + e.getMessage());
            return -1;
        }
    }

    private static byte[] encodeLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
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
                    encodeLong(value),
                    List.of(StateRevision.configEvent(groupId, value)));
        }

        @Override
        public synchronized QueryResult query(StateQuery query) {
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
            return new WatchBatch(
                    request.afterRevision(),
                    covered,
                    StateRevision.configEvent(groupId, 0L),
                    false,
                    List.of());
        }

        @Override
        public int snapshotSchemaVersion() {
            return 1;
        }

        @Override
        public synchronized void writeSnapshot(OutputStream output)
                throws IOException {
            DataOutputStream data = new DataOutputStream(output);
            data.writeLong(appliedIndex);
            data.writeLong(value);
        }

        @Override
        public synchronized void installSnapshot(
                int schemaVersion, InputStream input) throws IOException {
            if (schemaVersion != 1) {
                throw new IOException(
                        "Unsupported counter snapshot schema: " + schemaVersion);
            }
            DataInputStream data = new DataInputStream(input);
            appliedIndex = data.readLong();
            value = data.readLong();
        }
    }
}
