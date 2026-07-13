package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;
import org.junit.jupiter.api.Test;
import org.noear.socketd.transport.client.ClientSession;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketDMultiBrokerTransportTest {
    @Test
    void configConnectsEveryUniqueBrokerAndFailsOverReads() {
        FakeConfigTransport transport = new FakeConfigTransport();
        try {
            transport.connect(
                    Arrays.asList("broker-a:8088", "broker-b:8088", "broker-a:8088"),
                    "public", "DEFAULT_GROUP", "", event -> { });

            assertEquals(2, transport.activeSessionCount());
            ConfigSnapshot snapshot = transport.fetch("app.yml");
            assertNotNull(snapshot);
            assertEquals("from-broker", snapshot.getContent());
            assertEquals(2, transport.fetchAttempts.get());
        } finally {
            transport.close();
        }
    }

    @Test
    void discoveryConnectsEveryBrokerAndRegistersTheSameLeaseOnAllOfThem() {
        FakeDiscoveryTransport transport = new FakeDiscoveryTransport();
        try {
            transport.connect(
                    Arrays.asList("broker-a:8088", "broker-b:8088", "broker-a:8088"),
                    "public", "DEFAULT_GROUP", "order-service", "",
                    (eventType, instance, snapshot) -> { });
            assertEquals(2, transport.activeSessionCount());

            ServiceInstance request = serviceInstance();
            ServiceInstance registered = transport.register(request);
            assertEquals("lease-1", registered.getLeaseId());
            assertEquals(2, transport.registerCalls.get());
            assertEquals(true, transport.deregister("node-1"));
            assertEquals(2, transport.deregisterCalls.get());
        } finally {
            transport.close();
        }
    }

    @Test
    void slowConfigBrokerDoesNotDelayHealthyBrokerAndTokenStaysOutOfUrl() {
        FakeConfigTransport transport = new FakeConfigTransport();
        transport.slowFirstBroker = true;
        try {
            transport.connect(
                    Arrays.asList("broker-a:8088", "broker-b:8088"),
                    "public", "DEFAULT_GROUP", "secret-token", event -> { });

            long startedAt = System.nanoTime();
            ConfigSnapshot snapshot = transport.fetch("app.yml");
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);

            assertNotNull(snapshot);
            assertTrue(elapsedMs < 500L, "healthy Broker should win without waiting for slow Broker");
            assertEquals(2, transport.openedUrls.size());
            for (String url : transport.openedUrls) {
                assertFalse(url.contains("token="));
                assertFalse(url.contains("secret-token"));
                assertTrue(url.contains("clientId=test-client"));
                assertTrue(url.contains("applicationName=test-app"));
                assertTrue(url.contains("clientVersion=2.0.0-SNAPSHOT"));
            }
        } finally {
            transport.close();
        }
    }

    private static class FakeConfigTransport extends SocketDTransport {
        private final Map<ClientSession, Integer> brokerIndexes =
                new IdentityHashMap<ClientSession, Integer>();
        private final AtomicInteger fetchAttempts = new AtomicInteger();
        private final List<String> openedUrls = new CopyOnWriteArrayList<String>();
        private volatile boolean slowFirstBroker;

        private FakeConfigTransport() {
            super(new ClientIdentity("test-app", "test-client"));
        }

        @Override
        protected ClientSession openSession(int brokerIndex, String url) {
            openedUrls.add(url);
            ClientSession session = fakeSession("config-" + brokerIndex);
            brokerIndexes.put(session, brokerIndex);
            return session;
        }

        @Override
        protected ConfigSnapshot fetchFromSession(ClientSession session, String dataId) {
            fetchAttempts.incrementAndGet();
            if (brokerIndexes.get(session) == 0) {
                if (slowFirstBroker) {
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                throw new IllegalStateException("broker unavailable");
            }
            return new ConfigSnapshot(dataId, "from-broker", 7L, "sum", "text");
        }
    }

    private static class FakeDiscoveryTransport extends SocketDDiscoveryTransport {
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger deregisterCalls = new AtomicInteger();

        private FakeDiscoveryTransport() {
            super(new ClientIdentity("test-app", "test-client"));
        }

        @Override
        protected ClientSession openSession(int brokerIndex, String url) {
            return fakeSession("discovery-" + brokerIndex);
        }

        @Override
        protected ServiceSnapshot fetchBrokerSnapshot(ClientSession session) {
            return new ServiceSnapshot(0L, Collections.<ServiceInstance>emptyList());
        }

        @Override
        protected ServiceInstance registerOnSession(
                ClientSession session, ServiceInstance instance) {
            registerCalls.incrementAndGet();
            return instance;
        }

        @Override
        protected boolean deregisterOnSession(
                ClientSession session, String instanceId, String leaseId) {
            deregisterCalls.incrementAndGet();
            return false;
        }
    }

    private static ServiceInstance serviceInstance() {
        ServiceInstance instance = new ServiceInstance();
        instance.setInstanceId("node-1");
        instance.setLeaseId("lease-1");
        instance.setLeaseStartedAt(100L);
        instance.setIp("10.0.0.1");
        instance.setPort(8080);
        instance.setWeight(1D);
        instance.setHealthy(true);
        instance.setEnabled(true);
        return instance;
    }

    private static ClientSession fakeSession(final String sessionId) {
        InvocationHandler handler = new InvocationHandler() {
            private boolean valid = true;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("isValid".equals(name) || "isActive".equals(name)) return valid;
                if ("isClosing".equals(name)) return !valid;
                if ("sessionId".equals(name)) return sessionId;
                if ("close".equals(name) || "preclose".equals(name)
                        || "closeStarting".equals(name)) {
                    valid = false;
                    return null;
                }
                if ("closeCode".equals(name)) return 0;
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == args[0];
                if ("toString".equals(name)) return sessionId;
                throw new UnsupportedOperationException(name);
            }
        };
        return (ClientSession) Proxy.newProxyInstance(
                ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, handler);
    }
}
