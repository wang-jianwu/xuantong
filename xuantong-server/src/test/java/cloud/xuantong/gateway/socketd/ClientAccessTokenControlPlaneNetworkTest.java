package cloud.xuantong.gateway.socketd;

import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.ClientAccessToken;
import cloud.xuantong.security.repository.ClientAccessTokenRepository;
import cloud.xuantong.server.security.ClientAccessTokenControlPlaneAuthenticator;
import cloud.xuantong.security.service.ClientAccessTokenService;
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
import org.noear.socketd.transport.server.Server;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAccessTokenControlPlaneNetworkTest {
    @Test
    void realTokenServiceAuthenticatesScopesAndRevalidatesRevocationOverNativeTcp()
            throws Exception {
        MemoryTokenRepository repository = new MemoryTokenRepository();
        ClientAccessTokenService tokenService = new ClientAccessTokenService();
        inject(tokenService, "repository", repository);
        inject(tokenService, "authRequired", true);
        ClientAccessTokenService.IssuedToken issued = tokenService.issue(
                "payment-client",
                "tenant-a",
                "public",
                "PAYMENT",
                null,
                "integration-test");

        ClientAccessTokenControlPlaneAuthenticator authenticator =
                new ClientAccessTokenControlPlaneAuthenticator();
        inject(authenticator, "tokenService", tokenService);

        int port = freePort();
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-auth-test",
                "gateway-auth-test",
                1,
                4_000,
                true,
                100L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties,
                runtime,
                new ControlPlaneRequestDispatcher(),
                authenticator);
        PathListenerPlus router = new PathListenerPlus(true);
        router.doOf(ControlPlaneProtocol.CONTROL_PATH, endpoint);
        Server server = SocketD.createServer("sd:tcp")
                .config(config -> config.host("127.0.0.1").port(port))
                .listen(router)
                .start();

        ClientSession validClient = null;
        ClientSession wrongScopeClient = null;
        try {
            validClient = openClient(port);
            Envelope hello = request(
                    validClient,
                    ControlPlaneProtocol.SYSTEM_HELLO,
                    helloEnvelope("PAYMENT", issued.rawToken()));
            assertEquals(ResponseCode.OK, hello.getResponseStatus().getCode());
            assertEquals("client-token:" + issued.token().getId(),
                    runtime.connections().stream()
                            .filter(connection -> "payment@node-1".equals(
                                    connection.clientInstanceId()))
                            .findFirst()
                            .orElseThrow()
                            .principalId());

            wrongScopeClient = openClient(port);
            Envelope rejected = request(
                    wrongScopeClient,
                    ControlPlaneProtocol.SYSTEM_HELLO,
                    helloEnvelope("ORDER", issued.rawToken()));
            assertEquals(ResponseCode.UNAUTHORIZED,
                    rejected.getResponseStatus().getCode());

            assertTrue(tokenService.revoke(issued.token().getId()));
            Envelope revoked = awaitUnauthorized(validClient, Duration.ofSeconds(2));
            assertEquals(ResponseCode.UNAUTHORIZED,
                    revoked.getResponseStatus().getCode());
            assertEquals(1L, tokenService.issuedTotal());
            assertEquals(1L, tokenService.revokedTotal());
            assertEquals(1L, tokenService.authSuccessTotal());
            assertTrue(tokenService.authFailureTotal() >= 1L);
        } finally {
            close(validClient);
            close(wrongScopeClient);
            server.prestop();
            server.stop();
        }
    }

    private Envelope awaitUnauthorized(ClientSession client, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Envelope response = null;
        while (System.nanoTime() < deadline) {
            response = request(
                    client,
                    ControlPlaneProtocol.SYSTEM_PROBE,
                    probeEnvelope());
            if (response.getResponseStatus().getCode() == ResponseCode.UNAUTHORIZED) {
                return response;
            }
            Thread.sleep(25L);
        }
        return response;
    }

    private ClientSession openClient(int port) throws Exception {
        return SocketD.createClient("sd:tcp://127.0.0.1:" + port
                        + ControlPlaneProtocol.CONTROL_PATH)
                .config(config -> config.connectTimeout(3_000L)
                        .requestTimeout(2_000L)
                        .autoReconnect(false))
                .openOrThow();
    }

    private Envelope request(ClientSession client, String event, Envelope request)
            throws Exception {
        Reply reply = client.sendAndRequest(
                event, Entity.of(request.toByteArray()), 2_000L).await();
        assertTrue(reply.isEnd(), "Gateway must terminate every Request with replyEnd");
        return Envelope.parseFrom(reply.dataAsBytes());
    }

    private Envelope helloEnvelope(String groupName, String credential) {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("payment@node-1")
                .setApplicationName("payment")
                .setGroupName(groupName)
                .setClientVersion("2.0.0-test")
                .setSdkName("xuantong-client-java")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .setTransportPool("tcp-default")
                .setCredential(credential)
                .build();
        return baseEnvelope()
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope probeEnvelope() {
        ProbeRequest probe = ProbeRequest.newBuilder()
                .setNonce(UUID.randomUUID().toString())
                .setClientSendEpochMs(System.currentTimeMillis())
                .build();
        return baseEnvelope()
                .setPayloadType(ControlPlaneProtocol.PROBE_REQUEST_TYPE)
                .setPayload(probe.toByteString())
                .build();
    }

    private Envelope.Builder baseEnvelope() {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-auth-test")
                .setTransportGeneration(1)
                .setRequestId(UUID.randomUUID().toString())
                .setTraceId(UUID.randomUUID().toString())
                .setTenant("tenant-a")
                .setNamespaceId("public")
                .setRemainingBudgetMs(2_000);
    }

    private void close(ClientSession client) throws Exception {
        if (client != null && client.isValid()) {
            client.close();
        }
    }

    private void inject(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
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

    private static final class MemoryTokenRepository
            implements ClientAccessTokenRepository {
        private final Map<Long, ClientAccessToken> tokens =
                new ConcurrentHashMap<>();

        @Override
        public ClientAccessToken find(Long id) {
            return tokens.get(id);
        }

        @Override
        public ClientAccessToken findByHash(String tokenHash) {
            return tokens.values().stream()
                    .filter(token -> tokenHash.equals(token.getTokenHash()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<ClientAccessToken> findAll() {
            return new ArrayList<>(tokens.values());
        }

        @Override
        public PageResult<ClientAccessToken> findPage(
                String keyword, Boolean active, PageQuery pageQuery) {
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
            List<ClientAccessToken> filtered = tokens.values().stream()
                    .filter(token -> active == null || active.equals(token.getIsActive()))
                    .filter(token -> normalizedKeyword.isEmpty()
                            || containsIgnoreCase(token.getTokenName(), normalizedKeyword)
                            || containsIgnoreCase(token.getTenant(), normalizedKeyword))
                    .sorted(java.util.Comparator.comparing(ClientAccessToken::getId))
                    .toList();
            int from = (int) Math.min(pageQuery.offset(), filtered.size());
            int to = Math.min(from + pageQuery.pageSize(), filtered.size());
            return PageResult.of(pageQuery, filtered.size(), filtered.subList(from, to));
        }

        @Override
        public long save(ClientAccessToken token) {
            long id = tokens.size() + 1L;
            token.setId(id);
            tokens.put(id, token);
            return 1L;
        }

        @Override
        public long revoke(Long id) {
            ClientAccessToken token = tokens.get(id);
            if (token == null) {
                return 0L;
            }
            token.setIsActive(false);
            tokens.put(id, token);
            return 1L;
        }

        @Override
        public long countActive() {
            return tokens.values().stream()
                    .filter(token -> Boolean.TRUE.equals(token.getIsActive()))
                    .count();
        }

        private boolean containsIgnoreCase(String value, String normalizedKeyword) {
            return value != null && value.toLowerCase().contains(normalizedKeyword);
        }
    }
}
