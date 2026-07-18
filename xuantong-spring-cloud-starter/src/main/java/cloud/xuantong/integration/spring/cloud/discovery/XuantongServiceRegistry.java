package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Spring Cloud ServiceRegistry backed by Xuantong's fenced Registry leases. */
public class XuantongServiceRegistry implements ServiceRegistry<XuantongRegistration> {
    private final XuantongDiscoveryClientProvider manager;
    private final XuantongServiceInstanceMapper mapper;
    private final XuantongSpringCloudProperties properties;
    private final Map<String, XuantongRegistration> registrations = new ConcurrentHashMap<>();

    public XuantongServiceRegistry(
            XuantongDiscoveryClientProvider manager,
            XuantongServiceInstanceMapper mapper,
            XuantongSpringCloudProperties properties) {
        this.manager = manager;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public void register(XuantongRegistration registration) {
        requireUsable(registration);
        String key = key(registration);
        XuantongRegistration existing = registrations.get(key);
        if (existing != null && "UP".equals(existing.getStatus())) {
            return;
        }
        registration.setStatus("UP");
        XuantongDiscoveryOperations client = manager.get(registration.getServiceId());
        client.register(mapper.toXuantong(
                registration, properties.getNamespace(), properties.getGroup()));
        registrations.put(key, registration);
    }

    @Override
    public void deregister(XuantongRegistration registration) {
        if (registration == null) {
            return;
        }
        XuantongRegistration removed = registrations.remove(key(registration));
        XuantongDiscoveryOperations client = manager.getIfPresent(registration.getServiceId());
        if (removed != null && client != null) {
            client.deregister();
        }
        registration.setStatus("OUT_OF_SERVICE");
    }

    @Override
    public void close() {
        for (XuantongRegistration registration : List.copyOf(registrations.values())) {
            deregister(registration);
        }
        manager.close();
    }

    @Override
    public void setStatus(XuantongRegistration registration, String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if ("UP".equals(normalized)) {
            register(registration);
            return;
        }
        deregister(registration);
        registration.setStatus(normalized.isEmpty() ? "UNKNOWN" : normalized);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getStatus(XuantongRegistration registration) {
        return (T) registration.getStatus();
    }

    private void requireUsable(XuantongRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        if (registration.getPort() <= 0 || registration.getPort() > 65_535) {
            throw new IllegalArgumentException("registration port must be between 1 and 65535");
        }
    }

    private String key(XuantongRegistration registration) {
        return registration.getServiceId() + "\u0000" + registration.getInstanceId();
    }
}
