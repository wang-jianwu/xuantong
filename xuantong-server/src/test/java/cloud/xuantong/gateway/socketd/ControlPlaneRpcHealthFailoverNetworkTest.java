package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.impl.SocketDTransport;
import cloud.xuantong.protocol.v2.ConfigContentValue;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.ConfigFetchRequest;
import cloud.xuantong.protocol.v2.ConfigFetchResponse;
import cloud.xuantong.protocol.v2.ConfigSnapshotResponse;
import cloud.xuantong.protocol.v2.ConfigValueState;
import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that an open Socket.D channel with a lost Reply is not treated as RPC healthy. */
class ControlPlaneRpcHealthFailoverNetworkTest {
    @Test
    void lostReplyOnOpenSessionTriggersOneSequentialFailover() throws Exception {
        List<Integer> ports = freePorts(2);
        DispatcherHarness dispatcherA = dispatcher("gateway-a-value");
        DispatcherHarness dispatcherB = dispatcher("gateway-b-value");
        ControlPlaneGatewayProperties propertiesA = properties(
                "gateway-dropped-reply-a", ports.get(0));
        ControlPlaneGatewayProperties propertiesB = properties(
                "gateway-dropped-reply-b", ports.get(1));
        ControlPlaneGatewayRuntime runtimeA = new ControlPlaneGatewayRuntime(propertiesA);
        ControlPlaneGatewayRuntime runtimeB = new ControlPlaneGatewayRuntime(propertiesB);
        DroppingFetchEndpoint endpointA = new DroppingFetchEndpoint(
                propertiesA, runtimeA, dispatcherA.dispatcher);
        GatewayHarness gatewayA = gateway(propertiesA, runtimeA, endpointA);
        GatewayHarness gatewayB = gateway(
                propertiesB,
                runtimeB,
                new ControlPlaneGatewayEndpoint(
                        propertiesB, runtimeB, dispatcherB.dispatcher));
        CountingSocketDTransport transport = new CountingSocketDTransport(
                new ClientIdentity("dropped-reply-client", "dropped-reply-client@1"),
                new ControlPlaneOptions(
                        "default",
                        "config-default",
                        "cluster-rpc-health-test",
                        1L,
                        "tcp-default",
                        600L,
                        600L,
                        3_000L,
                        500L));
        ExecutorService fetchWorker = Executors.newSingleThreadExecutor();
        try {
            gatewayA.start();
            gatewayB.start();
            transport.connect(
                    List.of(
                            "127.0.0.1:" + ports.get(0),
                            "127.0.0.1:" + ports.get(1)),
                    "public",
                    "DEFAULT_GROUP",
                    "");
            assertEquals(1, transport.openAttempts(0));
            assertEquals(0, transport.openAttempts(1));
            assertEquals(1, runtimeA.activeSessions());
            assertEquals(0, runtimeB.activeSessions());

            Future<ConfigSnapshot> pending = fetchWorker.submit(
                    () -> transport.fetch("dropped.reply", 0L));
            assertTrue(endpointA.replyDropped.await(2, TimeUnit.SECONDS));
            assertEquals(1, runtimeA.activeSessions(),
                    "The physical Session remains open when only the Reply is lost");
            assertEquals(0, runtimeB.activeSessions(),
                    "The standby must not be contacted before the first attempt fails");
            assertFalse(pending.isDone());

            ConfigSnapshot snapshot = pending.get(6, TimeUnit.SECONDS);
            assertNotNull(snapshot);
            assertEquals("gateway-b-value", snapshot.getContent());
            assertEquals(1, transport.openAttempts(0));
            assertEquals(1, transport.openAttempts(1));
            assertEquals(1, endpointA.fetchRequests.get());
            assertEquals(1, dispatcherA.fetchRequests.get());
            assertEquals(1, dispatcherB.fetchRequests.get());
            awaitTrue(() -> runtimeA.activeSessions() == 0
                            && runtimeB.activeSessions() == 1,
                    Duration.ofSeconds(5));
            assertEquals(runtimeA.requestAcceptedTotal(),
                    runtimeA.requestCompletedTotal(),
                    "Dropping the wire Reply must not leak the Gateway request lifecycle");
        } finally {
            fetchWorker.shutdownNow();
            transport.close();
            gatewayA.stop();
            gatewayB.stop();
        }
        assertEquals(0, runtimeA.activeSessions());
        assertEquals(0, runtimeB.activeSessions());
        assertEquals(runtimeB.requestAcceptedTotal(), runtimeB.requestCompletedTotal());
    }

