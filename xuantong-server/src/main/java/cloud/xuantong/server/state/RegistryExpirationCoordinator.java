package cloud.xuantong.server.state;

import cloud.xuantong.raft.ratis.RatisStateNode;
import cloud.xuantong.raft.ratis.RatisStateRouter;
import cloud.xuantong.registry.state.ExpireLeaseBatch;
import cloud.xuantong.registry.state.RegistryActor;
import cloud.xuantong.registry.state.RegistryOverview;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateClient;
import cloud.xuantong.state.api.StateGroupId;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advances Registry logical time only while leases exist.
 *
 * <p>When the registry is empty, checks back off to 30 seconds and no Raft
 * mutation is written. A committed register, renew, deregister or takeover
 * wakes the coordinator immediately.</p>
 */
@Slf4j
final class RegistryExpirationCoordinator implements AutoCloseable {
    private static final long MAX_IDLE_DELAY_MS = 30_000L;

    private final LeaderProbe leaderProbe;
    private final StateClient stateClient;
    private final RegistryStatePlaneProperties properties;
    private final StateGroupId groupId;
    private final RegistryActor actor;
    private final long intervalMs;
    private final AtomicBoolean proposalInFlight = new AtomicBoolean();
    private final AtomicLong wakeVersion = new AtomicLong();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledTask;
    private volatile long idleDelayMs;

    RegistryExpirationCoordinator(
            RatisStateNode stateNode,
            RatisStateRouter stateRouter,
            RegistryStatePlaneProperties properties,
            StateGroupId groupId,
            RegistryActor actor) {
        this(() -> stateNode.isLeaderReady(groupId), stateRouter,
                properties, groupId, actor);
    }

    RegistryExpirationCoordinator(
            LeaderProbe leaderProbe,
            StateClient stateClient,
            RegistryStatePlaneProperties properties,
            StateGroupId groupId,
            RegistryActor actor) {
        this.leaderProbe = java.util.Objects.requireNonNull(leaderProbe, "leaderProbe");
        this.stateClient = java.util.Objects.requireNonNull(stateClient, "stateClient");
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.groupId = java.util.Objects.requireNonNull(groupId, "groupId");
        this.actor = java.util.Objects.requireNonNull(actor, "actor");
        this.intervalMs = properties.expirationInterval().toMillis();
        this.idleDelayMs = intervalMs;
    }

    synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-registry-expirer");
            thread.setDaemon(true);
            return thread;
        });
        schedule(intervalMs, false);
    }

    void wakeUp() {
        wakeVersion.incrementAndGet();
        idleDelayMs = intervalMs;
        schedule(0L, true);
    }

    private void runCheck() {
        long observedWakeVersion = wakeVersion.get();
        boolean leaderReady;
        try {
            leaderReady = leaderProbe.isLeaderReady();
        } catch (java.io.IOException e) {
            log.debug("Registry expiration leader check failed", e);
            schedule(intervalMs, false);
            return;
        }
        if (!leaderReady) {
            schedule(intervalMs, false);
            return;
        }
        if (!proposalInFlight.compareAndSet(false, true)) {
            return;
        }
        stateClient.query(RegistryStateCodec.overviewQuery(
                        groupId, ReadOptions.linearizable()))
                .whenComplete((result, queryFailure) -> {
                    if (queryFailure != null) {
                        proposalInFlight.set(false);
                        log.debug("Registry expiration overview failed", queryFailure);
                        schedule(intervalMs, false);
                        return;
                    }
                    try {
                        if (!RegistryStateCodec.RESULT_OVERVIEW.equals(result.resultType())) {
                            throw new IllegalStateException(
                                    "Registry expiration query returned " + result.resultType());
                        }
                        RegistryOverview overview = RegistryStateCodec.decodeOverview(
                                result.payload());
                        if (wakeVersion.get() != observedWakeVersion) {
                            proposalInFlight.set(false);
                            schedule(0L, false);
                            return;
                        }
                        if (overview.activeInstanceCount() == 0L) {
                            proposalInFlight.set(false);
                            idleDelayMs = Math.min(
                                    MAX_IDLE_DELAY_MS,
                                    Math.max(intervalMs, idleDelayMs * 2L));
                            schedule(idleDelayMs, false);
                            return;
                        }
                        submitExpiration(observedWakeVersion);
                    } catch (Exception e) {
                        proposalInFlight.set(false);
                        log.debug("Registry expiration overview could not be decoded", e);
                        schedule(intervalMs, false);
                    }
                });
    }

    private void submitExpiration(long observedWakeVersion) {
        stateClient.submit(RegistryStateCodec.mutationCommand(
                        groupId,
                        "expire:" + UUID.randomUUID(),
                        new ExpireLeaseBatch(
                                actor,
                                properties.expirationBatchSize(),
                                System.currentTimeMillis())))
                .whenComplete((ignored, failure) -> {
                    proposalInFlight.set(false);
                    if (failure != null) {
                        log.debug("Registry expiration proposal failed", failure);
                    }
                    schedule(wakeVersion.get() == observedWakeVersion
                            ? intervalMs : 0L, false);
                });
    }

    private synchronized void schedule(long delayMs, boolean replacePending) {
        ScheduledExecutorService current = executor;
        if (current == null || current.isShutdown()) {
            return;
        }
        if (replacePending && scheduledTask != null && !scheduledTask.isDone()) {
            scheduledTask.cancel(false);
        }
        try {
            scheduledTask = current.schedule(
                    this::runCheck,
                    Math.max(0L, delayMs),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!current.isShutdown()) {
                throw e;
            }
        }
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService current = executor;
        executor = null;
        ScheduledFuture<?> task = scheduledTask;
        scheduledTask = null;
        if (task != null) {
            task.cancel(false);
        }
        if (current != null) {
            current.shutdownNow();
        }
        proposalInFlight.set(false);
    }

    @FunctionalInterface
    interface LeaderProbe {
        boolean isLeaderReady() throws java.io.IOException;
    }
}
