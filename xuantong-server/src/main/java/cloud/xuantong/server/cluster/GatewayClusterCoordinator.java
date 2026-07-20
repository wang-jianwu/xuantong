package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayEndpoint;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayProperties;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.ClusterQuotaAllocation;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.bean.LifecycleBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;

@Slf4j
@Component(index = 1_900)
public final class GatewayClusterCoordinator
        implements GatewayClusterViewProvider, LifecycleBean {
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000L;
    @Inject
    private GatewayClusterProperties clusterProperties;
    @Inject
    private ControlPlaneGatewayProperties gatewayProperties;
    @Inject
    private ControlPlaneGatewayRuntime gatewayRuntime;
    @Inject
    private GatewayClusterStore store;
    @Inject
    private ControlPlaneGatewayEndpoint gatewayEndpoint;

    private final String runtimeId = UUID.randomUUID().toString();
    private final AtomicBoolean stopping = new AtomicBoolean(true);
    private final AtomicBoolean tickInProgress = new AtomicBoolean();
    private volatile List<GatewayClusterStore.StoredGatewaySnapshot> cachedSnapshots = List.of();
    private volatile long ownLeaseExpiresAtEpochMs;
    private volatile long revocationCursor;
    private volatile long joinNotBeforeEpochMs;
    private volatile long nextSnapshotAtEpochMs;
    private volatile long nextCleanupAtEpochMs;
    private volatile boolean coordinationReady;
    private ScheduledExecutorService executor;
    private ToIntFunction<String> credentialRevoker;

    public GatewayClusterCoordinator() {
    }

    GatewayClusterCoordinator(
            GatewayClusterProperties clusterProperties,
            ControlPlaneGatewayProperties gatewayProperties,
            ControlPlaneGatewayRuntime gatewayRuntime,
            GatewayClusterStore store,
            ToIntFunction<String> credentialRevoker) {
        this.clusterProperties = clusterProperties;
        this.gatewayProperties = gatewayProperties;
        this.gatewayRuntime = gatewayRuntime;
        this.store = store;
        this.credentialRevoker = credentialRevoker;
    }

    @Override
    public synchronized void start() {
        stopping.set(false);
        if (!clusterProperties.isCoordinationEnabled()) {
            gatewayRuntime.updateClusterQuotaAllocation(ClusterQuotaAllocation.disabled());
            coordinationReady = false;
            log.warn("Gateway cluster coordination is disabled; connection and quota views "
                    + "are limited to the current Gateway");
            return;
        }
        long now = System.currentTimeMillis();
        List<GatewayClusterStore.StoredGatewaySnapshot> existing = store.findRecent(
                gatewayProperties.getClusterId(), now);
        boolean joiningExistingCluster = existing.stream()
                .anyMatch(snapshot -> !gatewayProperties.getGatewayId()
                        .equals(snapshot.gatewayId()));
        joinNotBeforeEpochMs = joiningExistingCluster
                ? now + clusterProperties.leaseTtlMs() : now;
        revocationCursor = store.maxRevocationEventId();
        credentialRevoker = credentialRevoker == null
                ? gatewayEndpoint::revokeCredential : credentialRevoker;
        refreshClusterState(now);
        nextSnapshotAtEpochMs = now + clusterProperties.snapshotIntervalMs();
        nextCleanupAtEpochMs = now + clusterProperties.cleanupIntervalMs();

        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "xuantong-gateway-cluster-coordinator");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickSafely,
                clusterProperties.revocationPollIntervalMs(),
                clusterProperties.revocationPollIntervalMs(),
                TimeUnit.MILLISECONDS);
        log.info("Gateway cluster coordination started: clusterId={}, gatewayId={}, "
                        + "runtimeId={}, activeGateways={}, joinNotBefore={}, leaseTtlMs={}, "
                        + "snapshotIntervalMs={}, revocationCursor={}",
                gatewayProperties.getClusterId(), gatewayProperties.getGatewayId(),
                runtimeId, gatewayRuntime.clusterQuotaAllocation().activeGatewayCount(),
                joinNotBeforeEpochMs, clusterProperties.leaseTtlMs(),
                clusterProperties.snapshotIntervalMs(), revocationCursor);
    }

    @Override
    public void preStop() {
        stop();
    }

    @Override
    public synchronized void stop() {
        if (stopping.getAndSet(true) && executor == null) {
            return;
        }
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
            awaitTermination(current);
        }
        if (clusterProperties != null && clusterProperties.isCoordinationEnabled()) {
            ClusterQuotaAllocation allocation = gatewayRuntime.clusterQuotaAllocation();
            gatewayRuntime.updateClusterQuotaAllocation(new ClusterQuotaAllocation(
                    true, false, allocation.clusterId(), allocation.activeGatewayCount(),
                    allocation.safetyReservePercent(), System.currentTimeMillis(), 0L,
                    allocation.maxSessions(), allocation.maxSessionsPerTenant(),
                    allocation.maxSessionsPerCredential(), allocation.maxSubscriptions(),
                    allocation.maxSubscriptionsPerTenant(),
                    allocation.tenantRequestRatePerSecond(),
                    allocation.tenantRequestBurst()));
        } else {
            gatewayRuntime.updateClusterQuotaAllocation(ClusterQuotaAllocation.disabled());
        }
        coordinationReady = false;
    }

    @Override
    public GatewayClusterView currentView() {
        long now = System.currentTimeMillis();
        GatewayRuntimeSnapshot local = gatewayRuntime.localSnapshot(
                clusterProperties.maxConnectionDetails());
        ClusterQuotaAllocation allocation = gatewayRuntime.clusterQuotaAllocation();
        return GatewayClusterView.aggregate(
                gatewayProperties.getClusterId(), gatewayProperties.getGatewayId(), now,
                coordinationReady
                        && allocation.admissionsEnabled()
                        && allocation.leaseValid(now),
                cachedSnapshots, local, ownLeaseExpiresAtEpochMs);
    }

    void tick() {
        if (stopping.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= nextSnapshotAtEpochMs) {
            refreshClusterState(now);
            nextSnapshotAtEpochMs = now + clusterProperties.snapshotIntervalMs();
        }
        if (stopping.get()) {
            return;
        }
        pollRevocations();
        if (stopping.get()) {
            return;
        }
        if (now >= nextCleanupAtEpochMs) {
            store.cleanup(
                    now - clusterProperties.staleRetentionMs(),
                    now - clusterProperties.revocationRetentionMs());
            nextCleanupAtEpochMs = now + clusterProperties.cleanupIntervalMs();
        }
    }

    private void tickSafely() {
        if (stopping.get()) {
            return;
        }
        if (!tickInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!stopping.get()) {
                tick();
            }
        } catch (Exception e) {
            if (stopping.get()) {
                log.debug("Gateway cluster coordination tick stopped during shutdown", e);
            } else {
                log.warn("Gateway cluster coordination tick failed; the local lease will fail "
                        + "closed if it cannot be renewed before expiry", e);
            }
        } finally {
            tickInProgress.set(false);
        }
    }

    private void awaitTermination(ScheduledExecutorService current) {
        try {
            if (!current.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("Gateway cluster coordination executor did not stop within {}ms; "
                        + "database shutdown will continue", SHUTDOWN_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for Gateway cluster coordination to stop", e);
        }
    }

    private void refreshClusterState(long now) {
        long leaseExpiresAt = now + clusterProperties.leaseTtlMs();
        GatewayRuntimeSnapshot snapshot = gatewayRuntime.localSnapshot(
                clusterProperties.maxConnectionDetails());
        store.publish(
                gatewayProperties.getClusterId(),
                gatewayProperties.getGatewayId(),
                runtimeId,
                now,
                leaseExpiresAt,
                snapshot);
        List<GatewayClusterStore.StoredGatewaySnapshot> snapshots = store.findRecent(
                gatewayProperties.getClusterId(),
                now - clusterProperties.staleRetentionMs());
        int activeGatewayCount = (int) snapshots.stream()
                .filter(value -> value.leaseExpiresAtEpochMs() > now)
                .count();
        activeGatewayCount = Math.max(1, activeGatewayCount);
        boolean allocationPossible = allocationPossible(activeGatewayCount);
        boolean admissionsEnabled = allocationPossible && now >= joinNotBeforeEpochMs;
        ClusterQuotaAllocation allocation = new ClusterQuotaAllocation(
                true,
                admissionsEnabled,
                gatewayProperties.getClusterId(),
                activeGatewayCount,
                clusterProperties.quotaSafetyReservePercent(),
                now,
                leaseExpiresAt,
                allocate(gatewayProperties.getMaxSessions(), activeGatewayCount),
                allocate(gatewayProperties.getMaxSessionsPerTenant(), activeGatewayCount),
                allocate(gatewayProperties.getMaxSessionsPerCredential(), activeGatewayCount),
                allocate(gatewayProperties.getMaxSubscriptions(), activeGatewayCount),
                allocate(gatewayProperties.getMaxSubscriptionsPerTenant(), activeGatewayCount),
                allocate(gatewayProperties.getTenantRequestRatePerSecond(), activeGatewayCount),
                allocate(gatewayProperties.getTenantRequestBurst(), activeGatewayCount));
        gatewayRuntime.updateClusterQuotaAllocation(allocation);
        cachedSnapshots = snapshots;
        ownLeaseExpiresAtEpochMs = leaseExpiresAt;
        coordinationReady = true;
        if (!allocationPossible) {
            log.error("Gateway cluster quota cannot allocate at least one unit per active "
                            + "Gateway; admissions are disabled: activeGateways={}",
                    activeGatewayCount);
        }
    }

    private void pollRevocations() {
        int limit = clusterProperties.revocationBatchSize();
        for (int batch = 0; batch < 10; batch++) {
            List<GatewayClusterStore.CredentialRevocation> events =
                    store.findRevocationsAfter(revocationCursor, limit);
            if (events.isEmpty()) {
                return;
            }
            for (GatewayClusterStore.CredentialRevocation event : events) {
                int closed = credentialRevoker.applyAsInt(event.tokenHash());
                if (closed > 0) {
                    log.info("Closed {} control-plane Session(s) after cluster credential "
                                    + "revocation event {}", closed, event.eventId());
                }
                revocationCursor = event.eventId();
            }
            if (events.size() < limit) {
                return;
            }
        }
    }

    private boolean allocationPossible(int activeGatewayCount) {
        return usable(gatewayProperties.getMaxSessions()) >= activeGatewayCount
                && usable(gatewayProperties.getMaxSessionsPerTenant()) >= activeGatewayCount
                && usable(gatewayProperties.getMaxSessionsPerCredential()) >= activeGatewayCount
                && usable(gatewayProperties.getMaxSubscriptions()) >= activeGatewayCount
                && usable(gatewayProperties.getMaxSubscriptionsPerTenant()) >= activeGatewayCount
                && usable(gatewayProperties.getTenantRequestRatePerSecond())
                >= activeGatewayCount
                && usable(gatewayProperties.getTenantRequestBurst()) >= activeGatewayCount;
    }

    private int allocate(int clusterHardLimit, int activeGatewayCount) {
        return Math.max(1, usable(clusterHardLimit) / activeGatewayCount);
    }

    private int usable(int clusterHardLimit) {
        return Math.max(0, clusterHardLimit
                * (100 - clusterProperties.quotaSafetyReservePercent()) / 100);
    }
}
