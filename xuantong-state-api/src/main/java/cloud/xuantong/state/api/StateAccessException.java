package cloud.xuantong.state.api;

public final class StateAccessException extends RuntimeException {
    private final StateFailureCode code;
    private final StateGroupId groupId;
    private final boolean retryable;
    private final long retryAfterMs;
    private final StateRevision observedRevision;
    private final StateCommitStatus commitStatus;

    public StateAccessException(
            StateFailureCode code,
            StateGroupId groupId,
            String message,
            boolean retryable,
            long retryAfterMs,
            StateRevision observedRevision,
            StateCommitStatus commitStatus,
            Throwable cause) {
        super(failureMessage(code, message), cause);
        this.code = code;
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        this.groupId = groupId;
        if (retryAfterMs < 0) {
            throw new IllegalArgumentException("retryAfterMs must not be negative");
        }
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
        if (observedRevision != null
                && !groupId.equals(observedRevision.groupId())) {
            throw new IllegalArgumentException(
                    "observedRevision must belong to failure group " + groupId);
        }
        this.observedRevision = observedRevision;
        if (commitStatus == null) {
            throw new IllegalArgumentException("commitStatus must not be null");
        }
        this.commitStatus = commitStatus;
    }

    public static StateAccessException retryable(
            StateFailureCode code,
            StateGroupId groupId,
            String message,
            StateCommitStatus commitStatus,
            Throwable cause) {
        return new StateAccessException(
                code, groupId, message, true, 0L, null, commitStatus, cause);
    }

    public static StateAccessException nonRetryable(
            StateFailureCode code,
            StateGroupId groupId,
            String message,
            StateCommitStatus commitStatus,
            Throwable cause) {
        return new StateAccessException(
                code, groupId, message, false, 0L, null, commitStatus, cause);
    }

    public StateFailureCode code() {
        return code;
    }

    public StateGroupId groupId() {
        return groupId;
    }

    public boolean retryable() {
        return retryable;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    public StateRevision observedRevision() {
        return observedRevision;
    }

    public StateCommitStatus commitStatus() {
        return commitStatus;
    }

    private static String failureMessage(StateFailureCode code, String message) {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        return message == null || message.isBlank() ? code.name() : message;
    }
}
