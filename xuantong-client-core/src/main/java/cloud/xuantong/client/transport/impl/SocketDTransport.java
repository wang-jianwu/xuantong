package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.ControlPlaneProbeResult;
import cloud.xuantong.client.metrics.ControlPlaneTransportMetricsSnapshot;
import cloud.xuantong.client.model.ConfigGroupSnapshot;
import cloud.xuantong.client.model.ConfigInvalidation;
import cloud.xuantong.client.model.ConfigSnapshot;
import cloud.xuantong.client.model.ConfigWatchBatch;
import cloud.xuantong.client.tls.TlsContextFactory;
import cloud.xuantong.client.transport.ConfigTransport;
import cloud.xuantong.client.transport.WatchBatchHandler;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.ConfigDecisionSummary;
import cloud.xuantong.protocol.v2.ConfigFetchRequest;
import cloud.xuantong.protocol.v2.ConfigFetchResponse;
import cloud.xuantong.protocol.v2.ConfigSnapshotRequest;
import cloud.xuantong.protocol.v2.ConfigSnapshotResponse;
import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ProbeResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.protocol.v2.WatchAckRequest;
import cloud.xuantong.protocol.v2.WatchAckResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.noear.socketd.SocketD;
import org.noear.socketd.exception.SocketDChannelException;
import org.noear.socketd.exception.SocketDConnectionException;
import org.noear.socketd.exception.SocketDTimeoutException;
import org.noear.socketd.transport.client.ClientSession;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.socketd.transport.stream.SubscribeStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Native Socket.D TCP transport for the Xuantong 2.0 control plane.
 *
 * <p>Multiple addresses provide address-level availability. Exactly one stable
 * Socket.D session shell is active at a time, and one logical RPC is sent to one
 * Gateway per attempt. A retry may use one different address sequentially while
 * sharing the original total deadline.</p>
 */
public class SocketDTransport implements ConfigTransport {
    private static final Logger logger = LoggerFactory.getLogger(SocketDTransport.class);
    private static final int MAX_ADDRESS_ATTEMPTS = 2;
    private static final int DEFAULT_WATCH_BATCH_SIZE = 256;
    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;
    private static final long MAINTENANCE_INTERVAL_MS = 1_000L;
    private static final long WARNING_INTERVAL_MS = 30_000L;
    private static final long WATCH_STREAM_TIMEOUT_MS = 24 * 60 * 60_000L;
    private static final List<String> CONFIG_CAPABILITIES = List.of(
            "config-fetch-v1",
            "config-snapshot-v1",
            "config-watch-batch-v1",
            "config-watch-stream-v1",
            "watch-ack-v1");
    private static final List<String> DISCOVERY_CAPABILITIES = List.of(
            "discovery-lease-v1",
            "discovery-snapshot-v1",
            "discovery-watch-batch-v1",
            "discovery-watch-stream-v1",
            "watch-ack-v1");

    private final ClientIdentity identity;
    private final ControlPlaneOptions options;
    private final ClientProfile clientProfile;
    private final TlsContextFactory tlsContextFactory;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicReference<GatewayConnection> activeConnection =
            new AtomicReference<>();
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicLong lastWarningAt = new AtomicLong();
    private final Object drainMonitor = new Object();
    private final List<WatchRegistration<?>> watchRegistrations =
            new CopyOnWriteArrayList<>();

    private volatile List<String> gatewayUrls = Collections.emptyList();
    private volatile String namespace = "";
    private volatile String group = "";
    private volatile String accessToken = "";
    private volatile String pinnedClusterId;
    private volatile long pinnedTransportGeneration;
    private final List<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();
    private volatile Throwable terminalFailure;
    private volatile boolean closed;
    private volatile boolean everActive;
    private volatile int nextAddressIndex;
    private ScheduledFuture<?> maintenanceTask;
    private ExecutorService watchExecutor;

    public SocketDTransport() {
        this(ClientIdentity.defaultIdentity(), ControlPlaneOptions.defaults());
    }

    public SocketDTransport(ClientIdentity identity) {
        this(identity, ControlPlaneOptions.defaults());
    }

    public SocketDTransport(ClientIdentity identity, ControlPlaneOptions options) {
        this(identity, options, ClientProfile.CONFIG);
    }

