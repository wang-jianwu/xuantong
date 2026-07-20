package cloud.xuantong.state.api;

/**
 * Synchronous fail-closed admission check performed immediately before a write
 * is handed to the authoritative State implementation.
 */
@FunctionalInterface
public interface StateWriteAdmission {
    void check(StateGroupId groupId);

    static StateWriteAdmission allowAll() {
        return groupId -> {
        };
    }
}
