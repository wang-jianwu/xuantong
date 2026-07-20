package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.client.transport.impl.SocketDTransport;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the production Socket.D TCP path when every client loses the same active
 * Gateway at once. The clients must switch sequentially to one compatible standby,
 * restore their Watch cursor, and avoid opening or sending to every address eagerly.
 */
class ControlPlaneMultiGatewayReconnectStormTest {
    private static final int CLIENTS = 12;
    private static final StateGroupId CONFIG_GROUP =
            StateGroupId.config("config-default");

    @TempDir
    Path tempDirectory;

    @Test
    void reconnectStormSurvivesRepeatedGatewayFlappingWithoutFanOut()
            throws Exception {
        List<Integer> ports = freePorts(3);
        int statePort = ports.get(0);
        int gatewayAPort = ports.get(1);
        int gatewayBPort = ports.get(2);
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        ConfigStatePlaneProperties stateProperties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                CONFIG_GROUP.value(),
                "state-1@127.0.0.1:" + statePort,
                tempDirectory.resolve("state"),
                true);
        ControlStatePlaneRuntime stateRuntime = new ControlStatePlaneRuntime(
                stateProperties, dispatcher);
        GatewayHarness gatewayA = gateway(
                "gateway-reconnect-a", gatewayAPort, dispatcher);
        GatewayHarness gatewayB = gateway(
                "gateway-reconnect-b", gatewayBPort, dispatcher);
        List<CountingSocketDTransport> transports = new ArrayList<>();
        List<WatchSubscription> subscriptions = new ArrayList<>();
        try {
            stateRuntime.start();
            ConfigKey key = new ConfigKey(
                    "public", "DEFAULT_GROUP", "reconnect.value");
            assertEquals(ApplyStatus.APPLIED,
                    submitEventually(stateRuntime, mutation(key, 1L, "value-v1"))
                            .status());
            gatewayA.start();
            gatewayB.start();

            List<String> addresses = List.of(
                    "127.0.0.1:" + gatewayAPort,
                    "127.0.0.1:" + gatewayBPort);
            ControlPlaneOptions options = new ControlPlaneOptions(
                    "default",
                    CONFIG_GROUP.value(),
                    "cluster-reconnect-test",
                    1L,
                    "tcp-default",
                    1_000L,
                    2_000L,
                    4_000L,
                    500L);
            AtomicLongArray appliedRevisions = new AtomicLongArray(CLIENTS);
            List<AtomicInteger> reconnectCallbacks = new ArrayList<>();
            for (int index = 0; index < CLIENTS; index++) {
                int clientIndex = index;
                CountingSocketDTransport transport = new CountingSocketDTransport(
                        new ClientIdentity(
                                "reconnect-client",
                                "reconnect-client@" + index),
                        options);
                AtomicInteger reconnects = new AtomicInteger();
                transport.setOnReconnect(reconnects::incrementAndGet);
                transport.connect(
                        addresses, key.namespace(), key.group(), "");
                ConfigSnapshot initial = transport.fetch(key.dataId(), 0L);
                assertNotNull(initial);
                assertEquals(1L, initial.getRevision());
                WatchSubscription subscription = transport.subscribe(1L, batch -> {
                    if (!batch.events().isEmpty()) {
                        appliedRevisions.accumulateAndGet(
                                clientIndex,
                                batch.coveredThroughRevision(),
                                Math::max);
                    }
                    return batch.coveredThroughRevision();
                });
                transports.add(transport);
                subscriptions.add(subscription);
                reconnectCallbacks.add(reconnects);
            }

            awaitTrue(() -> gatewayA.runtime.activeSessions() == CLIENTS
                            && gatewayA.runtime.activeSubscriptions() == CLIENTS,
                    Duration.ofSeconds(10));
            assertEquals(0, gatewayB.runtime.activeSessions());
            assertEquals(0L, gatewayB.runtime.requestAcceptedTotal(),
                    "The standby Gateway must not receive eager connections or request fan-out");
            for (CountingSocketDTransport transport : transports) {
                assertEquals(1, transport.openAttempts(0));
                assertEquals(0, transport.openAttempts(1));
            }

            gatewayA.stop();
            awaitTrue(() -> gatewayB.runtime.activeSessions() == CLIENTS
                            && gatewayB.runtime.activeSubscriptions() == CLIENTS
                            && transports.stream().allMatch(
                                    transport -> transport.openAttempts(1) == 1),
                    Duration.ofSeconds(20));
            for (int index = 0; index < CLIENTS; index++) {
                assertEquals(1, transports.get(index).openAttempts(0),
                        "A stopped Gateway must not be retried before the healthy standby");
                assertEquals(1, transports.get(index).openAttempts(1),
                        "Each client must open exactly one standby Session");
                assertEquals(1, reconnectCallbacks.get(index).get(),
                        "One physical failover must produce one logical reconnect callback");
            }

            assertEquals(ApplyStatus.APPLIED,
                    submitEventually(stateRuntime, mutation(key, 2L, "value-v2"))
                            .status());
            awaitApplied(appliedRevisions, 2L);
            awaitGatewayIdle(gatewayB.runtime);
            assertSingleFetchPerClient(
                    gatewayB.runtime, transports, key, 2L, "value-v2");

            gatewayA.start();
            gatewayB.stop();
            awaitTrue(() -> gatewayA.runtime.activeSessions() == CLIENTS
                            && gatewayA.runtime.activeSubscriptions() == CLIENTS
                            && transports.stream().allMatch(
                                    transport -> transport.openAttempts(0) == 2),
                    Duration.ofSeconds(20));
            assertRoutingAttempts(transports, reconnectCallbacks, 2, 1, 2);
            assertEquals(ApplyStatus.APPLIED,
                    submitEventually(stateRuntime, mutation(key, 3L, "value-v3"))
                            .status());
            awaitApplied(appliedRevisions, 3L);
            awaitGatewayIdle(gatewayA.runtime);
            assertSingleFetchPerClient(
                    gatewayA.runtime, transports, key, 3L, "value-v3");

            gatewayB.start();
            gatewayA.stop();
            awaitTrue(() -> gatewayB.runtime.activeSessions() == CLIENTS
                            && gatewayB.runtime.activeSubscriptions() == CLIENTS
                            && transports.stream().allMatch(
                                    transport -> transport.openAttempts(1) == 2),
                    Duration.ofSeconds(20));
            assertRoutingAttempts(transports, reconnectCallbacks, 2, 2, 3);
            assertEquals(ApplyStatus.APPLIED,
                    submitEventually(stateRuntime, mutation(key, 4L, "value-v4"))
                            .status());
            awaitApplied(appliedRevisions, 4L);
            awaitGatewayIdle(gatewayB.runtime);
            assertSingleFetchPerClient(
                    gatewayB.runtime, transports, key, 4L, "value-v4");

            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
            for (CountingSocketDTransport transport : transports) {
                transport.close();
            }
            transports.clear();
            awaitTrue(() -> gatewayB.runtime.activeSessions() == 0
                            && gatewayB.runtime.activeSubscriptions() == 0
                            && gatewayB.runtime.pendingWatchAcknowledgements() == 0
                            && gatewayB.runtime.inFlightRequests() == 0
                            && gatewayB.runtime.requestAcceptedTotal()
                            == gatewayB.runtime.requestCompletedTotal(),
                    Duration.ofSeconds(15));
            assertEquals(gatewayB.runtime.sessionOpenedTotal(),
                    gatewayB.runtime.sessionClosedTotal());
            assertEquals(gatewayB.runtime.subscriptionOpenedTotal(),
                    gatewayB.runtime.subscriptionClosedTotal());
            assertEquals(gatewayA.runtime.sessionOpenedTotal(),
                    gatewayA.runtime.sessionClosedTotal());
            assertEquals(gatewayA.runtime.subscriptionOpenedTotal(),
                    gatewayA.runtime.subscriptionClosedTotal());
        } finally {
            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            for (CountingSocketDTransport transport : transports) {
                transport.close();
            }
            gatewayA.stop();
            gatewayB.stop();
            stateRuntime.stop();
        }
    }

    private void awaitApplied(AtomicLongArray revisions, long expectedRevision)
            throws Exception {
        awaitTrue(() -> {
            for (int index = 0; index < revisions.length(); index++) {
                if (revisions.get(index) < expectedRevision) {
                    return false;
                }
            }
            return true;
        }, Duration.ofSeconds(15));
    }

    private void awaitGatewayIdle(ControlPlaneGatewayRuntime runtime)
            throws Exception {
        awaitTrue(() -> runtime.pendingWatchAcknowledgements() == 0
                        && runtime.requestAcceptedTotal()
                        == runtime.requestCompletedTotal(),
                Duration.ofSeconds(5));
    }

    private void assertSingleFetchPerClient(
            ControlPlaneGatewayRuntime runtime,
            List<CountingSocketDTransport> transports,
            ConfigKey key,
            long revision,
            String expectedValue) {
        long acceptedBeforeFetch = runtime.requestAcceptedTotal();
        for (CountingSocketDTransport transport : transports) {
            ConfigSnapshot current = transport.fetch(key.dataId(), revision);
            assertNotNull(current);
            assertEquals(revision, current.getRevision());
            assertEquals(expectedValue, current.getContent());
        }
        assertEquals(CLIENTS,
                runtime.requestAcceptedTotal() - acceptedBeforeFetch,
                "One logical Fetch must produce exactly one Gateway request");
    }

    private void assertRoutingAttempts(
            List<CountingSocketDTransport> transports,
            List<AtomicInteger> reconnectCallbacks,
            int expectedGatewayAAttempts,
            int expectedGatewayBAttempts,
            int expectedReconnects) {
        for (int index = 0; index < CLIENTS; index++) {
            assertEquals(expectedGatewayAAttempts,
                    transports.get(index).openAttempts(0));
            assertEquals(expectedGatewayBAttempts,
                    transports.get(index).openAttempts(1));
            assertEquals(expectedReconnects,
                    reconnectCallbacks.get(index).get());
        }
    }

    private GatewayHarness gateway(
            String gatewayId,
            int port,
            ControlPlaneRequestDispatcher dispatcher) {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1",
                port,
                "cluster-reconnect-test",
                gatewayId,
                1L,
                10_000L,
                256,
                4,
                1_024,
                500L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime, dispatcher));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, null);
        return new GatewayHarness(runtime, server);
    }

    private StateCommand mutation(ConfigKey key, long revision, String value) {
        return ConfigStateCodec.mutationCommand(
                CONFIG_GROUP,
                "reconnect-publish-" + revision,
                new ConfigMutation(
                        new ConfigActor("default", "reconnect-test"),
                        key,
                        revision - 1L,
                        ConfigContentDraft.inline(
                                "text",
                                Math.toIntExact(revision),
                                value.getBytes(StandardCharsets.UTF_8)),
                        ConfigContentReference.newContent(),
                        List.of()));
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
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException("Config State did not elect a leader")
                : last;
    }

    private void awaitTrue(Check condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25L);
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

    private static final class CountingSocketDTransport extends SocketDTransport {
        private final AtomicIntegerArray openAttempts = new AtomicIntegerArray(2);

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
            return super.openSession(
                    gatewayIndex, url, listener, connectTimeoutMs);
        }

        private int openAttempts(int gatewayIndex) {
            return openAttempts.get(gatewayIndex);
        }
    }
}
