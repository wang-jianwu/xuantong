package cloud.xuantong.raft.ratis;

/** Startup mode for one physical Ratis process. */
public enum RatisStartupMode {
    /** Create the configured initial Group or recover its existing WAL/Snapshot. */
    BOOTSTRAP_OR_RECOVER,
    /** Start the RPC server without a Group and wait for membership orchestration. */
    JOIN_EXISTING
}
