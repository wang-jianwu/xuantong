package cloud.xuantong.integration.spring.cloud.discovery;

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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (XuantongDiscoveryOperations client : clients.values()) {
            client.close();
        }
        clients.clear();
    }
}
