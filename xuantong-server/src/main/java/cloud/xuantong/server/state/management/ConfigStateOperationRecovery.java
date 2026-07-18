package cloud.xuantong.server.state.management;

import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.config.management.model.ConfigStateOperation;
import cloud.xuantong.config.management.repository.ConfigStateOperationRepository;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Repairs ambiguous commits and lagging SQL projections on every Server node. */
@Slf4j
@Component
public final class ConfigStateOperationRecovery {
    private static final int BATCH_SIZE = 100;

    @Inject
    private ControlStatePlaneRuntime runtime;
    @Inject
    private ConfigStateOperationRepository operationRepository;
    @Inject
    private ConfigStateManagementService managementService;
    @Inject("${statePlane.config.operationRecoveryIntervalMs:1000}")
    private long recoveryIntervalMs;

    private ScheduledExecutorService executor;

    @Init(index = 1_700)
    public void start() {
        if (!runtime.isRunning()) {
            return;
        }
        if (recoveryIntervalMs < 250) {
            throw new IllegalArgumentException(
                    "statePlane.config.operationRecoveryIntervalMs must be at least 250");
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-config-state-recovery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::recoverSafely,
                recoveryIntervalMs,
                recoveryIntervalMs,
                TimeUnit.MILLISECONDS);
        log.info("Config State operation recovery started: intervalMs={}", recoveryIntervalMs);
    }

    void recoverOnce() {
        List<ConfigStateOperation> operations = operationRepository
                .findRecoverable(BATCH_SIZE);
        for (ConfigStateOperation operation : operations) {
            try {
                managementService.recover(operation);
            } catch (ConfigStateWriteException e) {
                if (!e.commitUnknown()) {
                    log.warn("Config State operation recovery reached a definitive failure: operationId={}, error={}",
                            operation.getOperationId(), e.getMessage());
                }
            } catch (RuntimeException e) {
                log.warn("Config State operation recovery will retry: operationId={}",
                        operation.getOperationId(), e);
            }
        }
    }

    private void recoverSafely() {
        try {
            recoverOnce();
        } catch (RuntimeException e) {
            log.warn("Config State operation recovery scan failed; it will retry", e);
        }
    }

    @Destroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
