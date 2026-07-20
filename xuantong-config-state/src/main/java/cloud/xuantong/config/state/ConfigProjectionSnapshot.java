package cloud.xuantong.config.state;

import java.util.List;

/** Linearizable, payload-free Config State view for restore validation. */
public record ConfigProjectionSnapshot(
        long eventRevision,
        long compactionRevision,
        List<ConfigProjectionEntry> entries,
        boolean hasMore) {

    public ConfigProjectionSnapshot {
        if (eventRevision < 0 || compactionRevision < 0
                || compactionRevision > eventRevision) {
            throw new IllegalArgumentException("Config projection watermarks are invalid");
        }
        entries = List.copyOf(entries == null ? List.of() : entries);
        List<ConfigProjectionEntry> sorted = entries.stream()
                .sorted(java.util.Comparator.comparing(
                        ConfigProjectionEntry::configKey))
                .toList();
        if (!entries.equals(sorted)) {
            throw new IllegalArgumentException(
                    "Config projection entries must be sorted by config key");
        }
    }
}
