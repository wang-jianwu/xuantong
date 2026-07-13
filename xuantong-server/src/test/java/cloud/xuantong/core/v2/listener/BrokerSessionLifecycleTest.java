package cloud.xuantong.core.v2.listener;

import cloud.xuantong.core.event.ClientAccessTokenRevokedEvent;
import cloud.xuantong.core.service.ClientAccessTokenService;
import cloud.xuantong.core.v2.model.ServiceDefinition;
import cloud.xuantong.core.v2.model.ServiceInstance;
import cloud.xuantong.core.v2.service.ServiceDefinitionService;
import cloud.xuantong.core.v2.service.ServiceInstanceRegistry;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.socketd.transport.core.Flags;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.MessageBuilder;
import org.noear.socketd.transport.core.entity.StringEntity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerSessionLifecycleTest {
    @Test
    void rejectsClientWithoutRequired2Identity() throws Exception {
        BrokerClientSessionRegistry registry = new BrokerClientSessionRegistry();
        inject(registry, "tokenService", new AllowAllTokenService());

        Map<String, String> params = discoveryParams();
        params.remove("clientId");
        params.remove("applicationName");

        assertFalse(registry.register(BrokerClientSessionRegistry.Channel.CONFIG,
                "token-hash", new SessionState("legacy-client", params).proxy()));
        assertEquals(0, registry.size());
    }

    @Test
    void removesDiscoveryLeaseImmediatelyWhenSocketDSessionCloses() throws Exception {
        AllowAllTokenService tokenService = new AllowAllTokenService();
        BrokerClientSessionRegistry sessionRegistry = new BrokerClientSessionRegistry();
        inject(sessionRegistry, "tokenService", tokenService);

        RecordingInstanceRegistry instanceRegistry = new RecordingInstanceRegistry();
        DiscoveryBrokerV2Listener listener = new DiscoveryBrokerV2Listener();
        inject(listener, "tokenService", tokenService);
        inject(listener, "sessionRegistry", sessionRegistry);
        inject(listener, "instanceRegistry", instanceRegistry);
        inject(listener, "serviceDefinitionService", new ExistingServiceDefinitionService());

        SessionState state = new SessionState("discovery-session", discoveryParams());
        Session session = state.proxy();
        listener.onOpen(session);

        assertEquals(1L, sessionRegistry.logicalClientCount(
                BrokerClientSessionRegistry.Channel.DISCOVERY));
        assertEquals(1L, sessionRegistry.sessionCount(
                BrokerClientSessionRegistry.Channel.DISCOVERY));
        assertEquals("order-service", sessionRegistry.connections().get(0).applicationName());
        assertEquals("order-service@node-1", sessionRegistry.connections().get(0).clientId());

        ServiceInstance request = new ServiceInstance();
        request.setInstanceId("order-node-1");
        request.setLeaseId("lease-1");
        request.setIp("10.0.0.8");
        request.setPort(8080);
        request.setWeight(1D);
        listener.onMessage(session, new MessageBuilder()
                .flag(Flags.Request)
                .event("/register")
                .entity(new StringEntity(ONode.serialize(request)))
                .build());

        assertEquals(1, instanceRegistry.registerCalls.get());
        assertEquals("true", state.lastReplyMeta("success"));

        listener.onClose(session);

        assertEquals(1, instanceRegistry.deregisterCalls.get());
        assertEquals("order-node-1", instanceRegistry.lastDeregisteredInstanceId);
        assertEquals("lease-1", instanceRegistry.lastDeregisteredLeaseId);
    }

    @Test
    void closesTrackedSessionWhenAccessTokenIsRevoked() throws Exception {
        AllowAllTokenService tokenService = new AllowAllTokenService();
        BrokerClientSessionRegistry registry = new BrokerClientSessionRegistry();
        inject(registry, "tokenService", tokenService);

        Map<String, String> params = discoveryParams();
        params.put("token", "token-hash");
        SessionState state = new SessionState("revoked-session", params);
        registry.register(BrokerClientSessionRegistry.Channel.DISCOVERY,
                "token-hash", state.proxy());

        registry.onEvent(new ClientAccessTokenRevokedEvent("token-hash"));

        assertTrue(state.closed.get());
        assertEquals(0, registry.size());
        assertEquals(0L, registry.sessionCount(BrokerClientSessionRegistry.Channel.DISCOVERY));
    }

    private Map<String, String> discoveryParams() {
        Map<String, String> params = new HashMap<>();
        params.put("@", "discovery:public:DEFAULT_GROUP:order-service");
        params.put("namespace", "public");
        params.put("group", "DEFAULT_GROUP");
        params.put("serviceName", "order-service");
        params.put("token", "token-hash");
        params.put("clientId", "order-service@node-1");
        params.put("applicationName", "order-service");
        params.put("clientVersion", "2.0.0-test");
        return params;
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class AllowAllTokenService extends ClientAccessTokenService {
        @Override
        public boolean authorize(String raw, String namespaceId, String groupName) {
            return true;
        }

        @Override
        public boolean isAuthorized(String raw, String namespaceId, String groupName) {
            return true;
        }

        @Override
        public String fingerprint(String raw) {
            return raw == null ? "" : raw;
        }
    }

    private static class ExistingServiceDefinitionService extends ServiceDefinitionService {
        @Override
        public ServiceDefinition find(String namespaceId, String groupName, String serviceName) {
            ServiceDefinition service = new ServiceDefinition();
            service.setNamespaceId(namespaceId);
            service.setGroupName(groupName);
            service.setServiceName(serviceName);
            return service;
        }
    }

    private static class RecordingInstanceRegistry extends ServiceInstanceRegistry {
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger deregisterCalls = new AtomicInteger();
        private String lastDeregisteredInstanceId;
        private String lastDeregisteredLeaseId;

        @Override
        public ServiceInstance register(
                String namespaceId, String groupName, String serviceName, ServiceInstance request) {
            registerCalls.incrementAndGet();
            request.setNamespaceId(namespaceId);
            request.setGroupName(groupName);
            request.setServiceName(serviceName);
            request.setLeaseStartedAt(System.currentTimeMillis());
            request.setHealthy(true);
            request.setEnabled(true);
            return request;
        }

        @Override
        public boolean deregister(
                String namespaceId,
                String groupName,
                String serviceName,
                String instanceId,
                String leaseId) {
            deregisterCalls.incrementAndGet();
            lastDeregisteredInstanceId = instanceId;
            lastDeregisteredLeaseId = leaseId;
            return true;
        }
    }

    private static class SessionState implements InvocationHandler {
        private final String sessionId;
        private final Map<String, String> params;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile StringEntity lastReply;

        private SessionState(String sessionId, Map<String, String> params) {
            this.sessionId = sessionId;
            this.params = params;
        }

        private Session proxy() {
            return (Session) Proxy.newProxyInstance(
                    Session.class.getClassLoader(), new Class<?>[]{Session.class}, this);
        }

        private String lastReplyMeta(String name) {
            return lastReply == null ? null : lastReply.meta(name);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("sessionId".equals(name)) return sessionId;
            if ("name".equals(name)) return params.get("@");
            if ("param".equals(name)) return params.get(args[0]);
            if ("paramOrDefault".equals(name)) return params.getOrDefault(args[0], (String) args[1]);
            if ("isValid".equals(name) || "isActive".equals(name)) return !closed.get();
            if ("isClosing".equals(name)) return closed.get();
            if ("close".equals(name) || "preclose".equals(name)) {
                closed.set(true);
                return null;
            }
            if ("reply".equals(name) || "replyEnd".equals(name)) {
                lastReply = (StringEntity) args[1];
                return null;
            }
            if ("closeCode".equals(name)) return 0;
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == args[0];
            if ("toString".equals(name)) return sessionId;
            throw new UnsupportedOperationException(name);
        }
    }
}
