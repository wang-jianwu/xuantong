package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.ConfigWatchBatchRequest;
import cloud.xuantong.protocol.v2.ConfigWatchBatchResponse;
import cloud.xuantong.protocol.v2.DiscoveryWatchBatchRequest;
import cloud.xuantong.protocol.v2.DiscoveryWatchBatchResponse;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.HelloRequest;
import cloud.xuantong.protocol.v2.HelloResponse;
import cloud.xuantong.protocol.v2.ProbeRequest;
import cloud.xuantong.protocol.v2.ProbeResponse;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import cloud.xuantong.protocol.v2.WatchAckRequest;
import cloud.xuantong.protocol.v2.WatchAckResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.noear.socketd.exception.SocketDChannelException;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.listener.SimpleListener;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.net.annotation.ServerEndpoint;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ServerEndpoint(ControlPlaneProtocol.CONTROL_PATH)
public class ControlPlaneGatewayEndpoint extends SimpleListener {
    private static final String ATTR_CONNECTION_GENERATION = "control.connectionGeneration";
    private static final String ATTR_HELLO_COMPLETE = "control.helloComplete";
    private static final String ATTR_HELLO_TERMINAL = "control.helloTerminal";
    private static final String ATTR_HELLO_TIMEOUT_TASK = "control.helloTimeoutTask";
    private static final String ATTR_CLIENT_INSTANCE_ID = "control.clientInstanceId";
    private static final String ATTR_APPLICATION_NAME = "control.applicationName";
    private static final String ATTR_CAPABILITIES = "control.capabilities";
    private static final String ATTR_PRINCIPAL = "control.principal";
    private static final String ATTR_AUTH_VALIDATED_NANOS = "control.authValidatedNanos";
    private static final List<String> BASE_CAPABILITIES = List.of(
            "protobuf-envelope-v2", "system-hello-v1", "system-probe-v1", "native-tcp-netty",
            "bounded-admission-v1", "graceful-drain-v1", "tls-v1");

    private final AtomicLong connectionGenerations = new AtomicLong();
    private final Map<String, ActiveSubscription> subscriptions =
            new ConcurrentHashMap<>();
    private final Map<String, ActiveSubscription> subscriptionsByRequestId =
            new ConcurrentHashMap<>();
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();

    @Inject
    private ControlPlaneGatewayProperties properties;
    @Inject
    private ControlPlaneGatewayRuntime runtime;
    @Inject
    private ControlPlaneRequestDispatcher dispatcher;
    @Inject(required = false)
    private ControlPlaneAuthenticator authenticator;

    public ControlPlaneGatewayEndpoint() {
    }

    ControlPlaneGatewayEndpoint(ControlPlaneGatewayProperties properties) {
        this(properties, new ControlPlaneGatewayRuntime(properties));
    }

    ControlPlaneGatewayEndpoint(
            ControlPlaneGatewayProperties properties,
            ControlPlaneGatewayRuntime runtime) {
        this(properties, runtime, new ControlPlaneRequestDispatcher());
    }

    ControlPlaneGatewayEndpoint(
            ControlPlaneGatewayProperties properties,
            ControlPlaneGatewayRuntime runtime,
            ControlPlaneRequestDispatcher dispatcher) {
        this(properties, runtime, dispatcher, null);
    }

    ControlPlaneGatewayEndpoint(
            ControlPlaneGatewayProperties properties,
            ControlPlaneGatewayRuntime runtime,
            ControlPlaneRequestDispatcher dispatcher,
            ControlPlaneAuthenticator authenticator) {
        this.properties = properties;
        this.runtime = runtime;
        this.dispatcher = dispatcher;
        this.authenticator = authenticator;
    }

    @Override
    public void onOpen(Session session) throws IOException {
        if (runtime.isDraining()) {
            session.preclose();
            return;
        }
        long connectionGeneration = connectionGenerations.incrementAndGet();
        ControlPlaneGatewayRuntime.SessionAdmission admission = runtime.sessionOpened(
                session.sessionId(), connectionGeneration, remoteIp(session));
        if (admission != ControlPlaneGatewayRuntime.SessionAdmission.ACCEPTED) {
            session.close();
            return;
        }
        activeSessions.put(session.sessionId(), session);
        session.attrPut(ATTR_CONNECTION_GENERATION, connectionGeneration);
        session.attrPut(ATTR_HELLO_COMPLETE, false);
        session.attrPut(ATTR_HELLO_TERMINAL, new AtomicBoolean());
        Future<?> helloTimeoutTask = runtime.scheduleStateCallback(
                () -> closePendingHello(session),
                () -> closeSessionQuietly(session, "Hello deadline callback was rejected"),
                properties.getHelloTimeoutMs());
        session.attrPut(ATTR_HELLO_TIMEOUT_TASK, helloTimeoutTask);
    }

    @Override
    public void onClose(Session session) {
        cancelHelloTimeout(session);
        closeSubscriptions(session.sessionId());
        activeSessions.remove(session.sessionId(), session);
        runtime.sessionClosed(session.sessionId());
    }

    private void closePendingHello(Session session) {
        if (activeSessions.get(session.sessionId()) != session) {
            return;
        }
        AtomicBoolean helloTerminal = helloTerminal(session);
        if (helloTerminal == null || !helloTerminal.compareAndSet(false, true)) {
            return;
        }
        runtime.sessionHelloTimedOut();
        try {
            session.close();
        } catch (IOException | RuntimeException e) {
            log.debug("Failed to close Session that did not complete system/hello: sessionId={}",
                    session.sessionId(), e);
        }
    }

