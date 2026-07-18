package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.model.ServiceInstance;

import java.util.List;

/** Narrow operations used by Spring Cloud adapters, independent of transport ownership. */
public interface XuantongDiscoveryOperations extends AutoCloseable {
    ServiceInstance register(ServiceInstance registration);

    boolean deregister();

    List<ServiceInstance> getInstances();

    List<String> getServices();

    @Override
    void close();
}
