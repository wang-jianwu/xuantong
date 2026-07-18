package cloud.xuantong.client;

import cloud.xuantong.client.discovery.LoadBalanceStrategy;
import cloud.xuantong.client.discovery.ServiceInstanceSelector;
import cloud.xuantong.client.exception.DiscoveryLeaseException;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.listener.ServiceListener;
import cloud.xuantong.client.model.ServiceChangeEvent;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceInvalidation;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.model.ServiceWatchBatch;
import cloud.xuantong.client.transport.DiscoveryTransport;
import cloud.xuantong.client.transport.WatchBatchHandler;
import cloud.xuantong.client.transport.WatchSubscription;
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

/** Registry-State-backed Discovery Agent for one service subscription. */
public class XuantongDiscoveryClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(XuantongDiscoveryClient.class);
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L;
    private static final long WATCH_INTERVAL_MS = 500L;
    private static final long RECONCILE_INTERVAL_MS = 30_000L;
    private static final int WATCH_BATCH_SIZE = 256;

    private final String namespace;
    private final String group;
    private final String serviceName;
    private final DiscoveryTransport transport;
    private final ServiceInstanceSelector selector = new ServiceInstanceSelector();
    private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();
    private final List<ServiceListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService agentExecutor;
    private volatile long revision;
    private volatile ServiceInstance localRegistration;
    private volatile ServiceInstance localInstance;
    private volatile boolean leaseFenced;
    private volatile boolean closed;
    private volatile WatchSubscription watchSubscription;
    private volatile boolean streamingWatch;

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
                DEFAULT_HEARTBEAT_INTERVAL_MS,
                discoveryTransport(
                        ClientIdentity.defaultIdentity(),
                        ControlPlaneOptions.registryDefaults(),
                        DEFAULT_HEARTBEAT_INTERVAL_MS));
    }

    public XuantongDiscoveryClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            long heartbeatIntervalMs) {
        this(serverAddresses, namespace, group, serviceName, accessToken,
                heartbeatIntervalMs,
                discoveryTransport(
                        ClientIdentity.defaultIdentity(),
                        ControlPlaneOptions.registryDefaults(),
                        heartbeatIntervalMs));
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
                heartbeatIntervalMs,
                discoveryTransport(
                        identity,
                        ControlPlaneOptions.registryDefaults(),
                        heartbeatIntervalMs));
    }

    public XuantongDiscoveryClient(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            long heartbeatIntervalMs,
            ClientIdentity identity,
            ControlPlaneOptions options) {
        this(serverAddresses, namespace, group, serviceName, accessToken,
                heartbeatIntervalMs,
                discoveryTransport(identity, options, heartbeatIntervalMs));
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
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.transport = transport;
        this.agentExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-discovery-agent");
            thread.setDaemon(true);
            return thread;
        });
        try {
            transport.connect(
                    serverAddresses,
                    this.namespace,
                    this.group,
                    this.serviceName,
                    accessToken == null ? "" : accessToken);
            transport.setOnReconnect(this::reconcileAfterReconnect);
            refreshFromServer();
            startWatchSubscription();
            agentExecutor.scheduleWithFixedDelay(
                    this::watchSafely,
                    WATCH_INTERVAL_MS,
                    WATCH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            agentExecutor.scheduleAtFixedRate(
                    this::heartbeatSafely,
                    heartbeatIntervalMs,
                    heartbeatIntervalMs,
                    TimeUnit.MILLISECONDS);
            agentExecutor.scheduleWithFixedDelay(
                    this::refreshSafely,
                    RECONCILE_INTERVAL_MS,
                    RECONCILE_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            agentExecutor.shutdownNow();
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
        if (candidate.getInstanceId() == null || candidate.getInstanceId().isBlank()) {
            candidate.setInstanceId(UUID.randomUUID().toString());
        }
        candidate.setServiceGeneration(null);
        candidate.setLeaseId(null);
        candidate.setLeaseEpoch(null);
        candidate.setRecoveryEpoch(null);
        candidate.setRenewSequence(null);
        ServiceInstance registered = transport.register(candidate);
        if (registered == null || registered.getLeaseId() == null) {
            throw new XuantongException("Registry State returned an empty registration");
        }
        localRegistration = copy(candidate);
        localRegistration.setServiceGeneration(registered.getServiceGeneration());
        localInstance = copy(registered);
        leaseFenced = false;
        applyLocalInstance(registered);
        return copy(registered);
    }

    public synchronized ServiceInstance heartbeat() {
        ensureOpen();
        ServiceInstance current = localInstance;
        if (current == null || current.getInstanceId() == null) {
            throw new IllegalStateException("No local service instance has been registered");
        }
        ServiceInstance renewed = transport.heartbeat(copy(current));
        if (renewed == null) {
            throw new XuantongException("Registry State returned an empty renewal");
        }
        localInstance = copy(renewed);
        applyLocalInstance(renewed);
        return copy(renewed);
    }

    public synchronized boolean deregister() {
        ServiceInstance current = localInstance;
        if (current == null || current.getInstanceId() == null) {
            return false;
        }
        boolean removed = transport.deregister(copy(current));
        if (removed) {
            instances.remove(current.getInstanceId());
            localInstance = null;
            localRegistration = null;
            leaseFenced = false;
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
        return Collections.unmodifiableList(new ArrayList<>(transport.fetchServices()));
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
        if (snapshot == null || snapshot.getRevision() < revision) {
            return;
        }
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
        ServiceInstance local = localInstance;
        if (local != null) {
            ServiceInstance authoritative = instances.get(local.getInstanceId());
            if (authoritative != null
                    && local.getLeaseId().equals(authoritative.getLeaseId())) {
                localInstance = copy(authoritative);
            }
        }
    }

    private synchronized long applyWatchBatch(ServiceWatchBatch batch) {
        if (batch == null) {
            throw new IllegalStateException("Discovery Watch returned no batch");
        }
        if (batch.requestedAfterRevision() != revision) {
            throw new IllegalStateException("Discovery Watch cursor does not match local state");
        }
        if (batch.resetRequired()) {
            refreshFromServer();
            return revision;
        }
        for (ServiceInvalidation event : batch.events()) {
            if (event.registryRevision() <= revision) {
                continue;
            }
            ServiceInstance value = event.instance();
            if (value == null || !isAvailable(value)) {
                instances.remove(event.instanceId());
                if (localInstance != null
                        && event.instanceId().equals(localInstance.getInstanceId())) {
                    localInstance = null;
                }
            } else {
                instances.put(event.instanceId(), copy(value));
                if (localInstance != null
                        && event.instanceId().equals(localInstance.getInstanceId())
                        && localInstance.getLeaseId().equals(value.getLeaseId())) {
                    localInstance = copy(value);
                }
            }
            revision = event.registryRevision();
            notifyListeners(event.eventType(), event.registryRevision(), value);
        }
        revision = Math.max(revision, batch.coveredThroughRevision());
        return revision;
    }

    private void startWatchSubscription() {
        try {
            watchSubscription = transport.subscribe(
                    revision,
                    new WatchBatchHandler<>() {
                        @Override
                        public long onBatch(ServiceWatchBatch batch) {
                            return applyWatchBatch(batch);
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (!closed) {
                                logger.debug(
                                        "Discovery Watch stream will resume from the committed cursor: service={}",
                                        serviceName,
                                        error);
                            }
                        }
                    });
            streamingWatch = true;
        } catch (UnsupportedOperationException e) {
            streamingWatch = false;
            logger.debug("Discovery transport uses Watch-Batch fallback: service={}",
                    serviceName);
        }
    }

    private void notifyListeners(
            String eventType, long eventRevision, ServiceInstance instance) {
        ServiceChangeEvent changeEvent = new ServiceChangeEvent(
                namespace,
                group,
                serviceName,
                eventType,
                eventRevision,
                copy(instance),
                getInstances());
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

    private void watchSafely() {
        if (closed || streamingWatch) {
            return;
        }
        try {
            applyWatchBatch(transport.watchBatch(revision, WATCH_BATCH_SIZE));
        } catch (Exception e) {
            logger.debug("Discovery Watch-Batch will retry: service={}", serviceName, e);
        }
    }

    private void heartbeatSafely() {
        if (closed || localRegistration == null || leaseFenced) {
            return;
        }
        try {
            if (localInstance == null) {
                restoreRegistration();
            } else {
                heartbeat();
            }
        } catch (DiscoveryLeaseException e) {
            if (e.reason() == DiscoveryLeaseException.Reason.EXPIRED) {
                try {
                    restoreRegistration();
                } catch (RuntimeException restoreFailure) {
                    logger.warn("Expired service lease could not be restored yet: service={}",
                            serviceName, restoreFailure);
                }
            } else {
                leaseFenced = true;
                logger.error("Service lease or definition generation was fenced; automatic writes are stopped: service={}, instanceId={}",
                        serviceName,
                        localRegistration.getInstanceId(),
                        e);
            }
        } catch (Exception e) {
            logger.warn("Service heartbeat failed; the authoritative lease will be retried: service={}",
                    serviceName, e);
        }
    }

    private synchronized void restoreRegistration() {
        if (closed || localRegistration == null || leaseFenced) {
            return;
        }
        ServiceInstance restored = transport.register(copy(localRegistration));
        localInstance = copy(restored);
        applyLocalInstance(restored);
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

    private void reconcileAfterReconnect() {
        if (!closed) {
            agentExecutor.execute(this::refreshSafely);
        }
    }

    private boolean isAvailable(ServiceInstance instance) {
        return instance != null
                && Boolean.TRUE.equals(instance.getHealthy())
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
        target.setServiceGeneration(source.getServiceGeneration());
        target.setLeaseId(source.getLeaseId());
        target.setLeaseStartedAt(source.getLeaseStartedAt());
        target.setLeaseEpoch(source.getLeaseEpoch());
        target.setRecoveryEpoch(source.getRecoveryEpoch());
        target.setRenewSequence(source.getRenewSequence());
        target.setExpiresAt(source.getExpiresAt());
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

    private static SocketDDiscoveryTransport discoveryTransport(
            ClientIdentity identity,
            ControlPlaneOptions options,
            long heartbeatIntervalMs) {
        long ttl;
        try {
            ttl = Math.max(30_000L, Math.multiplyExact(heartbeatIntervalMs, 3L));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("heartbeatIntervalMs is too large", e);
        }
        return new SocketDDiscoveryTransport(identity, options, ttl);
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
        agentExecutor.shutdownNow();
        WatchSubscription subscription = watchSubscription;
        watchSubscription = null;
        if (subscription != null) {
            subscription.close();
        }
        transport.close();
        instances.clear();
        listeners.clear();
    }
}
