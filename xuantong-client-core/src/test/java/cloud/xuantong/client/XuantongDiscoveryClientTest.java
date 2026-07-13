package cloud.xuantong.client;

import cloud.xuantong.client.discovery.LoadBalanceStrategy;
import cloud.xuantong.client.discovery.ServiceInstanceSelector;
import cloud.xuantong.client.model.ServiceChangeEvent;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.transport.DiscoveryTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XuantongDiscoveryClientTest {
    @Test
    void maintainsMergedInstancesAndClientOwnedLease() {
        FakeDiscoveryTransport transport = new FakeDiscoveryTransport();
        XuantongDiscoveryClient client = new XuantongDiscoveryClient(
                Collections.singletonList("127.0.0.1:8088"),
                "public", "DEFAULT_GROUP", "order-service", "", transport);
        try {
            assertEquals(1, client.getInstances().size());
            assertEquals(1L, client.getRevision());
            assertEquals(Collections.singletonList("order-service"), client.getServices());

            ServiceInstance local = instance("local-node", "10.0.0.2", 8081, 2D);
            ServiceInstance registered = client.register(local);
            assertEquals("local-node", registered.getInstanceId());
            assertNotNull(registered.getLeaseId());
            assertNotNull(registered.getLeaseStartedAt());
            assertEquals(registered.getLeaseId(), transport.registration.getLeaseId());
            assertEquals(2, client.getInstances().size());
            assertThrows(IllegalStateException.class, () -> client.register(local));

            AtomicReference<ServiceChangeEvent> received = new AtomicReference<ServiceChangeEvent>();
            client.addListener(received::set);
            transport.emit("INSTANCE_REGISTERED", 2L,
                    instance("remote-node", "10.0.0.3", 8082, 1D));
            assertNotNull(received.get());
            assertEquals(2L, client.getRevision());
            assertEquals(3, client.getInstances().size());

            transport.emit("INSTANCE_EXPIRED", 3L,
                    instance("remote-node", "10.0.0.3", 8082, 1D));
            assertEquals(3L, client.getRevision());
            assertEquals(2, client.getInstances().size());

            client.heartbeat();
            assertTrue(transport.heartbeatCalled);
            assertTrue(client.deregister());
            assertFalse(client.getInstances().stream()
                    .anyMatch(item -> "local-node".equals(item.getInstanceId())));
        } finally {
            client.close();
        }
    }

    @Test
    void selectsAvailableInstancesWithRoundRobinAndWeightedStrategies() {
        ServiceInstanceSelector selector = new ServiceInstanceSelector();
        ServiceInstance first = instance("a", "10.0.0.1", 8080, 1D);
        ServiceInstance second = instance("b", "10.0.0.2", 8080, 3D);
        ServiceInstance disabled = instance("c", "10.0.0.3", 8080, 10D);
        disabled.setEnabled(false);
        List<ServiceInstance> instances = Arrays.asList(first, second, disabled);

        assertEquals("a", selector.select(instances, LoadBalanceStrategy.ROUND_ROBIN).getInstanceId());
        assertEquals("b", selector.select(instances, LoadBalanceStrategy.ROUND_ROBIN).getInstanceId());
        assertNotNull(selector.select(instances, LoadBalanceStrategy.RANDOM));
        for (int i = 0; i < 20; i++) {
            String selected = selector.select(
                    instances, LoadBalanceStrategy.WEIGHTED_RANDOM).getInstanceId();
            assertTrue("a".equals(selected) || "b".equals(selected));
        }
    }

    private static ServiceInstance instance(
            String instanceId, String ip, int port, double weight) {
        ServiceInstance instance = new ServiceInstance();
        instance.setNamespaceId("public");
        instance.setGroupName("DEFAULT_GROUP");
        instance.setServiceName("order-service");
        instance.setInstanceId(instanceId);
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(weight);
        instance.setHealthy(true);
        instance.setEnabled(true);
        return instance;
    }

    private static ServiceInstance copy(ServiceInstance source) {
        ServiceInstance target = instance(
                source.getInstanceId(), source.getIp(), source.getPort(),
                source.getWeight() == null ? 1D : source.getWeight());
        target.setLeaseId(source.getLeaseId());
        target.setLeaseStartedAt(source.getLeaseStartedAt());
        target.setLastHeartbeatAt(source.getLastHeartbeatAt());
        target.setMetadata(source.getMetadata());
        target.setHealthy(source.getHealthy());
        target.setEnabled(source.getEnabled());
        return target;
    }

    private static class FakeDiscoveryTransport implements DiscoveryTransport {
        private final List<ServiceInstance> instances = new ArrayList<ServiceInstance>();
        private ServiceChangeListener listener;
        private ServiceInstance registration;
        private boolean heartbeatCalled;

        private FakeDiscoveryTransport() {
            instances.add(instance("seed-node", "10.0.0.1", 8080, 1D));
        }

        @Override
        public void connect(List<String> serverAddresses, String namespace, String group,
                            String serviceName, String accessToken, ServiceChangeListener listener) {
            this.listener = listener;
        }

        @Override
        public ServiceSnapshot fetchInstances() {
            return new ServiceSnapshot(1L, copies());
        }

        @Override
        public List<String> fetchServices() {
            return Collections.singletonList("order-service");
        }

        @Override
        public ServiceInstance register(ServiceInstance instance) {
            registration = copy(instance);
            if (registration.getLeaseStartedAt() == null) {
                registration.setLeaseStartedAt(System.currentTimeMillis());
            }
            registration.setLastHeartbeatAt(System.currentTimeMillis());
            instances.add(copy(registration));
            return copy(registration);
        }

        @Override
        public ServiceInstance heartbeat(String instanceId) {
            heartbeatCalled = true;
            for (ServiceInstance item : instances) {
                if (instanceId.equals(item.getInstanceId())) {
                    item.setLastHeartbeatAt(System.currentTimeMillis());
                    return copy(item);
                }
            }
            return null;
        }

        @Override
        public boolean deregister(String instanceId) {
            return instances.removeIf(item -> instanceId.equals(item.getInstanceId()));
        }

        @Override
        public void close() {
        }

        private void emit(String eventType, long revision, ServiceInstance changed) {
            instances.removeIf(item -> changed.getInstanceId().equals(item.getInstanceId()));
            if (!"INSTANCE_DEREGISTERED".equals(eventType)
                    && !"INSTANCE_EXPIRED".equals(eventType)) {
                instances.add(copy(changed));
            }
            listener.onChanged(eventType, copy(changed),
                    new ServiceSnapshot(revision, copies()));
        }

        private List<ServiceInstance> copies() {
            List<ServiceInstance> result = new ArrayList<ServiceInstance>();
            for (ServiceInstance instance : instances) result.add(copy(instance));
            return result;
        }
    }
}
