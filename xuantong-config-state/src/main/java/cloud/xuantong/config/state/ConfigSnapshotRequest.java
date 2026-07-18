package cloud.xuantong.config.state;

import java.util.List;

/** Empty configKeys means all keys hosted by the Config Group. */
public record ConfigSnapshotRequest(List<ConfigKey> configKeys) {
    public ConfigSnapshotRequest {
        configKeys = configKeys == null ? List.of() : configKeys.stream()
                .distinct()
                .sorted()
                .toList();
    }
}
