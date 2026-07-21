package cloud.xuantong.gateway.socketd;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.metrics.ControlPlaneTransportMetricsSnapshot;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.client.transport.impl.SocketDTransport;
import org.apache.ratis.server.RaftServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.socketd.SocketD;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.solon.Solon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts three independent child JVMs. Every child hosts the production native
 * Socket.D Gateway and one voter of the same Config Ratis Group. The test JVM is
 * only the client/load driver and test-only file-command coordinator.
 */
class ControlPlaneSplitProcessTopologyLoadTest {
    private static final String PREFIX = "xuantong.splitTopology.";
    private static final int NODE_COUNT = 3;
    private static final String DATA_ID = "split-topology.payload";

    @TempDir
    Path tempDirectory;

    @Test
    void splitReportRuntimeMetadataRecordsExactExecutionContext() {
        RuntimeMetadata metadata = RuntimeMetadata.capture();
        String json = metadata.toJson();

        assertTrue(json.contains("\"transportPath\":"
                + "\"native-socketd-tcp+ratis-three-voter"
                + "+three-child-jvms\""));
        assertTrue(json.contains("\"socketdVersion\":\"2.6.0\""));
        assertTrue(json.contains("\"solonVersion\":\"4.0.3\""));
        assertTrue(json.contains("\"ratisVersion\":\"3.2.2\""));
        assertTrue(json.contains("\"javaVersion\":\""));
        assertTrue(metadata.availableProcessors() > 0);
        assertTrue(metadata.physicalMemoryBytes() > 0L);
        assertTrue(metadata.driverMaxHeapBytes() > 0L);
    }

