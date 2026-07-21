package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.model.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XuantongReactiveDiscoveryClientTest {

    @Test
    void suppliesInstancesAndServiceCatalogThroughReactiveSpi() {
        ServiceInstance source = new ServiceInstance();
        source.setServiceName("order-service");
        source.setInstanceId("order-1");
        source.setIp("10.0.0.8");
        source.setPort(8080);
        source.setHealthy(true);
        source.setEnabled(true);
        XuantongDiscoveryOperations operations = new XuantongDiscoveryOperations() {
            @Override
            public ServiceInstance register(ServiceInstance registration) {
                return registration;
            }

            @Override
            public boolean deregister() {
                return true;
            }

            @Override
            public List<ServiceInstance> getInstances() {
                return List.of(source);
            }

            @Override
            public void close() {
            }
        };
        XuantongDiscoveryClientProvider provider = new XuantongDiscoveryClientProvider() {
            @Override
            public XuantongDiscoveryOperations get(String serviceName) {
                return operations;
            }

            @Override
            public XuantongDiscoveryOperations getIfPresent(String serviceName) {
                return operations;
            }

            @Override
            public List<String> getServices() {
                return List.of("order-service");
            }

            @Override
            public void close() {
            }
        };
        XuantongReactiveDiscoveryClient client = new XuantongReactiveDiscoveryClient(
                provider, new XuantongServiceInstanceMapper(),
                "public", "DEFAULT_GROUP");

        assertEquals("order-1", client.getInstances("order-service")
                .collectList().block().getFirst().getInstanceId());
        assertEquals(List.of("order-service"), client.getServices().collectList().block());
    }
}
