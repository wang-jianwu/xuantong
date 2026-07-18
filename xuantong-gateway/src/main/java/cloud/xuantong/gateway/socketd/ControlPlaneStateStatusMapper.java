package cloud.xuantong.gateway.socketd;

import cloud.xuantong.protocol.v2.CommitStatus;
import cloud.xuantong.protocol.v2.ResponseCode;
import cloud.xuantong.protocol.v2.ResponseStatus;
import cloud.xuantong.protocol.v2.RevisionType;
import cloud.xuantong.state.api.StateAccessException;
import cloud.xuantong.state.api.StateCommitStatus;
import cloud.xuantong.state.api.StateFailureCode;
import cloud.xuantong.state.api.StateGroupId;
import cloud.xuantong.state.api.StateRevision;
import cloud.xuantong.state.api.StateRevisionType;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class ControlPlaneStateStatusMapper {
    private ControlPlaneStateStatusMapper() {
    }

    static ResponseStatus map(Throwable failure, StateGroupId fallbackGroup) {
        Throwable unwrapped = unwrap(failure);
        if (!(unwrapped instanceof StateAccessException stateFailure)) {
            return ResponseStatus.newBuilder()
                    .setCode(ResponseCode.INTERNAL_ERROR)
                    .setMessage("Internal control-plane error")
                    .setRetryable(false)
                    .setGroupId(fallbackGroup == null ? "" : fallbackGroup.value())
                    .setCommitStatus(CommitStatus.COMMIT_STATUS_UNSPECIFIED)
                    .build();
        }

        ResponseStatus.Builder status = ResponseStatus.newBuilder()
                .setCode(responseCode(stateFailure.code()))
                .setMessage(stateFailure.getMessage())
                .setRetryable(stateFailure.retryable())
                .setRetryAfterMs(stateFailure.retryAfterMs())
                .setGroupId(stateFailure.groupId().value())
                .setCommitStatus(commitStatus(stateFailure.commitStatus()));
        StateRevision observed = stateFailure.observedRevision();
        if (observed != null) {
            status.setRevisionType(revisionType(observed.type()))
                    .setObservedRevision(observed.value());
        }
        return status.build();
    }

    private static ResponseCode responseCode(StateFailureCode code) {
        return switch (code) {
            case DEADLINE_EXCEEDED -> ResponseCode.DEADLINE_EXCEEDED;
            case NOT_LEADER, STATE_UNAVAILABLE -> ResponseCode.STATE_UNAVAILABLE;
            case NO_QUORUM -> ResponseCode.NO_QUORUM;
            case STALE_REPLICA -> ResponseCode.STALE_REPLICA;
            case REVISION_COMPACTED -> ResponseCode.REVISION_COMPACTED;
            case OPERATION_CONFLICT -> ResponseCode.OPERATION_CONFLICT;
            case LEASE_FENCED -> ResponseCode.LEASE_FENCED;
            case LEASE_EXPIRED -> ResponseCode.LEASE_EXPIRED;
            case UNAUTHORIZED -> ResponseCode.UNAUTHORIZED;
            case RATE_LIMITED -> ResponseCode.RATE_LIMITED;
            case INTERNAL_ERROR -> ResponseCode.INTERNAL_ERROR;
        };
    }

    private static CommitStatus commitStatus(StateCommitStatus status) {
        return switch (status) {
            case NOT_APPLICABLE -> CommitStatus.NOT_APPLICABLE;
            case UNKNOWN -> CommitStatus.UNKNOWN;
        };
    }

    private static RevisionType revisionType(StateRevisionType type) {
        return switch (type) {
            case CONFIG_DECISION -> RevisionType.CONFIG_DECISION;
            case CONFIG_EVENT -> RevisionType.CONFIG_EVENT;
            case REGISTRY -> RevisionType.REGISTRY;
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