    @Test
    void splitJvmTopologySurvivesWholeGatewayAndVoterCrash()
            throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PREFIX + "enabled"),
                "Enable with -D" + PREFIX + "enabled=true");
        LoadProfile profile = LoadProfile.fromSystemProperties();
        RuntimeMetadata runtimeMetadata = RuntimeMetadata.capture();
        long startedEpochMs = System.currentTimeMillis();
        SplitReportJournal reportJournal = SplitReportJournal.open(
                reportPath(profile),
                reportHeaderJson(profile, runtimeMetadata, startedEpochMs));
        List<NodeProcess> nodes = new ArrayList<>();
        List<ClientHarness> clients = new ArrayList<>();
        List<WatchSubscription> subscriptions = new ArrayList<>();
        ExecutorService fetchWorkers = null;
        ExecutorService publisher = null;
        ScheduledExecutorService sampler = null;
        List<SplitResourceSample> samples = Collections.synchronizedList(
                new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> fatal = new AtomicReference<>();
        LongAdder fetchSucceeded = new LongAdder();
        LongAdder fetchFailed = new LongAdder();
        LongAdder revisionRegressed = new LongAdder();
        LongAdder publishSucceeded = new LongAdder();
        LongAdder publishFailed = new LongAdder();
        LatencyHistogram fetchLatency = new LatencyHistogram();
        AtomicLong latestDecisionRevision = new AtomicLong();
        AtomicLong latestEventRevision = new AtomicLong();
        AtomicLong latestAppliedIndex = new AtomicLong();
        List<NodeMetrics> preCrashMetrics = List.of();
        List<NodeMetrics> finalMetrics = List.of();
        List<ControlPlaneTransportMetricsSnapshot> clientAfterClose = List.of();
        boolean crashedVoterWasLeader = false;
        long allowedFetchFailures = 0L;
        long workloadStartedNanos = 0L;
        long workloadElapsedNanos = 0L;
        try {
            List<Integer> ports = freePorts(NODE_COUNT * 2);
            String peers = peers(ports.subList(0, NODE_COUNT));
            for (int index = 0; index < NODE_COUNT; index++) {
                nodes.add(startNode(
                        index,
                        ports.get(index),
                        ports.get(NODE_COUNT + index),
                        peers,
                        profile));
            }
            awaitAllReady(nodes, profile.startupTimeoutSeconds());
            List<NodeMetrics> startupMetrics = statusAll(nodes);
            assertEquals(1L, startupMetrics.stream()
                    .filter(NodeMetrics::stateLeader)
                    .count());
            assertTrue(startupMetrics.stream()
                    .allMatch(NodeMetrics::stateAlive));
            int crashNodeIndex = selectCrashNodeIndex(
                    startupMetrics, profile.crashTarget());
            int publisherNodeIndex = selectPublisherNodeIndex(crashNodeIndex);

            PublishResult initial = publish(
                    nodes.get(0), 1L, profile.payloadBytes());
            latestDecisionRevision.set(initial.decisionRevision());
            latestEventRevision.set(initial.eventRevision());
            latestAppliedIndex.set(initial.appliedIndex());
            awaitStateReplication(nodes, initial.appliedIndex());

            List<String> canonicalAddresses = nodes.stream()
                    .map(NodeProcess::gatewayAddress)
                    .toList();
            ControlPlaneOptions options = new ControlPlaneOptions(
                    "default",
                    "config-default",
                    "cluster-split-process-topology-test",
                    1L,
                    "tcp-default",
                    3_000L,
                    5_000L,
                    10_000L,
                    2_000L);
            for (int index = 0; index < profile.clients(); index++) {
                int[] gatewayOrder = rotatedOrder(index % NODE_COUNT);
                List<String> addresses = new ArrayList<>(NODE_COUNT);
                for (int gatewayIndex : gatewayOrder) {
                    addresses.add(canonicalAddresses.get(gatewayIndex));
                }
                CountingSocketDTransport transport =
                        new CountingSocketDTransport(
                                new ClientIdentity(
                                        "split-topology-client",
                                        "split-topology-client@" + index),
                                options,
                                NODE_COUNT);
                ClientHarness client = new ClientHarness(
                        index,
                        gatewayOrder,
                        transport,
                        new AtomicLong(),
                        new AtomicLong(latestEventRevision.get()));
                clients.add(client);
                transport.connect(addresses, "public", "DEFAULT_GROUP", "");
                ConfigSnapshot snapshot = transport.fetch(DATA_ID, 0L);
                assertNotNull(snapshot);
                assertEquals(1L, snapshot.getRevision());
                client.observedDecision().set(snapshot.getRevision());
            }
            awaitInitialDistribution(nodes, clients, 0);
            assertSingleFetchPerClient(nodes, clients);
            for (int index = 0; index < profile.watchers(); index++) {
                ClientHarness client = clients.get(index);
                subscriptions.add(client.transport().subscribe(
                        client.watchCursor().get(),
                        batch -> {
                            long covered = batch.coveredThroughRevision();
                            long previous = client.watchCursor()
                                    .getAndAccumulate(covered, Math::max);
                            if (covered < previous) {
                                revisionRegressed.increment();
                                fatal.compareAndSet(null,
                                        new IllegalStateException(
                                                "Watch cursor regressed for client "
                                                        + client.index()));
                                stop.set(true);
                            }
                            return covered;
                        }));
            }
            awaitInitialDistribution(nodes, clients, profile.watchers());

            long deadlineNanos = System.nanoTime()
                    + Duration.ofSeconds(profile.durationSeconds()).toNanos();
            workloadStartedNanos = System.nanoTime();
            recordResourceSample(
                    samples,
                    reportJournal,
                    captureResourceSample(
                            "pre-crash",
                            0L,
                            0L,
                            nodes,
                            clients));
            sampler = startResourceSampler(
                    "pre-crash",
                    workloadStartedNanos,
                    workloadStartedNanos,
                    nodes,
                    clients,
                    samples,
                    reportJournal,
                    fatal,
                    stop,
                    profile.sampleIntervalSeconds());
            RatePacer pacer = new RatePacer(
                    profile.fetchRatePerSecond(), workloadStartedNanos);
            AtomicInteger nextClient = new AtomicInteger();
            fetchWorkers = Executors.newFixedThreadPool(
                    profile.fetchConcurrency(), named("xuantong-split-fetch-"));
            List<Future<?>> fetchTasks = new ArrayList<>();
            for (int worker = 0; worker < profile.fetchConcurrency(); worker++) {
                fetchTasks.add(fetchWorkers.submit(() -> {
                    while (!stop.get() && System.nanoTime() < deadlineNanos) {
                        pacer.awaitNext(deadlineNanos);
                        if (stop.get() || System.nanoTime() >= deadlineNanos) {
                            break;
                        }
                        ClientHarness client = clients.get(Math.floorMod(
                                nextClient.getAndIncrement(), clients.size()));
                        long requestStarted = System.nanoTime();
                        try {
                            ConfigSnapshot snapshot = client.transport().fetch(
                                    DATA_ID, 0L);
                            fetchLatency.record(
                                    System.nanoTime() - requestStarted);
                            if (snapshot == null) {
                                fetchFailed.increment();
                                continue;
                            }
                            long previous = client.observedDecision()
                                    .getAndAccumulate(
                                            snapshot.getRevision(), Math::max);
                            if (snapshot.getRevision() < previous) {
                                revisionRegressed.increment();
                            } else {
                                fetchSucceeded.increment();
                            }
                            assertOneActiveSession(client);
                        } catch (Throwable e) {
                            fetchLatency.record(
                                    System.nanoTime() - requestStarted);
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
                        named("xuantong-split-publish-"));
                publisher.submit(() -> {
                    long intervalNanos = Math.max(
                            1L,
                            Math.round(60_000_000_000D
                                    / profile.publishRatePerMinute()));
                    long nextPublish = System.nanoTime() + intervalNanos;
                    while (!stop.get() && nextPublish < deadlineNanos) {
                        try {
                            parkUntilOrStopped(nextPublish, stop);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (stop.get() || System.nanoTime() >= deadlineNanos) {
                            return;
                        }
                        long targetRevision =
                                latestDecisionRevision.get() + 1L;
                        try {
                            PublishResult result = publish(
                                    nodes.get(publisherNodeIndex),
                                    targetRevision,
                                    profile.payloadBytes());
                            latestDecisionRevision.set(
                                    result.decisionRevision());
                            latestEventRevision.set(result.eventRevision());
                            latestAppliedIndex.set(result.appliedIndex());
                            publishSucceeded.increment();
                        } catch (Throwable e) {
                            publishFailed.increment();
                            fatal.compareAndSet(null, e);
                            stop.set(true);
                            return;
                        }
                        nextPublish += intervalNanos;
                    }
                });
            }

            long crashAt = workloadStartedNanos
                    + Duration.ofSeconds(
                            Math.max(1, profile.durationSeconds() / 2L))
                    .toNanos();
            parkUntil(Math.min(crashAt, deadlineNanos));
            shutdownSampler(sampler);
            sampler = null;
            preCrashMetrics = statusAll(nodes);
            crashedVoterWasLeader = preCrashMetrics.get(
                    crashNodeIndex).stateLeader();
            assertEquals(
                    "leader".equals(profile.crashTarget()),
                    crashedVoterWasLeader,
                    "The selected crash target changed Raft role before injection");
            allowedFetchFailures = crashedVoterWasLeader
                    ? profile.fetchConcurrency()
                    : 0L;
            forceKill(nodes.get(crashNodeIndex));
            awaitSequentialFailover(nodes, clients, crashNodeIndex);
            List<NodeProcess> survivingNodes = nodes.stream()
                    .filter(NodeProcess::alive)
                    .toList();
            long postCrashSamplingStartedNanos = System.nanoTime();
            recordResourceSample(
                    samples,
                    reportJournal,
                    captureResourceSample(
                            "post-crash",
                            TimeUnit.NANOSECONDS.toMillis(
                                    postCrashSamplingStartedNanos
                                            - workloadStartedNanos),
                            0L,
                            survivingNodes,
                            clients));
            sampler = startResourceSampler(
                    "post-crash",
                    workloadStartedNanos,
                    postCrashSamplingStartedNanos,
                    survivingNodes,
                    clients,
                    samples,
                    reportJournal,
                    fatal,
                    stop,
                    profile.sampleIntervalSeconds());

            long taskTimeoutSeconds = profile.durationSeconds() + 40L;
            for (Future<?> task : fetchTasks) {
                task.get(taskTimeoutSeconds, TimeUnit.SECONDS);
            }
            workloadElapsedNanos = System.nanoTime() - workloadStartedNanos;
            stop.set(true);
            shutdownPublisher(publisher);
            publisher = null;
            shutdownNow(fetchWorkers);
            fetchWorkers = null;

            long postCrashRevision = latestDecisionRevision.get() + 1L;
            PublishResult postCrash = publish(
                    nodes.get(publisherNodeIndex),
                    postCrashRevision,
                    profile.payloadBytes());
            latestDecisionRevision.set(postCrash.decisionRevision());
            latestEventRevision.set(postCrash.eventRevision());
            latestAppliedIndex.set(postCrash.appliedIndex());
            publishSucceeded.increment();
            awaitStateReplication(
                    nodes.stream().filter(NodeProcess::alive).toList(),
                    postCrash.appliedIndex());

            long requiredEventRevision = latestEventRevision.get();
            if (!subscriptions.isEmpty()) {
                awaitTrue(() -> clients.stream()
                                .limit(profile.watchers())
                                .allMatch(client -> client.watchCursor().get()
                                        >= requiredEventRevision),
                        Duration.ofSeconds(30));
            }
            awaitClientShellConvergence(
                    survivingNodes, clients, profile.watchers());
            awaitNodeIdle(survivingNodes);
            shutdownSampler(sampler);
            sampler = null;
            recordResourceSample(
                    samples,
                    reportJournal,
                    awaitIdleResourceSample(
                            workloadStartedNanos,
                            postCrashSamplingStartedNanos,
                            survivingNodes,
                            clients,
                            Duration.ofSeconds(10)));
            List<SplitResourceSample> reportSamples = copy(samples);
            List<SplitResourceSample> preCrashSamples = reportSamples.stream()
                    .filter(sample -> "pre-crash".equals(sample.phase()))
                    .toList();
            List<SplitResourceSample> growthSamples = reportSamples.stream()
                    .filter(sample -> !"pre-crash".equals(sample.phase()))
                    .toList();
            assertFalse(preCrashSamples.isEmpty(),
                    "Split topology must record pre-crash resource samples");
            assertTrue(growthSamples.size() >= 2,
                    "Split topology must record post-crash baseline and final samples");
            assertTrue(preCrashSamples.stream().allMatch(sample ->
                            sample.nodes().size() == NODE_COUNT
                                    && sample.clientMaxActiveSessions() <= 1
                                    && sample.clientsWithMultipleActiveSessions()
                                    == 0
                                    && sample.clientRegisteredWatches()
                                    == profile.watchers()),
                    "Pre-crash samples must keep the complete topology stable");
            assertTrue(growthSamples.stream().allMatch(sample ->
                            sample.nodes().size() == NODE_COUNT - 1
                                    && sample.clientMaxActiveSessions() <= 1
                                    && sample.clientsWithMultipleActiveSessions()
                                    == 0
                                    && sample.clientRegisteredWatches()
                                    == profile.watchers()),
                    () -> "Post-crash samples must keep two surviving nodes, "
                            + "logical Watches, and at most one active Session "
                            + "per client; samples=" + growthSamples.stream()
                            .map(SplitResourceSample::toJson)
                            .toList());
            SplitResourceSample lastResourceSample =
                    reportSamples.get(reportSamples.size() - 1);
            assertEquals(profile.clients(),
                    lastResourceSample.clientActiveSessions());
            assertEquals(0,
                    lastResourceSample.clientsWithoutActiveSession());
            assertEquals(profile.watchers(),
                    lastResourceSample.clientActiveSubscribeStreams());
            assertEquals(0, lastResourceSample.inFlightRequests());
            assertEquals(0, lastResourceSample.clientInFlightRequests());
            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
            awaitTrue(() -> statusAll(nodes.stream()
                            .filter(NodeProcess::alive)
                            .toList()).stream()
                            .mapToInt(NodeMetrics::activeSubscriptions)
                            .sum() == 0,
                    Duration.ofSeconds(20));
            assertSingleFetchPerClient(
                    nodes.stream().filter(NodeProcess::alive).toList(),
                    clients);

            for (ClientHarness client : clients) {
                client.transport().close();
            }
            awaitAliveNodesClosed(nodes);
            clientAfterClose = clients.stream()
                    .map(client -> client.transport().metricsSnapshot())
                    .toList();
            assertTrue(clientAfterClose.stream().allMatch(
                    metrics -> metrics.activeSessions() == 0
                            && metrics.inFlightRequests() == 0
                            && metrics.registeredWatches() == 0
                            && metrics.activeSubscribeStreams() == 0
                            && metrics.closed()));
            finalMetrics = statusAll(
                    nodes.stream().filter(NodeProcess::alive).toList());
            assertEquals(1L, finalMetrics.stream()
                    .filter(NodeMetrics::stateLeader)
                    .count());
            assertTrue(finalMetrics.stream().allMatch(
                    metrics -> metrics.stateAlive()
                            && metrics.stateLastCommittedIndex()
                                    >= latestAppliedIndex.get()
                            && metrics.stateLastAppliedIndex()
                                    >= latestAppliedIndex.get()
                            && metrics.activeSessions() == 0
                            && metrics.activeSubscriptions() == 0
                            && metrics.pendingWatchAcknowledgements() == 0
                            && metrics.inFlightRequests() == 0
                            && metrics.requestAcceptedTotal()
                                    == metrics.requestCompletedTotal()
                            && metrics.sessionOpenedTotal()
                                    == metrics.sessionClosedTotal()
                            && metrics.subscriptionOpenedTotal()
                                    == metrics.subscriptionClosedTotal()
                            && metrics.tenantRateLimitedTotal() == 0L
                            && metrics.overloadedRejectedTotal() == 0L
                            && metrics.stateCallbackRejectedTotal() == 0L));

            gracefulShutdown(
                    nodes.stream().filter(NodeProcess::alive).toList());
            List<ProcessLogMetrics> logMetrics = nodes.stream()
                    .map(NodeProcess::logMetrics)
                    .toList();
            assertTrue(logMetrics.stream()
                    .mapToLong(ProcessLogMetrics::warningLines)
                    .sum() < 200L,
                    "Expected crash/shutdown warnings must remain bounded");
            assertTrue(logMetrics.stream()
                    .mapToLong(ProcessLogMetrics::errorLines)
                    .sum() < 50L,
                    "Expected Ratis leader-loss errors must remain bounded");
            writeSummary(
                    profile,
                    runtimeMetadata,
                    nodes,
                    startedEpochMs,
                    workloadElapsedNanos,
                    fetchSucceeded.sum(),
                    fetchFailed.sum(),
                    revisionRegressed.sum(),
                    publishSucceeded.sum(),
                    publishFailed.sum(),
                    latestDecisionRevision.get(),
                    latestEventRevision.get(),
                    crashNodeIndex,
                    crashedVoterWasLeader,
                    allowedFetchFailures,
                    fetchLatency,
                    preCrashMetrics,
                    finalMetrics,
                    clientAfterClose,
                    logMetrics,
                    reportSamples,
                    growthSamples,
                    reportJournal);
            reportJournal.close();

            assertNull(fatal.get(),
                    "Split-process load worker failed: " + fatal.get());
            assertTrue(fetchSucceeded.sum() > 0L);
            assertTrue(fetchFailed.sum() <= allowedFetchFailures,
                    "Fetch failures exceeded the hard-crash budget: actual="
                            + fetchFailed.sum() + ", allowed="
                            + allowedFetchFailures + ", crashedLeader="
                            + crashedVoterWasLeader);
            assertEquals(0L, revisionRegressed.sum());
            assertEquals(0L, publishFailed.sum());
            assertFalse(nodes.get(crashNodeIndex).process().isAlive());
        } finally {
            stop.set(true);
            shutdownNow(sampler);
            shutdownNow(publisher);
            shutdownNow(fetchWorkers);
            for (WatchSubscription subscription : subscriptions) {
                subscription.close();
            }
            for (ClientHarness client : clients) {
                client.transport().close();
            }
            for (NodeProcess node : nodes) {
                terminateProcessTree(node.process());
            }
            closeQuietly(reportJournal);
        }
    }

    private NodeProcess startNode(
            int index,
            int statePort,
            int gatewayPort,
            String peers,
            LoadProfile profile) throws IOException {
        Path nodeRoot = Files.createDirectories(
                tempDirectory.resolve("node-" + (index + 1)));
        Path controlDirectory = Files.createDirectories(
                nodeRoot.resolve("control"));
        Path stateDirectory = nodeRoot.resolve("state");
        Path log = nodeRoot.resolve("node.log");
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java")
                        .toString(),
                "-Xms64m",
                "-Xmx" + profile.childMaxHeapMb() + "m",
                "-cp",
                System.getProperty("java.class.path"),
                ControlPlaneSplitProcessNodeMain.class.getName(),
                "state-" + (index + 1),
                peers,
                stateDirectory.toString(),
                Integer.toString(gatewayPort),
                controlDirectory.toString(),
                Integer.toString(profile.clients()),
                Integer.toString(profile.fetchConcurrency()),
                Integer.toString(profile.startupTimeoutSeconds()));
        Process process = new ProcessBuilder(command)
                .directory(repositoryRoot().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        return new NodeProcess(
                index,
                statePort,
                gatewayPort,
                stateDirectory,
                controlDirectory,
                log,
                process);
    }

    private void awaitAllReady(
            List<NodeProcess> nodes, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            boolean ready = true;
            for (NodeProcess node : nodes) {
                if (!node.process().isAlive()) {
                    throw new AssertionError(
                            "Split node exited before ready: " + node.describeLog());
                }
                if (!Files.isRegularFile(node.readyPath())) {
                    ready = false;
                }
            }
            if (ready) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Split nodes did not become ready: "
                + nodes.stream().map(NodeProcess::describeLog).toList());
    }

    private PublishResult publish(
            NodeProcess node, long revision, int payloadBytes)
            throws Exception {
        Properties request = new Properties();
        request.setProperty("action", "publish");
        request.setProperty("revision", Long.toString(revision));
        request.setProperty("payloadBytes", Integer.toString(payloadBytes));
        Properties response = request(
                node, request, Duration.ofSeconds(30));
        String status = response.getProperty("status");
        // A lost first reply can make submitEventually retry the same
        // operationId; the State Machine then returns its committed result as
        // UNCHANGED. Exact revision matching keeps this distinct from a
        // different or rejected publish.
        assertTrue("APPLIED".equals(status) || "UNCHANGED".equals(status),
                () -> "Publish failed via " + node.nodeId() + ": " + response);
        long decisionRevision = Long.parseLong(
                response.getProperty("decisionRevision"));
        long eventRevision = Long.parseLong(
                response.getProperty("eventRevision"));
        assertEquals(revision, decisionRevision,
                "A committed publish must resolve the requested decision revision");
        assertEquals(revision, eventRevision,
                "A committed publish must resolve the requested event revision");
        return new PublishResult(
                decisionRevision,
                eventRevision,
                Long.parseLong(response.getProperty("appliedIndex")));
    }

    private NodeMetrics status(NodeProcess node) throws Exception {
        Properties request = new Properties();
        request.setProperty("action", "status");
        Properties response = request(
                node, request, Duration.ofSeconds(10));
        assertEquals("ok", response.getProperty("status"));
        return NodeMetrics.from(node.index(), false, response);
    }

    private List<NodeMetrics> statusAll(List<NodeProcess> nodes)
            throws Exception {
        List<NodeMetrics> result = new ArrayList<>();
        for (NodeProcess node : nodes) {
            result.add(status(node));
        }
        return List.copyOf(result);
    }

    private Properties request(
            NodeProcess node,
            Properties request,
            Duration timeout) throws Exception {
        if (!node.process().isAlive()) {
            throw new IllegalStateException(
                    "Split node is not alive: " + node.describeLog());
        }
        String requestId = String.format(
                "%020d-%s", System.nanoTime(), UUID.randomUUID());
        Path requestPath = node.controlDirectory().resolve(
                requestId + ".request");
        Path responsePath = node.controlDirectory().resolve(
                requestId + ".response");
        writeProperties(requestPath, request);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(responsePath)) {
                Properties response = readProperties(responsePath);
                Files.deleteIfExists(responsePath);
                if ("error".equals(response.getProperty("status"))) {
                    throw new IllegalStateException(
                            "Split node command failed: "
                                    + response.getProperty("errorType") + ": "
                                    + response.getProperty("errorMessage"));
                }
                return response;
            }
            if (!node.process().isAlive()) {
                throw new IllegalStateException(
                        "Split node exited during command: "
                                + node.describeLog());
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException(
                "Split node command timed out: " + node.describeLog());
    }

    private void awaitInitialDistribution(
            List<NodeProcess> nodes,
            List<ClientHarness> clients,
            int watchers) throws Exception {
        awaitTrue(() -> {
            List<NodeMetrics> metrics = statusAll(nodes);
            return metrics.stream().mapToInt(NodeMetrics::activeSessions).sum()
                    == clients.size()
                    && metrics.stream()
                    .mapToInt(NodeMetrics::activeSubscriptions).sum()
                    == watchers;
        }, Duration.ofSeconds(20));
        List<NodeMetrics> metrics = statusAll(nodes);
        for (ClientHarness client : clients) {
            assertEquals(0, client.transport().activeGatewayIndex());
            assertEquals(1, client.transport().openAttempts(0));
            assertEquals(0, client.transport().openAttempts(1));
            assertEquals(0, client.transport().openAttempts(2));
            assertOneActiveSession(client);
        }
        for (int nodeIndex = 0; nodeIndex < NODE_COUNT; nodeIndex++) {
            int expectedSessions = 0;
            int expectedSubscriptions = 0;
            for (ClientHarness client : clients) {
                if (client.gatewayOrder()[0] == nodeIndex) {
                    expectedSessions++;
                    if (client.index() < watchers) {
                        expectedSubscriptions++;
                    }
                }
            }
            assertEquals(expectedSessions,
                    metrics.get(nodeIndex).activeSessions());
            assertEquals(expectedSubscriptions,
                    metrics.get(nodeIndex).activeSubscriptions());
        }
    }

    private void awaitSequentialFailover(
            List<NodeProcess> nodes,
            List<ClientHarness> clients,
            int crashNodeIndex) throws Exception {
        for (ClientHarness client : clients) {
            if (client.gatewayOrder()[0] == crashNodeIndex
                    && activeGlobalGatewayIndex(client) == crashNodeIndex) {
                ConfigSnapshot snapshot = client.transport().fetch(
                        DATA_ID, client.observedDecision().get());
                assertNotNull(snapshot);
                client.observedDecision().accumulateAndGet(
                        snapshot.getRevision(), Math::max);
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (splitFailoverReady(nodes, clients)) {
                break;
            }
            Thread.sleep(50L);
        }
        assertTrue(splitFailoverReady(nodes, clients), () -> {
            List<String> clientState = clients.stream()
                    .map(client -> "client=" + client.index()
                            + ", order="
                            + java.util.Arrays.toString(client.gatewayOrder())
                            + ", activeLocal="
                            + client.transport().activeGatewayIndex()
                            + ", attempts=["
                            + client.transport().openAttempts(0) + ","
                            + client.transport().openAttempts(1) + ","
                            + client.transport().openAttempts(2) + "]"
                            + ", metrics="
                            + client.transport().metricsSnapshot())
                    .toList();
            List<String> nodeState = nodes.stream()
                    .map(node -> node.nodeId()
                            + "=" + node.gatewayAddress()
                            + ", alive=" + node.alive())
                    .toList();
            return "Split failover did not converge; clients=" + clientState
                    + ", nodes=" + nodeState;
        });
        for (ClientHarness client : clients) {
            if (client.gatewayOrder()[0] == crashNodeIndex) {
                assertEquals(1, client.transport().openAttempts(0));
                assertTrue(client.transport().openAttempts(1)
                                + client.transport().openAttempts(2) >= 1,
                        "A client on the crashed Gateway must try a standby address");
            }
            int activeGlobalIndex = activeGlobalGatewayIndex(client);
            assertTrue(activeGlobalIndex >= 0
                            && activeGlobalIndex < nodes.size()
                            && nodes.get(activeGlobalIndex).alive(),
                    "Every client must converge to one live Gateway");
            assertOneActiveSession(client);
        }
    }

    private boolean splitFailoverReady(
            List<NodeProcess> nodes,
            List<ClientHarness> clients) throws Exception {
        for (ClientHarness client : clients) {
            int activeGlobalIndex = activeGlobalGatewayIndex(client);
            if (activeGlobalIndex < 0
                    || activeGlobalIndex >= nodes.size()
                    || !nodes.get(activeGlobalIndex).alive()) {
                return false;
            }
            if (client.transport().metricsSnapshot().activeSessions() != 1) {
                return false;
            }
        }
        return statusAll(nodes.stream()
                .filter(NodeProcess::alive)
                .toList()).stream()
                .mapToInt(NodeMetrics::activeSessions)
                .sum() == clients.size();
    }

    private static int activeGlobalGatewayIndex(ClientHarness client) {
        int localIndex = client.transport().activeGatewayIndex();
        return localIndex < 0 ? -1 : client.gatewayOrder()[localIndex];
    }

    private static int selectCrashNodeIndex(
            List<NodeMetrics> metrics, String crashTarget) {
        boolean leader = "leader".equals(crashTarget);
        return metrics.stream()
                .filter(value -> value.stateLeader() == leader)
                .mapToInt(NodeMetrics::nodeIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No " + crashTarget + " voter is available to crash"));
    }

    private static int selectPublisherNodeIndex(int crashNodeIndex) {
        for (int index = 0; index < NODE_COUNT; index++) {
            if (index != crashNodeIndex) {
                return index;
            }
        }
        throw new IllegalStateException("No surviving publisher node is available");
    }

    private void assertSingleFetchPerClient(
            List<NodeProcess> aliveNodes,
            List<ClientHarness> clients) throws Exception {
        for (ClientHarness client : clients) {
            client.transport().probe();
            assertOneActiveSession(client);
        }
        awaitNodeIdle(aliveNodes);
        List<NodeMetrics> before = statusAll(aliveNodes);
        int[] expectedByNode = new int[NODE_COUNT];
        for (ClientHarness client : clients) {
            int localIndex = client.transport().activeGatewayIndex();
            assertTrue(localIndex >= 0);
            int globalIndex = client.gatewayOrder()[localIndex];
            expectedByNode[globalIndex]++;
            ConfigSnapshot snapshot = client.transport().fetch(
                    DATA_ID, client.observedDecision().get());
            assertNotNull(snapshot);
            long previous = client.observedDecision().getAndAccumulate(
                    snapshot.getRevision(), Math::max);
            assertTrue(snapshot.getRevision() >= previous);
            assertOneActiveSession(client);
        }
        awaitNodeIdle(aliveNodes);
        List<NodeMetrics> after = statusAll(aliveNodes);
        long totalDelta = 0L;
        for (int index = 0; index < aliveNodes.size(); index++) {
            NodeProcess node = aliveNodes.get(index);
            long delta = after.get(index).requestAcceptedTotal()
                    - before.get(index).requestAcceptedTotal();
            assertEquals(expectedByNode[node.index()], delta,
                    "One logical Fetch must reach exactly one child Gateway");
            totalDelta += delta;
        }
        assertEquals(clients.size(), totalDelta,
                "Fetch must not fan out across child JVM Gateways");
    }

    private void awaitStateReplication(
            List<NodeProcess> nodes,
            long requiredAppliedIndex) throws Exception {
        awaitTrue(() -> {
            List<NodeMetrics> metrics = statusAll(nodes);
            long leaders = metrics.stream()
                    .filter(NodeMetrics::stateLeader)
                    .count();
            return leaders == 1L && metrics.stream().allMatch(
                    value -> value.stateAlive()
                            && value.stateLastCommittedIndex()
                                    >= requiredAppliedIndex
                            && value.stateLastAppliedIndex()
                                    >= requiredAppliedIndex);
        }, Duration.ofSeconds(30));
    }

    private void awaitNodeIdle(List<NodeProcess> nodes) throws Exception {
        awaitTrue(() -> statusAll(nodes).stream().allMatch(
                        metrics -> metrics.inFlightRequests() == 0
                                && metrics.requestAcceptedTotal()
                                == metrics.requestCompletedTotal()),
                Duration.ofSeconds(10));
    }

    private void awaitClientShellConvergence(
            List<NodeProcess> nodes,
            List<ClientHarness> clients,
            int watchers) throws Exception {
        awaitTrue(() -> {
            for (ClientHarness client : clients) {
                ControlPlaneTransportMetricsSnapshot snapshot =
                        client.transport().metricsSnapshot();
                int expectedWatches = client.index() < watchers ? 1 : 0;
                if (snapshot.activeSessions() != 1
                        || snapshot.registeredWatches() != expectedWatches
                        || snapshot.activeSubscribeStreams()
                        != expectedWatches) {
                    return false;
                }
            }
            List<NodeMetrics> metrics = statusAll(nodes);
            return metrics.stream().mapToInt(NodeMetrics::activeSessions).sum()
                    == clients.size()
                    && metrics.stream()
                    .mapToInt(NodeMetrics::activeSubscriptions).sum()
                    == watchers;
        }, Duration.ofSeconds(30));
    }

    private void awaitAliveNodesClosed(List<NodeProcess> nodes)
            throws Exception {
        List<NodeProcess> alive = nodes.stream()
                .filter(NodeProcess::alive)
                .toList();
        awaitTrue(() -> statusAll(alive).stream().allMatch(
                        metrics -> metrics.activeSessions() == 0
                                && metrics.activeSubscriptions() == 0
                                && metrics.pendingWatchAcknowledgements() == 0
                                && metrics.inFlightRequests() == 0
                                && metrics.requestAcceptedTotal()
                                == metrics.requestCompletedTotal()),
                Duration.ofSeconds(20));
    }

    private void gracefulShutdown(List<NodeProcess> nodes) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(
                nodes.size(), named("xuantong-split-shutdown-"));
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (NodeProcess node : nodes) {
                tasks.add(executor.submit(() -> {
                    Properties command = new Properties();
                    command.setProperty("action", "shutdown");
                    request(node, command, Duration.ofSeconds(10));
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                task.get(15, TimeUnit.SECONDS);
            }
        } finally {
            shutdownNow(executor);
        }
        for (NodeProcess node : nodes) {
            assertTrue(node.process().waitFor(15, TimeUnit.SECONDS),
                    () -> "Split node did not exit: " + node.describeLog());
        }
    }

    private void forceKill(NodeProcess node) throws Exception {
        if (!node.process().isAlive()) {
            return;
        }
        node.process().destroyForcibly();
        assertTrue(node.process().waitFor(10, TimeUnit.SECONDS),
                "Force-killed split node did not exit");
    }

    private static void terminateProcessTree(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        List<ProcessHandle> handles = new ArrayList<>();
        try {
            handles.addAll(process.toHandle().descendants().toList());
        } catch (RuntimeException ignored) {
            // Best effort when process enumeration is restricted.
        }
        Collections.reverse(handles);
        handles.add(process.toHandle());
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroy();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (handles.stream().anyMatch(ProcessHandle::isAlive)
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertOneActiveSession(ClientHarness client) {
        assertEquals(1,
                client.transport().metricsSnapshot().activeSessions(),
                "Each client must keep exactly one child Gateway Session");
    }

    private SplitResourceSample captureResourceSample(
            String phase,
            long elapsedMs,
            long phaseElapsedMs,
            List<NodeProcess> nodes,
            List<ClientHarness> clients) throws Exception {
        List<NodeMetrics> metrics = statusAll(nodes);
        long walBytes = 0L;
        long snapshotBytes = 0L;
        for (NodeProcess node : nodes) {
            StorageSize storage = storageSize(node.stateDirectory());
            walBytes += storage.walBytes();
            snapshotBytes += storage.snapshotBytes();
        }
        int clientActiveSessions = 0;
        int clientMinActiveSessions = Integer.MAX_VALUE;
        int clientMaxActiveSessions = 0;
        int clientsWithoutActiveSession = 0;
        int clientsWithMultipleActiveSessions = 0;
        int clientInFlightRequests = 0;
        int clientRegisteredWatches = 0;
        int clientActiveSubscribeStreams = 0;
        for (ClientHarness client : clients) {
            ControlPlaneTransportMetricsSnapshot snapshot =
                    client.transport().metricsSnapshot();
            clientActiveSessions += snapshot.activeSessions();
            clientMinActiveSessions = Math.min(
                    clientMinActiveSessions, snapshot.activeSessions());
            clientMaxActiveSessions = Math.max(
                    clientMaxActiveSessions, snapshot.activeSessions());
            if (snapshot.activeSessions() == 0) {
                clientsWithoutActiveSession++;
            } else if (snapshot.activeSessions() > 1) {
                clientsWithMultipleActiveSessions++;
            }
            clientInFlightRequests += snapshot.inFlightRequests();
            clientRegisteredWatches += snapshot.registeredWatches();
            clientActiveSubscribeStreams += snapshot.activeSubscribeStreams();
        }
        return SplitResourceSample.from(
                phase,
                elapsedMs,
                phaseElapsedMs,
                metrics,
                clientActiveSessions,
                clients.isEmpty() ? 0 : clientMinActiveSessions,
                clientMaxActiveSessions,
                clientsWithoutActiveSession,
                clientsWithMultipleActiveSessions,
                clientInFlightRequests,
                clientRegisteredWatches,
                clientActiveSubscribeStreams,
                walBytes,
                snapshotBytes);
    }

    private ScheduledExecutorService startResourceSampler(
            String phase,
            long workloadStartedNanos,
            long phaseStartedNanos,
            List<NodeProcess> nodes,
            List<ClientHarness> clients,
            List<SplitResourceSample> samples,
            SplitReportJournal reportJournal,
            AtomicReference<Throwable> fatal,
            AtomicBoolean stop,
            int sampleIntervalSeconds) {
        ScheduledExecutorService result =
                Executors.newSingleThreadScheduledExecutor(
                        named("xuantong-split-sample-"));
        result.scheduleAtFixedRate(() -> {
            try {
                long now = System.nanoTime();
                recordResourceSample(
                        samples,
                        reportJournal,
                        captureResourceSample(
                                phase,
                                TimeUnit.NANOSECONDS.toMillis(
                                        now - workloadStartedNanos),
                                TimeUnit.NANOSECONDS.toMillis(
                                        now - phaseStartedNanos),
                                nodes,
                                clients));
            } catch (Throwable e) {
                fatal.compareAndSet(null, e);
                stop.set(true);
            }
        }, sampleIntervalSeconds, sampleIntervalSeconds, TimeUnit.SECONDS);
        return result;
    }

    private static void recordResourceSample(
            List<SplitResourceSample> samples,
            SplitReportJournal reportJournal,
            SplitResourceSample sample) throws IOException {
        samples.add(sample);
        reportJournal.writeSample(sample);
    }

    private SplitResourceSample awaitIdleResourceSample(
            long workloadStartedNanos,
            long phaseStartedNanos,
            List<NodeProcess> nodes,
            List<ClientHarness> clients,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        SplitResourceSample latest;
        do {
            long now = System.nanoTime();
            latest = captureResourceSample(
                    "final",
                    TimeUnit.NANOSECONDS.toMillis(
                            now - workloadStartedNanos),
                    TimeUnit.NANOSECONDS.toMillis(
                            now - phaseStartedNanos),
                    nodes,
                    clients);
            if (latest.inFlightRequests() == 0
                    && latest.clientInFlightRequests() == 0) {
                return latest;
            }
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(
                "Split topology did not reach an idle final sample: "
                        + latest.toJson());
    }

    private void writeSummary(
            LoadProfile profile,
            RuntimeMetadata runtimeMetadata,
            List<NodeProcess> nodes,
            long startedEpochMs,
            long workloadElapsedNanos,
            long fetchSucceeded,
            long fetchFailed,
            long revisionRegressed,
            long publishSucceeded,
            long publishFailed,
            long latestDecisionRevision,
            long latestEventRevision,
            int crashNodeIndex,
            boolean crashedVoterWasLeader,
            long allowedFetchFailures,
            LatencyHistogram latency,
            List<NodeMetrics> preCrashMetrics,
            List<NodeMetrics> finalMetrics,
            List<ControlPlaneTransportMetricsSnapshot> clientAfterClose,
            List<ProcessLogMetrics> logs,
            List<SplitResourceSample> samples,
            List<SplitResourceSample> growthSamples,
            SplitReportJournal reportJournal) throws Exception {
        double fetchPerSecond = workloadElapsedNanos <= 0L
                ? 0D
                : fetchSucceeded * 1_000_000_000D / workloadElapsedNanos;
        SplitResourceGrowth growth = SplitResourceGrowth.from(
                growthSamples,
                TimeUnit.SECONDS.toMillis(profile.growthWarmupSeconds()));
        long peakHeapUsedBytes = samples.stream()
                .mapToLong(SplitResourceSample::heapUsedBytes).max().orElse(0L);
        long peakNonHeapUsedBytes = samples.stream()
                .mapToLong(SplitResourceSample::nonHeapUsedBytes).max().orElse(0L);
        long peakDirectBufferMemoryUsedBytes = samples.stream()
                .mapToLong(SplitResourceSample::directBufferMemoryUsedBytes)
                .max().orElse(0L);
        int peakLiveThreads = samples.stream()
                .mapToInt(SplitResourceSample::liveThreads).max().orElse(0);
        StringBuilder json = new StringBuilder(8_192);
        json.append('{')
                .append("\"type\":\"summary\",")
                .append("\"schemaVersion\":1,")
                .append("\"runLabel\":\"").append(json(profile.runLabel()))
                .append("\",")
                .append("\"buildRevision\":\"")
                .append(json(profile.buildRevision())).append("\",")
                .append("\"buildState\":\"")
                .append(json(profile.buildState())).append("\",")
                .append("\"startedEpochMs\":").append(startedEpochMs)
                .append(',')
                .append("\"runtime\":")
                .append(runtimeMetadata.toJson()).append(',')
                .append("\"durationMs\":")
                .append(TimeUnit.NANOSECONDS.toMillis(workloadElapsedNanos))
                .append(',')
                .append("\"topology\":{")
                .append("\"transport\":\"native-socketd-tcp\",")
                .append("\"processModel\":\"three-server-child-jvms-plus-driver\",")
                .append("\"gatewayCount\":3,")
                .append("\"stateVoterCount\":3,")
                .append("\"crashedGatewayIndex\":")
                .append(crashNodeIndex).append(',')
                .append("\"crashedVoterIndex\":")
                .append(crashNodeIndex).append(',')
                .append("\"crashedVoterWasLeader\":")
                .append(crashedVoterWasLeader).append(',')
                .append("\"singleActiveSessionPerClient\":true,")
                .append("\"sequentialFailover\":true},")
                .append("\"profile\":").append(profile.toJson()).append(',')
                .append("\"result\":{")
                .append("\"fetchSucceeded\":").append(fetchSucceeded)
                .append(',')
                .append("\"fetchPerSecond\":")
                .append(Double.toString(fetchPerSecond)).append(',')
                .append("\"fetchFailed\":").append(fetchFailed).append(',')
                .append("\"allowedFetchFailures\":")
                .append(allowedFetchFailures).append(',')
                .append("\"revisionRegressed\":")
                .append(revisionRegressed).append(',')
                .append("\"publishSucceeded\":")
                .append(publishSucceeded).append(',')
                .append("\"publishFailed\":").append(publishFailed)
                .append(',')
                .append("\"latestDecisionRevision\":")
                .append(latestDecisionRevision).append(',')
                .append("\"latestEventRevision\":")
                .append(latestEventRevision).append(',')
                .append("\"fetchP50UpperBoundMs\":")
                .append(latency.percentileUpperBoundMs(0.50)).append(',')
                .append("\"fetchP95UpperBoundMs\":")
                .append(latency.percentileUpperBoundMs(0.95)).append(',')
                .append("\"fetchP99UpperBoundMs\":")
                .append(latency.percentileUpperBoundMs(0.99)).append(',')
                .append("\"fetchMaxMs\":")
                .append(TimeUnit.NANOSECONDS.toMillis(latency.maxNanos()))
                .append("},\"resources\":{")
                .append("\"sampleCount\":").append(samples.size()).append(',')
                .append("\"preCrashSampleCount\":")
                .append(samples.size() - growthSamples.size()).append(',')
                .append("\"growthSampleCount\":")
                .append(growthSamples.size()).append(',')
                .append("\"peakHeapUsedBytes\":")
                .append(peakHeapUsedBytes).append(',')
                .append("\"peakNonHeapUsedBytes\":")
                .append(peakNonHeapUsedBytes).append(',')
                .append("\"peakDirectBufferMemoryUsedBytes\":")
                .append(peakDirectBufferMemoryUsedBytes).append(',')
                .append("\"peakLiveThreads\":")
                .append(peakLiveThreads).append(',')
                .append("\"growth\":").append(growth.toJson())
                .append("},\"nodes\":[");
        for (int index = 0; index < nodes.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            NodeProcess node = nodes.get(index);
            NodeMetrics beforeCrash = preCrashMetrics.stream()
                    .filter(value -> value.nodeIndex() == node.index())
                    .findFirst().orElse(null);
            NodeMetrics afterCrash = finalMetrics.stream()
                    .filter(value -> value.nodeIndex() == node.index())
                    .findFirst().orElse(null);
            json.append(nodeToJson(
                    node,
                    beforeCrash,
                    afterCrash,
                    crashNodeIndex,
                    storageSize(node.stateDirectory()),
                    logs.get(index)));
        }
        int activeSessions = clientAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::activeSessions)
                .sum();
        int inFlight = clientAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::inFlightRequests)
                .sum();
        int watches = clientAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::registeredWatches)
                .sum();
        int streams = clientAfterClose.stream()
                .mapToInt(ControlPlaneTransportMetricsSnapshot::activeSubscribeStreams)
                .sum();
        json.append("],\"clientAfterClose\":{")
                .append("\"activeSessions\":").append(activeSessions)
                .append(',')
                .append("\"inFlightRequests\":").append(inFlight)
                .append(',')
                .append("\"registeredWatches\":").append(watches)
                .append(',')
                .append("\"activeSubscribeStreams\":")
                .append(streams).append("}}");
        reportJournal.writeSummary(json.toString());
    }

    private String nodeToJson(
            NodeProcess node,
            NodeMetrics beforeCrash,
            NodeMetrics finalMetrics,
            int crashNodeIndex,
            StorageSize storage,
            ProcessLogMetrics log) {
        StringBuilder result = new StringBuilder(1_024)
                .append('{')
                .append("\"nodeIndex\":").append(node.index()).append(',')
                .append("\"nodeId\":\"").append(json(node.nodeId()))
                .append("\",")
                .append("\"pid\":").append(node.process().pid()).append(',')
                .append("\"crashed\":")
                .append(node.index() == crashNodeIndex)
                .append(',')
                .append("\"preCrash\":")
                .append(beforeCrash == null ? "null" : beforeCrash.toJson())
                .append(',')
                .append("\"final\":")
                .append(finalMetrics == null ? "null" : finalMetrics.toJson())
                .append(',')
                .append("\"walFiles\":").append(storage.walFiles())
                .append(',')
                .append("\"walBytes\":").append(storage.walBytes())
                .append(',')
                .append("\"snapshotFiles\":")
                .append(storage.snapshotFiles()).append(',')
                .append("\"snapshotBytes\":")
                .append(storage.snapshotBytes()).append(',')
                .append("\"warningLines\":").append(log.warningLines())
                .append(',')
                .append("\"errorLines\":").append(log.errorLines())
                .append(",\"logSamples\":[");
        for (int index = 0; index < log.samples().size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append('\"').append(json(log.samples().get(index)))
                    .append('\"');
        }
        return result.append("]}").toString();
    }

    private StorageSize storageSize(Path root) {
        long walFiles = 0L;
        long walBytes = 0L;
        long snapshotFiles = 0L;
        long snapshotBytes = 0L;
        try {
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.filter(Files::isRegularFile).toList()) {
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
            return new StorageSize(
                    walFiles, walBytes, snapshotFiles, snapshotBytes);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to inspect State storage " + root, e);
        }
    }

    private List<Integer> freePorts(int count) throws Exception {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                sockets.add(new ServerSocket(0));
            }
            return sockets.stream().map(ServerSocket::getLocalPort).toList();
        } catch (SocketException e) {
            throw new IllegalStateException(
                    "Split-process topology testing requires local socket binding",
                    e);
        } finally {
            for (ServerSocket socket : sockets) {
                socket.close();
            }
        }
    }

    private String peers(List<Integer> ports) {
        List<String> result = new ArrayList<>(ports.size());
        for (int index = 0; index < ports.size(); index++) {
            result.add("state-" + (index + 1)
                    + "@127.0.0.1:" + ports.get(index));
        }
        return String.join(",", result);
    }

    private int[] rotatedOrder(int first) {
        int[] order = new int[NODE_COUNT];
        for (int index = 0; index < NODE_COUNT; index++) {
            order[index] = (first + index) % NODE_COUNT;
        }
        return order;
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isExecutable(current.resolve(
                    "scripts/run-control-plane-load.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Xuantong repository root was not found");
    }

    private void awaitTrue(Check condition, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.evaluate(),
                "Condition was not satisfied before timeout");
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            result.load(input);
        }
        return result;
    }

    private static void writeProperties(
            Path target, Properties properties) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + UUID.randomUUID());
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, null);
        }
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void parkUntil(long deadlineNanos)
            throws InterruptedException {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            LockSupport.parkNanos(Math.min(
                    remaining, TimeUnit.MILLISECONDS.toNanos(100L)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private static void parkUntilOrStopped(
            long deadlineNanos, AtomicBoolean stop)
            throws InterruptedException {
        while (!stop.get()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            LockSupport.parkNanos(Math.min(
                    remaining, TimeUnit.MILLISECONDS.toNanos(100L)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private static void shutdownPublisher(ExecutorService publisher) {
        if (publisher == null) {
            return;
        }
        publisher.shutdown();
        try {
            if (!publisher.awaitTermination(35, TimeUnit.SECONDS)) {
                publisher.shutdownNow();
                publisher.awaitTermination(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            publisher.shutdownNow();
            Thread.currentThread().interrupt();
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

    private static void shutdownSampler(ScheduledExecutorService sampler) {
        if (sampler == null) {
            return;
        }
        sampler.shutdown();
        try {
            if (!sampler.awaitTermination(10, TimeUnit.SECONDS)) {
                sampler.shutdownNow();
                sampler.awaitTermination(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            sampler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private Path reportPath(LoadProfile profile) {
        return profile.reportPath().isBlank()
                ? tempDirectory.resolve("split-process-topology-load.jsonl")
                : Path.of(profile.reportPath()).toAbsolutePath().normalize();
    }

    private static String reportHeaderJson(
            LoadProfile profile,
            RuntimeMetadata runtimeMetadata,
            long startedEpochMs) {
        return new StringBuilder(1_024)
                .append('{')
                .append("\"type\":\"header\",")
                .append("\"schemaVersion\":1,")
                .append("\"startedEpochMs\":").append(startedEpochMs)
                .append(',')
                .append("\"runLabel\":\"")
                .append(json(profile.runLabel())).append("\",")
                .append("\"buildRevision\":\"")
                .append(json(profile.buildRevision())).append("\",")
                .append("\"buildState\":\"")
                .append(json(profile.buildState())).append("\",")
                .append("\"profile\":").append(profile.toJson()).append(',')
                .append("\"runtime\":")
                .append(runtimeMetadata.toJson())
                .append('}')
                .toString();
    }

    private static void closeQuietly(SplitReportJournal reportJournal) {
        try {
            reportJournal.close();
        } catch (IOException ignored) {
            // A successful run closes explicitly before final assertions.
        }
    }

    private static <T> List<T> copy(List<T> source) {
        synchronized (source) {
            return List.copyOf(source);
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static final class SplitReportJournal implements AutoCloseable {
        private final BufferedWriter writer;
        private boolean headerWritten;
        private boolean summaryWritten;
        private boolean closed;

        private SplitReportJournal(BufferedWriter writer) {
            this.writer = writer;
        }

        private static SplitReportJournal open(
                Path reportPath, String header)
                throws IOException {
            if (reportPath.getParent() != null) {
                Files.createDirectories(reportPath.getParent());
            }
            SplitReportJournal journal = new SplitReportJournal(
                    Files.newBufferedWriter(
                            reportPath,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));
            try {
                journal.writeHeader(header);
                return journal;
            } catch (IOException e) {
                try {
                    journal.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        }

        private synchronized void writeHeader(String header)
                throws IOException {
            if (headerWritten) {
                throw new IOException("Split report header was already written");
            }
            writeLine(header);
            headerWritten = true;
        }

        private synchronized void writeSample(SplitResourceSample sample)
                throws IOException {
            if (!headerWritten) {
                throw new IOException(
                        "Cannot append a resource sample before header");
            }
            if (summaryWritten) {
                throw new IOException(
                        "Cannot append a resource sample after summary");
            }
            writeLine(sample.toJson());
        }

        private synchronized void writeSummary(String summary)
                throws IOException {
            if (!headerWritten) {
                throw new IOException("Cannot append summary before header");
            }
            if (summaryWritten) {
                throw new IOException("Split report summary was already written");
            }
            writeLine(summary);
            summaryWritten = true;
        }

        private void writeLine(String line) throws IOException {
            if (closed) {
                throw new IOException("Split report journal is closed");
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            writer.close();
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate() throws Exception;
    }

    private record RuntimeMetadata(
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
            long driverMaxHeapBytes) {

        private static RuntimeMetadata capture() {
            java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
            long physicalMemoryBytes = 0L;
            if (osBean instanceof
                    com.sun.management.OperatingSystemMXBean extended) {
                physicalMemoryBytes = Math.max(
                        0L, extended.getTotalMemorySize());
            }
            String ratisVersion = RaftServer.class.getPackage()
                    .getImplementationVersion();
            return new RuntimeMetadata(
                    "native-socketd-tcp+ratis-three-voter"
                            + "+three-child-jvms",
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

        private String toJson() {
            return new StringBuilder(768)
                    .append('{')
                    .append("\"transportPath\":\"")
                    .append(json(transportPath)).append("\",")
                    .append("\"javaVersion\":\"")
                    .append(json(javaVersion)).append("\",")
                    .append("\"javaVmName\":\"")
                    .append(json(javaVmName)).append("\",")
                    .append("\"javaVmVendor\":\"")
                    .append(json(javaVmVendor)).append("\",")
                    .append("\"socketdVersion\":\"")
                    .append(json(socketdVersion)).append("\",")
                    .append("\"solonVersion\":\"")
                    .append(json(solonVersion)).append("\",")
                    .append("\"ratisVersion\":\"")
                    .append(json(ratisVersion)).append("\",")
                    .append("\"osName\":\"")
                    .append(json(osName)).append("\",")
                    .append("\"osVersion\":\"")
                    .append(json(osVersion)).append("\",")
                    .append("\"osArch\":\"")
                    .append(json(osArch)).append("\",")
                    .append("\"availableProcessors\":")
                    .append(availableProcessors).append(',')
                    .append("\"physicalMemoryBytes\":")
                    .append(physicalMemoryBytes).append(',')
                    .append("\"driverMaxHeapBytes\":")
                    .append(driverMaxHeapBytes)
                    .append('}')
                    .toString();
        }
    }

    private record PublishResult(
            long decisionRevision,
            long eventRevision,
            long appliedIndex) {
    }

    private record ClientHarness(
            int index,
            int[] gatewayOrder,
            CountingSocketDTransport transport,
            AtomicLong observedDecision,
            AtomicLong watchCursor) {

        private ClientHarness {
            gatewayOrder = gatewayOrder.clone();
        }

        @Override
        public int[] gatewayOrder() {
            return gatewayOrder.clone();
        }
    }

    private record StorageSize(
            long walFiles,
            long walBytes,
            long snapshotFiles,
            long snapshotBytes) {
    }

    private record ProcessLogMetrics(
            long warningLines, long errorLines, List<String> samples) {

        private ProcessLogMetrics {
            samples = List.copyOf(samples);
        }
    }

    private record SplitResourceSample(
            String phase,
            long elapsedMs,
            long phaseElapsedMs,
            List<NodeMetrics> nodes,
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
            int clientActiveSessions,
            int clientMinActiveSessions,
            int clientMaxActiveSessions,
            int clientsWithoutActiveSession,
            int clientsWithMultipleActiveSessions,
            int clientInFlightRequests,
            int clientRegisteredWatches,
            int clientActiveSubscribeStreams,
            long walBytes,
            long snapshotBytes) {

        private SplitResourceSample {
            if (!"pre-crash".equals(phase)
                    && !"post-crash".equals(phase)
                    && !"final".equals(phase)) {
                throw new IllegalArgumentException(
                        "Unsupported split resource phase: " + phase);
            }
            nodes = List.copyOf(nodes);
        }

        private static SplitResourceSample from(
                String phase,
                long elapsedMs,
                long phaseElapsedMs,
                List<NodeMetrics> nodes,
                int clientActiveSessions,
                int clientMinActiveSessions,
                int clientMaxActiveSessions,
                int clientsWithoutActiveSession,
                int clientsWithMultipleActiveSessions,
                int clientInFlightRequests,
                int clientRegisteredWatches,
                int clientActiveSubscribeStreams,
                long walBytes,
                long snapshotBytes) {
            return new SplitResourceSample(
                    phase,
                    elapsedMs,
                    phaseElapsedMs,
                    nodes,
                    nodes.stream().mapToLong(NodeMetrics::heapUsedBytes).sum(),
                    nodes.stream().mapToLong(NodeMetrics::nonHeapUsedBytes).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::heapAfterLastGcBytes).sum(),
                    nodes.stream().mapToInt(
                            NodeMetrics::heapAfterLastGcPoolCount).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::gcCollectionCount).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::gcCollectionTimeMs).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::directBufferCount).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::directBufferMemoryUsedBytes).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::directBufferTotalCapacityBytes).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::mappedBufferCount).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::mappedBufferMemoryUsedBytes).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::mappedBufferTotalCapacityBytes).sum(),
                    nodes.stream().mapToInt(NodeMetrics::liveThreads).sum(),
                    nodes.stream().mapToInt(NodeMetrics::activeSessions).sum(),
                    nodes.stream().mapToInt(
                            NodeMetrics::activeSubscriptions).sum(),
                    nodes.stream().mapToInt(
                            NodeMetrics::pendingWatchAcknowledgements).sum(),
                    nodes.stream().mapToInt(NodeMetrics::inFlightRequests).sum(),
                    nodes.stream().mapToInt(NodeMetrics::workQueueDepth).sum(),
                    nodes.stream().mapToInt(
                            NodeMetrics::stateCallbackQueueDepth).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::requestAcceptedTotal).sum(),
                    nodes.stream().mapToLong(
                            NodeMetrics::requestCompletedTotal).sum(),
                    clientActiveSessions,
                    clientMinActiveSessions,
                    clientMaxActiveSessions,
                    clientsWithoutActiveSession,
                    clientsWithMultipleActiveSessions,
                    clientInFlightRequests,
                    clientRegisteredWatches,
                    clientActiveSubscribeStreams,
                    walBytes,
                    snapshotBytes);
        }

        private String toJson() {
            StringBuilder result = new StringBuilder(2_048)
                    .append('{')
                    .append("\"type\":\"sample\",")
                    .append("\"phase\":\"").append(phase).append("\",")
                    .append("\"elapsedMs\":").append(elapsedMs).append(',')
                    .append("\"phaseElapsedMs\":")
                    .append(phaseElapsedMs).append(',')
                    .append("\"heapUsedBytes\":").append(heapUsedBytes).append(',')
                    .append("\"nonHeapUsedBytes\":")
                    .append(nonHeapUsedBytes).append(',')
                    .append("\"heapAfterLastGcBytes\":")
                    .append(heapAfterLastGcBytes).append(',')
                    .append("\"heapAfterLastGcPoolCount\":")
                    .append(heapAfterLastGcPoolCount).append(',')
                    .append("\"gcCollectionCount\":")
                    .append(gcCollectionCount).append(',')
                    .append("\"gcCollectionTimeMs\":")
                    .append(gcCollectionTimeMs).append(',')
                    .append("\"directBufferCount\":")
                    .append(directBufferCount).append(',')
                    .append("\"directBufferMemoryUsedBytes\":")
                    .append(directBufferMemoryUsedBytes).append(',')
                    .append("\"directBufferTotalCapacityBytes\":")
                    .append(directBufferTotalCapacityBytes).append(',')
                    .append("\"mappedBufferCount\":")
                    .append(mappedBufferCount).append(',')
                    .append("\"mappedBufferMemoryUsedBytes\":")
                    .append(mappedBufferMemoryUsedBytes).append(',')
                    .append("\"mappedBufferTotalCapacityBytes\":")
                    .append(mappedBufferTotalCapacityBytes).append(',')
                    .append("\"liveThreads\":").append(liveThreads).append(',')
                    .append("\"activeSessions\":")
                    .append(activeSessions).append(',')
                    .append("\"activeSubscriptions\":")
                    .append(activeSubscriptions).append(',')
                    .append("\"pendingWatchAcknowledgements\":")
                    .append(pendingWatchAcknowledgements).append(',')
                    .append("\"inFlightRequests\":")
                    .append(inFlightRequests).append(',')
                    .append("\"workQueueDepth\":")
                    .append(workQueueDepth).append(',')
                    .append("\"stateCallbackQueueDepth\":")
                    .append(stateCallbackQueueDepth).append(',')
                    .append("\"requestAcceptedTotal\":")
                    .append(requestAcceptedTotal).append(',')
                    .append("\"requestCompletedTotal\":")
                    .append(requestCompletedTotal).append(',')
                    .append("\"clientActiveSessions\":")
                    .append(clientActiveSessions).append(',')
                    .append("\"clientMinActiveSessions\":")
                    .append(clientMinActiveSessions).append(',')
                    .append("\"clientMaxActiveSessions\":")
                    .append(clientMaxActiveSessions).append(',')
                    .append("\"clientsWithoutActiveSession\":")
                    .append(clientsWithoutActiveSession).append(',')
                    .append("\"clientsWithMultipleActiveSessions\":")
                    .append(clientsWithMultipleActiveSessions).append(',')
                    .append("\"clientInFlightRequests\":")
                    .append(clientInFlightRequests).append(',')
                    .append("\"clientRegisteredWatches\":")
                    .append(clientRegisteredWatches).append(',')
                    .append("\"clientActiveSubscribeStreams\":")
                    .append(clientActiveSubscribeStreams).append(',')
                    .append("\"walBytes\":").append(walBytes).append(',')
                    .append("\"snapshotBytes\":")
                    .append(snapshotBytes).append(',')
                    .append("\"nodes\":[");
            for (int index = 0; index < nodes.size(); index++) {
                if (index > 0) {
                    result.append(',');
                }
                result.append(nodes.get(index).toJson());
            }
            return result.append("]}").toString();
        }
    }

    private record SplitResourceGrowth(
            long baselineElapsedMs,
            long baselinePhaseElapsedMs,
            long lastElapsedMs,
            long lastPhaseElapsedMs,
            long observationWindowMs,
            long heapUsedBytesDelta,
            long nonHeapUsedBytesDelta,
            Long heapAfterLastGcBytesDelta,
            int liveThreadsDelta,
            int inFlightRequestsDelta,
            int clientInFlightRequestsDelta,
            int clientRegisteredWatchesDelta,
            int clientActiveSubscribeStreamsDelta,
            long gcCollectionCountDelta,
            long gcCollectionTimeMsDelta,
            long directBufferMemoryUsedBytesDelta,
            long mappedBufferMemoryUsedBytesDelta,
            long walBytesDelta,
            long snapshotBytesDelta) {

        private static SplitResourceGrowth from(
                List<SplitResourceSample> samples, long warmupElapsedMs) {
            if (samples == null || samples.isEmpty()) {
                throw new IllegalArgumentException(
                        "Split resource samples must not be empty");
            }
            SplitResourceSample baseline = samples.get(samples.size() - 1);
            for (SplitResourceSample sample : samples) {
                if (sample.phaseElapsedMs()
                        >= Math.max(0L, warmupElapsedMs)) {
                    baseline = sample;
                    break;
                }
            }
            SplitResourceSample last = samples.get(samples.size() - 1);
            boolean heapAfterLastGcAvailable =
                    baseline.heapAfterLastGcPoolCount() > 0
                            && last.heapAfterLastGcPoolCount() > 0;
            return new SplitResourceGrowth(
                    baseline.elapsedMs(),
                    baseline.phaseElapsedMs(),
                    last.elapsedMs(),
                    last.phaseElapsedMs(),
                    Math.max(0L, last.elapsedMs() - baseline.elapsedMs()),
                    last.heapUsedBytes() - baseline.heapUsedBytes(),
                    last.nonHeapUsedBytes() - baseline.nonHeapUsedBytes(),
                    heapAfterLastGcAvailable
                            ? last.heapAfterLastGcBytes()
                            - baseline.heapAfterLastGcBytes()
                            : null,
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
                    last.directBufferMemoryUsedBytes()
                            - baseline.directBufferMemoryUsedBytes(),
                    last.mappedBufferMemoryUsedBytes()
                            - baseline.mappedBufferMemoryUsedBytes(),
                    last.walBytes() - baseline.walBytes(),
                    last.snapshotBytes() - baseline.snapshotBytes());
        }

        private String toJson() {
            return new StringBuilder(768)
                    .append('{')
                    .append("\"baselineElapsedMs\":")
                    .append(baselineElapsedMs).append(',')
                    .append("\"baselinePhaseElapsedMs\":")
                    .append(baselinePhaseElapsedMs).append(',')
                    .append("\"lastElapsedMs\":").append(lastElapsedMs).append(',')
                    .append("\"lastPhaseElapsedMs\":")
                    .append(lastPhaseElapsedMs).append(',')
                    .append("\"observationWindowMs\":")
                    .append(observationWindowMs).append(',')
                    .append("\"heapUsedBytesDelta\":")
                    .append(heapUsedBytesDelta).append(',')
                    .append("\"nonHeapUsedBytesDelta\":")
                    .append(nonHeapUsedBytesDelta).append(',')
                    .append("\"heapAfterLastGcBytesDelta\":")
                    .append(heapAfterLastGcBytesDelta == null
                            ? "null" : heapAfterLastGcBytesDelta)
                    .append(',')
                    .append("\"liveThreadsDelta\":")
                    .append(liveThreadsDelta).append(',')
                    .append("\"inFlightRequestsDelta\":")
                    .append(inFlightRequestsDelta).append(',')
                    .append("\"clientInFlightRequestsDelta\":")
                    .append(clientInFlightRequestsDelta).append(',')
                    .append("\"clientRegisteredWatchesDelta\":")
                    .append(clientRegisteredWatchesDelta).append(',')
                    .append("\"clientActiveSubscribeStreamsDelta\":")
                    .append(clientActiveSubscribeStreamsDelta).append(',')
                    .append("\"gcCollectionCountDelta\":")
                    .append(gcCollectionCountDelta).append(',')
                    .append("\"gcCollectionTimeMsDelta\":")
                    .append(gcCollectionTimeMsDelta).append(',')
                    .append("\"directBufferMemoryUsedBytesDelta\":")
                    .append(directBufferMemoryUsedBytesDelta).append(',')
                    .append("\"mappedBufferMemoryUsedBytesDelta\":")
                    .append(mappedBufferMemoryUsedBytesDelta).append(',')
                    .append("\"walBytesDelta\":")
                    .append(walBytesDelta).append(',')
                    .append("\"snapshotBytesDelta\":")
                    .append(snapshotBytesDelta).append('}')
                    .toString();
        }
    }

    private record NodeProcess(
            int index,
            int statePort,
            int gatewayPort,
            Path stateDirectory,
            Path controlDirectory,
            Path log,
            Process process) {

        private String nodeId() {
            return "state-" + (index + 1);
        }

        private String gatewayAddress() {
            return "127.0.0.1:" + gatewayPort;
        }

        private Path readyPath() {
            return controlDirectory.resolve("ready.properties");
        }

        private boolean alive() {
            return process.isAlive();
        }

        private String describeLog() {
            try {
                return nodeId() + " log:\n"
                        + (Files.exists(log)
                        ? Files.readString(log, StandardCharsets.UTF_8)
                        : "<missing>");
            } catch (IOException e) {
                return nodeId() + " log unreadable: " + e.getMessage();
            }
        }

        private ProcessLogMetrics logMetrics() {
            try {
                if (!Files.exists(log)) {
                    return new ProcessLogMetrics(0L, 0L, List.of());
                }
                long warnings = 0L;
                long errors = 0L;
                List<String> samples = new ArrayList<>();
                for (String line : Files.readAllLines(
                        log, StandardCharsets.UTF_8)) {
                    if (line.contains(" WARN ")) {
                        warnings++;
                    }
                    if (line.contains(" ERROR ")) {
                        errors++;
                    }
                    if ((line.contains(" WARN ") || line.contains(" ERROR "))
                            && samples.size() < 20) {
                        samples.add(line);
                    }
                }
                return new ProcessLogMetrics(warnings, errors, samples);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to inspect split node log " + log, e);
            }
        }
    }

    private record NodeMetrics(
            int nodeIndex,
            boolean crashed,
            long pid,
            String nodeId,
            String gatewayId,
            int activeSessions,
            int activeSubscriptions,
            int pendingWatchAcknowledgements,
            int inFlightRequests,
            long requestAcceptedTotal,
            long requestCompletedTotal,
            long tenantRateLimitedTotal,
            long overloadedRejectedTotal,
            long stateCallbackRejectedTotal,
            long sessionOpenedTotal,
            long sessionClosedTotal,
            int peakActiveSessions,
            long subscriptionOpenedTotal,
            long subscriptionClosedTotal,
            int peakActiveSubscriptions,
            int peakInFlightRequests,
            int peakWorkQueueDepth,
            int peakStateCallbackQueueDepth,
            int workQueueDepth,
            int stateCallbackQueueDepth,
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
            boolean stateAlive,
            boolean stateLeader,
            boolean stateLeaderReady,
            String stateLeaderId,
            long stateCurrentTerm,
            long stateLastCommittedIndex,
            long stateLastAppliedIndex) {

        private static NodeMetrics from(
                int nodeIndex, boolean crashed, Properties value) {
            return new NodeMetrics(
                    nodeIndex,
                    crashed,
                    longValue(value, "pid"),
                    value.getProperty("nodeId", ""),
                    value.getProperty("gatewayId", ""),
                    intValue(value, "activeSessions"),
                    intValue(value, "activeSubscriptions"),
                    intValue(value, "pendingWatchAcknowledgements"),
                    intValue(value, "inFlightRequests"),
                    longValue(value, "requestAcceptedTotal"),
                    longValue(value, "requestCompletedTotal"),
                    longValue(value, "tenantRateLimitedTotal"),
                    longValue(value, "overloadedRejectedTotal"),
                    longValue(value, "stateCallbackRejectedTotal"),
                    longValue(value, "sessionOpenedTotal"),
                    longValue(value, "sessionClosedTotal"),
                    intValue(value, "peakActiveSessions"),
                    longValue(value, "subscriptionOpenedTotal"),
                    longValue(value, "subscriptionClosedTotal"),
                    intValue(value, "peakActiveSubscriptions"),
                    intValue(value, "peakInFlightRequests"),
                    intValue(value, "peakWorkQueueDepth"),
                    intValue(value, "peakStateCallbackQueueDepth"),
                    intValue(value, "workQueueDepth"),
                    intValue(value, "stateCallbackQueueDepth"),
                    longValue(value, "heapUsedBytes"),
                    longValue(value, "nonHeapUsedBytes"),
                    longValue(value, "heapAfterLastGcBytes"),
                    intValue(value, "heapAfterLastGcPoolCount"),
                    longValue(value, "gcCollectionCount"),
                    longValue(value, "gcCollectionTimeMs"),
                    longValue(value, "directBufferCount"),
                    longValue(value, "directBufferMemoryUsedBytes"),
                    longValue(value, "directBufferTotalCapacityBytes"),
                    longValue(value, "mappedBufferCount"),
                    longValue(value, "mappedBufferMemoryUsedBytes"),
                    longValue(value, "mappedBufferTotalCapacityBytes"),
                    intValue(value, "liveThreads"),
                    booleanValue(value, "stateAlive"),
                    booleanValue(value, "stateLeader"),
                    booleanValue(value, "stateLeaderReady"),
                    value.getProperty("stateLeaderId", ""),
                    longValue(value, "stateCurrentTerm"),
                    longValue(value, "stateLastCommittedIndex"),
                    longValue(value, "stateLastAppliedIndex"));
        }

        private String toJson() {
            return new StringBuilder(1_024)
                    .append('{')
                    .append("\"pid\":").append(pid).append(',')
                    .append("\"nodeId\":\"").append(json(nodeId))
                    .append("\",")
                    .append("\"gatewayId\":\"").append(json(gatewayId))
                    .append("\",")
                    .append("\"activeSessions\":").append(activeSessions)
                    .append(',')
                    .append("\"activeSubscriptions\":")
                    .append(activeSubscriptions).append(',')
                    .append("\"pendingWatchAcknowledgements\":")
                    .append(pendingWatchAcknowledgements).append(',')
                    .append("\"inFlightRequests\":")
                    .append(inFlightRequests).append(',')
                    .append("\"requestAcceptedTotal\":")
                    .append(requestAcceptedTotal).append(',')
                    .append("\"requestCompletedTotal\":")
                    .append(requestCompletedTotal).append(',')
                    .append("\"tenantRateLimitedTotal\":")
                    .append(tenantRateLimitedTotal).append(',')
                    .append("\"overloadedRejectedTotal\":")
                    .append(overloadedRejectedTotal).append(',')
                    .append("\"stateCallbackRejectedTotal\":")
                    .append(stateCallbackRejectedTotal).append(',')
                    .append("\"sessionOpenedTotal\":")
                    .append(sessionOpenedTotal).append(',')
                    .append("\"sessionClosedTotal\":")
                    .append(sessionClosedTotal).append(',')
                    .append("\"peakActiveSessions\":")
                    .append(peakActiveSessions).append(',')
                    .append("\"subscriptionOpenedTotal\":")
                    .append(subscriptionOpenedTotal).append(',')
                    .append("\"subscriptionClosedTotal\":")
                    .append(subscriptionClosedTotal).append(',')
                    .append("\"peakActiveSubscriptions\":")
                    .append(peakActiveSubscriptions).append(',')
                    .append("\"peakInFlightRequests\":")
                    .append(peakInFlightRequests).append(',')
                    .append("\"peakWorkQueueDepth\":")
                    .append(peakWorkQueueDepth).append(',')
                    .append("\"peakStateCallbackQueueDepth\":")
                    .append(peakStateCallbackQueueDepth).append(',')
                    .append("\"workQueueDepth\":")
                    .append(workQueueDepth).append(',')
                    .append("\"stateCallbackQueueDepth\":")
                    .append(stateCallbackQueueDepth).append(',')
                    .append("\"heapUsedBytes\":")
                    .append(heapUsedBytes).append(',')
                    .append("\"nonHeapUsedBytes\":")
                    .append(nonHeapUsedBytes).append(',')
                    .append("\"heapAfterLastGcBytes\":")
                    .append(heapAfterLastGcBytes).append(',')
                    .append("\"heapAfterLastGcPoolCount\":")
                    .append(heapAfterLastGcPoolCount).append(',')
                    .append("\"gcCollectionCount\":")
                    .append(gcCollectionCount).append(',')
                    .append("\"gcCollectionTimeMs\":")
                    .append(gcCollectionTimeMs).append(',')
                    .append("\"directBufferCount\":")
                    .append(directBufferCount).append(',')
                    .append("\"directBufferMemoryUsedBytes\":")
                    .append(directBufferMemoryUsedBytes).append(',')
                    .append("\"directBufferTotalCapacityBytes\":")
                    .append(directBufferTotalCapacityBytes).append(',')
                    .append("\"mappedBufferCount\":")
                    .append(mappedBufferCount).append(',')
                    .append("\"mappedBufferMemoryUsedBytes\":")
                    .append(mappedBufferMemoryUsedBytes).append(',')
                    .append("\"mappedBufferTotalCapacityBytes\":")
                    .append(mappedBufferTotalCapacityBytes).append(',')
                    .append("\"liveThreads\":")
                    .append(liveThreads).append(',')
                    .append("\"stateAlive\":").append(stateAlive).append(',')
                    .append("\"stateLeader\":").append(stateLeader)
                    .append(',')
                    .append("\"stateLeaderReady\":")
                    .append(stateLeaderReady).append(',')
                    .append("\"stateLeaderId\":\"")
                    .append(json(stateLeaderId)).append("\",")
                    .append("\"stateCurrentTerm\":")
                    .append(stateCurrentTerm).append(',')
                    .append("\"stateLastCommittedIndex\":")
                    .append(stateLastCommittedIndex).append(',')
                    .append("\"stateLastAppliedIndex\":")
                    .append(stateLastAppliedIndex).append('}')
                    .toString();
        }

        private static int intValue(Properties value, String name) {
            return Integer.parseInt(value.getProperty(name));
        }

        private static long longValue(Properties value, String name) {
            return Long.parseLong(value.getProperty(name));
        }

        private static boolean booleanValue(Properties value, String name) {
            return Boolean.parseBoolean(value.getProperty(name));
        }
    }

    private static final class CountingSocketDTransport
            extends SocketDTransport {
        private final AtomicIntegerArray openAttempts;

        private CountingSocketDTransport(
                ClientIdentity identity,
                ControlPlaneOptions options,
                int gatewayCount) {
            super(identity, options);
            this.openAttempts = new AtomicIntegerArray(gatewayCount);
        }

        @Override
        protected ClientSession openSession(
                int gatewayIndex,
                String url,
                EventListener listener,
                long connectTimeoutMs) throws Exception {
            openAttempts.incrementAndGet(gatewayIndex);
            return super.openSession(
                    gatewayIndex, url, listener, connectTimeoutMs);
        }

        private int openAttempts(int gatewayIndex) {
            return openAttempts.get(gatewayIndex);
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

        private void awaitNext(long workloadDeadlineNanos)
                throws InterruptedException {
            if (ratePerSecond <= 0) {
                return;
            }
            long sequence = issued.getAndIncrement();
            long target = startedNanos + Math.round(
                    sequence * 1_000_000_000D / ratePerSecond);
            parkUntil(Math.min(target, workloadDeadlineNanos));
        }
    }

    private static final class LatencyHistogram {
        private static final long[] UPPER_BOUNDS_MS = {
                1L, 2L, 5L, 10L, 20L, 50L, 100L, 250L,
                500L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L,
                Long.MAX_VALUE
        };
        private final LongAdder[] buckets =
                new LongAdder[UPPER_BOUNDS_MS.length];
        private final LongAdder count = new LongAdder();
        private final LongAccumulator maxNanos =
                new LongAccumulator(Math::max, 0L);

        private LatencyHistogram() {
            for (int index = 0; index < buckets.length; index++) {
                buckets[index] = new LongAdder();
            }
        }

        private void record(long latencyNanos) {
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, latencyNanos));
            count.increment();
            maxNanos.accumulate(Math.max(0L, latencyNanos));
            for (int index = 0; index < UPPER_BOUNDS_MS.length; index++) {
                if (latencyMs <= UPPER_BOUNDS_MS[index]) {
                    buckets[index].increment();
                    return;
                }
            }
        }

        private long maxNanos() {
            return maxNanos.get();
        }

        private long percentileUpperBoundMs(double percentile) {
            long total = count.sum();
            if (total == 0L) {
                return 0L;
            }
            long required = Math.max(
                    1L, (long) Math.ceil(total * percentile));
            long cumulative = 0L;
            for (int index = 0; index < buckets.length; index++) {
                cumulative += buckets[index].sum();
                if (cumulative >= required) {
                    return UPPER_BOUNDS_MS[index];
                }
            }
            return Long.MAX_VALUE;
        }
    }

    private record LoadProfile(
            int durationSeconds,
            int clients,
            int watchers,
            int fetchConcurrency,
            int fetchRatePerSecond,
            int publishRatePerMinute,
            int payloadBytes,
            int childMaxHeapMb,
            int startupTimeoutSeconds,
            int sampleIntervalSeconds,
            int growthWarmupSeconds,
            String crashTarget,
            String reportPath,
            String runLabel,
            String buildRevision,
            String buildState) {

        private LoadProfile {
            range("durationSeconds", durationSeconds, 5, 259_200);
            range("clients", clients, 3, 10_000);
            range("watchers", watchers, 0, clients);
            range("fetchConcurrency", fetchConcurrency, 1, 2_048);
            range("fetchRatePerSecond", fetchRatePerSecond, 0, 10_000_000);
            range("publishRatePerMinute", publishRatePerMinute, 0, 60_000);
            range("payloadBytes", payloadBytes, 16, 1_048_576);
            range("childMaxHeapMb", childMaxHeapMb, 128, 32_768);
            range("startupTimeoutSeconds", startupTimeoutSeconds, 10, 300);
            range("sampleIntervalSeconds", sampleIntervalSeconds, 1, 3_600);
            range("growthWarmupSeconds", growthWarmupSeconds,
                    0, Math.max(0, durationSeconds - 1));
            crashTarget = crashTarget == null
                    ? "follower"
                    : crashTarget.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"leader".equals(crashTarget)
                    && !"follower".equals(crashTarget)) {
                throw new IllegalArgumentException(
                        PREFIX + "crashTarget must be leader or follower");
            }
            reportPath = reportPath == null ? "" : reportPath.trim();
            runLabel = runLabel == null ? "" : runLabel.trim();
            buildRevision = buildRevision == null
                    ? "unknown" : buildRevision.trim();
            buildState = buildState == null
                    ? "unknown" : buildState.trim();
        }

        private static LoadProfile fromSystemProperties() {
            int duration = integer("durationSeconds", 60);
            int clients = integer("clients", 12);
            int sampleInterval = integer("sampleIntervalSeconds", 10);
            return new LoadProfile(
                    duration,
                    clients,
                    integer("watchers", clients),
                    integer("fetchConcurrency", Math.min(16, clients)),
                    integer("fetchRatePerSecond", 500),
                    integer("publishRatePerMinute", 12),
                    integer("payloadBytes", 1_024),
                    integer("childMaxHeapMb", 512),
                    integer("startupTimeoutSeconds", 45),
                    sampleInterval,
                    integer("growthWarmupSeconds",
                            defaultGrowthWarmup(duration, sampleInterval)),
                    System.getProperty(PREFIX + "crashTarget", "follower"),
                    System.getProperty(PREFIX + "reportPath", ""),
                    System.getProperty(PREFIX + "runLabel", ""),
                    System.getProperty(PREFIX + "buildRevision", "unknown"),
                    System.getProperty(PREFIX + "buildState", "unknown"));
        }

        private String toJson() {
            return new StringBuilder(512)
                    .append('{')
                    .append("\"durationSeconds\":")
                    .append(durationSeconds).append(',')
                    .append("\"clients\":").append(clients).append(',')
                    .append("\"watchers\":").append(watchers).append(',')
                    .append("\"fetchConcurrency\":")
                    .append(fetchConcurrency).append(',')
                    .append("\"fetchRatePerSecond\":")
                    .append(fetchRatePerSecond).append(',')
                    .append("\"publishRatePerMinute\":")
                    .append(publishRatePerMinute).append(',')
                    .append("\"payloadBytes\":")
                    .append(payloadBytes).append(',')
                    .append("\"childMaxHeapMb\":")
                    .append(childMaxHeapMb).append(',')
                    .append("\"sampleIntervalSeconds\":")
                    .append(sampleIntervalSeconds).append(',')
                    .append("\"growthWarmupSeconds\":")
                    .append(growthWarmupSeconds).append(',')
                    .append("\"crashTarget\":\"")
                    .append(json(crashTarget)).append("\",")
                    .append("\"mode\":\"")
                    .append(fetchRatePerSecond == 0
                            ? "capacity-chaos"
                            : "controlled-chaos")
                    .append("\"}")
                    .toString();
        }

        private static int defaultGrowthWarmup(
                int durationSeconds, int sampleIntervalSeconds) {
            if (durationSeconds < 60) {
                return 0;
            }
            int postCrashBudget = Math.max(1, durationSeconds / 2);
            int candidate = Math.min(
                    300,
                    Math.max(sampleIntervalSeconds, durationSeconds / 20));
            return Math.min(Math.max(0, postCrashBudget - 1), candidate);
        }

        private static int integer(String name, int defaultValue) {
            return Integer.getInteger(PREFIX + name, defaultValue);
        }

        private static void range(
                String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        PREFIX + name + " must be between "
                                + minimum + " and " + maximum);
            }
        }
    }
}
