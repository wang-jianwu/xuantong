package cloud.xuantong.gateway.socketd;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneGatewayQuotaTest {
    @Test
    void explicitRuntimeConstructorAppliesTenantRequestQuota() {
        ControlPlaneGatewayProperties properties = new ControlPlaneGatewayProperties(
                "127.0.0.1",
                8090,
                "cluster-quota-test",
                "gateway-quota-test",
                1L,
                4_000L,
                64,
                4,
                128,
                12_345,
                67_890,
                1_000L,
                ControlPlaneGatewayProperties.ClientAuth.NONE);

        assertEquals(12_345, properties.getTenantRequestRatePerSecond());
        assertEquals(67_890, properties.getTenantRequestBurst());
    }

    @Test
    void sessionAndCredentialQuotasAreAtomicAndReleasedOnClose() throws Exception {
        ControlPlaneGatewayProperties properties = properties();
        set(properties, "maxSessions", 5);
        set(properties, "maxSessionsPerTenant", 1);
        set(properties, "maxSessionsPerCredential", 1);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);

        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission.ACCEPTED,
                runtime.sessionOpened("s-1", 1L, "127.0.0.1"));
        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission.ACCEPTED,
                runtime.sessionOpened("s-2", 2L, "127.0.0.2"));
        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission.ACCEPTED,
                runtime.sessionOpened("s-3", 3L, "127.0.0.3"));

        assertEquals(ControlPlaneGatewayRuntime.AuthenticationAdmission.ACCEPTED,
                runtime.sessionAuthenticated("s-1", principal(
                        "principal-1", "tenant-a", "fingerprint-1")));
        assertEquals(ControlPlaneGatewayRuntime.AuthenticationAdmission.ACCEPTED,
                runtime.sessionAuthenticated("s-1", principal(
                        "principal-1", "tenant-a", "fingerprint-1")),
                "periodic revalidation must not double-count a Session");
        assertEquals(1, runtime.activeSessionsForTenant("tenant-a"));
        assertEquals(1, runtime.activeSessionsForCredential("fingerprint-1"));

        assertEquals(ControlPlaneGatewayRuntime.AuthenticationAdmission.TENANT_LIMIT,
                runtime.sessionAuthenticated("s-2", principal(
                        "principal-2", "tenant-a", "fingerprint-2")));
        runtime.sessionClosed("s-1");
        assertEquals(0, runtime.activeSessionsForTenant("tenant-a"));

        assertEquals(ControlPlaneGatewayRuntime.AuthenticationAdmission.ACCEPTED,
                runtime.sessionAuthenticated("s-2", principal(
                        "principal-2", "tenant-a", "fingerprint-2")));
        assertEquals(ControlPlaneGatewayRuntime.AuthenticationAdmission.CREDENTIAL_LIMIT,
                runtime.sessionAuthenticated("s-3", principal(
                        "principal-3", "tenant-b", "fingerprint-2")));
        runtime.sessionClosed("s-2");
        assertEquals(ControlPlaneGatewayRuntime.AuthenticationAdmission.ACCEPTED,
                runtime.sessionAuthenticated("s-3", principal(
                        "principal-3", "tenant-b", "fingerprint-2")));

        assertEquals(1L, runtime.tenantSessionLimitRejectedTotal());
        assertEquals(1L, runtime.credentialSessionLimitRejectedTotal());
    }

    @Test
    void gatewayAndTenantSubscriptionQuotasAreReleased() throws Exception {
        ControlPlaneGatewayProperties properties = properties();
        set(properties, "maxSessions", 1);
        set(properties, "maxSubscriptions", 2);
        set(properties, "maxSubscriptionsPerTenant", 1);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);

        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission.ACCEPTED,
                runtime.sessionOpened("s-1", 1L, "127.0.0.1"));
        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission.GATEWAY_LIMIT,
                runtime.sessionOpened("s-2", 2L, "127.0.0.2"));
        assertEquals(1L, runtime.gatewaySessionLimitRejectedTotal());

        assertEquals(ControlPlaneGatewayRuntime.SubscriptionAdmission.ACCEPTED,
                runtime.tryAcquireSubscription("tenant-a"));
        assertEquals(ControlPlaneGatewayRuntime.SubscriptionAdmission.TENANT_LIMIT,
                runtime.tryAcquireSubscription("tenant-a"));
        assertEquals(ControlPlaneGatewayRuntime.SubscriptionAdmission.ACCEPTED,
                runtime.tryAcquireSubscription("tenant-b"));
        assertEquals(ControlPlaneGatewayRuntime.SubscriptionAdmission.GATEWAY_LIMIT,
                runtime.tryAcquireSubscription("tenant-c"));
        assertEquals(1, runtime.activeSubscriptionsForTenant("tenant-a"));

        runtime.releaseSubscription("tenant-a");
        assertEquals(ControlPlaneGatewayRuntime.SubscriptionAdmission.ACCEPTED,
                runtime.tryAcquireSubscription("tenant-a"));
        assertEquals(1L, runtime.tenantSubscriptionLimitRejectedTotal());
    }

    @Test
    void tenantTokenBucketAndAuthenticationFailureWindowAreBounded() throws Exception {
        ControlPlaneGatewayProperties properties = properties();
        set(properties, "tenantRequestRatePerSecond", 10);
        set(properties, "tenantRequestBurst", 1);
        set(properties, "authFailureLimit", 2);
        set(properties, "authFailureWindowMs", 1_000L);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);

        assertTrue(runtime.tryAcquireTenantRequest("tenant-a").allowed());
        ControlPlaneGatewayRuntime.RateLimitDecision limited =
                runtime.tryAcquireTenantRequest("tenant-a");
        assertFalse(limited.allowed());
        assertTrue(limited.retryAfterMs() > 0L);
        Thread.sleep(120L);
        assertTrue(runtime.tryAcquireTenantRequest("tenant-a").allowed());
        assertEquals(1L, runtime.tenantRequestRateLimitedTotal());

        assertTrue(runtime.authenticationAttempt("127.0.0.1").allowed());
        runtime.authenticationFailed("127.0.0.1");
        assertTrue(runtime.authenticationAttempt("127.0.0.1").allowed());
        runtime.authenticationFailed("127.0.0.1");
        ControlPlaneGatewayRuntime.RateLimitDecision authLimited =
                runtime.authenticationAttempt("127.0.0.1");
        assertFalse(authLimited.allowed());
        assertTrue(authLimited.retryAfterMs() > 0L);
        runtime.authenticationSucceeded("127.0.0.1");
        assertTrue(runtime.authenticationAttempt("127.0.0.1").allowed());
        assertEquals(1L, runtime.authenticationRateLimitedTotal());
    }

    @Test
    void executorCapacityTelemetryReportsActiveQueuedAndScheduledWork()
            throws Exception {
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties());
        ThreadPoolExecutor work = executor();
        ThreadPoolExecutor callbacks = executor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workStarted = new CountDownLatch(1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        try {
            runtime.setExecutorTelemetry(work, callbacks, scheduler);
            work.execute(() -> await(workStarted, release));
            work.execute(() -> { });
            callbacks.execute(() -> await(callbackStarted, release));
            callbacks.execute(() -> { });
            scheduler.schedule(() -> { }, 1L, TimeUnit.HOURS);

            assertTrue(workStarted.await(1, TimeUnit.SECONDS));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, runtime.workActiveThreads());
            assertEquals(1, runtime.workQueueDepth());
            assertEquals(1, runtime.stateCallbackActiveThreads());
            assertEquals(1, runtime.stateCallbackQueueDepth());
            assertEquals(1, runtime.callbackScheduledTaskCount());
        } finally {
            release.countDown();
            work.shutdownNow();
            callbacks.shutdownNow();
            scheduler.shutdownNow();
            runtime.setExecutorTelemetry(null, null, null);
        }
    }

    @Test
    void clusterAllocationIsEnforcedInMemoryAndFailsClosedAfterLeaseExpiry()
            throws Exception {
        ControlPlaneGatewayProperties properties = properties();
        set(properties, "maxSessions", 10);
        set(properties, "maxSessionsPerTenant", 10);
        set(properties, "maxSessionsPerCredential", 10);
        set(properties, "maxSubscriptions", 10);
        set(properties, "maxSubscriptionsPerTenant", 10);
        ControlPlaneGatewayRuntime runtime = new ControlPlaneGatewayRuntime(properties);
        long now = System.currentTimeMillis();
        runtime.updateClusterQuotaAllocation(
                new ControlPlaneGatewayRuntime.ClusterQuotaAllocation(
                        true, true, "cluster-quota-test", 2, 5,
                        now, now + 10_000L,
                        1, 1, 1, 1, 1, 1, 1));

        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission.ACCEPTED,
                runtime.sessionOpened("s-1", 1L, "127.0.0.1"));
        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission.GATEWAY_LIMIT,
                runtime.sessionOpened("s-2", 2L, "127.0.0.2"));
        assertEquals(ControlPlaneGatewayRuntime.AuthenticationAdmission.ACCEPTED,
                runtime.sessionAuthenticated("s-1", principal(
                        "principal-1", "tenant-a", "raw-fingerprint")));
        assertEquals(ControlPlaneGatewayRuntime.SubscriptionAdmission.ACCEPTED,
                runtime.tryAcquireSubscription("tenant-a"));
        assertTrue(runtime.tryAcquireTenantRequest("tenant-a").allowed());
        assertFalse(runtime.tryAcquireTenantRequest("tenant-a").allowed());
        assertTrue(runtime.localSnapshot(10).credentialSessionCounts().keySet().stream()
                .allMatch(value -> value.startsWith("credential-")
                        && !value.contains("raw-fingerprint")));

        runtime.updateClusterQuotaAllocation(
                new ControlPlaneGatewayRuntime.ClusterQuotaAllocation(
                        true, true, "cluster-quota-test", 2, 5,
                        now, now - 1L,
                        1, 1, 1, 1, 1, 1, 1));
        assertEquals(ControlPlaneGatewayRuntime.SessionAdmission
                        .CLUSTER_COORDINATION_UNAVAILABLE,
                runtime.sessionOpened("s-3", 3L, "127.0.0.3"));
        assertEquals(ControlPlaneGatewayRuntime.SubscriptionAdmission
                        .CLUSTER_COORDINATION_UNAVAILABLE,
                runtime.tryAcquireSubscription("tenant-b"));
        assertFalse(runtime.tryAcquireTenantRequest("tenant-b").allowed());
        assertEquals(3L, runtime.clusterCoordinationRejectedTotal());
    }

    private ControlPlaneGatewayProperties properties() {
        return new ControlPlaneGatewayProperties(
                "cluster-quota-test", "gateway-quota-test", 1L, 4_000L);
    }

    private ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2));
    }

    private void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ControlPlanePrincipal principal(
            String principalId, String tenant, String fingerprint) {
        return new ControlPlanePrincipal(
                principalId,
                tenant,
                "public",
                "DEFAULT_GROUP",
                fingerprint,
                0L,
                false);
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
