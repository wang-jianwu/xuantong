package cloud.xuantong.core;

import cloud.xuantong.core.v2.event.ControlPlaneEvent;
import cloud.xuantong.core.v2.listener.ConfigBrokerV2Listener;
import cloud.xuantong.core.v2.listener.DiscoveryBrokerV2Listener;
import cloud.xuantong.core.v2.model.ConfigRelease;
import cloud.xuantong.core.v2.model.ServiceInstance;
import cloud.xuantong.core.v2.model.ServiceKey;
import cloud.xuantong.core.v2.event.ServiceInstanceEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ControlPlaneEventTest {
    @Test
    void createsRevisionedConfigEventAndSubscriberName() {
        ConfigRelease release = new ConfigRelease();
        release.setNamespaceId("mall-prod");
        release.setGroupName("PAYMENT_GROUP");
        release.setDataId("payment.yml");
        release.setRevision(7L);

        ControlPlaneEvent event = ControlPlaneEvent.create(
                "CONFIG_PUBLISHED", release.getNamespaceId(), release.getGroupName(),
                release.getDataId(), release.getRevision(), "node-a", release);

        assertNotNull(event.getEventId());
        assertEquals(7L, event.getRevision());
        assertEquals("payment.yml", event.getResourceName());
        assertEquals("config:mall-prod:PAYMENT_GROUP",
                ConfigBrokerV2Listener.subscriberName("mall-prod", "PAYMENT_GROUP"));
    }

    @Test
    void createsRevisionedDiscoveryEventAndSubscriberName() {
        ServiceKey key = ServiceKey.of("mall-prod", "PAYMENT_GROUP", "payment-service");
        ServiceInstance instance = new ServiceInstance();
        instance.setInstanceId("node-1");

        ServiceInstanceEvent instanceEvent = new ServiceInstanceEvent(
                "INSTANCE_REGISTERED", key, 9L, instance);
        ControlPlaneEvent event = ControlPlaneEvent.create(
                instanceEvent.eventType(), key.namespaceId(), key.groupName(), key.serviceName(),
                instanceEvent.revision(), "node-a", instance);

        assertEquals(9L, event.getRevision());
        assertEquals("payment-service", event.getResourceName());
        assertEquals("discovery:mall-prod:PAYMENT_GROUP:payment-service",
                DiscoveryBrokerV2Listener.subscriberName(
                        "mall-prod", "PAYMENT_GROUP", "payment-service"));
    }
}
