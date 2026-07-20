-- Bounded management list queries introduced in Xuantong 2.0.1.
CREATE INDEX idx_config_resource_list
    ON config_resource (namespace_id, group_name, lifecycle_status, data_id);

CREATE INDEX idx_config_rollout_history
    ON config_rollout (config_id, created_at, id);

CREATE INDEX idx_service_definition_list
    ON service_definition (namespace_id, group_name, lifecycle_state, service_name);

CREATE INDEX idx_audit_resource_history
    ON audit_log (namespace_id, group_name, resource_type, resource_name, id);

CREATE INDEX idx_audit_type_history
    ON audit_log (resource_type, id);

CREATE INDEX idx_client_access_token_list
    ON client_access_token (is_active, id);

CREATE INDEX idx_user_list
    ON `user` (is_active, role, username);

CREATE INDEX idx_user_role_list
    ON `user` (role, is_active, username);
