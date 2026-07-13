package cloud.xuantong.client.transport;

import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;

import java.util.List;

public interface DiscoveryTransport extends AutoCloseable {
    void connect(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            ServiceChangeListener listener);

    ServiceSnapshot fetchInstances();
    List<String> fetchServices();
    ServiceInstance register(ServiceInstance instance);
    ServiceInstance heartbeat(String instanceId);
    boolean deregister(String instanceId);
    @Override
    void close();

    @FunctionalInterface
    interface ServiceChangeListener {
        void onChanged(String eventType, ServiceInstance instance, ServiceSnapshot snapshot);
    }
}
