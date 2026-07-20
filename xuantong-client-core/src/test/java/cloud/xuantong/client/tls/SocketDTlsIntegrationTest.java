package cloud.xuantong.client.tls;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.TlsOptions;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.impl.SocketDTransport;
import cloud.xuantong.protocol.v2.ConfigContentValue;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.ConfigFetchRequest;
import cloud.xuantong.protocol.v2.ConfigFetchResponse;
import cloud.xuantong.protocol.v2.ConfigValueState;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ProbeResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.socketd.transport.server.Server;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketDTlsIntegrationTest {
    @TempDir
    static Path tempDirectory;

    private static TestTlsMaterial materialA;
    private static TestTlsMaterial materialB;
    private static TestTlsMaterial dnsOnlyMaterial;
    private static TestTlsMaterial expiredMaterial;

    @BeforeAll
    static void createCertificates() throws Exception {
        materialA = TestTlsMaterial.create(tempDirectory.resolve("a"));
        materialB = TestTlsMaterial.create(tempDirectory.resolve("b"));
        dnsOnlyMaterial = TestTlsMaterial.create(
                tempDirectory.resolve("dns-only"), "SAN=dns:localhost", null, 3_650);
        expiredMaterial = TestTlsMaterial.create(
                tempDirectory.resolve("expired"),
                "SAN=dns:localhost,ip:127.0.0.1", "2020/01/01 00:00:00", 1);
    }

    @Test
    void requireClientAuthAcceptsTrustedCertificateAndRejectsMissingCertificate()
            throws Exception {
        int port = freePort();
        Server server = startServer(port, materialA, false, true);
        TlsProbeTransport trusted = transport(tls(materialA, true, 30_000L));
        TlsProbeTransport missing = transport(tls(materialA, false, 30_000L));
        try {
            trusted.connect(List.of("127.0.0.1:" + port),
                    "public", "DEFAULT_GROUP", "");
            assertNotNull(trusted.fetch("tls.require", 0L));

            missing.connect(List.of("127.0.0.1:" + port),
                    "public", "DEFAULT_GROUP", "");
            assertNull(missing.fetch("tls.require", 0L));
        } finally {
            trusted.close();
            missing.close();
            server.stop();
        }
    }

    @Test
    void wantClientAuthAllowsTrustOnlyClient() throws Exception {
        int port = freePort();
        Server server = startServer(port, materialA, true, false);
        TlsProbeTransport client = transport(tls(materialA, false, 30_000L));
        try {
            client.connect(List.of("127.0.0.1:" + port),
                    "public", "DEFAULT_GROUP", "");
            assertNotNull(client.fetch("tls.want", 0L));
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    void rejectsWrongCaHostnameMismatchAndExpiredCertificate() throws Exception {
        assertRejected(materialA, tls(materialB, false, 30_000L), "tls.wrong-ca");
        assertRejected(dnsOnlyMaterial, tls(dnsOnlyMaterial, false, 30_000L),
                "tls.hostname");
        assertRejected(expiredMaterial, tls(expiredMaterial, false, 30_000L),
                "tls.expired");
    }

    @Test
    void dualTrustWindowRebuildsConnectionAcrossCertificateRotation() throws Exception {
        int portA = freePort();
        int portB = freePort();
        Server serverA = startServer(portA, materialA, false, false);
        Server serverB = startServer(portB, materialB, false, false);
        Path mutableTrust = materialA.mutableTrustStore(
                tempDirectory.resolve("rotating-trust.p12"));
        Path combined = TestTlsMaterial.combineTrustStores(
                tempDirectory.resolve("combined-trust.p12"), materialA, materialB);
        TlsOptions options = TlsOptions.enabled(
                mutableTrust.toString(), "PKCS12", TestTlsMaterial.PASSWORD,
                "", "PKCS12", "", "", true, 1_000L);
        TlsProbeTransport client = transport(options);
        try {
            client.connect(List.of(
                            "127.0.0.1:" + portA,
                            "127.0.0.1:" + portB),
                    "public", "DEFAULT_GROUP", "");
            assertNotNull(client.fetch("tls.rotation", 0L));
            assertEquals(1, client.successfulOpens.get());

            Files.copy(combined, mutableTrust, StandardCopyOption.REPLACE_EXISTING);
            awaitSuccessfulOpens(client, 2);
            assertNotNull(client.fetch("tls.rotation", 0L));

            Files.copy(materialB.trustStore(), mutableTrust,
                    StandardCopyOption.REPLACE_EXISTING);
            awaitSuccessfulOpens(client, 3);
            assertNotNull(client.fetch("tls.rotation", 0L));
        } finally {
            client.close();
            serverA.stop();
            serverB.stop();
        }
    }

    private void assertRejected(
            TestTlsMaterial serverMaterial, TlsOptions clientOptions, String dataId)
            throws Exception {
        int port = freePort();
        Server server = startServer(port, serverMaterial, false, false);
        TlsProbeTransport client = transport(clientOptions);
        try {
            client.connect(List.of("127.0.0.1:" + port),
                    "public", "DEFAULT_GROUP", "");
            assertNull(client.fetch(dataId, 0L));
        } finally {
            client.close();
            server.stop();
        }
    }

    private TlsOptions tls(
            TestTlsMaterial material, boolean clientCertificate, long reloadIntervalMs) {
        return TlsOptions.enabled(
                material.trustStore().toString(), "PKCS12", TestTlsMaterial.PASSWORD,
                clientCertificate ? material.clientKeyStore().toString() : "",
                "PKCS12", clientCertificate ? TestTlsMaterial.PASSWORD : "",
                clientCertificate ? TestTlsMaterial.PASSWORD : "",
                true, reloadIntervalMs);
    }

    private TlsProbeTransport transport(TlsOptions tls) {
        return new TlsProbeTransport(new ControlPlaneOptions(
                "default", "config-default", "", 0L, "tcp-default",
                2_000L, 1_000L, 3_000L, 500L, tls));
    }

    private Server startServer(
            int port, TestTlsMaterial material, boolean wantClientAuth,
            boolean needClientAuth) throws Exception {
        Server server = SocketD.createServer("sd:tcp");
        var serverContext = material.serverContext();
        server.config(config -> config.host("127.0.0.1")
                .port(port)
                .sslContext(serverContext)
                .sslWantClientAuth(wantClientAuth)
                .sslNeedClientAuth(needClientAuth));
        server.listen(new EventListener());
        server.start();
        return server;
    }

    private void awaitSuccessfulOpens(TlsProbeTransport client, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8L);
        while (client.successfulOpens.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue(client.successfulOpens.get() >= expected,
                "TLS material rotation did not rebuild the connection in time");
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

    private static final class TlsProbeTransport extends SocketDTransport {
        private final AtomicInteger successfulOpens = new AtomicInteger();

        private TlsProbeTransport(ControlPlaneOptions options) {
            super(new ClientIdentity("tls-test", "tls-test-instance"), options);
        }

        @Override
        protected ClientSession openSession(
                int gatewayIndex, String url, EventListener listener, long connectTimeoutMs)
                throws Exception {
            ClientSession session = super.openSession(
                    gatewayIndex, url, listener, connectTimeoutMs);
            successfulOpens.incrementAndGet();
            return session;
        }

        @Override
        protected Envelope request(
                ClientSession session, String event, Envelope request, long timeoutMs)
                throws Exception {
            if (ControlPlaneProtocol.SYSTEM_HELLO.equals(event)) {
                HelloResponse response = HelloResponse.newBuilder()
                        .setSelectedProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                        .setClusterId("tls-cluster")
                        .setTransportGeneration(1L)
                        .setGatewayId("tls-gateway")
                        .setConnectionGeneration(successfulOpens.get())
                        .setMaxRequestBudgetMs(10_000L)
                        .setTransportSchema("sd:tcp+tls")
                        .addAllCapabilities(List.of(
                                "config-fetch-v1", "config-snapshot-v1",
                                "config-watch-batch-v1", "config-watch-stream-v1",
                                "watch-ack-v1"))
                        .build();
                return ok(request, ControlPlaneProtocol.HELLO_RESPONSE_TYPE,
                        response.toByteString());
            }
            if (ControlPlaneProtocol.SYSTEM_PROBE.equals(event)) {
                ProbeRequest probe = ProbeRequest.parseFrom(request.getPayload());
                long receivedAt = System.currentTimeMillis();
                ProbeResponse response = ProbeResponse.newBuilder()
                        .setNonce(probe.getNonce())
                        .setGatewayId("tls-gateway")
                        .setConnectionGeneration(successfulOpens.get())
                        .setServerReceiveEpochMs(receivedAt)
                        .setServerSendEpochMs(System.currentTimeMillis())
                        .build();
                return ok(request, ControlPlaneProtocol.PROBE_RESPONSE_TYPE,
                        response.toByteString());
            }
            if (ControlPlaneProtocol.CONFIG_FETCH.equals(event)) {
                ConfigFetchRequest fetch = ConfigFetchRequest.parseFrom(request.getPayload());
                ConfigFetchResponse response = ConfigFetchResponse.newBuilder()
                        .setState(ConfigValueState.CONFIG_VALUE_STATE_ACTIVE)
                        .setConfig(ConfigCoordinate.newBuilder()
                                .setNamespaceId(request.getNamespaceId())
                                .setGroupName(fetch.getGroupName())
                                .setDataId(fetch.getDataId()))
                        .setDecisionRevision(1L)
                        .setContent(ConfigContentValue.newBuilder()
                                .setContentRevision(1L)
                                .setContentHash("tls")
                                .setContentType("text")
                                .setPayload(ByteString.copyFromUtf8("secure")))
                        .build();
                return ok(request, ControlPlaneProtocol.CONFIG_FETCH_RESPONSE_TYPE,
                        response.toByteString());
            }
            throw new UnsupportedOperationException(event);
        }

        private Envelope ok(Envelope request, String payloadType, ByteString payload) {
            return request.toBuilder()
                    .setClusterId("tls-cluster")
                    .setTransportGeneration(1L)
                    .setPayloadType(payloadType)
                    .setPayload(payload)
                    .setResponseStatus(ResponseStatus.newBuilder()
                            .setCode(ResponseCode.OK).setMessage("OK"))
                    .build();
        }
    }
}
