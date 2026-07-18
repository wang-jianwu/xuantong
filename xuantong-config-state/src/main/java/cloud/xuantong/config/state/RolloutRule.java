package cloud.xuantong.config.state;

import java.util.Comparator;
import java.util.List;

/** Resolved immutable rollout rule stored in a release decision. */
public record RolloutRule(
        String ruleId,
        long ruleGeneration,
        int selectorVersion,
        int priority,
        long targetContentRevision,
        String rolloutKey,
        RolloutSelectorType selectorType,
        String selectorKey,
        List<String> selectorValues,
        int percentageBasisPoints,
        long seed,
        RolloutRuleStatus status,
        long activationDecisionRevision) {

    public static final Comparator<RolloutRule> SELECTION_ORDER = Comparator
            .comparingInt(RolloutRule::priority).reversed()
            .thenComparing(RolloutRule::ruleId);

    public RolloutRule {
        RolloutRuleDraft validated = new RolloutRuleDraft(
                ruleId,
                ruleGeneration,
                selectorVersion,
                priority,
                ConfigContentReference.existing(targetContentRevision),
                rolloutKey,
                selectorType,
                selectorKey,
                selectorValues,
                percentageBasisPoints,
                seed,
                status);
        ruleId = validated.ruleId();
        rolloutKey = validated.rolloutKey();
        selectorKey = validated.selectorKey();
        selectorValues = validated.selectorValues();
        if (activationDecisionRevision < 1) {
            throw new IllegalArgumentException(
                    "activationDecisionRevision must be positive");
        }
    }

    String conflictCoordinate() {
        return priority + "\u0000" + selectorType + "\u0000" + selectorKey
                + "\u0000" + String.join("\u0001", selectorValues)
                + "\u0000" + percentageBasisPoints;
    }
}
