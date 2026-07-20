package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ProbeResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.socketd.transport.server.Server;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.net.ServerSocket;
import java.net.SocketException;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneGatewayNetworkTest {
    private static final AtomicInteger WORKER_IDS = new AtomicInteger();
    private static final ExecutorService SOCKETD_WORK_EXECUTOR =
            Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "gateway-network-test-work-" + WORKER_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    @Test
    void helloAndProbeCompleteOverNativeTcpNettyWithReplyEnd() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 7, 4_000);
        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(properties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH, endpoint);
        Server server = SocketD.createServer("sd:tcp")
                .config(config -> configureServer(config, port))
                .listen(router)
                .start();
        ClientSession client = null;
        try {
            client = SocketD.createClient("sd:tcp://127.0.0.1:" + port
                            + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> configureClient(config)
                            .connectTimeout(3_000L)
                            .requestTimeout(2_000L).autoReconnect(false))
                    .openOrThow();

            Envelope helloResponseEnvelope = request(client, ControlPlaneProtocol.SYSTEM_HELLO,
                    helloEnvelope("cluster-test", 7));
            assertEquals(ResponseCode.OK, helloResponseEnvelope.getResponseStatus().getCode());
            assertEquals(ControlPlaneProtocol.HELLO_RESPONSE_TYPE,
                    helloResponseEnvelope.getPayloadType());
            HelloResponse helloResponse = HelloResponse.parseFrom(helloResponseEnvelope.getPayload());
            assertEquals("cluster-test", helloResponse.getClusterId());
            assertEquals("gateway-test", helloResponse.getGatewayId());
            assertEquals(7, helloResponse.getTransportGeneration());
            assertEquals("sd:tcp", helloResponse.getTransportSchema());
            assertTrue(helloResponse.getConnectionGeneration() > 0);
            assertTrue(helloResponse.getCapabilitiesList().contains("protobuf-envelope-v2"));
            assertFalse(helloResponse.getCapabilitiesList().contains("config-snapshot-v1"),
                    "Gateway must not advertise State handlers that are not registered");

            String nonce = UUID.randomUUID().toString();
            ProbeRequest probe = ProbeRequest.newBuilder()
                    .setNonce(nonce)
                    .setClientSendEpochMs(System.currentTimeMillis())
                    .build();
            Envelope probeRequest = baseEnvelope("cluster-test", 7)
                    .setPayloadType(ControlPlaneProtocol.PROBE_REQUEST_TYPE)
                    .setPayload(probe.toByteString())
                    .build();
            Envelope probeResponseEnvelope = request(client, ControlPlaneProtocol.SYSTEM_PROBE,
                    probeRequest);
            assertEquals(ResponseCode.OK, probeResponseEnvelope.getResponseStatus().getCode());
            ProbeResponse probeResponse = ProbeResponse.parseFrom(probeResponseEnvelope.getPayload());
            assertEquals(nonce, probeResponse.getNonce());
            assertEquals(helloResponse.getConnectionGeneration(),
                    probeResponse.getConnectionGeneration());
        } finally {
            if (client != null) {
                client.close();
            }
            server.prestop();
            server.stop();
        }
    }

    @Test
    void protocolErrorsReturnStructuredReplyInsteadOfTimingOut() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1, 4_000);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties));
        Server server = SocketD.createServer("sd:tcp")
                .config(config -> configureServer(config, port))
                .listen(router)
                .start();
        ClientSession client = null;
        try {
            client = SocketD.createClient("sd:tcp://127.0.0.1:" + port
                            + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> configureClient(config)
                            .connectTimeout(3_000L)
                            .requestTimeout(2_000L).autoReconnect(false))
                    .openOrThow();

            Envelope probeBeforeHello = baseEnvelope("cluster-test", 1)
                    .setPayloadType(ControlPlaneProtocol.PROBE_REQUEST_TYPE)
                    .setPayload(ProbeRequest.newBuilder().setNonce("n-1").build().toByteString())
                    .build();
            Envelope failed = request(client, ControlPlaneProtocol.SYSTEM_PROBE, probeBeforeHello);
            assertEquals(ResponseCode.FAILED_PRECONDITION,
                    failed.getResponseStatus().getCode());

            Envelope mismatch = request(client, ControlPlaneProtocol.SYSTEM_HELLO,
                    helloEnvelope("another-cluster", 1));
            assertEquals(ResponseCode.CLUSTER_MISMATCH,
                    mismatch.getResponseStatus().getCode());
        } finally {
            if (client != null) {
                client.close();
            }
            server.prestop();
            server.stop();
        }
    }

    @Test
    void helloCredentialAuthenticatesOverNativeTcp() throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1, 4_000, true, 5_000L);
        ControlPlaneAuthenticator authenticator = authenticator();
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(
                        properties,
                        runtime,
                        new ControlPlaneRequestDispatcher(),
                        authenticator));
        Server server = SocketD.createServer("sd:tcp")
                .config(config -> configureServer(config, port))
                .listen(router)
                .start();
        ClientSession client = null;
        try {
            client = SocketD.createClient("sd:tcp://127.0.0.1:" + port
                            + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> configureClient(config)
                            .connectTimeout(3_000L)
                            .requestTimeout(2_000L)
                            .autoReconnect(false))
                    .openOrThow();

            Envelope response = request(
                    client,
                    ControlPlaneProtocol.SYSTEM_HELLO,
                    helloEnvelope("cluster-test", 1, "secret-token"));
            assertEquals(ResponseCode.OK, response.getResponseStatus().getCode());
            assertEquals("client-token:1",
                    runtime.connections().getFirst().principalId());
        } finally {
            if (client != null) {
                client.close();
            }
            server.prestop();
            server.stop();
        }
    }

    @Test
    void credentialSessionQuotaReturnsStructuredRateLimitOverNativeTcp()
            throws Exception {
        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1, 4_000, true, 5_000L);
        set(properties, "maxSessionsPerCredential", 1);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(
                        properties,
                        runtime,
                        new ControlPlaneRequestDispatcher(),
                        authenticator()));
        Server server = SocketD.createServer("sd:tcp")
                .config(config -> configureServer(config, port))
                .listen(router)
                .start();
        ClientSession first = null;
        ClientSession second = null;
        try {
            first = openClient(port);
            second = openClient(port);
            assertEquals(ResponseCode.OK,
                    request(first, ControlPlaneProtocol.SYSTEM_HELLO,
                            helloEnvelope("cluster-test", 1, "secret-token"))
                            .getResponseStatus().getCode());
            Envelope rejected = request(
                    second,
                    ControlPlaneProtocol.SYSTEM_HELLO,
                    helloEnvelope("cluster-test", 1, "secret-token"));
            assertEquals(ResponseCode.RATE_LIMITED,
                    rejected.getResponseStatus().getCode());
            assertTrue(rejected.getResponseStatus().getRetryable());
            assertEquals(1L, runtime.credentialSessionLimitRejectedTotal());
        } finally {
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
            server.prestop();
            server.stop();
        }
    }

    private ClientSession openClient(int port) throws Exception {
        return SocketD.createClient("sd:tcp://127.0.0.1:" + port
                        + ControlPlaneProtocol.CONTROL_PATH)
                .config(config -> configureClient(config)
                        .connectTimeout(3_000L)
                        .requestTimeout(2_000L)
                        .autoReconnect(false))
                .openOrThow();
    }

    private org.noear.socketd.transport.server.ServerConfig configureServer(
            org.noear.socketd.transport.server.ServerConfig config, int port) {
        return config.host("127.0.0.1")
                .port(port)
                .ioThreads(1)
                .codecThreads(1)
                .workExecutor(SOCKETD_WORK_EXECUTOR);
    }

    private org.noear.socketd.transport.client.ClientConfig configureClient(
            org.noear.socketd.transport.client.ClientConfig config) {
        return config.codecThreads(1)
                .workExecutor(SOCKETD_WORK_EXECUTOR);
    }

    private ControlPlaneAuthenticator authenticator() {
        return new ControlPlaneAuthenticator() {
            @Override
            public ControlPlanePrincipal authenticate(
                    String credential,
                    String tenant,
                    String namespaceId,
                    String groupName) {
                if (!"secret-token".equals(credential)) {
                    throw new ControlPlaneAuthenticationException("invalid credential");
                }
                return new ControlPlanePrincipal(
                        "client-token:1", tenant, namespaceId, groupName,
                        "fingerprint-1", 0L, false);
            }

            @Override
            public ControlPlanePrincipal revalidate(ControlPlanePrincipal principal) {
                return principal;
            }
        };
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Envelope request(ClientSession client, String event, Envelope request) throws Exception {
        Reply reply = client.sendAndRequest(event, Entity.of(request.toByteArray()), 2_000L).await();
        assertTrue(reply.isEnd(), "Gateway must terminate every Request with replyEnd");
        return Envelope.parseFrom(reply.dataAsBytes());
    }

    private Envelope helloEnvelope(String clusterId, long transportGeneration) {
        return helloEnvelope(clusterId, transportGeneration, "");
    }

    private Envelope helloEnvelope(
            String clusterId, long transportGeneration, String credential) {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("demo@node-1")
                .setApplicationName("demo")
                .setGroupName("DEFAULT_GROUP")
                .setClientVersion("2.0.0-test")
                .setSdkName("xuantong-client-java")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .setTransportPool("tcp-default")
                .setCredential(credential)
                .build();
        return baseEnvelope(clusterId, transportGeneration)
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope.Builder baseEnvelope(String clusterId, long transportGeneration) {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId(clusterId)
                .setTransportGeneration(transportGeneration)
                .setRequestId(UUID.randomUUID().toString())
                .setTraceId(UUID.randomUUID().toString())
                .setTenant("default")
                .setNamespaceId("public")
                .setRemainingBudgetMs(2_000);
    }

    private int freePort() throws Exception {
        try {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }
}
