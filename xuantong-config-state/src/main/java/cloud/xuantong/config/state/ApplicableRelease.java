package cloud.xuantong.config.state;

/** Content selected by the same pure function used by fetch and push-triggered pull. */
public record ApplicableRelease(
        ConfigValueState state,
        ConfigKey configKey,
        long decisionRevision,
        ConfigContent content,
        String matchedRuleId) {

    public ApplicableRelease {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        matchedRuleId = matchedRuleId == null ? "" : matchedRuleId.trim();
        if (state == ConfigValueState.ACTIVE) {
            if (configKey == null || decisionRevision < 1 || content == null) {
                throw new IllegalArgumentException(
                        "found release requires key, decision revision and content");
            }
            if (!configKey.equals(content.configKey())) {
                throw new IllegalArgumentException("content belongs to another config key");
            }
        } else if (state == ConfigValueState.TOMBSTONE) {
            if (configKey == null || decisionRevision < 1 || content != null
                    || !matchedRuleId.isEmpty()) {
                throw new IllegalArgumentException(
                        "tombstone release requires only key and decision revision");
            }
        } else if (configKey != null || decisionRevision != 0 || content != null
                || !matchedRuleId.isEmpty()) {
            throw new IllegalArgumentException("missing release must not carry release data");
        }
    }

    public static ApplicableRelease missing() {
        return new ApplicableRelease(ConfigValueState.MISSING, null, 0, null, "");
    }

    public static ApplicableRelease tombstone(ReleaseDecision decision) {
        if (decision == null || !decision.tombstone()) {
            throw new IllegalArgumentException("tombstone decision is required");
        }
        return new ApplicableRelease(
                ConfigValueState.TOMBSTONE,
                decision.configKey(),
                decision.decisionRevision(),
                null,
                "");
    }

    public boolean found() {
        return state == ConfigValueState.ACTIVE;
    }

    public boolean tombstone() {
        return state == ConfigValueState.TOMBSTONE;
    }
}
