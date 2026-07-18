package cloud.xuantong.raft.ratis;

import java.nio.file.Path;
import java.time.Duration;

public record RatisNodeOptions(
        String localNodeId,
        RatisGroupDefinition bootstrapGroup,
        Path storageDirectory,
        Duration electionTimeoutMin,
        Duration electionTimeoutMax,
        Duration requestTimeout,
        long snapshotAutoTriggerThreshold,
        boolean snapshotOnShutdown) {

    public RatisNodeOptions {
        if (localNodeId == null || localNodeId.isBlank()) {
            throw new IllegalArgumentException("localNodeId must not be blank");
        }
        localNodeId = localNodeId.trim();
        if (bootstrapGroup == null) {
            throw new IllegalArgumentException("bootstrapGroup must not be null");
        }
        bootstrapGroup.requirePeer(localNodeId);
        if (storageDirectory == null) {
            throw new IllegalArgumentException("storageDirectory must not be null");
        }
        electionTimeoutMin = positive("electionTimeoutMin", electionTimeoutMin);
        electionTimeoutMax = positive("electionTimeoutMax", electionTimeoutMax);
        requestTimeout = positive("requestTimeout", requestTimeout);
        if (electionTimeoutMax.compareTo(electionTimeoutMin) < 0) {
            throw new IllegalArgumentException(
                    "electionTimeoutMax must be greater than or equal to electionTimeoutMin");
        }
        if (snapshotAutoTriggerThreshold < 1) {
            throw new IllegalArgumentException("snapshotAutoTriggerThreshold must be positive");
        }
    }

    public static RatisNodeOptions productionDefaults(
            String localNodeId, RatisGroupDefinition group, Path storageDirectory) {
        return new RatisNodeOptions(
                localNodeId,
                group,
                storageDirectory,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                10_000L,
                true);
    }

    private static Duration positive(String field, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
