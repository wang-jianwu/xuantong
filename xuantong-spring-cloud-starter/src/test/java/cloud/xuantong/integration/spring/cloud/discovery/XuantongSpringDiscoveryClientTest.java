package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.model.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XuantongSpringDiscoveryClientTest {

    @Test
    void suppliesInstancesAndServiceCatalogThroughSpringCloudSpi() {
        ServiceInstance source = new ServiceInstance();
        source.setServiceName("order-service");
        source.setInstanceId("order-1");
        source.setIp("10.0.0.8");
        source.setPort(8080);
        source.setHealthy(true);
        source.setEnabled(true);
        XuantongDiscoveryOperations orderClient = operations(
                List.of(source), List.of());
        XuantongDiscoveryOperations catalogClient = operations(
                List.of(), List.of("order-service", "pay-service"));
        XuantongDiscoveryClientProvider manager = new XuantongDiscoveryClientProvider() {
            @Override
            public XuantongDiscoveryOperations get(String serviceName) {
                return "order-service".equals(serviceName) ? orderClient : catalogClient;
            }

            @Override
            public XuantongDiscoveryOperations getIfPresent(String serviceName) {
                return get(serviceName);
            }

            @Override
            public void close() {
            }
        };

        XuantongSpringDiscoveryClient client = new XuantongSpringDiscoveryClient(
                manager, new XuantongServiceInstanceMapper(), "public", "DEFAULT_GROUP");

        assertEquals(1, client.getInstances("order-service").size());
        assertEquals("order-1", client.getInstances("order-service").getFirst().getInstanceId());
        assertEquals(List.of("order-service", "pay-service"), client.getServices());
    }

    private XuantongDiscoveryOperations operations(
            List<ServiceInstance> instances, List<String> services) {
        return new XuantongDiscoveryOperations() {
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
                return instances;
            }

            @Override
            public List<String> getServices() {
                return services;
            }

            @Override
            public void close() {
            }
        };
    }
}
