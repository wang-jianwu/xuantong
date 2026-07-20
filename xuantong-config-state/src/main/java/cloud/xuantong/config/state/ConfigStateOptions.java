package cloud.xuantong.config.state;

public record ConfigStateOptions(
        int maxInlineContentBytes,
        int changeLogCapacity,
        int maxOperationRecords,
        int operationReplayWindow,
        int maxRulesPerDecision) {

    public ConfigStateOptions(
            int maxInlineContentBytes,
            int changeLogCapacity,
            int maxOperationRecords,
            int maxRulesPerDecision) {
        this(maxInlineContentBytes, changeLogCapacity, maxOperationRecords,
                maxOperationRecords, maxRulesPerDecision);
    }

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
        if (operationReplayWindow < 1 || operationReplayWindow > maxOperationRecords) {
            throw new IllegalArgumentException(
                    "operationReplayWindow must be between 1 and maxOperationRecords");
        }
        if (maxRulesPerDecision < 1) {
            throw new IllegalArgumentException("maxRulesPerDecision must be positive");
        }
    }

    public static ConfigStateOptions defaults() {
        return new ConfigStateOptions(
                1024 * 1024, 10_000, 100_000, 75_000, 128);
    }
}
