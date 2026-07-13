package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.model.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultiBrokerServiceStateTest {
    @Test
    void mergesDuplicateInstanceAndRemovesItOnlyAfterEveryBrokerDoes() {
        MultiBrokerServiceState state = new MultiBrokerServiceState();
        ServiceInstance instance = instance("node-1", "lease-1", 100L, 1000L);

        state.replaceBroker("broker-a", 1L, Collections.singletonList(instance));
        assertNull(state.replaceBroker("broker-b", 1L, Collections.singletonList(instance)));
        assertEquals(1, state.snapshot().getInstances().size());
        assertEquals(1L, state.snapshot().getRevision());

        state.applyEvent("broker-a", "INSTANCE_EXPIRED", 2L, instance);
        assertEquals(1, state.snapshot().getInstances().size());
        assertEquals(1L, state.snapshot().getRevision());

        state.applyEvent("broker-b", "INSTANCE_EXPIRED", 2L, instance);
        assertEquals(0, state.snapshot().getInstances().size());
        assertEquals(2L, state.snapshot().getRevision());
    }

    @Test
    void tracksBrokerRevisionsIndependentlyAndRejectsStaleSnapshot() {
        MultiBrokerServiceState state = new MultiBrokerServiceState();
        ServiceInstance first = instance("node-a", "lease-a", 100L, 1000L);
        ServiceInstance second = instance("node-b", "lease-b", 100L, 1000L);

        state.applyEvent("broker-a", "INSTANCE_REGISTERED", 5L, first);
        state.applyEvent("broker-b", "INSTANCE_REGISTERED", 1L, second);
        assertEquals(2, state.snapshot().getInstances().size());

        assertNull(state.applyEvent("broker-a", "INSTANCE_EXPIRED", 4L, first));
        assertNull(state.replaceBroker("broker-a", 3L, Collections.<ServiceInstance>emptyList()));
        assertEquals(2, state.snapshot().getInstances().size());
    }

    @Test
    void choosesTheSameLeaseWinnerRegardlessOfBrokerIterationOrder() {
        MultiBrokerServiceState state = new MultiBrokerServiceState();
        ServiceInstance older = instance("node-1", "lease-a", 100L, 5000L);
        ServiceInstance newer = instance("node-1", "lease-b", 100L, 1000L);
        newer.setIp("10.0.0.2");

        state.replaceBroker("broker-a", 1L, Arrays.asList(older));
        state.replaceBroker("broker-b", 1L, Arrays.asList(newer));

        assertEquals("lease-b", state.snapshot().getInstances().get(0).getLeaseId());
        assertEquals("10.0.0.2", state.snapshot().getInstances().get(0).getIp());
    }

    @Test
    void brokerDisconnectRecomputesTheReachableUnion() {
        MultiBrokerServiceState state = new MultiBrokerServiceState();
        state.replaceBroker("broker-a", 1L, Collections.singletonList(
                instance("node-a", "lease-a", 100L, 1000L)));
        state.replaceBroker("broker-b", 1L, Collections.singletonList(
                instance("node-b", "lease-b", 100L, 1000L)));

        state.removeBroker("broker-a");
        assertEquals(1, state.snapshot().getInstances().size());
        assertEquals("node-b", state.snapshot().getInstances().get(0).getInstanceId());
    }

    private ServiceInstance instance(
            String instanceId, String leaseId, long leaseStartedAt, long heartbeatAt) {
        ServiceInstance instance = new ServiceInstance();
        instance.setInstanceId(instanceId);
        instance.setLeaseId(leaseId);
        instance.setLeaseStartedAt(leaseStartedAt);
        instance.setIp("10.0.0.1");
        instance.setPort(8080);
        instance.setWeight(1D);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setLastHeartbeatAt(heartbeatAt);
        return instance;
    }
}