    @Override
    public void onMessage(Session session, Message message) throws IOException {
        runtime.sessionTouched(session.sessionId());
        if (!message.isRequest() && !message.isSubscribe()) {
            log.warn("Ignoring unsupported control-plane frame: event={}, sessionId={}",
                    message.event(), session.sessionId());
            return;
        }

        if (message.dataSize() > properties.getMaxRequestBytes()) {
            replyError(session, message, null, ResponseCode.INVALID_ARGUMENT,
                    "Control-plane request exceeds maxRequestBytes", false, 0L);
            return;
        }

        if (message.isSubscribe()) {
            handleSubscribe(session, message);
            return;
        }

        long requestStartedNanos = System.nanoTime();
        ControlPlaneGatewayRuntime.Admission admission = runtime.tryAcquireRequest();
        if (admission == ControlPlaneGatewayRuntime.Admission.DRAINING) {
            replyError(session, message, parseBestEffort(message), ResponseCode.DRAINING,
                    "Gateway is draining", true, properties.getOverloadRetryAfterMs());
            return;
        }
        if (admission == ControlPlaneGatewayRuntime.Admission.OVERLOADED) {
            replyError(session, message, parseBestEffort(message), ResponseCode.RATE_LIMITED,
                    "Gateway request capacity is exhausted", true,
                    properties.getOverloadRetryAfterMs());
            return;
        }

        Envelope request = null;
        boolean releaseRequest = true;
        try {
            request = Envelope.parseFrom(message.dataAsBytes());
            validateCommonEnvelope(request);
            switch (message.event()) {
                case ControlPlaneProtocol.SYSTEM_HELLO -> handleHello(session, message, request);
                case ControlPlaneProtocol.SYSTEM_PROBE -> handleProbe(session, message, request);
                case ControlPlaneProtocol.SYSTEM_WATCH_ACK ->
                        handleWatchAck(session, message, request);
                default -> releaseRequest = !handleBusiness(
                        session, message, request, requestStartedNanos);
            }
        } catch (ControlPlaneAuthenticationException e) {
            replyError(session, message, request, ResponseCode.UNAUTHORIZED,
                    e.getMessage(), false);
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            replyError(session, message, request, ResponseCode.INVALID_ARGUMENT, e.getMessage(), false);
        } catch (RuntimeException e) {
            log.error("Control-plane request failed: event={}, sessionId={}",
                    message.event(), session.sessionId(), e);
            replyError(session, message, request, ResponseCode.INTERNAL_ERROR,
                    "Internal control-plane error", true);
        } finally {
            if (releaseRequest) {
                runtime.releaseRequest(requestStartedNanos);
            }
        }
    }

