package cloud.xuantong.client.core;

import cloud.xuantong.client.cache.ConfigCacheManager;
import cloud.xuantong.client.model.ConfigGroupSnapshot;
import cloud.xuantong.client.model.ConfigInvalidation;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ConfigWatchBatch;
import cloud.xuantong.client.transport.ConfigTransport;
import cloud.xuantong.client.listener.ListenerRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCoreTest {
    @TempDir
    Path tempDir;

    @Test
    void startupUsesSnapshotAndRefreshesLastKnownGood() {
        withUserDir(() -> {
            seed("app.yml", "stale");
            MutableConfigTransport transport = new MutableConfigTransport(
                    new ConfigSnapshot("app.yml", "fresh", 2L, "sum-2", "text"),
                    2L);

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                assertTrue(transport.reconnectListenerInstalledBeforeConnect);
                assertEquals(1, transport.snapshotCount.get());
                assertEquals(1, transport.fetchCount.get());
                assertEquals("fresh", core.get("app.yml", null));
            }
        });
    }

    @Test
    void watchBatchAppliesGrayAndRollbackDecisionsMonotonically() throws Exception {
        withUserDir(() -> {
            seed("app.yml", "baseline");
            MutableConfigTransport transport = new MutableConfigTransport(
                    new ConfigSnapshot("app.yml", "baseline", 1L, "sum-1", "text"),
                    1L);

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                transport.publish(new ConfigSnapshot(
                        "app.yml", "candidate", 2L, "sum-2", "text"), 2L);
                await(() -> "candidate".equals(core.get("app.yml", null)));
                int afterCandidate = transport.fetchCount.get();

                Thread.sleep(700L);
                assertEquals(afterCandidate, transport.fetchCount.get(),
                        "covered Watch cursor must prevent duplicate fetches");

                transport.publish(new ConfigSnapshot(
                        "app.yml", "baseline", 3L, "sum-1", "text"), 3L);
                await(() -> "baseline".equals(core.get("app.yml", null)));
                assertEquals(2L, transport.lastFetchMinRevision,
                        "rollback fetch must fence against the previously applied decision");
            }
        });
    }

    @Test
    void failedInvalidationFetchDoesNotAdvanceWatchCursor() throws Exception {
        withUserDir(() -> {
            seed("app.yml", "baseline");
            MutableConfigTransport transport = new MutableConfigTransport(
                    new ConfigSnapshot("app.yml", "baseline", 1L, "sum-1", "text"),
                    1L);

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                transport.publish(new ConfigSnapshot(
                        "app.yml", "candidate", 2L, "sum-2", "text"), 2L);
                transport.failNextFetch.set(true);

                await(() -> transport.failedFetches.get() == 1);
                assertEquals("baseline", core.get("app.yml", null));
                await(() -> "candidate".equals(core.get("app.yml", null)));
                assertTrue(transport.requestedWatchCursors.stream()
                                .filter(cursor -> cursor == 1L).count() >= 2,
                        "failed fetch must leave the authoritative event retryable");
            }
        });
    }

    @Test
    void staleFetchAfterInvalidationNeverRollsBackOrCommitsTheWatchCursor()
            throws Exception {
        withUserDir(() -> {
            seed("app.yml", "stable-v3");
            MutableConfigTransport transport = new MutableConfigTransport(
                    new ConfigSnapshot(
                            "app.yml", "stable-v3", 3L, "sum-3", "text"),
                    3L);

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                transport.announce(
                        new ConfigSnapshot(
                                "app.yml", "stale-v2", 2L, "sum-2", "text"),
                        4L,
                        4L);

                await(() -> transport.fetchCount.get() >= 2);
                assertEquals("stable-v3", core.get("app.yml", null),
                        "A stale Gateway response must not roll back last-known-good");

                transport.publish(new ConfigSnapshot(
                        "app.yml", "stable-v4", 4L, "sum-4", "text"), 4L);
                await(() -> "stable-v4".equals(core.get("app.yml", null)));
                assertTrue(transport.requestedWatchCursors.stream()
                                .filter(cursor -> cursor == 3L).count() >= 2,
                        "The invalidation cursor must remain retryable until revision 4 is fetched");
            }
        });
    }

    @Test
    void reconnectRunsSnapshotRepairAndNeverDeletesLastKnownGood() throws Exception {
        withUserDir(() -> {
            seed("app.yml", "baseline");
            MutableConfigTransport transport = new MutableConfigTransport(
                    new ConfigSnapshot("app.yml", "baseline", 1L, "sum-1", "text"),
                    1L);

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                transport.unavailable.set(true);
                transport.reconnectListener.run();
                Thread.sleep(700L);
                assertEquals("baseline", core.get("app.yml", null));

                transport.unavailable.set(false);
                transport.publish(new ConfigSnapshot(
                        "app.yml", "recovered", 2L, "sum-2", "text"), 2L);
                transport.reconnectListener.run();
                await(() -> "recovered".equals(core.get("app.yml", null)));
                assertTrue(transport.snapshotCount.get() >= 2);
            }
        });
    }

    @Test
    void authoritativeTombstoneDeletesCacheNotifiesNullAndCanBeReactivated()
            throws Exception {
        withUserDir(() -> {
            seed("app.yml", "baseline");
            MutableConfigTransport transport = new MutableConfigTransport(
                    new ConfigSnapshot("app.yml", "baseline", 1L, "sum-1", "text"),
                    1L);
            AtomicBoolean tombstoneNotified = new AtomicBoolean();
            AtomicReference<String> lastValue = new AtomicReference<>("not-called");

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                core.addConfigListener("app.yml", event -> {
                    lastValue.set(event.getNewValue());
                    if (event.getNewValue() == null) {
                        tombstoneNotified.set(true);
                    }
                });

                transport.publish(ConfigSnapshot.tombstone("app.yml", 2L), 2L);
                await(() -> "fallback".equals(core.get("app.yml", "fallback")));
                await(tombstoneNotified::get);
                assertEquals(null, lastValue.get());
                int fetchesAfterDelete = transport.fetchCount.get();
                assertEquals("fallback", core.get("app.yml", "fallback"));
                assertEquals(fetchesAfterDelete, transport.fetchCount.get(),
                        "known tombstone must act as a negative cache");

                transport.publish(new ConfigSnapshot(
                        "app.yml", "rebuilt", 3L, "sum-3", "text"), 3L);
                await(() -> "rebuilt".equals(core.get("app.yml", null)));
                await(() -> "rebuilt".equals(lastValue.get()));
            }

            ConfigCacheManager reloaded = new ConfigCacheManager(
                    "public", "DEFAULT_GROUP");
            assertEquals("rebuilt", reloaded.get("app.yml"));
            reloaded.shutdown();
        });
    }

    @Test
    void coldFetchOfAuthoritativeTombstoneReturnsDefaultAndNegativeCaches() {
        withUserDir(() -> {
            MutableConfigTransport transport = new MutableConfigTransport(
                    ConfigSnapshot.tombstone("app.yml", 2L),
                    2L);

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                assertEquals("fallback", core.get("app.yml", "fallback"));
                int fetchesAfterTombstone = transport.fetchCount.get();
                assertEquals("fallback", core.get("app.yml", "fallback"));
                assertEquals(fetchesAfterTombstone, transport.fetchCount.get(),
                        "cold-fetched tombstone must become a negative cache entry");
            }

            ConfigCacheManager reloaded = new ConfigCacheManager(
                    "public", "DEFAULT_GROUP");
            assertEquals(null, reloaded.get("app.yml"));
            reloaded.shutdown();
        });
    }

    @Test
    void closingListenerRegistrationStopsFutureNotifications() throws Exception {
        withUserDir(() -> {
            seed("app.yml", "baseline");
            MutableConfigTransport transport = new MutableConfigTransport(
                    new ConfigSnapshot("app.yml", "baseline", 1L, "sum-1", "text"),
                    1L);
            AtomicInteger notifications = new AtomicInteger();

            try (ConfigCore core = new ConfigCore(
                    List.of("gateway-a:8090"),
                    "public", "DEFAULT_GROUP", "", transport)) {
                ListenerRegistration registration = core.addConfigListener(
                        "app.yml", event -> notifications.incrementAndGet());
                registration.close();
                registration.close();

                transport.publish(new ConfigSnapshot(
                        "app.yml", "candidate", 2L, "sum-2", "text"), 2L);
                await(() -> "candidate".equals(core.get("app.yml", null)));
                Thread.sleep(200L);
                assertEquals(0, notifications.get());
            }
        });
    }

    private void seed(String dataId, String value) {
        ConfigCacheManager seed = new ConfigCacheManager("public", "DEFAULT_GROUP");
        seed.batchUpdate(Collections.singletonMap(dataId, value));
        seed.shutdown();
    }

    private void withUserDir(ThrowingRunnable runnable) {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            runnable.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(condition.getAsBoolean());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class MutableConfigTransport implements ConfigTransport {
        private final AtomicInteger fetchCount = new AtomicInteger();
        private final AtomicInteger snapshotCount = new AtomicInteger();
        private final AtomicInteger failedFetches = new AtomicInteger();
        private final AtomicBoolean failNextFetch = new AtomicBoolean();
        private final AtomicBoolean unavailable = new AtomicBoolean();
        private final java.util.concurrent.CopyOnWriteArrayList<Long> requestedWatchCursors =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile ConfigSnapshot current;
        private volatile long eventRevision;
        private volatile long announcedDecisionRevision;
        private volatile Runnable reconnectListener;
        private volatile boolean reconnectListenerInstalledBeforeConnect;
        private volatile long lastFetchMinRevision;

        private MutableConfigTransport(ConfigSnapshot current, long eventRevision) {
            this.current = current;
            this.eventRevision = eventRevision;
            this.announcedDecisionRevision = current.getRevision();
        }

        private void publish(ConfigSnapshot snapshot, long newEventRevision) {
            this.current = snapshot;
            this.eventRevision = newEventRevision;
            this.announcedDecisionRevision = snapshot.getRevision();
        }

        private void announce(
                ConfigSnapshot returnedSnapshot,
                long decisionRevision,
                long newEventRevision) {
            this.current = returnedSnapshot;
            this.announcedDecisionRevision = decisionRevision;
            this.eventRevision = newEventRevision;
        }

        @Override
        public void connect(List<String> serverAddresses, String namespace, String group,
                            String accessToken) {
            reconnectListenerInstalledBeforeConnect = reconnectListener != null;
        }

        @Override
        public ConfigSnapshot fetch(String dataId, long minDecisionRevision) {
            fetchCount.incrementAndGet();
            lastFetchMinRevision = minDecisionRevision;
            if (unavailable.get()) {
                return null;
            }
            if (failNextFetch.compareAndSet(true, false)) {
                failedFetches.incrementAndGet();
                return null;
            }
            return current;
        }

        @Override
        public ConfigGroupSnapshot snapshot(
                Collection<String> dataIds, long minEventRevision) {
            snapshotCount.incrementAndGet();
            if (unavailable.get()) {
                return null;
            }
            return new ConfigGroupSnapshot(
                    eventRevision,
                    0L,
                    Map.of(current.getDataId(), current.getRevision()));
        }

        @Override
        public ConfigWatchBatch watchBatch(
                Collection<String> dataIds, long afterEventRevision, int maxBatchSize) {
            requestedWatchCursors.add(afterEventRevision);
            if (unavailable.get()) {
                return null;
            }
            if (afterEventRevision < eventRevision) {
                return new ConfigWatchBatch(
                        afterEventRevision,
                        eventRevision,
                        0L,
                        false,
                        List.of(new ConfigInvalidation(
                                current.getDataId(),
                                eventRevision,
                                announcedDecisionRevision)));
            }
            return new ConfigWatchBatch(
                    afterEventRevision,
                    afterEventRevision,
                    0L,
                    false,
                    List.of());
        }

        @Override
        public void setOnReconnect(Runnable listener) {
            reconnectListener = listener;
        }

        @Override
        public void close() {
        }
    }
}
