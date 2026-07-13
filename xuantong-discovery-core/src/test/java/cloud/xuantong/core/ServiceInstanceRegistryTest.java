package cloud.xuantong.core;

import cloud.xuantong.core.v2.config.BrokerNodeConfig;
import cloud.xuantong.core.v2.model.ServiceDefinition;
import cloud.xuantong.core.v2.model.ServiceInstance;
import cloud.xuantong.core.v2.model.ServiceKey;
import cloud.xuantong.core.v2.model.ServiceSnapshot;
import cloud.xuantong.core.v2.repository.ServiceDefinitionRepository;
import cloud.xuantong.core.v2.service.ServiceInstanceRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceInstanceRegistryTest {
    @Test
    void registersHeartbeatsAndDeregistersUsingTheSameLease() throws Exception {
        ServiceInstanceRegistry registry = registry(30_000L);
        ServiceInstance request = instance("order-node-1", "lease-1", 100L);

        ServiceInstance registered = registry.register(
                "public", "DEFAULT_GROUP", "order-service", request);
        assertEquals("node-test", registered.getOwnerNodeId());
        assertEquals("lease-1", registered.getLeaseId());
        assertTrue(registered.getHealthy());

        ServiceSnapshot snapshot = registry.snapshot(
                "public", "DEFAULT_GROUP", "order-service", true);
        assertEquals(1L, snapshot.revision());
        assertEquals(1, snapshot.instances().size());

        long previousHeartbeat = registered.getLastHeartbeatAt();
        Thread.sleep(2L);
        ServiceInstance heartbeat = registry.heartbeat(
                "public", "DEFAULT_GROUP", "order-service", "order-node-1", "lease-1");
        assertTrue(heartbeat.getLastHeartbeatAt() >= previousHeartbeat);

        assertTrue(registry.deregister(
                "public", "DEFAULT_GROUP", "order-service", "order-node-1", "lease-1"));
        assertEquals(3L, registry.snapshot(
                "public", "DEFAULT_GROUP", "order-service", false).revision());
        assertFalse(registry.hasInstances(
                ServiceKey.of("public", "DEFAULT_GROUP", "order-service")));
        assertEquals(1L, registry.registerTotal());
        assertEquals(1L, registry.heartbeatTotal());
        assertEquals(1L, registry.deregisterTotal());
    }

    @Test
    void requiresClientOwnedMultiBrokerIdentityAndAssignsLeaseTimeOnBroker() throws Exception {
        ServiceInstanceRegistry registry = registry(30_000L);

        ServiceInstance missingInstanceId = instance(null, "lease-1", 100L);
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                "public", "DEFAULT_GROUP", "order-service", missingInstanceId));

        ServiceInstance missingLeaseId = instance("node-1", null, 100L);
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                "public", "DEFAULT_GROUP", "order-service", missingLeaseId));

        ServiceInstance missingLeaseTime = instance("node-1", "lease-1", 100L);
        missingLeaseTime.setLeaseStartedAt(null);
        ServiceInstance registered = registry.register(
                "public", "DEFAULT_GROUP", "order-service", missingLeaseTime);
        assertNotNull(registered.getLeaseStartedAt());
        assertTrue(registered.getLeaseStartedAt() > 0L);
    }

    @Test
    void rejectsOldProcessAfterANewerLeaseTakesOwnership() throws Exception {
        ServiceInstanceRegistry registry = registry(30_000L);
        registry.register("public", "DEFAULT_GROUP", "order-service",
                instance("order-node-1", "lease-old", 100L));
        ServiceInstance current = registry.register(
                "public", "DEFAULT_GROUP", "order-service",
                instance("order-node-1", "lease-new", 200L));
        assertEquals("lease-new", current.getLeaseId());

        assertThrows(IllegalArgumentException.class, () -> registry.heartbeat(
                "public", "DEFAULT_GROUP", "order-service", "order-node-1", "lease-old"));
        assertThrows(IllegalArgumentException.class, () -> registry.deregister(
                "public", "DEFAULT_GROUP", "order-service", "order-node-1", "lease-old"));
        ServiceSnapshot snapshot = registry.snapshot(
                "public", "DEFAULT_GROUP", "order-service", true);
        assertEquals(2L, snapshot.revision());
        assertEquals("lease-new", snapshot.instances().get(0).getLeaseId());
        assertTrue(registry.forceDeregister(
                "public", "DEFAULT_GROUP", "order-service", "order-node-1"));
    }

    @Test
    void ignoresClientClockAndAssignsMonotonicBrokerLeaseTimes() throws Exception {
        ServiceInstanceRegistry registry = registry(30_000L);
        ServiceInstance first = registry.register(
                "public", "DEFAULT_GROUP", "order-service",
                instance("order-node-1", "lease-a", Long.MAX_VALUE));
        ServiceInstance winner = registry.register(
                "public", "DEFAULT_GROUP", "order-service",
                instance("order-node-1", "lease-b", 1L));
        assertEquals("lease-b", winner.getLeaseId());
        assertTrue(winner.getLeaseStartedAt() > first.getLeaseStartedAt());
    }

    @Test
    void expiresInstancesUsingBrokerLocalRevision() throws Exception {
        ServiceInstanceRegistry registry = registry(1L);
        ServiceInstance registered = registry.register(
                "public", "DEFAULT_GROUP", "order-service",
                instance("order-node-1", "lease-1", 100L));
        assertNotNull(registered.getInstanceId());

        Thread.sleep(5L);
        Method expire = ServiceInstanceRegistry.class.getDeclaredMethod("expireInstances");
        expire.setAccessible(true);
        expire.invoke(registry);

        ServiceSnapshot snapshot = registry.snapshot(
                "public", "DEFAULT_GROUP", "order-service", true);
        assertEquals(2L, snapshot.revision());
        assertTrue(snapshot.instances().isEmpty());
        assertEquals(1L, registry.expiredTotal());
    }

    private ServiceInstance instance(String instanceId, String leaseId, long leaseStartedAt) {
        ServiceInstance request = new ServiceInstance();
        request.setInstanceId(instanceId);
        request.setLeaseId(leaseId);
        request.setLeaseStartedAt(leaseStartedAt);
        request.setIp("10.0.0.8");
        request.setPort(8080);
        request.setWeight(2D);
        return request;
    }

    private ServiceInstanceRegistry registry(long timeoutMs) throws Exception {
        ServiceInstanceRegistry registry = new ServiceInstanceRegistry();
        inject(registry, "serviceRepository", new ExistingServiceRepository());
        inject(registry, "nodeConfig", new BrokerNodeConfig() {
            @Override
            public String getNodeId() {
                return "node-test";
            }
        });
        inject(registry, "instanceTimeoutMs", timeoutMs);
        inject(registry, "cleanupIntervalMs", 1000L);
        return registry;
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class ExistingServiceRepository implements ServiceDefinitionRepository {
        @Override
        public ServiceDefinition find(ServiceKey key) {
            ServiceDefinition service = new ServiceDefinition();
            service.setNamespaceId(key.namespaceId());
            service.setGroupName(key.groupName());
            service.setServiceName(key.serviceName());
            return service;
        }

        @Override
        public List<ServiceDefinition> findByGroup(String namespaceId, String groupName) {
            return List.of();
        }

        @Override public long save(ServiceDefinition service) { return 1; }
        @Override public long update(ServiceDefinition service) { return 1; }
        @Override public long delete(ServiceKey key) { return 1; }
    }
}
