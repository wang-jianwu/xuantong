package cloud.xuantong.server.state.management;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public final class ServiceDefinitionLifecycleRecovery {
    private static final long MAX_IDLE_INTERVAL_MS = 30_000L;

    @Inject
    private ServiceDefinitionLifecycleCoordinator coordinator;
    @Inject("${statePlane.registry.lifecycleRecoveryIntervalMs:5000}")
    private long intervalMs;

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile long nextDelayMs;

    @Init(index = 1_800)
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        if (intervalMs < 1_000L) {
            throw new IllegalStateException(
                    "statePlane.registry.lifecycleRecoveryIntervalMs must be at least 1000");
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "service-lifecycle-recovery");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        nextDelayMs = intervalMs;
        scheduleNext(0L);
    }

    @Destroy
    public synchronized void stop() {
        running = false;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private void recoverSafely() {
        int pending = 0;
        try {
            pending = coordinator.recoverPending();
        } catch (RuntimeException e) {
            if (!running) {
                return;
            }
            log.debug("Service lifecycle recovery scan failed; it will retry", e);
        } finally {
            if (running) {
                nextDelayMs = pending > 0
                        ? intervalMs
                        : Math.min(MAX_IDLE_INTERVAL_MS,
                                Math.max(intervalMs, nextDelayMs * 2L));
                scheduleNext(nextDelayMs);
            }
        }
    }

    private void scheduleNext(long delayMs) {
        ScheduledExecutorService current = executor;
        if (running && current != null && !current.isShutdown()) {
            try {
                current.schedule(this::recoverSafely, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                if (running) {
                    throw e;
                }
            }
        }
    }
}
