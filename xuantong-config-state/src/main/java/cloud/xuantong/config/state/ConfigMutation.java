package cloud.xuantong.config.state;

import java.util.List;

/**
 * Atomic release decision mutation.
 *
 * <p>The expected revision is mandatory: zero creates the first decision and
 * later mutations must name the exact current revision. A new immutable
 * content may be referenced by the stable fallback or any rollout rule in the
 * same command.</p>
 */
public record ConfigMutation(
        ConfigActor actor,
        ConfigKey configKey,
        long expectedDecisionRevision,
        ConfigContentDraft newContent,
        ConfigContentReference stableContent,
        List<RolloutRuleDraft> rules) {

    public ConfigMutation {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        if (configKey == null) {
            throw new IllegalArgumentException("configKey must not be null");
        }
        if (expectedDecisionRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedDecisionRevision must not be negative");
        }
        if (stableContent == null) {
            throw new IllegalArgumentException("stableContent must not be null");
        }
        rules = List.copyOf(rules == null ? List.of() : rules);
        boolean referencesNewContent = stableContent instanceof ConfigContentReference.NewContent
                || rules.stream().anyMatch(rule ->
                        rule.targetContent() instanceof ConfigContentReference.NewContent);
        if (referencesNewContent != (newContent != null)) {
            throw new IllegalArgumentException(
                    "new content must be present exactly when the decision references it");
        }
    }
}