    @Test
    void lateReplyAfterTimeoutDoesNotReplaceTheHealthyStandby() throws Exception {
        List<Integer> ports = freePorts(2);
        DispatcherHarness dispatcherA = dispatcher("late-gateway-a-value", 1_500L);
        DispatcherHarness dispatcherB = dispatcher("gateway-b-value");
        ControlPlaneGatewayProperties propertiesA = properties(
                "gateway-delayed-reply-a", ports.get(0));
        ControlPlaneGatewayProperties propertiesB = properties(
                "gateway-delayed-reply-b", ports.get(1));
        ControlPlaneGatewayRuntime runtimeA = new ControlPlaneGatewayRuntime(propertiesA);
        ControlPlaneGatewayRuntime runtimeB = new ControlPlaneGatewayRuntime(propertiesB);
        GatewayHarness gatewayA = gateway(
                propertiesA,
                runtimeA,
                new ControlPlaneGatewayEndpoint(
                        propertiesA, runtimeA, dispatcherA.dispatcher));
        GatewayHarness gatewayB = gateway(
                propertiesB,
                runtimeB,
                new ControlPlaneGatewayEndpoint(
                        propertiesB, runtimeB, dispatcherB.dispatcher));
        CountingSocketDTransport transport = new CountingSocketDTransport(
                new ClientIdentity("delayed-reply-client", "delayed-reply-client@1"),
                new ControlPlaneOptions(
                        "default",
                        "config-default",
                        "cluster-rpc-health-test",
                        1L,
                        "tcp-default",
                        500L,
                        500L,
                        3_000L,
                        500L));
        try {
            gatewayA.start();
            gatewayB.start();
            transport.connect(
                    List.of(
                            "127.0.0.1:" + ports.get(0),
                            "127.0.0.1:" + ports.get(1)),
                    "public",
                    "DEFAULT_GROUP",
                    "");

            ConfigSnapshot snapshot = transport.fetch("delayed.reply", 0L);
            assertNotNull(snapshot);
            assertEquals("gateway-b-value", snapshot.getContent());
            assertEquals(1, dispatcherA.fetchRequests.get());
            assertEquals(1, dispatcherB.fetchRequests.get());
            assertEquals(1, transport.openAttempts(0));
            assertEquals(1, transport.openAttempts(1));
            assertFalse(dispatcherA.fetchCompleted.await(100, TimeUnit.MILLISECONDS),
                    "The first Gateway Reply must still be pending after failover succeeds");

            assertTrue(dispatcherA.fetchCompleted.await(3, TimeUnit.SECONDS));
            awaitTrue(() -> runtimeA.requestAcceptedTotal()
                            == runtimeA.requestCompletedTotal(),
                    Duration.ofSeconds(3));
            assertEquals(1L, runtimeA.lateReplyDroppedTotal());
            assertEquals(0, runtimeA.activeSessions());
            assertEquals(1, runtimeB.activeSessions());

            ConfigSnapshot afterLateReply = transport.fetch("delayed.reply", 1L);
            assertNotNull(afterLateReply);
            assertEquals("gateway-b-value", afterLateReply.getContent(),
                    "A late Reply from the retired Session must not affect routing");
            assertEquals(1, dispatcherA.fetchRequests.get());
            assertEquals(2, dispatcherB.fetchRequests.get());
            assertEquals(1, transport.openAttempts(0));
            assertEquals(1, transport.openAttempts(1));
        } finally {
            transport.close();
            gatewayA.stop();
            gatewayB.stop();
        }
        assertEquals(0, runtimeA.activeSessions());
        assertEquals(0, runtimeB.activeSessions());
        assertEquals(runtimeA.requestAcceptedTotal(), runtimeA.requestCompletedTotal());
        assertEquals(runtimeB.requestAcceptedTotal(), runtimeB.requestCompletedTotal());
    }

