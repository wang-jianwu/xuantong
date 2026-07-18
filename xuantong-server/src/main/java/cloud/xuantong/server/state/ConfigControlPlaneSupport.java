package cloud.xuantong.server.state;

import cloud.xuantong.config.state.ConfigKey;
import cloud.xuantong.gateway.socketd.ControlPlaneReply;
import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.ConfigCoordinate;
import cloud.xuantong.protocol.v2.Envelope;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.state.api.ReadOptions;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevision;
import com.google.protobuf.ByteString;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class ConfigControlPlaneSupport {
    private ConfigControlPlaneSupport() {
    }

    static StateGroupId requireConfigGroup(Envelope request, RevisionType expectedType) {
        if (request.getRevisionType() != expectedType) {
            throw new IllegalArgumentException(
                    "revisionType must be " + expectedType.name());
        }
        return StateGroupId.config(required("groupId", request.getGroupId()));
    }

    static ConfigKey key(
            String authorizedNamespace,
            String authorizedGroup,
            String requestedNamespace,
            String groupName,
            String dataId) {
        String namespace = requestedNamespace == null || requestedNamespace.isBlank()
                ? required("namespaceId", authorizedNamespace)
                : requestedNamespace.trim();
        if (!namespace.equals(required("namespaceId", authorizedNamespace))) {
            throw new IllegalArgumentException(
                    "Config coordinate is outside the authorized namespace");
        }
        String group = required("groupName", groupName);
        if (!group.equals(required("authorizedGroup", authorizedGroup))) {
            throw new IllegalArgumentException(
                    "Config coordinate is outside the authorized group");
        }
        return new ConfigKey(namespace, group, dataId);
    }

    static ConfigCoordinate coordinate(ConfigKey key) {
        return ConfigCoordinate.newBuilder()
                .setNamespaceId(key.namespace())
                .setGroupName(key.group())
                .setDataId(key.dataId())
                .build();
    }

    static ReadOptions readOptions(
            Envelope request, StateGroupId groupId, ConfigKey configKey) {
        if (request.getMinRevision() == 0) {
            return ReadOptions.linearizable();
        }
        return switch (request.getRevisionType()) {
            case CONFIG_DECISION -> ReadOptions.linearizable(
                    StateRevision.configDecision(
                            groupId, configKey.canonicalName(), request.getMinRevision()));
            case CONFIG_EVENT -> ReadOptions.linearizable(
                    StateRevision.configEvent(groupId, request.getMinRevision()));
            default -> throw new IllegalArgumentException(
                    "Config request requires CONFIG_DECISION or CONFIG_EVENT revisionType");
        };
    }

    static CompletionStage<ControlPlaneReply> invalid(String message) {
        return CompletableFuture.completedFuture(ControlPlaneReply.failure(
                ResponseStatus.newBuilder()
                        .setCode(ResponseCode.INVALID_ARGUMENT)
                        .setMessage(message == null ? "Invalid config request" : message)
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
}
