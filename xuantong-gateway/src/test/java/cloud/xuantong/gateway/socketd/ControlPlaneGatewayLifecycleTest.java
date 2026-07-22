package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ResponseCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.net.ServerSocket;
import java.net.SocketException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneGatewayLifecycleTest {
    @Test
    void tracksUnifiedControlPlaneConnectionIdentity() {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("orders@node-1")
                .setApplicationName("orders")
                .setGroupName("DEFAULT_GROUP")
                .setClientVersion("2.0.0")
                .setSdkName("xuantong-client-java")
                .setTransportPool("tcp-default")
                .addCapabilities("config-fetch-v1")
                .addCapabilities("discovery-lease-v1")
                .build();

        runtime.sessionOpened("session-1", 7L, "127.0.0.1");
        runtime.sessionIdentified("session-1", hello);
        runtime.recordConfigSelection(
                "session-1", "public", "DEFAULT_GROUP", "app.yml",
                "ACTIVE", 8L, 5L, "rule-gray-client");

        assertEquals(1, runtime.activeSessions());
        assertEquals(1, runtime.logicalClients());
        ControlPlaneGatewayRuntime.ControlPlaneConnectionView view =
                runtime.connections().getFirst();
        assertEquals("orders@node-1", view.clientInstanceId());
        assertEquals("orders", view.applicationName());
        assertEquals("xuantong-client-java", view.sdkName());
        assertEquals("gateway-test", view.gatewayId());
        assertEquals(7L, view.connectionGeneration());
        assertEquals(2, view.capabilities().size());
        assertEquals("app.yml", view.lastConfigSelection().dataId());
        assertEquals("ACTIVE", view.lastConfigSelection().valueState());
        assertEquals(8L, view.lastConfigSelection().decisionRevision());
        assertEquals(5L, view.lastConfigSelection().contentRevision());
        assertEquals("rule-gray-client", view.lastConfigSelection().matchedRuleId());

        runtime.sessionClosed("session-1");
        assertEquals(0, runtime.activeSessions());
        assertEquals(0, runtime.logicalClients());
    }

    @Test
    void countsOneLogicalClientAcrossMultiplePhysicalSessions() {
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(
                new ControlPlaneGatewayProperties(
                        "cluster-test", "gateway-test", 1L, 4_000L));
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("orders@node-1")
                .setApplicationName("orders")
                .build();

        runtime.sessionOpened("session-1", 1L, "127.0.0.1");
        runtime.sessionOpened("session-2", 2L, "127.0.0.1");
        runtime.sessionIdentified("session-1", hello);
        runtime.sessionIdentified("session-2", hello);

        assertEquals(1L, runtime.logicalClients());
        runtime.sessionClosed("session-1");
        assertEquals(1L, runtime.logicalClients());
        runtime.sessionClosed("session-2");
        assertEquals(0L, runtime.logicalClients());
    }

    @Test
    void boundedWorkQueueAppliesCallerRunsBackpressureInsteadOfDroppingTasks() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1", port, "cluster-test", "gateway-test", 1L, 4_000L,
                4, 1, 1, 500L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, new PathListenerPlus(true), null);
        server.start();
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<String> callerRunsThread = new AtomicReference<>();
        try {
            server.workExecutor().execute(() -> {
                workerEntered.countDown();
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS));
            server.workExecutor().execute(() -> { });

            String submittingThread = Thread.currentThread().getName();
            server.workExecutor().execute(
                    () -> callerRunsThread.set(Thread.currentThread().getName()));
            assertEquals(submittingThread, callerRunsThread.get());
            assertEquals(1, server.workExecutor().getQueue().remainingCapacity()
                    + server.workExecutor().getQueue().size());
        } finally {
            releaseWorker.countDown();
            server.stop();
        }
    }

    @Test
    void clientAuthWithoutTlsFailsFast() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1", port, "cluster-test", "gateway-test", 1L, 4_000L,
                4, 1, 4, 100L,
                ControlPlaneGatewayProperties.ClientAuth.REQUIRE);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, new PathListenerPlus(true), null);

        IllegalStateException error = assertThrows(IllegalStateException.class, server::start);
        assertTrue(error.getMessage().contains("keyStore"));
        assertTrue(runtime.isDraining());
    }

    @Test
    void productionWithoutApplicationAuthenticationFailsFast() {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L,
                false, 5_000L, true);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, new PathListenerPlus(true), null);

        IllegalStateException error = assertThrows(IllegalStateException.class, server::start);
        assertTrue(error.getMessage().contains("clientAuthRequired"));
        assertTrue(runtime.isDraining());
    }

    @Test
    void overloadAndDrainReturnStructuredRetryableResponses() throws Exception {
        int port = freePort();
        GatewayFixture fixture = startGateway(port, 1, 2_000L);
        ClientSession client = null;
        try {
            client = openClient(port);
            assertEquals(ResponseCode.OK,
                    request(client, ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope())
                            .getResponseStatus().getCode());

            assertEquals(ControlPlaneGatewayRuntime.Admission.ACCEPTED,
                    fixture.runtime().tryAcquireRequest());
            Envelope overloaded = request(client, ControlPlaneProtocol.SYSTEM_PROBE,
                    probeEnvelope());
            assertEquals(ResponseCode.RATE_LIMITED,
                    overloaded.getResponseStatus().getCode());
            assertTrue(overloaded.getResponseStatus().getRetryable());
            assertEquals(100L, overloaded.getResponseStatus().getRetryAfterMs());
            fixture.runtime().releaseRequest();

            fixture.runtime().beginDrain();
            Envelope draining = request(client, ControlPlaneProtocol.SYSTEM_PROBE,
                    probeEnvelope());
            assertEquals(ResponseCode.DRAINING, draining.getResponseStatus().getCode());
            assertTrue(draining.getResponseStatus().getRetryable());
        } finally {
            if (client != null) {
                client.close();
            }
            fixture.server().stop();
        }
    }

    @Test
    void stopPreclosesWaitsForInflightThenFinalCloses() throws Exception {
        int port = freePort();
        GatewayFixture fixture = startGateway(port, 4, 2_000L);
        ClientSession client = openClient(port);
        try {
            assertEquals(ResponseCode.OK,
                    request(client, ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope())
                            .getResponseStatus().getCode());
            assertEquals(ControlPlaneGatewayRuntime.Admission.ACCEPTED,
                    fixture.runtime().tryAcquireRequest());

            Thread stopThread = new Thread(fixture.server()::stop, "gateway-stop-test");
            stopThread.start();
            awaitTrue(fixture.runtime()::isDraining, 2_000L);
            awaitTrue(client::isClosing, 2_000L);
            assertTrue(stopThread.isAlive(), "Final close must wait for admitted work");

            fixture.runtime().releaseRequest();
            stopThread.join(3_000L);
            assertFalse(stopThread.isAlive());
            awaitTrue(() -> !client.isValid(), 2_000L);
        } finally {
            if (client.isValid()) {
                client.close();
            }
            fixture.server().stop();
        }
    }

    @Test
    void drainTimeoutStillForcesBoundedFinalClose() throws Exception {
        int port = freePort();
        GatewayFixture fixture = startGateway(port, 4, 100L);
        ClientSession client = openClient(port);
        try {
            assertEquals(ResponseCode.OK,
                    request(client, ControlPlaneProtocol.SYSTEM_HELLO, helloEnvelope())
                            .getResponseStatus().getCode());
            assertEquals(ControlPlaneGatewayRuntime.Admission.ACCEPTED,
                    fixture.runtime().tryAcquireRequest());

            long started = System.nanoTime();
            fixture.server().stop();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMs < 2_000L, "Drain timeout must bound final close");
            awaitTrue(() -> !client.isValid(), 2_000L);
            fixture.runtime().releaseRequest();
        } finally {
            if (client.isValid()) {
                client.close();
            }
            fixture.server().stop();
        }
    }

    private GatewayFixture startGateway(int port, int maxInFlight, long drainTimeoutMs)
            throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1", port, "cluster-test", "gateway-test", 1L, 4_000L,
                maxInFlight, 2, 8, drainTimeoutMs,
                ControlPlaneGatewayProperties.ClientAuth.NONE);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, null);
        server.start();
        return new GatewayFixture(server, runtime);
    }

    private ClientSession openClient(int port) throws Exception {
        return SocketD.createClient("sd:tcp://127.0.0.1:" + port
                        + ControlPlaneProtocol.CONTROL_PATH)
                .config(config -> config.connectTimeout(3_000L)
                        .requestTimeout(2_000L).autoReconnect(false))
                .openOrThow();
    }

    private Envelope request(ClientSession client, String event, Envelope envelope)
            throws Exception {
        Reply reply = client.sendAndRequest(event, Entity.of(envelope.toByteArray()), 2_000L)
                .await();
        assertTrue(reply.isEnd());
        Envelope response = Envelope.parseFrom(reply.dataAsBytes());
        assertEquals(envelope.getRequestId(), response.getRequestId());
        return response;
    }

    private Envelope helloEnvelope() {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("demo@node-1")
                .setApplicationName("demo")
                .setGroupName("DEFAULT_GROUP")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .build();
        return baseEnvelope()
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope probeEnvelope() {
        return baseEnvelope()
                .setPayloadType(ControlPlaneProtocol.PROBE_REQUEST_TYPE)
                .setPayload(ProbeRequest.newBuilder().setNonce("probe").build().toByteString())
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
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(check.evaluate());
    }

    private record GatewayFixture(
            ControlPlaneGatewayServer server,
            ControlPlaneGatewayRuntime runtime) {
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }
}
