package cloud.xuantong.raft.ratis;

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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatisNetworkPartitionIntegrationTest {
    private static final byte INCREMENT = 1;
    private static final byte GET = 2;

    @TempDir
    Path tempDirectory;

    @Test
    void quorumPartitionRejectsAcknowledgementAndRecoveryRemainsMonotonic()
            throws Exception {
        List<Integer> ports = freePorts(6);
        List<RatisPeerDefinition> peers = List.of(
                new RatisPeerDefinition("state-1", "127.0.0.1", ports.get(0)),
                new RatisPeerDefinition("state-2", "127.0.0.1", ports.get(1)),
                new RatisPeerDefinition("state-3", "127.0.0.1", ports.get(2)));
        RatisGroupDefinition group = new RatisGroupDefinition(
                cloud.xuantong.state.api.StateGroupId.meta("network-partition"),
                peers);
        List<TcpCutProxy> proxies = new ArrayList<>();
        List<RatisStateNode> nodes = new ArrayList<>();
        try {
            for (int index = 0; index < peers.size(); index++) {
                TcpCutProxy proxy = new TcpCutProxy(
                        peers.get(index).port(), ports.get(index + peers.size()));
                proxy.restore();
                proxies.add(proxy);
            }
            for (int index = 0; index < peers.size(); index++) {
                RatisPeerDefinition peer = peers.get(index);
                RatisNodeOptions options = new RatisNodeOptions(
                        peer.nodeId(),
                        group,
                        tempDirectory.resolve(peer.nodeId()),
                        0L,
                        Duration.ofMillis(300),
                        Duration.ofMillis(600),
                        Duration.ofSeconds(2),
                        10_000L,
                        false,
                        RatisNodeOptions.DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT,
                        RatisStartupMode.BOOTSTRAP_OR_RECOVER,
                        "127.0.0.1",
                        ports.get(index + peers.size()));
                RatisStateNode node = new RatisStateNode(
                        options, ignored -> new CounterStateMachine());
                node.start();
                nodes.add(node);
            }

            RatisResult initial;
            try (RatisStateClient client = new RatisStateClient(
                    group, Duration.ofSeconds(2), 5)) {
                initial = writeEventually(client, Duration.ofSeconds(15));
                assertEquals(1L, decodeLong(initial.payload()));
            }

            int leaderIndex = indexOf(peers, initial.serverId());
            List<Integer> followerIndexes = new ArrayList<>();
            for (int index = 0; index < peers.size(); index++) {
                if (index != leaderIndex) {
                    followerIndexes.add(index);
                }
            }
            proxies.get(followerIndexes.get(0)).cut();
            proxies.get(followerIndexes.get(1)).cut();

            try (RatisStateClient minority = new RatisStateClient(
                    group, Duration.ofMillis(600), 2)) {
                assertThrows(IOException.class,
                        () -> minority.write(new byte[]{INCREMENT}),
                        "A Leader partitioned from quorum must not acknowledge a write");
            }

            proxies.get(followerIndexes.get(0)).restore();
            proxies.get(followerIndexes.get(1)).restore();
            long valueAfterRecovery;
            try (RatisStateClient recovered = new RatisStateClient(
                    group, Duration.ofSeconds(2), 10)) {
                valueAfterRecovery = decodeLong(
                        readEventually(recovered, Duration.ofSeconds(20)).payload());
                assertTrue(valueAfterRecovery == 1L || valueAfterRecovery == 2L,
                        "The timed-out write is UNKNOWN and may commit after quorum recovery");
                RatisResult next = writeEventually(recovered, Duration.ofSeconds(15));
                assertEquals(valueAfterRecovery + 1L, decodeLong(next.payload()));
                valueAfterRecovery = decodeLong(next.payload());
            }

            try (RatisStateClient converged = new RatisStateClient(
                    group, Duration.ofSeconds(2), 10)) {
                for (RatisPeerDefinition peer : peers) {
                    assertEquals(valueAfterRecovery, decodeLong(readEventually(
                            converged, peer.nodeId(), Duration.ofSeconds(20)).payload()));
                }
            }
            for (RatisStateNode node : nodes) {
                assertTrue(node.isHealthy());
            }
            for (TcpCutProxy proxy : proxies) {
                proxy.assertNoFailure();
            }
        } finally {
            for (int index = nodes.size() - 1; index >= 0; index--) {
                try {
                    nodes.get(index).close();
                } catch (Exception ignored) {
                }
            }
            for (TcpCutProxy proxy : proxies) {
                proxy.close();
            }
        }
    }

    private int indexOf(List<RatisPeerDefinition> peers, String nodeId) {
        for (int index = 0; index < peers.size(); index++) {
            if (peers.get(index).nodeId().equals(nodeId)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown Raft peer: " + nodeId);
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

    private RatisResult readEventually(
            RatisStateClient client, String nodeId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.linearizableRead(new byte[]{GET}, nodeId);
            } catch (IOException e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null ? new IOException("Raft peer read did not complete") : last;
    }

    private List<Integer> freePorts(int count) throws Exception {
        List<ServerSocket> reservations = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                reservations.add(new ServerSocket(0));
            }
            return reservations.stream().map(ServerSocket::getLocalPort).toList();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: "
                            + e.getMessage());
            return List.of();
        } finally {
            for (ServerSocket reservation : reservations) {
                try {
                    reservation.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static long decodeLong(byte[] payload) {
        return ByteBuffer.wrap(payload).getLong();
    }

    private static final class TcpCutProxy implements AutoCloseable {
        private final int listenPort;
        private final int targetPort;
        private final ExecutorService io = Executors.newCachedThreadPool();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private volatile ServerSocket listener;
        private volatile boolean accepting;
        private volatile Throwable failure;

        private TcpCutProxy(int listenPort, int targetPort) {
            this.listenPort = listenPort;
            this.targetPort = targetPort;
        }

        private synchronized void restore() throws IOException {
            if (accepting) {
                return;
            }
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", listenPort));
            listener = server;
            accepting = true;
            io.submit(() -> acceptLoop(server));
        }

        private void acceptLoop(ServerSocket server) {
            while (accepting && listener == server) {
                try {
                    Socket downstream = server.accept();
                    downstream.setTcpNoDelay(true);
                    sockets.add(downstream);
                    io.submit(() -> bridge(downstream));
                } catch (IOException e) {
                    if (accepting && listener == server) {
                        failure = e;
                    }
                    return;
                }
            }
        }

        private void bridge(Socket downstream) {
            Socket upstream = new Socket();
            try {
                upstream.setTcpNoDelay(true);
                upstream.connect(new InetSocketAddress("127.0.0.1", targetPort), 1_000);
                sockets.add(upstream);
                Socket connectedUpstream = upstream;
                InputStream downstreamInput = downstream.getInputStream();
                OutputStream upstreamOutput = connectedUpstream.getOutputStream();
                io.submit(() -> pump(
                        downstream, connectedUpstream,
                        downstreamInput, upstreamOutput));
                pump(upstream, downstream,
                        upstream.getInputStream(), downstream.getOutputStream());
            } catch (IOException ignored) {
                closeSocket(upstream);
                closeSocket(downstream);
            }
        }

        private void pump(
                Socket source,
                Socket target,
                InputStream input,
                OutputStream output) {
            try {
                input.transferTo(output);
            } catch (IOException ignored) {
            } finally {
                closeSocket(source);
                closeSocket(target);
            }
        }

        private synchronized void cut() {
            accepting = false;
            ServerSocket server = listener;
            listener = null;
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
            for (Socket socket : List.copyOf(sockets)) {
                closeSocket(socket);
            }
        }

        private void closeSocket(Socket socket) {
            sockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private void assertNoFailure() {
            if (failure != null) {
                throw new AssertionError("TCP partition proxy failed", failure);
            }
        }

        @Override
        public void close() {
            cut();
            io.shutdownNow();
        }
    }

    private static final class CounterStateMachine extends BaseStateMachine {
        private long value;

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