    @Test
    void precloseWithoutFinalCloseIsForcedToAHealthyStandby() throws Exception {
        List<Integer> ports = freePorts(2);
        DispatcherHarness dispatcherA = dispatcher("gateway-a-value");
        DispatcherHarness dispatcherB = dispatcher("gateway-b-value");
        ControlPlaneGatewayProperties propertiesA = properties(
                "gateway-preclose-a", ports.get(0));
        ControlPlaneGatewayProperties propertiesB = properties(
                "gateway-preclose-b", ports.get(1));
        ControlPlaneGatewayRuntime runtimeA = new ControlPlaneGatewayRuntime(propertiesA);
        ControlPlaneGatewayRuntime runtimeB = new ControlPlaneGatewayRuntime(propertiesB);
        CapturingEndpoint endpointA = new CapturingEndpoint(
                propertiesA, runtimeA, dispatcherA.dispatcher);
        GatewayHarness gatewayA = gateway(propertiesA, runtimeA, endpointA);
        GatewayHarness gatewayB = gateway(
                propertiesB,
                runtimeB,
                new ControlPlaneGatewayEndpoint(
                        propertiesB, runtimeB, dispatcherB.dispatcher));
        CountingSocketDTransport transport = new CountingSocketDTransport(
                new ClientIdentity("preclose-client", "preclose-client@1"),
                new ControlPlaneOptions(
                        "default",
                        "config-default",
                        "cluster-rpc-health-test",
                        1L,
                        "tcp-default",
                        500L,
                        500L,
                        3_000L,
                        250L));
        AtomicInteger reconnects = new AtomicInteger();
        transport.setOnReconnect(reconnects::incrementAndGet);
        try {
            gatewayA.start();
            gatewayB.start();
            transport.connect(
                    List.of(
                            "127.0.0.1:" + ports.get(0),
                            "127.0.0.1:" + ports.get(1)),
                    "public",
                    "DEFAULT_GROUP",
                    "");
            assertEquals(1, runtimeA.activeSessions());
            assertEquals(0, runtimeB.activeSessions());
            assertEquals(1, transport.openAttempts(0));
            assertEquals(0, transport.openAttempts(1));

            endpointA.precloseWithoutFinalClose();
            assertTrue(endpointA.session().isValid(),
                    "preclose must leave the physical Session valid");
            assertEquals(0, runtimeB.activeSessions(),
                    "A standby Session must not be opened eagerly");
            awaitTrue(() -> transport.session(0) != null
                            && transport.session(0).isValid()
                            && transport.session(0).isClosing(),
                    Duration.ofSeconds(2));

            awaitTrue(() -> runtimeA.activeSessions() == 0
                            && runtimeB.activeSessions() == 1
                            && transport.openAttempts(1) == 1,
                    Duration.ofSeconds(8));
            assertEquals(1, reconnects.get());
            assertEquals(1, transport.openAttempts(0));
            assertEquals(1, transport.openAttempts(1));

            ConfigSnapshot snapshot = transport.fetch("preclose.value", 0L);
            assertNotNull(snapshot);
            assertEquals("gateway-b-value", snapshot.getContent());
            assertEquals(0, dispatcherA.fetchRequests.get());
            assertEquals(1, dispatcherB.fetchRequests.get());
        } finally {
            transport.close();
            gatewayA.stop();
            gatewayB.stop();
        }
        assertEquals(0, runtimeA.activeSessions());
        assertEquals(0, runtimeB.activeSessions());
    }

