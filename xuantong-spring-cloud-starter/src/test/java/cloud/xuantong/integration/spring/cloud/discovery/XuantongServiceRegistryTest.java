package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XuantongServiceRegistryTest {

    @Test
    void registersDeregistersAndUpdatesStatusThroughOneLeaseOwner() {
        FakeDiscoveryOperations discoveryClient = new FakeDiscoveryOperations();
        XuantongDiscoveryClientProvider manager = new XuantongDiscoveryClientProvider() {
            @Override
            public XuantongDiscoveryOperations get(String serviceName) {
                return discoveryClient;
            }

            @Override
            public XuantongDiscoveryOperations getIfPresent(String serviceName) {
                return discoveryClient;
            }

            @Override
            public void close() {
            }
        };

        XuantongSpringCloudProperties properties = new XuantongSpringCloudProperties();
        XuantongServiceRegistry registry = new XuantongServiceRegistry(
                manager, new XuantongServiceInstanceMapper(), properties);
        XuantongRegistration registration = new XuantongRegistration(
                "order-1", "order-service", "10.0.0.8", 8080,
                false, 2D, Map.of("version", "v1"));

        registry.register(registration);

        assertEquals("order-1", discoveryClient.registered.getInstanceId());
        assertEquals("public", discoveryClient.registered.getNamespaceId());
        assertEquals("DEFAULT_GROUP", discoveryClient.registered.getGroupName());
        assertTrue(discoveryClient.registered.getEnabled());
        assertEquals("UP", registry.<String>getStatus(registration));

        registry.setStatus(registration, "DOWN");

        assertTrue(discoveryClient.deregistered);
        assertEquals("DOWN", registry.<String>getStatus(registration));
    }

    private static final class FakeDiscoveryOperations implements XuantongDiscoveryOperations {
        private ServiceInstance registered;
        private boolean deregistered;

        @Override
        public ServiceInstance register(ServiceInstance registration) {
            this.registered = registration;
            return registration;
        }

        @Override
        public boolean deregister() {
            deregistered = true;
            return true;
        }

        @Override
        public List<ServiceInstance> getInstances() {
            return List.of();
        }

        @Override
        public List<String> getServices() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
