package cloud.xuantong.client.transport;

import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.model.ServiceWatchBatch;

import java.util.List;

public interface DiscoveryTransport extends AutoCloseable {
    void connect(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken);

    ServiceSnapshot fetchInstances();
    List<String> fetchServices();
    ServiceWatchBatch watchBatch(long afterRegistryRevision, int maxBatchSize);
    default WatchSubscription subscribe(
            long afterRegistryRevision,
            WatchBatchHandler<ServiceWatchBatch> handler) {
        throw new UnsupportedOperationException("Discovery Watch subscription is not supported");
    }
    ServiceInstance register(ServiceInstance instance);
    ServiceInstance heartbeat(ServiceInstance instance);
    boolean deregister(ServiceInstance instance);
    void setOnReconnect(Runnable listener);
    @Override
    void close();
}
