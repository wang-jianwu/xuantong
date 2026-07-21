package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.proto.RaftProtos.RaftPeerRole;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.MD5FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisThreeNodePoCTest {
    private static final byte INCREMENT = 1;
    private static final byte GET = 2;

    @TempDir
    Path tempDirectory;

    private final List<RatisStateNode> openNodes = new ArrayList<>();

    @AfterEach
    void closeNodes() {
        closeAllNodes();
    }

    @Test
    void everyReplicaBecomesReadyAfterCurrentTermStartupEntryIsApplied()
            throws Exception {
        ClusterFixture cluster = startCluster("readiness-poc");

        for (RatisStateNode node : cluster.nodes().values()) {
            node.awaitReady(List.of(cluster.group().groupId()), Duration.ofSeconds(10));
            assertTrue(node.isReady(List.of(cluster.group().groupId())));
        }
        List<RatisGroupRuntimeStatus> statuses = cluster.nodes().values().stream()
                .map(node -> runtimeStatus(node, cluster.group().groupId()))
                .toList();
        assertEquals(1L, statuses.stream()
                .filter(RatisGroupRuntimeStatus::leader)
                .count());
        assertTrue(statuses.stream().allMatch(
                status -> status.alive()
                        && !status.leaderId().isBlank()
                        && status.lastCommittedIndex() >= 0L
                        && status.lastAppliedIndex() >= 0L));
    }

    private RatisGroupRuntimeStatus runtimeStatus(
            RatisStateNode node, StateGroupId groupId) {
        try {
            return node.groupRuntimeStatus(groupId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Ratis Group status", e);
        }
    }

    @Test
    void replyWaitsForApplyAndSnapshotRecoversAllReplicas() throws Exception {
        ClusterFixture cluster = startCluster("config-poc");
        try (RatisStateClient client = new RatisStateClient(
                cluster.group(), Duration.ofSeconds(2), 5)) {
            RatisResult initial = writeEventually(client, Duration.ofSeconds(15));
            assertEquals(1L, decodeLong(initial.payload()));

            ApplyBarrier barrier = new ApplyBarrier();
            cluster.applyBarrier().set(barrier);
            CompletableFuture<RatisResult> pending = client.writeAsync(command(INCREMENT));
            assertTrue(barrier.entered().await(5, TimeUnit.SECONDS),
                    "Leader must enter state-machine apply after quorum commit");
            assertFalse(pending.isDone(),
                    "Client reply must not complete before state-machine apply finishes");
            barrier.release().countDown();

            RatisResult applied = pending.get(5, TimeUnit.SECONDS);
            assertEquals(2L, decodeLong(applied.payload()));
            cluster.applyBarrier().set(null);
            assertEquals(2L, decodeLong(client.linearizableRead(command(GET)).payload()));

            RatisResult snapshot = client.forceSnapshot(Duration.ofSeconds(5));
            assertTrue(snapshot.logIndex() >= applied.logIndex());
            waitForSnapshot(cluster.storageDirectories(), Duration.ofSeconds(10));
        }

        closeAllNodes();
        ClusterFixture restarted = startCluster(cluster);
        try (RatisStateClient client = new RatisStateClient(
                restarted.group(), Duration.ofSeconds(2), 5)) {
            assertEquals(2L, decodeLong(readEventually(client, Duration.ofSeconds(15)).payload()));
            for (RatisPeerDefinition peer : restarted.group().peers()) {
                assertEquals(2L, decodeLong(readEventually(
                        client, peer.nodeId(), Duration.ofSeconds(10)).payload()));
            }
        }
    }

    @Test
    void leaderFailureElectsReplacementAndLostQuorumRecovers() throws Exception {
        ClusterFixture cluster = startCluster("registry-poc");
        RatisResult first;
        try (RatisStateClient client = new RatisStateClient(
                cluster.group(), Duration.ofSeconds(2), 5)) {
            first = writeEventually(client, Duration.ofSeconds(15));
        }
        RatisStateNode oldLeader = cluster.nodes().get(first.serverId());
        oldLeader.close();
        openNodes.remove(oldLeader);

        RatisResult afterFailover;
        try (RatisStateClient client = new RatisStateClient(
                cluster.group(), Duration.ofSeconds(2), 10)) {
            afterFailover = writeEventually(client, Duration.ofSeconds(15));
            assertEquals(2L, decodeLong(afterFailover.payload()));
            assertNotEquals(first.serverId(), afterFailover.serverId());
        }

        RatisStateNode remainingFollower = cluster.nodes().values().stream()
                .filter(node -> !node.nodeId().equals(first.serverId()))
                .filter(node -> !node.nodeId().equals(afterFailover.serverId()))
                .findFirst()
                .orElseThrow();
        remainingFollower.close();
        openNodes.remove(remainingFollower);

        try (RatisStateClient minorityClient = new RatisStateClient(
                cluster.group(), Duration.ofMillis(600), 2)) {
            assertThrows(IOException.class, () -> minorityClient.write(command(INCREMENT)),
                    "A single remaining voter must not acknowledge a write");
        }

        RatisPeerDefinition recoveringPeer = cluster.group().requirePeer(
                remainingFollower.nodeId());
        int recoveringIndex = cluster.group().peers().indexOf(recoveringPeer);
        RatisStateNode recoveredFollower = startNode(
                cluster.group(),
                cluster.storageDirectories().get(recoveringIndex),
                recoveringPeer,
                cluster.applyBarrier(),
                cluster.snapshotAutoTriggerThreshold(),
                cluster.snapshotOnShutdown());
        cluster.nodes().put(recoveringPeer.nodeId(), recoveredFollower);

        try (RatisStateClient recoveredClient = new RatisStateClient(
                cluster.group(), Duration.ofSeconds(2), 10)) {
            long valueAfterQuorumRecovery = decodeLong(
                    readEventually(recoveredClient, Duration.ofSeconds(15)).payload());
            RatisResult next = writeEventually(
                    recoveredClient, Duration.ofSeconds(15));
            assertEquals(valueAfterQuorumRecovery + 1L, decodeLong(next.payload()));
            assertEquals(decodeLong(next.payload()), decodeLong(readEventually(
                    recoveredClient,
                    recoveringPeer.nodeId(),
                    Duration.ofSeconds(10)).payload()));
        }
    }

    @Test
    void walReplayRecoversWithoutSnapshot() throws Exception {
        ClusterFixture cluster = startCluster("wal-poc", 10_000L, false);
        try (RatisStateClient client = new RatisStateClient(
                cluster.group(), Duration.ofSeconds(2), 5)) {
            assertEquals(1L, decodeLong(writeEventually(client, Duration.ofSeconds(15)).payload()));
            assertEquals(2L, decodeLong(client.write(command(INCREMENT)).payload()));
            assertEquals(3L, decodeLong(client.write(command(INCREMENT)).payload()));
            assertNoSnapshot(cluster.storageDirectories());
        }

        closeAllNodes();
        ClusterFixture restarted = startCluster(cluster);
        try (RatisStateClient client = new RatisStateClient(
                restarted.group(), Duration.ofSeconds(2), 5)) {
            assertEquals(3L, decodeLong(readEventually(client, Duration.ofSeconds(15)).payload()));
            assertNoSnapshot(restarted.storageDirectories());
        }
    }

    @Test
    void corruptedWalHeaderFailsNodeStartupAndRemainsUnhealthy() throws Exception {
        assertWalDamage("header", this::corruptFirstByte, true);
    }

    @Test
    void corruptedCommittedWalEntryFailsNodeStartupAndRemainsUnhealthy() throws Exception {
        assertWalDamage("entry-checksum", this::corruptLastByte, true);
    }

    @Test
    void incompleteWalTailPreservesAcknowledgedStateAndRemainsWritable() throws Exception {
        assertWalDamage("incomplete-tail", this::appendIncompleteByte, false);
    }

    private void assertWalDamage(
            String caseName, WalDamage damage, boolean expectStartupFailure)
            throws Exception {
        RatisPeerDefinition peer = new RatisPeerDefinition(
                "state-1", "127.0.0.1", freePort());
        RatisGroupDefinition group = new RatisGroupDefinition(
                StateGroupId.meta("wal-corruption-" + caseName), List.of(peer));
        Path storage = tempDirectory.resolve("wal-corruption-" + caseName)
                .resolve(peer.nodeId());
        AtomicReference<ApplyBarrier> applyBarrier = new AtomicReference<>();
        RatisStateNode first = startNode(
                group, storage, peer, applyBarrier, 10_000L, false);
        try (RatisStateClient client = new RatisStateClient(
                group, Duration.ofSeconds(2), 5)) {
            assertEquals(1L, decodeLong(
                    writeEventually(client, Duration.ofSeconds(15)).payload()));
            assertEquals(2L, decodeLong(client.write(command(INCREMENT)).payload()));
            assertTrue(first.isHealthy());
            assertNoSnapshot(List.of(storage));
        }
        first.close();
        openNodes.remove(first);
        assertFalse(first.isHealthy());

        Path wal = latestWal(storage);
        damage.apply(wal);
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                storage,
                Duration.ofMillis(300),
                Duration.ofMillis(600),
                Duration.ofSeconds(2),
                10_000L,
                false);
        try (RatisStateNode corrupted = new RatisStateNode(
                options, ignored -> new SnapshotCounterStateMachine(applyBarrier))) {
            if (expectStartupFailure) {
                assertThrows(Exception.class, corrupted::start);
                assertFalse(corrupted.isHealthy());
                return;
            }
            corrupted.start();
            assertTrue(corrupted.isHealthy());
            try (RatisStateClient recovered = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                assertEquals(2L, decodeLong(
                        readEventually(recovered, Duration.ofSeconds(15)).payload()),
                        "Tail recovery must not roll back an acknowledged entry");
                assertEquals(3L, decodeLong(
                        writeEventually(recovered, Duration.ofSeconds(15)).payload()));
            }
        }
    }

    @FunctionalInterface
    private interface WalDamage {
        void apply(Path wal) throws IOException;
    }

    @Test
    void insufficientStorageReserveFailsNodeStartupAndRemainsUnhealthy() throws Exception {
        RatisPeerDefinition peer = new RatisPeerDefinition(
                "state-1", "127.0.0.1", freePort());
        RatisGroupDefinition group = new RatisGroupDefinition(
                StateGroupId.meta("storage-reserve"), List.of(peer));
        Path storage = Files.createDirectories(
                tempDirectory.resolve("storage-reserve").resolve(peer.nodeId()));
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                storage,
                Long.MAX_VALUE,
                Duration.ofMillis(300),
                Duration.ofMillis(600),
                Duration.ofSeconds(2),
                10_000L,
                false,
                RatisNodeOptions.DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT,
                RatisStartupMode.BOOTSTRAP_OR_RECOVER);
        try (RatisStateNode node = new RatisStateNode(
                options, ignored -> new SnapshotCounterStateMachine(
                        new AtomicReference<>()))) {
            assertThrows(Exception.class, node::start);
            assertFalse(node.isHealthy());
        }
    }

    private ClusterFixture startCluster(String groupId) throws Exception {
        return startCluster(groupId, 1L, true);
    }

    private ClusterFixture startCluster(
            String groupId,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown) throws Exception {
        List<RatisPeerDefinition> peers = List.of(
                new RatisPeerDefinition("state-1", "127.0.0.1", freePort()),
                new RatisPeerDefinition("state-2", "127.0.0.1", freePort()),
                new RatisPeerDefinition("state-3", "127.0.0.1", freePort()));
        List<Path> storageDirectories = peers.stream()
                .map(peer -> tempDirectory.resolve(groupId).resolve(peer.nodeId()))
                .toList();
        return startCluster(new RatisGroupDefinition(StateGroupId.meta(groupId), peers),
                storageDirectories, new AtomicReference<>(), snapshotAutoTriggerThreshold,
                snapshotOnShutdown);
    }

    private ClusterFixture startCluster(ClusterFixture previous) throws Exception {
        return startCluster(previous.group(), previous.storageDirectories(),
                new AtomicReference<>(), previous.snapshotAutoTriggerThreshold(),
                previous.snapshotOnShutdown());
    }

    private ClusterFixture startCluster(
            RatisGroupDefinition group,
            List<Path> storageDirectories,
            AtomicReference<ApplyBarrier> applyBarrier,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown) throws Exception {
        Map<String, RatisStateNode> nodes = new LinkedHashMap<>();
        for (int i = 0; i < group.peers().size(); i++) {
            RatisPeerDefinition peer = group.peers().get(i);
            RatisStateNode node = startNode(
                    group,
                    storageDirectories.get(i),
                    peer,
                    applyBarrier,
                    snapshotAutoTriggerThreshold,
                    snapshotOnShutdown);
            nodes.put(peer.nodeId(), node);
        }
        return new ClusterFixture(group, storageDirectories, nodes, applyBarrier,
                snapshotAutoTriggerThreshold, snapshotOnShutdown);
    }

    private RatisStateNode startNode(
            RatisGroupDefinition group,
            Path storageDirectory,
            RatisPeerDefinition peer,
            AtomicReference<ApplyBarrier> applyBarrier,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown) throws IOException {
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                storageDirectory,
                Duration.ofMillis(300),
                Duration.ofMillis(600),
                Duration.ofSeconds(2),
                snapshotAutoTriggerThreshold,
                snapshotOnShutdown);
        RatisStateNode node = new RatisStateNode(
                options, ignored -> new SnapshotCounterStateMachine(applyBarrier));
        node.start();
        openNodes.add(node);
        return node;
    }

    private RatisResult writeEventually(RatisStateClient client, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.write(command(INCREMENT));
            } catch (IOException e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft write did not complete") : last;
    }

    private RatisResult readEventually(RatisStateClient client, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.linearizableRead(command(GET));
            } catch (IOException e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft read did not complete") : last;
    }

    private RatisResult readEventually(
            RatisStateClient client, String nodeId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.linearizableRead(command(GET), nodeId);
            } catch (IOException e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft peer read did not complete") : last;
    }

    private void waitForSnapshot(List<Path> directories, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            int directoriesWithSnapshot = 0;
            for (Path directory : directories) {
                if (!Files.exists(directory)) {
                    continue;
                }
                try (var files = Files.walk(directory)) {
                    boolean found = files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().startsWith("snapshot"))
                            .findAny()
                            .isPresent();
                    if (found) {
                        directoriesWithSnapshot++;
                    }
                }
            }
            if (directoriesWithSnapshot == directories.size()) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Not every Raft replica created a snapshot");
    }

    private void assertNoSnapshot(List<Path> directories) throws IOException {
        for (Path directory : directories) {
            if (!Files.exists(directory)) {
                continue;
            }
            try (var files = Files.walk(directory)) {
                assertFalse(files.filter(Files::isRegularFile)
                                .anyMatch(path -> path.getFileName().toString()
                                        .startsWith("snapshot")),
                        () -> "Unexpected Raft snapshot in " + directory);
            }
        }
    }

    private Path latestWal(Path storage) throws IOException {
        try (var files = Files.walk(storage)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("log_"))
                    .max((left, right) -> {
                        try {
                            return Files.getLastModifiedTime(left).compareTo(
                                    Files.getLastModifiedTime(right));
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .orElseThrow(() -> new AssertionError(
                            "Raft WAL was not created under " + storage));
        }
    }

    private void corruptFirstByte(Path wal) throws IOException {
        corruptByte(wal, 0L);
    }

    private void corruptLastByte(Path wal) throws IOException {
        long size = Files.size(wal);
        assertTrue(size > 1L, () -> "Raft WAL is unexpectedly empty: " + wal);
        corruptByte(wal, size - 1L);
    }

    private void corruptByte(Path wal, long position) throws IOException {
        try (var channel = Files.newByteChannel(
                wal, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer value = ByteBuffer.allocate(1);
            channel.position(position);
            assertEquals(1, channel.read(value));
            value.flip();
            byte corrupted = (byte) (value.get() ^ 0x5A);
            value.clear();
            value.put(corrupted).flip();
            channel.position(position);
            assertEquals(1, channel.write(value));
        }
    }

    private void appendIncompleteByte(Path wal) throws IOException {
        assertTrue(Files.size(wal) > 1L,
                () -> "Raft WAL is unexpectedly empty: " + wal);
        Files.write(wal, new byte[]{0x01}, StandardOpenOption.APPEND);
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

    private static byte[] command(byte command) {
        return new byte[]{command};
    }

    private static byte[] encodeLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long decodeLong(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    private record ApplyBarrier(CountDownLatch entered, CountDownLatch release) {
        private ApplyBarrier() {
            this(new CountDownLatch(1), new CountDownLatch(1));
        }
    }

    private record ClusterFixture(
            RatisGroupDefinition group,
            List<Path> storageDirectories,
            Map<String, RatisStateNode> nodes,
            AtomicReference<ApplyBarrier> applyBarrier,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown) {
    }

    private static final class SnapshotCounterStateMachine extends BaseStateMachine {
        private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();
        private final AtomicReference<ApplyBarrier> applyBarrier;
        private long value;

        private SnapshotCounterStateMachine(AtomicReference<ApplyBarrier> applyBarrier) {
            this.applyBarrier = applyBarrier;
        }

        @Override
        public void initialize(
                RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
            super.initialize(server, groupId, raftStorage);
            storage.init(raftStorage);
            reinitialize();
        }

        @Override
        public synchronized void reinitialize() throws IOException {
            SingleFileSnapshotInfo snapshot = storage.loadLatestSnapshot();
            if (snapshot == null) {
                return;
            }
            Path snapshotPath = snapshot.getFile().getPath();
            MD5Hash md5 = snapshot.getFile().getFileDigest();
            if (md5 != null) {
                MD5FileUtil.verifySavedMD5(snapshotPath.toFile(), md5);
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(snapshotPath))) {
                value = input.readLong();
            }
            updateLastAppliedTermIndex(
                    SimpleStateMachineStorage.getTermIndexFromSnapshotFile(snapshotPath.toFile()));
        }

        @Override
        public SimpleStateMachineStorage getStateMachineStorage() {
            return storage;
        }

        @Override
        public synchronized long takeSnapshot() throws IOException {
            TermIndex applied = getLastAppliedTermIndex();
            if (applied == null) {
                return 0L;
            }
            Path target = storage.getSnapshotFile(
                    applied.getTerm(), applied.getIndex()).toPath();
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeLong(value);
            }
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            MD5Hash md5 = MD5FileUtil.computeAndSaveMd5ForFile(target.toFile());
            storage.updateLatestSnapshot(new SingleFileSnapshotInfo(
                    new FileInfo(target, md5), applied));
            return applied.getIndex();
        }

        @Override
        public CompletableFuture<Message> query(Message request) {
            if (!isCommand(request.getContent(), GET)) {
                return JavaUtils.completeExceptionally(
                        new IllegalArgumentException("Unsupported query"));
            }
            synchronized (this) {
                return CompletableFuture.completedFuture(message(value));
            }
        }

        @Override
        public TransactionContext startTransaction(RaftClientRequest request) throws IOException {
            TransactionContext transaction = super.startTransaction(request);
            if (!isCommand(request.getMessage().getContent(), INCREMENT)) {
                transaction.setException(new IllegalArgumentException("Unsupported command"));
            }
            return transaction;
        }

        @Override
        public CompletableFuture<Message> applyTransaction(TransactionContext transaction) {
            LogEntryProto entry = transaction.getLogEntry();
            TermIndex termIndex = TermIndex.valueOf(entry);
            ApplyBarrier barrier = transaction.getServerRole() == RaftPeerRole.LEADER
                    ? applyBarrier.get()
                    : null;
            if (barrier == null) {
                return CompletableFuture.completedFuture(apply(termIndex));
            }
            barrier.entered().countDown();
            try {
                if (!barrier.release().await(5, TimeUnit.SECONDS)) {
                    return JavaUtils.completeExceptionally(
                            new IllegalStateException("Apply barrier was not released"));
                }
                return CompletableFuture.completedFuture(apply(termIndex));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return JavaUtils.completeExceptionally(
                        new IllegalStateException("Interrupted while waiting to apply", e));
            }
        }

        private synchronized Message apply(TermIndex termIndex) {
            updateLastAppliedTermIndex(termIndex);
            value++;
            return message(value);
        }

        private static boolean isCommand(ByteString content, byte command) {
            return content.size() == 1 && content.byteAt(0) == command;
        }

        private static Message message(long value) {
            return Message.valueOf(ByteString.copyFrom(encodeLong(value)));
        }
    }
}
