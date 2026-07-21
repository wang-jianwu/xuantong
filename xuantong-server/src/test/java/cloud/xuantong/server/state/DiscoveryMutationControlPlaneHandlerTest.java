package cloud.xuantong.server.state;

import cloud.xuantong.discovery.management.model.ServiceDefinition;
import cloud.xuantong.discovery.management.service.ServiceDefinitionService;
import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.DiscoveryMutationResponse;
import cloud.xuantong.protocol.v2.DiscoveryRegisterRequest;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.registry.state.RegistryStateMachine;
import cloud.xuantong.state.api.ApplyContext;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.QueryResult;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateQuery;
import cloud.xuantong.state.api.WatchBatch;
import cloud.xuantong.state.api.WatchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscoveryMutationControlPlaneHandlerTest {
    private StateGroupId groupId;
    private ControlPlaneRequestContext context;
    private DirectStateClient stateClient;

    @BeforeEach
    void setUp() {
        groupId = StateGroupId.registry("registry-default");
        stateClient = new DirectStateClient(new RegistryStateMachine(groupId));
        context = new ControlPlaneRequestContext(
                "session-1",
                "demo-app@node-1",
                "demo-app",
                "client-token:1",
                "gateway-1",
                1L,
                "tenant-a",
                "public",
                "DEFAULT_GROUP",
                "127.0.0.1",
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void firstRegisterCreatesServiceAndUpdatesManagementProjection() throws Exception {
        RecordingProjection projection = new RecordingProjection(false);
        DiscoveryMutationControlPlaneHandler handler = handler(projection);

        ControlPlaneReply reply = handler.handle(context, registerEnvelope())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        DiscoveryMutationResponse response = DiscoveryMutationResponse.parseFrom(
                reply.payload());

        assertEquals(ResponseCode.OK, reply.status().getCode());
        assertEquals(1, response.getInstancesCount());
        assertEquals(1L, response.getInstances(0).getServiceGeneration());
        assertEquals("public:DEFAULT_GROUP:demo-app", projection.service);
        assertEquals(1L, projection.generation);
    }

    @Test
    void projectionFailureDoesNotTurnCommittedRegistrationIntoClientFailure()
            throws Exception {
        DiscoveryMutationControlPlaneHandler handler = handler(
                new RecordingProjection(true));

        ControlPlaneReply reply = handler.handle(context, registerEnvelope())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ResponseCode.OK, reply.status().getCode());
        assertEquals(1, DiscoveryMutationResponse.parseFrom(
                reply.payload()).getInstancesCount());
    }

    private DiscoveryMutationControlPlaneHandler handler(
            ServiceDefinitionService projection) {
        return new DiscoveryMutationControlPlaneHandler(
                new ControlPlaneStateExecutor(stateClient),
                DiscoveryMutationControlPlaneHandler.Operation.REGISTER,
                projection);
    }

    private Envelope registerEnvelope() {
        DiscoveryRegisterRequest register = DiscoveryRegisterRequest.newBuilder()
                .setGroupName("DEFAULT_GROUP")
                .setServiceName("demo-app")
                .setInstanceId("demo-app@node-1")
                .setIp("127.0.0.1")
                .setPort(8080)
                .setWeight(1D)
                .setEnabled(true)
                .setTtlMs(30_000L)
                .build();
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("xuantong-local")
                .setTransportGeneration(1L)
                .setRequestId("request-1")
                .setOperationId("register-1")
                .setTenant("tenant-a")
                .setNamespaceId("public")
                .setGroupId(groupId.value())
                .setRevisionType(RevisionType.REGISTRY)
                .setRemainingBudgetMs(5_000L)
                .setPayloadType(ControlPlaneProtocol.DISCOVERY_REGISTER_REQUEST_TYPE)
                .setPayload(register.toByteString())
                .build();
    }

    private static final class RecordingProjection extends ServiceDefinitionService {
        private final boolean fail;
        private String service;
        private long generation;

        private RecordingProjection(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ServiceDefinition ensureActiveProjection(
                String namespaceId,
                String groupName,
                String serviceName,
                long generation,
                String operator) {
            if (fail) {
                throw new IllegalStateException("database unavailable");
            }
            this.service = namespaceId + ":" + groupName + ":" + serviceName;
            this.generation = generation;
            return new ServiceDefinition();
        }
    }

    private static final class DirectStateClient implements StateClient {
        private final RegistryStateMachine stateMachine;
        private final AtomicLong logIndex = new AtomicLong();

        private DirectStateClient(RegistryStateMachine stateMachine) {
            this.stateMachine = stateMachine;
        }

        @Override
        public CompletionStage<ApplyResult> submit(StateCommand command) {
            return CompletableFuture.completedFuture(stateMachine.apply(
                    command,
                    new ApplyContext(
                            command.groupId(), 1L, logIndex.incrementAndGet())));
        }

        @Override
        public CompletionStage<QueryResult> query(StateQuery query) {
            return CompletableFuture.completedFuture(stateMachine.query(query));
        }

        @Override
        public CompletionStage<WatchBatch> watch(WatchRequest request) {
            return CompletableFuture.completedFuture(stateMachine.watch(request));
        }

        @Override
        public void close() {
        }
    }
}
