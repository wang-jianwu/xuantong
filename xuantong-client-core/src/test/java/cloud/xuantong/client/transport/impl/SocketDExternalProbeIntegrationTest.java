package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.ControlPlaneProbeResult;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ProbeResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.listener.SimpleListener;
import org.noear.socketd.transport.server.Server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketDExternalProbeIntegrationTest {
    @Test
    void probeOnceUsesNativeTcpAndCompletesHelloThenRequestReply() throws Exception {
        int port = freePort();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger helloRequests = new AtomicInteger();
        AtomicInteger probeRequests = new AtomicInteger();
        Server server = SocketD.createServer("sd:tcp")
                .config(config -> config.host("127.0.0.1").port(port))
                .listen(new SimpleListener() {
                    @Override
                    public void onMessage(Session session, Message message) throws IOException {
                        try {
                            Envelope request = Envelope.parseFrom(message.dataAsBytes());
                            requests.incrementAndGet();
                            if (ControlPlaneProtocol.SYSTEM_HELLO.equals(message.event())) {
                                HelloRequest hello = HelloRequest.parseFrom(request.getPayload());
                                assertEquals("probe-secret", hello.getCredential());
                                helloRequests.incrementAndGet();
                                HelloResponse response = HelloResponse.newBuilder()
                                        .setSelectedProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                                        .setClusterId("probe-cluster")
                                        .setTransportGeneration(9L)
                                        .setGatewayId("probe-gateway")
                                        .setConnectionGeneration(17L)
                                        .setMaxRequestBudgetMs(5_000L)
                                        .setTransportSchema("sd:tcp")
                                        .addAllCapabilities(List.of(
                                                "config-fetch-v1",
                                                "config-snapshot-v1",
                                                "config-watch-batch-v1",
                                                "config-watch-stream-v1",
                                                "watch-ack-v1"))
                                        .build();
                                reply(session, message, request,
                                        ControlPlaneProtocol.HELLO_RESPONSE_TYPE,
                                        response.toByteArray());
                                return;
                            }
                            if (ControlPlaneProtocol.SYSTEM_PROBE.equals(message.event())) {
                                ProbeRequest probe = ProbeRequest.parseFrom(request.getPayload());
                                probeRequests.incrementAndGet();
                                long receivedAt = System.currentTimeMillis();
                                ProbeResponse response = ProbeResponse.newBuilder()
                                        .setNonce(probe.getNonce())
                                        .setGatewayId("probe-gateway")
                                        .setConnectionGeneration(17L)
                                        .setServerReceiveEpochMs(receivedAt)
                                        .setServerSendEpochMs(System.currentTimeMillis())
                                        .build();
                                reply(session, message, request,
                                        ControlPlaneProtocol.PROBE_RESPONSE_TYPE,
                                        response.toByteArray());
                            }
                        } catch (Exception e) {
                            throw e instanceof IOException io ? io : new IOException(e);
                        }
                    }
                })
                .start();
        try {
            ControlPlaneOptions options = new ControlPlaneOptions(
                    "default", "config-default", "", 0L, "tcp-default",
                    2_000L, 1_000L, 3_000L, 100L);
            try (SocketDTransport transport = new SocketDTransport(
                    new ClientIdentity("probe-test", "probe-test-instance"), options)) {
                ControlPlaneProbeResult result = transport.probeOnce(
                        List.of("127.0.0.1:" + port),
                        "public", "DEFAULT_GROUP", "probe-secret");

                assertEquals("probe-gateway", result.gatewayId());
                assertEquals("probe-cluster", result.clusterId());
                assertEquals(9L, result.transportGeneration());
                assertEquals(17L, result.connectionGeneration());
                assertEquals(0, result.addressIndex());
                assertTrue(result.address().endsWith(
                        ':' + Integer.toString(port) + ControlPlaneProtocol.CONTROL_PATH));
                assertTrue(result.rpcDurationNanos() >= 0L);
            }
            assertEquals(2, requests.get(),
                    "one-shot health must be exactly one Hello plus one Probe");
            assertEquals(1, helloRequests.get());
            assertEquals(1, probeRequests.get());
        } finally {
            server.prestop();
            server.stop();
        }
    }

    private static void reply(
            Session session,
            Message message,
            Envelope request,
            String payloadType,
            byte[] payload) throws IOException {
        Envelope response = request.toBuilder()
                .setClusterId("probe-cluster")
                .setTransportGeneration(9L)
                .setPayloadType(payloadType)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .setResponseStatus(ResponseStatus.newBuilder()
                        .setCode(ResponseCode.OK)
                        .setMessage("OK"))
                .build();
        session.replyEnd(message, Entity.of(response.toByteArray()));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable: " + e.getMessage());
            return -1;
        }
    }
}
