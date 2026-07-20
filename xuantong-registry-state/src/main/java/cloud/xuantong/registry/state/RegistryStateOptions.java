package cloud.xuantong.registry.state;

public record RegistryStateOptions(
        long minLeaseTtlMs,
        long maxLeaseTtlMs,
        int maxServices,
        int maxInstances,
        int maxRenewBatchSize,
        int changeLogCapacity,
        int maxOperationRecords,
        int operationReplayWindow) {

    public RegistryStateOptions(
            long minLeaseTtlMs,
            long maxLeaseTtlMs,
            int maxServices,
            int maxInstances,
            int maxRenewBatchSize,
            int changeLogCapacity,
            int maxOperationRecords) {
        this(minLeaseTtlMs, maxLeaseTtlMs, maxServices, maxInstances,
                maxRenewBatchSize, changeLogCapacity, maxOperationRecords,
                maxOperationRecords);
    }

    public RegistryStateOptions {
        if (minLeaseTtlMs < 1 || maxLeaseTtlMs < minLeaseTtlMs) {
            throw new IllegalArgumentException("lease TTL bounds are invalid");
        }
        if (maxServices < 1 || maxInstances < 1 || maxRenewBatchSize < 1
                || changeLogCapacity < 1 || maxOperationRecords < 1) {
            throw new IllegalArgumentException("Registry State capacities must be positive");
        }
        if (operationReplayWindow < 1 || operationReplayWindow > maxOperationRecords) {
            throw new IllegalArgumentException(
                    "operationReplayWindow must be between 1 and maxOperationRecords");
        }
    }

    public static RegistryStateOptions defaults() {
        return new RegistryStateOptions(
                3_000L,
                120_000L,
                1_000_000,
                1_000_000,
                1_000,
                100_000,
                200_000,
                150_000);
    }
}
