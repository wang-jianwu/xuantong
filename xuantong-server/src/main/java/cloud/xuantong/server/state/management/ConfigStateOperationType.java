package cloud.xuantong.server.state.management;

enum ConfigStateOperationType {
    PUBLISH,
    ROLLOUT_START,
    ROLLOUT_PROMOTE,
    ROLLOUT_ABORT,
    ROLLBACK
}
