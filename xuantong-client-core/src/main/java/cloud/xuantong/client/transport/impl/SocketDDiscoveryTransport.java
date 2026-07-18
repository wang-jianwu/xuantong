package cloud.xuantong.client.transport.impl;

import cloud.xuantong.client.ClientIdentity;
import cloud.xuantong.client.ControlPlaneOptions;
import cloud.xuantong.client.exception.DiscoveryLeaseException;
import cloud.xuantong.client.exception.XuantongException;
import cloud.xuantong.client.model.ServiceInstance;
import cloud.xuantong.client.model.ServiceInvalidation;
import cloud.xuantong.client.model.ServiceSnapshot;
import cloud.xuantong.client.model.ServiceWatchBatch;
import cloud.xuantong.client.transport.DiscoveryTransport;
import cloud.xuantong.client.transport.WatchBatchHandler;
import cloud.xuantong.client.transport.WatchSubscription;
import cloud.xuantong.protocol.v2.ControlPlaneProtocol;
import cloud.xuantong.protocol.v2.DiscoveryChange;
import cloud.xuantong.protocol.v2.DiscoveryDeregisterRequest;
import cloud.xuantong.protocol.v2.DiscoveryLeaseReference;
import cloud.xuantong.protocol.v2.DiscoveryLeaseRenewal;
import cloud.xuantong.protocol.v2.DiscoveryMutationResponse;
import cloud.xuantong.protocol.v2.DiscoveryRegisterRequest;
import cloud.xuantong.protocol.v2.DiscoveryRenewBatchRequest;
import cloud.xuantong.protocol.v2.DiscoveryResolveOperationRequest;
import cloud.xuantong.protocol.v2.DiscoveryResolveOperationResponse;
import cloud.xuantong.protocol.v2.DiscoveryServiceInstance;
import cloud.xuantong.protocol.v2.DiscoverySnapshotRequest;
import cloud.xuantong.protocol.v2.DiscoverySnapshotResponse;
import cloud.xuantong.protocol.v2.DiscoveryWatchBatchRequest;
import cloud.xuantong.protocol.v2.DiscoveryWatchBatchResponse;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.protocol.v2.ServiceCoordinate;
import cloud.xuantong.protocol.v2.ServiceInstanceCoordinate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Discovery protocol adapter reusing the single-active-Gateway control runtime. */
public class SocketDDiscoveryTransport implements DiscoveryTransport {
    private static final int DEFAULT_WATCH_BATCH_SIZE = 256;
    private static final long DEFAULT_LEASE_TTL_MS = 30_000L;

    private final SocketDTransport controlTransport;
    private final long leaseTtlMs;
    private volatile String namespace = "";
    private volatile String group = "";
    private volatile String serviceName = "";

    public SocketDDiscoveryTransport() {
        this(ClientIdentity.defaultIdentity(), ControlPlaneOptions.registryDefaults(),
                DEFAULT_LEASE_TTL_MS);
    }

    public SocketDDiscoveryTransport(ClientIdentity identity) {
        this(identity, ControlPlaneOptions.registryDefaults(), DEFAULT_LEASE_TTL_MS);
    }

    public SocketDDiscoveryTransport(
            ClientIdentity identity, ControlPlaneOptions options) {
        this(identity, options, DEFAULT_LEASE_TTL_MS);
    }

    public SocketDDiscoveryTransport(
            ClientIdentity identity,
            ControlPlaneOptions options,
            long leaseTtlMs) {
        this(SocketDTransport.discovery(identity, options), leaseTtlMs);
    }

    SocketDDiscoveryTransport(SocketDTransport controlTransport, long leaseTtlMs) {
        if (controlTransport == null) {
            throw new IllegalArgumentException("controlTransport must not be null");
        }
        if (leaseTtlMs < 1_000L) {
            throw new IllegalArgumentException("leaseTtlMs must be at least 1000");
        }
        this.controlTransport = controlTransport;
        this.leaseTtlMs = leaseTtlMs;
    }

    @Override
    public void connect(
            List<String> serverAddresses,
            String namespace,
            String group,
            String serviceName,
            String accessToken) {
        this.namespace = requireName("namespace", namespace);
        this.group = requireName("group", group);
        this.serviceName = requireName("serviceName", serviceName);
        controlTransport.connect(
                serverAddresses,
                this.namespace,
                this.group,
                accessToken == null ? "" : accessToken);
    }

    @Override
    public ServiceSnapshot fetchInstances() {
        DiscoverySnapshotResponse snapshot = snapshot(List.of(serviceName), 0L);
        List<ServiceInstance> instances = snapshot.getInstancesList().stream()
                .map(value -> toClientInstance(value, true))
                .toList();
        return new ServiceSnapshot(
                snapshot.getRegistryRevision(),
                snapshot.getCompactionRevision(),
                snapshot.getServerTimeEpochMs(),
                instances);
    }

