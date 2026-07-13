package cloud.xuantong.core.v2.service;

import cloud.xuantong.core.v2.config.BrokerNodeConfig;
import cloud.xuantong.core.v2.event.ServiceInstanceEvent;
import cloud.xuantong.core.v2.model.ResourceNameRules;
import cloud.xuantong.core.v2.model.ServiceInstance;
import cloud.xuantong.core.v2.model.ServiceKey;
import cloud.xuantong.core.v2.model.ServiceSnapshot;
import cloud.xuantong.core.v2.repository.ServiceDefinitionRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ServiceInstanceRegistry {
    private static final Logger log = LoggerFactory.getLogger(ServiceInstanceRegistry.class);

    @Inject
    private ServiceDefinitionRepository serviceRepository;
    @Inject
    private BrokerNodeConfig nodeConfig;
    @Inject("${discovery.instanceTimeoutMs:30000}")
    private long instanceTimeoutMs;
    @Inject("${discovery.cleanupIntervalMs:5000}")
    private long cleanupIntervalMs;

    private final Map<ServiceKey, ServiceState> services = new ConcurrentHashMap<>();
    private final AtomicLong registerTotal = new AtomicLong();
    private final AtomicLong heartbeatTotal = new AtomicLong();
    private final AtomicLong deregisterTotal = new AtomicLong();
    private final AtomicLong expiredTotal = new AtomicLong();
    private volatile boolean cleanupHealthy = true;
    private ScheduledExecutorService cleanupExecutor;

    @Init
    public void start() {
        if (instanceTimeoutMs <= 0 || cleanupIntervalMs <= 0) {
            throw new IllegalArgumentException("Discovery timeout settings must be positive");
        }
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "xuantong-instance-expirer");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleAtFixedRate(
                this::expireInstancesSafely,
                cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
    }

    public ServiceInstance register(
            String namespaceId, String groupName, String serviceName, ServiceInstance request) {
        ServiceKey key = ServiceKey.of(namespaceId, groupName, serviceName);
        if (serviceRepository.find(key) == null) {
            throw new IllegalArgumentException("Service does not exist: " + key.canonicalName());
        }
        if (request == null) {
            throw new IllegalArgumentException("Service instance body must not be null");
        }

        String ip = requireText("ip", request.getIp(), 255);
        int port = requirePort(request.getPort());
        String instanceId = ResourceNameRules.validate("instanceId", request.getInstanceId());

        double weight = request.getWeight() == null ? 1D : request.getWeight();
        if (!Double.isFinite(weight) || weight <= 0D) {
            throw new IllegalArgumentException("weight must be greater than 0");
        }

        long now = System.currentTimeMillis();
        String leaseId = ResourceNameRules.validate("leaseId", request.getLeaseId());

        ServiceState state = services.computeIfAbsent(key, ignored -> new ServiceState());
        ServiceInstance instance;
        String eventType;
        long revision;
        synchronized (state) {
            ServiceInstance existing = state.instances.get(instanceId);
            long leaseStartedAt = existing != null && leaseId.equals(existing.getLeaseId())
                    ? existing.getLeaseStartedAt()
                    : Math.max(now, state.lastLeaseStartedAt + 1L);
            state.lastLeaseStartedAt = Math.max(state.lastLeaseStartedAt, leaseStartedAt);
            instance = new ServiceInstance();
            instance.setNamespaceId(key.namespaceId());
            instance.setGroupName(key.groupName());
            instance.setServiceName(key.serviceName());
            instance.setInstanceId(instanceId);
            instance.setLeaseId(leaseId);
            instance.setLeaseStartedAt(leaseStartedAt);
            instance.setIp(ip);
            instance.setPort(port);
            instance.setWeight(weight);
            instance.setHealthy(true);
            instance.setEnabled(request.getEnabled() == null || request.getEnabled());
            instance.setMetadata(request.getMetadata());
            instance.setOwnerNodeId(nodeConfig.getNodeId());
            instance.setRegisteredAt(existing == null || !leaseId.equals(existing.getLeaseId())
                    ? now : existing.getRegisteredAt());
            instance.setLastHeartbeatAt(now);
            revision = state.revision.incrementAndGet();
            state.instances.put(instanceId, instance);
            state.revision.set(revision);
            eventType = existing == null ? "INSTANCE_REGISTERED" : "INSTANCE_UPDATED";
        }
        publish(eventType, key, revision, instance);
        registerTotal.incrementAndGet();
        return copy(instance);
    }

    public ServiceInstance heartbeat(
            String namespaceId,
            String groupName,
            String serviceName,
            String instanceId,
            String leaseId) {
        ServiceKey key = ServiceKey.of(namespaceId, groupName, serviceName);
        String normalizedInstanceId = ResourceNameRules.validate("instanceId", instanceId);
        ServiceState state = services.get(key);
        ServiceInstance instance;
        long revision;
        if (state == null) {
            throw new IllegalArgumentException("Service instance does not exist: " + normalizedInstanceId);
        }
        synchronized (state) {
            instance = state.instances.get(normalizedInstanceId);
            if (instance == null) {
                throw new IllegalArgumentException("Service instance does not exist: " + normalizedInstanceId);
            }
            requireLease(instance, leaseId);
            revision = state.revision.incrementAndGet();
            instance.setLastHeartbeatAt(System.currentTimeMillis());
            instance.setHealthy(true);
            state.revision.set(revision);
        }
        publish("INSTANCE_HEARTBEAT", key, revision, instance);
        heartbeatTotal.incrementAndGet();
        return copy(instance);
    }

    public boolean deregister(
            String namespaceId,
            String groupName,
            String serviceName,
            String instanceId,
            String leaseId) {
        return deregisterInternal(
                namespaceId, groupName, serviceName, instanceId, leaseId, true);
    }

    public boolean forceDeregister(
            String namespaceId, String groupName, String serviceName, String instanceId) {
        return deregisterInternal(
                namespaceId, groupName, serviceName, instanceId, null, false);
    }

    private boolean deregisterInternal(
            String namespaceId,
            String groupName,
            String serviceName,
            String instanceId,
            String leaseId,
            boolean verifyLease) {
        ServiceKey key = ServiceKey.of(namespaceId, groupName, serviceName);
        String normalizedInstanceId = ResourceNameRules.validate("instanceId", instanceId);
        ServiceState state = services.get(key);
        if (state == null) {
            return false;
        }
        ServiceInstance removed;
        long revision;
        synchronized (state) {
            removed = state.instances.get(normalizedInstanceId);
            if (removed == null) {
                return false;
            }
            if (verifyLease) {
                requireLease(removed, leaseId);
            }
            revision = state.revision.incrementAndGet();
            state.instances.remove(normalizedInstanceId);
            state.revision.set(revision);
        }
        publish("INSTANCE_DEREGISTERED", key, revision, removed);
        deregisterTotal.incrementAndGet();
        return true;
    }

    public ServiceSnapshot snapshot(
            String namespaceId, String groupName, String serviceName, boolean onlyAvailable) {
        return snapshot(ServiceKey.of(namespaceId, groupName, serviceName), onlyAvailable);
    }

    public ServiceSnapshot snapshot(ServiceKey key, boolean onlyAvailable) {
        ServiceState state = services.get(key);
        if (state == null) {
            return new ServiceSnapshot(key, 0L, List.of());
        }
        List<ServiceInstance> instances = new ArrayList<>();
        for (ServiceInstance instance : state.instances.values()) {
            if (!onlyAvailable || (Boolean.TRUE.equals(instance.getHealthy())
                    && Boolean.TRUE.equals(instance.getEnabled()))) {
                instances.add(copy(instance));
            }
        }
        instances.sort(Comparator.comparing(ServiceInstance::getInstanceId));
        return new ServiceSnapshot(key, state.revision.get(), List.copyOf(instances));
    }

    public boolean hasInstances(ServiceKey key) {
        ServiceState state = services.get(key);
        return state != null && !state.instances.isEmpty();
    }

    public void removeService(ServiceKey key) {
        services.remove(key);
    }

    void expireInstances() {
        long now = System.currentTimeMillis();
        for (Map.Entry<ServiceKey, ServiceState> serviceEntry : services.entrySet()) {
            ServiceKey key = serviceEntry.getKey();
            ServiceState state = serviceEntry.getValue();
            for (Map.Entry<String, ServiceInstance> instanceEntry : state.instances.entrySet()) {
                ServiceInstance instance = instanceEntry.getValue();
                if (now - instance.getLastHeartbeatAt() > instanceTimeoutMs) {
                    long revision = 0L;
                    boolean removed = false;
                    synchronized (state) {
                        if (state.instances.get(instanceEntry.getKey()) == instance
                                && now - instance.getLastHeartbeatAt() > instanceTimeoutMs) {
                            revision = state.revision.incrementAndGet();
                            state.instances.remove(instanceEntry.getKey());
                            state.revision.set(revision);
                            instance.setHealthy(false);
                            removed = true;
                        }
                    }
                    if (removed) {
                        publish("INSTANCE_EXPIRED", key, revision, instance);
                        expiredTotal.incrementAndGet();
                    }
                }
            }
        }
    }

    private void expireInstancesSafely() {
        try {
            expireInstances();
            cleanupHealthy = true;
        } catch (RuntimeException e) {
            cleanupHealthy = false;
            log.warn("Discovery instance cleanup failed; it will retry on the next interval", e);
        }
    }

    public long activeInstanceCount() { return services.values().stream().mapToLong(state -> state.instances.size()).sum(); }
    public long trackedServiceCount() { return services.size(); }
    public long registerTotal() { return registerTotal.get(); }
    public long heartbeatTotal() { return heartbeatTotal.get(); }
    public long deregisterTotal() { return deregisterTotal.get(); }
    public long expiredTotal() { return expiredTotal.get(); }
    public boolean cleanupRunning() {
        return cleanupExecutor != null && !cleanupExecutor.isShutdown() && cleanupHealthy;
    }
    public boolean cleanupHealthy() { return cleanupHealthy; }

    private void publish(String eventType, ServiceKey key, long revision, ServiceInstance instance) {
        EventBus.publish(new ServiceInstanceEvent(eventType, key, revision, copy(instance)));
    }

    private ServiceInstance copy(ServiceInstance source) {
        ServiceInstance target = new ServiceInstance();
        target.setNamespaceId(source.getNamespaceId());
        target.setGroupName(source.getGroupName());
        target.setServiceName(source.getServiceName());
        target.setInstanceId(source.getInstanceId());
        target.setLeaseId(source.getLeaseId());
        target.setLeaseStartedAt(source.getLeaseStartedAt());
        target.setIp(source.getIp());
        target.setPort(source.getPort());
        target.setWeight(source.getWeight());
        target.setHealthy(source.getHealthy());
        target.setEnabled(source.getEnabled());
        target.setMetadata(source.getMetadata());
        target.setOwnerNodeId(source.getOwnerNodeId());
        target.setRegisteredAt(source.getRegisteredAt());
        target.setLastHeartbeatAt(source.getLastHeartbeatAt());
        return target;
    }

    private void requireLease(ServiceInstance instance, String leaseId) {
        String normalizedLeaseId = ResourceNameRules.validate("leaseId", leaseId);
        if (!normalizedLeaseId.equals(instance.getLeaseId())) {
            throw new IllegalArgumentException(
                    "Service instance lease does not match: " + instance.getInstanceId());
        }
    }

    private String requireText(String field, String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }

    private int requirePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return port;
    }

    @Destroy
    public void stop() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
    }

    private static class ServiceState {
        private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();
        private final AtomicLong revision = new AtomicLong();
        private long lastLeaseStartedAt;
    }
}
