package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.model.ConfigGroupSnapshot;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ConfigWatchBatch;
import cloud.xuantong.client.transport.impl.SocketDTransport;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.ConfigFetchRequest;
import cloud.xuantong.protocol.v2.ConfigFetchResponse;
import cloud.xuantong.protocol.v2.ConfigSnapshotRequest;
import cloud.xuantong.protocol.v2.ConfigSnapshotResponse;
import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateCommand;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigControlPlaneProductionIntegrationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void productionEndpointRoutesFetchSnapshotAndWatchThroughRatis() throws Exception {
        int statePort = freePort();
        int gatewayPort = freePort();
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        ConfigStatePlaneProperties stateProperties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                "config-default",
                "state-1@127.0.0.1:" + statePort,
                tempDirectory.resolve("state"),
                true);
        ControlStatePlaneRuntime stateRuntime = new ControlStatePlaneRuntime(
                stateProperties, dispatcher);
        ControlPlaneGatewayServer gatewayServer = null;
        ClientSession client = null;
        SocketDTransport clientTransport = null;
        WatchSubscription watchSubscription = null;
        try {
            stateRuntime.start();
            ConfigKey key = new ConfigKey("public", "DEFAULT_GROUP", "demo.value");
            StateCommand publish = ConfigStateCodec.mutationCommand(
                    stateProperties.stateGroupId(),
                    "publish-production-test",
                    new ConfigMutation(
                            new ConfigActor("tenant-a", "admin-a"),
                            key,
                            0,
                            ConfigContentDraft.inline(
                                    "text", 1, bytes("production-ratis-value")),
                            ConfigContentReference.newContent(),
                            List.of()));
            ApplyResult applied = submitEventually(stateRuntime, publish);
            assertEquals(ApplyStatus.APPLIED, applied.status());

            ControlPlaneGatewayProperties gatewayProperties =
                    new ControlPlaneGatewayProperties(
                            "127.0.0.1",
                            gatewayPort,
                            "cluster-production-test",
                            "gateway-1",
                            1,
                            5_000,
                            32,
                            2,
                            64,
                            2_000,
                            ControlPlaneGatewayProperties.ClientAuth.NONE);
            ControlPlaneGatewayRuntime gatewayRuntime =
                    new ControlPlaneGatewayRuntime(gatewayProperties);
            PathListenerPlus router = new PathListenerPlus(true);
            router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                    new ControlPlaneGatewayEndpoint(
                            gatewayProperties, gatewayRuntime, dispatcher));
            gatewayServer = new ControlPlaneGatewayServer(
                    gatewayProperties, gatewayRuntime, router, null);
            gatewayServer.start();

            client = SocketD.createClient(
                            "sd:tcp://127.0.0.1:" + gatewayPort
                                    + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> config.connectTimeout(3_000)
                            .requestTimeout(5_000)
                            .autoReconnect(false))
                    .openOrThow();
            assertEquals(ResponseCode.OK,
                    request(client, ControlPlaneProtocol.SYSTEM_HELLO,
                            helloEnvelope()).getResponseStatus().getCode());

            ConfigFetchRequest fetch = ConfigFetchRequest.newBuilder()
                    .setGroupName(key.group())
                    .setDataId(key.dataId())
                    .build();
            Envelope fetchResponse = request(
                    client,
                    ControlPlaneProtocol.CONFIG_FETCH,
                    configEnvelope(RevisionType.CONFIG_DECISION)
                            .setPayloadType(ControlPlaneProtocol.CONFIG_FETCH_REQUEST_TYPE)
                            .setPayload(fetch.toByteString())
                            .build());
            ConfigFetchResponse fetched = ConfigFetchResponse.parseFrom(
                    fetchResponse.getPayload());
            assertEquals(ResponseCode.OK, fetchResponse.getResponseStatus().getCode());
            assertEquals("production-ratis-value",
                    fetched.getContent().getPayload().toStringUtf8());
            assertEquals(1, fetched.getDecisionRevision());

            ConfigCoordinate coordinate = ConfigCoordinate.newBuilder()
                    .setNamespaceId(key.namespace())
                    .setGroupName(key.group())
                    .setDataId(key.dataId())
                    .build();
            ConfigSnapshotRequest snapshot = ConfigSnapshotRequest.newBuilder()
                    .addConfigs(coordinate)
                    .build();
            Envelope snapshotResponse = request(
                    client,
                    ControlPlaneProtocol.CONFIG_SNAPSHOT,
                    configEnvelope(RevisionType.CONFIG_EVENT)
                            .setPayloadType(ControlPlaneProtocol.CONFIG_SNAPSHOT_REQUEST_TYPE)
                            .setPayload(snapshot.toByteString())
                            .build());
            ConfigSnapshotResponse snapped = ConfigSnapshotResponse.parseFrom(
                    snapshotResponse.getPayload());
            assertEquals(1, snapped.getEventRevision());
            assertEquals(1, snapped.getDecisionsCount());

            ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                    .setAfterEventRevision(0)
                    .setGroupName("DEFAULT_GROUP")
                    .setMaxBatchSize(10)
                    .addConfigs(coordinate)
                    .build();
            Envelope watchResponse = request(
                    client,
                    ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                    configEnvelope(RevisionType.CONFIG_EVENT)
                            .setPayloadType(
                                    ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE)
                            .setPayload(watch.toByteString())
                            .build());
            ConfigWatchBatchResponse watched = ConfigWatchBatchResponse.parseFrom(
                    watchResponse.getPayload());
            assertEquals(1, watched.getCoveredThroughRevision());
            assertEquals(1, watched.getEventsCount());
            assertEquals(key.dataId(), watched.getEvents(0).getConfig().getDataId());

            client.close();
            client = null;
            clientTransport = new SocketDTransport(
                    new ClientIdentity("production-client", "production-client@node-1"),
                    new ControlPlaneOptions(
                            "tenant-a",
                            "config-default",
                            "cluster-production-test",
                            1L,
                            "tcp-default",
                            3_000L,
                            3_000L,
                            6_000L,
                            1_000L));
            clientTransport.connect(
                    List.of("127.0.0.1:" + gatewayPort),
                    key.namespace(),
                    key.group(),
                    "");

            ConfigSnapshot sdkFetch = clientTransport.fetch(key.dataId(), 0L);
            assertNotNull(sdkFetch);
            assertEquals("production-ratis-value", sdkFetch.getContent());
            assertEquals(1L, sdkFetch.getRevision());

            ConfigGroupSnapshot sdkSnapshot = clientTransport.snapshot(
                    List.of(key.dataId()), 0L);
            assertNotNull(sdkSnapshot);
            assertEquals(1L, sdkSnapshot.eventRevision());
            assertEquals(1L, sdkSnapshot.decisionRevisions().get(key.dataId()));

            ConfigWatchBatch sdkWatch = clientTransport.watchBatch(
                    List.of(key.dataId()), 0L, 10);
            assertNotNull(sdkWatch);
            assertEquals(1L, sdkWatch.coveredThroughRevision());
            assertEquals(1, sdkWatch.events().size());

            CountDownLatch streamed = new CountDownLatch(1);
            AtomicReference<ConfigWatchBatch> streamedBatch = new AtomicReference<>();
            watchSubscription = clientTransport.subscribe(1L, batch -> {
                streamedBatch.set(batch);
                streamed.countDown();
                return batch.coveredThroughRevision();
            });

            StateCommand update = ConfigStateCodec.mutationCommand(
                    stateProperties.stateGroupId(),
                    "update-production-test",
                    new ConfigMutation(
                            new ConfigActor("tenant-a", "admin-a"),
                            key,
                            1,
                            ConfigContentDraft.inline(
                                    "text", 1, bytes("production-ratis-value-v2")),
                            ConfigContentReference.newContent(),
                            List.of()));
            ApplyResult updated = submitEventually(stateRuntime, update);
            assertEquals(ApplyStatus.APPLIED, updated.status());

            assertTrue(streamed.await(5, TimeUnit.SECONDS));
            assertEquals(2L, streamedBatch.get().coveredThroughRevision());
            assertEquals(1, streamedBatch.get().events().size());
            assertEquals(2L, streamedBatch.get().events().getFirst().decisionRevision());

            ConfigSnapshot refreshed = clientTransport.fetch(key.dataId(), 1L);
            assertNotNull(refreshed);
            assertEquals("production-ratis-value-v2", refreshed.getContent());
            assertEquals(2L, refreshed.getRevision());
        } finally {
            if (watchSubscription != null) {
                watchSubscription.close();
            }
            if (clientTransport != null) {
                clientTransport.close();
            }
            if (client != null && client.isValid()) {
                client.close();
            }
            if (gatewayServer != null) {
                gatewayServer.stop();
            }
            stateRuntime.stop();
        }
    }

    private ApplyResult submitEventually(
            ControlStatePlaneRuntime runtime, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return runtime.stateClient().submit(command)
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last == null ? new IllegalStateException("Config State did not elect a leader") : last;
    }

    private Envelope helloEnvelope() {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("demo@production-test")
                .setApplicationName("demo")
                .setGroupName("DEFAULT_GROUP")
                .setClientVersion("2.0.0-test")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .build();
        return baseEnvelope()
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope.Builder configEnvelope(RevisionType revisionType) {
        return baseEnvelope()
                .setRevisionType(revisionType)
                .setGroupId("config-default");
    }

    private Envelope.Builder baseEnvelope() {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-production-test")
                .setTransportGeneration(1)
                .setRequestId(UUID.randomUUID().toString())
                .setTenant("tenant-a")
                .setNamespaceId("public")
                .setRemainingBudgetMs(5_000);
    }

    private Envelope request(ClientSession client, String event, Envelope envelope)
            throws Exception {
        Reply reply = client.sendAndRequest(
                        event, Entity.of(envelope.toByteArray()), 5_000)
                .await();
        assertTrue(reply.isEnd());
        Envelope response = Envelope.parseFrom(reply.dataAsBytes());
        assertEquals(envelope.getRequestId(), response.getRequestId());
        return response;
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

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