    private void handleSubscribe(Session session, Message message) throws IOException {
        Envelope request = null;
        String acquiredSubscriptionTenant = null;
        try {
            request = Envelope.parseFrom(message.dataAsBytes());
            validateCommonEnvelope(request);
            if (!Boolean.TRUE.equals(session.attr(ATTR_HELLO_COMPLETE))) {
                replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                        "system/hello must complete before subscriptions", false);
                return;
            }
            if (!supportsCapability(session, "watch-ack-v1")) {
                replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                        "Long Watch requires the watch-ack-v1 capability", false);
                return;
            }
            ControlPlanePrincipal principal = ensureAuthorized(session, request);
            ControlPlaneGatewayRuntime.RateLimitDecision requestLimit =
                    runtime.tryAcquireTenantRequest(principal.tenant());
            if (!requestLimit.allowed()) {
                replyError(session, message, request, ResponseCode.RATE_LIMITED,
                        "Tenant control-plane request rate is exhausted",
                        true,
                        requestLimit.retryAfterMs());
                return;
            }
            if (!ControlPlaneProtocol.CONFIG_WATCH_BATCH.equals(message.event())
                    && !ControlPlaneProtocol.DISCOVERY_WATCH_BATCH.equals(message.event())) {
                replyError(session, message, request, ResponseCode.INVALID_ARGUMENT,
                        "Unsupported control-plane subscription: " + message.event(), false);
                return;
            }
            ControlPlaneRequestHandler handler = dispatcher.find(message.event());
            if (handler == null) {
                replyError(session, message, request, ResponseCode.INVALID_ARGUMENT,
                        "No subscription handler is registered for " + message.event(), false);
                return;
            }
            ControlPlaneGatewayRuntime.SubscriptionAdmission subscriptionAdmission =
                    runtime.tryAcquireSubscription(principal.tenant());
            if (subscriptionAdmission
                    != ControlPlaneGatewayRuntime.SubscriptionAdmission.ACCEPTED) {
                ResponseCode code = subscriptionAdmission
                        == ControlPlaneGatewayRuntime.SubscriptionAdmission.DRAINING
                        ? ResponseCode.DRAINING : ResponseCode.RATE_LIMITED;
                replyError(session, message, request, code,
                        code == ResponseCode.DRAINING
                                ? "Gateway is draining"
                                : subscriptionAdmission
                                == ControlPlaneGatewayRuntime.SubscriptionAdmission.TENANT_LIMIT
                                ? "Tenant subscription quota is exhausted"
                                : subscriptionAdmission
                                == ControlPlaneGatewayRuntime.SubscriptionAdmission
                                .CLUSTER_COORDINATION_UNAVAILABLE
                                ? "Gateway cluster coordination lease is unavailable"
                                : "Gateway subscription capacity is exhausted",
                        true,
                        properties.getOverloadRetryAfterMs());
                return;
            }
            acquiredSubscriptionTenant = principal.tenant();
            ActiveSubscription subscription = new ActiveSubscription(
                    session, message, request, handler, principal.tenant());
            ActiveSubscription previous = subscriptions.putIfAbsent(
                    subscription.key, subscription);
            if (previous != null) {
                replyError(session, message, request, ResponseCode.INVALID_ARGUMENT,
                        "Duplicate Socket.D subscription stream", false);
                return;
            }
            ActiveSubscription previousRequest = subscriptionsByRequestId.putIfAbsent(
                    subscription.requestKey, subscription);
            if (previousRequest != null) {
                subscriptions.remove(subscription.key, subscription);
                replyError(session, message, request, ResponseCode.INVALID_ARGUMENT,
                        "Duplicate Watch subscription requestId", false);
                return;
            }
            acquiredSubscriptionTenant = null;
            // Socket.D installs the SubscribeStream callbacks immediately after the send
            // call returns. Start asynchronously so the first Reply cannot outrun that setup.
            subscription.schedule(properties.getWatchPollIntervalMs());
        } catch (ControlPlaneAuthenticationException e) {
            replyError(session, message, request, ResponseCode.UNAUTHORIZED,
                    e.getMessage(), false);
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            replyError(session, message, request, ResponseCode.INVALID_ARGUMENT,
                    e.getMessage(), false);
        } finally {
            if (acquiredSubscriptionTenant != null) {
                runtime.releaseSubscription(acquiredSubscriptionTenant);
            }
        }
    }

    private void closeSubscriptions(String sessionId) {
        for (ActiveSubscription subscription : subscriptions.values()) {
            if (subscription.session.sessionId().equals(sessionId)) {
                subscription.close();
            }
        }
    }

    private boolean handleBusiness(
            Session session,
            Message message,
            Envelope request,
            long requestStartedNanos) throws IOException {
        if (!Boolean.TRUE.equals(session.attr(ATTR_HELLO_COMPLETE))) {
            replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                    "system/hello must complete before business requests", false);
            return false;
        }
        ControlPlanePrincipal principal = ensureAuthorized(session, request);
        ControlPlaneGatewayRuntime.RateLimitDecision requestLimit =
                runtime.tryAcquireTenantRequest(principal.tenant());
        if (!requestLimit.allowed()) {
            replyError(session, message, request, ResponseCode.RATE_LIMITED,
                    "Tenant control-plane request rate is exhausted",
                    true,
                    requestLimit.retryAfterMs());
            return false;
        }
        ControlPlaneRequestHandler handler = dispatcher.find(message.event());
        if (handler == null) {
            replyError(session, message, request, ResponseCode.INVALID_ARGUMENT,
                    "Unsupported control-plane event: " + message.event(), false);
            return false;
        }

        ControlPlaneRequestContext context = requestContext(session, request);
        CompletionStage<ControlPlaneReply> response;
        try {
            response = handler.handle(context, request);
        } catch (RuntimeException e) {
            response = java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        if (response == null) {
            response = java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("Control-plane handler returned no response"));
        }

        Envelope currentRequest = request;
        response.whenComplete((reply, failure) -> {
            Runnable completion = () -> completeBusinessReply(
                    session, message, currentRequest, reply, failure,
                    requestStartedNanos);
            if (!runtime.executeStateCallback(completion)) {
                runtime.releaseRequest(requestStartedNanos);
                closeSessionQuietly(session,
                        "State callback capacity was exhausted while completing a request");
            }
        });
        return true;
    }

    // Keep slow reply serialization off Socket.D and Raft completion threads.
    private void completeBusinessReply(
            Session session,
            Message message,
            Envelope request,
            ControlPlaneReply reply,
            Throwable failure,
            long requestStartedNanos) {
        try {
            if (!session.isValid()) {
                recordLateReplyDrop(session, message, null);
                return;
            }
            if (failure == null && reply != null) {
                reply(session, message, request, reply);
            } else {
                ResponseStatus status = ControlPlaneStateStatusMapper.map(
                        failure == null
                                ? new IllegalStateException("Handler returned no reply")
                                : failure,
                        stateGroup(request));
                reply(session, message, request, ControlPlaneReply.failure(status));
            }
        } catch (SocketDChannelException e) {
            recordLateReplyDrop(session, message, e);
        } catch (IOException e) {
            if (!session.isValid()) {
                recordLateReplyDrop(session, message, e);
            } else {
                log.warn("Failed to complete control-plane reply: event={}, sessionId={}",
                        message.event(), session.sessionId(), e);
            }
        } catch (RuntimeException e) {
            log.error("Failed to map control-plane handler result: event={}, sessionId={}",
                    message.event(), session.sessionId(), e);
        } finally {
            runtime.releaseRequest(requestStartedNanos);
        }
    }

    private void recordLateReplyDrop(
            Session session, Message message, Throwable failure) {
        runtime.lateReplyDropped();
        if (failure == null) {
            log.debug("Discarding late control-plane reply for closed Session: event={}, "
                            + "sessionId={}",
                    message.event(), session.sessionId());
        } else {
            log.debug("Discarding undeliverable control-plane reply: event={}, sessionId={}",
                    message.event(), session.sessionId(), failure);
        }
    }

    private void closeSessionQuietly(Session session, String reason) {
        try {
            session.close();
        } catch (IOException | RuntimeException e) {
            log.debug("Failed to close control-plane Session: sessionId={}, reason={}",
                    session.sessionId(), reason, e);
        }
    }

    private void handleHello(Session session, Message message, Envelope request)
            throws IOException, InvalidProtocolBufferException {
        if (Boolean.TRUE.equals(session.attr(ATTR_HELLO_COMPLETE))) {
            replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                    "system/hello has already completed for this Session", false);
            return;
        }
        AtomicBoolean helloTerminal = helloTerminal(session);
        if (helloTerminal == null || helloTerminal.get()) {
            replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                    "system/hello deadline has elapsed for this Session", false);
            return;
        }
        requirePayloadType(request, ControlPlaneProtocol.HELLO_REQUEST_TYPE);
        HelloRequest hello = HelloRequest.parseFrom(request.getPayload());
        requireText("clientInstanceId", hello.getClientInstanceId());
        requireText("applicationName", hello.getApplicationName());
        requireScope("tenant", request.getTenant());
        requireScope("namespaceId", request.getNamespaceId());
        requireScope("groupName", hello.getGroupName());
        if (!hello.getSupportedProtocolVersionsList().contains(ControlPlaneProtocol.CURRENT_VERSION)) {
            replyError(session, message, request, ResponseCode.UNSUPPORTED_PROTOCOL,
                    "Client does not support protocol version " + ControlPlaneProtocol.CURRENT_VERSION, false);
            return;
        }
        if (!request.getClusterId().isBlank()
                && !properties.getClusterId().equals(request.getClusterId())) {
            replyError(session, message, request, ResponseCode.CLUSTER_MISMATCH,
                    "Requested cluster does not match this Gateway", false);
            return;
        }
        if (request.getTransportGeneration() != 0
                && request.getTransportGeneration() != properties.getTransportGeneration()) {
            replyError(session, message, request, ResponseCode.TRANSPORT_GENERATION_MISMATCH,
                    "Transport generation does not match this Gateway", false);
            return;
        }

        String remoteIp = remoteIp(session);
        ControlPlaneGatewayRuntime.RateLimitDecision authLimit =
                runtime.authenticationAttempt(remoteIp);
        if (!authLimit.allowed()) {
            replyError(session, message, request, ResponseCode.RATE_LIMITED,
                    "Too many failed authentication attempts from this address",
                    true,
                    authLimit.retryAfterMs());
            return;
        }

        ControlPlanePrincipal principal;
        try {
            principal = authenticate(
                    hello.getCredential(),
                    request.getTenant(),
                    request.getNamespaceId(),
                    hello.getGroupName());
        } catch (ControlPlaneAuthenticationException e) {
            runtime.authenticationFailed(remoteIp);
            throw e;
        }
        runtime.authenticationSucceeded(remoteIp);
        ControlPlaneGatewayRuntime.AuthenticationAdmission authenticationAdmission =
                runtime.sessionAuthenticated(session.sessionId(), principal);
        if (authenticationAdmission
                != ControlPlaneGatewayRuntime.AuthenticationAdmission.ACCEPTED) {
            ResponseCode code = authenticationAdmission
                    == ControlPlaneGatewayRuntime.AuthenticationAdmission.TENANT_LIMIT
                    || authenticationAdmission
                    == ControlPlaneGatewayRuntime.AuthenticationAdmission.CREDENTIAL_LIMIT
                    || authenticationAdmission
                    == ControlPlaneGatewayRuntime.AuthenticationAdmission
                    .CLUSTER_COORDINATION_UNAVAILABLE
                    ? ResponseCode.RATE_LIMITED
                    : ResponseCode.FAILED_PRECONDITION;
            String error = switch (authenticationAdmission) {
                case TENANT_LIMIT -> "Tenant Session quota is exhausted on this Gateway";
                case CREDENTIAL_LIMIT ->
                        "Credential Session quota is exhausted on this Gateway";
                case CLUSTER_COORDINATION_UNAVAILABLE ->
                        "Gateway cluster coordination lease is unavailable";
                case IDENTITY_CHANGED ->
                        "Authenticated Session identity cannot change";
                case SESSION_CLOSED -> "Control-plane Session is already closed";
                case ACCEPTED -> throw new IllegalStateException("Unexpected admission state");
            };
            replyError(session, message, request, code, error,
                    code == ResponseCode.RATE_LIMITED,
                    code == ResponseCode.RATE_LIMITED
                            ? properties.getOverloadRetryAfterMs() : 0L);
            return;
        }
        if (!helloTerminal.compareAndSet(false, true)) {
            replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                    "system/hello deadline elapsed while authentication was in progress",
                    false);
            return;
        }
        cancelHelloTimeout(session);
        session.attrPut(ATTR_PRINCIPAL, principal);
        session.attrPut(ATTR_AUTH_VALIDATED_NANOS, System.nanoTime());
        session.attrPut(ATTR_HELLO_COMPLETE, true);
        session.attrPut(ATTR_CLIENT_INSTANCE_ID, hello.getClientInstanceId());
        session.attrPut(ATTR_APPLICATION_NAME, hello.getApplicationName());
        session.attrPut(ATTR_CAPABILITIES, List.copyOf(hello.getCapabilitiesList()));
        runtime.sessionIdentified(session.sessionId(), hello);
        long connectionGeneration = connectionGeneration(session);
        HelloResponse response = HelloResponse.newBuilder()
                .setSelectedProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId(properties.getClusterId())
                .setTransportGeneration(properties.getTransportGeneration())
                .setGatewayId(properties.getGatewayId())
                .setConnectionGeneration(connectionGeneration)
                .addAllCapabilities(serverCapabilities())
                .setMaxRequestBudgetMs(properties.getMaxRequestBudgetMs())
                .setServerTimeEpochMs(System.currentTimeMillis())
                .setTransportSchema(runtime.transportSchema())
                .build();
        replyOk(session, message, request, ControlPlaneProtocol.HELLO_RESPONSE_TYPE,
                response.toByteString());
    }

    private List<String> serverCapabilities() {
        List<String> capabilities = new ArrayList<>(BASE_CAPABILITIES);
        boolean configFetch = dispatcher.hasHandler(ControlPlaneProtocol.CONFIG_FETCH);
        boolean configSnapshot = dispatcher.hasHandler(ControlPlaneProtocol.CONFIG_SNAPSHOT);
        boolean configWatch = dispatcher.hasHandler(ControlPlaneProtocol.CONFIG_WATCH_BATCH);
        boolean discoveryLease = dispatcher.hasHandler(ControlPlaneProtocol.DISCOVERY_REGISTER)
                && dispatcher.hasHandler(ControlPlaneProtocol.DISCOVERY_RENEW_BATCH)
                && dispatcher.hasHandler(ControlPlaneProtocol.DISCOVERY_DEREGISTER)
                && dispatcher.hasHandler(ControlPlaneProtocol.DISCOVERY_TAKEOVER)
                && dispatcher.hasHandler(ControlPlaneProtocol.DISCOVERY_GET_LEASE_STATE)
                && dispatcher.hasHandler(ControlPlaneProtocol.DISCOVERY_RESOLVE_OPERATION);
        boolean discoverySnapshot = dispatcher.hasHandler(
                ControlPlaneProtocol.DISCOVERY_SNAPSHOT);
        boolean discoveryWatch = dispatcher.hasHandler(
                ControlPlaneProtocol.DISCOVERY_WATCH_BATCH);
        if (configFetch || configSnapshot || configWatch
                || discoveryLease || discoverySnapshot || discoveryWatch) {
            capabilities.add("state-routing-v1");
        }
        if (configFetch) {
            capabilities.add("config-fetch-v1");
        }
        if (configSnapshot) {
            capabilities.add("config-snapshot-v1");
        }
        if (configWatch) {
            capabilities.add("config-watch-batch-v1");
            capabilities.add("config-watch-stream-v1");
        }
        if (discoveryLease) {
            capabilities.add("discovery-lease-v1");
        }
        if (discoverySnapshot) {
            capabilities.add("discovery-snapshot-v1");
        }
        if (discoveryWatch) {
            capabilities.add("discovery-watch-batch-v1");
            capabilities.add("discovery-watch-stream-v1");
        }
        if (configWatch || discoveryWatch) {
            capabilities.add("watch-ack-v1");
        }
        return List.copyOf(capabilities);
    }

    private AtomicBoolean helloTerminal(Session session) {
        Object value = session.attr(ATTR_HELLO_TERMINAL);
        return value instanceof AtomicBoolean terminal ? terminal : null;
    }

    private void cancelHelloTimeout(Session session) {
        Object value = session.attr(ATTR_HELLO_TIMEOUT_TASK);
        session.attrDel(ATTR_HELLO_TIMEOUT_TASK);
        if (value instanceof Future<?> future) {
            future.cancel(false);
        }
    }

    private boolean supportsCapability(Session session, String capability) {
        Object value = session.attr(ATTR_CAPABILITIES);
        return value instanceof List<?> capabilities
                && capabilities.contains(capability);
    }

    private void handleProbe(Session session, Message message, Envelope request)
            throws IOException, InvalidProtocolBufferException {
        if (!Boolean.TRUE.equals(session.attr(ATTR_HELLO_COMPLETE))) {
            replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                    "system/hello must complete before system/probe", false);
            return;
        }
        ControlPlanePrincipal principal = ensureAuthorized(session, request);
        ControlPlaneGatewayRuntime.RateLimitDecision requestLimit =
                runtime.tryAcquireTenantRequest(principal.tenant());
        if (!requestLimit.allowed()) {
            replyError(session, message, request, ResponseCode.RATE_LIMITED,
                    "Tenant control-plane request rate is exhausted",
                    true,
                    requestLimit.retryAfterMs());
            return;
        }
        requirePayloadType(request, ControlPlaneProtocol.PROBE_REQUEST_TYPE);
        ProbeRequest probe = ProbeRequest.parseFrom(request.getPayload());
        requireText("nonce", probe.getNonce());
        long receivedAt = System.currentTimeMillis();
        ProbeResponse response = ProbeResponse.newBuilder()
                .setNonce(probe.getNonce())
                .setGatewayId(properties.getGatewayId())
                .setConnectionGeneration(connectionGeneration(session))
                .setServerReceiveEpochMs(receivedAt)
                .setServerSendEpochMs(System.currentTimeMillis())
                .build();
        replyOk(session, message, request, ControlPlaneProtocol.PROBE_RESPONSE_TYPE,
                response.toByteString());
    }

    private void handleWatchAck(Session session, Message message, Envelope request)
            throws IOException, InvalidProtocolBufferException {
        if (!Boolean.TRUE.equals(session.attr(ATTR_HELLO_COMPLETE))) {
            replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                    "system/hello must complete before system/watch-ack", false);
            return;
        }
        ensureAuthorized(session, request);
        requirePayloadType(request, ControlPlaneProtocol.WATCH_ACK_REQUEST_TYPE);
        WatchAckRequest acknowledgement = WatchAckRequest.parseFrom(request.getPayload());
        requireText("subscriptionRequestId",
                acknowledgement.getSubscriptionRequestId());
        ActiveSubscription subscription = subscriptionsByRequestId.get(subscriptionKey(
                session.sessionId(), acknowledgement.getSubscriptionRequestId()));
        if (subscription == null) {
            replyError(session, message, request, ResponseCode.FAILED_PRECONDITION,
                    "Watch subscription is no longer active", true,
                    properties.getWatchPollIntervalMs());
            return;
        }
        long acceptedRevision = subscription.acknowledge(
                request, acknowledgement.getCommittedRevision());
        WatchAckResponse response = WatchAckResponse.newBuilder()
                .setSubscriptionRequestId(acknowledgement.getSubscriptionRequestId())
                .setAcceptedRevision(acceptedRevision)
                .build();
        replyOk(session, message, request,
                ControlPlaneProtocol.WATCH_ACK_RESPONSE_TYPE,
                response.toByteString());
    }

    private void validateCommonEnvelope(Envelope request) {
        if (request.getProtocolVersion() != ControlPlaneProtocol.CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported envelope protocolVersion: " + request.getProtocolVersion());
        }
        requireText("requestId", request.getRequestId());
        if (request.getRemainingBudgetMs() == 0) {
            throw new IllegalArgumentException("remainingBudgetMs must be greater than zero");
        }
    }

    private String subscriptionKey(String sessionId, String requestId) {
        return sessionId + ':' + requestId;
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - startedNanos));
    }

    private Envelope parseBestEffort(Message message) {
        try {
            return Envelope.parseFrom(message.dataAsBytes());
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    private void requirePayloadType(Envelope request, String expected) {
        if (!expected.equals(request.getPayloadType())) {
            throw new IllegalArgumentException(
                    "Expected payloadType " + expected + " but got " + request.getPayloadType());
        }
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private String requireScope(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private ControlPlanePrincipal authenticate(
            String credential,
            String tenant,
            String namespaceId,
            String groupName) {
        String trustedTenant = requireScope("tenant", tenant);
        String trustedNamespace = requireScope("namespaceId", namespaceId);
        String trustedGroup = requireScope("groupName", groupName);
        ControlPlaneAuthenticator current = authenticator;
        ControlPlanePrincipal principal;
        if (current == null) {
            if (properties.isApplicationAuthRequired()) {
                throw new ControlPlaneAuthenticationException(
                        "Control-plane credential is required");
            }
            principal = ControlPlanePrincipal.anonymous(
                    trustedTenant, trustedNamespace, trustedGroup);
        } else {
            principal = current.authenticate(
                    credential(credential),
                    trustedTenant,
                    trustedNamespace,
                    trustedGroup);
        }
        return validatePrincipalScope(
                principal, trustedTenant, trustedNamespace, trustedGroup);
    }

    private String credential(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > 4_096
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ControlPlaneAuthenticationException(
                    "Control-plane credential is invalid");
        }
        return normalized;
    }

    private ControlPlanePrincipal ensureAuthorized(Session session, Envelope request) {
        ControlPlanePrincipal principal = session.attr(ATTR_PRINCIPAL);
        if (principal == null) {
            throw new ControlPlaneAuthenticationException(
                    "Control-plane Session is not authenticated");
        }
        validatePrincipalScope(
                principal,
                requireScope("tenant", request.getTenant()),
                requireScope("namespaceId", request.getNamespaceId()),
                principal.groupName());
        ControlPlaneAuthenticator current = authenticator;
        if (current == null) {
            if (properties.isApplicationAuthRequired()) {
                throw new ControlPlaneAuthenticationException(
                        "Control-plane authenticator is unavailable");
            }
            return principal;
        }
        Long lastValidated = session.attr(ATTR_AUTH_VALIDATED_NANOS);
        long now = System.nanoTime();
        long interval = TimeUnit.MILLISECONDS.toNanos(
                properties.getAuthRevalidateIntervalMs());
        if (lastValidated == null || now - lastValidated >= interval) {
            principal = validatePrincipalScope(
                    current.revalidate(principal),
                    principal.tenant(),
                    principal.namespaceId(),
                    principal.groupName());
            session.attrPut(ATTR_PRINCIPAL, principal);
            session.attrPut(ATTR_AUTH_VALIDATED_NANOS, now);
            ControlPlaneGatewayRuntime.AuthenticationAdmission admission =
                    runtime.sessionAuthenticated(session.sessionId(), principal);
            if (admission != ControlPlaneGatewayRuntime.AuthenticationAdmission.ACCEPTED) {
                throw new ControlPlaneAuthenticationException(
                        "Control-plane Session identity or quota binding is no longer valid");
            }
        }
        return principal;
    }

    private ControlPlanePrincipal validatePrincipalScope(
            ControlPlanePrincipal principal,
            String tenant,
            String namespaceId,
            String groupName) {
        if (principal == null
                || !tenant.equals(principal.tenant())
                || !namespaceId.equals(principal.namespaceId())
                || !groupName.equals(principal.groupName())) {
            throw new ControlPlaneAuthenticationException(
                    "Control-plane credential is invalid or outside its scope");
        }
        if (principal.expiresAtEpochMs() > 0
                && principal.expiresAtEpochMs() <= System.currentTimeMillis()) {
            throw new ControlPlaneAuthenticationException(
                    "Control-plane credential is expired");
        }
        return principal;
    }

    public int revokeCredential(String credentialFingerprint) {
        if (credentialFingerprint == null || credentialFingerprint.isBlank()) {
            return 0;
        }
        int closed = 0;
        for (Session session : activeSessions.values()) {
            ControlPlanePrincipal principal = session.attr(ATTR_PRINCIPAL);
            if (principal != null
                    && credentialFingerprint.equals(principal.credentialFingerprint())) {
                try {
                    session.close();
                } catch (IOException | RuntimeException e) {
                    log.debug("Failed to close revoked control-plane Session: sessionId={}",
                            session.sessionId(), e);
                }
                closed++;
            }
        }
        return closed;
    }

    private long connectionGeneration(Session session) {
        Long generation = session.attr(ATTR_CONNECTION_GENERATION);
        if (generation == null) {
            generation = connectionGenerations.incrementAndGet();
            session.attrPut(ATTR_CONNECTION_GENERATION, generation);
        }
        return generation;
    }

    private void replyOk(Session session, Message message, Envelope request,
                         String payloadType, ByteString payload) throws IOException {
        reply(session, message, request, ControlPlaneReply.ok(payloadType, payload));
    }

    private void replyError(Session session, Message message, Envelope request,
                            ResponseCode code, String errorMessage, boolean retryable) throws IOException {
        replyError(session, message, request, code, errorMessage, retryable, 0L);
    }

    private void replyError(Session session, Message message, Envelope request,
                            ResponseCode code, String errorMessage, boolean retryable,
                            long retryAfterMs) throws IOException {
        ResponseStatus status = status(
                code, errorMessage, retryable, retryAfterMs);
        reply(session, message, request, ControlPlaneReply.failure(status));
    }

    private ResponseStatus unauthorizedStatus(String message) {
        return status(ResponseCode.UNAUTHORIZED, message, false, 0L);
    }

    private ResponseStatus status(
            ResponseCode code,
            String errorMessage,
            boolean retryable,
            long retryAfterMs) {
        return ResponseStatus.newBuilder()
                .setCode(code)
                .setMessage(errorMessage == null ? code.name() : errorMessage)
                .setRetryable(retryable)
                .setRetryAfterMs(Math.max(0L, retryAfterMs))
                .setCommitStatus(CommitStatus.NOT_APPLICABLE)
                .build();
    }

    private void reply(
            Session session,
            Message message,
            Envelope request,
            ControlPlaneReply reply) throws IOException {
        replyFrame(session, message, request, reply, true);
    }

    private void replyFrame(
            Session session,
            Message message,
            Envelope request,
            ControlPlaneReply reply,
            boolean end) throws IOException {
        Envelope response = responseEnvelope(request)
                .setPayloadType(reply.payloadType())
                .setPayload(reply.payload())
                .setResponseStatus(reply.status())
                .build();
        Entity entity = Entity.of(response.toByteArray());
        if (end) {
            session.replyEnd(message, entity);
        } else {
            session.reply(message, entity);
        }
    }

    private ControlPlaneRequestContext requestContext(
            Session session, Envelope request) {
        ControlPlanePrincipal principal = ensureAuthorized(session, request);
        long budgetMs = Math.min(
                request.getRemainingBudgetMs(), properties.getMaxRequestBudgetMs());
        long now = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
        long deadline = budgetNanos > 0 && now > Long.MAX_VALUE - budgetNanos
                ? Long.MAX_VALUE
                : now + budgetNanos;
        return new ControlPlaneRequestContext(
                session.sessionId(),
                session.attr(ATTR_CLIENT_INSTANCE_ID),
                session.attr(ATTR_APPLICATION_NAME),
                principal.principalId(),
                properties.getGatewayId(),
                connectionGeneration(session),
                principal.tenant(),
                principal.namespaceId(),
                principal.groupName(),
                remoteIp(session),
                deadline);
    }

    private String remoteIp(Session session) {
        try {
            InetSocketAddress remote = session.remoteAddress();
            if (remote == null) {
                return "";
            }
            return remote.getAddress() == null
                    ? remote.getHostString()
                    : remote.getAddress().getHostAddress();
        } catch (Exception e) {
            log.debug("Unable to resolve control-plane remote IP: sessionId={}",
                    session.sessionId(), e);
            return "";
        }
    }

    private cloud.xuantong.state.api.StateGroupId stateGroup(Envelope request) {
        if (request == null || request.getGroupId().isBlank()) {
            return null;
        }
        return switch (request.getRevisionType()) {
            case CONFIG_DECISION, CONFIG_EVENT ->
                    cloud.xuantong.state.api.StateGroupId.config(request.getGroupId());
            case REGISTRY ->
                    cloud.xuantong.state.api.StateGroupId.registry(request.getGroupId());
            default -> null;
        };
    }

    private Envelope.Builder responseEnvelope(Envelope request) {
        Envelope.Builder builder = Envelope.newBuilder()
                .setProtocolVersion(ControlPlaneProtocol.CURRENT_VERSION)
                .setClusterId(properties.getClusterId())
                .setTransportGeneration(properties.getTransportGeneration())
                .setRequestId(request == null || request.getRequestId().isBlank()
                        ? UUID.randomUUID().toString() : request.getRequestId());
        if (request != null) {
            builder.setOperationId(request.getOperationId())
                    .setTraceId(request.getTraceId())
                    .setTenant(request.getTenant())
                    .setNamespaceId(request.getNamespaceId())
                    .setRemainingBudgetMs(Math.min(
                            request.getRemainingBudgetMs(), properties.getMaxRequestBudgetMs()))
                    .setRevisionType(request.getRevisionType())
                    .setGroupId(request.getGroupId())
                    .setKnownRevision(request.getKnownRevision())
                    .setMinRevision(request.getMinRevision());
        }
        return builder;
    }

    private record WatchAdvance(
            Envelope nextRequest,
            boolean emit,
            boolean end) {
    }

    private final class ActiveSubscription {
        private final String key;
        private final String requestKey;
        private final Session session;
        private final Message message;
        private final ControlPlaneRequestHandler handler;
        private final String tenant;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final long startedNanos = System.nanoTime();
        private volatile Envelope request;
        private Future<?> scheduledTask;
        private long awaitingAckRevision = -1L;
        private long awaitingAckStartedNanos;
        private long lastAcknowledgedRevision;
        private long idlePollDelayMs;

        private ActiveSubscription(
                Session session,
                Message message,
                Envelope request,
                ControlPlaneRequestHandler handler,
                String tenant) {
            this.key = session.sessionId() + ':' + message.sid();
            this.requestKey = subscriptionKey(
                    session.sessionId(), request.getRequestId());
            this.session = session;
            this.message = message;
            this.request = request;
            this.handler = handler;
            this.tenant = tenant;
            this.lastAcknowledgedRevision = request.getKnownRevision();
            this.idlePollDelayMs = properties.getWatchPollIntervalMs();
        }

        private void pump() {
            if (closed.get()) {
                return;
            }
            synchronized (this) {
                if (awaitingAckRevision >= 0L) {
                    return;
                }
            }
            if (runtime.isDraining()) {
                finishFailure(ResponseStatus.newBuilder()
                        .setCode(ResponseCode.DRAINING)
                        .setMessage("Gateway is draining")
                        .setRetryable(true)
                        .setRetryAfterMs(properties.getOverloadRetryAfterMs())
                        .setCommitStatus(CommitStatus.NOT_APPLICABLE)
                        .build());
                return;
            }
            try {
                if (!session.isActive() || session.isClosing()) {
                    close();
                    return;
                }
            } catch (RuntimeException e) {
                close();
                return;
            }
            try {
                ensureAuthorized(session, request);
            } catch (ControlPlaneAuthenticationException e) {
                finishFailure(unauthorizedStatus(e.getMessage()));
                return;
            }

            long requestStartedNanos = System.nanoTime();
            ControlPlaneGatewayRuntime.Admission admission = runtime.tryAcquireRequest();
            if (admission == ControlPlaneGatewayRuntime.Admission.DRAINING) {
                finishFailure(ResponseStatus.newBuilder()
                        .setCode(ResponseCode.DRAINING)
                        .setMessage("Gateway is draining")
                        .setRetryable(true)
                        .setRetryAfterMs(properties.getOverloadRetryAfterMs())
                        .setCommitStatus(CommitStatus.NOT_APPLICABLE)
                        .build());
                return;
            }
            if (admission == ControlPlaneGatewayRuntime.Admission.OVERLOADED) {
                schedule(properties.getOverloadRetryAfterMs());
                return;
            }

            Envelope current = request;
            boolean rotationDue = elapsedMillis(startedNanos)
                    >= properties.getWatchStreamMaxLifetimeMs();
            runtime.watchPollStarted();
            CompletionStage<ControlPlaneReply> stage;
            try {
                stage = handler.handle(requestContext(session, current), current);
            } catch (RuntimeException e) {
                stage = CompletableFuture.failedFuture(e);
            }
            if (stage == null) {
                stage = CompletableFuture.failedFuture(
                        new IllegalStateException("Subscription handler returned no response"));
            }
            stage.whenComplete((reply, failure) -> {
                Runnable completion = () -> completePump(
                        current, reply, failure, rotationDue, requestStartedNanos);
                if (!runtime.executeStateCallback(completion)) {
                    runtime.releaseRequest(requestStartedNanos);
                    closeForCallbackOverload();
                }
            });
        }

        private void completePump(
                Envelope current,
                ControlPlaneReply reply,
                Throwable failure,
                boolean rotationDue,
                long requestStartedNanos) {
            try {
                if (closed.get()) {
                    return;
                }
                if (failure != null || reply == null) {
                    ResponseStatus status = ControlPlaneStateStatusMapper.map(
                            failure == null
                                    ? new IllegalStateException(
                                            "Subscription handler returned no reply")
                                    : failure,
                            stateGroup(current));
                    finishFailure(status);
                    return;
                }
                if (reply.status().getCode() != ResponseCode.OK) {
                    replyFrame(session, message, current, reply, true);
                    close();
                    return;
                }

                WatchAdvance advance = advanceWatch(
                        message.event(), current, reply);
                request = advance.nextRequest();
                boolean rotate = rotationDue || elapsedMillis(startedNanos)
                        >= properties.getWatchStreamMaxLifetimeMs();
                boolean end = advance.end() || rotate;
                if (advance.emit() || end) {
                    if (!end) {
                        beginAwaitingAcknowledgement(
                                advance.nextRequest().getKnownRevision());
                    }
                    replyFrame(session, message, current, reply, end);
                    runtime.watchReplyEmitted();
                    idlePollDelayMs = properties.getWatchPollIntervalMs();
                }
                if (end) {
                    if (rotate && !advance.end()) {
                        runtime.watchStreamRotated();
                    }
                    close();
                } else if (advance.emit()) {
                    scheduleAcknowledgementTimeout(
                            advance.nextRequest().getKnownRevision());
                } else {
                    runtime.watchIdlePollCompleted();
                    schedule(nextIdlePollDelay());
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Control-plane subscription failed: event={}, sessionId={}, sid={}",
                        message.event(), session.sessionId(), message.sid(), e);
                close();
            } finally {
                runtime.releaseRequest(requestStartedNanos);
            }
        }

        private void finishFailure(ResponseStatus status) {
            try {
                replyFrame(session, message, request,
                        ControlPlaneReply.failure(status), true);
            } catch (IOException e) {
                log.debug("Failed to terminate control-plane subscription: event={}, sid={}",
                        message.event(), message.sid(), e);
            } finally {
                close();
            }
        }

        private void schedule(long delayMs) {
            synchronized (this) {
                if (closed.get()) {
                    return;
                }
                cancelScheduledTask();
                scheduledTask = runtime.scheduleStateCallback(() -> {
                            synchronized (ActiveSubscription.this) {
                                scheduledTask = null;
                            }
                            pump();
                        },
                        this::closeForCallbackOverload,
                        jitteredDelay(delayMs));
            }
        }

        private void scheduleAcknowledgementTimeout(long expectedRevision) {
            synchronized (this) {
                if (closed.get() || awaitingAckRevision != expectedRevision) {
                    return;
                }
                cancelScheduledTask();
                scheduledTask = runtime.scheduleStateCallback(() -> {
                            synchronized (ActiveSubscription.this) {
                                scheduledTask = null;
                            }
                            acknowledgementTimedOut(expectedRevision);
                        },
                        this::closeForCallbackOverload,
                        properties.getWatchAckTimeoutMs());
            }
        }

        private void beginAwaitingAcknowledgement(long revision) {
            synchronized (this) {
                if (awaitingAckRevision >= 0L) {
                    throw new IllegalStateException(
                            "Watch already has an unacknowledged Reply");
                }
                awaitingAckRevision = revision;
                awaitingAckStartedNanos = System.nanoTime();
                runtime.watchReplyAwaitingAcknowledgement();
            }
        }

        private long acknowledge(Envelope acknowledgement, long committedRevision) {
            boolean resume = false;
            synchronized (this) {
                if (closed.get()) {
                    throw new IllegalArgumentException(
                            "Watch subscription is already closed");
                }
                if (acknowledgement.getRevisionType() != request.getRevisionType()
                        || !acknowledgement.getGroupId().equals(request.getGroupId())) {
                    throw new IllegalArgumentException(
                            "Watch acknowledgement scope does not match the subscription");
                }
                if (awaitingAckRevision < 0L) {
                    if (committedRevision == lastAcknowledgedRevision) {
                        return lastAcknowledgedRevision;
                    }
                    throw new IllegalArgumentException(
                            "Watch has no Reply awaiting acknowledgement");
                }
                if (committedRevision != awaitingAckRevision) {
                    throw new IllegalArgumentException(
                            "Watch acknowledgement revision does not match the pending Reply");
                }
                awaitingAckRevision = -1L;
                lastAcknowledgedRevision = committedRevision;
                cancelScheduledTask();
                runtime.watchAcknowledged(elapsedMillis(awaitingAckStartedNanos));
                awaitingAckStartedNanos = 0L;
                resume = true;
            }
            if (resume) {
                schedule(properties.getWatchPollIntervalMs());
            }
            return committedRevision;
        }

        private void acknowledgementTimedOut(long expectedRevision) {
            boolean closeSession = false;
            synchronized (this) {
                if (!closed.get() && awaitingAckRevision == expectedRevision) {
                    awaitingAckRevision = -1L;
                    awaitingAckStartedNanos = 0L;
                    runtime.watchAcknowledgementTimedOut();
                    closeSession = true;
                }
            }
            if (closeSession) {
                log.warn("Closing slow control-plane Watch consumer: event={}, sessionId={}, "
                                + "sid={}, unacknowledgedRevision={}",
                        message.event(), session.sessionId(), message.sid(), expectedRevision);
                close();
                closeSessionQuietly(session,
                        "Watch acknowledgement deadline exceeded");
            }
        }

        private long nextIdlePollDelay() {
            long delay = idlePollDelayMs;
            idlePollDelayMs = Math.min(
                    properties.getWatchIdlePollMaxIntervalMs(),
                    Math.max(properties.getWatchPollIntervalMs(), delay * 2L));
            return delay;
        }

        private long jitteredDelay(long delayMs) {
            long bounded = Math.max(1L, delayMs);
            if (bounded < 20L) {
                return bounded;
            }
            long spread = Math.max(1L, bounded / 10L);
            long lower = Math.max(1L, bounded - spread);
            long upper = bounded + spread + 1L;
            return ThreadLocalRandom.current().nextLong(lower, upper);
        }

        private void closeForCallbackOverload() {
            close();
            closeSessionQuietly(session,
                    "State callback capacity was exhausted for a Watch");
        }

        private void cancelScheduledTask() {
            Future<?> current = scheduledTask;
            scheduledTask = null;
            if (current != null) {
                current.cancel(false);
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (this) {
                    cancelScheduledTask();
                    if (awaitingAckRevision >= 0L) {
                        awaitingAckRevision = -1L;
                        awaitingAckStartedNanos = 0L;
                        runtime.watchAcknowledgementAbandoned();
                    }
                }
                subscriptions.remove(key, this);
                subscriptionsByRequestId.remove(requestKey, this);
                runtime.releaseSubscription(tenant);
            }
        }
    }

    private WatchAdvance advanceWatch(
            String event,
            Envelope request,
            ControlPlaneReply reply) throws IOException {
        if (ControlPlaneProtocol.CONFIG_WATCH_BATCH.equals(event)) {
            if (!ControlPlaneProtocol.CONFIG_WATCH_BATCH_RESPONSE_TYPE.equals(
                    reply.payloadType())) {
                throw new IOException("Unexpected Config Watch payload type");
            }
            ConfigWatchBatchRequest current = ConfigWatchBatchRequest.parseFrom(
                    request.getPayload());
            ConfigWatchBatchResponse response = ConfigWatchBatchResponse.parseFrom(
                    reply.payload());
            validateWatchCursor(
                    current.getAfterEventRevision(),
                    response.getRequestedAfterRevision(),
                    response.getCoveredThroughRevision());
            long covered = response.getCoveredThroughRevision();
            ConfigWatchBatchRequest next = current.toBuilder()
                    .setAfterEventRevision(covered)
                    .build();
            return new WatchAdvance(
                    request.toBuilder()
                            .setKnownRevision(covered)
                            .setMinRevision(covered)
                            .setPayload(next.toByteString())
                            .build(),
                    response.getResetRequired()
                            || response.getEventsCount() > 0
                            || covered > current.getAfterEventRevision(),
                    response.getResetRequired());
        }
        if (ControlPlaneProtocol.DISCOVERY_WATCH_BATCH.equals(event)) {
            if (!ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_RESPONSE_TYPE.equals(
                    reply.payloadType())) {
                throw new IOException("Unexpected Discovery Watch payload type");
            }
            DiscoveryWatchBatchRequest current = DiscoveryWatchBatchRequest.parseFrom(
                    request.getPayload());
            DiscoveryWatchBatchResponse response = DiscoveryWatchBatchResponse.parseFrom(
                    reply.payload());
            validateWatchCursor(
                    current.getAfterRegistryRevision(),
                    response.getRequestedAfterRevision(),
                    response.getCoveredThroughRevision());
            long covered = response.getCoveredThroughRevision();
            DiscoveryWatchBatchRequest next = current.toBuilder()
                    .setAfterRegistryRevision(covered)
                    .build();
            return new WatchAdvance(
                    request.toBuilder()
                            .setKnownRevision(covered)
                            .setMinRevision(covered)
                            .setPayload(next.toByteString())
                            .build(),
                    response.getResetRequired()
                            || response.getEventsCount() > 0
                            || covered > current.getAfterRegistryRevision(),
                    response.getResetRequired());
        }
        throw new IOException("Unsupported Watch subscription event: " + event);
    }

    private void validateWatchCursor(
            long requested,
            long echoedRequested,
            long covered) throws IOException {
        if (echoedRequested != requested) {
            throw new IOException("Watch response cursor does not match the request");
        }
        if (covered < requested) {
            throw new IOException("Watch response cursor moved backwards");
        }
    }
}
