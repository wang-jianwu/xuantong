-- Low-frequency Gateway snapshots and durable credential revocation events.
CREATE TABLE IF NOT EXISTS gateway_runtime_snapshot (
    cluster_id VARCHAR(128) NOT NULL,
    gateway_id VARCHAR(128) NOT NULL,
    runtime_id VARCHAR(64) NOT NULL,
    transport_generation BIGINT NOT NULL,
    snapshot_payload LONGTEXT NOT NULL,
    captured_at BIGINT NOT NULL,
    lease_expires_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (cluster_id, gateway_id)
);

CREATE INDEX idx_gateway_runtime_lease
    ON gateway_runtime_snapshot (cluster_id, lease_expires_at);

CREATE TABLE IF NOT EXISTS credential_revocation_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL,
    revoked_at BIGINT NOT NULL
);

CREATE INDEX idx_credential_revocation_time
    ON credential_revocation_event (revoked_at);