    @Override
    public List<String> fetchServices() {
        DiscoverySnapshotResponse snapshot = snapshot(List.of(), 0L);
        Set<String> services = new LinkedHashSet<>();
        for (DiscoveryServiceInstance instance : snapshot.getInstancesList()) {
            validateScope(instance.getInstance().getService(), false);
            services.add(instance.getInstance().getService().getServiceName());
        }
        return services.stream().sorted().toList();
    }

    @Override
    public ServiceWatchBatch watchBatch(long afterRegistryRevision, int maxBatchSize) {
        try {
            int batchSize = maxBatchSize <= 0 ? DEFAULT_WATCH_BATCH_SIZE : maxBatchSize;
            DiscoveryWatchBatchRequest payload = DiscoveryWatchBatchRequest.newBuilder()
                    .setAfterRegistryRevision(Math.max(0L, afterRegistryRevision))
                    .setGroupName(group)
                    .addServiceNames(serviceName)
                    .setMaxBatchSize(batchSize)
                    .build();
            Envelope envelope = controlTransport.invokeControlPlane(
                    "discovery/watch-batch",
                    ControlPlaneProtocol.DISCOVERY_WATCH_BATCH,
                    RevisionType.REGISTRY,
                    Math.max(0L, afterRegistryRevision),
                    Math.max(0L, afterRegistryRevision),
                    "",
                    ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_REQUEST_TYPE,
                    payload.toByteString(),
                    ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_RESPONSE_TYPE);
            return decodeWatchBatch(envelope, afterRegistryRevision);
        } catch (Exception e) {
            throw translate("Discovery Watch-Batch failed", e);
        }
    }

    @Override
    public WatchSubscription subscribe(
            long afterRegistryRevision,
            WatchBatchHandler<ServiceWatchBatch> handler) {
        return controlTransport.subscribeDiscovery(
                group,
                List.of(serviceName),
                afterRegistryRevision,
                handler,
                this::decodeWatchBatch);
    }

    private ServiceWatchBatch decodeWatchBatch(
            Envelope envelope, long expectedCursor) throws Exception {
        controlTransport.requireOk(
                envelope, ControlPlaneProtocol.DISCOVERY_WATCH_BATCH_RESPONSE_TYPE);
        DiscoveryWatchBatchResponse response = DiscoveryWatchBatchResponse.parseFrom(
                envelope.getPayload());
        if (response.getRequestedAfterRevision() != expectedCursor
                || response.getCoveredThroughRevision() < expectedCursor) {
            throw new XuantongException("Discovery Watch cursor moved backwards");
        }
        List<ServiceInvalidation> events = new ArrayList<>();
        long previous = expectedCursor;
        for (DiscoveryChange event : response.getEventsList()) {
            if (event.getRegistryRevision() <= previous
                    || event.getRegistryRevision()
                    > response.getCoveredThroughRevision()) {
                throw new XuantongException(
                        "Discovery Watch event revisions are not monotonic");
            }
            validateCoordinate(event.getInstance(), true);
            previous = event.getRegistryRevision();
            events.add(new ServiceInvalidation(
                    event.getRegistryRevision(),
                    event.getEventType(),
                    event.getInstance().getInstanceId(),
                    event.hasValue() ? toClientInstance(event.getValue(), true) : null));
        }
        return new ServiceWatchBatch(
                response.getRequestedAfterRevision(),
                response.getCoveredThroughRevision(),
                response.getCompactionRevision(),
                response.getResetRequired(),
                events);
    }

    @Override
    public ServiceInstance register(ServiceInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        String instanceId = requireText("instanceId", instance.getInstanceId());
        DiscoveryRegisterRequest payload = DiscoveryRegisterRequest.newBuilder()
                .setGroupName(group)
                .setServiceName(serviceName)
                .setInstanceId(instanceId)
                .setIp(requireText("ip", instance.getIp()))
                .setPort(requirePort(instance.getPort()))
                .setWeight(instance.getWeight() == null ? 1D : instance.getWeight())
                .setEnabled(instance.getEnabled() == null || instance.getEnabled())
                .setMetadata(instance.getMetadata() == null ? "" : instance.getMetadata())
                .setTtlMs(leaseTtlMs(instance))
                .setExpectedServiceGeneration(
                        instance.getServiceGeneration() == null
                                ? 0L : instance.getServiceGeneration())
                .build();
        DiscoveryMutationResponse response = mutate(
                "discovery/register",
                ControlPlaneProtocol.DISCOVERY_REGISTER,
                ControlPlaneProtocol.DISCOVERY_REGISTER_REQUEST_TYPE,
                payload.toByteString(),
                UUID.randomUUID().toString());
        if (response.getInstancesCount() != 1) {
            throw new XuantongException("Discovery register returned no lease");
        }
        return toClientInstance(response.getInstances(0), true);
    }

