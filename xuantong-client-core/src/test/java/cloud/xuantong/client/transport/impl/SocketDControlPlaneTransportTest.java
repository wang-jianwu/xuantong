package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.ControlPlaneProbeResult;
import cloud.xuantong.client.metrics.ControlPlaneTransportMetricsSnapshot;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.LeaseRenewalResult;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.protocol.v2.ConfigContentValue;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.ConfigFetchRequest;
import cloud.xuantong.protocol.v2.ConfigFetchResponse;
import cloud.xuantong.protocol.v2.ConfigValueState;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.DiscoveryServiceInstance;
import cloud.xuantong.protocol.v2.DiscoveryMutationResponse;
import cloud.xuantong.protocol.v2.DiscoveryRegisterRequest;
import cloud.xuantong.protocol.v2.DiscoveryRenewBatchRequest;
import cloud.xuantong.protocol.v2.DiscoverySnapshotRequest;
import cloud.xuantong.protocol.v2.DiscoverySnapshotResponse;
import cloud.xuantong.protocol.v2.ServiceCoordinate;
import cloud.xuantong.protocol.v2.ServiceInstanceCoordinate;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ProbeResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import cloud.xuantong.protocol.v2.WatchAckRequest;
import cloud.xuantong.protocol.v2.WatchAckResponse;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.noear.socketd.exception.SocketDTimeoutException;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.socketd.transport.stream.SubscribeStream;
import org.noear.socketd.utils.IoConsumer;
import org.noear.socketd.utils.TriConsumer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketDControlPlaneTransportTest {
    @Test
    void metricsSnapshotTracksSessionRequestWaitsAndSubscribeStreams() {
        FakeConfigTransport transport = new FakeConfigTransport();
        WatchSubscription subscription = null;
        try {
            assertEquals(new ControlPlaneTransportMetricsSnapshot(0, 0, 0, 0, false),
                    transport.metricsSnapshot());
            transport.connect(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "");
            subscription = transport.subscribe(0L,
                    batch -> batch.coveredThroughRevision());

            assertEquals(new ControlPlaneTransportMetricsSnapshot(1, 0, 1, 1, false),
                    transport.metricsSnapshot());
            subscription.close();
            subscription = null;
            assertEquals(new ControlPlaneTransportMetricsSnapshot(0, 0, 0, 0, false),
                    transport.metricsSnapshot());
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            transport.close();
        }

        assertEquals(new ControlPlaneTransportMetricsSnapshot(0, 0, 0, 0, true),
                transport.metricsSnapshot());
    }

    @Test
    void configWatchUsesSocketdSubscribeAndCommitsDeliveredCursor() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        WatchSubscription subscription = null;
        try {
            transport.connect(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "");
            CountDownLatch applied = new CountDownLatch(1);
            AtomicLong committed = new AtomicLong();
            subscription = transport.subscribe(0L, batch -> {
                committed.set(batch.coveredThroughRevision());
                applied.countDown();
                return batch.coveredThroughRevision();
            });

            transport.emitConfigWatch(0, 0L, 1L, false);

            assertTrue(applied.await(1, TimeUnit.SECONDS));
            assertEquals(1L, committed.get());
            long acknowledgementDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(1L);
            while (transport.watchAcknowledgements.get() == 0
                    && System.nanoTime() < acknowledgementDeadline) {
                Thread.sleep(10L);
            }
            assertEquals(1, transport.watchAcknowledgements.get());
            assertEquals(1, transport.sessionControls.get(0).subscribeCount.get());
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            transport.close();
        }
    }

    @Test
    void failedWatchHandlerResubscribesFromLastCommittedCursor() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        WatchSubscription subscription = null;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");
            AtomicInteger deliveries = new AtomicInteger();
            subscription = transport.subscribe(0L, batch -> {
                if (deliveries.incrementAndGet() == 1) {
                    throw new IllegalStateException("simulated apply failure");
                }
                return batch.coveredThroughRevision();
            });

            transport.emitConfigWatch(0, 0L, 1L, false);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while ((transport.activeGatewayIndex() != 1
                    || transport.sessionControls.get(1) == null
                    || transport.sessionControls.get(1).subscribeEnvelope == null)
                    && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(1, transport.activeGatewayIndex());
            ConfigWatchBatchRequest resumed = ConfigWatchBatchRequest.parseFrom(
                    transport.sessionControls.get(1).subscribeEnvelope.getPayload());
            assertEquals(0L, resumed.getAfterEventRevision());
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            transport.close();
        }
    }

    @Test
    void partiallyAcceptedWatchBatchResubscribesFromCommittedCursor() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        WatchSubscription subscription = null;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");
            subscription = transport.subscribe(0L, batch -> 1L);

            transport.emitConfigWatch(0, 0L, 2L, false);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while ((transport.activeGatewayIndex() != 1
                    || transport.sessionControls.get(1) == null
                    || transport.sessionControls.get(1).subscribeEnvelope == null)
                    && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(1, transport.activeGatewayIndex());
            ConfigWatchBatchRequest resumed = ConfigWatchBatchRequest.parseFrom(
                    transport.sessionControls.get(1).subscribeEnvelope.getPayload());
            assertEquals(1L, resumed.getAfterEventRevision());
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            transport.close();
        }
    }

    @Test
    void configUsesOneActiveGatewayAndSequentialFailover() {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.failFetchOnGatewayZero = true;
        try {
            transport.connect(
                    Arrays.asList("gateway-a:8090", "gateway-b:8090", "gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "");

            assertEquals(1, transport.activeSessionCount());
            assertEquals(0, transport.activeGatewayIndex());
            ConfigSnapshot snapshot = transport.fetch("app.yml", 0L);
            assertNotNull(snapshot);
            assertEquals("from-gateway-1", snapshot.getContent());
            assertEquals(2, transport.fetchAttempts.get());
            assertEquals(2, transport.openedUrls.size());
            assertEquals(1, transport.activeSessionCount());
            assertEquals(1, transport.activeGatewayIndex());
        } finally {
            transport.close();
        }
    }

    @Test
    void initialConnectSkipsUnavailableAddressWithinTheSameDeadline() {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.failOpenGatewayZero = true;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");
            assertEquals(1, transport.activeSessionCount());
            assertEquals(1, transport.activeGatewayIndex());
            assertEquals(2, transport.openedUrls.size());
        } finally {
            transport.close();
        }
    }

    @Test
    void healthyConfigRequestDoesNotFanOutAndTokenStaysOutOfUrl() {
        FakeConfigTransport transport = new FakeConfigTransport();
        try {
            transport.connect(
                    Arrays.asList("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "secret-token");

            ConfigSnapshot snapshot = transport.fetch("app.yml", 0L);
            assertNotNull(snapshot);
            assertEquals("from-gateway-0", snapshot.getContent());
            assertEquals(1, transport.fetchAttempts.get());
            assertEquals(1, transport.openedUrls.size(),
                    "standby addresses must not become standby connections");
            assertEquals("sd:tcp://gateway-a:8090/control-v2", transport.openedUrls.get(0));
            assertFalse(transport.openedUrls.get(0).contains("secret-token"));
            assertEquals("test-client", transport.helloClientInstanceId);
            assertEquals("test-app", transport.helloApplicationName);
            assertEquals("DEFAULT_GROUP", transport.helloGroupName);
            assertEquals("secret-token", transport.helloCredential);
        } finally {
            transport.close();
        }
    }

    @Test
    void publicProbeReturnsValidatedRequestReplyTelemetry() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "secret-token");

            ControlPlaneProbeResult result = transport.probe();

            assertEquals("gateway-0", result.gatewayId());
            assertEquals("cluster-test", result.clusterId());
            assertEquals(1L, result.transportGeneration());
            assertEquals(1L, result.connectionGeneration());
            assertEquals("sd:tcp://gateway-a:8090/control-v2", result.address());
            assertEquals(0, result.addressIndex());
            assertTrue(result.rpcDurationNanos() >= 0L);
            assertTrue(result.rpcDurationSeconds() >= 0D);
            assertTrue(result.serverReceiveEpochMs() > 0L);
            assertTrue(result.serverSendEpochMs() >= result.serverReceiveEpochMs());
            assertEquals(1, transport.activeSessionCount());
            assertEquals(1, transport.openedUrls.size(),
                    "a healthy Probe must not fan out to standby addresses");
        } finally {
            transport.close();
        }
    }

    @Test
    void oneShotProbeRejectsAReusedTransport() {
        FakeConfigTransport transport = new FakeConfigTransport();
        try {
            transport.connect(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "");

            assertThrows(IllegalStateException.class, () -> transport.probeOnce(
                    List.of("gateway-b:8090"),
                    "public", "DEFAULT_GROUP", ""));
        } finally {
            transport.close();
        }
    }

    @Test
    void oneShotProbeUsesOnlyOneSequentialStandbyWithinItsDeadline() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.failOpenGatewayZero = true;
        try {
            ControlPlaneProbeResult result = transport.probeOnce(
                    List.of("gateway-a:8090", "gateway-b:8090", "gateway-c:8090"),
                    "public", "DEFAULT_GROUP", "");

            assertEquals(1, result.addressIndex());
            assertEquals("gateway-1", result.gatewayId());
            assertEquals(2, transport.openedUrls.size(),
                    "Probe failover must be sequential and capped at two addresses");
            assertEquals(1, transport.activeSessionCount());
        } finally {
            transport.close();
        }
    }

    @Test
    void configFetchMapsAuthoritativeTombstoneWithoutTreatingItAsFailure() {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.tombstoneConfig = true;
        try {
            transport.connect(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "");

            ConfigSnapshot snapshot = transport.fetch("app.yml", 7L);

            assertNotNull(snapshot);
            assertTrue(snapshot.isTombstone());
            assertEquals("app.yml", snapshot.getDataId());
            assertEquals(8L, snapshot.getRevision());
            assertNull(snapshot.getContent());
            assertEquals(1, transport.fetchAttempts.get());
        } finally {
            transport.close();
        }
    }

    @Test
    void unauthorizedHelloStopsBackgroundReconnectStorm() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.rejectHelloUnauthorized = true;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "bad-token");

            assertEquals(0, transport.activeSessionCount());
            assertEquals(1, transport.openedUrls.size());
            Thread.sleep(1_200L);
            assertEquals(1, transport.openedUrls.size(),
                    "terminal authentication failures must stop background reconnects");
        } finally {
            transport.close();
        }
    }

    @Test
    void missingRequiredCapabilityStopsBackgroundReconnectStorm() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.omitConfigSnapshotCapability = true;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");

            assertEquals(0, transport.activeSessionCount());
            assertEquals(2, transport.openedUrls.size(),
                    "the client may check one standby in the same compatibility pool");
            Thread.sleep(1_200L);
            assertEquals(2, transport.openedUrls.size(),
                    "a Gateway without the required State capability must not cause reconnects");
        } finally {
            transport.close();
        }
    }

    @Test
    void rateLimitedHelloDoesNotImmediatelyAmplifyToAnotherGateway() {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.rejectHelloRateLimited = true;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "secret-token");

            assertEquals(0, transport.activeSessionCount());
            assertEquals(1, transport.openedUrls.size(),
                    "RATE_LIMITED Hello must end the current deadline without failover");
        } finally {
            transport.close();
        }
    }

    @Test
    void rateLimitedRequestDoesNotFailOverOrRetireTheHealthyGateway() {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.rateLimitFetchOnGatewayZero = true;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "secret-token");

            assertNull(transport.fetch("app.yml", 0L));
            assertEquals(1, transport.fetchAttempts.get());
            assertEquals(1, transport.openedUrls.size(),
                    "RATE_LIMITED must not amplify traffic to another Gateway");
            assertEquals(1, transport.activeSessionCount());
            assertEquals(0, transport.activeGatewayIndex());
        } finally {
            transport.close();
        }
    }

    @Test
    void nonRetryableBusinessStatusDoesNotRetireTheHealthyGateway() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.rejectFetchInvalidArgument = true;
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");

            assertNull(transport.fetch("app.yml", 0L));
            assertEquals(1, transport.activeSessionCount());
            assertEquals(0, transport.activeGatewayIndex());
            Thread.sleep(1_200L);
            assertEquals(1, transport.openedUrls.size(),
                    "INVALID_ARGUMENT is a healthy RPC response, not a reconnect signal");
        } finally {
            transport.close();
        }
    }

    @Test
    void automaticFailoverCannotCrossTransportGeneration() {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.failFetchOnGatewayZero = true;
        transport.gatewayOneGeneration = 2L;
        try {
            transport.connect(
                    Arrays.asList("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");

            assertNull(transport.fetch("app.yml", 0L));
            assertEquals(2, transport.openedUrls.size());
            assertEquals(0, transport.activeSessionCount());
        } finally {
            transport.close();
        }
    }

    @Test
    void rejectsLegacyBridgeAddressInsteadOfSilentlyAppendingNewPath() {
        FakeConfigTransport transport = new FakeConfigTransport();
        assertThrows(IllegalArgumentException.class, () -> transport.connect(
                List.of("sd:ws://gateway-a:8088/config-v2"),
                "public", "DEFAULT_GROUP", ""));
    }

    @Test
    void closingDeadlineReplacesPreclosedSessionWhenFinalCloseIsLost() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");
            transport.precloseGateway(0);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4L);
            while (transport.activeGatewayIndex() != 1 && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(1, transport.activeGatewayIndex());
            assertFalse(transport.sessionControls.get(0).valid,
                    "closing deadline must force final close");
        } finally {
            transport.close();
        }
    }

    @Test
    void transparentSocketdReconnectMustRepeatHelloBeforeBusinessTraffic() throws Exception {
        FakeConfigTransport transport = new FakeConfigTransport();
        try {
            transport.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "");
            transport.simulateSocketdReconnect(0);

            ConfigSnapshot snapshot = transport.fetch("app.yml", 0L);
            assertNotNull(snapshot);
            assertEquals("from-gateway-1", snapshot.getContent());
            assertEquals(1, transport.fetchAttempts.get(),
                    "business request must not reach the reconnected session before Hello");
            assertEquals(1, transport.activeGatewayIndex());
        } finally {
            transport.close();
        }
    }

    @Test
    void discoveryUsesTheSameSingleActiveGatewayAndSequentialFailover() {
        FakeConfigTransport control = new FakeConfigTransport();
        control.failDiscoveryOnGatewayZero = true;
        SocketDDiscoveryTransport discovery = new SocketDDiscoveryTransport(control, 30_000L);
        try {
            discovery.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "orders", "");

            ServiceSnapshot snapshot = discovery.fetchInstances();

            assertEquals(1, snapshot.getInstances().size());
            assertEquals("from-gateway-1", snapshot.getInstances().getFirst().getInstanceId());
            assertEquals(1, control.activeSessionCount());
            assertEquals(1, control.activeGatewayIndex());
        } finally {
            discovery.close();
        }
    }

    @Test
    void storageRejectedDiscoveryWriteRetriesOnceWithTheSameOperationId() {
        FakeConfigTransport control = new FakeConfigTransport();
        control.storageExhaustedDiscoveryOnGatewayZero = true;
        SocketDDiscoveryTransport discovery = new SocketDDiscoveryTransport(control, 30_000L);
        try {
            discovery.connect(
                    List.of("gateway-a:8090", "gateway-b:8090"),
                    "public", "DEFAULT_GROUP", "orders", "");
            ServiceInstance registration = new ServiceInstance();
            registration.setInstanceId("orders-storage-retry");
            registration.setServiceGeneration(7L);
            registration.setIp("10.0.0.9");
            registration.setPort(8080);
            registration.setWeight(1D);
            registration.setEnabled(true);

            ServiceInstance registered = discovery.register(registration);

            assertEquals("orders-storage-retry", registered.getInstanceId());
            assertEquals(2, control.discoveryMutationAttempts.get());
            assertEquals(2, control.discoveryOperationIds.size());
            assertFalse(control.discoveryOperationIds.getFirst().isBlank());
            assertEquals(control.discoveryOperationIds.getFirst(),
                    control.discoveryOperationIds.getLast());
            assertEquals(1, control.activeGatewayIndex());
        } finally {
            discovery.close();
        }
    }

    @Test
    void discoveryLeaseRecoveryCarriesTheCommittedServiceGeneration() {
        FakeConfigTransport control = new FakeConfigTransport();
        SocketDDiscoveryTransport discovery = new SocketDDiscoveryTransport(control, 30_000L);
        try {
            discovery.connect(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "orders", "");
            ServiceInstance registration = new ServiceInstance();
            registration.setInstanceId("orders-1");
            registration.setServiceGeneration(7L);
            registration.setIp("10.0.0.8");
            registration.setPort(8080);
            registration.setWeight(1D);
            registration.setEnabled(true);

            ServiceInstance registered = discovery.register(registration);

            assertEquals(7L, control.lastExpectedServiceGeneration);
            assertEquals(7L, registered.getServiceGeneration());
        } finally {
            discovery.close();
        }
    }

    @Test
    void discoveryRenewalReturnsAuthoritativeServerCommitTime() {
        FakeConfigTransport control = new FakeConfigTransport();
        SocketDDiscoveryTransport discovery = new SocketDDiscoveryTransport(control, 30_000L);
        try {
            discovery.connect(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "orders", "");
            ServiceInstance registration = new ServiceInstance();
            registration.setInstanceId("orders-1");
            registration.setIp("10.0.0.8");
            registration.setPort(8080);
            registration.setWeight(1D);
            registration.setEnabled(true);
            ServiceInstance registered = discovery.register(registration);

            LeaseRenewalResult renewed = discovery.heartbeatResult(registered);

            assertEquals(110_000L, renewed.serverTimeEpochMs());
            assertEquals(140_000L, renewed.instance().getExpiresAt());
            assertEquals(1L, renewed.instance().getRenewSequence());
        } finally {
            discovery.close();
        }
    }

    private static final class FakeConfigTransport extends SocketDTransport {
        private final Map<ClientSession, Integer> gatewayIndexes =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final AtomicInteger fetchAttempts = new AtomicInteger();
        private final AtomicInteger discoveryMutationAttempts = new AtomicInteger();
        private final AtomicInteger watchAcknowledgements = new AtomicInteger();
        private final List<String> openedUrls = new CopyOnWriteArrayList<>();
        private final List<String> discoveryOperationIds = new CopyOnWriteArrayList<>();
        private final Map<Integer, SessionControl> sessionControls =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Integer, EventListener> sessionListeners =
                new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean failFetchOnGatewayZero;
        private volatile boolean failDiscoveryOnGatewayZero;
        private volatile boolean storageExhaustedDiscoveryOnGatewayZero;
        private volatile boolean failOpenGatewayZero;
        private volatile boolean rejectHelloUnauthorized;
        private volatile boolean rejectHelloRateLimited;
        private volatile boolean rateLimitFetchOnGatewayZero;
        private volatile boolean rejectFetchInvalidArgument;
        private volatile boolean tombstoneConfig;
        private volatile boolean omitConfigSnapshotCapability;
        private volatile long gatewayOneGeneration = 1L;
        private volatile String helloClientInstanceId;
        private volatile String helloApplicationName;
        private volatile String helloGroupName;
        private volatile String helloCredential;
        private volatile long lastExpectedServiceGeneration;

        private FakeConfigTransport() {
            super(
                    new ClientIdentity("test-app", "test-client"),
                    new ControlPlaneOptions(
                            "default", "config-default", "", 0L, "tcp-default",
                            500L, 500L, 1_500L, 100L));
        }

        private void precloseGateway(int gatewayIndex) {
            sessionControls.get(gatewayIndex).closing = true;
        }

        private void simulateSocketdReconnect(int gatewayIndex) throws Exception {
            sessionListeners.get(gatewayIndex).onOpen(
                    new SessionControl("reconnected-real-" + gatewayIndex).session());
        }

        private void emitConfigWatch(
                int gatewayIndex,
                long requested,
                long covered,
                boolean reset) throws Exception {
            SessionControl control = sessionControls.get(gatewayIndex);
            Envelope request = control.subscribeEnvelope;
            ConfigWatchBatchResponse response = ConfigWatchBatchResponse.newBuilder()
                    .setRequestedAfterRevision(requested)
                    .setCoveredThroughRevision(covered)
                    .setResetRequired(reset)
                    .build();
            Envelope wire = ok(
                    request,
                    gatewayIndex == 1 ? gatewayOneGeneration : 1L,
                    ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE,
                    response.toByteString());
            control.subscribeStream.emit(wire.toByteArray(), reset);
        }

        @Override
        protected ClientSession openSession(
                int gatewayIndex,
                String url,
                EventListener listener,
                long connectTimeoutMs) {
            openedUrls.add(url);
            if (gatewayIndex == 0 && failOpenGatewayZero) {
                throw new SocketDTimeoutException("simulated connect timeout");
            }
            sessionListeners.put(gatewayIndex, listener);
            SessionControl control = new SessionControl("config-" + gatewayIndex);
            sessionControls.put(gatewayIndex, control);
            ClientSession session = control.session();
            gatewayIndexes.put(session, gatewayIndex);
            return session;
        }

        @Override
        protected Envelope request(
                ClientSession session, String event, Envelope request, long timeoutMs)
                throws Exception {
            int gatewayIndex = gatewayIndexes.get(session);
            long generation = gatewayIndex == 1 ? gatewayOneGeneration : 1L;
            if (ControlPlaneProtocol.SYSTEM_HELLO.equals(event)) {
                HelloRequest hello = HelloRequest.parseFrom(request.getPayload());
                helloClientInstanceId = hello.getClientInstanceId();
                helloApplicationName = hello.getApplicationName();
                helloGroupName = hello.getGroupName();
                helloCredential = hello.getCredential();
                if (rejectHelloUnauthorized) {
                    return request.toBuilder()
                            .setClusterId("cluster-test")
                            .setTransportGeneration(generation)
                            .setResponseStatus(ResponseStatus.newBuilder()
                                    .setCode(ResponseCode.UNAUTHORIZED)
                                    .setMessage("invalid credential")
                                    .setRetryable(false))
                            .build();
                }
                if (rejectHelloRateLimited) {
                    return request.toBuilder()
                            .setClusterId("cluster-test")
                            .setTransportGeneration(generation)
                            .setResponseStatus(ResponseStatus.newBuilder()
                                    .setCode(ResponseCode.RATE_LIMITED)
                                    .setMessage("credential session quota")
                                    .setRetryable(true)
                                    .setRetryAfterMs(250L))
                            .build();
                }
                HelloResponse.Builder response = HelloResponse.newBuilder()
                        .setSelectedProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                        .setClusterId("cluster-test")
                        .setTransportGeneration(generation)
                        .setGatewayId("gateway-" + gatewayIndex)
                        .setConnectionGeneration(gatewayIndex + 1L)
                        .setMaxRequestBudgetMs(10_000L)
                        .setTransportSchema("sd:tcp")
                        .addAllCapabilities(List.of(
                                "config-fetch-v1",
                                "config-watch-batch-v1",
                                "config-watch-stream-v1",
                                "discovery-lease-v1",
                                "discovery-snapshot-v1",
                                "discovery-watch-batch-v1",
                                "discovery-watch-stream-v1",
                                "watch-ack-v1"));
                if (!omitConfigSnapshotCapability) {
                    response.addCapabilities("config-snapshot-v1");
                }
                return ok(request, generation,
                        ControlPlaneProtocol.HELLO_RESPONSE_TYPE,
                        response.build().toByteString());
            }
            if (ControlPlaneProtocol.SYSTEM_PROBE.equals(event)) {
                ProbeRequest probe = ProbeRequest.parseFrom(request.getPayload());
                long receivedAt = System.currentTimeMillis();
                ProbeResponse response = ProbeResponse.newBuilder()
                        .setNonce(probe.getNonce())
                        .setGatewayId("gateway-" + gatewayIndex)
                        .setConnectionGeneration(gatewayIndex + 1L)
                        .setServerReceiveEpochMs(receivedAt)
                        .setServerSendEpochMs(System.currentTimeMillis())
                        .build();
                return ok(request, generation,
                        ControlPlaneProtocol.PROBE_RESPONSE_TYPE,
                        response.toByteString());
            }
            if (ControlPlaneProtocol.SYSTEM_WATCH_ACK.equals(event)) {
                WatchAckRequest acknowledgement = WatchAckRequest.parseFrom(
                        request.getPayload());
                watchAcknowledgements.incrementAndGet();
                WatchAckResponse response = WatchAckResponse.newBuilder()
                        .setSubscriptionRequestId(
                                acknowledgement.getSubscriptionRequestId())
                        .setAcceptedRevision(acknowledgement.getCommittedRevision())
                        .build();
                return ok(request, generation,
                        ControlPlaneProtocol.WATCH_ACK_RESPONSE_TYPE,
                        response.toByteString());
            }
            if (ControlPlaneProtocol.CONFIG_FETCH.equals(event)) {
                fetchAttempts.incrementAndGet();
                if (rejectFetchInvalidArgument) {
                    return request.toBuilder()
                            .setClusterId("cluster-test")
                            .setTransportGeneration(generation)
                            .setResponseStatus(ResponseStatus.newBuilder()
                                    .setCode(ResponseCode.INVALID_ARGUMENT)
                                    .setMessage("unsupported config request")
                                    .setRetryable(false))
                            .build();
                }
                if (gatewayIndex == 0 && failFetchOnGatewayZero) {
                    throw new SocketDTimeoutException("simulated response timeout");
                }
                if (gatewayIndex == 0 && rateLimitFetchOnGatewayZero) {
                    return request.toBuilder()
                            .setClusterId("cluster-test")
                            .setTransportGeneration(generation)
                            .setResponseStatus(ResponseStatus.newBuilder()
                                    .setCode(ResponseCode.RATE_LIMITED)
                                    .setMessage("tenant rate limit")
                                    .setRetryable(true)
                                    .setRetryAfterMs(250L))
                            .build();
                }
                ConfigFetchRequest fetch = ConfigFetchRequest.parseFrom(request.getPayload());
                if (tombstoneConfig) {
                    ConfigFetchResponse response = ConfigFetchResponse.newBuilder()
                            .setState(ConfigValueState.CONFIG_VALUE_STATE_TOMBSTONE)
                            .setConfig(ConfigCoordinate.newBuilder()
                                    .setNamespaceId(request.getNamespaceId())
                                    .setGroupName(fetch.getGroupName())
                                    .setDataId(fetch.getDataId()))
                            .setDecisionRevision(8L)
                            .build();
                    return ok(request, generation,
                            ControlPlaneProtocol.CONFIG_FETCH_RESPONSE_TYPE,
                            response.toByteString());
                }
                ConfigFetchResponse response = ConfigFetchResponse.newBuilder()
                        .setState(ConfigValueState.CONFIG_VALUE_STATE_ACTIVE)
                        .setConfig(ConfigCoordinate.newBuilder()
                                .setNamespaceId(request.getNamespaceId())
                                .setGroupName(fetch.getGroupName())
                                .setDataId(fetch.getDataId()))
                        .setDecisionRevision(7L)
                        .setContent(ConfigContentValue.newBuilder()
                                .setContentRevision(7L)
                                .setContentHash("sum-7")
                                .setContentType("text")
                                .setPayload(ByteString.copyFromUtf8(
                                        "from-gateway-" + gatewayIndex)))
                        .build();
                return ok(request, generation,
                        ControlPlaneProtocol.CONFIG_FETCH_RESPONSE_TYPE,
                        response.toByteString());
            }
            if (ControlPlaneProtocol.DISCOVERY_SNAPSHOT.equals(event)) {
                if (gatewayIndex == 0 && failDiscoveryOnGatewayZero) {
                    throw new SocketDTimeoutException("simulated discovery timeout");
                }
                DiscoverySnapshotRequest snapshot = DiscoverySnapshotRequest.parseFrom(
                        request.getPayload());
                DiscoverySnapshotResponse response = DiscoverySnapshotResponse.newBuilder()
                        .setRegistryRevision(3L)
                        .addInstances(DiscoveryServiceInstance.newBuilder()
                                .setInstance(ServiceInstanceCoordinate.newBuilder()
                                        .setService(ServiceCoordinate.newBuilder()
                                                .setNamespaceId(request.getNamespaceId())
                                                .setGroupName(snapshot.getGroupName())
                                                .setServiceName(snapshot.getServiceNames(0)))
                                        .setInstanceId("from-gateway-" + gatewayIndex))
                                .setIp("10.0.0.8")
                                .setPort(8080)
                                .setWeight(1D)
                                .setEnabled(true))
                        .build();
                return ok(request, generation,
                        ControlPlaneProtocol.DISCOVERY_SNAPSHOT_RESPONSE_TYPE,
                        response.toByteString());
            }
            if (ControlPlaneProtocol.DISCOVERY_REGISTER.equals(event)) {
                discoveryMutationAttempts.incrementAndGet();
                discoveryOperationIds.add(request.getOperationId());
                if (gatewayIndex == 0 && storageExhaustedDiscoveryOnGatewayZero) {
                    return request.toBuilder()
                            .setClusterId("cluster-test")
                            .setTransportGeneration(generation)
                            .setResponseStatus(ResponseStatus.newBuilder()
                                    .setCode(ResponseCode.STORAGE_EXHAUSTED)
                                    .setMessage("storage watermark reached")
                                    .setRetryable(true)
                                    .setCommitStatus(CommitStatus.NOT_COMMITTED))
                            .build();
                }
                DiscoveryRegisterRequest register = DiscoveryRegisterRequest.parseFrom(
                        request.getPayload());
                lastExpectedServiceGeneration = register.getExpectedServiceGeneration();
                DiscoveryMutationResponse response = DiscoveryMutationResponse.newBuilder()
                        .setAction("REGISTER")
                        .setRegistryRevision(4L)
                        .setServerTimeEpochMs(100_000L)
                        .addInstances(DiscoveryServiceInstance.newBuilder()
                                .setInstance(ServiceInstanceCoordinate.newBuilder()
                                        .setService(ServiceCoordinate.newBuilder()
                                                .setNamespaceId(request.getNamespaceId())
                                                .setGroupName(register.getGroupName())
                                                .setServiceName(register.getServiceName()))
                                        .setInstanceId(register.getInstanceId()))
                                .setServiceGeneration(7L)
                                .setIp(register.getIp())
                                .setPort(register.getPort())
                                .setWeight(register.getWeight())
                                .setEnabled(register.getEnabled())
                                .setLeaseId("lease-orders-1")
                                .setLeaseEpoch(1L)
                                .setRecoveryEpoch(1L)
                                .setRenewSequence(0L)
                                .setRegisteredAtEpochMs(100_000L)
                                .setLastRenewedAtEpochMs(100_000L)
                                .setExpiresAtEpochMs(130_000L))
                        .build();
                return ok(request, generation,
                        ControlPlaneProtocol.DISCOVERY_MUTATION_RESPONSE_TYPE,
                        response.toByteString());
            }
            if (ControlPlaneProtocol.DISCOVERY_RENEW_BATCH.equals(event)) {
                DiscoveryRenewBatchRequest renew = DiscoveryRenewBatchRequest.parseFrom(
                        request.getPayload());
                var renewal = renew.getRenewals(0);
                var coordinate = renewal.getLease().getInstance();
                DiscoveryMutationResponse response = DiscoveryMutationResponse.newBuilder()
                        .setAction("RENEW")
                        .setRegistryRevision(5L)
                        .setServerTimeEpochMs(110_000L)
                        .addInstances(DiscoveryServiceInstance.newBuilder()
                                .setInstance(coordinate)
                                .setServiceGeneration(7L)
                                .setIp("10.0.0.8")
                                .setPort(8080)
                                .setWeight(1D)
                                .setEnabled(true)
                                .setLeaseId(renewal.getLease().getLeaseId())
                                .setLeaseEpoch(renewal.getLease().getLeaseEpoch())
                                .setRecoveryEpoch(renewal.getLease().getRecoveryEpoch())
                                .setRenewSequence(renewal.getRenewSequence())
                                .setRegisteredAtEpochMs(100_000L)
                                .setLastRenewedAtEpochMs(110_000L)
                                .setExpiresAtEpochMs(140_000L))
                        .build();
                return ok(request, generation,
                        ControlPlaneProtocol.DISCOVERY_MUTATION_RESPONSE_TYPE,
                        response.toByteString());
            }
            throw new UnsupportedOperationException(event);
        }

        private Envelope ok(
                Envelope request,
                long generation,
                String payloadType,
                ByteString payload) {
            return request.toBuilder()
                    .setClusterId("cluster-test")
                    .setTransportGeneration(generation)
                    .setPayloadType(payloadType)
                    .setPayload(payload)
                    .setResponseStatus(ResponseStatus.newBuilder()
                            .setCode(ResponseCode.OK)
                            .setMessage("OK"))
                    .build();
        }
    }

    private static ClientSession fakeSession(final String sessionId) {
        return new SessionControl(sessionId).session();
    }

    private static final class SessionControl implements InvocationHandler {
        private final String sessionId;
        private volatile boolean valid = true;
        private volatile boolean closing;
        private final AtomicInteger subscribeCount = new AtomicInteger();
        private volatile Envelope subscribeEnvelope;
        private volatile TestSubscribeStream subscribeStream;

        private SessionControl(String sessionId) {
            this.sessionId = sessionId;
        }

        private Session session() {
            return (Session) Proxy.newProxyInstance(
                    ClientSession.class.getClassLoader(),
                    new Class<?>[]{Session.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            String name = method.getName();
            if ("isValid".equals(name)) return valid;
            if ("isActive".equals(name)) return valid && !closing;
            if ("isClosing".equals(name)) return closing;
            if ("sessionId".equals(name)) return sessionId;
            if ("preclose".equals(name) || "closeStarting".equals(name)) {
                closing = true;
                return null;
            }
            if ("close".equals(name)) {
                valid = false;
                closing = false;
                return null;
            }
            if ("closeCode".equals(name)) return 0;
            if ("sendAndSubscribe".equals(name)) {
                subscribeEnvelope = Envelope.parseFrom(((Entity) args[1]).dataAsBytes());
                subscribeStream = new TestSubscribeStream();
                subscribeCount.incrementAndGet();
                return subscribeStream;
            }
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == args[0];
            if ("toString".equals(name)) return sessionId;
            throw new UnsupportedOperationException(name);
        }
    }

    private static final class TestSubscribeStream implements SubscribeStream {
        private volatile IoConsumer<Reply> replyConsumer;
        private volatile Consumer<Throwable> errorConsumer;
        private volatile boolean done;

        @Override
        public String sid() {
            return "watch-stream";
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public SubscribeStream thenReply(IoConsumer<Reply> onReply) {
            replyConsumer = onReply;
            return this;
        }

        @Override
        public SubscribeStream thenError(Consumer<Throwable> onError) {
            errorConsumer = onError;
            return this;
        }

        @Override
        public SubscribeStream thenProgress(
                TriConsumer<Boolean, Integer, Integer> onProgress) {
            return this;
        }

        private void emit(byte[] bytes, boolean end) throws Exception {
            done = end;
            Reply reply = (Reply) Proxy.newProxyInstance(
                    Reply.class.getClassLoader(),
                    new Class<?>[]{Reply.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "dataAsBytes" -> bytes;
                        case "isEnd" -> end;
                        case "sid" -> "watch-stream";
                        case "dataSize" -> bytes.length;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            IoConsumer<Reply> consumer = replyConsumer;
            if (consumer == null) {
                throw new IllegalStateException("Subscribe callback is not installed");
            }
            consumer.accept(reply);
        }
    }
}
