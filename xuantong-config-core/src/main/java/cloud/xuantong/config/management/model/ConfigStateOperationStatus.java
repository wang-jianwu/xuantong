package cloud.xuantong.config.management.model;

/** Durable administration-side progress for one Config Raft operation. */
public enum ConfigStateOperationStatus {
    PENDING,
    COMMITTED,
    PROJECTION_PENDING,
    PROJECTED,
    FAILED
}
