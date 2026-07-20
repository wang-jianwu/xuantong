package cloud.xuantong.config.state;

/** Minimal rollout identity required to validate the SQL projection. */
public record ConfigRolloutDigest(
        String ruleId,
        long targetContentRevision,
        RolloutRuleStatus status) {

    public ConfigRolloutDigest {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        ruleId = ruleId.trim();
        if (targetContentRevision < 1) {
            throw new IllegalArgumentException("targetContentRevision must be positive");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    public static ConfigRolloutDigest from(RolloutRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
        return new ConfigRolloutDigest(
                rule.ruleId(), rule.targetContentRevision(), rule.status());
    }
}
