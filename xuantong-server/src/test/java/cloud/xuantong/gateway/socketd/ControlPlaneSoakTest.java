package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.metrics.ControlPlaneTransportMetricsSnapshot;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.client.transport.impl.SocketDTransport;
import cloud.xuantong.config.state.ConfigActor;
import cloud.xuantong.config.state.ConfigContentDraft;
import cloud.xuantong.config.state.ConfigContentReference;
import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.config.state.ConfigMutation;
import cloud.xuantong.config.state.ConfigStateCodec;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.server.state.ConfigStatePlaneProperties;
import cloud.xuantong.server.state.ControlStatePlaneRuntime;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevisionType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.ratis.server.RaftServer;
import org.noear.socketd.SocketD;
import org.noear.solon.Solon;
import org.noear.solon.net.socketd.listener.PathListenerPlus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in production-path staircase and soak runner. It keeps latency memory bounded,
 * emits periodic resource samples, and verifies resource reclamation after the run.
 */
class ControlPlaneSoakTest {
    private static final String PREFIX = "xuantong.soak.";
    private static final StateGroupId CONFIG_GROUP =
            StateGroupId.config("config-default");

    @TempDir
    Path tempDirectory;

    @Test
    void profileValidationRejectsUnsafeOrUnboundedValues() {
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile(
                0, 1, 1, 1, 0, 100_000_000, 100_000_000,
                0, 128, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile(
                1, 2, 3, 1, 0, 100_000_000, 100_000_000,
                0, 128, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile(
                1, 2, 1, 3, 0, 100_000_000, 100_000_000,
                0, 128, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile(
                1, 2, 2, 1, 100, 100, 1_000,
                0, 128, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new LoadProfile(
                1, 2, 2, 1, 100, 200, 3,
                0, 128, 1, 0, ""));
    }

    @Test
    void boundedLatencyHistogramComputesPercentilesWithoutRetainingRequests() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(TimeUnit.MILLISECONDS.toNanos(1));
        histogram.record(TimeUnit.MILLISECONDS.toNanos(9));
        histogram.record(TimeUnit.MILLISECONDS.toNanos(120));

        assertEquals(3L, histogram.count());
        assertEquals(10L, histogram.percentileUpperBoundMs(0.50D));
        assertEquals(200L, histogram.percentileUpperBoundMs(0.95D));
        assertTrue(histogram.maxNanos() >= TimeUnit.MILLISECONDS.toNanos(120));
    }

    @Test
    void profileReportMakesLoadModeAndTestQuotaExplicit() {
        LoadProfile capacity = new LoadProfile(
                30, 16, 16, 16, 0, 100_000_000, 100_000_000,
                12, 1_024, 5, 15, "");
        LoadProfile controlled = new LoadProfile(
                30, 16, 16, 16, 500, 600, 1_200,
                12, 1_024, 5, 15, "");

        assertTrue(capacity.toJson().contains("\"mode\":\"capacity-saturation\""));
        assertTrue(capacity.toJson().contains(
                "\"tenantRequestRatePerSecond\":100000000"));
        assertTrue(controlled.toJson().contains(
                "\"mode\":\"controlled-lossless\""));
        assertTrue(controlled.toJson().contains("\"tenantRequestBurst\":1200"));
    }

    @Test
    void runtimeMetadataRecordsExactDependencyAndExecutionContext() {
        RuntimeMetadata metadata = RuntimeMetadata.capture();
        String json = metadata.toJson();

        assertTrue(json.contains("\"transportPath\":"
                + "\"native-socketd-tcp+ratis-single-node+in-process\""));
        assertTrue(json.contains("\"socketdVersion\":\"2.6.0\""));
        assertTrue(json.contains("\"solonVersion\":\"4.0.3\""));
        assertTrue(json.contains("\"ratisVersion\":\"3.2.2\""));
        assertTrue(json.contains("\"javaVersion\":\""));
        assertTrue(metadata.availableProcessors() > 0);
        assertTrue(metadata.maxHeapBytes() > 0L);
    }

    @Test
    void resourceGrowthUsesConfiguredWarmupBoundary() {
        ResourceGrowth growth = ResourceGrowth.from(List.of(
                ResourceSample.synthetic(0L, 10L, 8L, 2, 10L, 100L),
                ResourceSample.synthetic(1_000L, 100L, 80L, 10, 20L, 1_000L),
                ResourceSample.synthetic(3_000L, 140L, 90L, 11, 30L, 1_600L)),
                1_000L);

        assertEquals(1_000L, growth.baselineElapsedMs());
        assertEquals(3_000L, growth.lastElapsedMs());
        assertEquals(2_000L, growth.observationWindowMs());
        assertEquals(40L, growth.heapUsedBytesDelta());
        assertEquals(10L, growth.heapAfterLastGcBytesDelta());
        assertEquals(1, growth.liveThreadsDelta());
        assertEquals(10L, growth.directBufferMemoryUsedBytesDelta());
        assertEquals(600L, growth.walBytesDelta());
    }

    @Test
    void runsParameterizedProductionPathSoakAndWritesGrowthReport() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PREFIX + "enabled"),
                "Enable with -D" + PREFIX + "enabled=true");
        LoadProfile profile = LoadProfile.fromSystemProperties();
        RuntimeMetadata runtimeMetadata = RuntimeMetadata.capture();
        long startedEpochMs = System.currentTimeMillis();
        int statePort = freePort();
        int gatewayPort = freePort();
        ReportSink reportSink = ReportSink.open(
                profile, runtimeMetadata, startedEpochMs);
        ConfigKey key = new ConfigKey(
                "public", "DEFAULT_GROUP", "soak.payload");
        ControlPlaneRequestDispatcher dispatcher = new ControlPlaneRequestDispatcher();
        ConfigStatePlaneProperties stateProperties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                CONFIG_GROUP.value(),
                "state-1@127.0.0.1:" + statePort,
                tempDirectory.resolve("state"),
                true);
        ControlStatePlaneRuntime stateRuntime = new ControlStatePlaneRuntime(
                stateProperties, dispatcher);
        ControlPlaneGatewayServer gatewayServer = null;
        ControlPlaneGatewayRuntime gatewayRuntime = null;
        ExecutorService fetchWorkers = null;
        ExecutorService publisher = null;
        ScheduledExecutorService sampler = null;
        List<SocketDTransport> transports = new ArrayList<>();
        List<WatchSubscription> subscriptions = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        LongAdder fetchSucceeded = new LongAdder();
        LongAdder fetchFailed = new LongAdder();
        LongAdder revisionRegressed = new LongAdder();
        LongAdder publishSucceeded = new LongAdder();
        LongAdder publishFailed = new LongAdder();
        LatencyHistogram fetchLatency = new LatencyHistogram();
        List<ResourceSample> samples = Collections.synchronizedList(new ArrayList<>());
        long workloadStartedNanos = 0L;
        long workloadElapsedNanos = 0L;
        StorageSize storageBefore = StorageSize.empty();
        StorageSize storageAfter = StorageSize.empty();
        ResourceSample afterClose = null;
        try {
            stateRuntime.start();
            ApplyResult initial = stateRuntime.stateClient()
                    .submit(mutation(key, 1L, payload(1L, profile.payloadBytes())))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ApplyStatus.APPLIED, initial.status());
            AtomicLong latestDecisionRevision = new AtomicLong(1L);
            AtomicLong latestEventRevision = new AtomicLong(
                    revision(initial, StateRevisionType.CONFIG_EVENT));

            ControlPlaneGatewayProperties gatewayProperties =
                    new ControlPlaneGatewayProperties(
                            "127.0.0.1",
                            gatewayPort,
                            "cluster-soak-test",
                            "gateway-soak-1",
                            1L,
                            10_000L,
                            Math.max(256, profile.clients() * 2),
                            Math.min(64, Math.max(4, Runtime.getRuntime()
                                    .availableProcessors() * 2)),
                            Math.max(4_096, profile.fetchConcurrency() * 16),
                            profile.tenantRequestRatePerSecond(),
                            profile.tenantRequestBurst(),
                            5_000L,
                            ControlPlaneGatewayProperties.ClientAuth.NONE);
            gatewayRuntime = new ControlPlaneGatewayRuntime(gatewayProperties);
            PathListenerPlus router = new PathListenerPlus(true);
            router.doOf(ControlPlaneProtocol.CONTROL_PATH,
                    new ControlPlaneGatewayEndpoint(
                            gatewayProperties, gatewayRuntime, dispatcher));
            gatewayServer = new ControlPlaneGatewayServer(
                    gatewayProperties, gatewayRuntime, router, null);
            gatewayServer.start();

            List<String> addresses = List.of("127.0.0.1:" + gatewayPort);
            ControlPlaneOptions options = new ControlPlaneOptions(
                    "default",
                    CONFIG_GROUP.value(),
                    "cluster-soak-test",
                    1L,
                    "tcp-default",
                    3_000L,
                    5_000L,
                    10_000L,
                    2_000L);
            List<AtomicLong> observedDecisions = new ArrayList<>();
            List<AtomicLong> watchCursors = new ArrayList<>();
            for (int index = 0; index < profile.clients(); index++) {
                SocketDTransport transport = new SocketDTransport(
                        new ClientIdentity(
                                "soak-client", "soak-client@" + index),
                        options);
                transport.connect(addresses, key.namespace(), key.group(), "");
                ConfigSnapshot snapshot = transport.fetch(key.dataId(), 0L);
                assertNotNull(snapshot);
                assertEquals(1L, snapshot.getRevision());
                transports.add(transport);
                observedDecisions.add(new AtomicLong(snapshot.getRevision()));
                if (index < profile.watchers()) {
                    AtomicLong cursor = new AtomicLong(latestEventRevision.get());
                    watchCursors.add(cursor);
                    subscriptions.add(transport.subscribe(cursor.get(), batch -> {
                        cursor.accumulateAndGet(
                                batch.coveredThroughRevision(), Math::max);
                        return batch.coveredThroughRevision();
                    }));
                }
            }
            ControlPlaneGatewayRuntime activeRuntime = gatewayRuntime;
            awaitTrue(() -> activeRuntime.activeSessions() == profile.clients()
                            && activeRuntime.activeSubscriptions() == profile.watchers(),
                    Duration.ofSeconds(20));

            storageBefore = storageSize(stateProperties.storageDirectory());
            ResourceSample initialSample = ResourceSample.capture(
                    0L, activeRuntime, transports,
                    stateProperties.storageDirectory());
            samples.add(initialSample);
            reportSink.sample(initialSample);
            long deadlineNanos = System.nanoTime()
                    + Duration.ofSeconds(profile.durationSeconds()).toNanos();
            workloadStartedNanos = System.nanoTime();
            RatePacer pacer = new RatePacer(
                    profile.fetchRatePerSecond(), workloadStartedNanos);
            AtomicInteger nextTransport = new AtomicInteger();

            fetchWorkers = Executors.newFixedThreadPool(
                    profile.fetchConcurrency(), named("xuantong-soak-fetch-"));
            List<Future<?>> fetchTasks = new ArrayList<>();
            for (int worker = 0; worker < profile.fetchConcurrency(); worker++) {
                fetchTasks.add(fetchWorkers.submit(() -> {
                    while (!stop.get() && System.nanoTime() < deadlineNanos) {
                        pacer.awaitNext(deadlineNanos);
                        if (stop.get() || System.nanoTime() >= deadlineNanos) {
                            break;
                        }
                        int index = Math.floorMod(
                                nextTransport.getAndIncrement(), transports.size());
                        long requestStarted = System.nanoTime();
                        try {
                            ConfigSnapshot snapshot = transports.get(index).fetch(
                                    key.dataId(), 0L);
                            fetchLatency.record(System.nanoTime() - requestStarted);
                            if (snapshot == null) {
                                fetchFailed.increment();
                                continue;
                            }
                            AtomicLong observed = observedDecisions.get(index);
                            long previous = observed.getAndAccumulate(
                                    snapshot.getRevision(), Math::max);
                            if (snapshot.getRevision() < previous) {
                                revisionRegressed.increment();
                            } else {
                                fetchSucceeded.increment();
                            }
                        } catch (Throwable e) {
                            fetchLatency.record(System.nanoTime() - requestStarted);
                            fetchFailed.increment();
                            fatal.compareAndSet(null, e);
                            stop.set(true);
                        }
                    }
                    return null;
                }));
            }

            if (profile.publishRatePerMinute() > 0) {
                publisher = Executors.newSingleThreadExecutor(
                        named("xuantong-soak-publish-"));
                long publishIntervalNanos = Math.max(
                        1L,
                        Math.round(60_000_000_000D
                                / profile.publishRatePerMinute()));
                publisher.submit(() -> {
                    long nextPublish = System.nanoTime() + publishIntervalNanos;
                    while (!stop.get() && nextPublish < deadlineNanos) {
                        try {
                            parkUntil(nextPublish);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (stop.get() || System.nanoTime() >= deadlineNanos) {
                            break;
                        }
                        long targetRevision = latestDecisionRevision.get() + 1L;
                        try {
                            ApplyResult applied = submitEventually(
                                    stateRuntime,
                                    mutation(
                                            key,
                                            targetRevision,
                                            payload(targetRevision,
                                                    profile.payloadBytes())));
                            if (applied.status() != ApplyStatus.APPLIED) {
                                throw new IllegalStateException(
                                        "Unexpected publish status " + applied.status());
                            }
                            latestDecisionRevision.set(targetRevision);
                            latestEventRevision.set(revision(
                                    applied, StateRevisionType.CONFIG_EVENT));
                            publishSucceeded.increment();
                        } catch (Throwable e) {
                            publishFailed.increment();
                            fatal.compareAndSet(null, e);
                            stop.set(true);
                        }
                        nextPublish += publishIntervalNanos;
                    }
                });
            }

            sampler = Executors.newSingleThreadScheduledExecutor(
                    named("xuantong-soak-sample-"));
            long sampleStartedNanos = workloadStartedNanos;
            sampler.scheduleAtFixedRate(() -> {
                try {
                    ResourceSample sample = ResourceSample.capture(
                            TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - sampleStartedNanos),
                            activeRuntime,
                            transports,
                            stateProperties.storageDirectory());
                    samples.add(sample);
                    reportSink.sample(sample);
                } catch (Throwable e) {
                    fatal.compareAndSet(null, e);
                    stop.set(true);
                }
            }, profile.sampleIntervalSeconds(), profile.sampleIntervalSeconds(),
                    TimeUnit.SECONDS);

            long waitSeconds = profile.durationSeconds() + 30L;
            for (Future<?> task : fetchTasks) {
                task.get(waitSeconds, TimeUnit.SECONDS);
            }
            workloadElapsedNanos = System.nanoTime() - workloadStartedNanos;
            stop.set(true);
            if (publisher != null) {
                publisher.shutdownNow();
                publisher.awaitTermination(10, TimeUnit.SECONDS);
            }
            if (sampler != null) {
                sampler.shutdownNow();
                sampler.awaitTermination(10, TimeUnit.SECONDS);
            }

            long requiredEventRevision = latestEventRevision.get();
            if (!watchCursors.isEmpty() && publishSucceeded.sum() > 0L) {
                awaitTrue(() -> watchCursors.stream()
                                .allMatch(cursor -> cursor.get() >= requiredEventRevision),
                        Duration.ofSeconds(30));
            }
            storageAfter = storageSize(stateProperties.storageDirectory());

            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
            for (SocketDTransport transport : transports) {
                transport.close();
            }
            awaitTrue(() -> activeRuntime.activeSessions() == 0
                            && activeRuntime.activeSubscriptions() == 0
                            && activeRuntime.pendingWatchAcknowledgements() == 0
                            && activeRuntime.inFlightRequests() == 0
                            && activeRuntime.requestAcceptedTotal()
                            == activeRuntime.requestCompletedTotal(),
                    Duration.ofSeconds(20));
            awaitTrue(() -> threadCount("nettyTcpClientWork-") == 0
                            && threadCount("xuantong-control-plane-watch") == 0,
                    Duration.ofSeconds(15));
            afterClose = ResourceSample.capture(
                    TimeUnit.NANOSECONDS.toMillis(workloadElapsedNanos),
                    activeRuntime,
                    transports,
                    stateProperties.storageDirectory());
            transports.clear();

            List<ResourceSample> reportSamples = copy(samples);
            LoadReport report = new LoadReport(
                    startedEpochMs,
                    System.currentTimeMillis(),
                    profile,
                    runtimeMetadata,
                    fetchSucceeded.sum(),
                    fetchFailed.sum(),
                    revisionRegressed.sum(),
                    publishSucceeded.sum(),
                    publishFailed.sum(),
                    latestDecisionRevision.get(),
                    latestEventRevision.get(),
                    fetchLatency,
                    storageBefore,
                    storageAfter,
                    afterClose,
                    reportSamples,
                    fatal.get());
            report.write(reportSink);

            assertNull(fatal.get(), "Soak worker failed: " + fatal.get());
            assertEquals(0L, fetchFailed.sum());
            assertEquals(0L, revisionRegressed.sum());
            assertEquals(0L, publishFailed.sum());
            assertTrue(fetchSucceeded.sum() > 0L);
            assertEquals(0, afterClose.activeSessions());
            assertEquals(0, afterClose.activeSubscriptions());
            assertEquals(0, afterClose.inFlightRequests());
            assertEquals(0, afterClose.clientActiveSessions());
            assertEquals(0, afterClose.clientInFlightRequests());
            assertEquals(0, afterClose.clientRegisteredWatches());
            assertEquals(0, afterClose.clientActiveSubscribeStreams());
            assertEquals(activeRuntime.requestAcceptedTotal(),
                    activeRuntime.requestCompletedTotal());
            assertEquals(0L, activeRuntime.tenantRequestRateLimitedTotal(),
                    "Load profile must not confuse tenant quota rejection with capacity");
            assertEquals(0L, activeRuntime.stateCallbackRejectedTotal());
            assertEquals(profile.clients(), reportSamples.stream()
                    .mapToInt(ResourceSample::clientActiveSessions)
                    .max().orElse(0));
            assertEquals(profile.watchers(), reportSamples.stream()
                    .mapToInt(ResourceSample::clientRegisteredWatches)
                    .max().orElse(0));
            assertEquals(profile.watchers(), reportSamples.stream()
                    .mapToInt(ResourceSample::clientActiveSubscribeStreams)
                    .max().orElse(0));
            assertEquals(0, threadCount("nettyTcpClientWork-"));
            assertEquals(0, threadCount("xuantong-control-plane-watch"));
        } finally {
            stop.set(true);
            shutdownNow(sampler);
            shutdownNow(publisher);
            shutdownNow(fetchWorkers);
            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            for (SocketDTransport transport : transports) {
                transport.close();
            }
            if (gatewayServer != null) {
                gatewayServer.stop();
            }
            stateRuntime.stop();
            reportSink.close();
        }
    }

    private StateCommand mutation(ConfigKey key, long revision, byte[] value) {
        return ConfigStateCodec.mutationCommand(
                CONFIG_GROUP,
                "soak-publish-" + revision,
                new ConfigMutation(
                        new ConfigActor("default", "soak-test"),
                        key,
                        revision - 1L,
                        ConfigContentDraft.inline(
                                "text", Math.toIntExact(revision), value),
                        ConfigContentReference.newContent(),
                        List.of()));
    }

    private byte[] payload(long revision, int size) {
        byte[] prefix = ("revision=" + revision + ";")
                .getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[size];
        for (int index = 0; index < result.length; index++) {
            result[index] = index < prefix.length ? prefix[index] : (byte) 'x';
        }
        return result;
    }

    private long revision(ApplyResult result, StateRevisionType type) {
        return result.revisions().stream()
                .filter(revision -> revision.type() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Apply result did not contain " + type))
                .value();
    }

    private ApplyResult submitEventually(
            ControlStatePlaneRuntime runtime, StateCommand command) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return runtime.stateClient().submit(command)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                Thread.sleep(100L);
            }
        }
        throw last == null
                ? new IllegalStateException("Config State did not elect a leader") : last;
    }

    private void awaitTrue(Check condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.evaluate(), "Condition was not satisfied before timeout");
    }

    private StorageSize storageSize(Path root) throws Exception {
        long walFiles = 0L;
        long walBytes = 0L;
        long snapshotFiles = 0L;
        long snapshotBytes = 0L;
        if (Files.exists(root)) {
            try (var files = Files.walk(root)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    String name = path.getFileName().toString();
                    if (name.startsWith("log_")) {
                        walFiles++;
                        walBytes += Files.size(path);
                    } else if (name.startsWith("snapshot.")
                            && !name.endsWith(".md5")) {
                        snapshotFiles++;
                        snapshotBytes += Files.size(path);
                    }
                }
            }
        }
        return new StorageSize(walFiles, walBytes, snapshotFiles, snapshotBytes);
    }

    private int threadCount(String prefix) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            Assumptions.assumeTrue(false,
                    "Local socket binding is unavailable in this sandbox: " + e.getMessage());
            return -1;
        }
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void parkUntil(long deadlineNanos) throws InterruptedException {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100L)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private static void shutdownNow(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> List<T> copy(List<T> source) {
        synchronized (source) {
            return List.copyOf(source);
        }
    }

    private static String json(String value) {
        String normalized = value == null ? "" : value;
        return normalized.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }

    private record RuntimeMetadata(
            String runLabel,
            String buildRevision,
            String buildState,
            String transportPath,
            String javaVersion,
            String javaVmName,
            String javaVmVendor,
            String socketdVersion,
            String solonVersion,
            String ratisVersion,
            String osName,
            String osVersion,
            String osArch,
            int availableProcessors,
            long physicalMemoryBytes,
            long maxHeapBytes) {

        private static RuntimeMetadata capture() {
            java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
            long physicalMemoryBytes = 0L;
            if (osBean instanceof com.sun.management.OperatingSystemMXBean extended) {
                physicalMemoryBytes = Math.max(0L, extended.getTotalMemorySize());
            }
            String ratisVersion = RaftServer.class.getPackage()
                    .getImplementationVersion();
            return new RuntimeMetadata(
                    system("runLabel", ""),
                    system("buildRevision", "unknown"),
                    system("buildState", "unknown"),
                    "native-socketd-tcp+ratis-single-node+in-process",
                    System.getProperty("java.version", "unknown"),
                    System.getProperty("java.vm.name", "unknown"),
                    System.getProperty("java.vm.vendor", "unknown"),
                    SocketD.version(),
                    Solon.version(),
                    ratisVersion == null || ratisVersion.isBlank()
                            ? "unknown" : ratisVersion,
                    System.getProperty("os.name", "unknown"),
                    System.getProperty("os.version", "unknown"),
                    System.getProperty("os.arch", "unknown"),
                    Runtime.getRuntime().availableProcessors(),
                    physicalMemoryBytes,
                    Runtime.getRuntime().maxMemory());
        }

        private static String system(String name, String defaultValue) {
            String value = System.getProperty(PREFIX + name, defaultValue);
            return value == null ? defaultValue : value.trim();
        }

        private String toJson() {
            return String.format(Locale.ROOT,
                    "{\"runLabel\":\"%s\",\"buildRevision\":\"%s\","
                            + "\"buildState\":\"%s\",\"transportPath\":\"%s\","
                            + "\"javaVersion\":\"%s\",\"javaVmName\":\"%s\","
                            + "\"javaVmVendor\":\"%s\",\"socketdVersion\":\"%s\","
                            + "\"solonVersion\":\"%s\",\"ratisVersion\":\"%s\","
                            + "\"osName\":\"%s\",\"osVersion\":\"%s\","
                            + "\"osArch\":\"%s\",\"availableProcessors\":%d,"
                            + "\"physicalMemoryBytes\":%d,\"maxHeapBytes\":%d}",
                    json(runLabel), json(buildRevision), json(buildState),
                    json(transportPath), json(javaVersion), json(javaVmName),
                    json(javaVmVendor), json(socketdVersion), json(solonVersion),
                    json(ratisVersion), json(osName), json(osVersion), json(osArch),
                    availableProcessors, physicalMemoryBytes, maxHeapBytes);
        }
    }

    private record LoadProfile(
            int durationSeconds,
            int clients,
            int watchers,
            int fetchConcurrency,
            int fetchRatePerSecond,
            int tenantRequestRatePerSecond,
            int tenantRequestBurst,
            int publishRatePerMinute,
            int payloadBytes,
            int sampleIntervalSeconds,
            int growthWarmupSeconds,
            String reportPath) {

        private LoadProfile {
            range("durationSeconds", durationSeconds, 1, 259_200);
            range("clients", clients, 1, 2_000);
            range("watchers", watchers, 0, clients);
            range("fetchConcurrency", fetchConcurrency, 1, clients);
            range("fetchRatePerSecond", fetchRatePerSecond, 0, 1_000_000);
            range("tenantRequestRatePerSecond", tenantRequestRatePerSecond,
                    1, Integer.MAX_VALUE);
            range("tenantRequestBurst", tenantRequestBurst, 1, Integer.MAX_VALUE);
            if (fetchRatePerSecond > 0
                    && tenantRequestRatePerSecond <= fetchRatePerSecond) {
                throw new IllegalArgumentException(
                        PREFIX + "tenantRequestRatePerSecond must exceed "
                                + PREFIX + "fetchRatePerSecond in controlled-lossless mode");
            }
            if (tenantRequestBurst < clients + watchers) {
                throw new IllegalArgumentException(
                        PREFIX + "tenantRequestBurst must be at least clients + watchers "
                                + "so setup requests cannot consume the test quota");
            }
            range("publishRatePerMinute", publishRatePerMinute, 0, 6_000);
            range("payloadBytes", payloadBytes, 1, 1_048_576);
            range("sampleIntervalSeconds", sampleIntervalSeconds, 1, 3_600);
            range("growthWarmupSeconds", growthWarmupSeconds,
                    0, Math.max(0, durationSeconds - 1));
            reportPath = reportPath == null ? "" : reportPath.trim();
        }

        private static LoadProfile fromSystemProperties() {
            int duration = integer("durationSeconds", 60);
            int clients = integer("clients", 16);
            int watchers = integer("watchers", clients);
            int fetchRate = integer("fetchRatePerSecond", 0);
            int sampleInterval = integer("sampleIntervalSeconds", 10);
            int tenantRate = integer(
                    "tenantRequestRatePerSecond", defaultTenantRate(fetchRate));
            return new LoadProfile(
                    duration,
                    clients,
                    watchers,
                    integer("fetchConcurrency", Math.min(clients, 16)),
                    fetchRate,
                    tenantRate,
                    integer("tenantRequestBurst",
                            defaultTenantBurst(fetchRate, tenantRate, clients, watchers)),
                    integer("publishRatePerMinute", 6),
                    integer("payloadBytes", 1_024),
                    sampleInterval,
                    integer("growthWarmupSeconds",
                            defaultGrowthWarmup(duration, sampleInterval)),
                    System.getProperty(PREFIX + "reportPath", ""));
        }

        private static int integer(String name, int defaultValue) {
            return Integer.getInteger(PREFIX + name, defaultValue);
        }

        private static int defaultTenantRate(int fetchRatePerSecond) {
            if (fetchRatePerSecond == 0) {
                return 100_000_000;
            }
            long headroom = Math.max(100L,
                    Math.round(fetchRatePerSecond * 0.20D));
            return Math.toIntExact(fetchRatePerSecond + headroom);
        }

        private static int defaultTenantBurst(
                int fetchRatePerSecond,
                int tenantRequestRatePerSecond,
                int clients,
                int watchers) {
            if (fetchRatePerSecond == 0) {
                return 100_000_000;
            }
            long setupFloor = (long) clients + watchers;
            return Math.toIntExact(Math.max(
                    setupFloor,
                    Math.min(Integer.MAX_VALUE,
                            tenantRequestRatePerSecond * 2L)));
        }

        private static int defaultGrowthWarmup(
                int durationSeconds, int sampleIntervalSeconds) {
            if (durationSeconds <= 1) {
                return 0;
            }
            int candidate = Math.min(
                    300,
                    Math.max(30, durationSeconds / 20));
            return Math.min(
                    durationSeconds / 2,
                    Math.max(sampleIntervalSeconds, candidate));
        }

        private static void range(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(PREFIX + name + " must be between "
                        + minimum + " and " + maximum);
            }
        }

        private String toJson() {
            return String.format(Locale.ROOT,
                    "{\"mode\":\"%s\",\"durationSeconds\":%d,"
                            + "\"clients\":%d,\"watchers\":%d,"
                            + "\"fetchConcurrency\":%d,\"fetchRatePerSecond\":%d,"
                            + "\"tenantRequestRatePerSecond\":%d,"
                            + "\"tenantRequestBurst\":%d,"
                            + "\"publishRatePerMinute\":%d,\"payloadBytes\":%d,"
                            + "\"sampleIntervalSeconds\":%d,"
                            + "\"growthWarmupSeconds\":%d}",
                    mode(), durationSeconds, clients, watchers, fetchConcurrency,
                    fetchRatePerSecond, tenantRequestRatePerSecond,
                    tenantRequestBurst, publishRatePerMinute, payloadBytes,
                    sampleIntervalSeconds, growthWarmupSeconds);
        }

        private String mode() {
            return fetchRatePerSecond == 0
                    ? "capacity-saturation"
                    : "controlled-lossless";
        }
    }

    private static final class RatePacer {
        private final int ratePerSecond;
        private final long startedNanos;
        private final AtomicLong issued = new AtomicLong();

        private RatePacer(int ratePerSecond, long startedNanos) {
            this.ratePerSecond = ratePerSecond;
            this.startedNanos = startedNanos;
        }

        private void awaitNext(long workloadDeadlineNanos) throws InterruptedException {
            if (ratePerSecond == 0) {
                return;
            }
            long ticket = issued.getAndIncrement();
            long target = startedNanos
                    + Math.round(ticket * (1_000_000_000D / ratePerSecond));
            parkUntil(Math.min(target, workloadDeadlineNanos));
        }
    }

    private static final class LatencyHistogram {
        private static final long[] UPPER_BOUNDS_MS = {
                1L, 2L, 5L, 10L, 20L, 50L, 100L, 200L,
                500L, 1_000L, 2_000L, 5_000L, 10_000L, Long.MAX_VALUE
        };
        private final LongAdder[] buckets = new LongAdder[UPPER_BOUNDS_MS.length];
        private final LongAdder count = new LongAdder();
        private final LongAccumulator maxNanos = new LongAccumulator(Math::max, 0L);

        private LatencyHistogram() {
            for (int index = 0; index < buckets.length; index++) {
                buckets[index] = new LongAdder();
            }
        }

        private void record(long latencyNanos) {
            long normalized = Math.max(0L, latencyNanos);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(normalized);
            for (int index = 0; index < UPPER_BOUNDS_MS.length; index++) {
                if (latencyMs <= UPPER_BOUNDS_MS[index]) {
                    buckets[index].increment();
                    break;
                }
            }
            count.increment();
            maxNanos.accumulate(normalized);
        }

        private long count() {
            return count.sum();
        }

        private long maxNanos() {
            return maxNanos.get();
        }

        private long percentileUpperBoundMs(double percentile) {
            long total = count();
            if (total == 0L) {
                return 0L;
            }
            long threshold = Math.max(1L, (long) Math.ceil(total * percentile));
            long cumulative = 0L;
            for (int index = 0; index < buckets.length; index++) {
                cumulative += buckets[index].sum();
                if (cumulative >= threshold) {
                    return UPPER_BOUNDS_MS[index];
                }
            }
            return Long.MAX_VALUE;
        }
    }

    private record StorageSize(
            long walFiles,
            long walBytes,
            long snapshotFiles,
            long snapshotBytes) {

        private static StorageSize empty() {
            return new StorageSize(0L, 0L, 0L, 0L);
        }
    }

    private record BufferPoolUsage(
            long count,
            long memoryUsedBytes,
            long totalCapacityBytes) {

        private static BufferPoolUsage empty() {
            return new BufferPoolUsage(0L, 0L, 0L);
        }

        private BufferPoolUsage plus(BufferPoolMXBean pool) {
            return new BufferPoolUsage(
                    count + Math.max(0L, pool.getCount()),
                    memoryUsedBytes + Math.max(0L, pool.getMemoryUsed()),
                    totalCapacityBytes + Math.max(0L, pool.getTotalCapacity()));
        }
    }

    private record JvmResourceUsage(
            long heapUsedBytes,
            long nonHeapUsedBytes,
            long heapAfterLastGcBytes,
            int heapAfterLastGcPoolCount,
            long gcCollectionCount,
            long gcCollectionTimeMs,
            BufferPoolUsage directBuffers,
            BufferPoolUsage mappedBuffers) {

        private static JvmResourceUsage capture() {
            long heapAfterLastGcBytes = 0L;
            int heapAfterLastGcPoolCount = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != MemoryType.HEAP
                        || pool.getCollectionUsage() == null) {
                    continue;
                }
                heapAfterLastGcBytes += Math.max(
                        0L, pool.getCollectionUsage().getUsed());
                heapAfterLastGcPoolCount++;
            }

            long gcCollectionCount = 0L;
            long gcCollectionTimeMs = 0L;
            for (GarbageCollectorMXBean collector
                    : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCollectionCount += Math.max(0L, collector.getCollectionCount());
                gcCollectionTimeMs += Math.max(0L, collector.getCollectionTime());
            }

            BufferPoolUsage directBuffers = BufferPoolUsage.empty();
            BufferPoolUsage mappedBuffers = BufferPoolUsage.empty();
            for (BufferPoolMXBean pool
                    : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                String name = pool.getName().toLowerCase(Locale.ROOT);
                if ("direct".equals(name)) {
                    directBuffers = directBuffers.plus(pool);
                } else if (name.startsWith("mapped")) {
                    mappedBuffers = mappedBuffers.plus(pool);
                }
            }

            return new JvmResourceUsage(
                    ManagementFactory.getMemoryMXBean()
                            .getHeapMemoryUsage().getUsed(),
                    ManagementFactory.getMemoryMXBean()
                            .getNonHeapMemoryUsage().getUsed(),
                    heapAfterLastGcBytes,
                    heapAfterLastGcPoolCount,
                    gcCollectionCount,
                    gcCollectionTimeMs,
                    directBuffers,
                    mappedBuffers);
        }
    }

    private record ResourceSample(
            long elapsedMs,
            long heapUsedBytes,
            long nonHeapUsedBytes,
            long heapAfterLastGcBytes,
            int heapAfterLastGcPoolCount,
            long gcCollectionCount,
            long gcCollectionTimeMs,
            long directBufferCount,
            long directBufferMemoryUsedBytes,
            long directBufferTotalCapacityBytes,
            long mappedBufferCount,
            long mappedBufferMemoryUsedBytes,
            long mappedBufferTotalCapacityBytes,
            int liveThreads,
            int activeSessions,
            int activeSubscriptions,
            int pendingWatchAcknowledgements,
            int inFlightRequests,
            int workQueueDepth,
            int stateCallbackQueueDepth,
            long requestAcceptedTotal,
            long requestCompletedTotal,
            long tenantRequestRateLimitedTotal,
            int clientActiveSessions,
            int clientInFlightRequests,
            int clientRegisteredWatches,
            int clientActiveSubscribeStreams,
            long walBytes,
            long snapshotBytes) {

        private static ResourceSample capture(
                long elapsedMs,
                ControlPlaneGatewayRuntime runtime,
                List<SocketDTransport> transports,
                Path storageRoot) throws Exception {
            JvmResourceUsage jvm = JvmResourceUsage.capture();
            long walBytes = 0L;
            long snapshotBytes = 0L;
            if (Files.exists(storageRoot)) {
                try (var files = Files.walk(storageRoot)) {
                    for (Path path : files.filter(Files::isRegularFile).toList()) {
                        String name = path.getFileName().toString();
                        if (name.startsWith("log_")) {
                            walBytes += Files.size(path);
                        } else if (name.startsWith("snapshot.")
                                && !name.endsWith(".md5")) {
                            snapshotBytes += Files.size(path);
                        }
                    }
                }
            }
            int clientActiveSessions = 0;
            int clientInFlightRequests = 0;
            int clientRegisteredWatches = 0;
            int clientActiveSubscribeStreams = 0;
            for (SocketDTransport transport : transports) {
                ControlPlaneTransportMetricsSnapshot snapshot = transport.metricsSnapshot();
                clientActiveSessions += snapshot.activeSessions();
                clientInFlightRequests += snapshot.inFlightRequests();
                clientRegisteredWatches += snapshot.registeredWatches();
                clientActiveSubscribeStreams += snapshot.activeSubscribeStreams();
            }
            return new ResourceSample(
                    elapsedMs,
                    jvm.heapUsedBytes(),
                    jvm.nonHeapUsedBytes(),
                    jvm.heapAfterLastGcBytes(),
                    jvm.heapAfterLastGcPoolCount(),
                    jvm.gcCollectionCount(),
                    jvm.gcCollectionTimeMs(),
                    jvm.directBuffers().count(),
                    jvm.directBuffers().memoryUsedBytes(),
                    jvm.directBuffers().totalCapacityBytes(),
                    jvm.mappedBuffers().count(),
                    jvm.mappedBuffers().memoryUsedBytes(),
                    jvm.mappedBuffers().totalCapacityBytes(),
                    ManagementFactory.getThreadMXBean().getThreadCount(),
                    runtime.activeSessions(),
                    runtime.activeSubscriptions(),
                    runtime.pendingWatchAcknowledgements(),
                    runtime.inFlightRequests(),
                    runtime.workQueueDepth(),
                    runtime.stateCallbackQueueDepth(),
                    runtime.requestAcceptedTotal(),
                    runtime.requestCompletedTotal(),
                    runtime.tenantRequestRateLimitedTotal(),
                    clientActiveSessions,
                    clientInFlightRequests,
                    clientRegisteredWatches,
                    clientActiveSubscribeStreams,
                    walBytes,
                    snapshotBytes);
        }

        private String toJson() {
            return String.format(Locale.ROOT,
                    "{\"type\":\"sample\",\"elapsedMs\":%d,"
                            + "\"heapUsedBytes\":%d,\"nonHeapUsedBytes\":%d,"
                            + "\"heapAfterLastGcBytes\":%d,"
                            + "\"heapAfterLastGcPoolCount\":%d,"
                            + "\"gcCollectionCount\":%d,"
                            + "\"gcCollectionTimeMs\":%d,"
                            + "\"directBufferCount\":%d,"
                            + "\"directBufferMemoryUsedBytes\":%d,"
                            + "\"directBufferTotalCapacityBytes\":%d,"
                            + "\"mappedBufferCount\":%d,"
                            + "\"mappedBufferMemoryUsedBytes\":%d,"
                            + "\"mappedBufferTotalCapacityBytes\":%d,"
                            + "\"liveThreads\":%d,\"activeSessions\":%d,"
                            + "\"activeSubscriptions\":%d,"
                            + "\"pendingWatchAcknowledgements\":%d,"
                            + "\"inFlightRequests\":%d,\"workQueueDepth\":%d,"
                            + "\"stateCallbackQueueDepth\":%d,"
                            + "\"requestAcceptedTotal\":%d,"
                            + "\"requestCompletedTotal\":%d,"
                            + "\"tenantRequestRateLimitedTotal\":%d,"
                            + "\"clientActiveSessions\":%d,"
                            + "\"clientInFlightRequests\":%d,"
                            + "\"clientRegisteredWatches\":%d,"
                            + "\"clientActiveSubscribeStreams\":%d,"
                            + "\"walBytes\":%d,\"snapshotBytes\":%d}",
                    elapsedMs, heapUsedBytes, nonHeapUsedBytes,
                    heapAfterLastGcBytes, heapAfterLastGcPoolCount,
                    gcCollectionCount, gcCollectionTimeMs,
                    directBufferCount, directBufferMemoryUsedBytes,
                    directBufferTotalCapacityBytes,
                    mappedBufferCount, mappedBufferMemoryUsedBytes,
                    mappedBufferTotalCapacityBytes,
                    liveThreads,
                    activeSessions, activeSubscriptions,
                    pendingWatchAcknowledgements, inFlightRequests,
                    workQueueDepth, stateCallbackQueueDepth,
                    requestAcceptedTotal, requestCompletedTotal,
                    tenantRequestRateLimitedTotal,
                    clientActiveSessions, clientInFlightRequests,
                    clientRegisteredWatches, clientActiveSubscribeStreams,
                    walBytes, snapshotBytes);
        }

        private static ResourceSample synthetic(
                long elapsedMs,
                long heapUsedBytes,
                long heapAfterLastGcBytes,
                int liveThreads,
                long directBufferMemoryUsedBytes,
                long walBytes) {
            return new ResourceSample(
                    elapsedMs,
                    heapUsedBytes,
                    heapUsedBytes / 2L,
                    heapAfterLastGcBytes,
                    1,
                    elapsedMs / 1_000L,
                    elapsedMs / 10_000L,
                    1L,
                    directBufferMemoryUsedBytes,
                    directBufferMemoryUsedBytes,
                    0L,
                    0L,
                    0L,
                    liveThreads,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0L,
                    0L,
                    0L,
                    0,
                    0,
                    0,
                    0,
                    walBytes,
                    0L);
        }
    }

    private record ResourceGrowth(
            long baselineElapsedMs,
            long lastElapsedMs,
            long observationWindowMs,
            long heapUsedBytesDelta,
            long nonHeapUsedBytesDelta,
            long heapAfterLastGcBytesDelta,
            boolean heapAfterLastGcAvailable,
            int liveThreadsDelta,
            int inFlightRequestsDelta,
            int clientInFlightRequestsDelta,
            int clientRegisteredWatchesDelta,
            int clientActiveSubscribeStreamsDelta,
            long gcCollectionCountDelta,
            long gcCollectionTimeMsDelta,
            long directBufferCountDelta,
            long directBufferMemoryUsedBytesDelta,
            long directBufferTotalCapacityBytesDelta,
            long mappedBufferCountDelta,
            long mappedBufferMemoryUsedBytesDelta,
            long mappedBufferTotalCapacityBytesDelta,
            long walBytesDelta,
            long snapshotBytesDelta) {

        private static ResourceGrowth from(
                List<ResourceSample> samples, long warmupElapsedMs) {
            if (samples == null || samples.isEmpty()) {
                throw new IllegalArgumentException("Resource samples must not be empty");
            }
            ResourceSample baseline = samples.get(samples.size() - 1);
            for (ResourceSample sample : samples) {
                if (sample.elapsedMs() >= Math.max(0L, warmupElapsedMs)) {
                    baseline = sample;
                    break;
                }
            }
            ResourceSample last = samples.get(samples.size() - 1);
            boolean heapAfterLastGcAvailable =
                    baseline.heapAfterLastGcPoolCount() > 0
                            && last.heapAfterLastGcPoolCount() > 0;
            return new ResourceGrowth(
                    baseline.elapsedMs(),
                    last.elapsedMs(),
                    Math.max(0L, last.elapsedMs() - baseline.elapsedMs()),
                    last.heapUsedBytes() - baseline.heapUsedBytes(),
                    last.nonHeapUsedBytes() - baseline.nonHeapUsedBytes(),
                    heapAfterLastGcAvailable
                            ? last.heapAfterLastGcBytes()
                            - baseline.heapAfterLastGcBytes()
                            : 0L,
                    heapAfterLastGcAvailable,
                    last.liveThreads() - baseline.liveThreads(),
                    last.inFlightRequests() - baseline.inFlightRequests(),
                    last.clientInFlightRequests()
                            - baseline.clientInFlightRequests(),
                    last.clientRegisteredWatches()
                            - baseline.clientRegisteredWatches(),
                    last.clientActiveSubscribeStreams()
                            - baseline.clientActiveSubscribeStreams(),
                    last.gcCollectionCount() - baseline.gcCollectionCount(),
                    last.gcCollectionTimeMs() - baseline.gcCollectionTimeMs(),
                    last.directBufferCount() - baseline.directBufferCount(),
                    last.directBufferMemoryUsedBytes()
                            - baseline.directBufferMemoryUsedBytes(),
                    last.directBufferTotalCapacityBytes()
                            - baseline.directBufferTotalCapacityBytes(),
                    last.mappedBufferCount() - baseline.mappedBufferCount(),
                    last.mappedBufferMemoryUsedBytes()
                            - baseline.mappedBufferMemoryUsedBytes(),
                    last.mappedBufferTotalCapacityBytes()
                            - baseline.mappedBufferTotalCapacityBytes(),
                    last.walBytes() - baseline.walBytes(),
                    last.snapshotBytes() - baseline.snapshotBytes());
        }

        private String toJson() {
            return String.format(Locale.ROOT,
                    "{\"baselineElapsedMs\":%d,\"lastElapsedMs\":%d,"
                            + "\"observationWindowMs\":%d,"
                            + "\"heapUsedBytesDelta\":%d,"
                            + "\"nonHeapUsedBytesDelta\":%d,"
                            + "\"heapAfterLastGcBytesDelta\":%s,"
                            + "\"liveThreadsDelta\":%d,"
                            + "\"inFlightRequestsDelta\":%d,"
                            + "\"clientInFlightRequestsDelta\":%d,"
                            + "\"clientRegisteredWatchesDelta\":%d,"
                            + "\"clientActiveSubscribeStreamsDelta\":%d,"
                            + "\"gcCollectionCountDelta\":%d,"
                            + "\"gcCollectionTimeMsDelta\":%d,"
                            + "\"directBufferCountDelta\":%d,"
                            + "\"directBufferMemoryUsedBytesDelta\":%d,"
                            + "\"directBufferTotalCapacityBytesDelta\":%d,"
                            + "\"mappedBufferCountDelta\":%d,"
                            + "\"mappedBufferMemoryUsedBytesDelta\":%d,"
                            + "\"mappedBufferTotalCapacityBytesDelta\":%d,"
                            + "\"walBytesDelta\":%d,\"snapshotBytesDelta\":%d}",
                    baselineElapsedMs,
                    lastElapsedMs,
                    observationWindowMs,
                    heapUsedBytesDelta,
                    nonHeapUsedBytesDelta,
                    heapAfterLastGcAvailable
                            ? Long.toString(heapAfterLastGcBytesDelta) : "null",
                    liveThreadsDelta,
                    inFlightRequestsDelta,
                    clientInFlightRequestsDelta,
                    clientRegisteredWatchesDelta,
                    clientActiveSubscribeStreamsDelta,
                    gcCollectionCountDelta,
                    gcCollectionTimeMsDelta,
                    directBufferCountDelta,
                    directBufferMemoryUsedBytesDelta,
                    directBufferTotalCapacityBytesDelta,
                    mappedBufferCountDelta,
                    mappedBufferMemoryUsedBytesDelta,
                    mappedBufferTotalCapacityBytesDelta,
                    walBytesDelta,
                    snapshotBytesDelta);
        }
    }

    private record LoadReport(
            long startedEpochMs,
            long finishedEpochMs,
            LoadProfile profile,
            RuntimeMetadata runtimeMetadata,
            long fetchSucceeded,
            long fetchFailed,
            long revisionRegressed,
            long publishSucceeded,
            long publishFailed,
            long finalDecisionRevision,
            long finalEventRevision,
            LatencyHistogram fetchLatency,
            StorageSize storageBefore,
            StorageSize storageAfter,
            ResourceSample afterClose,
            List<ResourceSample> samples,
            Throwable fatal) {

        private void write(ReportSink reportSink) throws Exception {
            reportSink.summary(summaryJson());
            System.out.println("XUANTONG_SOAK_REPORT " + summaryJson());
        }

        private String summaryJson() {
            long peakHeapUsedBytes = samples.stream()
                    .mapToLong(ResourceSample::heapUsedBytes).max().orElse(0L);
            long peakNonHeapUsedBytes = samples.stream()
                    .mapToLong(ResourceSample::nonHeapUsedBytes).max().orElse(0L);
            int peakLiveThreads = samples.stream()
                    .mapToInt(ResourceSample::liveThreads).max().orElse(0);
            int peakSessions = samples.stream()
                    .mapToInt(ResourceSample::activeSessions).max().orElse(0);
            int peakSubscriptions = samples.stream()
                    .mapToInt(ResourceSample::activeSubscriptions).max().orElse(0);
            int peakInFlightRequests = samples.stream()
                    .mapToInt(ResourceSample::inFlightRequests).max().orElse(0);
            int peakWorkQueueDepth = samples.stream()
                    .mapToInt(ResourceSample::workQueueDepth).max().orElse(0);
            int peakStateCallbackQueueDepth = samples.stream()
                    .mapToInt(ResourceSample::stateCallbackQueueDepth).max().orElse(0);
            int peakClientActiveSessions = samples.stream()
                    .mapToInt(ResourceSample::clientActiveSessions).max().orElse(0);
            int peakClientInFlightRequests = samples.stream()
                    .mapToInt(ResourceSample::clientInFlightRequests).max().orElse(0);
            int peakClientRegisteredWatches = samples.stream()
                    .mapToInt(ResourceSample::clientRegisteredWatches).max().orElse(0);
            int peakClientActiveSubscribeStreams = samples.stream()
                    .mapToInt(ResourceSample::clientActiveSubscribeStreams).max().orElse(0);
            ResourceGrowth growth = ResourceGrowth.from(
                    samples,
                    TimeUnit.SECONDS.toMillis(profile.growthWarmupSeconds()));
            return String.format(Locale.ROOT,
                    "{\"type\":\"summary\",\"startedAt\":\"%s\","
                            + "\"finishedAt\":\"%s\",\"profile\":%s,"
                            + "\"runtime\":%s,"
                            + "\"fetchSucceeded\":%d,\"fetchFailed\":%d,"
                            + "\"revisionRegressed\":%d,"
                            + "\"publishSucceeded\":%d,\"publishFailed\":%d,"
                            + "\"finalDecisionRevision\":%d,"
                            + "\"finalEventRevision\":%d,"
                            + "\"fetchThroughputPerSecond\":%.2f,"
                            + "\"fetchP50UpperBoundMs\":%d,"
                            + "\"fetchP95UpperBoundMs\":%d,"
                            + "\"fetchP99UpperBoundMs\":%d,"
                            + "\"fetchMaxMs\":%.3f,\"sampleCount\":%d,"
                            + "\"peakHeapUsedBytes\":%d,"
                            + "\"peakNonHeapUsedBytes\":%d,"
                            + "\"peakLiveThreads\":%d,\"peakSessions\":%d,"
                            + "\"peakSubscriptions\":%d,"
                            + "\"peakInFlightRequests\":%d,"
                            + "\"peakWorkQueueDepth\":%d,"
                            + "\"peakStateCallbackQueueDepth\":%d,"
                            + "\"peakClientActiveSessions\":%d,"
                            + "\"peakClientInFlightRequests\":%d,"
                            + "\"peakClientRegisteredWatches\":%d,"
                            + "\"peakClientActiveSubscribeStreams\":%d,"
                            + "\"walBytesDelta\":%d,\"snapshotBytesDelta\":%d,"
                            + "\"afterCloseSessions\":%d,"
                            + "\"afterCloseSubscriptions\":%d,"
                            + "\"afterCloseInFlightRequests\":%d,"
                            + "\"afterCloseClientActiveSessions\":%d,"
                            + "\"afterCloseClientInFlightRequests\":%d,"
                            + "\"afterCloseClientRegisteredWatches\":%d,"
                            + "\"afterCloseClientActiveSubscribeStreams\":%d,"
                            + "\"tenantRequestRateLimitedTotal\":%d,"
                            + "\"growth\":%s,"
                            + "\"fatal\":%s}",
                    Instant.ofEpochMilli(startedEpochMs),
                    Instant.ofEpochMilli(finishedEpochMs),
                    profile.toJson(),
                    runtimeMetadata.toJson(),
                    fetchSucceeded, fetchFailed, revisionRegressed,
                    publishSucceeded, publishFailed,
                    finalDecisionRevision, finalEventRevision,
                    fetchSucceeded / (double) profile.durationSeconds(),
                    fetchLatency.percentileUpperBoundMs(0.50D),
                    fetchLatency.percentileUpperBoundMs(0.95D),
                    fetchLatency.percentileUpperBoundMs(0.99D),
                    fetchLatency.maxNanos() / 1_000_000D,
                    samples.size(),
                    peakHeapUsedBytes,
                    peakNonHeapUsedBytes,
                    peakLiveThreads,
                    peakSessions,
                    peakSubscriptions,
                    peakInFlightRequests,
                    peakWorkQueueDepth,
                    peakStateCallbackQueueDepth,
                    peakClientActiveSessions,
                    peakClientInFlightRequests,
                    peakClientRegisteredWatches,
                    peakClientActiveSubscribeStreams,
                    storageAfter.walBytes() - storageBefore.walBytes(),
                    storageAfter.snapshotBytes() - storageBefore.snapshotBytes(),
                    afterClose.activeSessions(),
                    afterClose.activeSubscriptions(),
                    afterClose.inFlightRequests(),
                    afterClose.clientActiveSessions(),
                    afterClose.clientInFlightRequests(),
                    afterClose.clientRegisteredWatches(),
                    afterClose.clientActiveSubscribeStreams(),
                    afterClose.tenantRequestRateLimitedTotal(),
                    growth.toJson(),
                    fatal == null ? "null" : "\"" + json(fatal.toString()) + "\"");
        }
    }

    private static final class ReportSink implements AutoCloseable {
        private final BufferedWriter writer;

        private ReportSink(BufferedWriter writer) {
            this.writer = writer;
        }

        private static ReportSink open(
                LoadProfile profile,
                RuntimeMetadata runtimeMetadata,
                long startedEpochMs) throws IOException {
            if (profile.reportPath().isBlank()) {
                return new ReportSink(null);
            }
            Path output = Path.of(profile.reportPath()).toAbsolutePath().normalize();
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BufferedWriter writer = Files.newBufferedWriter(
                    output,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            ReportSink sink = new ReportSink(writer);
            sink.append(String.format(Locale.ROOT,
                    "{\"type\":\"header\",\"startedAt\":\"%s\","
                            + "\"profile\":%s,\"runtime\":%s}",
                    Instant.ofEpochMilli(startedEpochMs),
                    profile.toJson(), runtimeMetadata.toJson()));
            return sink;
        }

        private void sample(ResourceSample sample) throws IOException {
            append(sample.toJson());
        }

        private void summary(String summary) throws IOException {
            append(summary);
        }

        private synchronized void append(String line) throws IOException {
            if (writer == null) {
                return;
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
