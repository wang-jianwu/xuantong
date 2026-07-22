package cloud.xuantong.server.cluster;

import cloud.xuantong.gateway.socketd.ControlPlaneGatewayProperties;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime;
import cloud.xuantong.gateway.socketd.ControlPlaneGatewayRuntime.GatewayRuntimeSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayClusterCoordinatorTest {
    @Test
    void standaloneModeDoesNotStartDatabaseCoordination() throws Exception {
        GatewayClusterProperties clusterProperties = new GatewayClusterProperties(
                false, 2_000L, 10_000L, 60_000L, 5_000, 5,
                100L, 500, 604_800_000L, 3_600_000L);
        ControlPlaneGatewayProperties gatewayProperties = gatewayProperties(
                "gateway-standalone");
        ControlPlaneGatewayRuntime runtime = runtime(gatewayProperties);
        GatewayClusterStore unexpectedStore = new GatewayClusterStore() {
            @Override
            public void publish(
                    String clusterId, String gatewayId, String runtimeId,
                    long nowEpochMs, long leaseExpiresAtEpochMs,
                    GatewayRuntimeSnapshot snapshot) {
                throw new AssertionError("standalone must not publish cluster snapshots");
            }

            @Override
            public List<StoredGatewaySnapshot> findRecent(
                    String clusterId, long minimumLeaseExpiryEpochMs) {
                throw new AssertionError("standalone must not query cluster snapshots");
            }

            @Override
            public long maxRevocationEventId() {
                throw new AssertionError("standalone must not query revocation cursor");
            }

            @Override
            public List<CredentialRevocation> findRevocationsAfter(
                    long eventId, int limit) {
                throw new AssertionError("standalone must not poll revocations");
            }

            @Override
            public void cleanup(
                    long snapshotExpiryCutoffEpochMs, long revocationCutoffEpochMs) {
                throw new AssertionError("standalone must not clean cluster state");
            }
        };
        GatewayClusterCoordinator coordinator = new GatewayClusterCoordinator(
                clusterProperties, gatewayProperties, runtime, unexpectedStore, ignored -> 0);

        coordinator.start();
        try {
            assertFalse(coordinator.currentSummary().clusterAggregated());
            assertEquals(1, coordinator.currentSummary().activeGatewayCount());
            assertTrue(runtime.clusterQuotaAllocation().admissionsEnabled());
        } finally {
            coordinator.stop();
        }
    }

    @Test
    void waitsForInFlightTickBeforeCompletingShutdown() throws Exception {
        BlockingStore store = new BlockingStore();
        GatewayClusterProperties clusterProperties = new GatewayClusterProperties(
                true, 2_000L, 10_000L, 60_000L, 5_000, 5,
                100L, 500, 604_800_000L, 3_600_000L);
        ControlPlaneGatewayProperties gatewayProperties = gatewayProperties("gateway-stop");
        ControlPlaneGatewayRuntime runtime = runtime(gatewayProperties);
        GatewayClusterCoordinator coordinator = new GatewayClusterCoordinator(
                clusterProperties, gatewayProperties, runtime, store, ignored -> 0);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try {
            coordinator.start();
            store.blockNextPoll();
            assertTrue(store.awaitBlockedPoll(), "scheduled coordination tick did not start");

            Future<?> stopped = stopper.submit(coordinator::stop);
            Thread.sleep(50L);
            assertFalse(stopped.isDone(),
                    "shutdown must wait for the in-flight database operation");

            store.releaseBlockedPoll();
            stopped.get(2, TimeUnit.SECONDS);
            int callsAfterStop = store.pollCalls();
            Thread.sleep(250L);
            assertEquals(callsAfterStop, store.pollCalls(),
                    "no coordination database work may start after shutdown");
        } finally {
            store.releaseBlockedPoll();
            coordinator.stop();
            stopper.shutdownNow();
        }
    }

    @Test
    void splitsLocalQuotasReallocatesAfterGatewayLeavesAndConsumesRevocations()
            throws Exception {
        InMemoryStore store = new InMemoryStore();
        GatewayClusterProperties clusterProperties = new GatewayClusterProperties(
                true, 2_000L, 10_000L, 60_000L, 5_000, 5,
                60_000L, 500, 604_800_000L, 3_600_000L);
        ControlPlaneGatewayProperties gatewayAProperties = gatewayProperties("gateway-a");
        ControlPlaneGatewayProperties gatewayBProperties = gatewayProperties("gateway-b");
        ControlPlaneGatewayRuntime runtimeA = runtime(gatewayAProperties);
        ControlPlaneGatewayRuntime runtimeB = runtime(gatewayBProperties);
        AtomicInteger closedOnA = new AtomicInteger();
        AtomicInteger closedOnB = new AtomicInteger();
        GatewayClusterCoordinator coordinatorA = new GatewayClusterCoordinator(
                clusterProperties, gatewayAProperties, runtimeA, store,
                ignored -> closedOnA.incrementAndGet());
        GatewayClusterCoordinator coordinatorB = new GatewayClusterCoordinator(
                clusterProperties, gatewayBProperties, runtimeB, store,
                ignored -> closedOnB.incrementAndGet());
        try {
            coordinatorA.start();
            coordinatorB.start();
            forceSnapshotRefresh(coordinatorA);
            coordinatorA.tick();

            assertEquals(2, runtimeA.clusterQuotaAllocation().activeGatewayCount());
            assertEquals(9_500, runtimeA.clusterQuotaAllocation().maxSessions());
            assertFalse(runtimeB.clusterQuotaAllocation().admissionsEnabled(),
                    "A joining Gateway waits one lease TTL before admitting clients");
            assertEquals(2, coordinatorA.currentView().activeGatewayCount());

            store.appendRevocation("token-hash");
            coordinatorA.tick();
            coordinatorB.tick();
            assertEquals(1, closedOnA.get());
            assertEquals(1, closedOnB.get());

            coordinatorB.stop();
            store.expire("gateway-b");
            forceSnapshotRefresh(coordinatorA);
            coordinatorA.tick();
            assertEquals(1, runtimeA.clusterQuotaAllocation().activeGatewayCount());
            assertEquals(19_000, runtimeA.clusterQuotaAllocation().maxSessions());
            assertTrue(runtimeA.clusterQuotaAllocation().admissionsEnabled());
        } finally {
            coordinatorA.stop();
            coordinatorB.stop();
        }
    }

    private ControlPlaneGatewayProperties gatewayProperties(String gatewayId)
            throws Exception {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties();
        set(properties, "clusterId", "cluster-test");
        set(properties, "configuredGatewayId", gatewayId);
        set(properties, "transportGeneration", 1L);
        set(properties, "maxSessions", 20_000);
        set(properties, "maxSessionsPerTenant", 10_000);
        set(properties, "maxSessionsPerCredential", 1_000);
        set(properties, "maxSubscriptions", 10_000);
        set(properties, "maxSubscriptionsPerTenant", 2_000);
        set(properties, "tenantRequestRatePerSecond", 2_000);
        set(properties, "tenantRequestBurst", 4_000);
        return properties;
    }

    private ControlPlaneGatewayRuntime runtime(ControlPlaneGatewayProperties properties)
            throws Exception {
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime();
        set(runtime, "properties", properties);
        return runtime;
    }

    private void forceSnapshotRefresh(GatewayClusterCoordinator coordinator)
            throws Exception {
        set(coordinator, "nextSnapshotAtEpochMs", 0L);
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class InMemoryStore implements GatewayClusterStore {
        private final Map<String, StoredGatewaySnapshot> snapshots = new LinkedHashMap<>();
        private final List<CredentialRevocation> revocations = new ArrayList<>();

        @Override
        public synchronized void publish(
                String clusterId,
                String gatewayId,
                String runtimeId,
                long nowEpochMs,
                long leaseExpiresAtEpochMs,
                GatewayRuntimeSnapshot snapshot) {
            StoredGatewaySnapshot existing = snapshots.get(gatewayId);
            if (existing != null
                    && !runtimeId.equals(existing.runtimeId())
                    && existing.leaseExpiresAtEpochMs() > nowEpochMs) {
                throw new IllegalStateException("duplicate Gateway identity");
            }
            snapshots.put(gatewayId, new StoredGatewaySnapshot(
                    clusterId, gatewayId, runtimeId, leaseExpiresAtEpochMs, snapshot));
        }

        @Override
        public synchronized List<StoredGatewaySnapshot> findRecent(
                String clusterId, long minimumLeaseExpiryEpochMs) {
            return snapshots.values().stream()
                    .filter(value -> clusterId.equals(value.clusterId()))
                    .filter(value -> value.leaseExpiresAtEpochMs()
                            >= minimumLeaseExpiryEpochMs)
                    .toList();
        }

        @Override
        public synchronized long maxRevocationEventId() {
            return revocations.size();
        }

        @Override
        public synchronized List<CredentialRevocation> findRevocationsAfter(
                long eventId, int limit) {
            return revocations.stream()
                    .filter(event -> event.eventId() > eventId)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void cleanup(long snapshotExpiryCutoffEpochMs, long revocationCutoffEpochMs) {
        }

        private synchronized void appendRevocation(String tokenHash) {
            long eventId = revocations.size() + 1L;
            revocations.add(new CredentialRevocation(
                    eventId, tokenHash, System.currentTimeMillis()));
        }

        private synchronized void expire(String gatewayId) {
            StoredGatewaySnapshot existing = snapshots.get(gatewayId);
            if (existing != null) {
                snapshots.put(gatewayId, new StoredGatewaySnapshot(
                        existing.clusterId(), existing.gatewayId(), existing.runtimeId(),
                        System.currentTimeMillis() - 1L, existing.snapshot()));
            }
        }
    }

    private static final class BlockingStore extends InMemoryStore {
        private final AtomicBoolean blockNextPoll = new AtomicBoolean();
        private final CountDownLatch pollEntered = new CountDownLatch(1);
        private final CountDownLatch pollRelease = new CountDownLatch(1);
        private final AtomicInteger pollCalls = new AtomicInteger();

        @Override
        public List<CredentialRevocation> findRevocationsAfter(long eventId, int limit) {
            pollCalls.incrementAndGet();
            if (blockNextPoll.compareAndSet(true, false)) {
                pollEntered.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        pollRelease.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.findRevocationsAfter(eventId, limit);
        }

        private void blockNextPoll() {
            blockNextPoll.set(true);
        }

        private boolean awaitBlockedPoll() throws InterruptedException {
            return pollEntered.await(2, TimeUnit.SECONDS);
        }

        private void releaseBlockedPoll() {
            pollRelease.countDown();
        }

        private int pollCalls() {
            return pollCalls.get();
        }
    }
}
