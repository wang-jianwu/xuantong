package cloud.xuantong.config.state;

/** Content selected by the same pure function used by fetch and push-triggered pull. */
public record ApplicableRelease(
        boolean found,
        ConfigKey configKey,
        long decisionRevision,
        ConfigContent content,
        String matchedRuleId) {

    public ApplicableRelease {
        matchedRuleId = matchedRuleId == null ? "" : matchedRuleId.trim();
        if (found) {
            if (configKey == null || decisionRevision < 1 || content == null) {
                throw new IllegalArgumentException(
                        "found release requires key, decision revision and content");
            }
            if (!configKey.equals(content.configKey())) {
                throw new IllegalArgumentException("content belongs to another config key");
            }
        } else if (configKey != null || decisionRevision != 0 || content != null
                || !matchedRuleId.isEmpty()) {
            throw new IllegalArgumentException("missing release must not carry release data");
        }
    }

    public static ApplicableRelease missing() {
        return new ApplicableRelease(false, null, 0, null, "");
    }
}
