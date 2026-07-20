package cloud.xuantong.raft.ratis;

import java.nio.file.Path;
import java.time.Duration;

public record RatisNodeOptions(
        String localNodeId,
        RatisGroupDefinition bootstrapGroup,
        Path storageDirectory,
        long storageFreeSpaceMinBytes,
        Duration electionTimeoutMin,
        Duration electionTimeoutMax,
        Duration requestTimeout,
        long snapshotAutoTriggerThreshold,
        boolean snapshotOnShutdown,
        int snapshotRetentionFileCount,
        RatisStartupMode startupMode,
        String rpcBindHost,
        int rpcBindPort,
        long logPreallocatedSizeBytes) {

    public static final int DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT = 3;
    public static final long DEFAULT_STORAGE_FREE_SPACE_MIN_BYTES =
            512L * 1024L * 1024L;
    public static final long DEFAULT_LOG_PREALLOCATED_SIZE_BYTES =
            4L * 1024L * 1024L;

    public RatisNodeOptions(
            String localNodeId,
            RatisGroupDefinition bootstrapGroup,
            Path storageDirectory,
            long storageFreeSpaceMinBytes,
            Duration electionTimeoutMin,
            Duration electionTimeoutMax,
            Duration requestTimeout,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown,
            int snapshotRetentionFileCount,
            RatisStartupMode startupMode,
            String rpcBindHost,
            int rpcBindPort) {
        this(localNodeId, bootstrapGroup, storageDirectory,
                storageFreeSpaceMinBytes,
                electionTimeoutMin, electionTimeoutMax, requestTimeout,
                snapshotAutoTriggerThreshold, snapshotOnShutdown,
                snapshotRetentionFileCount, startupMode, rpcBindHost, rpcBindPort,
                DEFAULT_LOG_PREALLOCATED_SIZE_BYTES);
    }

    public RatisNodeOptions(
            String localNodeId,
            RatisGroupDefinition bootstrapGroup,
            Path storageDirectory,
            Duration electionTimeoutMin,
            Duration electionTimeoutMax,
            Duration requestTimeout,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown) {
        this(localNodeId, bootstrapGroup, storageDirectory,
                0L,
                electionTimeoutMin, electionTimeoutMax, requestTimeout,
                snapshotAutoTriggerThreshold, snapshotOnShutdown,
                DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT,
                RatisStartupMode.BOOTSTRAP_OR_RECOVER,
                peerHost(bootstrapGroup, localNodeId),
                peerPort(bootstrapGroup, localNodeId));
    }

    public RatisNodeOptions(
            String localNodeId,
            RatisGroupDefinition bootstrapGroup,
            Path storageDirectory,
            Duration electionTimeoutMin,
            Duration electionTimeoutMax,
            Duration requestTimeout,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown,
            RatisStartupMode startupMode) {
        this(localNodeId, bootstrapGroup, storageDirectory,
                0L,
                electionTimeoutMin, electionTimeoutMax, requestTimeout,
                snapshotAutoTriggerThreshold, snapshotOnShutdown,
                DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT, startupMode,
                peerHost(bootstrapGroup, localNodeId),
                peerPort(bootstrapGroup, localNodeId));
    }

    public RatisNodeOptions(
            String localNodeId,
            RatisGroupDefinition bootstrapGroup,
            Path storageDirectory,
            Duration electionTimeoutMin,
            Duration electionTimeoutMax,
            Duration requestTimeout,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown,
            int snapshotRetentionFileCount,
            RatisStartupMode startupMode) {
        this(localNodeId, bootstrapGroup, storageDirectory,
                0L,
                electionTimeoutMin, electionTimeoutMax, requestTimeout,
                snapshotAutoTriggerThreshold, snapshotOnShutdown,
                snapshotRetentionFileCount, startupMode,
                peerHost(bootstrapGroup, localNodeId),
                peerPort(bootstrapGroup, localNodeId));
    }

    public RatisNodeOptions(
            String localNodeId,
            RatisGroupDefinition bootstrapGroup,
            Path storageDirectory,
            long storageFreeSpaceMinBytes,
            Duration electionTimeoutMin,
            Duration electionTimeoutMax,
            Duration requestTimeout,
            long snapshotAutoTriggerThreshold,
            boolean snapshotOnShutdown,
            int snapshotRetentionFileCount,
            RatisStartupMode startupMode) {
        this(localNodeId, bootstrapGroup, storageDirectory,
                storageFreeSpaceMinBytes,
                electionTimeoutMin, electionTimeoutMax, requestTimeout,
                snapshotAutoTriggerThreshold, snapshotOnShutdown,
                snapshotRetentionFileCount, startupMode,
                peerHost(bootstrapGroup, localNodeId),
                peerPort(bootstrapGroup, localNodeId));
    }

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
        if (storageFreeSpaceMinBytes < 0L) {
            throw new IllegalArgumentException(
                    "storageFreeSpaceMinBytes must not be negative");
        }
        if (logPreallocatedSizeBytes < 1L) {
            throw new IllegalArgumentException(
                    "logPreallocatedSizeBytes must be positive");
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
        if (snapshotRetentionFileCount < 1 || snapshotRetentionFileCount > 64) {
            throw new IllegalArgumentException(
                    "snapshotRetentionFileCount must be between 1 and 64");
        }
        if (startupMode == null) {
            throw new IllegalArgumentException("startupMode must not be null");
        }
        if (rpcBindHost == null || rpcBindHost.isBlank()) {
            throw new IllegalArgumentException("rpcBindHost must not be blank");
        }
        rpcBindHost = rpcBindHost.trim();
        if (rpcBindPort < 1 || rpcBindPort > 65_535) {
            throw new IllegalArgumentException(
                    "rpcBindPort must be between 1 and 65535");
        }
    }

    public static RatisNodeOptions productionDefaults(
            String localNodeId, RatisGroupDefinition group, Path storageDirectory) {
        return new RatisNodeOptions(
                localNodeId,
                group,
                storageDirectory,
                DEFAULT_STORAGE_FREE_SPACE_MIN_BYTES,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                10_000L,
                true,
                DEFAULT_SNAPSHOT_RETENTION_FILE_COUNT,
                RatisStartupMode.BOOTSTRAP_OR_RECOVER,
                peerHost(group, localNodeId),
                peerPort(group, localNodeId));
    }

    private static String peerHost(
            RatisGroupDefinition group, String localNodeId) {
        if (group == null) {
            throw new IllegalArgumentException("bootstrapGroup must not be null");
        }
        return group.requirePeer(localNodeId).host();
    }

    private static int peerPort(
            RatisGroupDefinition group, String localNodeId) {
        if (group == null) {
            throw new IllegalArgumentException("bootstrapGroup must not be null");
        }
        return group.requirePeer(localNodeId).port();
    }

    private static Duration positive(String field, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
