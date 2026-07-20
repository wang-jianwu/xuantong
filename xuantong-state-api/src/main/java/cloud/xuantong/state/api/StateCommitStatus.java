package cloud.xuantong.state.api;

/** Whether a failed call may still have produced a committed write. */
public enum StateCommitStatus {
    NOT_APPLICABLE,
    NOT_COMMITTED,
    UNKNOWN
}