    private SocketDTransport(
            ClientIdentity identity,
            ControlPlaneOptions options,
            ClientProfile clientProfile) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (clientProfile == null) {
            throw new IllegalArgumentException("clientProfile must not be null");
        }
        this.identity = identity;
        this.options = options;
        this.clientProfile = clientProfile;
        this.tlsContextFactory = new TlsContextFactory(options.tls());
        this.pinnedClusterId = options.clusterId();
        this.pinnedTransportGeneration = options.transportGeneration();
    }

    public static SocketDTransport forDiscovery(
            ClientIdentity identity, ControlPlaneOptions options) {
        return new SocketDTransport(identity, options, ClientProfile.DISCOVERY);
    }

    static SocketDTransport discovery(
            ClientIdentity identity, ControlPlaneOptions options) {
        return forDiscovery(identity, options);
    }

    @Override
    public void connect(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken) {
        configureEndpoint(serverAddresses, namespace, group, accessToken);
        ScheduledFuture<?> previousMaintenance = maintenanceTask;
        if (previousMaintenance != null) {
            previousMaintenance.cancel(false);
        }
        this.watchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "xuantong-control-plane-watch");
            thread.setDaemon(true);
            return thread;
        });

        long deadline = deadlineAfter(options.operationTimeoutMs());
        try {
            acquireAvailableConnection(deadline);
        } catch (Exception e) {
            if (isTerminalControlPlaneFailure(e)) {
                terminalFailure = e;
                warnRateLimited("Xuantong 2.0 control-plane connection was rejected; "
                        + "background reconnect is disabled until the client or Server is reconfigured", e);
            } else {
                warnRateLimited("No Xuantong 2.0 control-plane Gateway is currently available; "
                        + "using last-known-good cache and retrying in background", e);
            }
        }
        maintenanceTask = ControlPlaneClientExecutors.maintenanceScheduler()
                .scheduleWithFixedDelay(
                this::maintenanceSafely,
                MAINTENANCE_INTERVAL_MS,
                MAINTENANCE_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public ConfigSnapshot fetch(String dataId, long minDecisionRevision) {
        String normalizedDataId = requireName("dataId", dataId);
        try {
            return execute("config/fetch " + normalizedDataId, (connection, timeoutMs) -> {
                ConfigFetchRequest payload = ConfigFetchRequest.newBuilder()
                        .setGroupName(group)
                        .setDataId(normalizedDataId)
                        .build();
                Envelope response = request(
                        connection.session,
                        ControlPlaneProtocol.CONFIG_FETCH,
                        businessEnvelope(
                                RevisionType.CONFIG_DECISION,
                                0L,
                                Math.max(0L, minDecisionRevision),
                                ControlPlaneProtocol.CONFIG_FETCH_REQUEST_TYPE,
                                payload.toByteString(),
                                timeoutMs),
                        timeoutMs);
                requireOk(response, ControlPlaneProtocol.CONFIG_FETCH_RESPONSE_TYPE);
                ConfigFetchResponse fetch = ConfigFetchResponse.parseFrom(response.getPayload());
                return switch (fetch.getState()) {
                    case CONFIG_VALUE_STATE_MISSING -> null;
                    case CONFIG_VALUE_STATE_TOMBSTONE -> {
                        validateCoordinate(fetch.getConfig(), normalizedDataId);
                        if (fetch.getDecisionRevision() < minDecisionRevision) {
                            throw new ProtocolException(
                                    "Config tombstone decision revision moved backwards");
                        }
                        if (fetch.hasContent()) {
                            throw new ProtocolException(
                                    "Config tombstone response must not carry content");
                        }
                        yield ConfigSnapshot.tombstone(
                                normalizedDataId, fetch.getDecisionRevision());
                    }
                    case CONFIG_VALUE_STATE_ACTIVE -> {
                        validateCoordinate(fetch.getConfig(), normalizedDataId);
                        if (!fetch.hasContent()) {
                            throw new ProtocolException("Config fetch response has no content");
                        }
                        if (fetch.getDecisionRevision() < minDecisionRevision) {
                            throw new ProtocolException("Config decision revision moved backwards");
                        }
                        if (!fetch.getContent().getBlobReference().isBlank()) {
                            throw new ProtocolException(
                                    "External config blobs are not supported by this client build");
                        }
                        yield new ConfigSnapshot(
                                normalizedDataId,
                                fetch.getContent().getPayload().toString(StandardCharsets.UTF_8),
                                fetch.getDecisionRevision(),
                                fetch.getContent().getContentHash(),
                                fetch.getContent().getContentType());
                    }
                    case CONFIG_VALUE_STATE_UNSPECIFIED, UNRECOGNIZED ->
                            throw new ProtocolException("Config fetch response has no valid state");
                };
            });
        } catch (Exception e) {
            warnRateLimited("Config fetch failed; retaining last-known-good value: dataId="
                    + normalizedDataId, e);
            return null;
        }
    }

    /**
     * Executes a real {@code system/probe} Request/Reply on one Gateway.
     *
     * <p>Unlike config reads, failure is never converted to {@code null}. The
     * caller receives the transport, authentication, compatibility, timeout or
     * protocol exception so this method can be used by an external availability
     * probe. If the active Gateway fails, the existing routing contract permits
     * at most one sequential failover inside the pinned compatibility pool and
     * the original total deadline.</p>
     */
    public ControlPlaneProbeResult probe() throws Exception {
        return execute("system/probe", this::performProbe);
    }

    /**
     * Opens a fresh native Socket.D connection and returns the Probe that
     * validated its Hello exchange.
     *
     * <p>This is the preferred external monitoring API. It does not start the
     * background reconnect loop and it never hides the initial failure behind
     * last-known-good cache behavior. Use a fresh transport in a
     * try-with-resources block for every observation so each sample proves that
     * DNS/TCP/TLS, Hello and Request/Reply all work at observation time.</p>
     */
    public ControlPlaneProbeResult probeOnce(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken) throws Exception {
        if (!gatewayUrls.isEmpty() || activeConnection.get() != null
                || maintenanceTask != null) {
            throw new IllegalStateException(
                    "probeOnce requires a fresh SocketDTransport instance");
        }
        configureEndpoint(serverAddresses, namespace, group, accessToken);
        GatewayConnection connection = acquireAvailableConnection(
                deadlineAfter(options.operationTimeoutMs()));
        if (connection.validationProbe == null) {
            throw new ProtocolException("Gateway connection has no validation Probe");
        }
        return connection.validationProbe;
    }

    private void configureEndpoint(
            List<String> serverAddresses,
            String namespace,
            String group,
            String accessToken) {
        if (serverAddresses == null || serverAddresses.isEmpty()) {
            throw new IllegalArgumentException("serverAddresses must not be empty");
        }
        this.namespace = requireName("namespace", namespace);
        this.group = requireName("group", group);
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.gatewayUrls = buildGatewayUrls(serverAddresses);
        this.terminalFailure = null;
        this.closed = false;
    }

    @Override
    public ConfigGroupSnapshot snapshot(
            Collection<String> dataIds, long minEventRevision) {
        List<String> normalizedDataIds = normalizeDataIds(dataIds);
        if (normalizedDataIds.isEmpty()) {
            return new ConfigGroupSnapshot(0L, 0L, Map.of());
        }
        try {
            return execute("config/snapshot", (connection, timeoutMs) -> {
                ConfigSnapshotRequest.Builder payload = ConfigSnapshotRequest.newBuilder();
                normalizedDataIds.forEach(dataId -> payload.addConfigs(coordinate(dataId)));
                Envelope response = request(
                        connection.session,
                        ControlPlaneProtocol.CONFIG_SNAPSHOT,
                        businessEnvelope(
                                RevisionType.CONFIG_EVENT,
                                0L,
                                Math.max(0L, minEventRevision),
                                ControlPlaneProtocol.CONFIG_SNAPSHOT_REQUEST_TYPE,
                                payload.build().toByteString(),
                                timeoutMs),
                        timeoutMs);
                requireOk(response, ControlPlaneProtocol.CONFIG_SNAPSHOT_RESPONSE_TYPE);
                ConfigSnapshotResponse snapshot =
                        ConfigSnapshotResponse.parseFrom(response.getPayload());
                if (snapshot.getEventRevision() < minEventRevision) {
                    throw new ProtocolException("Config event revision moved backwards");
                }
                Map<String, Long> revisions = new LinkedHashMap<>();
                for (ConfigDecisionSummary decision : snapshot.getDecisionsList()) {
                    validateCoordinate(decision.getConfig(), decision.getConfig().getDataId());
                    if (!normalizedDataIds.contains(decision.getConfig().getDataId())) {
                        throw new ProtocolException("Snapshot contains an unrequested config");
                    }
                    revisions.merge(
                            decision.getConfig().getDataId(),
                            decision.getDecisionRevision(),
                            Math::max);
                }
                return new ConfigGroupSnapshot(
                        snapshot.getEventRevision(),
                        snapshot.getCompactionRevision(),
                        revisions);
            });
        } catch (Exception e) {
            warnRateLimited("Config snapshot failed; retaining last-known-good values", e);
            return null;
        }
    }

    @Override
    public ConfigWatchBatch watchBatch(
            Collection<String> dataIds,
            long afterEventRevision,
            int maxBatchSize) {
        List<String> normalizedDataIds = normalizeDataIds(dataIds);
        int batchSize = maxBatchSize <= 0 ? DEFAULT_WATCH_BATCH_SIZE : maxBatchSize;
        try {
            return execute("config/watch-batch", (connection, timeoutMs) -> {
                ConfigWatchBatchRequest.Builder payload = ConfigWatchBatchRequest.newBuilder()
                        .setAfterEventRevision(Math.max(0L, afterEventRevision))
                        .setGroupName(group)
                        .setMaxBatchSize(batchSize);
                normalizedDataIds.forEach(dataId -> payload.addConfigs(coordinate(dataId)));
                Envelope response = request(
                        connection.session,
                        ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                        businessEnvelope(
                                RevisionType.CONFIG_EVENT,
                                Math.max(0L, afterEventRevision),
                                Math.max(0L, afterEventRevision),
                                ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE,
                                payload.build().toByteString(),
                                timeoutMs),
                        timeoutMs);
                requireOk(response, ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE);
                ConfigWatchBatchResponse batch =
                        ConfigWatchBatchResponse.parseFrom(response.getPayload());
                if (batch.getRequestedAfterRevision() != afterEventRevision) {
                    throw new ProtocolException("Watch response cursor does not match request");
                }
                if (batch.getCoveredThroughRevision() < afterEventRevision) {
                    throw new ProtocolException("Watch response cursor moved backwards");
                }
                List<ConfigInvalidation> events = new ArrayList<>();
                long previousEventRevision = afterEventRevision;
                for (cloud.xuantong.protocol.v2.ConfigInvalidation event
                        : batch.getEventsList()) {
                    validateCoordinate(event.getConfig(), event.getConfig().getDataId());
                    if (!normalizedDataIds.isEmpty()
                            && !normalizedDataIds.contains(event.getConfig().getDataId())) {
                        throw new ProtocolException("Watch contains an unrequested config");
                    }
                    if (event.getEventRevision() <= previousEventRevision
                            || event.getEventRevision() > batch.getCoveredThroughRevision()) {
                        throw new ProtocolException("Watch event revisions are not monotonic");
                    }
                    previousEventRevision = event.getEventRevision();
                    events.add(new ConfigInvalidation(
                            event.getConfig().getDataId(),
                            event.getEventRevision(),
                            event.getDecisionRevision()));
                }
                return new ConfigWatchBatch(
                        batch.getRequestedAfterRevision(),
                        batch.getCoveredThroughRevision(),
                        batch.getCompactionRevision(),
                        batch.getResetRequired(),
                        events);
            });
        } catch (Exception e) {
            warnRateLimited("Config Watch-Batch failed; retaining last-known-good values", e);
            return null;
        }
    }

    @Override
    public WatchSubscription subscribe(
            long afterEventRevision,
            WatchBatchHandler<ConfigWatchBatch> handler) {
        WatchRegistration<ConfigWatchBatch> registration = new WatchRegistration<>(
                "config",
                ControlPlaneProtocol.CONFIG_WATCH_BATCH,
                RevisionType.CONFIG_EVENT,
                Math.max(0L, afterEventRevision),
                cursor -> {
                    ConfigWatchBatchRequest payload = ConfigWatchBatchRequest.newBuilder()
                            .setAfterEventRevision(cursor)
                            .setGroupName(group)
                            .setMaxBatchSize(DEFAULT_WATCH_BATCH_SIZE)
                            .build();
                    return businessEnvelope(
                            RevisionType.CONFIG_EVENT,
                            cursor,
                            cursor,
                            ControlPlaneProtocol.CONFIG_WATCH_BATCH_REQUEST_TYPE,
                            payload.toByteString(),
                            options.requestTimeoutMs());
                },
                (response, expectedCursor) -> decodeConfigWatchBatch(
                        response, expectedCursor, List.of()),
                ConfigWatchBatch::requestedAfterRevision,
                ConfigWatchBatch::coveredThroughRevision,
                handler);
        return registerWatch(registration);
    }

    WatchSubscription subscribeDiscovery(
            String groupName,
            List<String> serviceNames,
            long afterRegistryRevision,
            WatchBatchHandler<cloud.xuantong.client.model.ServiceWatchBatch> handler,
            WatchDecoder<cloud.xuantong.client.model.ServiceWatchBatch> decoder) {
        String normalizedGroup = requireName("group", groupName);
        List<String> normalizedServices = serviceNames == null
                ? List.of()
                : serviceNames.stream()
                        .map(value -> requireName("serviceName", value))
                        .distinct()
                        .sorted()
                        .toList();
        WatchRegistration<cloud.xuantong.client.model.ServiceWatchBatch> registration =
                new WatchRegistration<>(
                        "discovery",
                        ControlPlaneProtocol.DISCOVERY_WATCH_BATCH,
                        RevisionType.REGISTRY,
                        Math.max(0L, afterRegistryRevision),
                        cursor -> {
                            cloud.xuantong.protocol.v2.DiscoveryWatchBatchRequest.Builder payload =
                                    cloud.xuantong.protocol.v2.DiscoveryWatchBatchRequest.newBuilder()
                                            .setAfterRegistryRevision(cursor)
                                            .setGroupName(normalizedGroup)
                                            .setMaxBatchSize(DEFAULT_WATCH_BATCH_SIZE);
                            payload.addAllServiceNames(normalizedServices);
                            return businessEnvelope(
                                    RevisionType.REGISTRY,
                                    cursor,
                                    cursor,
                                    ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_REQUEST_TYPE,
                                    payload.build().toByteString(),
                                    options.requestTimeoutMs());
                        },
                        decoder,
                        cloud.xuantong.client.model.ServiceWatchBatch::requestedAfterRevision,
                        cloud.xuantong.client.model.ServiceWatchBatch::coveredThroughRevision,
                        handler);
        return registerWatch(registration);
    }

    private ConfigWatchBatch decodeConfigWatchBatch(
            Envelope response,
            long expectedCursor,
            Collection<String> allowedDataIds) throws Exception {
        requireOk(response, ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE);
        ConfigWatchBatchResponse batch = ConfigWatchBatchResponse.parseFrom(response.getPayload());
        if (batch.getRequestedAfterRevision() != expectedCursor) {
            throw new ProtocolException("Watch response cursor does not match request");
        }
        if (batch.getCoveredThroughRevision() < expectedCursor) {
            throw new ProtocolException("Watch response cursor moved backwards");
        }
        Set<String> allowed = allowedDataIds == null
                ? Set.of() : Set.copyOf(allowedDataIds);
        List<ConfigInvalidation> events = new ArrayList<>();
        long previousEventRevision = expectedCursor;
        for (cloud.xuantong.protocol.v2.ConfigInvalidation event : batch.getEventsList()) {
            validateCoordinate(event.getConfig(), event.getConfig().getDataId());
            if (!allowed.isEmpty() && !allowed.contains(event.getConfig().getDataId())) {
                throw new ProtocolException("Watch contains an unrequested config");
            }
            if (event.getEventRevision() <= previousEventRevision
                    || event.getEventRevision() > batch.getCoveredThroughRevision()) {
                throw new ProtocolException("Watch event revisions are not monotonic");
            }
            previousEventRevision = event.getEventRevision();
            events.add(new ConfigInvalidation(
                    event.getConfig().getDataId(),
                    event.getEventRevision(),
                    event.getDecisionRevision()));
        }
        return new ConfigWatchBatch(
                batch.getRequestedAfterRevision(),
                batch.getCoveredThroughRevision(),
                batch.getCompactionRevision(),
                batch.getResetRequired(),
                events);
    }

    protected ClientSession openSession(
            int gatewayIndex,
            String url,
            EventListener listener,
            long connectTimeoutMs) throws Exception {
        return SocketD.createClient(url)
                .config(config -> {
                    config.heartbeatInterval(HEARTBEAT_INTERVAL_MS)
                            .codecThreads(1)
                            .connectTimeout(Math.max(1L, connectTimeoutMs))
                            .requestTimeout(options.requestTimeoutMs())
                            .workExecutor(
                                    ControlPlaneClientExecutors.socketdWorkExecutor())
                            .autoReconnect(true);
                    if (tlsContextFactory.enabled()) {
                        config.sslContext(tlsContextFactory.create(peerHost(url)));
                    }
                })
                .listen(listener)
                .openOrThow();
    }

    protected Envelope request(
            ClientSession session,
            String event,
            Envelope request,
            long timeoutMs) throws Exception {
        Reply reply = session.sendAndRequest(
                        event,
                        Entity.of(request.toByteArray()),
                        Math.max(1L, timeoutMs))
                .await();
        if (!reply.isEnd()) {
            throw new ProtocolException("Control-plane Request did not terminate with ReplyEnd");
        }
        Envelope response = Envelope.parseFrom(reply.dataAsBytes());
        if (!request.getRequestId().equals(response.getRequestId())) {
            throw new ProtocolException("Control-plane response requestId does not match");
        }
        return response;
    }

    Envelope invokeControlPlane(
            String operation,
            String event,
            RevisionType revisionType,
            long knownRevision,
            long minRevision,
            String operationId,
            String requestPayloadType,
            ByteString payload,
            String responsePayloadType) throws Exception {
        return execute(operation, (connection, timeoutMs) -> {
            Envelope response = request(
                    connection.session,
                    event,
                    businessEnvelope(
                            revisionType,
                            knownRevision,
                            minRevision,
                            operationId,
                            requestPayloadType,
                            payload,
                            timeoutMs),
                    timeoutMs);
            requireOk(response, responsePayloadType);
            return response;
        });
    }

    private <T> WatchSubscription registerWatch(WatchRegistration<T> registration) {
        if (registration.handler == null) {
            throw new IllegalArgumentException("Watch handler must not be null");
        }
        if (closed) {
            throw new IllegalStateException("Socket.D transport is closed");
        }
        watchRegistrations.add(registration);
        openPendingWatches(activeConnection.get());
        return registration;
    }

    private void openPendingWatches(GatewayConnection connection) {
        if (!isRoutable(connection) || closed) {
            return;
        }
        for (WatchRegistration<?> registration : watchRegistrations) {
            openWatchUnchecked(registration, connection);
        }
    }

    private void openWatchUnchecked(
            WatchRegistration<?> registration,
            GatewayConnection connection) {
        try {
            openWatch(registration, connection);
        } catch (Exception e) {
            registration.failed(e);
            if (isRetryable(e)) {
                retire(connection);
            }
        }
    }

    private <T> void openWatch(
            WatchRegistration<T> registration,
            GatewayConnection connection) throws Exception {
        synchronized (registration) {
            if (registration.closed.get()
                    || !isRoutable(connection)
                    || System.nanoTime() < registration.nextAttemptNanos) {
                return;
            }
            SubscribeStream current = registration.stream;
            if (registration.connection == connection
                    && current != null
                    && !current.isDone()) {
                return;
            }

            long cursor = registration.committedCursor.get();
            Envelope envelope = registration.requestFactory.create(cursor);
            SubscribeStream stream = connection.session.sendAndSubscribe(
                    registration.event,
                    Entity.of(envelope.toByteArray()),
                    WATCH_STREAM_TIMEOUT_MS);
            registration.connection = connection;
            registration.stream = stream;
            registration.streamCursor = cursor;
            registration.requestId = envelope.getRequestId();
            registration.nextAttemptNanos = 0L;
            stream.thenReply(reply -> submitWatchTask(
                            () -> handleWatchReply(registration, connection, reply)))
                    .thenError(error -> submitWatchTask(
                            () -> handleWatchError(registration, connection, error)));
        }
    }

    private <T> void handleWatchReply(
            WatchRegistration<T> registration,
            GatewayConnection connection,
            Reply reply) {
        if (registration.closed.get() || registration.connection != connection) {
            return;
        }
        try {
            Envelope response = Envelope.parseFrom(reply.dataAsBytes());
            if (!registration.requestId.equals(response.getRequestId())) {
                throw new ProtocolException("Watch response requestId does not match");
            }
            long expectedCursor = registration.streamCursor;
            T batch = registration.decoder.decode(response, expectedCursor);
            long requested = registration.requestedCursor.read(batch);
            long covered = registration.coveredCursor.read(batch);
            if (requested != expectedCursor || covered < requested) {
                throw new ProtocolException("Watch stream cursor is invalid");
            }
            registration.streamCursor = covered;
            long accepted = registration.handler.onBatch(batch);
            long previousCommitted = registration.committedCursor.get();
            if (accepted < previousCommitted) {
                throw new ProtocolException("Watch handler moved the committed cursor backwards");
            }
            registration.committedCursor.set(accepted);
            registration.failureBackoffMs = 1_000L;
            if (accepted != covered && !reply.isEnd()) {
                // The server stream has already advanced to covered. If the handler committed
                // less, replay from that lower cursor; if an authoritative Snapshot committed
                // more, restart from the higher cursor. Socket.D has no stream cancellation API,
                // so retiring the owning connection is the deterministic cutover.
                retire(connection);
                return;
            }
            if (!reply.isEnd()) {
                acknowledgeWatch(registration, connection, covered);
            }
            markRpcSuccess(connection);
            if (reply.isEnd()) {
                synchronized (registration) {
                    if (registration.connection == connection) {
                        registration.stream = null;
                        registration.connection = null;
                    }
                }
                registration.nextAttemptNanos = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(100L);
            }
        } catch (Exception e) {
            ControlPlaneStatusException status = statusException(e);
            if (status != null && status.code == ResponseCode.RATE_LIMITED) {
                registration.failed(e, status.retryAfterMs);
                synchronized (registration) {
                    if (registration.connection == connection) {
                        registration.stream = null;
                        registration.connection = null;
                    }
                }
                markRpcSuccess(connection);
                return;
            }
            if (isTerminalControlPlaneFailure(e)) {
                registration.failed(e);
                terminalFailure = e;
                retire(connection);
                return;
            }
            if (status != null && !status.retryable) {
                registration.failed(e);
                synchronized (registration) {
                    if (registration.connection == connection) {
                        registration.stream = null;
                        registration.connection = null;
                    }
                }
                markRpcSuccess(connection);
                return;
            }
            registration.failed(e);
            retire(connection);
        }
    }

    private void acknowledgeWatch(
            WatchRegistration<?> registration,
            GatewayConnection connection,
            long committedRevision) throws Exception {
        String subscriptionRequestId = registration.requestId;
        WatchAckRequest acknowledgement = WatchAckRequest.newBuilder()
                .setSubscriptionRequestId(subscriptionRequestId)
                .setCommittedRevision(committedRevision)
                .build();
        long timeoutMs = options.requestTimeoutMs();
        Envelope response = request(
                connection.session,
                ControlPlaneProtocol.SYSTEM_WATCH_ACK,
                businessEnvelope(
                        registration.revisionType,
                        committedRevision,
                        committedRevision,
                        ControlPlaneProtocol.WATCH_ACK_REQUEST_TYPE,
                        acknowledgement.toByteString(),
                        timeoutMs),
                timeoutMs);
        requireOk(response, ControlPlaneProtocol.WATCH_ACK_RESPONSE_TYPE);
        WatchAckResponse accepted = WatchAckResponse.parseFrom(response.getPayload());
        if (!subscriptionRequestId.equals(accepted.getSubscriptionRequestId())
                || accepted.getAcceptedRevision() != committedRevision) {
            throw new ProtocolException(
                    "Watch acknowledgement response does not match the committed batch");
        }
    }

    private void handleWatchError(
            WatchRegistration<?> registration,
            GatewayConnection connection,
            Throwable error) {
        if (registration.closed.get() || registration.connection != connection) {
            return;
        }
        registration.failed(error);
        synchronized (registration) {
            if (registration.connection == connection) {
                registration.stream = null;
                registration.connection = null;
            }
        }
        if (!(error instanceof SocketDTimeoutException) || !isRoutable(connection)) {
            retire(connection);
        }
    }

    private void submitWatchTask(Runnable task) {
        ExecutorService executor = watchExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RuntimeException e) {
            logger.debug("Control-plane Watch executor rejected a task", e);
        }
    }

    int activeSessionCount() {
        GatewayConnection connection = activeConnection.get();
        return isRoutable(connection) ? 1 : 0;
    }

    int activeGatewayIndex() {
        GatewayConnection connection = activeConnection.get();
        return isRoutable(connection) ? connection.gatewayIndex : -1;
    }

    public ControlPlaneTransportMetricsSnapshot metricsSnapshot() {
        int registeredWatches = 0;
        int activeSubscribeStreams = 0;
        for (WatchRegistration<?> registration : watchRegistrations) {
            if (registration.closed.get()) {
                continue;
            }
            registeredWatches++;
            synchronized (registration) {
                SubscribeStream stream = registration.stream;
                if (stream != null && !stream.isDone()) {
                    activeSubscribeStreams++;
                }
            }
        }
        return new ControlPlaneTransportMetricsSnapshot(
                activeSessionCount(),
                inFlightRequests.get(),
                registeredWatches,
                activeSubscribeStreams,
                closed);
    }

    private <T> T execute(String operation, RpcCall<T> call) throws Exception {
        if (closed) {
            throw new IllegalStateException("Socket.D transport is closed");
        }
        if (terminalFailure != null) {
            throw new SocketDConnectionException(
                    "Control-plane connection is disabled after a terminal capability, protocol, "
                            + "or authentication failure");
        }
        long deadline = deadlineAfter(options.operationTimeoutMs());
        Set<Integer> attempted = new LinkedHashSet<>();
        Exception lastFailure = null;
        while (attempted.size() < Math.min(MAX_ADDRESS_ATTEMPTS, gatewayUrls.size())) {
            GatewayConnection connection;
            try {
                connection = acquireConnection(deadline, attempted);
            } catch (Exception e) {
                lastFailure = e;
                if (isTerminalControlPlaneFailure(e) && !isCapabilityFailure(e)) {
                    terminalFailure = e;
                    throw e;
                }
                ControlPlaneStatusException status = statusException(e);
                if (status != null && status.code == ResponseCode.RATE_LIMITED) {
                    throw e;
                }
                if (remainingMillis(deadline) <= 0) {
                    break;
                }
                continue;
            }

            long timeoutMs = requestBudget(deadline);
            if (connection.serverMaxRequestBudgetMs > 0) {
                timeoutMs = Math.min(timeoutMs, connection.serverMaxRequestBudgetMs);
            }
            if (timeoutMs <= 0) {
                lastFailure = new SocketDTimeoutException(
                        "Control-plane total deadline exceeded before " + operation);
                break;
            }
            inFlightRequests.incrementAndGet();
            try {
                T result = call.invoke(connection, timeoutMs);
                markRpcSuccess(connection);
                return result;
            } catch (Exception e) {
                lastFailure = e;
                ControlPlaneStatusException status = statusException(e);
                if (status != null && status.code == ResponseCode.RATE_LIMITED) {
                    // RATE_LIMITED is a successful RPC-health exchange. Retrying another
                    // Gateway would multiply tenant traffic and bypass per-Gateway quotas.
                    markRpcSuccess(connection);
                    throw e;
                }
                if (isTerminalControlPlaneFailure(e)) {
                    terminalFailure = e;
                    markRpcFailure(connection, e);
                    retire(connection);
                    throw e;
                }
                if (status != null && !status.retryable) {
                    // A structured non-retryable response proves the Request/Reply path is
                    // healthy. It is an application or capability error, not a reason to
                    // discard the physical Socket.D Session.
                    markRpcSuccess(connection);
                    throw e;
                }
                markRpcFailure(connection, e);
                if (!isRetryable(e)) {
                    throw e;
                }
                retire(connection);
            } finally {
                if (inFlightRequests.decrementAndGet() == 0) {
                    synchronized (drainMonitor) {
                        drainMonitor.notifyAll();
                    }
                }
            }
        }
        if (lastFailure != null) {
            if (isCapabilityFailure(lastFailure)) {
                terminalFailure = lastFailure;
            }
            throw lastFailure;
        }
        throw new SocketDConnectionException("No control-plane Gateway address is available");
    }

    private GatewayConnection acquireConnection(
            long deadline, Set<Integer> attempted) throws Exception {
        lifecycleLock.lockInterruptibly();
        try {
            if (closed) {
                throw new IllegalStateException("Socket.D transport is closed");
            }
            GatewayConnection current = activeConnection.get();
            if (isRoutable(current) && !attempted.contains(current.gatewayIndex)) {
                attempted.add(current.gatewayIndex);
                return current;
            }
            if (current != null && !isRoutable(current)) {
                retireLocked(current);
            }

            int gatewayIndex = selectNextAddress(attempted);
            if (gatewayIndex < 0) {
                throw new SocketDConnectionException(
                        "No untried control-plane Gateway address remains");
            }
            attempted.add(gatewayIndex);
            if (remainingMillis(deadline) <= 0) {
                throw new SocketDTimeoutException(
                        "Control-plane total deadline exceeded while connecting");
            }

            GatewayConnection candidate;
            try {
                candidate = openAndValidate(gatewayIndex, deadline);
            } catch (Exception e) {
                nextAddressIndex = (gatewayIndex + 1) % gatewayUrls.size();
                throw e;
            }
            activeConnection.set(candidate);
            nextAddressIndex = (gatewayIndex + 1) % gatewayUrls.size();
            boolean reconnect = everActive;
            everActive = true;
            if (reconnect) {
                notifyReconnectListener();
            }
            logger.info("Connected to Xuantong 2.0 control-plane Gateway {}: "
                            + "gatewayId={}, clusterId={}, transportGeneration={}, sessionId={}",
                    gatewayIndex,
                    candidate.gatewayId,
                    pinnedClusterId,
                    pinnedTransportGeneration,
                    candidate.session.sessionId());
            openPendingWatches(candidate);
            return candidate;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private GatewayConnection openAndValidate(int gatewayIndex, long deadline) throws Exception {
        GatewayConnection candidate = new GatewayConnection(
                gatewayIndex, gatewayUrls.get(gatewayIndex));
        EventListener listener = new EventListener()
                .doOnOpen(session -> {
                    if (candidate.helloComplete) {
                        // Socket.D may transparently reconnect the stable shell. The new
                        // server Session has not completed Xuantong Hello and must not
                        // inherit ACTIVE from the previous physical connection.
                        candidate.state = ConnectionState.SUSPECT;
                    }
                })
                .doOnClose(session -> handleSessionClosed(candidate))
                .doOnError((session, error) -> handleSessionError(candidate, error));
        try {
            ClientSession stableSession = openSession(
                    gatewayIndex,
                    candidate.url,
                    listener,
                    Math.min(options.connectTimeoutMs(), remainingMillis(deadline)));
            candidate.session = stableSession;
            if (!stableSession.isActive() || stableSession.isClosing()) {
                throw new SocketDConnectionException(
                        "Gateway session is not active after open");
            }

            HelloResponse hello = hello(candidate, requestBudget(deadline));
            pinTopology(hello);
            validateServerCapabilities(hello);
            candidate.gatewayId = hello.getGatewayId();
            candidate.connectionGeneration = hello.getConnectionGeneration();
            candidate.serverMaxRequestBudgetMs = hello.getMaxRequestBudgetMs();

            ControlPlaneProbeResult probe = performProbe(
                    candidate, requestBudget(deadline));
            if (probe.connectionGeneration() != candidate.connectionGeneration
                    || !probe.gatewayId().equals(candidate.gatewayId)) {
                throw new ProtocolException(
                        "Probe response does not match the Hello connection");
            }
            candidate.state = ConnectionState.ACTIVE;
            candidate.helloComplete = true;
            candidate.validationProbe = probe;
            candidate.lastRpcSuccessNanos = System.nanoTime();
            return candidate;
        } catch (Exception e) {
            candidate.state = ConnectionState.CLOSED;
            closeQuietly(candidate.session);
            throw e;
        }
    }

    private HelloResponse hello(GatewayConnection connection, long timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            throw new SocketDTimeoutException("No remaining budget for system/hello");
        }
        HelloRequest.Builder hello = HelloRequest.newBuilder()
                .setClientInstanceId(identity.getClientInstanceId())
                .setApplicationName(identity.getApplicationName())
                .setClientVersion(ClientIdentity.CLIENT_VERSION)
                .setSdkName("xuantong-client-java")
                .addSupportedProtocolVersions(ControlPlaneProtocol.CURRENT_VERSION)
                .addCapabilities("protobuf-envelope-v2")
                .setTransportPool(options.transportPool())
                .setGroupName(group)
                .setCredential(accessToken);
        hello.addAllCapabilities(clientProfile.requiredCapabilities);
        Envelope response = request(
                connection.session,
                ControlPlaneProtocol.SYSTEM_HELLO,
                baseEnvelope(timeoutMs)
                        .setPayloadType(ControlPlaneProtocol.HELLO_REQUEST_TYPE)
                        .setPayload(hello.build().toByteString())
                        .build(),
                timeoutMs);
        requireOk(response, ControlPlaneProtocol.HELLO_RESPONSE_TYPE);
        HelloResponse result = HelloResponse.parseFrom(response.getPayload());
        if (result.getSelectedProtocolVersion() != ControlPlaneProtocol.CURRENT_VERSION) {
            throw new ProtocolException("Gateway selected an unsupported protocol version");
        }
        if (result.getGatewayId().isBlank() || result.getConnectionGeneration() == 0) {
            throw new ProtocolException("Gateway Hello response is incomplete");
        }
        return result;
    }

    private void validateServerCapabilities(HelloResponse hello)
            throws ControlPlaneCapabilityException {
        Set<String> available = new LinkedHashSet<>(hello.getCapabilitiesList());
        List<String> missing = clientProfile.requiredCapabilities.stream()
                .filter(capability -> !available.contains(capability))
                .toList();
        if (!missing.isEmpty()) {
            throw new ControlPlaneCapabilityException(
                    "Gateway " + hello.getGatewayId()
                            + " does not provide required " + clientProfile.displayName
                            + " capabilities " + missing
                            + "; verify that the corresponding State Plane is enabled");
        }
    }

    private ControlPlaneProbeResult performProbe(
            GatewayConnection connection, long timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            throw new SocketDTimeoutException("No remaining budget for system/probe");
        }
        String nonce = UUID.randomUUID().toString();
        long clientSendEpochMs = System.currentTimeMillis();
        ProbeRequest probe = ProbeRequest.newBuilder()
                .setNonce(nonce)
                .setClientSendEpochMs(clientSendEpochMs)
                .build();
        long startedAtNanos = System.nanoTime();
        Envelope response = request(
                connection.session,
                ControlPlaneProtocol.SYSTEM_PROBE,
                baseEnvelope(timeoutMs)
                        .setPayloadType(ControlPlaneProtocol.PROBE_REQUEST_TYPE)
                        .setPayload(probe.toByteString())
                        .build(),
                timeoutMs);
        long rpcDurationNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
        long clientReceiveEpochMs = System.currentTimeMillis();
        requireOk(response, ControlPlaneProtocol.PROBE_RESPONSE_TYPE);
        ProbeResponse result = ProbeResponse.parseFrom(response.getPayload());
        if (!nonce.equals(result.getNonce())) {
            throw new ProtocolException("Gateway Probe nonce does not match");
        }
        if (result.getConnectionGeneration() != connection.connectionGeneration
                || !result.getGatewayId().equals(connection.gatewayId)) {
            throw new ProtocolException(
                    "Probe response does not match the Hello connection");
        }
        return new ControlPlaneProbeResult(
                result.getGatewayId(),
                pinnedClusterId,
                pinnedTransportGeneration,
                result.getConnectionGeneration(),
                connection.url,
                connection.gatewayIndex,
                rpcDurationNanos,
                clientSendEpochMs,
                clientReceiveEpochMs,
                result.getServerReceiveEpochMs(),
                result.getServerSendEpochMs());
    }

    private Envelope.Builder baseEnvelope(long remainingBudgetMs) {
        return Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId(pinnedClusterId == null ? "" : pinnedClusterId)
                .setTransportGeneration(pinnedTransportGeneration)
                .setRequestId(UUID.randomUUID().toString())
                .setTraceId(UUID.randomUUID().toString())
                .setTenant(options.tenant())
                .setNamespaceId(namespace)
                .setRemainingBudgetMs(Math.max(1L, remainingBudgetMs));
    }

    private Envelope businessEnvelope(
            RevisionType revisionType,
            long knownRevision,
            long minRevision,
            String payloadType,
            ByteString payload,
            long remainingBudgetMs) {
        return businessEnvelope(
                revisionType,
                knownRevision,
                minRevision,
                "",
                payloadType,
                payload,
                remainingBudgetMs);
    }

    private Envelope businessEnvelope(
            RevisionType revisionType,
            long knownRevision,
            long minRevision,
            String operationId,
            String payloadType,
            ByteString payload,
            long remainingBudgetMs) {
        return baseEnvelope(remainingBudgetMs)
                .setRevisionType(revisionType)
                .setGroupId(options.stateGroupId())
                .setKnownRevision(knownRevision)
                .setMinRevision(minRevision)
                .setOperationId(operationId == null ? "" : operationId)
                .setPayloadType(payloadType)
                .setPayload(payload)
                .build();
    }

    void requireOk(Envelope response, String expectedPayloadType) throws Exception {
        if (response.getProtocolVersion() != ControlPlaneProtocol.CURRENT_VERSION) {
            throw new ProtocolException("Unsupported response protocol version");
        }
        if (pinnedClusterId != null && !pinnedClusterId.isBlank()
                && pinnedTransportGeneration != 0) {
            validatePinnedTopology(response.getClusterId(), response.getTransportGeneration());
        }
        ResponseStatus status = response.getResponseStatus();
        if (status.getCode() != ResponseCode.OK) {
            throw new ControlPlaneStatusException(status);
        }
        if (!expectedPayloadType.equals(response.getPayloadType())) {
            throw new ProtocolException(
                    "Expected payloadType " + expectedPayloadType
                            + " but received " + response.getPayloadType());
        }
    }

    private void pinTopology(HelloResponse hello) throws ProtocolException {
        if (hello.getClusterId().isBlank() || hello.getTransportGeneration() == 0) {
            throw new ProtocolException("Gateway topology identity is incomplete");
        }
        if (pinnedClusterId == null || pinnedClusterId.isBlank()) {
            pinnedClusterId = hello.getClusterId();
        }
        if (pinnedTransportGeneration == 0) {
            pinnedTransportGeneration = hello.getTransportGeneration();
        }
        validatePinnedTopology(hello.getClusterId(), hello.getTransportGeneration());
    }

    private void validatePinnedTopology(String clusterId, long transportGeneration)
            throws ProtocolException {
        if (!pinnedClusterId.equals(clusterId)) {
            throw new ProtocolException(
                    "Automatic failover attempted to cross cluster boundary: expected="
                            + pinnedClusterId + ", actual=" + clusterId);
        }
        if (pinnedTransportGeneration != transportGeneration) {
            throw new ProtocolException(
                    "Automatic failover attempted to cross transport generation boundary: expected="
                            + pinnedTransportGeneration + ", actual=" + transportGeneration);
        }
    }

    private void validateCoordinate(ConfigCoordinate coordinate, String dataId)
            throws ProtocolException {
        if (!namespace.equals(coordinate.getNamespaceId())
                || !group.equals(coordinate.getGroupName())
                || !dataId.equals(coordinate.getDataId())) {
            throw new ProtocolException("Config response coordinate is outside the request scope");
        }
    }

    private ConfigCoordinate coordinate(String dataId) {
        return ConfigCoordinate.newBuilder()
                .setNamespaceId(namespace)
                .setGroupName(group)
                .setDataId(dataId)
                .build();
    }

    private List<String> normalizeDataIds(Collection<String> dataIds) {
        if (dataIds == null || dataIds.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String dataId : dataIds) {
            unique.add(requireName("dataId", dataId));
        }
        return List.copyOf(unique);
    }

    private int selectNextAddress(Set<Integer> attempted) {
        if (gatewayUrls.isEmpty()) {
            return -1;
        }
        for (int offset = 0; offset < gatewayUrls.size(); offset++) {
            int candidate = (nextAddressIndex + offset) % gatewayUrls.size();
            if (!attempted.contains(candidate)) {
                return candidate;
            }
        }
        return -1;
    }

    private void markRpcSuccess(GatewayConnection connection) {
        connection.consecutiveRpcFailures = 0;
        connection.lastRpcSuccessNanos = System.nanoTime();
        if (connection.state == ConnectionState.SUSPECT) {
            connection.state = ConnectionState.ACTIVE;
        }
    }

    private void markRpcFailure(GatewayConnection connection, Exception failure) {
        connection.consecutiveRpcFailures++;
        connection.lastFailure = failure;
        connection.state = ConnectionState.SUSPECT;
    }

    private void handleSessionClosed(GatewayConnection connection) {
        connection.state = ConnectionState.CLOSED;
        activeConnection.compareAndSet(connection, null);
        detachWatches(connection);
    }

    private void handleSessionError(GatewayConnection connection, Throwable error) {
        connection.lastFailure = error;
        logger.debug("Socket.D control-plane Gateway {} session error",
                connection.gatewayIndex, error);
    }

    private void maintenanceSafely() {
        if (closed || terminalFailure != null) {
            return;
        }
        try {
            if (tlsContextFactory.reloadRequired()) {
                GatewayConnection rotating = activeConnection.get();
                if (rotating != null) {
                    logger.info("Xuantong TLS material changed; rebuilding control-plane "
                            + "connection within the configured reload interval");
                    retire(rotating);
                }
            }
            GatewayConnection current = activeConnection.get();
            if (current != null) {
                if (current.state == ConnectionState.SUSPECT
                        || current.state == ConnectionState.CLOSED
                        || current.session == null
                        || !current.session.isValid()) {
                    retire(current);
                } else if (current.session.isClosing()) {
                    if (current.state != ConnectionState.DRAINING) {
                        current.state = ConnectionState.DRAINING;
                        current.closingSinceNanos = System.nanoTime();
                    }
                    if (elapsedMillis(current.closingSinceNanos)
                            >= options.closingTimeoutMs()) {
                        logger.info("Forcing final close for draining control-plane Gateway {}",
                                current.gatewayIndex);
                        retire(current);
                    }
                }
            }
            if (activeConnection.get() == null) {
                acquireAvailableConnection(deadlineAfter(options.operationTimeoutMs()));
            } else {
                openPendingWatches(activeConnection.get());
            }
        } catch (Exception e) {
            warnRateLimited("Control-plane background reconnect failed", e);
        }
    }

    private void retire(GatewayConnection connection) {
        lifecycleLock.lock();
        try {
            retireLocked(connection);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private GatewayConnection acquireAvailableConnection(long deadline) throws Exception {
        Set<Integer> attempted = new LinkedHashSet<>();
        Exception lastFailure = null;
        while (attempted.size() < Math.min(MAX_ADDRESS_ATTEMPTS, gatewayUrls.size())
                && remainingMillis(deadline) > 0) {
            try {
                return acquireConnection(deadline, attempted);
            } catch (Exception e) {
                lastFailure = e;
                if (isTerminalControlPlaneFailure(e) && !isCapabilityFailure(e)) {
                    terminalFailure = e;
                    throw e;
                }
                ControlPlaneStatusException status = statusException(e);
                if (status != null && status.code == ResponseCode.RATE_LIMITED) {
                    throw e;
                }
            }
        }
        if (lastFailure != null) {
            if (isCapabilityFailure(lastFailure)) {
                terminalFailure = lastFailure;
            }
            throw lastFailure;
        }
        throw new SocketDConnectionException("No control-plane Gateway address is available");
    }

    private void retireLocked(GatewayConnection connection) {
        if (connection == null) {
            return;
        }
        activeConnection.compareAndSet(connection, null);
        connection.state = ConnectionState.CLOSED;
        detachWatches(connection);
        closeQuietly(connection.session);
        if (!gatewayUrls.isEmpty()) {
            nextAddressIndex = (connection.gatewayIndex + 1) % gatewayUrls.size();
        }
    }

    private void detachWatches(GatewayConnection connection) {
        for (WatchRegistration<?> registration : watchRegistrations) {
            synchronized (registration) {
                if (registration.connection == connection) {
                    registration.connection = null;
                    registration.stream = null;
                }
            }
        }
    }

    private boolean isRoutable(GatewayConnection connection) {
        return connection != null
                && connection.state == ConnectionState.ACTIVE
                && connection.session != null
                && connection.session.isActive()
                && !connection.session.isClosing();
    }

    private boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ControlPlaneStatusException statusException) {
                return statusException.retryable;
            }
            if (current instanceof ProtocolException
                    || current instanceof InvalidProtocolBufferException
                    || current instanceof SocketDTimeoutException
                    || current instanceof SocketDChannelException
                    || current instanceof SocketDConnectionException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ControlPlaneStatusException statusException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ControlPlaneStatusException statusException) {
                return statusException;
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean isTerminalControlPlaneFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ControlPlaneCapabilityException) {
                return true;
            }
            if (current instanceof ControlPlaneStatusException statusException) {
                return statusException.code == ResponseCode.UNAUTHORIZED
                        || statusException.code == ResponseCode.UNSUPPORTED_PROTOCOL
                        || statusException.code == ResponseCode.CLUSTER_MISMATCH
                        || statusException.code
                        == ResponseCode.TRANSPORT_GENERATION_MISMATCH;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isCapabilityFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ControlPlaneCapabilityException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<String> buildGatewayUrls(List<String> addresses) {
        Set<String> unique = new LinkedHashSet<>();
        for (String rawAddress : addresses) {
            if (rawAddress == null || rawAddress.isBlank()) {
                continue;
            }
            String address = rawAddress.trim();
            if (address.startsWith("tcp://")) {
                address = "sd:" + address;
            } else if (!address.startsWith("sd:")) {
                if (address.contains("://")) {
                    throw new IllegalArgumentException(
                            "Xuantong 2.0 control plane requires sd:tcp addresses: " + address);
                }
                address = "sd:tcp://" + address;
            }
            if (!address.startsWith("sd:tcp://")) {
                throw new IllegalArgumentException(
                        "Xuantong 2.0 control plane requires native sd:tcp: " + address);
            }
            if (address.contains("?")) {
                throw new IllegalArgumentException(
                        "Control-plane address must not contain query parameters");
            }
            int authorityStart = "sd:tcp://".length();
            int pathStart = address.indexOf('/', authorityStart);
            if (pathStart < 0) {
                address += ControlPlaneProtocol.CONTROL_PATH;
            } else {
                String path = address.substring(pathStart);
                if (!ControlPlaneProtocol.CONTROL_PATH.equals(path)) {
                    throw new IllegalArgumentException(
                            "Xuantong 2.0 control-plane path must be "
                                    + ControlPlaneProtocol.CONTROL_PATH + ": " + address);
                }
            }
            unique.add(address);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("serverAddresses must not be blank");
        }
        return List.copyOf(unique);
    }

    private String peerHost(String url) {
        URI uri = URI.create(url.startsWith("sd:") ? url.substring(3) : url);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Control-plane address has no host: " + url);
        }
        return host;
    }

    private long requestBudget(long deadline) {
        long remaining = remainingMillis(deadline);
        if (remaining <= 0) {
            return 0L;
        }
        return Math.max(1L, Math.min(options.requestTimeoutMs(), remaining));
    }

    private long deadlineAfter(long timeoutMs) {
        long now = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        return now > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void notifyReconnectListener() {
        if (closed) {
            return;
        }
        for (Runnable listener : reconnectListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                logger.warn("Control-plane reconnect listener failed", e);
            }
        }
    }

    private void warnRateLimited(String message, Throwable error) {
        long now = System.currentTimeMillis();
        long previous = lastWarningAt.get();
        if (now - previous >= WARNING_INTERVAL_MS
                && lastWarningAt.compareAndSet(previous, now)) {
            logger.warn("{}: {}", message, rootMessage(error));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "unknown" : current.toString();
    }

    private String requireName(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value;
    }

    private void precloseQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            if (session.isActive() && !session.isClosing()) {
                session.preclose();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setOnReconnect(Runnable listener) {
        reconnectListeners.clear();
        if (listener != null) {
            reconnectListeners.add(listener);
        }
    }

    AutoCloseable addReconnectListener(Runnable listener) {
        if (listener == null) {
            return () -> { };
        }
        reconnectListeners.add(listener);
        return () -> reconnectListeners.remove(listener);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> scheduled = maintenanceTask;
        maintenanceTask = null;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        for (WatchRegistration<?> registration : watchRegistrations) {
            registration.close();
        }
        watchRegistrations.clear();
        ExecutorService watches = watchExecutor;
        if (watches != null) {
            watches.shutdownNow();
        }
        reconnectListeners.clear();

        GatewayConnection connection;
        lifecycleLock.lock();
        try {
            connection = activeConnection.getAndSet(null);
        } finally {
            lifecycleLock.unlock();
        }
        if (connection != null) {
            connection.state = ConnectionState.DRAINING;
            precloseQuietly(connection.session);
            long deadline = deadlineAfter(options.closingTimeoutMs());
            synchronized (drainMonitor) {
                while (inFlightRequests.get() > 0 && remainingMillis(deadline) > 0) {
                    try {
                        drainMonitor.wait(Math.min(100L, remainingMillis(deadline)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            connection.state = ConnectionState.CLOSED;
            closeQuietly(connection.session);
        }
    }

    private enum ConnectionState {
        CONNECTING,
        ACTIVE,
        SUSPECT,
        DRAINING,
        CLOSED
    }

    private enum ClientProfile {
        CONFIG("Config", CONFIG_CAPABILITIES),
        DISCOVERY("Discovery", DISCOVERY_CAPABILITIES);

        private final String displayName;
        private final List<String> requiredCapabilities;

        ClientProfile(String displayName, List<String> requiredCapabilities) {
            this.displayName = displayName;
            this.requiredCapabilities = requiredCapabilities;
        }
    }

    private static final class GatewayConnection {
        private final int gatewayIndex;
        private final String url;
        private volatile ConnectionState state = ConnectionState.CONNECTING;
        private volatile ClientSession session;
        private volatile boolean helloComplete;
        private volatile String gatewayId = "";
        private volatile long connectionGeneration;
        private volatile long serverMaxRequestBudgetMs;
        private volatile ControlPlaneProbeResult validationProbe;
        private volatile long lastRpcSuccessNanos;
        private volatile long closingSinceNanos;
        private volatile int consecutiveRpcFailures;
        private volatile Throwable lastFailure;

        private GatewayConnection(int gatewayIndex, String url) {
            this.gatewayIndex = gatewayIndex;
            this.url = url;
        }
    }

    @FunctionalInterface
    private interface RpcCall<T> {
        T invoke(GatewayConnection connection, long timeoutMs) throws Exception;
    }

    @FunctionalInterface
    private interface WatchRequestFactory {
        Envelope create(long cursor) throws Exception;
    }

    @FunctionalInterface
    interface WatchDecoder<T> {
        T decode(Envelope response, long expectedCursor) throws Exception;
    }

    @FunctionalInterface
    private interface CursorReader<T> {
        long read(T value);
    }

    private final class WatchRegistration<T> implements WatchSubscription {
        private final String name;
        private final String event;
        private final RevisionType revisionType;
        private final WatchRequestFactory requestFactory;
        private final WatchDecoder<T> decoder;
        private final CursorReader<T> requestedCursor;
        private final CursorReader<T> coveredCursor;
        private final WatchBatchHandler<T> handler;
        private final AtomicLong committedCursor;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile GatewayConnection connection;
        private volatile SubscribeStream stream;
        private volatile String requestId = "";
        private volatile long streamCursor;
        private volatile long nextAttemptNanos;
        private volatile long failureBackoffMs = 1_000L;

        private WatchRegistration(
                String name,
                String event,
                RevisionType revisionType,
                long initialCursor,
                WatchRequestFactory requestFactory,
                WatchDecoder<T> decoder,
                CursorReader<T> requestedCursor,
                CursorReader<T> coveredCursor,
                WatchBatchHandler<T> handler) {
            this.name = name;
            this.event = event;
            this.revisionType = revisionType;
            this.requestFactory = requestFactory;
            this.decoder = decoder;
            this.requestedCursor = requestedCursor;
            this.coveredCursor = coveredCursor;
            this.handler = handler;
            this.committedCursor = new AtomicLong(initialCursor);
            this.streamCursor = initialCursor;
        }

        private void failed(Throwable error) {
            failed(error, 0L);
        }

        private void failed(Throwable error, long requestedDelayMs) {
            if (closed.get() || SocketDTransport.this.closed) {
                return;
            }
            try {
                handler.onError(error);
            } catch (RuntimeException handlerFailure) {
                logger.debug("{} Watch error handler failed", name, handlerFailure);
            }
            long delay = requestedDelayMs > 0L
                    ? Math.min(30_000L, Math.max(1L, requestedDelayMs))
                    : Math.min(30_000L, Math.max(1_000L, failureBackoffMs));
            nextAttemptNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(delay);
            if (requestedDelayMs <= 0L) {
                failureBackoffMs = Math.min(30_000L, delay * 2L);
            }
            warnRateLimited(name + " Watch subscription failed; it will resume from cursor "
                    + committedCursor.get(), error);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            watchRegistrations.remove(this);
            GatewayConnection current = connection;
            connection = null;
            stream = null;
            if (!SocketDTransport.this.closed && current != null) {
                retire(current);
            }
        }
    }

    private static final class ProtocolException extends Exception {
        private ProtocolException(String message) {
            super(message);
        }
    }

    private static final class ControlPlaneCapabilityException extends Exception {
        private ControlPlaneCapabilityException(String message) {
            super(message);
        }
    }

    static final class ControlPlaneStatusException extends Exception {
        private final ResponseCode code;
        private final boolean retryable;
        private final long retryAfterMs;

        private ControlPlaneStatusException(ResponseStatus status) {
            super(status.getCode() + ": " + status.getMessage());
            this.code = status.getCode();
            this.retryable = status.getRetryable();
            this.retryAfterMs = status.getRetryAfterMs();
        }

        ResponseCode code() {
            return code;
        }

        boolean retryable() {
            return retryable;
        }

        long retryAfterMs() {
            return retryAfterMs;
        }
    }
}
