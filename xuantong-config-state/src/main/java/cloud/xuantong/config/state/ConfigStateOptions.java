package cloud.xuantong.config.state;

public record ConfigStateOptions(
        int maxInlineContentBytes,
        int changeLogCapacity,
        int maxOperationRecords,
        int maxRulesPerDecision) {

    public ConfigStateOptions {
        if (maxInlineContentBytes < 1) {
            throw new IllegalArgumentException("maxInlineContentBytes must be positive");
        }
        if (changeLogCapacity < 1) {
            throw new IllegalArgumentException("changeLogCapacity must be positive");
        }
        if (maxOperationRecords < 1) {
            throw new IllegalArgumentException("maxOperationRecords must be positive");
        }
        if (maxRulesPerDecision < 1) {
            throw new IllegalArgumentException("maxRulesPerDecision must be positive");
        }
    }

    public static ConfigStateOptions defaults() {
        return new ConfigStateOptions(1024 * 1024, 10_000, 100_000, 128);
    }
}