    @Test
    void oneWayHalfOpenTcpConnectionFailsOverWithinTheRpcDeadline()
            throws Exception {
        List<Integer> ports = freePorts(2);
        DispatcherHarness dispatcherA = dispatcher("gateway-a-value");
        DispatcherHarness dispatcherB = dispatcher("gateway-b-value");
        ControlPlaneGatewayProperties propertiesA = properties(
                "gateway-half-open-a", ports.get(0));
        ControlPlaneGatewayProperties propertiesB = properties(
                "gateway-half-open-b", ports.get(1));
        ControlPlaneGatewayRuntime runtimeA = new ControlPlaneGatewayRuntime(propertiesA);
        ControlPlaneGatewayRuntime runtimeB = new ControlPlaneGatewayRuntime(propertiesB);
        GatewayHarness gatewayA = gateway(
                propertiesA,
                runtimeA,
                new ControlPlaneGatewayEndpoint(
                        propertiesA, runtimeA, dispatcherA.dispatcher));
        GatewayHarness gatewayB = gateway(
                propertiesB,
                runtimeB,
                new ControlPlaneGatewayEndpoint(
                        propertiesB, runtimeB, dispatcherB.dispatcher));
        CountingSocketDTransport transport = new CountingSocketDTransport(
                new ClientIdentity("half-open-client", "half-open-client@1"),
                new ControlPlaneOptions(
                        "default",
                        "config-default",
                        "cluster-rpc-health-test",
                        1L,
                        "tcp-default",
                        500L,
                        500L,
                        3_000L,
                        500L));
        TcpOneWayBlackhole proxy = null;
        try {
            gatewayA.start();
            gatewayB.start();
            proxy = new TcpOneWayBlackhole(ports.get(0));
            proxy.start();
            transport.connect(
                    List.of(
                            "127.0.0.1:" + proxy.port(),
                            "127.0.0.1:" + ports.get(1)),
                    "public",
                    "DEFAULT_GROUP",
                    "");
            assertTrue(proxy.connected.await(2, TimeUnit.SECONDS));
            assertEquals(1, runtimeA.activeSessions());
            assertEquals(0, runtimeB.activeSessions());
            proxy.dropServerToClient();

            ConfigSnapshot snapshot = transport.fetch("half.open", 0L);
            assertNotNull(snapshot);
            assertEquals("gateway-b-value", snapshot.getContent());
            assertTrue(proxy.droppedBytes() > 0L,
                    "The proxy must blackhole bytes while both TCP sides remain established");
            assertEquals(1, dispatcherA.fetchRequests.get(),
                    "The one-way broken Gateway must receive exactly one attempt");
            assertEquals(1, dispatcherB.fetchRequests.get());
            assertEquals(1, transport.openAttempts(0));
            assertEquals(1, transport.openAttempts(1));
            awaitTrue(() -> runtimeA.activeSessions() == 0
                            && runtimeB.activeSessions() == 1,
                    Duration.ofSeconds(5));
            assertEquals(runtimeA.requestAcceptedTotal(),
                    runtimeA.requestCompletedTotal());
        } finally {
            transport.close();
            if (proxy != null) {
                proxy.close();
            }
            gatewayA.stop();
            gatewayB.stop();
        }
        assertEquals(0, runtimeA.activeSessions());
        assertEquals(0, runtimeB.activeSessions());
    }

    private DispatcherHarness dispatcher(String value) {
        return dispatcher(value, 0L);
    }

