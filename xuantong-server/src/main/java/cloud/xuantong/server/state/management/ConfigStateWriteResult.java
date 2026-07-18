package cloud.xuantong.server.state.management;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigRollout;

/** Business result is authoritative even when its SQL projection is still repairing. */
public record ConfigStateWriteResult(
        ConfigRelease release,
        ConfigRollout rollout,
        boolean projectionPending) {
}
