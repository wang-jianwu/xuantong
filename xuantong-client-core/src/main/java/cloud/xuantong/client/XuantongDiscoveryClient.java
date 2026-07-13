package cloud.xuantong.client;

import cloud.xuantong.client.discovery.LoadBalanceStrategy;
import cloud.xuantong.client.discovery.ServiceInstanceSelector;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.listener.ServiceListener;
import cloud.xuantong.client.model.ServiceChangeEvent;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.transport.DiscoveryTransport;
import cloud.xuantong.client.transport.impl.SocketDDiscoveryTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class XuantongDiscoveryClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(XuantongDiscoveryClient.class);
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L;
    private static final long RECONCILE_INTERVAL_MS = 30_000L;

    private final String namespace;
    private final String group;
    private final String serviceName;
    private final DiscoveryTransport transport;
    private final ServiceInstanceSelector selector = new ServiceInstanceSelector();
    private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();
    private final List<ServiceListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeatExecutor;
    private volatile long revision;
    private volatile ServiceInstance localRegistration;
    private volatile ServiceInstance localInstance;
    private volatile boolean closed;

    public XuantongDiscoveryClient(
            List<String> serverAddresses, String namespace, String group, String serviceName) {
        this(serverAddresses, namespace, group, serviceName, "");
    }

    public XuantongDiscoveryClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken) {
        this(serverAddresses, namespace, group, serviceName, accessToken,
                DEFAULT_HEARTBEAT_INTERVAL_MS, new SocketDDiscoveryTransport());
    }

    public XuantongDiscoveryClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            long heartbeatIntervalMs) {
        this(serverAddresses, namespace, group, serviceName, accessToken,
                heartbeatIntervalMs, new SocketDDiscoveryTransport());
    }

    public XuantongDiscoveryClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            long heartbeatIntervalMs,
            ClientIdentity identity) {
        this(serverAddresses, namespace, group, serviceName, accessToken,
                heartbeatIntervalMs, new SocketDDiscoveryTransport(identity));
    }

    XuantongDiscoveryClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            DiscoveryTransport transport) {
        this(serverAddresses, namespace, group, serviceName, accessToken,
                DEFAULT_HEARTBEAT_INTERVAL_MS, transport);
    }

    private XuantongDiscoveryClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            long heartbeatIntervalMs,
            DiscoveryTransport transport) {
        this.namespace = requireName("namespace", namespace);
        this.group = requireName("group", group);
        this.serviceName = requireName("serviceName", serviceName);
        if (heartbeatIntervalMs <= 0L) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be positive");
        }
        this.transport = transport;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "xuantong-v2-instance-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        try {
            transport.connect(serverAddresses, this.namespace, this.group, this.serviceName,
                    accessToken == null ? "" : accessToken, this::handleServiceChange);
            refreshFromServer();
            heartbeatExecutor.scheduleAtFixedRate(
                    this::heartbeatSafely,
                    heartbeatIntervalMs,
                    heartbeatIntervalMs,
                    TimeUnit.MILLISECONDS);
            heartbeatExecutor.scheduleWithFixedDelay(
                    this::refreshSafely,
                    RECONCILE_INTERVAL_MS,
                    RECONCILE_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            heartbeatExecutor.shutdownNow();
            transport.close();
            throw e;
        }
    }

    public synchronized ServiceInstance register(ServiceInstance registration) {
        ensureOpen();
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        if (localInstance != null) {
            throw new IllegalStateException(
                    "A local service instance is already registered; deregister it first");
        }
        ServiceInstance candidate = copy(registration);
        if (candidate.getInstanceId() == null
                || candidate.getInstanceId().trim().isEmpty()) {
            candidate.setInstanceId(UUID.randomUUID().toString());
        }
        candidate.setLeaseId(UUID.randomUUID().toString());
        ServiceInstance registered = transport.register(candidate);
        if (registered == null) {
            throw new XuantongException("Discovery Broker returned an empty registration");
        }
        localRegistration = copy(candidate);
        localRegistration.setInstanceId(registered.getInstanceId());
        localRegistration.setLeaseStartedAt(registered.getLeaseStartedAt());
        localInstance = copy(registered);
        applyLocalInstance(registered);
        return copy(registered);
    }

    public ServiceInstance heartbeat() {
        ensureOpen();
        ServiceInstance current = localInstance;
        if (current == null || current.getInstanceId() == null) {
            throw new IllegalStateException("No local service instance has been registered");
        }
        localInstance = transport.heartbeat(current.getInstanceId());
        applyLocalInstance(localInstance);
        return copy(localInstance);
    }

    public synchronized boolean deregister() {
        ServiceInstance current = localInstance;
        if (current == null || current.getInstanceId() == null) {
            return false;
        }
        boolean removed = transport.deregister(current.getInstanceId());
        if (removed) {
            instances.remove(current.getInstanceId());
            localInstance = null;
            localRegistration = null;
        }
        return removed;
    }

    public List<ServiceInstance> getInstances() {
        List<ServiceInstance> snapshot = new ArrayList<>();
        for (ServiceInstance instance : instances.values()) {
            if (isAvailable(instance)) {
                snapshot.add(copy(instance));
            }
        }
        snapshot.sort((left, right) -> left.getInstanceId().compareTo(right.getInstanceId()));
        return Collections.unmodifiableList(snapshot);
    }

    public List<String> getServices() {
        ensureOpen();
        List<String> services = new ArrayList<>(transport.fetchServices());
        Collections.sort(services);
        return Collections.unmodifiableList(services);
    }

    public ServiceInstance selectInstance(LoadBalanceStrategy strategy) {
        ServiceInstance selected = selector.select(getInstances(), strategy);
        return selected == null ? null : copy(selected);
    }

    public void addListener(ServiceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ServiceListener listener) {
        listeners.remove(listener);
    }

    public long getRevision() { return revision; }
    public String getNamespace() { return namespace; }
    public String getGroup() { return group; }
    public String getServiceName() { return serviceName; }

    private synchronized void refreshFromServer() {
        ServiceSnapshot snapshot = transport.fetchInstances();
        replaceInstances(snapshot);
    }

    private void replaceInstances(ServiceSnapshot snapshot) {
        instances.clear();
        for (ServiceInstance instance : snapshot.getInstances()) {
            if (isAvailable(instance)) {
                instances.put(instance.getInstanceId(), copy(instance));
            }
        }
        revision = snapshot.getRevision();
    }

    private synchronized void handleServiceChange(
            String eventType, ServiceInstance instance, ServiceSnapshot snapshot) {
        if (closed || snapshot == null || snapshot.getRevision() <= revision) return;
        replaceInstances(snapshot);
        notifyListeners(eventType, snapshot.getRevision(), instance);
    }

    private void notifyListeners(String eventType, long eventRevision, ServiceInstance instance) {
        ServiceChangeEvent changeEvent = new ServiceChangeEvent(
                namespace, group, serviceName, eventType, eventRevision,
                copy(instance), getInstances());
        for (ServiceListener listener : listeners) {
            try {
                listener.onServiceChange(changeEvent);
            } catch (Exception e) {
                logger.warn("Service listener failed: service={}", serviceName, e);
            }
        }
    }

    private void applyLocalInstance(ServiceInstance instance) {
        if (isAvailable(instance)) {
            instances.put(instance.getInstanceId(), copy(instance));
        }
    }

    private void heartbeatSafely() {
        if (closed || localRegistration == null) {
            return;
        }
        try {
            heartbeat();
        } catch (Exception heartbeatError) {
            logger.warn("Service heartbeat failed; disconnected Brokers will restore registration on reconnect: service={}",
                    serviceName, heartbeatError);
        }
    }

    private void refreshSafely() {
        if (closed) {
            return;
        }
        try {
            refreshFromServer();
        } catch (Exception e) {
            logger.warn("Periodic discovery reconciliation failed; it will retry: service={}",
                    serviceName, e);
        }
    }

    private boolean isAvailable(ServiceInstance instance) {
        return instance != null && Boolean.TRUE.equals(instance.getHealthy())
                && Boolean.TRUE.equals(instance.getEnabled());
    }

    private ServiceInstance copy(ServiceInstance source) {
        if (source == null) {
            return null;
        }
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

    private String requireName(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value;
    }

    private void ensureOpen() {
        if (closed) {
            throw new XuantongException("Discovery client is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            deregister();
        } catch (Exception e) {
            logger.debug("Failed to deregister local instance during close", e);
        }
        closed = true;
        heartbeatExecutor.shutdownNow();
        transport.close();
        instances.clear();
        listeners.clear();
    }
}