    @Override
    public ServiceInstance heartbeat(ServiceInstance instance) {
        DiscoveryLeaseReference lease = lease(instance);
        long nextSequence = requiredNonNegative("renewSequence",
                instance.getRenewSequence()) + 1;
        DiscoveryRenewBatchRequest payload = DiscoveryRenewBatchRequest.newBuilder()
                .addRenewals(DiscoveryLeaseRenewal.newBuilder()
                        .setLease(lease)
                        .setRenewSequence(nextSequence)
                        .setTtlMs(leaseTtlMs(instance)))
                .build();
        DiscoveryMutationResponse response = mutate(
                "discovery/renew-batch",
                ControlPlaneProtocol.DISCOVERY_RENEW_BATCH,
                ControlPlaneProtocol.DISCOVERY_RENEW_BATCH_REQUEST_TYPE,
                payload.toByteString(),
                UUID.randomUUID().toString());
        if (response.getInstancesCount() != 1) {
            throw new XuantongException("Discovery renew returned no lease");
        }
        return toClientInstance(response.getInstances(0), true);
    }

    @Override
    public boolean deregister(ServiceInstance instance) {
        DiscoveryDeregisterRequest payload = DiscoveryDeregisterRequest.newBuilder()
                .setLease(lease(instance))
                .build();
        DiscoveryMutationResponse response = mutate(
                "discovery/deregister",
                ControlPlaneProtocol.DISCOVERY_DEREGISTER,
                ControlPlaneProtocol.DISCOVERY_DEREGISTER_REQUEST_TYPE,
                payload.toByteString(),
                UUID.randomUUID().toString());
        return response.getRemovedInstancesList().stream()
                .anyMatch(value -> instance.getInstanceId().equals(value.getInstanceId()));
    }

    private DiscoverySnapshotResponse snapshot(
            List<String> services, long minRevision) {
        try {
            DiscoverySnapshotRequest payload = DiscoverySnapshotRequest.newBuilder()
                    .setGroupName(group)
                    .addAllServiceNames(services)
                    .build();
            Envelope response = controlTransport.invokeControlPlane(
                    "discovery/snapshot",
                    ControlPlaneProtocol.DISCOVERY_SNAPSHOT,
                    RevisionType.REGISTRY,
                    0L,
                    Math.max(0L, minRevision),
                    "",
                    ControlPlaneProtocol.DISCOVERY_SNAPSHOT_REQUEST_TYPE,
                    payload.toByteString(),
                    ControlPlaneProtocol.DISCOVERY_SNAPSHOT_RESPONSE_TYPE);
            DiscoverySnapshotResponse snapshot = DiscoverySnapshotResponse.parseFrom(
                    response.getPayload());
            if (snapshot.getRegistryRevision() < minRevision) {
                throw new XuantongException("Discovery Registry revision moved backwards");
            }
            return snapshot;
        } catch (Exception e) {
            throw translate("Discovery snapshot failed", e);
        }
    }

    private DiscoveryMutationResponse mutate(
            String operation,
            String event,
            String requestType,
            com.google.protobuf.ByteString payload,
            String operationId) {
        try {
            Envelope response = controlTransport.invokeControlPlane(
                    operation,
                    event,
                    RevisionType.REGISTRY,
                    0L,
                    0L,
                    operationId,
                    requestType,
                    payload,
                    ControlPlaneProtocol.DISCOVERY_MUTATION_RESPONSE_TYPE);
            return DiscoveryMutationResponse.parseFrom(response.getPayload());
        } catch (SocketDTransport.ControlPlaneStatusException e) {
            throw translateLeaseStatus(operation, e);
        } catch (Exception original) {
            DiscoveryMutationResponse resolved = resolve(operationId);
            if (resolved != null) {
                return resolved;
            }
            throw translate(operation + " failed", original);
        }
    }

