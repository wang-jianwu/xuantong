package cloud.xuantong.server.state;

import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigDecisionState;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.config.state.ConfigStateMachine;
import cloud.xuantong.config.state.RolloutRuleDraft;
import cloud.xuantong.config.state.RolloutRuleStatus;
import cloud.xuantong.config.state.RolloutSelectorType;
import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.gateway.socketd.ControlPlaneRequestContext;
import cloud.xuantong.gateway.socketd.ControlPlaneStateExecutor;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.ConfigFetchRequest;
import cloud.xuantong.protocol.v2.ConfigFetchResponse;
import cloud.xuantong.protocol.v2.ConfigSnapshotRequest;
import cloud.xuantong.protocol.v2.ConfigSnapshotResponse;
import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigControlPlaneHandlerTest {
    private StateGroupId groupId;
    private ConfigKey key;
    private DirectStateClient stateClient;
    private ControlPlaneRequestContext context;

    @BeforeEach
    void setUp() {
        groupId = StateGroupId.config("config-default");
        key = new ConfigKey("public", "DEFAULT_GROUP", "demo.value");
        stateClient = new DirectStateClient(new ConfigStateMachine(groupId));
        context = new ControlPlaneRequestContext(
                "session-1",
                "demo@instance-1",
                "demo",
                "client-token:1",
                "gateway-1",
                1,
                "tenant-a",
                "public",
                "DEFAULT_GROUP",
                "10.0.0.8",
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void fetchUsesServerObservedIpForGraySelection() throws Exception {
        publishStable("stable-op", "stable");
        RolloutRuleDraft ipRule = new RolloutRuleDraft(
                "ip-gray",
                1,
                1,
                100,
                ConfigContentReference.newContent(),
                "rollout-ip",
                RolloutSelectorType.REMOTE_IP,
                "",
                List.of("10.0.0.8"),
                0,
                1,
                RolloutRuleStatus.ACTIVE);
        stateClient.submit(ConfigStateCodec.mutationCommand(
                groupId,
                "gray-op",
                new ConfigMutation(
                        new ConfigActor("tenant-a", "admin-a"),
                        key,
                        1,
                        ConfigContentDraft.inline("text", 1, bytes("candidate")),
                        ConfigContentReference.existing(1),
                        List.of(ipRule)))).toCompletableFuture().join();
        ConfigFetchControlPlaneHandler handler = new ConfigFetchControlPlaneHandler(
                new ControlPlaneStateExecutor(stateClient));
        ConfigFetchRequest fetch = ConfigFetchRequest.newBuilder()
                .setGroupName(key.group())
                .setDataId(key.dataId())
                .build();

        ControlPlaneReply reply = handler.handle(context, baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_DECISION)
                        .setPayloadType(ControlPlaneProtocol.CONFIG_FETCH_REQUEST_TYPE)
                        .setPayload(fetch.toByteString())
                        .build())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        ConfigFetchResponse response = ConfigFetchResponse.parseFrom(reply.payload());

        assertEquals(ResponseCode.OK, reply.status().getCode());
        assertEquals(cloud.xuantong.protocol.v2.ConfigValueState
                .CONFIG_VALUE_STATE_ACTIVE, response.getState());
        assertEquals("candidate", response.getContent().getPayload().toStringUtf8());
        assertEquals("ip-gray", response.getMatchedRuleId());
        assertEquals(2, response.getDecisionRevision());
    }

    @Test
    void fetchAndSnapshotExposeAuthoritativeTombstoneState() throws Exception {
        publishStable("publish-before-delete", "stable");
        stateClient.submit(ConfigStateCodec.mutationCommand(
                groupId,
                "tombstone",
                new ConfigMutation(
                        new ConfigActor("tenant-a", "admin-a"),
                        key,
                        1,
                        null,
                        ConfigDecisionState.TOMBSTONE,
                        null,
                        List.of()))).toCompletableFuture().join();

        ConfigFetchControlPlaneHandler fetchHandler = new ConfigFetchControlPlaneHandler(
                new ControlPlaneStateExecutor(stateClient));
        ConfigFetchRequest fetch = ConfigFetchRequest.newBuilder()
                .setGroupName(key.group())
                .setDataId(key.dataId())
                .build();
        ControlPlaneReply fetchReply = fetchHandler.handle(context, baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_DECISION)
                        .setPayloadType(ControlPlaneProtocol.CONFIG_FETCH_REQUEST_TYPE)
                        .setPayload(fetch.toByteString())
                        .build())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        ConfigFetchResponse fetchResponse = ConfigFetchResponse.parseFrom(fetchReply.payload());

        assertEquals(cloud.xuantong.protocol.v2.ConfigValueState
                .CONFIG_VALUE_STATE_TOMBSTONE, fetchResponse.getState());
        assertEquals(2L, fetchResponse.getDecisionRevision());
        assertFalse(fetchResponse.hasContent());

        ConfigSnapshotControlPlaneHandler snapshotHandler =
                new ConfigSnapshotControlPlaneHandler(
                        new ControlPlaneStateExecutor(stateClient));
        ConfigSnapshotRequest snapshot = ConfigSnapshotRequest.newBuilder()
                .addConfigs(ConfigCoordinate.newBuilder()
                        .setNamespaceId(key.namespace())
                        .setGroupName(key.group())
                        .setDataId(key.dataId()))
                .build();
        ControlPlaneReply snapshotReply = snapshotHandler.handle(context, baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_EVENT)
                        .setPayloadType(ControlPlaneProtocol.CONFIG_SNAPSHOT_REQUEST_TYPE)
                        .setPayload(snapshot.toByteString())
                        .build())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        ConfigSnapshotResponse snapshotResponse = ConfigSnapshotResponse.parseFrom(
                snapshotReply.payload());
        assertEquals(1, snapshotResponse.getDecisionsCount());
        assertEquals(cloud.xuantong.protocol.v2.ConfigValueState
                        .CONFIG_VALUE_STATE_TOMBSTONE,
                snapshotResponse.getDecisions(0).getState());
        assertEquals(0L, snapshotResponse.getDecisions(0).getStableContentRevision());
    }

    @Test
    void snapshotRejectsCoordinatesOutsideAuthorizedNamespace() throws Exception {
        ConfigSnapshotControlPlaneHandler handler = new ConfigSnapshotControlPlaneHandler(
                new ControlPlaneStateExecutor(stateClient));
        ConfigSnapshotRequest snapshot = ConfigSnapshotRequest.newBuilder()
                .addConfigs(ConfigCoordinate.newBuilder()
                        .setNamespaceId("another-tenant")
                        .setGroupName("DEFAULT_GROUP")
                        .setDataId("secret"))
                .build();

        ControlPlaneReply reply = handler.handle(context, baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_EVENT)
                        .setPayloadType(ControlPlaneProtocol.CONFIG_SNAPSHOT_REQUEST_TYPE)
                        .setPayload(snapshot.toByteString())
                        .build())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ResponseCode.INVALID_ARGUMENT, reply.status().getCode());
    }

    @Test
    void fetchRejectsGroupOutsideAuthenticatedSession() throws Exception {
        ConfigFetchControlPlaneHandler handler = new ConfigFetchControlPlaneHandler(
                new ControlPlaneStateExecutor(stateClient));
        ConfigFetchRequest fetch = ConfigFetchRequest.newBuilder()
                .setGroupName("OTHER_GROUP")
                .setDataId("secret")
                .build();

        ControlPlaneReply reply = handler.handle(context, baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_DECISION)
                        .setPayloadType(ControlPlaneProtocol.CONFIG_FETCH_REQUEST_TYPE)
                        .setPayload(fetch.toByteString())
                        .build())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(ResponseCode.INVALID_ARGUMENT, reply.status().getCode());
    }

    @Test
    void watchBatchCarriesFilteredEventsAndCoveredWatermark() throws Exception {
        publishStable("publish-1", "v1");
        publishStable("publish-2", "v2");
        ConfigWatchBatchControlPlaneHandler handler =
                new ConfigWatchBatchControlPlaneHandler(
                        new ControlPlaneStateExecutor(stateClient));
        ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                .setAfterEventRevision(0)
                .setGroupName("DEFAULT_GROUP")
                .setMaxBatchSize(10)
                .addConfigs(ConfigCoordinate.newBuilder()
                        .setNamespaceId(key.namespace())
                        .setGroupName(key.group())
                        .setDataId(key.dataId()))
                .build();

        ControlPlaneReply reply = handler.handle(context, baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_EVENT)
                        .setPayloadType(ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE)
                        .setPayload(watch.toByteString())
                        .build())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        ConfigWatchBatchResponse response =
                ConfigWatchBatchResponse.parseFrom(reply.payload());

        assertEquals(ResponseCode.OK, reply.status().getCode());
        assertEquals(2, response.getEventsCount());
        assertEquals(2, response.getCoveredThroughRevision());
        assertEquals(2, response.getEvents(1).getDecisionRevision());
    }

    private void publishStable(String operationId, String content) {
        long expected = stateClient.logIndex.get();
        ConfigMutation mutation = new ConfigMutation(
                new ConfigActor("tenant-a", "admin-a"),
                key,
                expected,
                ConfigContentDraft.inline("text", 1, bytes(content)),
                ConfigContentReference.newContent(),
                List.of());
        stateClient.submit(ConfigStateCodec.mutationCommand(
                groupId, operationId, mutation)).toCompletableFuture().join();
    }

    private Envelope.Builder baseEnvelope() {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-a")
                .setTransportGeneration(1)
                .setRequestId(java.util.UUID.randomUUID().toString())
                .setTenant("tenant-a")
                .setNamespaceId("public")
                .setRemainingBudgetMs(5_000)
                .setGroupId(groupId.value());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class DirectStateClient implements StateClient {
        private final ConfigStateMachine stateMachine;
        private final AtomicLong logIndex = new AtomicLong();

        private DirectStateClient(ConfigStateMachine stateMachine) {
            this.stateMachine = stateMachine;
        }

        @Override
        public CompletionStage<ApplyResult> submit(StateCommand command) {
            long index = logIndex.incrementAndGet();
            return CompletableFuture.completedFuture(stateMachine.apply(
                    command,
                    new ApplyContext(command.groupId(), 1, index)));
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