    private DispatcherHarness dispatcher(String value, long delayMs) {
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        AtomicInteger fetchRequests = new AtomicInteger();
        CountDownLatch fetchCompleted = new CountDownLatch(1);
        dispatcher.register(new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return ControlPlaneProtocol.CONFIG_FETCH;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context,
                    cloud.xuantong.protocol.v2.Envelope request) {
                try {
                    ConfigFetchRequest fetch = ConfigFetchRequest.parseFrom(
                            request.getPayload());
                    fetchRequests.incrementAndGet();
                    ConfigFetchResponse response = ConfigFetchResponse.newBuilder()
                            .setState(ConfigValueState.CONFIG_VALUE_STATE_ACTIVE)
                            .setConfig(ConfigCoordinate.newBuilder()
                                    .setNamespaceId(context.namespaceId())
                                    .setGroupName(fetch.getGroupName())
                                    .setDataId(fetch.getDataId()))
                            .setDecisionRevision(1L)
                            .setContent(ConfigContentValue.newBuilder()
                                    .setContentRevision(1L)
                                    .setContentHash("test-hash")
                                    .setContentType("text")
                                    .setSchemaVersion(1)
                                    .setPayload(ByteString.copyFromUtf8(value)))
                            .build();
                    ControlPlaneReply reply = ControlPlaneReply.ok(
                            ControlPlaneProtocol.CONFIG_FETCH_RESPONSE_TYPE,
                            response.toByteString());
                    CompletionStage<ControlPlaneReply> result;
                    if (delayMs <= 0L) {
                        result = CompletableFuture.completedFuture(reply);
                    } else {
                        result = CompletableFuture.supplyAsync(
                                () -> reply,
                                CompletableFuture.delayedExecutor(
                                        delayMs, TimeUnit.MILLISECONDS));
                    }
                    return result.whenComplete(
                            (ignored, error) -> fetchCompleted.countDown());
                } catch (Exception e) {
                    fetchCompleted.countDown();
                    return CompletableFuture.failedFuture(e);
                }
            }
        });
        dispatcher.register(staticHandler(
                ControlPlaneProtocol.CONFIG_SNAPSHOT,
                ControlPlaneProtocol.CONFIG_SNAPSHOT_RESPONSE_TYPE,
                ConfigSnapshotResponse.getDefaultInstance().toByteString()));
        dispatcher.register(new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return ControlPlaneProtocol.CONFIG_WATCH_BATCH;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context,
                    cloud.xuantong.protocol.v2.Envelope request) {
                try {
                    ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.parseFrom(
                            request.getPayload());
                    return CompletableFuture.completedFuture(ControlPlaneReply.ok(
                            ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                            ConfigWatchBatchResponse.newBuilder()
                                    .setRequestedAfterRevision(
                                            watch.getAfterEventRevision())
                                    .setCoveredThroughRevision(
                                            watch.getAfterEventRevision())
                                    .build()
                                    .toByteString()));
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
        });
        return new DispatcherHarness(dispatcher, fetchRequests, fetchCompleted);
    }

    private ControlPlaneRequestHandler staticHandler(
            String event, String payloadType, ByteString payload) {
        return new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return event;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context,
                    cloud.xuantong.protocol.v2.Envelope request) {
                return CompletableFuture.completedFuture(
                        ControlPlaneReply.ok(payloadType, payload));
            }
        };
    }

    private ControlPlaneGatewayProperties properties(String gatewayId, int port) {
        return new ControlPlaneGatewayProperties(
                "127.0.0.1",
                port,
                "cluster-rpc-health-test",
                gatewayId,
                1L,
                10_000L,
                32,
                2,
                128,
                500L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
    }

    private GatewayHarness gateway(
            ControlPlaneGatewayProperties properties,
            ControlPlaneGatewayRuntime runtime,
            ControlPlaneGatewayEndpoint endpoint) {
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH, endpoint);
        return new GatewayHarness(runtime, new ControlPlaneGatewayServer(
                properties, runtime, router, null));
    }

    private void awaitTrue(Check condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(20L);
        }
        assertTrue(condition.evaluate(), "Condition was not satisfied before timeout");
    }

    private List<Integer> freePorts(int count) throws Exception {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                sockets.add(new ServerSocket(0));
            }
            return sockets.stream().map(ServerSocket::getLocalPort).toList();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: "
                            + e.getMessage());
            return List.of();
        } finally {
            for (ServerSocket socket : sockets) {
                socket.close();
            }
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }

    private record DispatcherHarness(
            ControlPlaneRequestDispatcher dispatcher,
            AtomicInteger fetchRequests,
            CountDownLatch fetchCompleted) {
    }

    private static final class GatewayHarness {
        private final ControlPlaneGatewayRuntime runtime;
        private final ControlPlaneGatewayServer server;
        private boolean started;

        private GatewayHarness(
                ControlPlaneGatewayRuntime runtime,
                ControlPlaneGatewayServer server) {
            this.runtime = runtime;
            this.server = server;
        }

        private void start() throws Exception {
            server.start();
            started = true;
        }

        private void stop() {
            if (started) {
                started = false;
                server.stop();
            }
        }
    }

    private static final class TcpOneWayBlackhole implements AutoCloseable {
        private final int upstreamPort;
        private final ServerSocket listener;
        private final ExecutorService workers;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean dropServerToClient = new AtomicBoolean();
        private final AtomicLong droppedBytes = new AtomicLong();
        private final CountDownLatch connected = new CountDownLatch(1);
        private final AtomicReference<Socket> clientSocket = new AtomicReference<>();
        private final AtomicReference<Socket> upstreamSocket = new AtomicReference<>();

        private TcpOneWayBlackhole(int upstreamPort) throws IOException {
            this.upstreamPort = upstreamPort;
            this.listener = new ServerSocket(0);
            this.workers = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "xuantong-test-tcp-blackhole");
                thread.setDaemon(true);
                return thread;
            });
        }

        private void start() {
            workers.execute(() -> {
                try {
                    Socket client = listener.accept();
                    Socket upstream = new Socket("127.0.0.1", upstreamPort);
                    clientSocket.set(client);
                    upstreamSocket.set(upstream);
                    connected.countDown();
                    workers.execute(() -> copy(
                            client, upstream, false));
                    workers.execute(() -> copy(
                            upstream, client, true));
                } catch (IOException e) {
                    if (!closed.get()) {
                        throw new IllegalStateException("TCP blackhole proxy failed", e);
                    }
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        private void dropServerToClient() {
            dropServerToClient.set(true);
        }

        private long droppedBytes() {
            return droppedBytes.get();
        }

        private void copy(Socket source, Socket destination, boolean serverToClient) {
            byte[] buffer = new byte[8_192];
            try {
                var input = source.getInputStream();
                var output = destination.getOutputStream();
                while (!closed.get()) {
                    int length = input.read(buffer);
                    if (length < 0) {
                        break;
                    }
                    if (serverToClient && dropServerToClient.get()) {
                        droppedBytes.addAndGet(length);
                        continue;
                    }
                    output.write(buffer, 0, length);
                    output.flush();
                }
            } catch (IOException ignored) {
                // Closing either side is the expected termination signal for both pumps.
            } finally {
                closeSockets();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                listener.close();
            } catch (IOException ignored) {
                // Best-effort test cleanup.
            }
            closeSockets();
            workers.shutdownNow();
        }

        private void closeSockets() {
            close(clientSocket.getAndSet(null));
            close(upstreamSocket.getAndSet(null));
        }

        private void close(Socket socket) {
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort test cleanup.
            }
        }
    }

    private static final class DroppingFetchEndpoint
            extends ControlPlaneGatewayEndpoint {
        private final java.util.concurrent.CountDownLatch replyDropped =
                new java.util.concurrent.CountDownLatch(1);
        private final AtomicInteger fetchRequests = new AtomicInteger();

        private DroppingFetchEndpoint(
                ControlPlaneGatewayProperties properties,
                ControlPlaneGatewayRuntime runtime,
                ControlPlaneRequestDispatcher dispatcher) {
            super(properties, runtime, dispatcher);
        }

        @Override
        public void onMessage(Session session, Message message) throws IOException {
            if (ControlPlaneProtocol.CONFIG_FETCH.equals(message.event())) {
                fetchRequests.incrementAndGet();
                super.onMessage(dropReplySession(session), message);
                return;
            }
            super.onMessage(session, message);
        }

        private Session dropReplySession(Session delegate) {
            return (Session) Proxy.newProxyInstance(
                    Session.class.getClassLoader(),
                    new Class<?>[]{Session.class},
                    (proxy, method, args) -> {
                        if ("reply".equals(method.getName())
                                || "replyEnd".equals(method.getName())) {
                            replyDropped.countDown();
                            return null;
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    private static final class CapturingEndpoint
            extends ControlPlaneGatewayEndpoint {
        private final AtomicReference<Session> activeSession = new AtomicReference<>();

        private CapturingEndpoint(
                ControlPlaneGatewayProperties properties,
                ControlPlaneGatewayRuntime runtime,
                ControlPlaneRequestDispatcher dispatcher) {
            super(properties, runtime, dispatcher);
        }

        @Override
        public void onOpen(Session session) throws IOException {
            super.onOpen(session);
            activeSession.set(session);
        }

        private Session session() {
            Session session = activeSession.get();
            if (session == null) {
                throw new IllegalStateException("Gateway Session has not opened");
            }
            return session;
        }

        private void precloseWithoutFinalClose() throws IOException {
            session().preclose();
        }
    }

    private static final class CountingSocketDTransport extends SocketDTransport {
        private final AtomicIntegerArray openAttempts = new AtomicIntegerArray(2);
        private final AtomicReferenceArray<ClientSession> sessions =
                new AtomicReferenceArray<>(2);

        private CountingSocketDTransport(
                ClientIdentity identity, ControlPlaneOptions options) {
            super(identity, options);
        }

        @Override
        protected ClientSession openSession(
                int gatewayIndex,
                String url,
                EventListener listener,
                long connectTimeoutMs) throws Exception {
            openAttempts.incrementAndGet(gatewayIndex);
            ClientSession session = super.openSession(
                    gatewayIndex, url, listener, connectTimeoutMs);
            sessions.set(gatewayIndex, session);
            return session;
        }

        private int openAttempts(int gatewayIndex) {
            return openAttempts.get(gatewayIndex);
        }

        private ClientSession session(int gatewayIndex) {
            return sessions.get(gatewayIndex);
        }
    }
}