    private DiscoveryMutationResponse resolve(String operationId) {
        try {
            DiscoveryResolveOperationRequest payload =
                    DiscoveryResolveOperationRequest.newBuilder()
                            .setOperationId(operationId)
                            .build();
            Envelope response = controlTransport.invokeControlPlane(
                    "discovery/resolve-operation",
                    ControlPlaneProtocol.DISCOVERY_RESOLVE_OPERATION,
                    RevisionType.REGISTRY,
                    0L,
                    0L,
                    "",
                    ControlPlaneProtocol.DISCOVERY_RESOLVE_OPERATION_REQUEST_TYPE,
                    payload.toByteString(),
                    ControlPlaneProtocol.DISCOVERY_RESOLVE_OPERATION_RESPONSE_TYPE);
            DiscoveryResolveOperationResponse resolved =
                    DiscoveryResolveOperationResponse.parseFrom(response.getPayload());
            if (!resolved.getFound()) {
                return null;
            }
            if (resolved.getApplied() && resolved.hasResult()) {
                return resolved.getResult();
            }
            throw new XuantongException("Discovery operation was rejected: "
                    + resolved.getErrorCode() + ": " + resolved.getErrorMessage());
        } catch (XuantongException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    private DiscoveryLeaseReference lease(ServiceInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        return DiscoveryLeaseReference.newBuilder()
                .setInstance(ServiceInstanceCoordinate.newBuilder()
                        .setService(ServiceCoordinate.newBuilder()
                                .setNamespaceId(namespace)
                                .setGroupName(group)
                                .setServiceName(serviceName))
                        .setInstanceId(requireText("instanceId", instance.getInstanceId())))
                .setLeaseId(requireText("leaseId", instance.getLeaseId()))
                .setLeaseEpoch(requiredPositive("leaseEpoch", instance.getLeaseEpoch()))
                .setRecoveryEpoch(requiredPositive(
                        "recoveryEpoch", instance.getRecoveryEpoch()))
                .build();
    }

    private ServiceInstance toClientInstance(
            DiscoveryServiceInstance source, boolean requireService) {
        if (!source.hasInstance()) {
            throw new XuantongException("Discovery instance coordinate is missing");
        }
        validateCoordinate(source.getInstance(), requireService);
        ServiceCoordinate service = source.getInstance().getService();
        ServiceInstance target = new ServiceInstance();
        target.setNamespaceId(service.getNamespaceId());
        target.setGroupName(service.getGroupName());
        target.setServiceName(service.getServiceName());
        target.setInstanceId(source.getInstance().getInstanceId());
        target.setServiceGeneration(source.getServiceGeneration());
        target.setLeaseId(source.getLeaseId());
        target.setLeaseEpoch(source.getLeaseEpoch());
        target.setRecoveryEpoch(source.getRecoveryEpoch());
        target.setRenewSequence(source.getRenewSequence());
        target.setLeaseStartedAt(source.getRegisteredAtEpochMs());
        target.setExpiresAt(source.getExpiresAtEpochMs());
        target.setIp(source.getIp());
        target.setPort(source.getPort());
        target.setWeight(source.getWeight());
        target.setHealthy(true);
        target.setEnabled(source.getEnabled());
        target.setMetadata(source.getMetadata());
        target.setOwnerNodeId(source.getOwnerClientInstanceId());
        target.setRegisteredAt(source.getRegisteredAtEpochMs());
        target.setLastHeartbeatAt(source.getLastRenewedAtEpochMs());
        return target;
    }

    private void validateCoordinate(
            ServiceInstanceCoordinate coordinate, boolean requireService) {
        if (!coordinate.hasService()) {
            throw new XuantongException("Discovery service coordinate is missing");
        }
        validateScope(coordinate.getService(), requireService);
        requireText("instanceId", coordinate.getInstanceId());
    }

    private void validateScope(ServiceCoordinate coordinate, boolean requireService) {
        if (!namespace.equals(coordinate.getNamespaceId())
                || !group.equals(coordinate.getGroupName())
                || (requireService && !serviceName.equals(coordinate.getServiceName()))) {
            throw new XuantongException(
                    "Discovery response coordinate is outside the request scope");
        }
    }

    private RuntimeException translateLeaseStatus(
            String operation, SocketDTransport.ControlPlaneStatusException error) {
        if (error.code() == ResponseCode.LEASE_EXPIRED) {
            return new DiscoveryLeaseException(
                    DiscoveryLeaseException.Reason.EXPIRED,
                    operation + " failed because the lease expired",
                    error);
        }
        if (error.code() == ResponseCode.LEASE_FENCED) {
            return new DiscoveryLeaseException(
                    DiscoveryLeaseException.Reason.FENCED,
                    operation + " failed because the lease was fenced",
                    error);
        }
        if (error.code() == ResponseCode.SERVICE_FENCED) {
            return new DiscoveryLeaseException(
                    DiscoveryLeaseException.Reason.SERVICE_FENCED,
                    operation + " failed because the service definition generation was fenced",
                    error);
        }
        return new XuantongException(operation + " failed: " + error.getMessage(), error);
    }

    private RuntimeException translate(String message, Exception error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        return new XuantongException(message + ": " + error.getMessage(), error);
    }

    private long leaseTtlMs(ServiceInstance instance) {
        return leaseTtlMs;
    }

    private long requiredPositive(String field, Long value) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private long requiredNonNegative(String field, Long value) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private int requirePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return port;
    }

    private String requireName(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
        return value;
    }

    private String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Override
    public void setOnReconnect(Runnable listener) {
        controlTransport.setOnReconnect(listener);
    }

    @Override
    public void close() {
        controlTransport.close();
    }
}
