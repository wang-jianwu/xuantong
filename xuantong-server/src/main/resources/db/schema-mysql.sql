CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    email VARCHAR(100) UNIQUE,
    real_name VARCHAR(100),
    role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_time TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS config_namespace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    namespace_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    labels LONGTEXT,
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_namespace_id UNIQUE (namespace_id)
);

CREATE TABLE IF NOT EXISTS resource_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    namespace_id VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_resource_group UNIQUE (namespace_id, group_name)
);

CREATE TABLE IF NOT EXISTS config_resource (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    namespace_id VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL DEFAULT 'DEFAULT_GROUP',
    data_id VARCHAR(128) NOT NULL,
    content LONGTEXT,
    content_type VARCHAR(32) NOT NULL DEFAULT 'text',
    checksum VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0,
    is_encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(500),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_resource UNIQUE (namespace_id, group_name, data_id)
);

CREATE TABLE IF NOT EXISTS config_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id VARCHAR(64) NOT NULL,
    config_id BIGINT NOT NULL,
    namespace_id VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    data_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    content_revision BIGINT,
    decision_revision BIGINT,
    event_revision BIGINT,
    content LONGTEXT,
    content_type VARCHAR(32) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    release_type VARCHAR(32) NOT NULL,
    operator VARCHAR(100),
    operation_id VARCHAR(128),
    released_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_release_id UNIQUE (release_id),
    CONSTRAINT uk_config_release_revision UNIQUE (config_id, revision),
    CONSTRAINT uk_config_release_operation UNIQUE (operation_id)
);

CREATE TABLE IF NOT EXISTS config_rollout (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rollout_id VARCHAR(64) NOT NULL,
    config_id BIGINT NOT NULL,
    namespace_id VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    data_id VARCHAR(128) NOT NULL,
    baseline_release_id VARCHAR(64) NOT NULL,
    candidate_release_id VARCHAR(64) NOT NULL,
    rollout_type VARCHAR(32) NOT NULL,
    target_value LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_by VARCHAR(100),
    completed_at TIMESTAMP,
    decision_revision BIGINT,
    start_operation_id VARCHAR(128),
    complete_operation_id VARCHAR(128),
    CONSTRAINT uk_config_rollout_id UNIQUE (rollout_id),
    INDEX idx_config_rollout_active (config_id, status)
);

CREATE TABLE IF NOT EXISTS config_state_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(256) NOT NULL,
    principal VARCHAR(256) NOT NULL,
    namespace_id VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    data_id VARCHAR(128) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    command_payload LONGTEXT NOT NULL,
    projection_payload LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    content_revision BIGINT,
    decision_revision BIGINT,
    event_revision BIGINT,
    error_message LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_state_operation UNIQUE (tenant, principal, operation_id),
    INDEX idx_config_state_operation_recovery (status, created_at)
);

CREATE TABLE IF NOT EXISTS service_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    namespace_id VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL DEFAULT 'DEFAULT_GROUP',
    service_name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    metadata LONGTEXT,
    service_generation BIGINT NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVATING',
    lifecycle_operation_id VARCHAR(128),
    lifecycle_error LONGTEXT,
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_service_definition UNIQUE (namespace_id, group_name, service_name),
    INDEX idx_service_definition_lifecycle (lifecycle_state, updated_at)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    namespace_id VARCHAR(128),
    group_name VARCHAR(128),
    resource_type VARCHAR(32) NOT NULL,
    resource_name VARCHAR(128),
    operation VARCHAR(64) NOT NULL,
    operator VARCHAR(100),
    detail LONGTEXT,
    ip_address VARCHAR(64),
    operation_id VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_audit_operation UNIQUE (operation_id)
);

CREATE TABLE IF NOT EXISTS client_access_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    tenant VARCHAR(128) NOT NULL DEFAULT 'default',
    namespace_id VARCHAR(128) NOT NULL DEFAULT '*',
    group_name VARCHAR(128) NOT NULL DEFAULT '*',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    CONSTRAINT uk_client_access_token_hash UNIQUE (token_hash)
);

CREATE TABLE IF NOT EXISTS user_scope_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    namespace_id VARCHAR(128) NOT NULL,
    group_name VARCHAR(128) NOT NULL DEFAULT '*',
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_scope_role UNIQUE (user_id, namespace_id, group_name)
);

INSERT INTO `user` (username, password, email, real_name, role, is_active)
SELECT 'admin', '0192023a7bbd73250516f069df18b500', 'admin@xuantong.com', '系统管理员', 'SYSTEM_ADMIN', TRUE
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE username = 'admin');

INSERT INTO config_namespace (namespace_id, name, description, is_active, created_by)
SELECT 'public', 'Public', 'Default namespace', TRUE, 'system'
WHERE NOT EXISTS (SELECT 1 FROM config_namespace WHERE namespace_id = 'public');

INSERT INTO resource_group (namespace_id, group_name, description, created_by)
SELECT 'public', 'DEFAULT_GROUP', 'Default resource group', 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM resource_group WHERE namespace_id = 'public' AND group_name = 'DEFAULT_GROUP'
);
