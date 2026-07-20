package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.metrics.LeaseRenewalMetricsSnapshot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one service-scoped Discovery Agent per service name. */
public class XuantongDiscoveryClientManager implements XuantongDiscoveryClientProvider {
    private final XuantongDiscoveryClientFactory factory;
    private final Map<String, XuantongDiscoveryOperations> clients = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public XuantongDiscoveryClientManager(XuantongDiscoveryClientFactory factory) {
        this.factory = factory;
    }

    @Override
    public XuantongDiscoveryOperations get(String serviceName) {
        if (closed.get()) {
            throw new IllegalStateException("Xuantong discovery client manager is closed");
        }
        return clients.computeIfAbsent(serviceName, factory::create);
    }

    @Override
    public XuantongDiscoveryOperations getIfPresent(String serviceName) {
        return clients.get(serviceName);
    }

    public List<LeaseRenewalMetricsSnapshot> leaseRenewalMetrics() {
        return clients.values().stream()
                .map(XuantongDiscoveryOperations::leaseRenewalMetrics)
                .filter(java.util.Objects::nonNull)
                .filter(metrics -> metrics.registered()
                        || metrics.successCount() > 0L
                        || metrics.failureCount() > 0L)
                .toList();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (XuantongDiscoveryOperations client : clients.values()) {
            client.close();
        }
        clients.clear();
        factory.close();
    }
}
