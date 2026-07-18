package cloud.xuantong.config.management.service;

import cloud.xuantong.config.management.model.ConfigRelease;
import cloud.xuantong.config.management.model.ConfigRollout;

public record ConfigRolloutMutation(ConfigRollout rollout, ConfigRelease release) {
}
