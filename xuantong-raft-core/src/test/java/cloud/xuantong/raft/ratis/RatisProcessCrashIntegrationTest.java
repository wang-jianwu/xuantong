package cloud.xuantong.raft.ratis;

import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.JavaUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RatisProcessCrashIntegrationTest {
    private static final byte INCREMENT = 1;
    private static final byte GET = 2;

    @TempDir
    Path tempDirectory;

    @Test
    void forceKilledProcessRecoversAcknowledgedWalAndContinuesWriting() throws Exception {
        int port = freePort();
        Path storage = tempDirectory.resolve("state-1");
        Path log = tempDirectory.resolve("child.log");
        Process first = startNode(port, storage, tempDirectory.resolve("ready-1"), log);
        try {
            RatisGroupDefinition group = group(port);
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                assertEquals(1L, decodeLong(writeEventually(
                        client, Duration.ofSeconds(15)).payload()));
                assertEquals(2L, decodeLong(client.write(new byte[]{INCREMENT}).payload()));
                assertEquals(3L, decodeLong(client.write(new byte[]{INCREMENT}).payload()));
            }
        } finally {
            forceKill(first);
        }
        assertNoSnapshot(storage);

        Process restarted = startNode(
                port, storage, tempDirectory.resolve("ready-2"), log);
        try {
            try (RatisStateClient client = new RatisStateClient(
                    group(port), Duration.ofSeconds(2), 5)) {
                assertEquals(3L, decodeLong(readEventually(
                        client, Duration.ofSeconds(15)).payload()));
                assertEquals(4L, decodeLong(writeEventually(
                        client, Duration.ofSeconds(15)).payload()));
            }
        } finally {
            forceKill(restarted);
        }
        assertFalse(Files.readString(log).contains(
                "Snapshot checksum mismatch"));
    }

    private Process startNode(
            int port, Path storage, Path ready, Path log) throws Exception {
        Files.deleteIfExists(ready);
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                CrashNodeMain.class.getName(),
                Integer.toString(port),
                storage.toString(),
                ready.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ready)) {
                return process;
            }
            if (!process.isAlive()) {
                throw new AssertionError(
                        "Ratis child exited before ready:\n" + Files.readString(log));
            }
            Thread.sleep(50L);
        }
        forceKill(process);
        throw new AssertionError("Ratis child did not become ready:\n"
                + (Files.exists(log) ? Files.readString(log) : "<no log>"));
    }

    private void forceKill(Process process) throws Exception {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS),
                "Force-killed Ratis child did not exit");
    }

    private RatisResult writeEventually(RatisStateClient client, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.write(new byte[]{INCREMENT});
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
                return client.linearizableRead(new byte[]{GET});
            } catch (IOException e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft read did not complete") : last;
    }

    private RatisGroupDefinition group(int port) {
        return new RatisGroupDefinition(
                cloud.xuantong.state.api.StateGroupId.meta("process-crash"),
                List.of(new RatisPeerDefinition("state-1", "127.0.0.1", port)));
    }

    private void assertNoSnapshot(Path storage) throws IOException {
        try (var files = Files.walk(storage)) {
            assertFalse(files.filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString()
                            .startsWith("snapshot.")));
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

    private static long decodeLong(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    public static final class CrashNodeMain {
        private CrashNodeMain() {
        }

        public static void main(String[] args) throws Exception {
            int port = Integer.parseInt(args[0]);
            Path storage = Path.of(args[1]);
            Path ready = Path.of(args[2]);
            RatisPeerDefinition peer = new RatisPeerDefinition(
                    "state-1", "127.0.0.1", port);
            RatisGroupDefinition group = new RatisGroupDefinition(
                    cloud.xuantong.state.api.StateGroupId.meta("process-crash"),
                    List.of(peer));
            RatisNodeOptions options = new RatisNodeOptions(
                    peer.nodeId(),
                    group,
                    storage,
                    Duration.ofMillis(300),
                    Duration.ofMillis(600),
                    Duration.ofSeconds(2),
                    10_000L,
                    false);
            RatisStateNode node = new RatisStateNode(
                    options, ignored -> new CrashCounterStateMachine());
            node.start();
            Files.writeString(ready, "ready", StandardCharsets.UTF_8);
            new CountDownLatch(1).await();
        }
    }

    private static final class CrashCounterStateMachine extends BaseStateMachine {
        private long value;

        @Override
        public void initialize(
                RaftServer server, RaftGroupId groupId, RaftStorage raftStorage)
                throws IOException {
            super.initialize(server, groupId, raftStorage);
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
        public TransactionContext startTransaction(RaftClientRequest request)
                throws IOException {
            TransactionContext transaction = super.startTransaction(request);
            if (!isCommand(request.getMessage().getContent(), INCREMENT)) {
                transaction.setException(new IllegalArgumentException(
                        "Unsupported command"));
            }
            return transaction;
        }

        @Override
        public CompletableFuture<Message> applyTransaction(
                TransactionContext transaction) {
            LogEntryProto entry = transaction.getLogEntry();
            return CompletableFuture.completedFuture(apply(TermIndex.valueOf(entry)));
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
            return Message.valueOf(ByteString.copyFrom(
                    ByteBuffer.allocate(Long.BYTES).putLong(value).array()));
        }
    }
}
