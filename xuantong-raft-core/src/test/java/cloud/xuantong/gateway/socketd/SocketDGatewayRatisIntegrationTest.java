package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisNodeOptions;
import cloud.xuantong.raft.ratis.RatisPeerDefinition;
import cloud.xuantong.raft.ratis.RatisStateMachineAdapter;
import cloud.xuantong.raft.ratis.RatisStateNode;
import cloud.xuantong.raft.ratis.RatisStateRouter;
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
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketDGatewayRatisIntegrationTest {
    private static final String COMMAND_EVENT = "test/ratis-command";
    private static final String QUERY_EVENT = "test/ratis-query";
    private static final String RESULT_TYPE = "test.RatisCounterValue";
    private static final StateGroupId GROUP_ID = StateGroupId.config("gateway-ratis");

    @TempDir
    Path tempDirectory;

    @Test
    void nativeSocketDRequestCommitsThroughGatewayAndRatisLeader() throws Exception {
        RatisPeerDefinition peer = new RatisPeerDefinition(
                "state-1", "127.0.0.1", freePort());
        RatisGroupDefinition group = new RatisGroupDefinition(GROUP_ID, List.of(peer));
        RatisNodeOptions nodeOptions = new RatisNodeOptions(
                peer.nodeId(),
                group,
                tempDirectory.resolve("state-1"),
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofSeconds(2),
                10_000,
                false);

        try (RatisStateNode stateNode = new RatisStateNode(
                nodeOptions,
                ignored -> new RatisStateMachineAdapter(
                        new CounterStateMachine(GROUP_ID)))) {
            stateNode.start();
            waitForLeader(stateNode, Duration.ofSeconds(5));

            try (RatisStateRouter stateRouter = new RatisStateRouter(
                    List.of(group), Duration.ofSeconds(2), 10)) {
                runGatewaySlice(stateRouter);
            }
        }
    }

    private void runGatewaySlice(RatisStateRouter stateRouter) throws Exception {
        int gatewayPort = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1",
                gatewayPort,
                "cluster-test",
                "gateway-test",
                1L,
                5_000L,
                32,
                2,
                32,
                2_000L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        ControlPlaneStateExecutor stateExecutor = new ControlPlaneStateExecutor(stateRouter);
        dispatcher.register(commandHandler(stateExecutor));
        dispatcher.register(queryHandler(stateExecutor));

        PathListenerPlus socketRouter = new PathListenerPlus(true);
        socketRouter.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime, dispatcher));
        ControlPlaneGatewayServer gateway = new ControlPlaneGatewayServer(
                properties, runtime, socketRouter, null);
        ClientSession client = null;
        try {
            gateway.start();
            client = SocketD.createClient("sd:tcp://127.0.0.1:" + gatewayPort
                            + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> config.connectTimeout(3_000L)
                            .requestTimeout(5_000L).autoReconnect(false))
                    .openOrThow();

            assertEquals(ResponseCode.OK,
                    request(client, ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope())
                            .getResponseStatus().getCode());

            String operationId = UUID.randomUUID().toString();
            Envelope commandResponse = request(client, COMMAND_EVENT,
                    stateEnvelope(operationId, "test.CounterIncrement"));
            assertEquals(ResponseCode.OK,
                    commandResponse.getResponseStatus().getCode());
            assertEquals(operationId, commandResponse.getOperationId());
            assertEquals(1L, decodeLong(commandResponse.getPayload().toByteArray()));

            Envelope queryResponse = request(client, QUERY_EVENT,
                    stateEnvelope("", "test.CounterQuery"));
            assertEquals(ResponseCode.OK,
                    queryResponse.getResponseStatus().getCode());
            assertEquals(1L, decodeLong(queryResponse.getPayload().toByteArray()));
            assertEquals(0, runtime.inFlightRequests());
        } finally {
            if (client != null && client.isValid()) {
                client.close();
            }
            gateway.stop();
        }
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
                        GROUP_ID,
                        request.getOperationId(),
                        "counter.increment",
                        1,
                        request.getPayload().toByteArray());
                return stateExecutor.submit(command, context)
                        .thenApply(result -> ControlPlaneReply.ok(
                                RESULT_TYPE, ByteString.copyFrom(result.payload())));
            }
        };
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
                        GROUP_ID,
                        "counter.get",
                        1,
                        request.getPayload().toByteArray(),
                        ReadOptions.linearizable());
                return stateExecutor.query(query, context)
                        .thenApply(result -> ControlPlaneReply.ok(
                                RESULT_TYPE, ByteString.copyFrom(result.payload())));
            }
        };
    }

    private Envelope helloEnvelope() {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("integration@node-1")
                .setApplicationName("integration")
                .setGroupName("DEFAULT_GROUP")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .build();
        return baseEnvelope()
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope stateEnvelope(String operationId, String payloadType) {
        return baseEnvelope()
                .setOperationId(operationId)
                .setRevisionType(RevisionType.CONFIG_EVENT)
                .setGroupId(GROUP_ID.value())
                .setPayloadType(payloadType)
                .setPayload(ByteString.copyFromUtf8("payload"))
                .build();
    }

    private Envelope.Builder baseEnvelope() {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-test")
                .setTransportGeneration(1L)
                .setRequestId(UUID.randomUUID().toString())
                .setTenant("default")
                .setNamespaceId("public")
                .setRemainingBudgetMs(4_000L);
    }

    private Envelope request(
            ClientSession client, String event, Envelope envelope) throws Exception {
        Reply reply = client.sendAndRequest(
                        event, Entity.of(envelope.toByteArray()), 5_000L)
                .await();
        assertTrue(reply.isEnd());
        return Envelope.parseFrom(reply.dataAsBytes());
    }

    private void waitForLeader(RatisStateNode node, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (node.isLeader()) {
                Thread.sleep(200);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Single-node State Group did not elect a leader");
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
            value++;
            appliedIndex = context.logIndex();
            return new ApplyResult(
                    groupId,
                    command.operationId(),
                    ApplyStatus.APPLIED,
                    appliedIndex,
                    RESULT_TYPE,
                    encodeLong(value),
                    List.of(StateRevision.configEvent(groupId, value)));
        }

        @Override
        public synchronized QueryResult query(StateQuery query) {
            return new QueryResult(
                    groupId,
                    appliedIndex,
                    false,
                    RESULT_TYPE,
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
