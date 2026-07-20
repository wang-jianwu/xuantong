package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.JavaUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Destructive integration test for an actual filesystem ENOSPC after a write
 * has entered Raft. It only runs on an explicitly marked, size-bounded test
 * volume; normal Maven test runs skip it.
 */
public class RatisEnospcIntegrationTest {
    private static final String VOLUME_ENV = "XUANTONG_ENOSPC_VOLUME";
    private static final String MAX_VOLUME_ENV = "XUANTONG_ENOSPC_MAX_VOLUME_BYTES";
    private static final String MARKER_FILE = ".xuantong-enospc-test-volume";
    private static final String MARKER_CONTENT = "xuantong-enospc-test-only";
    private static final long DEFAULT_MAX_VOLUME_BYTES = 1024L * 1024L * 1024L;
    private static final int FAULT_PAYLOAD_BYTES = 512 * 1024;
    private static final long TEST_LOG_PREALLOCATED_SIZE_BYTES = 64L * 1024L;
    private static final byte GET = 1;

    @Test
    void writeEnteringRaftDuringRealEnospcRemainsUnknownAndResolvesBeforeRetry()
            throws Exception {
        TestVolume volume = requireDedicatedTestVolume();
        Path runDirectory = volume.root().resolve(
                "xuantong-enospc-" + UUID.randomUUID());
        Files.createDirectories(runDirectory);
        Path storage = runDirectory.resolve("state");
        Path filler = runDirectory.resolve("fill.bin");
        int port = freePort();
        StateGroupId groupId = StateGroupId.meta("ratis-enospc");
        RatisGroupDefinition group = new RatisGroupDefinition(
                groupId,
                List.of(new RatisPeerDefinition(
                        "state-1", "127.0.0.1", port)));
        CountDownLatch enteredRaft = new CountDownLatch(1);
        CountDownLatch releaseRaft = new CountDownLatch(1);
        byte[] payload = incompressiblePayload();
        StateCommand faultCommand = new StateCommand(
                groupId,
                "enospc-operation-1",
                "test.increment",
                1,
                payload);

        RatisStateNode first = null;
        try {
            first = startNode(group, storage,
                    new EnospcStateMachine(
                            groupId, enteredRaft, releaseRaft, faultCommand.operationId()));
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(20), 1)) {
                submitEventually(client, new StateCommand(
                        groupId, "enospc-warmup", "test.noop", 1, new byte[0]));
                long walCapacity = activeWalSize(storage);
                assertTrue(walCapacity <= TEST_LOG_PREALLOCATED_SIZE_BYTES * 2L,
                        "Test WAL preallocation control was not applied: " + walCapacity);

                CompletableFuture<ApplyResult> pending = client.submit(faultCommand);
                assertTrue(enteredRaft.await(15, TimeUnit.SECONDS),
                        "Fault write did not enter Raft startTransaction");
                IOException exhausted = exhaustVolume(filler);
                assertNotNull(exhausted);
                releaseRaft.countDown();

                StateAccessException failure = awaitStateFailure(pending);
                assertEquals(StateCommitStatus.UNKNOWN, failure.commitStatus());
                assertNotEquals(StateFailureCode.STORAGE_EXHAUSTED, failure.code(),
                        "A post-admission filesystem failure must not be reported "
                                + "as a pre-Raft watermark rejection");
                assertTrue(failure.code() == StateFailureCode.STATE_UNAVAILABLE
                                || failure.code() == StateFailureCode.NO_QUORUM,
                        "Unexpected ENOSPC failure code: " + failure.code());
            } finally {
                releaseRaft.countDown();
                Files.deleteIfExists(filler);
                assertTrue(volume.fileStore().getUsableSpace()
                                > FAULT_PAYLOAD_BYTES * 4L,
                        "Dedicated volume did not reclaim space after deleting filler");
            }
        } finally {
            closeNode(first);
        }

        RatisStateNode restarted = null;
        try {
            restarted = startNode(
                    group, storage, new EnospcStateMachine(groupId, null, null, null));
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(20), 3)) {
                long resolvedValue = decodeLong(readEventually(client));
                assertTrue(resolvedValue == 0L || resolvedValue == 1L,
                        "Recovered state must resolve the ambiguous write");
                if (resolvedValue == 0L) {
                    ApplyResult retried = submitEventually(client, faultCommand);
                    assertEquals(1L, decodeLong(retried.payload()));
                }
                assertEquals(1L, decodeLong(readEventually(client)));
            }
        } finally {
            closeNode(restarted);
            deleteRecursively(runDirectory);
        }
    }

    private TestVolume requireDedicatedTestVolume() throws Exception {
        String configured = System.getenv(VOLUME_ENV);
        Assumptions.assumeTrue(
                configured != null && !configured.isBlank(),
                () -> VOLUME_ENV + " is not set; real ENOSPC test skipped");
        Path root = Path.of(configured).toRealPath();
        assertTrue(Files.isDirectory(root), "ENOSPC volume is not a directory: " + root);
        Path marker = root.resolve(MARKER_FILE);
        assertTrue(Files.isRegularFile(marker),
                "Refusing to fill an unmarked filesystem: " + marker);
        assertEquals(MARKER_CONTENT, Files.readString(marker).trim(),
                "Invalid ENOSPC volume marker");

        Path filesystemRoot = Path.of("/").toRealPath();
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        Path workingDirectory = Path.of("").toRealPath();
        assertNotEquals(filesystemRoot, root, "Refusing to fill the root filesystem");
        assertNotEquals(home, root, "Refusing to fill the user home filesystem");
        assertNotEquals(workingDirectory, root, "Refusing to fill the workspace filesystem");

        FileStore fileStore = Files.getFileStore(root);
        long maximum = parsePositiveLong(
                System.getenv(MAX_VOLUME_ENV), DEFAULT_MAX_VOLUME_BYTES);
        long total = fileStore.getTotalSpace();
        assertTrue(total > FAULT_PAYLOAD_BYTES * 4L,
                "ENOSPC volume is too small for Ratis startup: " + total);
        assertTrue(total <= maximum,
                "Refusing to fill a volume larger than " + maximum + " bytes: " + total);
        return new TestVolume(root, fileStore);
    }

    private RatisStateNode startNode(
            RatisGroupDefinition group,
            Path storage,
            EnospcStateMachine stateMachine) throws Exception {
        RatisPeerDefinition peer = group.peers().getFirst();
        RatisNodeOptions options = new RatisNodeOptions(
                peer.nodeId(),
                group,
                storage,
                0L,
                Duration.ofMillis(300),
                Duration.ofMillis(600),
                Duration.ofSeconds(20),
                10_000L,
                false,
                RatisNodeOptions.DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT,
                RatisStartupMode.BOOTSTRAP_OR_RECOVER,
                peer.host(),
                peer.port(),
                TEST_LOG_PREALLOCATED_SIZE_BYTES);
        RatisStateNode node = new RatisStateNode(options, ignored -> stateMachine);
        node.start();
        awaitLeaderReady(node, group, Duration.ofSeconds(15));
        return node;
    }

    private void awaitLeaderReady(
            RatisStateNode node,
            RatisGroupDefinition group,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (node.server().getDivision(group.toRaftGroupId())
                    .getInfo().isLeaderReady()) {
                return;
            }
            Thread.sleep(50L);
        }
        var division = node.server().getDivision(group.toRaftGroupId());
        var info = division.getInfo();
        var log = division.getRaftLog();
        throw new AssertionError(
                "Ratis Division did not become ready after ENOSPC recovery: "
                        + "lifeCycle=" + info.getLifeCycleState()
                        + ", role=" + info.getCurrentRole()
                        + ", leaderId=" + info.getLeaderId()
                        + ", leaderReady=" + info.isLeaderReady()
                        + ", term=" + info.getCurrentTerm()
                        + ", lastApplied=" + info.getLastAppliedIndex()
                        + ", logStart=" + log.getStartIndex()
                        + ", logNext=" + log.getNextIndex()
                        + ", logLast=" + log.getLastEntryTermIndex()
                        + ", commitIndex=" + log.getLastCommittedIndex()
                        + ", flushIndex=" + log.getFlushIndex());
    }

    private ApplyResult submitEventually(
            RatisStateClient client, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        StateAccessException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.submit(command).get(20, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable failure = unwrap(e);
                if (failure instanceof StateAccessException stateFailure) {
                    last = stateFailure;
                    Thread.sleep(100L);
                    continue;
                }
                throw e;
            }
        }
        if (last == null) {
            throw new AssertionError("State write did not complete");
        }
        throw last;
    }

    private byte[] readEventually(RatisStateClient client) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.linearizableRead(new byte[]{GET}).payload();
            } catch (IOException e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IOException("State read did not complete")
                : last;
    }

    private StateAccessException awaitStateFailure(
            CompletableFuture<ApplyResult> pending) throws Exception {
        try {
            ApplyResult result = pending.get(30, TimeUnit.SECONDS);
            return fail("Write unexpectedly succeeded while the test volume was full: "
                    + result);
        } catch (ExecutionException e) {
            Throwable failure = unwrap(e);
            if (failure instanceof StateAccessException stateFailure) {
                return stateFailure;
            }
            throw e;
        }
    }

    private IOException exhaustVolume(Path filler) throws Exception {
        IOException exhausted = null;
        int[] blockSizes = {1024 * 1024, 64 * 1024, 4 * 1024};
        for (int blockSize : blockSizes) {
            exhausted = fillUntilFailure(filler, blockSize);
        }
        assertNotNull(exhausted, "Expected the dedicated volume to report ENOSPC");
        assertTrue(isSpaceExhaustion(exhausted),
                "Dedicated volume write failed for a reason other than ENOSPC: "
                        + exhausted);
        return exhausted;
    }

    private IOException fillUntilFailure(Path filler, int blockSize) throws Exception {
        byte[] bytes = new byte[blockSize];
        new Random(0x5855414e544f4e47L + blockSize).nextBytes(bytes);
        StandardOpenOption create = Files.exists(filler)
                ? StandardOpenOption.APPEND
                : StandardOpenOption.CREATE_NEW;
        try (FileChannel channel = FileChannel.open(
                filler, create, StandardOpenOption.WRITE)) {
            ByteBuffer block = ByteBuffer.wrap(bytes);
            int blocks = 0;
            while (true) {
                block.rewind();
                while (block.hasRemaining()) {
                    channel.write(block);
                }
                if (++blocks % 16 == 0) {
                    channel.force(false);
                }
            }
        } catch (IOException e) {
            return e;
        }
    }

    private long activeWalSize(Path storage) throws IOException {
        try (var paths = Files.walk(storage)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .startsWith("log_inprogress_"))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            throw new WalInspectionException(e);
                        }
                    })
                    .max()
                    .orElseThrow(() -> new IOException(
                            "Active Ratis WAL was not found under " + storage));
        } catch (WalInspectionException e) {
            throw e.ioException;
        }
    }

    private boolean isSpaceExhaustion(IOException failure) {
        String details = failure.toString().toLowerCase(Locale.ROOT);
        return details.contains("no space left")
                || details.contains("enospc")
                || details.contains("disk quota")
                || details.contains("空间");
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

    private byte[] incompressiblePayload() {
        byte[] payload = new byte[FAULT_PAYLOAD_BYTES];
        new Random(0x454e4f535043L).nextBytes(payload);
        return payload;
    }

    private void closeNode(RatisStateNode node) throws IOException {
        if (node != null) {
            node.close();
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static long parsePositiveLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long parsed = Long.parseLong(value.trim());
        if (parsed < 1L) {
            throw new IllegalArgumentException(
                    MAX_VOLUME_ENV + " must be positive");
        }
        return parsed;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof ExecutionException
                || current instanceof java.util.concurrent.CompletionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static byte[] encodeLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long decodeLong(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    private record TestVolume(Path root, FileStore fileStore) {
    }

    private static final class WalInspectionException extends RuntimeException {
        private final IOException ioException;

        private WalInspectionException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }

    private static final class EnospcStateMachine extends BaseStateMachine {
        private final StateGroupId groupId;
        private final CountDownLatch enteredRaft;
        private final CountDownLatch releaseRaft;
        private final String blockedOperationId;
        private final Map<String, Long> operationResults = new HashMap<>();
        private long value;

        private EnospcStateMachine(
                StateGroupId groupId,
                CountDownLatch enteredRaft,
                CountDownLatch releaseRaft,
                String blockedOperationId) {
            this.groupId = groupId;
            this.enteredRaft = enteredRaft;
            this.releaseRaft = releaseRaft;
            this.blockedOperationId = blockedOperationId;
        }

        @Override
        public TransactionContext startTransaction(RaftClientRequest request)
                throws IOException {
            TransactionContext transaction = super.startTransaction(request);
            try {
                StateCommand command = RatisStateMessageCodec.decodeCommand(
                        request.getMessage().getContent().toByteArray());
                if (!groupId.equals(command.groupId())) {
                    throw new IllegalArgumentException("Unexpected State Group");
                }
                if (enteredRaft != null
                        && blockedOperationId.equals(command.operationId())) {
                    enteredRaft.countDown();
                    if (!releaseRaft.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for ENOSPC injection");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                transaction.setException(new IOException(
                        "Interrupted while waiting for ENOSPC injection", e));
            } catch (Exception e) {
                transaction.setException(e);
            }
            return transaction;
        }

        @Override
        public CompletableFuture<Message> applyTransaction(
                TransactionContext transaction) {
            try {
                LogEntryProto entry = transaction.getLogEntry();
                TermIndex termIndex = TermIndex.valueOf(entry);
                StateCommand command = RatisStateMessageCodec.decodeCommand(
                        entry.getStateMachineLogEntry().getLogData().toByteArray());
                ApplyResult result = apply(command, termIndex.getIndex());
                updateLastAppliedTermIndex(termIndex);
                return CompletableFuture.completedFuture(Message.valueOf(
                        ByteString.copyFrom(
                                RatisStateMessageCodec.encodeApplyResult(result))));
            } catch (Exception e) {
                return JavaUtils.completeExceptionally(e);
            }
        }

        @Override
        public CompletableFuture<Message> query(Message request) {
            ByteString content = request.getContent();
            if (content.size() != 1 || content.byteAt(0) != GET) {
                return JavaUtils.completeExceptionally(
                        new IllegalArgumentException("Unsupported query"));
            }
            synchronized (this) {
                return CompletableFuture.completedFuture(Message.valueOf(
                        ByteString.copyFrom(encodeLong(value))));
            }
        }

        private synchronized ApplyResult apply(
                StateCommand command, long appliedIndex) {
            Long previous = operationResults.get(command.operationId());
            ApplyStatus status;
            long result;
            if (previous != null) {
                status = ApplyStatus.UNCHANGED;
                result = previous;
            } else if ("test.increment".equals(command.commandType())) {
                status = ApplyStatus.APPLIED;
                result = ++value;
                operationResults.put(command.operationId(), result);
            } else if ("test.noop".equals(command.commandType())) {
                status = ApplyStatus.APPLIED;
                result = value;
                operationResults.put(command.operationId(), result);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported command: " + command.commandType());
            }
            return new ApplyResult(
                    groupId,
                    command.operationId(),
                    status,
                    appliedIndex,
                    "enospc.value",
                    encodeLong(result),
                    List.of());
        }
    }
}
