package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import javax.net.ssl.SSLContext;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlPlaneGatewayTlsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void mutualTlsAcceptsTrustedClientAndRejectsMissingClientCertificate() throws Exception {
        int port = freePort();
        TestTlsMaterial tls = TestTlsMaterial.create(tempDirectory.resolve("tls"));
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1", port, "cluster-test", "gateway-tls", 1L, 4_000L,
                16, 2, 16, 1_000L,
                ControlPlaneGatewayProperties.ClientAuth.REQUIRE);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                new ControlPlaneGatewayEndpoint(properties, runtime));
        ControlPlaneGatewayServer server = new ControlPlaneGatewayServer(
                properties, runtime, router, tls.serverContext());
        server.start();
        SSLContext trustedClientContext = tls.clientContextWithCertificate();
        SSLContext trustOnlyClientContext = tls.clientContextWithoutCertificate();
        ClientSession trustedClient = null;
        try {
            trustedClient = SocketD.createClient("sd:tcp://127.0.0.1:" + port
                            + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> config.sslContext(trustedClientContext)
                            .connectTimeout(3_000L).requestTimeout(2_000L)
                            .autoReconnect(false))
                    .openOrThow();
            Envelope response = requestHello(trustedClient);
            assertEquals(ResponseCode.OK, response.getResponseStatus().getCode());
            HelloResponse hello = HelloResponse.parseFrom(response.getPayload());
            assertEquals("sd:tcp+tls", hello.getTransportSchema());
            assertEquals(ControlPlaneGatewayProperties.ClientAuth.REQUIRE, runtime.clientAuth());

            assertThrows(Exception.class, () -> SocketD.createClient(
                            "sd:tcp://127.0.0.1:" + port + ControlPlaneProtocol.CONTROL_PATH)
                    .config(config -> config.sslContext(trustOnlyClientContext)
                            .connectTimeout(1_000L).requestTimeout(1_000L)
                            .autoReconnect(false))
                    .openOrThow());
        } finally {
            if (trustedClient != null) {
                trustedClient.close();
            }
            server.stop();
        }
    }

    private Envelope requestHello(ClientSession client) throws Exception {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("tls-client@node-1")
                .setApplicationName("tls-client")
                .setGroupName("DEFAULT_GROUP")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .build();
        Envelope request = Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-test")
                .setTransportGeneration(1L)
                .setRequestId(UUID.randomUUID().toString())
                .setTenant("default")
                .setNamespaceId("public")
                .setRemainingBudgetMs(2_000L)
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
        Reply reply = client.sendAndRequest(ControlPlaneProtocol.SYSTEM_HELLO,
                Entity.of(request.toByteArray()), 2_000L).await();
        return Envelope.parseFrom(reply.dataAsBytes());
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
}
