package cloud.xuantong.config.state;

import java.util.List;

/** Linearizable decision snapshot used before opening a resumable Watch. */
public record ConfigSnapshot(
        long eventRevision,
        long compactionRevision,
        List<ReleaseDecision> decisions) {

    public ConfigSnapshot {
        if (eventRevision < 0) {
            throw new IllegalArgumentException("eventRevision must not be negative");
        }
        if (compactionRevision < 0 || compactionRevision > eventRevision) {
            throw new IllegalArgumentException(
                    "compactionRevision must be between zero and eventRevision");
        }
        decisions = List.copyOf(decisions == null ? List.of() : decisions);
        List<ReleaseDecision> sorted = decisions.stream()
                .sorted(java.util.Comparator.comparing(ReleaseDecision::configKey))
                .toList();
        if (!decisions.equals(sorted)) {
            throw new IllegalArgumentException("decisions must be sorted by config key");
        }
    }
}
