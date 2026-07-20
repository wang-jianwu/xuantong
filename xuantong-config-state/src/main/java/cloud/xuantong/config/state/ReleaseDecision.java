package cloud.xuantong.config.state;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Current stable fallback and ordered rollout rules for one config key. */
public record ReleaseDecision(
        ConfigKey configKey,
        long decisionRevision,
        ConfigDecisionState state,
        long stableContentRevision,
        List<RolloutRule> rules) {

    public ReleaseDecision {
        if (configKey == null) {
            throw new IllegalArgumentException("configKey must not be null");
        }
        if (decisionRevision < 1) {
            throw new IllegalArgumentException("decisionRevision must be positive");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        rules = List.copyOf(rules == null ? List.of() : rules);
        if (state == ConfigDecisionState.TOMBSTONE) {
            if (stableContentRevision != 0 || !rules.isEmpty()) {
                throw new IllegalArgumentException(
                        "tombstone decision must not reference content or rollout rules");
            }
        } else {
            if (stableContentRevision < 1) {
                throw new IllegalArgumentException(
                        "active decision requires a positive stableContentRevision");
            }
            List<RolloutRule> sorted = rules.stream().sorted(RolloutRule.SELECTION_ORDER).toList();
            if (!rules.equals(sorted)) {
                throw new IllegalArgumentException(
                        "rules must be sorted by priority descending and ruleId ascending");
            }
            Set<String> ids = new HashSet<>();
            Set<String> activeSelectorCoordinates = new HashSet<>();
            for (RolloutRule rule : rules) {
                if (!ids.add(rule.ruleId())) {
                    throw new IllegalArgumentException("duplicate ruleId: " + rule.ruleId());
                }
                if (rule.status() != RolloutRuleStatus.ACTIVE) {
                    continue;
                }
                if (rule.selectorType() == RolloutSelectorType.PERCENTAGE) {
                    requireUnique(activeSelectorCoordinates,
                            rule.priority() + "\u0000PERCENTAGE");
                    continue;
                }
                for (String value : rule.selectorValues()) {
                    requireUnique(activeSelectorCoordinates,
                            rule.priority() + "\u0000" + rule.selectorType()
                                    + "\u0000" + rule.selectorKey() + "\u0000" + value);
                }
            }
        }
    }

    public ReleaseDecision(
            ConfigKey configKey,
            long decisionRevision,
            long stableContentRevision,
            List<RolloutRule> rules) {
        this(configKey, decisionRevision, ConfigDecisionState.ACTIVE,
                stableContentRevision, rules);
    }

    public boolean active() {
        return state == ConfigDecisionState.ACTIVE;
    }

    public boolean tombstone() {
        return state == ConfigDecisionState.TOMBSTONE;
    }

    private static void requireUnique(Set<String> coordinates, String coordinate) {
        if (!coordinates.add(coordinate)) {
            throw new IllegalArgumentException(
                    "conflicting active selectors at the same priority");
        }
    }
}
