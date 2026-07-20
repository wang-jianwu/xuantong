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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisOfflineNodeBackupIntegrationTest {
    @TempDir
    Path tempDirectory;

    private final Map<String, RatisStateNode> nodes = new LinkedHashMap<>();
    private final Map<String, SnapshotCounter> counters = new LinkedHashMap<>();
    private final List<Integer> allocatedPorts = new ArrayList<>();

    @AfterEach
    void closeNodes() {
        closeAll();
    }

    @Test
    void restoresAStoppedVoterFromItsCompleteOfflineDirectory() throws Exception {
        StateGroupId groupId = StateGroupId.config("offline-node-backup");
        List<RatisPeerDefinition> peers = List.of(
                peer("state-1"), peer("state-2"), peer("state-3"));
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, peers);
        for (RatisPeerDefinition peer : peers) {
            start(peer, group);
        }

        try (RatisStateClient client = client(group)) {
            assertEquals(1L, increment(client, groupId, 1));
            assertEquals(2L, increment(client, groupId, 2));
            assertEquals(3L, increment(client, groupId, 3));
            awaitCounter("state-3", 3L, Duration.ofSeconds(10));
            client.forceSnapshot(Duration.ofSeconds(10), "state-3");
        }

        stop("state-3");
        Path original = storage("state-3");
        Path backup = tempDirectory.resolve("backup-state-3");
        copyTree(original, backup);
        assertTrue(snapshotCount(backup) >= 1L);

        deleteTree(original);
        copyTree(backup, original);
        start(peers.get(2), group);
        awaitCounter("state-3", 3L, Duration.ofSeconds(15));

        try (RatisStateClient client = client(group)) {
            assertEquals(4L, increment(client, groupId, 4));
        }
        for (RatisPeerDefinition peer : peers) {
            awaitCounter(peer.nodeId(), 4L, Duration.ofSeconds(15));
        }
    }

    @Test
    void restoresAQuorumOfIndependentArchivesAfterCompleteClusterLoss()
            throws Exception {
        StateGroupId groupId = StateGroupId.config("offline-quorum-backup");
        List<RatisPeerDefinition> peers = List.of(
                peer("state-1"), peer("state-2"), peer("state-3"));
        RatisGroupDefinition group = new RatisGroupDefinition(groupId, peers);
        for (RatisPeerDefinition peer : peers) {
            start(peer, group);
        }

        try (RatisStateClient client = client(group)) {
            assertEquals(1L, increment(client, groupId, 1));
            assertEquals(2L, increment(client, groupId, 2));
            assertEquals(3L, increment(client, groupId, 3));
            for (RatisPeerDefinition peer : peers) {
                awaitCounter(peer.nodeId(), 3L, Duration.ofSeconds(10));
            }
            client.forceSnapshot(Duration.ofSeconds(10), "state-1");
            client.forceSnapshot(Duration.ofSeconds(10), "state-2");
        }

        closeAll();
        Path firstArchive = tempDirectory.resolve("archive-state-1");
        Path secondArchive = tempDirectory.resolve("archive-state-2");
        copyTree(storage("state-1"), firstArchive);
        copyTree(storage("state-2"), secondArchive);
        assertTrue(snapshotCount(firstArchive) >= 1L);
        assertTrue(snapshotCount(secondArchive) >= 1L);

        for (RatisPeerDefinition peer : peers) {
            deleteTree(storage(peer.nodeId()));
        }
        copyTree(firstArchive, storage("state-1"));
        copyTree(secondArchive, storage("state-2"));

        start(peers.get(0), group);
        start(peers.get(1), group);
        awaitCounter("state-1", 3L, Duration.ofSeconds(15));
        awaitCounter("state-2", 3L, Duration.ofSeconds(15));
        try (RatisStateClient client = client(group)) {
            assertEquals(4L, increment(client, groupId, 4));
        }
        awaitCounter("state-1", 4L, Duration.ofSeconds(15));
        awaitCounter("state-2", 4L, Duration.ofSeconds(15));

        start(peers.get(2), group);
        awaitCounter("state-3", 4L, Duration.ofSeconds(20));
        try (RatisStateClient client = client(group)) {
            assertEquals(5L, increment(client, groupId, 5));
        }
        for (RatisPeerDefinition peer : peers) {
            awaitCounter(peer.nodeId(), 5L, Duration.ofSeconds(15));
        }
    }

    private long increment(
            RatisStateClient client, StateGroupId groupId, long operationNumber)
            throws Exception {
        StateCommand command = new StateCommand(
                groupId,
                "backup-op-" + operationNumber,
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

    private void start(RatisPeerDefinition peer, RatisGroupDefinition group)
            throws Exception {
        SnapshotCounter counter = new SnapshotCounter(group.groupId());
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                storage(peer.nodeId()),
                Duration.ofMillis(300),
                Duration.ofMillis(600),
                Duration.ofSeconds(2),
                10_000L,
                false);
        RatisStateNode node = new RatisStateNode(
                options,
                ignored -> new RatisStateMachineAdapter(counter));
        node.start();
        nodes.put(peer.nodeId(), node);
        counters.put(peer.nodeId(), counter);
    }

    private void stop(String nodeId) throws Exception {
        RatisStateNode node = nodes.remove(nodeId);
        counters.remove(nodeId);
        if (node != null) {
            node.close();
        }
    }

    private void awaitCounter(String nodeId, long expected, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long actual = -1L;
        while (System.nanoTime() < deadline) {
            SnapshotCounter counter = counters.get(nodeId);
            actual = counter == null ? -1L : counter.value();
            if (actual == expected) {
                return;
            }
            Thread.sleep(100L);
        }
        assertEquals(expected, actual, "State voter did not converge: " + nodeId);
    }

    private RatisStateClient client(RatisGroupDefinition group) {
        return new RatisStateClient(group, Duration.ofSeconds(2), 10);
    }

    private Path storage(String nodeId) {
        return tempDirectory.resolve(nodeId);
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
        counters.clear();
        awaitAllocatedPortsReleased(Duration.ofSeconds(5));
    }

    private void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private long snapshotCount(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("snapshot."))
                    .filter(name -> !name.endsWith(".md5"))
                    .count();
        }
    }

    private RatisPeerDefinition peer(String nodeId) throws Exception {
        int port = freePort();
        allocatedPorts.add(port);
        return new RatisPeerDefinition(nodeId, "127.0.0.1", port);
    }

    private void awaitAllocatedPortsReleased(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Integer> pending = new ArrayList<>(allocatedPorts);
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            pending.removeIf(this::canBind);
            if (!pending.isEmpty()) {
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for Ratis ports to close", e);
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException(
                    "Ratis test ports were not released after close: " + pending);
        }
    }

    private boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
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

        private synchronized long value() {
            return value;
        }
    }
}
