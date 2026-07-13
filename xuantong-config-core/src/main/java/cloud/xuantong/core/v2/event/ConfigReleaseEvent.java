package cloud.xuantong.core.v2.event;

import cloud.xuantong.core.v2.model.ConfigRelease;

public record ConfigReleaseEvent(String eventType, ConfigRelease release) {
}
