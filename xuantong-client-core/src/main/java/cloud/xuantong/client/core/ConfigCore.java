package cloud.xuantong.client.core;

import cloud.xuantong.client.cache.ConfigCacheManager;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.listener.ConfigListenerManager;
import cloud.xuantong.client.model.ConfigChangeEvent;
import cloud.xuantong.client.model.ConfigGroupSnapshot;
import cloud.xuantong.client.model.ConfigInvalidation;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ConfigWatchBatch;
import cloud.xuantong.client.transport.ConfigTransport;
import cloud.xuantong.client.transport.WatchBatchHandler;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.client.util.WarningRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

/**
 * Config Agent built around the authoritative Config State Snapshot/Watch contract.
 *
 * <p>The local file cache is last-known-good state. Network failures, a temporarily
 * unavailable release, or a stale response never delete or roll it backwards.</p>
 */
public class ConfigCore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConfigCore.class);
    private static final long WATCH_TICK_MS = 500L;
    private static final long WATCH_IDLE_DELAY_MS = 1_000L;
    private static final long WATCH_MAX_BACKOFF_MS = 30_000L;
    private static final long PERIODIC_SNAPSHOT_REPAIR_MS = 5 * 60_000L;
    private static final int WATCH_BATCH_SIZE = 256;
    private static final WarningRateLimiter WATCH_WARNINGS =
            new WarningRateLimiter(Duration.ofSeconds(30));

    private final String namespace;
    private final String group;
    private final ConfigTransport transport;
    private final ConfigCacheManager cacheManager;
    private final ConfigListenerManager listenerManager = new ConfigListenerManager();
    private final Map<String, Long> decisionRevisions = new ConcurrentHashMap<>();
    private final Set<String> tombstonedDataIds = ConcurrentHashMap.newKeySet();
    private final Set<String> listenerDataIds = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService reconcileExecutor;
    private final AtomicBoolean recoveryRequested = new AtomicBoolean(true);
    private final Object applyLock = new Object();

    private volatile boolean initialized;
    private volatile long eventCursor;
    private volatile long nextWatchAttemptNanos;
    private volatile long watchBackoffMs = WATCH_IDLE_DELAY_MS;
    private volatile long lastSnapshotRepairNanos;
    private volatile WatchSubscription watchSubscription;
    private volatile boolean streamingWatch;

    public ConfigCore(List<String> serverAddresses,
                      String namespace,
                      String group,
                      String accessToken,
                      ConfigTransport transport) {
        this.namespace = requireName("namespace", namespace);
        this.group = requireName("group", group);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cacheManager = new ConfigCacheManager(this.namespace, this.group);
        this.reconcileExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-config-watch");
            thread.setDaemon(true);
            return thread;
        });
        initialize(serverAddresses, accessToken);
    }

    private void initialize(List<String> serverAddresses, String accessToken) {
        try {
            transport.setOnReconnect(this::requestRecovery);
            transport.connect(
                    serverAddresses,
                    namespace,
                    group,
                    accessToken == null ? "" : accessToken);
            initialized = true;
            boolean authoritativeSync = recoverKnownConfigs();
            startWatchSubscription();
            reconcileExecutor.scheduleWithFixedDelay(
                    this::watchTickSafely,
                    WATCH_TICK_MS,
                    WATCH_TICK_MS,
                    TimeUnit.MILLISECONDS);
            logger.info("Xuantong config client 2.0 initialized: namespace={}, group={}, "
                            + "cachedConfigs={}, authoritativeConfigs={}, eventCursor={}, "
                            + "authoritativeSync={}",
                    namespace,
                    group,
                    cacheManager.getAll().size(),
                    decisionRevisions.size(),
                    eventCursor,
                    authoritativeSync ? "SUCCEEDED" : "DEFERRED");
        } catch (Exception e) {
            initialized = false;
            transport.close();
            reconcileExecutor.shutdownNow();
            cacheManager.shutdown();
            listenerManager.shutdown();
            throw new XuantongException("Failed to initialize Xuantong config client 2.0", e);
        }
    }

    public String get(String dataId, String defaultValue) {
        ensureInitialized();
        String normalizedDataId = requireName("dataId", dataId);
        if (tombstonedDataIds.contains(normalizedDataId)) {
            return defaultValue;
        }
        String cached = cacheManager.get(normalizedDataId);
        if (cached != null) {
            return cached;
        }

        ConfigSnapshot snapshot = transport.fetch(
                normalizedDataId,
                decisionRevisions.getOrDefault(normalizedDataId, 0L));
        if (snapshot == null) {
            return defaultValue;
        }
        applySnapshot(snapshot, false);
        if (tombstonedDataIds.contains(normalizedDataId)) {
            return defaultValue;
        }
        String resolved = cacheManager.get(normalizedDataId);
        return resolved == null ? defaultValue : resolved;
    }

    private void watchTickSafely() {
        if (!initialized || System.nanoTime() < nextWatchAttemptNanos) {
            return;
        }
        try {
            if (recoveryRequested.get()
                    || elapsedMillis(lastSnapshotRepairNanos)
                    >= PERIODIC_SNAPSHOT_REPAIR_MS) {
                if (!recoverKnownConfigs()) {
                    recordWatchFailure();
                    return;
                }
            }

            if (streamingWatch) {
                return;
            }

            Set<String> knownConfigs = knownConfigs();
            if (knownConfigs.isEmpty()) {
                recordWatchSuccess(false);
                return;
            }
            ConfigWatchBatch batch = transport.watchBatch(
                    knownConfigs,
                    eventCursor,
                    WATCH_BATCH_SIZE);
            if (batch == null) {
                recordWatchFailure();
                return;
            }
            applyWatchBatch(batch);
            recordWatchSuccess(!batch.events().isEmpty());
        } catch (Exception e) {
            WarningRateLimiter.Decision decision = WATCH_WARNINGS.acquire();
            if (decision.allowed()) {
                logger.warn("Config Watch recovery failed; last-known-good values remain active; "
                                + "suppressedSinceLast={}",
                        decision.suppressedSinceLast(), e);
            }
            recordWatchFailure();
        }
    }

    private void startWatchSubscription() {
        try {
            watchSubscription = transport.subscribe(
                    eventCursor,
                    new WatchBatchHandler<>() {
                        @Override
                        public long onBatch(ConfigWatchBatch batch) {
                            return applyWatchBatch(batch);
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (!initialized) {
                                return;
                            }
                            recoveryRequested.set(true);
                            logger.debug(
                                    "Config Watch stream will resume from the committed cursor",
                                    error);
                        }
                    });
            streamingWatch = true;
        } catch (UnsupportedOperationException e) {
            streamingWatch = false;
            logger.debug("Config transport uses Watch-Batch fallback");
        }
    }

    private synchronized long applyWatchBatch(ConfigWatchBatch batch) {
        if (batch == null) {
            throw new IllegalStateException("Config Watch returned no batch");
        }
        if (batch.resetRequired()) {
            recoveryRequested.set(true);
            if (!recoverKnownConfigs()) {
                throw new IllegalStateException("Config Snapshot reset is not available yet");
            }
            return eventCursor;
        }

        Set<String> knownConfigs = knownConfigs();
        for (ConfigInvalidation invalidation : batch.events()) {
            if (!knownConfigs.contains(invalidation.dataId())) {
                continue;
            }
            long localRevision = decisionRevisions.getOrDefault(
                    invalidation.dataId(), 0L);
            if (invalidation.decisionRevision() <= localRevision) {
                continue;
            }
            ConfigSnapshot snapshot = transport.fetch(
                    invalidation.dataId(), localRevision);
            if (snapshot == null
                    || snapshot.getRevision() < invalidation.decisionRevision()) {
                logger.debug("Config invalidation remains retryable: dataId={}, "
                                + "eventRevision={}, decisionRevision={}, localRevision={}",
                        invalidation.dataId(),
                        invalidation.eventRevision(),
                        invalidation.decisionRevision(),
                        localRevision);
                throw new IllegalStateException(
                        "Applicable Config release has not reached the invalidation revision");
            }
            applySnapshot(snapshot, true);
        }

        eventCursor = Math.max(eventCursor, batch.coveredThroughRevision());
        return eventCursor;
    }

    private synchronized boolean recoverKnownConfigs() {
        Set<String> knownConfigs = knownConfigs();
        if (knownConfigs.isEmpty()) {
            recoveryRequested.set(false);
            lastSnapshotRepairNanos = System.nanoTime();
            return true;
        }

        ConfigGroupSnapshot snapshot = transport.snapshot(knownConfigs, eventCursor);
        if (snapshot == null) {
            return false;
        }
        for (String dataId : knownConfigs) {
            long localRevision = decisionRevisions.getOrDefault(dataId, 0L);
            long authoritativeRevision = snapshot.decisionRevisions()
                    .getOrDefault(dataId, 0L);
            if (authoritativeRevision <= localRevision && localRevision > 0) {
                continue;
            }
            ConfigSnapshot resolved = transport.fetch(dataId, localRevision);
            if (resolved == null) {
                // Missing/unavailable is not a deletion signal. Keep the last-known-good value.
                continue;
            }
            if (authoritativeRevision > 0
                    && resolved.getRevision() < authoritativeRevision) {
                return false;
            }
            applySnapshot(resolved, localRevision > 0);
        }
        eventCursor = Math.max(eventCursor, snapshot.eventRevision());
        recoveryRequested.set(false);
        lastSnapshotRepairNanos = System.nanoTime();
        return true;
    }

    private Set<String> knownConfigs() {
        Set<String> result = new LinkedHashSet<>(cacheManager.getAll().keySet());
        result.addAll(decisionRevisions.keySet());
        result.addAll(listenerDataIds);
        return result;
    }

    private boolean applySnapshot(ConfigSnapshot snapshot, boolean notify) {
        if (snapshot == null || snapshot.getDataId() == null) {
            return false;
        }
        synchronized (applyLock) {
            long localRevision = decisionRevisions.getOrDefault(
                    snapshot.getDataId(), 0L);
            String oldValue = cacheManager.get(snapshot.getDataId());
            boolean alreadyApplied = snapshot.isTombstone()
                    ? tombstonedDataIds.contains(snapshot.getDataId())
                    : oldValue != null && !tombstonedDataIds.contains(snapshot.getDataId());
            boolean valueChanged = snapshot.isTombstone()
                    ? !alreadyApplied
                    : !Objects.equals(oldValue, snapshot.getContent());
            if (snapshot.getRevision() < localRevision
                    || (snapshot.getRevision() == localRevision && alreadyApplied)) {
                logger.debug("Ignoring stale/duplicate config snapshot: dataId={}, "
                                + "revision={}, localRevision={}",
                        snapshot.getDataId(), snapshot.getRevision(), localRevision);
                return false;
            }
            if (snapshot.isTombstone()) {
                cacheManager.remove(snapshot.getDataId());
                tombstonedDataIds.add(snapshot.getDataId());
            } else {
                cacheManager.batchUpdate(Map.of(
                        snapshot.getDataId(), snapshot.getContent()));
                tombstonedDataIds.remove(snapshot.getDataId());
            }
            decisionRevisions.put(snapshot.getDataId(), snapshot.getRevision());
            if (notify && valueChanged) {
                listenerManager.fireEvent(new ConfigChangeEvent(
                        namespace,
                        group,
                        snapshot.getDataId(),
                        snapshot.getContent(),
                        snapshot.getRevision()));
            }
            return true;
        }
    }

    private void requestRecovery() {
        if (!initialized) {
            return;
        }
        recoveryRequested.set(true);
        nextWatchAttemptNanos = 0L;
    }

    private void recordWatchSuccess(boolean backlog) {
        watchBackoffMs = WATCH_IDLE_DELAY_MS;
        long delayMs = backlog ? 0L : WATCH_IDLE_DELAY_MS;
        nextWatchAttemptNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(delayMs);
    }

    private void recordWatchFailure() {
        long base = Math.min(WATCH_MAX_BACKOFF_MS, Math.max(WATCH_IDLE_DELAY_MS, watchBackoffMs));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 4L));
        nextWatchAttemptNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(base + jitter);
        watchBackoffMs = Math.min(WATCH_MAX_BACKOFF_MS, base * 2L);
    }

    private long elapsedMillis(long startedAtNanos) {
        if (startedAtNanos == 0L) {
            return Long.MAX_VALUE;
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public void addConfigListener(String dataId, ConfigListener listener) {
        String normalizedDataId = requireName("dataId", dataId);
        listenerManager.addListener(normalizedDataId, listener);
        listenerDataIds.add(normalizedDataId);
        requestRecovery();
    }

    public void removeConfigListener(String dataId, ConfigListener listener) {
        String normalizedDataId = requireName("dataId", dataId);
        listenerManager.removeListener(normalizedDataId, listener);
        if (!listenerManager.hasListeners(normalizedDataId)) {
            listenerDataIds.remove(normalizedDataId);
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public String getGroup() {
        return group;
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() {
        initialized = false;
        reconcileExecutor.shutdownNow();
        WatchSubscription subscription = watchSubscription;
        watchSubscription = null;
        if (subscription != null) {
            subscription.close();
        }
        transport.close();
        cacheManager.shutdown();
        listenerManager.shutdown();
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Config client is not initialized");
        }
    }

    private String requireName(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value;
    }
}
