package cloud.xuantong.state.api;

public record ApplyContext(StateGroupId groupId, long term, long logIndex) {
    public ApplyContext {
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        if (term < 0) {
            throw new IllegalArgumentException("term must not be negative");
        }
        if (logIndex < 1) {
            throw new IllegalArgumentException("logIndex must be positive");
        }
    }
}
