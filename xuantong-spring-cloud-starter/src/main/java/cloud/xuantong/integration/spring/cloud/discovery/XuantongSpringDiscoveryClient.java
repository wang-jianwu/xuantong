package cloud.xuantong.integration.spring.cloud.discovery;

import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.List;

/** Blocking Spring Cloud DiscoveryClient backed by Xuantong Snapshot and Watch state. */
public class XuantongSpringDiscoveryClient implements DiscoveryClient {
    private static final String CATALOG_SERVICE = "xuantong-service-catalog";

    private final XuantongDiscoveryClientProvider manager;
    private final XuantongServiceInstanceMapper mapper;
    private final String namespace;
    private final String group;

    public XuantongSpringDiscoveryClient(
            XuantongDiscoveryClientProvider manager,
            XuantongServiceInstanceMapper mapper,
            String namespace,
            String group) {
        this.manager = manager;
        this.mapper = mapper;
        this.namespace = namespace;
        this.group = group;
    }

    @Override
    public String description() {
        return "Xuantong DiscoveryClient[namespace=" + namespace
                + ", group=" + group + "]";
    }

    @Override
    public List<org.springframework.cloud.client.ServiceInstance> getInstances(
            String serviceId) {
        return manager.get(serviceId).getInstances().stream()
                .map(mapper::toSpring)
                .toList();
    }

    @Override
    public List<String> getServices() {
        return manager.get(CATALOG_SERVICE).getServices();
    }
}
