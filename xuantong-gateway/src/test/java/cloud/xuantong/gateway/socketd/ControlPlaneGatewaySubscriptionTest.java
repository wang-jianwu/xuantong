package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.protocol.v2.WatchAckRequest;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Flags;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.MessageDefault;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneGatewaySubscriptionTest {
    @Test
    void subscribeStreamsReplyFramesAndEndsOnReset() throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        AtomicInteger calls = new AtomicInteger();
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
                    int call = calls.incrementAndGet();
                    assertEquals(call - 1L, watch.getAfterEventRevision());
                    ConfigWatchBatchResponse response =
                            ConfigWatchBatchResponse.newBuilder()
                                    .setRequestedAfterRevision(watch.getAfterEventRevision())
                                    .setCoveredThroughRevision(call)
                                    .setCompactionRevision(call == 2 ? 2 : 0)
                                    .setResetRequired(call == 2)
                                    .build();
                    return CompletableFuture.completedFuture(ControlPlaneReply.ok(
                            ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                            response.toByteString()));
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
        });

        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties, runtime, dispatcher);
        RecordingSession recording = new RecordingSession();
        Session session = recording.proxy();
        endpoint.onOpen(session);
        endpoint.onMessage(session, requestMessage(
                ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope()));
        assertEquals(ResponseCode.OK,
                recording.responses.getFirst().getResponseStatus().getCode());
        HelloResponse hello = HelloResponse.parseFrom(
                recording.responses.getFirst().getPayload());
        assertTrue(hello.getCapabilitiesList().contains("config-watch-stream-v1"));
        assertTrue(hello.getCapabilitiesList().contains("watch-ack-v1"));
        assertFalse(hello.getCapabilitiesList().contains("config-snapshot-v1"));

        ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                .setAfterEventRevision(0)
                .setGroupName("DEFAULT_GROUP")
                .setMaxBatchSize(10)
                .build();
        Envelope subscriptionRequest = baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_EVENT)
                        .setGroupId("config-default")
                        .setPayloadType(ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE)
                        .setPayload(watch.toByteString())
                        .build();
        endpoint.onMessage(session, subscribeMessage(
                ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                subscriptionRequest));

        assertTrue(recording.firstWatchReply.await(1, TimeUnit.SECONDS));
        WatchAckRequest acknowledgement = WatchAckRequest.newBuilder()
                .setSubscriptionRequestId(subscriptionRequest.getRequestId())
                .setCommittedRevision(1L)
                .build();
        endpoint.onMessage(session, requestMessage(
                ControlPlaneProtocol.SYSTEM_WATCH_ACK,
                baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_EVENT)
                        .setGroupId("config-default")
                        .setKnownRevision(1L)
                        .setMinRevision(1L)
                        .setPayloadType(ControlPlaneProtocol.WATCH_ACK_REQUEST_TYPE)
                        .setPayload(acknowledgement.toByteString())
                        .build()));

        assertTrue(recording.watchReplies.await(2, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        assertEquals(4, recording.responses.size());
        assertFalse(recording.replyEndFlags.get(1));
        assertEquals(ControlPlaneProtocol.WATCH_ACK_RESPONSE_TYPE,
                recording.responses.get(2).getPayloadType());
        assertTrue(recording.replyEndFlags.get(2));
        assertTrue(recording.replyEndFlags.get(3));
        awaitSubscriptionClosed(runtime);
        assertEquals(0, runtime.activeSubscriptions());
        assertEquals(1L, runtime.watchAcknowledgedTotal());
    }

    @Test
    void unacknowledgedWatchReplyClosesSlowConsumerAndReleasesQuota()
            throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L);
        set(properties, "watchPollIntervalMs", 10L);
        set(properties, "watchIdlePollMaxIntervalMs", 100L);
        set(properties, "watchAckTimeoutMs", 1_000L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        AtomicInteger calls = new AtomicInteger();
        dispatcher.register(new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return ControlPlaneProtocol.CONFIG_WATCH_BATCH;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context, Envelope request) {
                calls.incrementAndGet();
                ConfigWatchBatchResponse response = ConfigWatchBatchResponse.newBuilder()
                        .setRequestedAfterRevision(0L)
                        .setCoveredThroughRevision(1L)
                        .build();
                return CompletableFuture.completedFuture(ControlPlaneReply.ok(
                        ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                        response.toByteString()));
            }
        });

        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties, runtime, dispatcher);
        RecordingSession recording = new RecordingSession();
        Session session = recording.proxy();
        endpoint.onOpen(session);
        endpoint.onMessage(session, requestMessage(
                ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope()));
        ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                .setAfterEventRevision(0L)
                .setGroupName("DEFAULT_GROUP")
                .setMaxBatchSize(10)
                .build();
        endpoint.onMessage(session, subscribeMessage(
                ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_EVENT)
                        .setGroupId("config-default")
                        .setPayloadType(ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE)
                        .setPayload(watch.toByteString())
                        .build()));

        assertTrue(recording.firstWatchReply.await(1, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (!recording.closed.get() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        assertTrue(recording.closed.get());
        assertEquals(1, calls.get(), "one unacknowledged Reply must stop the pump");
        assertEquals(0, runtime.activeSubscriptions());
        assertEquals(0, runtime.pendingWatchAcknowledgements());
        assertEquals(1L, runtime.watchAckTimeoutClosedTotal());
    }

    @Test
    void sessionCloseCancelsQueuedWatchPollAndReleasesSubscription()
            throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L);
        set(properties, "watchPollIntervalMs", 50L);
        set(properties, "watchIdlePollMaxIntervalMs", 200L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstPoll = new CountDownLatch(1);
        dispatcher.register(new ControlPlaneRequestHandler() {
            @Override
            public String event() {
                return ControlPlaneProtocol.CONFIG_WATCH_BATCH;
            }

            @Override
            public java.util.concurrent.CompletionStage<ControlPlaneReply> handle(
                    ControlPlaneRequestContext context, Envelope request) {
                calls.incrementAndGet();
                firstPoll.countDown();
                ConfigWatchBatchResponse response = ConfigWatchBatchResponse.newBuilder()
                        .setRequestedAfterRevision(0L)
                        .setCoveredThroughRevision(0L)
                        .build();
                return CompletableFuture.completedFuture(ControlPlaneReply.ok(
                        ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                        response.toByteString()));
            }
        });

        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties, runtime, dispatcher);
        RecordingSession recording = new RecordingSession();
        Session session = recording.proxy();
        endpoint.onOpen(session);
        endpoint.onMessage(session, requestMessage(
                ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope()));
        ConfigWatchBatchRequest watch = ConfigWatchBatchRequest.newBuilder()
                .setAfterEventRevision(0L)
                .setGroupName("DEFAULT_GROUP")
                .setMaxBatchSize(10)
                .build();
        endpoint.onMessage(session, subscribeMessage(
                ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                baseEnvelope()
                        .setRevisionType(RevisionType.CONFIG_EVENT)
                        .setGroupId("config-default")
                        .setPayloadType(ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE)
                        .setPayload(watch.toByteString())
                        .build()));

        assertTrue(firstPoll.await(1, TimeUnit.SECONDS));
        endpoint.onClose(session);
        Thread.sleep(300L);

        assertEquals(1, calls.get());
        assertEquals(0, runtime.activeSubscriptions());
        assertEquals(0, runtime.activeSessions());
    }

    private void awaitSubscriptionClosed(ControlPlaneGatewayRuntime runtime)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (runtime.activeSubscriptions() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Envelope helloEnvelope() {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("demo@node-1")
                .setApplicationName("demo")
                .setGroupName("DEFAULT_GROUP")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .addCapabilities("watch-ack-v1")
                .build();
        return baseEnvelope()
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
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
                .setRemainingBudgetMs(2_000L);
    }

    private Message requestMessage(String event, Envelope envelope) {
        return new MessageDefault(
                Flags.Request,
                UUID.randomUUID().toString(),
                event,
                Entity.of(envelope.toByteArray()));
    }

    private Message subscribeMessage(String event, Envelope envelope) {
        return new MessageDefault(
                Flags.Subscribe,
                UUID.randomUUID().toString(),
                event,
                Entity.of(envelope.toByteArray()));
    }

    private static final class RecordingSession {
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<Envelope> responses = new ArrayList<>();
        private final List<Boolean> replyEndFlags = new ArrayList<>();
        private final CountDownLatch firstWatchReply = new CountDownLatch(1);
        private final CountDownLatch watchReplies = new CountDownLatch(2);
        private final AtomicBoolean closed = new AtomicBoolean();

        private Session proxy() {
            return (Session) Proxy.newProxyInstance(
                    Session.class.getClassLoader(),
                    new Class<?>[]{Session.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "sessionId" -> "session-1";
                        case "isActive", "isValid" -> !closed.get();
                        case "isClosing" -> false;
                        case "remoteAddress" -> new InetSocketAddress("127.0.0.1", 12345);
                        case "attr" -> attributes.get((String) args[0]);
                        case "attrPut" -> {
                            attributes.put((String) args[0], args[1]);
                            yield proxy;
                        }
                        case "attrDel" -> {
                            attributes.remove((String) args[0]);
                            yield proxy;
                        }
                        case "reply" -> {
                            record((Entity) args[1], false);
                            yield null;
                        }
                        case "replyEnd" -> {
                            record((Entity) args[1], true);
                            yield null;
                        }
                        case "close" -> {
                            closed.set(true);
                            yield null;
                        }
                        case "preclose" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private synchronized void record(Entity entity, boolean end) throws Exception {
            Envelope response = Envelope.parseFrom(entity.dataAsBytes());
            responses.add(response);
            replyEndFlags.add(end);
            if (ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE.equals(
                    response.getPayloadType())) {
                firstWatchReply.countDown();
                watchReplies.countDown();
            }
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0D;
            if (type == float.class) return 0F;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
