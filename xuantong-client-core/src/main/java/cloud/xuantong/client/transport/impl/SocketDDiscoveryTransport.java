package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.model.ControlPlaneEvent;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.serializer.Serializer;
import cloud.xuantong.client.transport.DiscoveryTransport;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Socket.D multi-broker discovery transport.
 *
 * Providers register and renew the same lease on every active Broker. All Broker
 * operations execute concurrently, while Socket.D independently reconnects each link.
 */
public class SocketDDiscoveryTransport implements DiscoveryTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDDiscoveryTransport.class);
    private static final long HEARTBEAT_INTERVAL = 20_000L;
    private static final long CONNECTION_TIMEOUT = 5_000L;
    private static final long REQUEST_TIMEOUT = 5_000L;
    private static final long OPERATION_TIMEOUT = REQUEST_TIMEOUT * 2L + 1_000L;

    private final ClientIdentity identity;

    private final Serializer serializer = Serializer.defaultSerializer();
    private final MultiBrokerServiceState serviceState = new MultiBrokerServiceState();
    private final Map<Integer, ClientSession> sessions =
            new ConcurrentHashMap<Integer, ClientSession>();
    private volatile List<String> discoveryUrls = Collections.emptyList();
    private volatile String subscriberName = "";
    private volatile String accessToken = "";
    private volatile ServiceChangeListener changeListener;
    private volatile ServiceInstance registration;
    private volatile boolean closed;
    private ExecutorService operationExecutor;

    public SocketDDiscoveryTransport() {
        this(ClientIdentity.defaultIdentity());
    }

    public SocketDDiscoveryTransport(ClientIdentity identity) {
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        this.identity = identity;
    }

    @Override
    public void connect(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken,
            ServiceChangeListener listener) {
        if (serverAddresses == null || serverAddresses.isEmpty()) {
            throw new IllegalArgumentException("serverAddresses must not be empty");
        }
        this.changeListener = listener;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.subscriberName = "discovery:" + namespace + ":" + group + ":" + serviceName;
        this.discoveryUrls = buildDiscoveryUrls(
                serverAddresses, namespace, group, serviceName);
        this.operationExecutor = newDaemonPool(
                Math.min(16, Math.max(4, discoveryUrls.size() * 2)),
                "xuantong-discovery-broker-operation");
        openAllSessions();
        if (availableSessions().isEmpty()) {
            logger.warn("All discovery-v2 Brokers are unavailable; Socket.D will reconnect in background");
        }
    }

    @Override
    public ServiceSnapshot fetchInstances() {
        List<BrokerResult<ServiceSnapshot>> results = executeAcross(
                availableSessions(), new BrokerOperation<ServiceSnapshot>() {
                    @Override
                    public ServiceSnapshot execute(int brokerIndex, ClientSession session)
                            throws Exception {
                        return fetchBrokerSnapshot(session);
                    }
                }, REQUEST_TIMEOUT + 1_000L);
        for (BrokerResult<ServiceSnapshot> result : results) {
            if (result.error == null) {
                serviceState.replaceBroker(
                        brokerId(result.brokerIndex),
                        result.value.getRevision(),
                        result.value.getInstances());
            } else {
                logger.warn("Failed to fetch instances from discovery Broker {}",
                        result.brokerIndex, result.error);
            }
        }
        return serviceState.snapshot();
    }

    @Override
    public List<String> fetchServices() {
        List<BrokerResult<List<String>>> results = executeAcross(
                availableSessions(), new BrokerOperation<List<String>>() {
                    @Override
                    public List<String> execute(int brokerIndex, ClientSession session)
                            throws Exception {
                        return fetchServicesFromSession(session);
                    }
                }, REQUEST_TIMEOUT + 1_000L);
        Set<String> services = new TreeSet<String>();
        int successes = 0;
        for (BrokerResult<List<String>> result : results) {
            if (result.error == null) {
                services.addAll(result.value);
                successes++;
            } else {
                logger.warn("Failed to fetch services from discovery Broker {}",
                        result.brokerIndex, result.error);
            }
        }
        if (successes == 0) {
            throw new XuantongException("Discovery Brokers are unavailable");
        }
        return Collections.unmodifiableList(new ArrayList<String>(services));
    }

    @Override
    public ServiceInstance register(ServiceInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        registration = copy(instance);
        final ServiceInstance requested = copy(instance);
        List<BrokerResult<ServiceInstance>> results = executeAcross(
                availableSessions(), new BrokerOperation<ServiceInstance>() {
                    @Override
                    public ServiceInstance execute(int brokerIndex, ClientSession session)
                            throws Exception {
                        return registerOnSession(session, requested);
                    }
                }, REQUEST_TIMEOUT + 1_000L);
        ServiceInstance representative = null;
        int successes = 0;
        for (BrokerResult<ServiceInstance> result : results) {
            if (result.error == null && result.value != null) {
                if (representative == null || isNewerLease(result.value, representative)) {
                    representative = result.value;
                }
                successes++;
            } else if (result.error != null) {
                logger.warn("Registration failed on discovery Broker {}",
                        result.brokerIndex, result.error);
            }
        }
        if (successes == 0 || representative == null) {
            registration = null;
            throw new XuantongException("No discovery Broker accepted the registration");
        }
        registration = mergeRegistration(requested, representative);
        return copy(representative);
    }

    @Override
    public ServiceInstance heartbeat(final String instanceId) {
        final ServiceInstance currentRegistration = registration;
        if (currentRegistration == null) {
            throw new XuantongException("No local service registration exists");
        }
        List<BrokerResult<ServiceInstance>> results = executeAcross(
                availableSessions(), new BrokerOperation<ServiceInstance>() {
                    @Override
                    public ServiceInstance execute(int brokerIndex, ClientSession session)
                            throws Exception {
                        try {
                            return heartbeatOnSession(
                                    session, instanceId, currentRegistration.getLeaseId());
                        } catch (Exception heartbeatError) {
                            return registerOnSession(session, currentRegistration);
                        }
                    }
                }, OPERATION_TIMEOUT);
        ServiceInstance representative = null;
        int successes = 0;
        for (BrokerResult<ServiceInstance> result : results) {
            if (result.error == null && result.value != null) {
                if (representative == null || isNewerHeartbeat(result.value, representative)) {
                    representative = result.value;
                }
                successes++;
            } else if (result.error != null) {
                logger.warn("Heartbeat and re-registration failed on discovery Broker {}",
                        result.brokerIndex, result.error);
            }
        }
        if (successes == 0 || representative == null) {
            throw new XuantongException("No discovery Broker accepted the heartbeat");
        }
        registration = mergeRegistration(currentRegistration, representative);
        return copy(representative);
    }

    @Override
    public boolean deregister(final String instanceId) {
        final ServiceInstance currentRegistration = registration;
        registration = null;
        if (currentRegistration == null) {
            return false;
        }
        List<BrokerResult<Boolean>> results = executeAcross(
                availableSessions(), new BrokerOperation<Boolean>() {
                    @Override
                    public Boolean execute(int brokerIndex, ClientSession session)
                            throws Exception {
                        return deregisterOnSession(
                                session, instanceId, currentRegistration.getLeaseId());
                    }
                }, REQUEST_TIMEOUT + 1_000L);
        for (BrokerResult<Boolean> result : results) {
            if (result.error != null) {
                logger.warn("Deregistration failed on discovery Broker {}",
                        result.brokerIndex, result.error);
            }
        }
        return true;
    }

    protected ServiceSnapshot fetchBrokerSnapshot(ClientSession session) throws Exception {
        Entity response = SocketDRpcSupport.request(
                session, "/get", new StringEntity("{}").at(subscriberName), REQUEST_TIMEOUT);
        List<ServiceInstance> instances = serializer.deserializeToList(
                response.dataAsString(), ServiceInstance.class);
        return new ServiceSnapshot(parseRevision(response.meta("revision")), instances);
    }

    protected List<String> fetchServicesFromSession(ClientSession session) throws Exception {
        Entity response = SocketDRpcSupport.request(
                session, "/services", new StringEntity("{}").at(subscriberName), REQUEST_TIMEOUT);
        ensureSuccess(response, "fetch services");
        return serializer.deserializeToList(response.dataAsString(), String.class);
    }

    protected ServiceInstance registerOnSession(
            ClientSession session, ServiceInstance instance) throws Exception {
        Entity response = SocketDRpcSupport.request(
                session, "/register",
                new StringEntity(serializer.serialize(instance)).at(subscriberName),
                REQUEST_TIMEOUT);
        ensureSuccess(response, "register service instance");
        return serializer.deserialize(response.dataAsString(), ServiceInstance.class);
    }

    protected ServiceInstance heartbeatOnSession(
            ClientSession session, String instanceId, String leaseId) throws Exception {
        Entity response = SocketDRpcSupport.request(session, "/heartbeat", new StringEntity("")
                .at(subscriberName)
                .metaPut("instanceId", instanceId)
                .metaPut("leaseId", leaseId == null ? "" : leaseId),
                REQUEST_TIMEOUT);
        ensureSuccess(response, "heartbeat service instance");
        return serializer.deserialize(response.dataAsString(), ServiceInstance.class);
    }

    protected boolean deregisterOnSession(
            ClientSession session, String instanceId, String leaseId) throws Exception {
        Entity response = SocketDRpcSupport.request(session, "/deregister", new StringEntity("")
                .at(subscriberName)
                .metaPut("instanceId", instanceId)
                .metaPut("leaseId", leaseId == null ? "" : leaseId),
                REQUEST_TIMEOUT);
        return "true".equals(response.meta("success"));
    }

    protected ClientSession openSession(final int brokerIndex, String url) {
        return SocketD.createClient(url)
                .config(c -> {
                    c.heartbeatInterval(HEARTBEAT_INTERVAL)
                            .connectTimeout(CONNECTION_TIMEOUT)
                            .requestTimeout(REQUEST_TIMEOUT)
                            .autoReconnect(true);
                    if (!accessToken.isEmpty()) {
                        c.metaPut("token", accessToken);
                    }
                })
                .listen(new EventListener()
                        .doOn("/service-change", (session, message) ->
                                handleBrokerEvent(brokerIndex, message.dataAsString()))
                        .doOnOpen(session -> handleOpened(brokerIndex, session))
                        .doOnClose(session -> handleClosed(brokerIndex, session))
                        .doOnError((session, error) -> logger.warn(
                                "discovery-v2 Broker {} connection error", brokerIndex, error)))
                .open();
    }

    int activeSessionCount() {
        return availableSessions().size();
    }

    private void openAllSessions() {
        ExecutorService connectorExecutor = newDaemonPool(
                Math.max(1, discoveryUrls.size()), "xuantong-discovery-broker-connect");
        List<Callable<OpenedSession>> tasks = new ArrayList<>();
        for (int index = 0; index < discoveryUrls.size(); index++) {
            final int brokerIndex = index;
            tasks.add(new Callable<OpenedSession>() {
                @Override
                public OpenedSession call() {
                    try {
                        return new OpenedSession(brokerIndex,
                                openSession(brokerIndex, discoveryUrls.get(brokerIndex)), null);
                    } catch (Exception e) {
                        return new OpenedSession(brokerIndex, null, e);
                    }
                }
            });
        }
        try {
            List<Future<OpenedSession>> futures = connectorExecutor.invokeAll(
                    tasks, CONNECTION_TIMEOUT + 2_000L, TimeUnit.MILLISECONDS);
            for (Future<OpenedSession> future : futures) {
                if (future.isCancelled()) {
                    continue;
                }
                OpenedSession opened = future.get();
                if (opened.session != null) {
                    sessions.putIfAbsent(opened.brokerIndex, opened.session);
                } else if (opened.error != null) {
                    logger.warn("Failed to create discovery-v2 Broker {} session",
                            opened.brokerIndex, opened.error);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Failed to initialize discovery-v2 Broker sessions", e);
        } finally {
            connectorExecutor.shutdownNow();
        }
    }

    private void handleOpened(final int brokerIndex, final ClientSession session) {
        if (closed) {
            closeQuietly(session);
            return;
        }
        sessions.put(brokerIndex, session);
        logger.info("Connected to discovery-v2 Broker {}: {}",
                brokerIndex, session.sessionId());
        if (operationExecutor != null) {
            operationExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    recoverBroker(brokerIndex, session);
                }
            });
        }
    }

    private void recoverBroker(int brokerIndex, ClientSession session) {
        if (closed || !isAvailable(session)) {
            return;
        }
        try {
            ServiceInstance currentRegistration = registration;
            if (currentRegistration != null) {
                registerOnSession(session, currentRegistration);
            }
            ServiceSnapshot snapshot = fetchBrokerSnapshot(session);
            notifyUpdate(serviceState.replaceBroker(
                    brokerId(brokerIndex), snapshot.getRevision(), snapshot.getInstances()));
        } catch (Exception e) {
            logger.warn("Failed to recover discovery Broker {} state", brokerIndex, e);
        }
    }

    private void handleBrokerEvent(int brokerIndex, String eventJson) {
        try {
            ControlPlaneEvent event = serializer.deserialize(eventJson, ControlPlaneEvent.class);
            if (event == null || event.getPayload() == null) {
                return;
            }
            ServiceInstance instance = serializer.toBean(event.getPayload(), ServiceInstance.class);
            MultiBrokerServiceState.Update update = serviceState.applyEvent(
                    brokerId(brokerIndex),
                    event.getEventType(),
                    event.getRevision() == null ? 0L : event.getRevision(),
                    instance);
            notifyUpdate(update);
        } catch (Exception e) {
            logger.warn("Failed to process discovery event from Broker {}", brokerIndex, e);
        }
    }

    private void notifyUpdate(MultiBrokerServiceState.Update update) {
        ServiceChangeListener listener = changeListener;
        if (update != null && listener != null) {
            listener.onChanged(update.eventType(), update.instance(), update.snapshot());
        }
    }

    private void handleClosed(int brokerIndex, ClientSession session) {
        logger.info("Disconnected from discovery-v2 Broker {}: {}; Socket.D will reconnect",
                brokerIndex, session.sessionId());
        MultiBrokerServiceState.Update update = serviceState.removeBroker(brokerId(brokerIndex));
        if (!closed) {
            notifyUpdate(update);
        }
    }

    private <T> List<BrokerResult<T>> executeAcross(
            List<Map.Entry<Integer, ClientSession>> available,
            final BrokerOperation<T> operation,
            long timeoutMs) {
        if (available.isEmpty() || operationExecutor == null || closed) {
            return Collections.emptyList();
        }
        CompletionService<BrokerResult<T>> completion =
                new ExecutorCompletionService<BrokerResult<T>>(operationExecutor);
        List<Future<BrokerResult<T>>> futures =
                new ArrayList<Future<BrokerResult<T>>>();
        for (final Map.Entry<Integer, ClientSession> entry : available) {
            futures.add(completion.submit(new Callable<BrokerResult<T>>() {
                @Override
                public BrokerResult<T> call() {
                    try {
                        return BrokerResult.success(entry.getKey(),
                                operation.execute(entry.getKey(), entry.getValue()));
                    } catch (Exception e) {
                        return BrokerResult.failure(entry.getKey(), e);
                    }
                }
            }));
        }

        List<BrokerResult<T>> results = new ArrayList<BrokerResult<T>>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            for (int completed = 0; completed < futures.size(); completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                Future<BrokerResult<T>> future = completion.poll(
                        remaining, TimeUnit.NANOSECONDS);
                if (future == null) {
                    break;
                }
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Parallel discovery Broker operation failed", e);
        } finally {
            cancelAll(futures);
        }
        return results;
    }

    private List<Map.Entry<Integer, ClientSession>> availableSessions() {
        List<Map.Entry<Integer, ClientSession>> result =
                new ArrayList<Map.Entry<Integer, ClientSession>>();
        for (Map.Entry<Integer, ClientSession> entry : sessions.entrySet()) {
            if (isAvailable(entry.getValue())) {
                result.add(entry);
            }
        }
        return result;
    }

    private boolean isAvailable(ClientSession session) {
        return session != null && session.isActive() && !session.isClosing();
    }

    private List<String> buildDiscoveryUrls(
            List<String> addresses,
            String namespace,
            String group,
            String serviceName) {
        String subscriber = "discovery:" + namespace + ":" + group + ":" + serviceName;
        String params = "@=" + encode(subscriber)
                + "&namespace=" + encode(namespace)
                + "&group=" + encode(group)
                + "&serviceName=" + encode(serviceName)
                + "&clientInstanceId=" + encode(identity.getClientInstanceId())
                + "&applicationName=" + encode(identity.getApplicationName())
                + "&clientVersion=" + encode(ClientIdentity.CLIENT_VERSION);
        Set<String> unique = new LinkedHashSet<>();
        for (String rawAddress : addresses) {
            if (rawAddress == null || rawAddress.trim().isEmpty()) {
                continue;
            }
            String address = rawAddress.trim();
            address = address.startsWith("sd:") ? address : "sd:ws://" + address;
            if (!address.contains("/discovery-v2")) {
                address += "/discovery-v2";
            }
            unique.add(address + (address.contains("?") ? "&" : "?") + params);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("serverAddresses must not be blank");
        }
        return new ArrayList<String>(unique);
    }

    private void ensureSuccess(Entity response, String operation) {
        if (!"true".equals(response.meta("success"))) {
            throw new XuantongException(
                    "Failed to " + operation + ": " + response.meta("error"));
        }
    }

    private boolean isNewerLease(ServiceInstance candidate, ServiceInstance current) {
        return value(candidate.getLeaseStartedAt()) > value(current.getLeaseStartedAt());
    }

    private boolean isNewerHeartbeat(ServiceInstance candidate, ServiceInstance current) {
        return value(candidate.getLastHeartbeatAt()) > value(current.getLastHeartbeatAt());
    }

    private ServiceInstance mergeRegistration(
            ServiceInstance registration, ServiceInstance response) {
        ServiceInstance merged = copy(registration);
        merged.setNamespaceId(response.getNamespaceId());
        merged.setGroupName(response.getGroupName());
        merged.setServiceName(response.getServiceName());
        merged.setLeaseStartedAt(response.getLeaseStartedAt());
        merged.setHealthy(response.getHealthy());
        merged.setEnabled(response.getEnabled());
        merged.setLastHeartbeatAt(response.getLastHeartbeatAt());
        return merged;
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

    private String brokerId(int index) {
        return "broker-" + index;
    }

    private long parseRevision(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long value(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }

    private ExecutorService newDaemonPool(int size, final String threadName) {
        return Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private void precloseQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            if (session.isActive() && !session.isClosing()) {
                session.preclose();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        registration = null;
        for (ClientSession session : sessions.values()) {
            precloseQuietly(session);
        }
        for (ClientSession session : sessions.values()) {
            closeQuietly(session);
        }
        sessions.clear();
        if (operationExecutor != null) {
            operationExecutor.shutdownNow();
        }
    }

    private interface BrokerOperation<T> {
        T execute(int brokerIndex, ClientSession session) throws Exception;
    }

    private static class BrokerResult<T> {
        private final int brokerIndex;
        private final T value;
        private final Exception error;

        private BrokerResult(int brokerIndex, T value, Exception error) {
            this.brokerIndex = brokerIndex;
            this.value = value;
            this.error = error;
        }

        private static <T> BrokerResult<T> success(int brokerIndex, T value) {
            return new BrokerResult<T>(brokerIndex, value, null);
        }

        private static <T> BrokerResult<T> failure(int brokerIndex, Exception error) {
            return new BrokerResult<T>(brokerIndex, null, error);
        }
    }

    private static class OpenedSession {
        private final int brokerIndex;
        private final ClientSession session;
        private final Exception error;

        private OpenedSession(int brokerIndex, ClientSession session, Exception error) {
            this.brokerIndex = brokerIndex;
            this.session = session;
            this.error = error;
        }
    }
}
