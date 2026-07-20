package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneGatewayStateRoutingTest {
    private static final String QUERY_EVENT = "test/state-query";
    private static final String COMMAND_EVENT = "test/state-command";
    private static final String RESULT_TYPE = "test.StateValue";
    private static final StateGroupId CONFIG_GROUP =
            StateGroupId.config("config-default");

    @Test
    void asynchronousStateQueryKeepsAdmissionUntilReplyEnd() throws Exception {
        try (RoutingFixture fixture = startGateway()) {
            fixture.hello();
            CompletableFuture<QueryResult> stateResult = new CompletableFuture<>();
            fixture.stateClient().queryResult = stateResult;
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Envelope> pendingReply = caller.submit(() -> fixture.request(
                        QUERY_EVENT, stateEnvelope(2_000, false)));
                awaitTrue(() -> fixture.runtime().inFlightRequests() == 1, 2_000);
                assertFalse(pendingReply.isDone());

                stateResult.complete(new QueryResult(
                        CONFIG_GROUP,
                        9,
                        false,
                        RESULT_TYPE,
                        encodeLong(42),
                        List.of(StateRevision.configEvent(CONFIG_GROUP, 7))));

                Envelope response = pendingReply.get(3, TimeUnit.SECONDS);
                assertEquals(ResponseCode.OK, response.getResponseStatus().getCode());
                assertEquals(42, decodeLong(response.getPayload().toByteArray()));
                assertEquals(CommitStatus.NOT_APPLICABLE,
                        response.getResponseStatus().getCommitStatus());
                awaitTrue(() -> fixture.runtime().inFlightRequests() == 0, 2_000);
            } finally {
                caller.shutdownNow();
            }
        }
    }

    @Test
    void writeDeadlineReturnsUnknownCommitWithoutCancellingStateOperation() throws Exception {
        try (RoutingFixture fixture = startGateway()) {
            fixture.hello();
            CompletableFuture<ApplyResult> stateResult = new CompletableFuture<>();
            fixture.stateClient().applyResult = stateResult;

            Envelope response = fixture.request(
                    COMMAND_EVENT, stateEnvelope(50, true));

            assertEquals(ResponseCode.DEADLINE_EXCEEDED,
                    response.getResponseStatus().getCode());
            assertEquals(CommitStatus.UNKNOWN,
                    response.getResponseStatus().getCommitStatus());
            assertEquals("config-default",
                    response.getResponseStatus().getGroupId());
            assertTrue(response.getResponseStatus().getRetryable());
            assertFalse(stateResult.isCancelled(),
                    "Gateway deadline must not pretend to cancel the State write");
            awaitTrue(() -> fixture.runtime().inFlightRequests() == 0, 2_000);
        }
    }

    @Test
    void internalNotLeaderIsSanitizedToStateUnavailable() throws Exception {
        try (RoutingFixture fixture = startGateway()) {
            fixture.hello();
            fixture.stateClient().queryResult = CompletableFuture.failedFuture(
                    StateAccessException.retryable(
                            StateFailureCode.NOT_LEADER,
                            CONFIG_GROUP,
                            "internal leader is state-2:9876",
                            StateCommitStatus.NOT_APPLICABLE,
                            null));

            Envelope response = fixture.request(
                    QUERY_EVENT, stateEnvelope(2_000, false));

            assertEquals(ResponseCode.STATE_UNAVAILABLE,
                    response.getResponseStatus().getCode());
            assertEquals("config-default",
                    response.getResponseStatus().getGroupId());
            assertFalse(response.getResponseStatus().getMessage().contains("state-2"),
                    "State Node addresses must not leak to service clients");
        }
    }

    @Test
    void storageAdmissionFailureMapsSafeWriteRetrySemantics() throws Exception {
        try (RoutingFixture fixture = startGateway()) {
            fixture.hello();
            fixture.stateClient().applyResult = CompletableFuture.failedFuture(
                    StateAccessException.retryable(
                            StateFailureCode.STORAGE_EXHAUSTED,
                            CONFIG_GROUP,
                            "State storage is below the configured write-admission watermark",
                            StateCommitStatus.NOT_COMMITTED,
                            null));

            Envelope response = fixture.request(
                    COMMAND_EVENT, stateEnvelope(2_000, true));

            assertEquals(ResponseCode.STORAGE_EXHAUSTED,
                    response.getResponseStatus().getCode());
            assertEquals(CommitStatus.NOT_COMMITTED,
                    response.getResponseStatus().getCommitStatus());
            assertTrue(response.getResponseStatus().getRetryable());
        }
    }

    @Test
    void expiredBudgetDoesNotStartAStateWrite() {
        FakeStateClient stateClient = new FakeStateClient();
        stateClient.applyResult = new CompletableFuture<>();
        ControlPlaneStateExecutor executor = new ControlPlaneStateExecutor(stateClient);
        ControlPlaneRequestContext expired = new ControlPlaneRequestContext(
                "session-1",
                "demo@node-1",
                "demo",
                "principal-1",
                "gateway-1",
                1L,
                "default",
                "public",
                "DEFAULT_GROUP",
                "127.0.0.1",
                System.nanoTime() - 1L);
        StateCommand command = new StateCommand(
                CONFIG_GROUP, "op-expired-1", "test.command", 1, new byte[0]);

        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> executor.submit(command, expired).toCompletableFuture().join());

        StateAccessException stateFailure = (StateAccessException) failure.getCause();
        assertEquals(StateFailureCode.DEADLINE_EXCEEDED, stateFailure.code());
        assertEquals(StateCommitStatus.UNKNOWN, stateFailure.commitStatus());
        assertEquals(0, stateClient.submitCalls.get());
    }

    private RoutingFixture startGateway() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1", port, "cluster-test", "gateway-test", 1L, 4_000L,
                16, 2, 16, 2_000L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        FakeStateClient stateClient = new FakeStateClient();
        ControlPlaneStateExecutor stateExecutor = new ControlPlaneStateExecutor(stateClient);
        dispatcher.register(queryHandler(stateExecutor));
        dispatcher.register(commandHandler(stateExecutor));

        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime, dispatcher));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, null);
        server.start();
        ClientSession client = SocketD.createClient("sd:tcp://127.0.0.1:" + port
                        + ControlPlaneProtocol.CONTROL_PATH)
                .config(config -> config.connectTimeout(3_000L)
                        .requestTimeout(3_000L).autoReconnect(false))
                .openOrThow();
        return new RoutingFixture(server, runtime, stateClient, client);
    }

    private ControlPlaneRequestHandler queryHandler(
            ControlPlaneStateExecutor stateExecutor) {
        return new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return QUERY_EVENT;
            }

            @Override
            public CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context, Envelope request) {
                StateQuery query = new StateQuery(
                        CONFIG_GROUP,
                        "test.query",
                        1,
                        request.getPayload().toByteArray(),
                        ReadOptions.linearizable());
                return stateExecutor.query(query, context)
                        .thenApply(result -> ControlPlaneReply.ok(
                                RESULT_TYPE, ByteString.copyFrom(result.payload())));
            }
        };
    }

    private ControlPlaneRequestHandler commandHandler(
            ControlPlaneStateExecutor stateExecutor) {
        return new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return COMMAND_EVENT;
            }

            @Override
            public CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context, Envelope request) {
                StateCommand command = new StateCommand(
                        CONFIG_GROUP,
                        request.getOperationId(),
                        "test.command",
                        1,
                        request.getPayload().toByteArray());
                return stateExecutor.submit(command, context)
                        .thenApply(result -> ControlPlaneReply.ok(
                                RESULT_TYPE, ByteString.copyFrom(result.payload())));
            }
        };
    }

    private Envelope stateEnvelope(long budgetMs, boolean write) {
        Envelope.Builder envelope = baseEnvelope(budgetMs)
                .setRevisionType(RevisionType.CONFIG_EVENT)
                .setGroupId(CONFIG_GROUP.value())
                .setPayloadType(write ? "test.StateCommand" : "test.StateQuery")
                .setPayload(ByteString.copyFromUtf8("payload"));
        if (write) {
            envelope.setOperationId(UUID.randomUUID().toString());
        }
        return envelope.build();
    }

    private Envelope helloEnvelope() {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("demo@node-1")
                .setApplicationName("demo")
                .setGroupName("DEFAULT_GROUP")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .build();
        return baseEnvelope(2_000)
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope.Builder baseEnvelope(long budgetMs) {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-test")
                .setTransportGeneration(1L)
                .setRequestId(UUID.randomUUID().toString())
                .setTenant("default")
                .setNamespaceId("public")
                .setRemainingBudgetMs(budgetMs);
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

    private void awaitTrue(Check check, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(check.evaluate());
    }

    private static byte[] encodeLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long decodeLong(byte[] value) {
        return ByteBuffer.wrap(value).getLong();
    }

    private final class RoutingFixture implements AutoCloseable {
        private final ControlPlaneGatewayServer server;
        private final ControlPlaneGatewayRuntime runtime;
        private final FakeStateClient stateClient;
        private final ClientSession client;

        private RoutingFixture(
                ControlPlaneGatewayServer server,
                ControlPlaneGatewayRuntime runtime,
                FakeStateClient stateClient,
                ClientSession client) {
            this.server = server;
            this.runtime = runtime;
            this.stateClient = stateClient;
            this.client = client;
        }

        private void hello() throws Exception {
            assertEquals(ResponseCode.OK,
                    request(ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope())
                            .getResponseStatus().getCode());
        }

        private Envelope request(String event, Envelope envelope) throws Exception {
            Reply reply = client.sendAndRequest(
                            event, Entity.of(envelope.toByteArray()), 3_000L)
                    .await();
            assertTrue(reply.isEnd());
            Envelope response = Envelope.parseFrom(reply.dataAsBytes());
            assertEquals(envelope.getRequestId(), response.getRequestId());
            return response;
        }

        private ControlPlaneGatewayRuntime runtime() {
            return runtime;
        }

        private FakeStateClient stateClient() {
            return stateClient;
        }

        @Override
        public void close() throws Exception {
            if (client.isValid()) {
                client.close();
            }
            server.stop();
        }
    }

    private static final class FakeStateClient implements StateClient {
        private CompletableFuture<ApplyResult> applyResult;
        private CompletableFuture<QueryResult> queryResult;
        private final AtomicInteger submitCalls = new AtomicInteger();

        @Override
        public CompletionStage<ApplyResult> submit(StateCommand command) {
            submitCalls.incrementAndGet();
            return applyResult;
        }

        @Override
        public CompletionStage<QueryResult> query(StateQuery query) {
            return queryResult;
        }

        @Override
        public CompletionStage<WatchBatch> watch(WatchRequest request) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Watch is not used by this test"));
        }

        @Override
        public void close() {
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }
}
