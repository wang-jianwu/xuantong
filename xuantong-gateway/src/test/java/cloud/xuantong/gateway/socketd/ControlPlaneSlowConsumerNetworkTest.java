package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.ConfigInvalidation;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneSlowConsumerNetworkTest {
    @Test
    void constrainedSocketReceiveWindowCannotAccumulateWatchReplies() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1",
                port,
                "cluster-socket-backpressure",
                "gateway-socket-backpressure",
                1L,
                5_000L,
                32,
                2,
                64,
                2_000L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        set(properties, "watchPollIntervalMs", 10L);
        set(properties, "watchIdlePollMaxIntervalMs", 100L);
        set(properties, "watchAckTimeoutMs", 1_500L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        dispatcher.register(new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return ControlPlaneProtocol.CONFIG_WATCH_BATCH;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context, Envelope request) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        ControlPlaneReply.ok(
                                ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                                largeWatchResponse().toByteString()));
            }
        });

        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime, dispatcher));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, null);
        ExecutorService clientWork = Executors.newFixedThreadPool(2);
        ClientSession client = null;
        try {
            server.start();
            client = SocketD.createClient(
                            "sd:tcp://127.0.0.1:" + port
                                    + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> config.codecThreads(1)
                            .workExecutor(clientWork)
                            .readBufferSize(1_024)
                            .connectTimeout(3_000L)
                            .requestTimeout(2_000L)
                            .autoReconnect(false))
                    .openOrThow();
            assertEquals(ResponseCode.OK,
                    request(
                            client,
                            ControlPlaneProtocol.SYSTEM_HELLO,
                            helloEnvelope(
                                    "socket-backpressure@node-1",
                                    "cluster-socket-backpressure"))
                            .getResponseStatus().getCode());

            pauseClientSocketReads(client);
            CountDownLatch replyDelivered = new CountDownLatch(1);
            ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                    .setAfterEventRevision(0L)
                    .setGroupName("DEFAULT_GROUP")
                    .setMaxBatchSize(1_024)
                    .build();
            client.sendAndSubscribe(
                            ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                            Entity.of(baseEnvelope("cluster-socket-backpressure")
                                    .setRevisionType(RevisionType.CONFIG_EVENT)
                                    .setGroupId("config-default")
                                    .setPayloadType(
                                            ControlPlaneProtocol
                                                    .CONFIG_WATCH_BATCH_REQUEST_TYPE)
                                    .setPayload(watch.toByteString())
                                    .build()
                                    .toByteArray()),
                            10_000L)
                    .thenReply(reply -> replyDelivered.countDown());

            awaitTrue(() -> runtime.pendingWatchAcknowledgements() == 1,
                    Duration.ofSeconds(3));
            assertFalse(replyDelivered.await(300, TimeUnit.MILLISECONDS),
                    "Paused Netty reads must keep the large Reply outside the client");
            assertEquals(1, runtime.peakPendingWatchAcknowledgements(),
                    "One Watch may have at most one unacknowledged Reply");
            awaitTrue(() -> runtime.watchAckTimeoutClosedTotal() == 1L
                            && runtime.activeSubscriptions() == 0
                            && runtime.pendingWatchAcknowledgements() == 0
                            && runtime.activeSessions() == 0,
                    Duration.ofSeconds(5));
            assertEquals(1L, runtime.subscriptionOpenedTotal());
            assertEquals(1L, runtime.subscriptionClosedTotal());
            assertEquals(runtime.requestAcceptedTotal(), runtime.requestCompletedTotal());
        } finally {
            if (client != null && client.isValid()) {
                client.close();
            }
            clientWork.shutdownNow();
            server.stop();
        }
    }

    @Test
    void realSocketdWatchWithoutAckIsClosedAndFullyReclaimed() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1",
                port,
                "cluster-slow-consumer",
                "gateway-slow-consumer",
                1L,
                5_000L,
                32,
                2,
                64,
                2_000L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        set(properties, "watchPollIntervalMs", 10L);
        set(properties, "watchIdlePollMaxIntervalMs", 100L);
        set(properties, "watchAckTimeoutMs", 1_000L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        dispatcher.register(new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return ControlPlaneProtocol.CONFIG_WATCH_BATCH;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context, Envelope request) {
                try {
                    ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.parseFrom(
                            request.getPayload());
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            ControlPlaneReply.ok(
                                    ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                                    ConfigWatchBatchResponse.newBuilder()
                                            .setRequestedAfterRevision(
                                                    watch.getAfterEventRevision())
                                            .setCoveredThroughRevision(1L)
                                            .build()
                                            .toByteString()));
                } catch (Exception e) {
                    return java.util.concurrent.CompletableFuture.failedFuture(e);
                }
            }
        });

        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime, dispatcher));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, null);
        ExecutorService clientWork = Executors.newFixedThreadPool(2);
        ClientSession client = null;
        try {
            server.start();
            client = SocketD.createClient(
                            "sd:tcp://127.0.0.1:" + port
                                    + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> config.codecThreads(1)
                            .workExecutor(clientWork)
                            .connectTimeout(3_000L)
                            .requestTimeout(2_000L)
                            .autoReconnect(false))
                    .openOrThow();
            assertEquals(ResponseCode.OK,
                    request(client, ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope())
                            .getResponseStatus().getCode());

            CountDownLatch firstReply = new CountDownLatch(1);
            ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                    .setAfterEventRevision(0L)
                    .setGroupName("DEFAULT_GROUP")
                    .setMaxBatchSize(10)
                    .build();
            client.sendAndSubscribe(
                            ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                            Entity.of(baseEnvelope()
                                    .setRevisionType(RevisionType.CONFIG_EVENT)
                                    .setGroupId("config-default")
                                    .setPayloadType(
                                            ControlPlaneProtocol
                                                    .CONFIG_WATCH_BATCH_REQUEST_TYPE)
                                    .setPayload(watch.toByteString())
                                    .build()
                                    .toByteArray()),
                            10_000L)
                    .thenReply(reply -> firstReply.countDown());

            assertTrue(firstReply.await(3, TimeUnit.SECONDS));
            assertEquals(1, runtime.pendingWatchAcknowledgements());
            awaitTrue(() -> runtime.watchAckTimeoutClosedTotal() == 1L
                            && runtime.activeSubscriptions() == 0
                            && runtime.pendingWatchAcknowledgements() == 0
                            && runtime.activeSessions() == 0,
                    Duration.ofSeconds(5));
            assertEquals(1L, runtime.subscriptionOpenedTotal());
            assertEquals(1L, runtime.subscriptionClosedTotal());
            assertEquals(runtime.requestAcceptedTotal(), runtime.requestCompletedTotal());
        } finally {
            if (client != null && client.isValid()) {
                client.close();
            }
            clientWork.shutdownNow();
            server.stop();
        }
    }

    @Test
    void slowConsumerCohortIsBoundedAndFullyReclaimed() throws Exception {
        int clients = 24;
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1",
                port,
                "cluster-slow-consumer-cohort",
                "gateway-slow-consumer-cohort",
                1L,
                5_000L,
                128,
                4,
                512,
                2_000L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        set(properties, "watchPollIntervalMs", 10L);
        set(properties, "watchIdlePollMaxIntervalMs", 100L);
        set(properties, "watchAckTimeoutMs", 3_000L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        dispatcher.register(new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return ControlPlaneProtocol.CONFIG_WATCH_BATCH;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context, Envelope request) {
                try {
                    ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.parseFrom(
                            request.getPayload());
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            ControlPlaneReply.ok(
                                    ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                                    ConfigWatchBatchResponse.newBuilder()
                                            .setRequestedAfterRevision(
                                                    watch.getAfterEventRevision())
                                            .setCoveredThroughRevision(1L)
                                            .build()
                                            .toByteString()));
                } catch (Exception e) {
                    return java.util.concurrent.CompletableFuture.failedFuture(e);
                }
            }
        });

        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime, dispatcher));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, null);
        ExecutorService clientWork = Executors.newFixedThreadPool(4);
        List<ClientSession> sessions = new ArrayList<>();
        try {
            server.start();
            CountDownLatch firstReplies = new CountDownLatch(clients);
            for (int index = 0; index < clients; index++) {
                ClientSession client = SocketD.createClient(
                                "sd:tcp://127.0.0.1:" + port
                                        + ControlPlaneProtocol.CONTROL_PATH)
                        .config(config -> config.codecThreads(1)
                                .workExecutor(clientWork)
                                .connectTimeout(3_000L)
                                .requestTimeout(2_000L)
                                .autoReconnect(false))
                        .openOrThow();
                sessions.add(client);
                assertEquals(ResponseCode.OK,
                        request(
                                client,
                                ControlPlaneProtocol.SYSTEM_HELLO,
                                helloEnvelope(
                                        "slow-consumer@" + index,
                                        "cluster-slow-consumer-cohort"))
                                .getResponseStatus()
                                .getCode());
                ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                        .setAfterEventRevision(0L)
                        .setGroupName("DEFAULT_GROUP")
                        .setMaxBatchSize(10)
                        .build();
                client.sendAndSubscribe(
                                ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                                Entity.of(baseEnvelope("cluster-slow-consumer-cohort")
                                        .setRevisionType(RevisionType.CONFIG_EVENT)
                                        .setGroupId("config-default")
                                        .setPayloadType(
                                                ControlPlaneProtocol
                                                        .CONFIG_WATCH_BATCH_REQUEST_TYPE)
                                        .setPayload(watch.toByteString())
                                        .build()
                                        .toByteArray()),
                                10_000L)
                        .thenReply(reply -> firstReplies.countDown());
            }

            assertTrue(firstReplies.await(5, TimeUnit.SECONDS));
            assertEquals(clients, runtime.activeSessions());
            assertEquals(clients, runtime.activeSubscriptions());
            assertEquals(clients, runtime.pendingWatchAcknowledgements());
            awaitTrue(() -> runtime.watchAckTimeoutClosedTotal() == clients
                            && runtime.activeSubscriptions() == 0
                            && runtime.pendingWatchAcknowledgements() == 0
                            && runtime.activeSessions() == 0,
                    Duration.ofSeconds(10));
            assertEquals(clients, runtime.subscriptionOpenedTotal());
            assertEquals(clients, runtime.subscriptionClosedTotal());
            assertEquals(clients, runtime.sessionOpenedTotal());
            assertEquals(clients, runtime.sessionClosedTotal());
            assertEquals(runtime.requestAcceptedTotal(), runtime.requestCompletedTotal());
        } finally {
            for (ClientSession session : sessions) {
                if (session.isValid()) {
                    session.close();
                }
            }
            clientWork.shutdownNow();
            server.stop();
        }
    }

    private Envelope request(ClientSession client, String event, Envelope envelope)
            throws Exception {
        Reply reply = client.sendAndRequest(
                        event, Entity.of(envelope.toByteArray()), 2_000L)
                .await();
        return Envelope.parseFrom(reply.dataAsBytes());
    }

    private ConfigWatchBatchResponse largeWatchResponse() {
        ConfigWatchBatchResponse.Builder response =
                ConfigWatchBatchResponse.newBuilder()
                        .setRequestedAfterRevision(0L)
                        .setCoveredThroughRevision(512L);
        String dataIdSuffix = "x".repeat(1_024);
        for (int revision = 1; revision <= 512; revision++) {
            response.addEvents(ConfigInvalidation.newBuilder()
                    .setEventRevision(revision)
                    .setDecisionRevision(revision)
                    .setConfig(ConfigCoordinate.newBuilder()
                            .setNamespaceId("public")
                            .setGroupName("DEFAULT_GROUP")
                            .setDataId("slow-read-" + revision + "-" + dataIdSuffix)));
        }
        return response.build();
    }

    private void pauseClientSocketReads(ClientSession client) throws Exception {
        Field sessionChannel = findField(client.getClass(), "channel");
        Object clientChannel = sessionChannel.get(client);
        Field real = findField(clientChannel.getClass(), "real");
        Object realChannel = real.get(clientChannel);
        Field source = findField(realChannel.getClass(), "source");
        io.netty.channel.Channel nettyChannel =
                (io.netty.channel.Channel) source.get(realChannel);
        nettyChannel.eventLoop().submit(() -> {
            nettyChannel.config().setOption(
                    io.netty.channel.ChannelOption.SO_RCVBUF, 1_024);
            nettyChannel.config().setAutoRead(false);
        }).syncUninterruptibly();
    }

    private Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private Envelope helloEnvelope() {
        return helloEnvelope(
                "slow-consumer@node-1", "cluster-slow-consumer");
    }

    private Envelope helloEnvelope(String clientInstanceId, String clusterId) {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId(clientInstanceId)
                .setApplicationName("slow-consumer")
                .setGroupName("DEFAULT_GROUP")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .addCapabilities("watch-ack-v1")
                .build();
        return baseEnvelope(clusterId)
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope.Builder baseEnvelope(String clusterId) {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId(clusterId)
                .setTransportGeneration(1L)
                .setRequestId(UUID.randomUUID().toString())
                .setTenant("default")
                .setNamespaceId("public")
                .setRemainingBudgetMs(2_000L);
    }

    private Envelope.Builder baseEnvelope() {
        return baseEnvelope("cluster-slow-consumer");
    }

    private void awaitTrue(Check condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(20L);
        }
        assertTrue(condition.evaluate());
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

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }
}
