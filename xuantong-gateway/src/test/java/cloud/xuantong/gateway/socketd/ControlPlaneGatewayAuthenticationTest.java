package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ResponseCode;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneGatewayAuthenticationTest {
    @Test
    void helloDeadlineClosesOnlySessionsThatNeverAuthenticate() throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L, true, 100L);
        set(properties, "helloTimeoutMs", 100L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties,
                runtime,
                new ControlPlaneRequestDispatcher(),
                new TestAuthenticator());

        RecordingSession pending = new RecordingSession("session-pending-hello");
        Session pendingSession = pending.proxy();
        endpoint.onOpen(pendingSession);

        RecordingSession authenticated = new RecordingSession("session-authenticated");
        Session authenticatedSession = authenticated.proxy();
        endpoint.onOpen(authenticatedSession);
        endpoint.onMessage(authenticatedSession, request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "secret")));

        Thread.sleep(250L);

        assertFalse(pending.active);
        assertTrue(authenticated.active);
        assertEquals(1L, runtime.helloTimeoutClosedTotal());
        assertEquals(ResponseCode.OK,
                authenticated.responses.getFirst().getResponseStatus().getCode());
    }

    @Test
    void helloCompletionCannotRacePastItsDeadline() throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L, true, 100L);
        set(properties, "helloTimeoutMs", 100L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        CountDownLatch authenticationEntered = new CountDownLatch(1);
        CountDownLatch releaseAuthentication = new CountDownLatch(1);
        ControlPlaneAuthenticator blockingAuthenticator = new ControlPlaneAuthenticator() {
            @Override
            public ControlPlanePrincipal authenticate(
                    String credential,
                    String tenant,
                    String namespaceId,
                    String groupName) {
                authenticationEntered.countDown();
                try {
                    if (!releaseAuthentication.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("authentication test did not resume");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("authentication test interrupted", e);
                }
                return principal(tenant, namespaceId, groupName);
            }

            @Override
            public ControlPlanePrincipal revalidate(ControlPlanePrincipal principal) {
                return principal;
            }

            private ControlPlanePrincipal principal(
                    String tenant, String namespaceId, String groupName) {
                return new ControlPlanePrincipal(
                        "client-token:slow",
                        tenant,
                        namespaceId,
                        groupName,
                        "fingerprint-slow",
                        0L,
                        false);
            }
        };
        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties,
                runtime,
                new ControlPlaneRequestDispatcher(),
                blockingAuthenticator);
        RecordingSession recording = new RecordingSession("session-slow-hello");
        Session session = recording.proxy();
        endpoint.onOpen(session);
        AtomicReference<Throwable> helloFailure = new AtomicReference<>();
        Thread helloThread = new Thread(() -> {
            try {
                endpoint.onMessage(session, request(
                        ControlPlaneProtocol.SYSTEM_HELLO,
                        hello("public", "secret")));
            } catch (Throwable e) {
                helloFailure.set(e);
            }
        }, "slow-hello-test");

        helloThread.start();
        assertTrue(authenticationEntered.await(1, TimeUnit.SECONDS));
        long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (recording.active && System.nanoTime() < closeDeadline) {
            Thread.sleep(10L);
        }
        releaseAuthentication.countDown();
        helloThread.join(2_000L);

        if (helloFailure.get() != null) {
            throw new AssertionError(helloFailure.get());
        }
        assertFalse(recording.active);
        assertFalse(helloThread.isAlive());
        assertEquals(1L, runtime.helloTimeoutClosedTotal());
        assertEquals(ResponseCode.FAILED_PRECONDITION,
                recording.responses.getFirst().getResponseStatus().getCode());
    }

    @Test
    void requiredAuthenticationRejectsMissingCredential() throws Exception {
        Fixture fixture = fixture();
        RecordingSession recording = new RecordingSession();
        Session session = recording.proxy();
        fixture.endpoint.onOpen(session);

        fixture.endpoint.onMessage(session, request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "")));

        assertEquals(ResponseCode.UNAUTHORIZED,
                recording.responses.getFirst().getResponseStatus().getCode());
        assertEquals(0, fixture.runtime.logicalClients());
    }

    @Test
    void helloBindsTrustedPrincipalAndRejectsScopeSpoofing() throws Exception {
        Fixture fixture = fixture();
        RecordingSession recording = new RecordingSession();
        Session session = recording.proxy();
        fixture.endpoint.onOpen(session);

        fixture.endpoint.onMessage(session, request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "secret")));
        fixture.endpoint.onMessage(session, request(
                ControlPlaneProtocol.SYSTEM_PROBE, probe("other")));

        assertEquals(ResponseCode.OK,
                recording.responses.get(0).getResponseStatus().getCode());
        assertEquals(ResponseCode.UNAUTHORIZED,
                recording.responses.get(1).getResponseStatus().getCode());
        ControlPlaneGatewayRuntime.ControlPlaneConnectionView connection =
                fixture.runtime.connections().getFirst();
        assertEquals("client-token:7", connection.principalId());
        assertEquals("default", connection.tenant());
        assertEquals("public", connection.namespaceId());
        assertEquals("DEFAULT_GROUP", connection.groupName());
    }

    @Test
    void revalidationAndExplicitRevocationCutOffTheSession() throws Exception {
        Fixture fixture = fixture();
        RecordingSession recording = new RecordingSession();
        Session session = recording.proxy();
        fixture.endpoint.onOpen(session);
        fixture.endpoint.onMessage(session, request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "secret")));

        fixture.authenticator.rejected = true;
        Thread.sleep(120L);
        fixture.endpoint.onMessage(session, request(
                ControlPlaneProtocol.SYSTEM_PROBE, probe("public")));
        assertEquals(ResponseCode.UNAUTHORIZED,
                recording.responses.get(1).getResponseStatus().getCode());

        assertEquals(1, fixture.endpoint.revokeCredential("fingerprint-7"));
        assertFalse(recording.active);
    }

    @Test
    void quotasAndAuthenticationFailureThrottleReturnStructuredStatus()
            throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L, true, 100L);
        set(properties, "maxSessionsPerTenant", 1);
        set(properties, "tenantRequestRatePerSecond", 1);
        set(properties, "tenantRequestBurst", 1);
        set(properties, "authFailureLimit", 1);
        set(properties, "authFailureWindowMs", 1_000L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        TestAuthenticator authenticator = new TestAuthenticator();
        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties,
                runtime,
                new ControlPlaneRequestDispatcher(),
                authenticator);

        RecordingSession accepted = new RecordingSession("session-accepted");
        endpoint.onOpen(accepted.proxy());
        endpoint.onMessage(accepted.proxy(), request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "secret")));
        assertEquals(ResponseCode.OK,
                accepted.responses.getFirst().getResponseStatus().getCode());
        endpoint.onMessage(accepted.proxy(), request(
                ControlPlaneProtocol.SYSTEM_PROBE, probe("public")));
        endpoint.onMessage(accepted.proxy(), request(
                ControlPlaneProtocol.SYSTEM_PROBE, probe("public")));
        assertEquals(ResponseCode.OK,
                accepted.responses.get(1).getResponseStatus().getCode());
        assertEquals(ResponseCode.RATE_LIMITED,
                accepted.responses.get(2).getResponseStatus().getCode());

        RecordingSession quotaRejected = new RecordingSession("session-quota");
        endpoint.onOpen(quotaRejected.proxy());
        endpoint.onMessage(quotaRejected.proxy(), request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "secret")));
        assertEquals(ResponseCode.RATE_LIMITED,
                quotaRejected.responses.getFirst().getResponseStatus().getCode());

        RecordingSession invalid = new RecordingSession("session-invalid");
        endpoint.onOpen(invalid.proxy());
        endpoint.onMessage(invalid.proxy(), request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "bad")));
        assertEquals(ResponseCode.UNAUTHORIZED,
                invalid.responses.getFirst().getResponseStatus().getCode());

        RecordingSession throttled = new RecordingSession("session-throttled");
        endpoint.onOpen(throttled.proxy());
        endpoint.onMessage(throttled.proxy(), request(
                ControlPlaneProtocol.SYSTEM_HELLO, hello("public", "bad")));
        assertEquals(ResponseCode.RATE_LIMITED,
                throttled.responses.getFirst().getResponseStatus().getCode());
        assertEquals(1L, runtime.tenantSessionLimitRejectedTotal());
        assertEquals(1L, runtime.tenantRequestRateLimitedTotal());
        assertEquals(1L, runtime.authenticationRateLimitedTotal());
    }

    private Fixture fixture() {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "cluster-test", "gateway-test", 1L, 4_000L, true, 100L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        TestAuthenticator authenticator = new TestAuthenticator();
        ControlPlaneGatewayEndpoint endpoint = new ControlPlaneGatewayEndpoint(
                properties,
                runtime,
                new ControlPlaneRequestDispatcher(),
                authenticator);
        return new Fixture(runtime, endpoint, authenticator);
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Envelope hello(String namespaceId, String credential) {
        HelloRequest hello = HelloRequest.newBuilder()
                .setClientInstanceId("demo@node-1")
                .setApplicationName("demo")
                .setGroupName("DEFAULT_GROUP")
                .setCredential(credential)
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .build();
        return base(namespaceId)
                .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                .setPayload(hello.toByteString())
                .build();
    }

    private Envelope probe(String namespaceId) {
        return base(namespaceId)
                .setPayloadType(ControlPlaneProtocol.PROBE_REQUEST_TYPE)
                .setPayload(ProbeRequest.newBuilder().setNonce("n-1").build().toByteString())
                .build();
    }

    private Envelope.Builder base(String namespaceId) {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId("cluster-test")
                .setTransportGeneration(1L)
                .setRequestId(UUID.randomUUID().toString())
                .setTenant("default")
                .setNamespaceId(namespaceId)
                .setRemainingBudgetMs(2_000L);
    }

    private Message request(String event, Envelope envelope) {
        return new MessageDefault(
                Flags.Request,
                UUID.randomUUID().toString(),
                event,
                Entity.of(envelope.toByteArray()));
    }

    private record Fixture(
            ControlPlaneGatewayRuntime runtime,
            ControlPlaneGatewayEndpoint endpoint,
            TestAuthenticator authenticator) {
    }

    private static final class TestAuthenticator implements ControlPlaneAuthenticator {
        private volatile boolean rejected;

        @Override
        public ControlPlanePrincipal authenticate(
                String credential,
                String tenant,
                String namespaceId,
                String groupName) {
            if (rejected || !"secret".equals(credential)) {
                throw new ControlPlaneAuthenticationException("invalid credential");
            }
            return principal(tenant, namespaceId, groupName);
        }

        @Override
        public ControlPlanePrincipal revalidate(ControlPlanePrincipal principal) {
            if (rejected) {
                throw new ControlPlaneAuthenticationException("revoked credential");
            }
            return principal(
                    principal.tenant(), principal.namespaceId(), principal.groupName());
        }

        private ControlPlanePrincipal principal(
                String tenant, String namespaceId, String groupName) {
            return new ControlPlanePrincipal(
                    "client-token:7",
                    tenant,
                    namespaceId,
                    groupName,
                    "fingerprint-7",
                    0L,
                    false);
        }
    }

    private static final class RecordingSession {
        private final String sessionId;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<Envelope> responses = new ArrayList<>();
        private volatile boolean active = true;

        private RecordingSession() {
            this("session-auth-1");
        }

        private RecordingSession(String sessionId) {
            this.sessionId = sessionId;
        }

        private Session proxy() {
            return (Session) Proxy.newProxyInstance(
                    Session.class.getClassLoader(),
                    new Class<?>[]{Session.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "sessionId" -> sessionId;
                        case "isActive", "isValid" -> active;
                        case "isClosing" -> false;
                        case "remoteAddress" ->
                                new InetSocketAddress("127.0.0.1", 12345);
                        case "param" -> null;
                        case "attr" -> attributes.get((String) args[0]);
                        case "attrPut" -> {
                            attributes.put((String) args[0], args[1]);
                            yield proxy;
                        }
                        case "reply", "replyEnd" -> {
                            responses.add(Envelope.parseFrom(
                                    ((Entity) args[1]).dataAsBytes()));
                            yield null;
                        }
                        case "close" -> {
                            active = false;
                            yield null;
                        }
                        case "preclose" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
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
