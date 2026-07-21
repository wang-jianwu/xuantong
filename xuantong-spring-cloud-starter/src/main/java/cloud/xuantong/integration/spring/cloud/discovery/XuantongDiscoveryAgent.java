package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.XuantongDiscoveryClient;
import cloud.xuantong.client.metrics.LeaseRenewalMetricsSnapshot;
import cloud.xuantong.client.model.ServiceInstance;

import java.util.List;

final class XuantongDiscoveryAgent implements XuantongDiscoveryOperations {
    private final XuantongDiscoveryClient delegate;

    XuantongDiscoveryAgent(XuantongDiscoveryClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ServiceInstance register(ServiceInstance registration) {
        return delegate.register(registration);
    }

    @Override
    public boolean deregister() {
        return delegate.deregister();
    }

    @Override
    public List<ServiceInstance> getInstances() {
        return delegate.getInstances();
    }

    @Override
    public LeaseRenewalMetricsSnapshot leaseRenewalMetrics() {
        return delegate.getLeaseRenewalMetrics();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
