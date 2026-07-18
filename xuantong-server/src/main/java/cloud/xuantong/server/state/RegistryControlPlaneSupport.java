package cloud.xuantong.server.state;

import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.DiscoveryMutationResponse;
import cloud.xuantong.protocol.v2.DiscoveryServiceInstance;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.protocol.v2.ServiceCoordinate;
import cloud.xuantong.protocol.v2.ServiceInstanceCoordinate;
import cloud.xuantong.registry.state.InstanceKey;
import cloud.xuantong.registry.state.RegistryInstance;
import cloud.xuantong.registry.state.RegistryMutationError;
import cloud.xuantong.registry.state.RegistryMutationResult;
import cloud.xuantong.registry.state.RegistryStateCodec;
import cloud.xuantong.registry.state.ServiceKey;
import cloud.xuantong.state.api.ApplyResult;
import cloud.xuantong.state.api.ApplyStatus;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevision;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class RegistryControlPlaneSupport {
    private RegistryControlPlaneSupport() {
    }

    static StateGroupId requireRegistryGroup(Envelope request) {
        if (request.getRevisionType() != RevisionType.REGISTRY) {
            throw new IllegalArgumentException("revisionType must be REGISTRY");
        }
        return StateGroupId.registry(required("groupId", request.getGroupId()));
    }

    static ServiceKey service(
            String authorizedNamespace,
            String authorizedGroup,
            String requestedNamespace,
            String groupName,
            String serviceName) {
        String namespace = requestedNamespace == null || requestedNamespace.isBlank()
                ? required("namespaceId", authorizedNamespace)
                : requestedNamespace.trim();
        if (!namespace.equals(required("namespaceId", authorizedNamespace))) {
            throw new IllegalArgumentException(
                    "Service coordinate is outside the authorized namespace");
        }
        String group = required("groupName", groupName);
        if (!group.equals(required("authorizedGroup", authorizedGroup))) {
            throw new IllegalArgumentException(
                    "Service coordinate is outside the authorized group");
        }
        return new ServiceKey(namespace, group, serviceName);
    }

    static InstanceKey instance(
            String authorizedNamespace,
            String authorizedGroup,
            ServiceInstanceCoordinate coordinate) {
        if (coordinate == null || !coordinate.hasService()) {
            throw new IllegalArgumentException("Service instance coordinate is required");
        }
        ServiceCoordinate service = coordinate.getService();
        return new InstanceKey(
                service(
                        authorizedNamespace,
                        authorizedGroup,
                        service.getNamespaceId(),
                        service.getGroupName(),
                        service.getServiceName()),
                coordinate.getInstanceId());
    }

    static ServiceCoordinate coordinate(ServiceKey key) {
        return ServiceCoordinate.newBuilder()
                .setNamespaceId(key.namespace())
                .setGroupName(key.group())
                .setServiceName(key.serviceName())
                .build();
    }

    static ServiceInstanceCoordinate coordinate(InstanceKey key) {
        return ServiceInstanceCoordinate.newBuilder()
                .setService(coordinate(key.service()))
                .setInstanceId(key.instanceId())
                .build();
    }

    static DiscoveryServiceInstance instance(RegistryInstance value) {
        return DiscoveryServiceInstance.newBuilder()
                .setInstance(coordinate(value.instanceKey()))
                .setIp(value.registration().ip())
                .setPort(value.registration().port())
                .setWeight(value.registration().weight())
                .setEnabled(value.registration().enabled())
                .setMetadata(value.registration().metadata())
                .setLeaseId(value.leaseId())
                .setLeaseEpoch(value.leaseEpoch())
                .setRecoveryEpoch(value.recoveryEpoch())
                .setRenewSequence(value.renewSequence())
                .setOwnerClientInstanceId(value.ownerClientInstanceId())
                .setOwnerApplicationName(value.ownerApplicationName())
                .setRegisteredAtEpochMs(value.registeredAtEpochMs())
                .setLastRenewedAtEpochMs(value.lastRenewedAtEpochMs())
                .setExpiresAtEpochMs(value.expiresAtEpochMs())
                .setServiceGeneration(value.registration().serviceGeneration())
                .build();
    }

    static DiscoveryMutationResponse mutation(RegistryMutationResult result) {
        DiscoveryMutationResponse.Builder response = DiscoveryMutationResponse.newBuilder()
                .setAction(result.action())
                .setRegistryRevision(result.registryRevision())
                .setServerTimeEpochMs(result.serverTimeEpochMs());
        result.instances().forEach(value -> response.addInstances(instance(value)));
        result.removedInstances().forEach(value -> response.addRemovedInstances(
                coordinate(value)));
        return response.build();
    }

    static ControlPlaneReply mutationReply(ApplyResult result) {
        try {
            if (result.status() == ApplyStatus.REJECTED) {
                if (!RegistryStateCodec.RESULT_MUTATION_ERROR.equals(result.resultType())) {
                    return internal("Registry State returned an unexpected rejection type");
                }
                RegistryMutationError error = RegistryStateCodec.decodeMutationError(
                        result.payload());
                return ControlPlaneReply.failure(status(error));
            }
            if (!RegistryStateCodec.RESULT_MUTATION.equals(result.resultType())) {
                return internal("Registry State returned an unexpected mutation type");
            }
            RegistryMutationResult mutation = RegistryStateCodec.decodeMutationResult(
                    result.payload());
            return ok(
                    cloud.xuantong.protocol.v2.ControlPlaneProtocol
                            .DISCOVERY_MUTATION_RESPONSE_TYPE,
                    mutation(mutation).toByteArray());
        } catch (IOException e) {
            return internal("Registry State returned an invalid mutation payload");
        }
    }

    static ReadOptions readOptions(Envelope request, StateGroupId groupId) {
        return request.getMinRevision() == 0
                ? ReadOptions.linearizable()
                : ReadOptions.linearizable(StateRevision.registry(
                        groupId, request.getMinRevision()));
    }

    static CompletionStage<ControlPlaneReply> invalid(String message) {
        return CompletableFuture.completedFuture(ControlPlaneReply.failure(
                ResponseStatus.newBuilder()
                        .setCode(ResponseCode.INVALID_ARGUMENT)
                        .setMessage(message == null ? "Invalid discovery request" : message)
                        .setRetryable(false)
                        .setCommitStatus(CommitStatus.NOT_APPLICABLE)
                        .build()));
    }

    static ControlPlaneReply ok(String payloadType, byte[] payload) {
        return ControlPlaneReply.ok(payloadType, ByteString.copyFrom(payload));
    }

    static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static ResponseStatus status(RegistryMutationError error) {
        ResponseCode code = switch (error.code()) {
            case "OPERATION_ID_CONFLICT" -> ResponseCode.OPERATION_CONFLICT;
            case "LEASE_FENCED" -> ResponseCode.LEASE_FENCED;
            case "LEASE_EXPIRED" -> ResponseCode.LEASE_EXPIRED;
            case "SERVICE_DEFINITION_NOT_ACTIVE", "SERVICE_GENERATION_FENCED" ->
                    ResponseCode.SERVICE_FENCED;
            case "INSTANCE_ALREADY_OWNED" -> ResponseCode.FAILED_PRECONDITION;
            case "REGISTRY_CAPACITY_EXCEEDED", "SERVICE_CAPACITY_EXCEEDED",
                    "IDEMPOTENCY_CAPACITY_EXCEEDED" ->
                    ResponseCode.STATE_UNAVAILABLE;
            default -> ResponseCode.INVALID_ARGUMENT;
        };
        return ResponseStatus.newBuilder()
                .setCode(code)
                .setMessage(error.message())
                .setRetryable(code == ResponseCode.STATE_UNAVAILABLE)
                .setCommitStatus(CommitStatus.NOT_APPLICABLE)
                .build();
    }

    private static ControlPlaneReply internal(String message) {
        return ControlPlaneReply.failure(ResponseStatus.newBuilder()
                .setCode(ResponseCode.INTERNAL_ERROR)
                .setMessage(message)
                .setRetryable(false)
                .setCommitStatus(CommitStatus.NOT_APPLICABLE)
                .build());
    }
}
