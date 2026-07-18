package cloud.xuantong.gateway.socketd;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ControlPlaneGatewayRuntime {
    private static final ScheduledExecutorService FALLBACK_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "xuantong-gateway-fallback-scheduler");
                thread.setDaemon(true);
                return thread;
            });
    enum Admission {
        ACCEPTED,
        DRAINING,
        OVERLOADED
    }

    enum SessionAdmission {
        ACCEPTED,
        GATEWAY_LIMIT
    }

    enum AuthenticationAdmission {
        ACCEPTED,
        SESSION_CLOSED,
        IDENTITY_CHANGED,
        TENANT_LIMIT,
        CREDENTIAL_LIMIT
    }

    enum SubscriptionAdmission {
        ACCEPTED,
        DRAINING,
        GATEWAY_LIMIT,
        TENANT_LIMIT
    }

    private final Object drainMonitor = new Object();
    private final Object quotaMonitor = new Object();
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicInteger activeSubscriptions = new AtomicInteger();
    private final AtomicInteger pendingWatchAcknowledgements = new AtomicInteger();
    private final Map<String, TrackedConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, Integer> tenantSessionCounts = new HashMap<>();
    private final Map<String, Integer> credentialSessionCounts = new HashMap<>();
    private final Map<String, Integer> tenantSubscriptionCounts = new HashMap<>();
    private final Map<String, TenantTokenBucket> tenantRequestBuckets = new HashMap<>();
    private final Map<String, AuthenticationFailureWindow> authenticationFailures =
            new HashMap<>();
    private final AtomicLong gatewaySessionLimitRejectedTotal = new AtomicLong();
    private final AtomicLong tenantSessionLimitRejectedTotal = new AtomicLong();
    private final AtomicLong credentialSessionLimitRejectedTotal = new AtomicLong();
    private final AtomicLong tenantSubscriptionLimitRejectedTotal = new AtomicLong();
    private final AtomicLong tenantRequestRateLimitedTotal = new AtomicLong();
    private final AtomicLong authenticationRateLimitedTotal = new AtomicLong();
    private final AtomicLong helloTimeoutClosedTotal = new AtomicLong();
    private final AtomicLong watchAcknowledgedTotal = new AtomicLong();
    private final AtomicLong watchPollTotal = new AtomicLong();
    private final AtomicLong watchIdlePollTotal = new AtomicLong();
    private final AtomicLong watchReplyTotal = new AtomicLong();
    private final AtomicLong watchAckLatencyMsTotal = new AtomicLong();
    private final AtomicLong watchAckLatencyMsMax = new AtomicLong();
    private final AtomicLong watchAckTimeoutClosedTotal = new AtomicLong();
    private final AtomicLong watchStreamRotatedTotal = new AtomicLong();
    private final AtomicLong stateCallbackRejectedTotal = new AtomicLong();

    @Inject
    private ControlPlaneGatewayProperties properties;

    private volatile boolean draining;
    private volatile boolean closed;
    private volatile boolean tlsEnabled;
    private volatile ControlPlaneGatewayProperties.ClientAuth clientAuth =
            ControlPlaneGatewayProperties.ClientAuth.NONE;
    private volatile Executor stateCallbackExecutor = Runnable::run;
    private volatile ScheduledExecutorService callbackScheduler = FALLBACK_SCHEDULER;
    private volatile ThreadPoolExecutor workExecutorTelemetry;
    private volatile ThreadPoolExecutor stateCallbackExecutorTelemetry;
    private volatile ScheduledThreadPoolExecutor callbackSchedulerTelemetry;

    public ControlPlaneGatewayRuntime() {
    }

    ControlPlaneGatewayRuntime(ControlPlaneGatewayProperties properties) {
        this.properties = properties;
    }

    Admission tryAcquireRequest() {
        if (draining || closed) {
            return Admission.DRAINING;
        }

        int current = inFlightRequests.incrementAndGet();
        if (current > properties.getMaxInFlightRequests()) {
            releaseRequest();
            return Admission.OVERLOADED;
        }

        if (draining || closed) {
            releaseRequest();
            return Admission.DRAINING;
        }
        return Admission.ACCEPTED;
    }

    void releaseRequest() {
        int previous = inFlightRequests.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (previous <= 0) {
            throw new IllegalStateException("Gateway in-flight request count became negative");
        }
        int remaining = previous - 1;
        if (remaining == 0) {
            synchronized (drainMonitor) {
                drainMonitor.notifyAll();
            }
        }
    }

    SessionAdmission sessionOpened(
            String sessionId,
            long connectionGeneration,
            String remoteAddress) {
        long now = System.currentTimeMillis();
        synchronized (quotaMonitor) {
            if (connections.size() >= properties.getMaxSessions()) {
                gatewaySessionLimitRejectedTotal.incrementAndGet();
                return SessionAdmission.GATEWAY_LIMIT;
            }
            TrackedConnection previous = connections.putIfAbsent(
                    sessionId,
                    new TrackedConnection(
                            sessionId,
                            connectionGeneration,
                            normalized(remoteAddress),
                            now));
            return previous == null
                    ? SessionAdmission.ACCEPTED
                    : SessionAdmission.GATEWAY_LIMIT;
        }
    }

    void sessionIdentified(String sessionId, cloud.xuantong.protocol.v2.HelloRequest hello) {
        TrackedConnection connection = connections.get(sessionId);
        if (connection == null) {
            return;
        }
        connection.clientInstanceId = normalized(hello.getClientInstanceId());
        connection.applicationName = normalized(hello.getApplicationName());
        connection.clientVersion = normalized(hello.getClientVersion());
        connection.sdkName = normalized(hello.getSdkName());
        connection.transportPool = normalized(hello.getTransportPool());
        connection.capabilities = List.copyOf(hello.getCapabilitiesList());
        connection.lastActiveAt = System.currentTimeMillis();
    }

    AuthenticationAdmission sessionAuthenticated(
            String sessionId, ControlPlanePrincipal principal) {
        if (principal == null) {
            return AuthenticationAdmission.SESSION_CLOSED;
        }
        synchronized (quotaMonitor) {
            TrackedConnection connection = connections.get(sessionId);
            if (connection == null) {
                return AuthenticationAdmission.SESSION_CLOSED;
            }
            String tenant = principal.tenant();
            String credentialKey = credentialQuotaKey(principal);
            if (connection.principalId != null) {
                if (!tenant.equals(connection.tenant)
                        || !credentialKey.equals(connection.credentialQuotaKey)) {
                    return AuthenticationAdmission.IDENTITY_CHANGED;
                }
                updateAuthenticatedConnection(connection, principal, credentialKey);
                return AuthenticationAdmission.ACCEPTED;
            }
            if (count(tenantSessionCounts, tenant)
                    >= properties.getMaxSessionsPerTenant()) {
                tenantSessionLimitRejectedTotal.incrementAndGet();
                return AuthenticationAdmission.TENANT_LIMIT;
            }
            if (count(credentialSessionCounts, credentialKey)
                    >= properties.getMaxSessionsPerCredential()) {
                credentialSessionLimitRejectedTotal.incrementAndGet();
                return AuthenticationAdmission.CREDENTIAL_LIMIT;
            }
            increment(tenantSessionCounts, tenant);
            increment(credentialSessionCounts, credentialKey);
            updateAuthenticatedConnection(connection, principal, credentialKey);
            return AuthenticationAdmission.ACCEPTED;
        }
    }

    void sessionTouched(String sessionId) {
        TrackedConnection connection = connections.get(sessionId);
        if (connection != null) {
            connection.lastActiveAt = System.currentTimeMillis();
        }
    }

    void sessionClosed(String sessionId) {
        if (sessionId != null) {
            synchronized (quotaMonitor) {
                TrackedConnection connection = connections.remove(sessionId);
                if (connection != null && connection.principalId != null) {
                    decrement(tenantSessionCounts, connection.tenant);
                    decrement(credentialSessionCounts, connection.credentialQuotaKey);
                }
            }
        }
    }

    SubscriptionAdmission tryAcquireSubscription(String tenant) {
        if (draining || closed) {
            return SubscriptionAdmission.DRAINING;
        }
        String normalizedTenant = requiredKey(tenant);
        synchronized (quotaMonitor) {
            if (draining || closed) {
                return SubscriptionAdmission.DRAINING;
            }
            if (activeSubscriptions.get() >= properties.getMaxSubscriptions()) {
                return SubscriptionAdmission.GATEWAY_LIMIT;
            }
            if (count(tenantSubscriptionCounts, normalizedTenant)
                    >= properties.getMaxSubscriptionsPerTenant()) {
                tenantSubscriptionLimitRejectedTotal.incrementAndGet();
                return SubscriptionAdmission.TENANT_LIMIT;
            }
            activeSubscriptions.incrementAndGet();
            increment(tenantSubscriptionCounts, normalizedTenant);
            return SubscriptionAdmission.ACCEPTED;
        }
    }

    void releaseSubscription(String tenant) {
        synchronized (quotaMonitor) {
            activeSubscriptions.updateAndGet(current -> Math.max(0, current - 1));
            decrement(tenantSubscriptionCounts, requiredKey(tenant));
        }
    }

    RateLimitDecision tryAcquireTenantRequest(String tenant) {
        String normalizedTenant = requiredKey(tenant);
        long now = System.nanoTime();
        synchronized (quotaMonitor) {
            TenantTokenBucket bucket = tenantRequestBuckets.computeIfAbsent(
                    normalizedTenant,
                    ignored -> new TenantTokenBucket(
                            properties.getTenantRequestBurst(), now));
            RateLimitDecision decision = bucket.tryAcquire(
                    now,
                    properties.getTenantRequestRatePerSecond(),
                    properties.getTenantRequestBurst());
            if (!decision.allowed()) {
                tenantRequestRateLimitedTotal.incrementAndGet();
            }
            return decision;
        }
    }

    RateLimitDecision authenticationAttempt(String remoteAddress) {
        long now = System.currentTimeMillis();
        synchronized (quotaMonitor) {
            String key = authenticationFailureKey(remoteAddress);
            AuthenticationFailureWindow window = authenticationFailures.get(key);
            if (window == null) {
                return RateLimitDecision.permit();
            }
            if (window.blockedUntilEpochMs > now) {
                authenticationRateLimitedTotal.incrementAndGet();
                return RateLimitDecision.reject(window.blockedUntilEpochMs - now);
            }
            if (now - window.windowStartedAtEpochMs
                    >= properties.getAuthFailureWindowMs()) {
                authenticationFailures.remove(key);
            }
            return RateLimitDecision.permit();
        }
    }

    void authenticationFailed(String remoteAddress) {
        long now = System.currentTimeMillis();
        synchronized (quotaMonitor) {
            String key = authenticationFailureKey(remoteAddress);
            AuthenticationFailureWindow window = authenticationFailures.computeIfAbsent(
                    key, ignored -> new AuthenticationFailureWindow(now));
            if (now - window.windowStartedAtEpochMs
                    >= properties.getAuthFailureWindowMs()) {
                window.windowStartedAtEpochMs = now;
                window.failureCount = 0;
                window.blockedUntilEpochMs = 0L;
            }
            window.failureCount++;
            if (window.failureCount >= properties.getAuthFailureLimit()) {
                window.blockedUntilEpochMs = now + properties.getAuthFailureWindowMs();
            }
        }
    }

    void authenticationSucceeded(String remoteAddress) {
        synchronized (quotaMonitor) {
            authenticationFailures.remove(authenticationFailureKey(remoteAddress));
        }
    }

    void markActive(boolean tlsEnabled, ControlPlaneGatewayProperties.ClientAuth clientAuth) {
        this.tlsEnabled = tlsEnabled;
        this.clientAuth = clientAuth;
        this.closed = false;
        this.draining = false;
    }

    void beginDrain() {
        draining = true;
    }

    boolean awaitRequestsDrained(Duration timeout) throws InterruptedException {
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (drainMonitor) {
            while (inFlightRequests.get() > 0 && remainingNanos > 0) {
                long millis = Math.clamp(remainingNanos / 1_000_000L, 1L, 100L);
                drainMonitor.wait(millis);
                remainingNanos = deadline - System.nanoTime();
            }
        }
        return inFlightRequests.get() == 0;
    }

    void markClosed() {
        closed = true;
        draining = true;
        synchronized (quotaMonitor) {
            connections.clear();
            tenantSessionCounts.clear();
            credentialSessionCounts.clear();
            tenantSubscriptionCounts.clear();
            tenantRequestBuckets.clear();
            authenticationFailures.clear();
            activeSubscriptions.set(0);
            pendingWatchAcknowledgements.set(0);
        }
        synchronized (drainMonitor) {
            drainMonitor.notifyAll();
        }
    }

    public boolean isDraining() {
        return draining || closed;
    }

    public int inFlightRequests() {
        return inFlightRequests.get();
    }

    public int activeSessions() {
        return connections.size();
    }

    public int activeSubscriptions() {
        return activeSubscriptions.get();
    }

    public int pendingWatchAcknowledgements() {
        return pendingWatchAcknowledgements.get();
    }

    public long gatewaySessionLimitRejectedTotal() {
        return gatewaySessionLimitRejectedTotal.get();
    }

    public long tenantSessionLimitRejectedTotal() {
        return tenantSessionLimitRejectedTotal.get();
    }

    public long credentialSessionLimitRejectedTotal() {
        return credentialSessionLimitRejectedTotal.get();
    }

    public long tenantSubscriptionLimitRejectedTotal() {
        return tenantSubscriptionLimitRejectedTotal.get();
    }

    public long tenantRequestRateLimitedTotal() {
        return tenantRequestRateLimitedTotal.get();
    }

    public long authenticationRateLimitedTotal() {
        return authenticationRateLimitedTotal.get();
    }

    public long helloTimeoutClosedTotal() {
        return helloTimeoutClosedTotal.get();
    }

    public long watchAcknowledgedTotal() {
        return watchAcknowledgedTotal.get();
    }

    public long watchPollTotal() {
        return watchPollTotal.get();
    }

    public long watchIdlePollTotal() {
        return watchIdlePollTotal.get();
    }

    public long watchReplyTotal() {
        return watchReplyTotal.get();
    }

    public long watchAckLatencyMsTotal() {
        return watchAckLatencyMsTotal.get();
    }

    public long watchAckLatencyMsMax() {
        return watchAckLatencyMsMax.get();
    }

    public long watchAckTimeoutClosedTotal() {
        return watchAckTimeoutClosedTotal.get();
    }

    public long watchStreamRotatedTotal() {
        return watchStreamRotatedTotal.get();
    }

    public long stateCallbackRejectedTotal() {
        return stateCallbackRejectedTotal.get();
    }

    public int workActiveThreads() {
        ThreadPoolExecutor executor = workExecutorTelemetry;
        return executor == null ? 0 : executor.getActiveCount();
    }

    public int workQueueDepth() {
        ThreadPoolExecutor executor = workExecutorTelemetry;
        return executor == null ? 0 : executor.getQueue().size();
    }

    public int stateCallbackActiveThreads() {
        ThreadPoolExecutor executor = stateCallbackExecutorTelemetry;
        return executor == null ? 0 : executor.getActiveCount();
    }

    public int stateCallbackQueueDepth() {
        ThreadPoolExecutor executor = stateCallbackExecutorTelemetry;
        return executor == null ? 0 : executor.getQueue().size();
    }

    public int callbackScheduledTaskCount() {
        ScheduledThreadPoolExecutor scheduler = callbackSchedulerTelemetry;
        return scheduler == null ? 0 : scheduler.getQueue().size();
    }

    void sessionHelloTimedOut() {
        helloTimeoutClosedTotal.incrementAndGet();
    }

    void watchReplyAwaitingAcknowledgement() {
        pendingWatchAcknowledgements.incrementAndGet();
    }

    void watchPollStarted() {
        watchPollTotal.incrementAndGet();
    }

    void watchIdlePollCompleted() {
        watchIdlePollTotal.incrementAndGet();
    }

    void watchReplyEmitted() {
        watchReplyTotal.incrementAndGet();
    }

    void watchAcknowledged(long latencyMs) {
        pendingWatchAcknowledgements.updateAndGet(current -> Math.max(0, current - 1));
        watchAcknowledgedTotal.incrementAndGet();
        long boundedLatency = Math.max(0L, latencyMs);
        watchAckLatencyMsTotal.addAndGet(boundedLatency);
        watchAckLatencyMsMax.accumulateAndGet(boundedLatency, Math::max);
    }

    void watchAcknowledgementAbandoned() {
        pendingWatchAcknowledgements.updateAndGet(current -> Math.max(0, current - 1));
    }

    void watchAcknowledgementTimedOut() {
        watchAcknowledgementAbandoned();
        watchAckTimeoutClosedTotal.incrementAndGet();
    }

    void watchStreamRotated() {
        watchStreamRotatedTotal.incrementAndGet();
    }

    int activeSessionsForTenant(String tenant) {
        synchronized (quotaMonitor) {
            return count(tenantSessionCounts, requiredKey(tenant));
        }
    }

    int activeSessionsForCredential(String credentialKey) {
        synchronized (quotaMonitor) {
            return count(credentialSessionCounts, requiredKey(credentialKey));
        }
    }

    int activeSubscriptionsForTenant(String tenant) {
        synchronized (quotaMonitor) {
            return count(tenantSubscriptionCounts, requiredKey(tenant));
        }
    }

    public long logicalClients() {
        Set<String> clientInstanceIds = new HashSet<>();
        for (TrackedConnection connection : connections.values()) {
            if (connection.clientInstanceId != null) {
                clientInstanceIds.add(connection.clientInstanceId);
            }
        }
        return clientInstanceIds.size();
    }

    public List<ControlPlaneConnectionView> connections() {
        String gatewayId = properties == null ? "local" : properties.getGatewayId();
        List<ControlPlaneConnectionView> views = new ArrayList<>();
        for (TrackedConnection connection : connections.values()) {
            views.add(connection.view(gatewayId));
        }
        views.sort(Comparator.comparingLong(
                ControlPlaneConnectionView::connectedAt).reversed());
        return List.copyOf(views);
    }

    String transportSchema() {
        return tlsEnabled ? "sd:tcp+tls" : "sd:tcp";
    }

    ControlPlaneGatewayProperties.ClientAuth clientAuth() {
        return clientAuth;
    }

    void setStateCallbackExecutor(Executor executor) {
        stateCallbackExecutor = executor == null ? Runnable::run : executor;
    }

    void setCallbackScheduler(ScheduledExecutorService scheduler) {
        callbackScheduler = scheduler == null ? FALLBACK_SCHEDULER : scheduler;
    }

    void setExecutorTelemetry(
            ThreadPoolExecutor workExecutor,
            ThreadPoolExecutor callbackExecutor,
            ScheduledThreadPoolExecutor scheduler) {
        workExecutorTelemetry = workExecutor;
        stateCallbackExecutorTelemetry = callbackExecutor;
        callbackSchedulerTelemetry = scheduler;
    }

    boolean executeStateCallback(Runnable task) {
        try {
            stateCallbackExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            stateCallbackRejectedTotal.incrementAndGet();
            return false;
        }
    }

    Future<?> scheduleStateCallback(
            Runnable task,
            Runnable onRejected,
            long delayMs) {
        try {
            return callbackScheduler.schedule(() -> {
                if (!executeStateCallback(task) && onRejected != null) {
                    onRejected.run();
                }
            }, Math.max(1L, delayMs), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            stateCallbackRejectedTotal.incrementAndGet();
            if (onRejected != null) {
                onRejected.run();
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private void updateAuthenticatedConnection(
            TrackedConnection connection,
            ControlPlanePrincipal principal,
            String credentialKey) {
        connection.principalId = principal.principalId();
        connection.tenant = principal.tenant();
        connection.namespaceId = principal.namespaceId();
        connection.groupName = principal.groupName();
        connection.credentialQuotaKey = credentialKey;
        connection.lastActiveAt = System.currentTimeMillis();
    }

    private String credentialQuotaKey(ControlPlanePrincipal principal) {
        String fingerprint = normalized(principal.credentialFingerprint());
        return fingerprint == null ? principal.principalId() : fingerprint;
    }

    private String authenticationFailureKey(String remoteAddress) {
        String normalized = normalized(remoteAddress);
        String key = normalized == null ? "unknown" : normalized;
        if (authenticationFailures.containsKey(key)
                || authenticationFailures.size() < properties.getMaxSessions()) {
            return key;
        }
        return "overflow";
    }

    private static int count(Map<String, Integer> counts, String key) {
        return counts.getOrDefault(key, 0);
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(requiredKey(key), 1, Integer::sum);
    }

    private static void decrement(Map<String, Integer> counts, String key) {
        String normalized = requiredKey(key);
        Integer current = counts.get(normalized);
        if (current == null || current <= 1) {
            counts.remove(normalized);
        } else {
            counts.put(normalized, current - 1);
        }
    }

    private static String requiredKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("quota key must not be blank");
        }
        return value.trim();
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public record ControlPlaneConnectionView(
            String sessionId,
            String clientInstanceId,
            String applicationName,
            String principalId,
            String tenant,
            String namespaceId,
            String groupName,
            String clientVersion,
            String sdkName,
            String transportPool,
            List<String> capabilities,
            String remoteAddress,
            String gatewayId,
            long connectionGeneration,
            long connectedAt,
            long lastActiveAt) {
    }

    record RateLimitDecision(boolean allowed, long retryAfterMs) {
        private static RateLimitDecision permit() {
            return new RateLimitDecision(true, 0L);
        }

        private static RateLimitDecision reject(long retryAfterMs) {
            return new RateLimitDecision(false, Math.max(1L, retryAfterMs));
        }
    }

    private static final class TenantTokenBucket {
        private double tokens;
        private long lastRefillNanos;

        private TenantTokenBucket(int burst, long now) {
            this.tokens = burst;
            this.lastRefillNanos = now;
        }

        private RateLimitDecision tryAcquire(long now, int ratePerSecond, int burst) {
            long elapsed = Math.max(0L, now - lastRefillNanos);
            if (elapsed > 0L) {
                tokens = Math.min(
                        burst,
                        tokens + elapsed * (double) ratePerSecond / 1_000_000_000D);
                lastRefillNanos = now;
            }
            if (tokens >= 1D) {
                tokens -= 1D;
                return RateLimitDecision.permit();
            }
            double missing = 1D - tokens;
            long retryAfterMs = (long) Math.ceil(
                    missing * 1_000D / ratePerSecond);
            return RateLimitDecision.reject(retryAfterMs);
        }
    }

    private static final class AuthenticationFailureWindow {
        private long windowStartedAtEpochMs;
        private int failureCount;
        private long blockedUntilEpochMs;

        private AuthenticationFailureWindow(long windowStartedAtEpochMs) {
            this.windowStartedAtEpochMs = windowStartedAtEpochMs;
        }
    }

    private static final class TrackedConnection {
        private final String sessionId;
        private final long connectionGeneration;
        private final String remoteAddress;
        private final long connectedAt;
        private volatile String clientInstanceId;
        private volatile String applicationName;
        private volatile String principalId;
        private volatile String tenant;
        private volatile String namespaceId;
        private volatile String groupName;
        private volatile String credentialQuotaKey;
        private volatile String clientVersion;
        private volatile String sdkName;
        private volatile String transportPool;
        private volatile List<String> capabilities = List.of();
        private volatile long lastActiveAt;

        private TrackedConnection(
                String sessionId,
                long connectionGeneration,
                String remoteAddress,
                long connectedAt) {
            this.sessionId = sessionId;
            this.connectionGeneration = connectionGeneration;
            this.remoteAddress = remoteAddress;
            this.connectedAt = connectedAt;
            this.lastActiveAt = connectedAt;
        }

        private ControlPlaneConnectionView view(String gatewayId) {
            return new ControlPlaneConnectionView(
                    sessionId,
                    clientInstanceId,
                    applicationName,
                    principalId,
                    tenant,
                    namespaceId,
                    groupName,
                    clientVersion,
                    sdkName,
                    transportPool,
                    capabilities,
                    remoteAddress,
                    gatewayId,
                    connectionGeneration,
                    connectedAt,
                    lastActiveAt);
        }
    }
}
