package cloud.xuantong.config.state;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One authoritative decision and hashes for every content revision it references. */
public record ConfigProjectionEntry(
        ConfigKey configKey,
        long decisionRevision,
        ConfigDecisionState state,
        long stableContentRevision,
        List<ConfigRolloutDigest> rules,
        List<ConfigContentDigest> referencedContents) {

    public ConfigProjectionEntry {
        if (configKey == null || decisionRevision < 1 || state == null) {
            throw new IllegalArgumentException(
                    "configKey, decisionRevision and state are required");
        }
        rules = List.copyOf(rules == null ? List.of() : rules);
        referencedContents = List.copyOf(
                referencedContents == null ? List.of() : referencedContents);
        List<ConfigContentDigest> sorted = referencedContents.stream()
                .sorted(java.util.Comparator.comparingLong(
                        ConfigContentDigest::contentRevision))
                .toList();
        if (!referencedContents.equals(sorted)) {
            throw new IllegalArgumentException(
                    "referencedContents must be sorted by contentRevision");
        }
        Set<Long> expected = new LinkedHashSet<>();
        if (state == ConfigDecisionState.ACTIVE) {
            if (stableContentRevision < 1) {
                throw new IllegalArgumentException(
                        "active projection requires stableContentRevision");
            }
            expected.add(stableContentRevision);
            rules.forEach(rule -> expected.add(rule.targetContentRevision()));
        } else if (stableContentRevision != 0 || !rules.isEmpty()) {
            throw new IllegalArgumentException(
                    "tombstone projection must not reference content or rules");
        }
        Set<Long> actual = new LinkedHashSet<>();
        referencedContents.forEach(content -> {
            if (!actual.add(content.contentRevision())) {
                throw new IllegalArgumentException("duplicate referenced content revision");
            }
        });
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "referenced contents do not match decision content revisions");
        }
    }
}
