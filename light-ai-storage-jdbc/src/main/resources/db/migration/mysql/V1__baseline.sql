-- Light AI MySQL Schema Migration DDL V1 (DATABASE_PLAN §2)
-- 包含全部 39 张表定义与初始种子数据，兼容 MySQL 5.7 / 8.0

-- 1. provider
CREATE TABLE IF NOT EXISTS provider (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    name VARCHAR(64) NOT NULL,
    type VARCHAR(64) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    proxy_url VARCHAR(2048),
    connect_timeout_ms INT NOT NULL DEFAULT 3000,
    read_timeout_ms INT NOT NULL DEFAULT 120000,
    default_headers JSON,
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. credential_pool
CREATE TABLE IF NOT EXISTS credential_pool (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    provider_id VARCHAR(36) NOT NULL,
    name VARCHAR(64) NOT NULL,
    selection_strategy VARCHAR(32) NOT NULL DEFAULT 'LEAST_CONCURRENT',
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. credential
CREATE TABLE IF NOT EXISTS credential (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    pool_id VARCHAR(36) NOT NULL,
    name VARCHAR(64) NOT NULL,
    secret_source VARCHAR(24) NOT NULL DEFAULT 'INLINE_ENCRYPTED',
    weight INT NOT NULL DEFAULT 1,
    rpm_limit BIGINT,
    tpm_limit BIGINT,
    concurrent_limit INT,
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. credential_secret
CREATE TABLE IF NOT EXISTS credential_secret (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    credential_id VARCHAR(36) NOT NULL,
    secret_ciphertext LONGBLOB,
    secret_ref_ciphertext LONGBLOB,
    encryption_key_id VARCHAR(128) NOT NULL,
    masked_value VARCHAR(128) NOT NULL,
    secret_version BIGINT NOT NULL DEFAULT 1,
    rotated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. provider_model
CREATE TABLE IF NOT EXISTS provider_model (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    provider_id VARCHAR(36) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    model_type VARCHAR(16) NOT NULL DEFAULT 'CHAT_TEXT',
    tokenizer_family VARCHAR(64),
    context_window BIGINT,
    max_output_tokens BIGINT,
    support_stream TINYINT(1),
    support_system_message TINYINT(1),
    support_temperature TINYINT(1),
    support_top_p TINYINT(1),
    support_stop TINYINT(1),
    temperature_min DECIMAL(9,4),
    temperature_max DECIMAL(9,4),
    top_p_min DECIMAL(9,4),
    top_p_max DECIMAL(9,4),
    max_stop_sequences INT,
    max_stop_length INT,
    default_temperature DECIMAL(9,4),
    default_top_p DECIMAL(9,4),
    default_max_tokens BIGINT,
    default_stop JSON,
    input_price DECIMAL(20,8) NOT NULL DEFAULT 0,
    output_price DECIMAL(20,8) NOT NULL DEFAULT 0,
    price_unit INT NOT NULL DEFAULT 1000000,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    import_source VARCHAR(24),
    import_adapter_version VARCHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. model_alias
CREATE TABLE IF NOT EXISTS model_alias (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    alias VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    description VARCHAR(500),
    route_strategy VARCHAR(32) NOT NULL DEFAULT 'PRIORITY_WEIGHTED',
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. route_candidate
CREATE TABLE IF NOT EXISTS route_candidate (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    alias_id VARCHAR(36) NOT NULL,
    provider_model_id VARCHAR(36) NOT NULL,
    credential_pool_id VARCHAR(36) NOT NULL,
    priority INT NOT NULL DEFAULT 10,
    weight INT NOT NULL DEFAULT 1,
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8. limit_policy
CREATE TABLE IF NOT EXISTS limit_policy (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    name VARCHAR(64) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    rpm_limit BIGINT,
    tpm_limit BIGINT,
    concurrent_limit INT,
    overflow_strategy VARCHAR(24) NOT NULL,
    queue_timeout_ms INT,
    queue_max_size INT,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    UNIQUE KEY uk_limit_policy_name (name),
    KEY idx_limit_policy_scope (scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9. reliability_policy
CREATE TABLE IF NOT EXISTS reliability_policy (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    name VARCHAR(64) NOT NULL,
    alias_id VARCHAR(36) NOT NULL,
    connect_timeout_ms INT NOT NULL DEFAULT 3000,
    first_token_timeout_ms INT NOT NULL DEFAULT 30000,
    total_timeout_ms INT NOT NULL DEFAULT 120000,
    max_retries INT NOT NULL DEFAULT 1,
    max_credential_failovers INT NOT NULL DEFAULT 1,
    initial_backoff_ms INT NOT NULL DEFAULT 200,
    backoff_multiplier DECIMAL(5,2) NOT NULL DEFAULT 2.0,
    jitter_percent INT NOT NULL DEFAULT 20,
    respect_retry_after TINYINT(1) NOT NULL DEFAULT 1,
    max_retry_after_ms INT NOT NULL DEFAULT 5000,
    fallback_enabled TINYINT(1) NOT NULL DEFAULT 1,
    max_fallbacks INT NOT NULL DEFAULT 2,
    circuit_window_seconds INT NOT NULL DEFAULT 60,
    circuit_min_requests INT NOT NULL DEFAULT 20,
    circuit_failure_rate DECIMAL(9,4) NOT NULL DEFAULT 0.5,
    circuit_open_seconds INT NOT NULL DEFAULT 30,
    circuit_half_open_probes INT NOT NULL DEFAULT 3,
    circuit_half_open_successes INT NOT NULL DEFAULT 2,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    UNIQUE KEY uk_reliability_policy_name (name),
    KEY idx_reliability_policy_alias (alias_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 10. runtime_config
CREATE TABLE IF NOT EXISTS runtime_config (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    singleton_key INT NOT NULL DEFAULT 1 UNIQUE,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    timezone_locked TINYINT(1) NOT NULL DEFAULT 0,
    trace_retention_days INT NOT NULL DEFAULT 7,
    usage_retention_days INT NOT NULL DEFAULT 90,
    audit_retention_days INT NOT NULL DEFAULT 365,
    dashboard_refresh_seconds INT NOT NULL DEFAULT 30,
    max_message_chars INT NOT NULL DEFAULT 64000,
    max_request_chars INT NOT NULL DEFAULT 256000,
    diagnostic_sampling_enabled TINYINT(1) NOT NULL DEFAULT 0,
    diagnostic_sample_rate DECIMAL(5,4) NOT NULL DEFAULT 0,
    diagnostic_sample_retention_days INT NOT NULL DEFAULT 3,
    diagnostic_sample_max_chars INT NOT NULL DEFAULT 4000,
    client_ip_recording_enabled TINYINT(1) NOT NULL DEFAULT 0,
    trusted_proxy_cidrs JSON,
    publish_instance_timeout_seconds INT NOT NULL DEFAULT 300,
    instance_stale_seconds INT NOT NULL DEFAULT 45,
    default_alias_id VARCHAR(36),
    current_snapshot_no BIGINT NOT NULL DEFAULT 0,
    published_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 11. object_runtime_state
CREATE TABLE IF NOT EXISTS object_runtime_state (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    connection_status VARCHAR(16),
    health_status VARCHAR(24),
    reset_at DATETIME(6),
    last_success_at DATETIME(6),
    last_checked_at DATETIME(6),
    last_failed_at DATETIME(6),
    last_error_code VARCHAR(64),
    last_error_summary VARCHAR(1000),
    state_version BIGINT NOT NULL,
    UNIQUE KEY uk_object_runtime_state_entity (entity_type, entity_id),
    KEY idx_object_runtime_state_health (health_status, reset_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 12. provider_check_record
CREATE TABLE IF NOT EXISTS provider_check_record (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    mode VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128),
    attempt_id VARCHAR(36),
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NOT NULL,
    total_ms INT NOT NULL,
    `usage` JSON,
    provider_request_id VARCHAR(256),
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    KEY idx_provider_check_record_target (target_type, target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 13. batch_check_job
CREATE TABLE IF NOT EXISTS batch_check_job (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    total_items INT NOT NULL DEFAULT 0,
    completed_items INT NOT NULL DEFAULT 0,
    success_items INT NOT NULL DEFAULT 0,
    failed_items INT NOT NULL DEFAULT 0,
    timeout_ms INT NOT NULL DEFAULT 60000,
    started_at DATETIME(6),
    ended_at DATETIME(6),
    created_by VARCHAR(128) NOT NULL,
    command JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 14. batch_check_item
CREATE TABLE IF NOT EXISTS batch_check_item (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    provider_model_id VARCHAR(36) NOT NULL,
    credential_id VARCHAR(36),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    latency_ms INT,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    check_record_id VARCHAR(36)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 15. trace
CREATE TABLE IF NOT EXISTS trace (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL UNIQUE,
    application VARCHAR(64) NOT NULL,
    project VARCHAR(64),
    tenant VARCHAR(64),
    request_user VARCHAR(128),
    tags JSON,
    source_mode VARCHAR(32) NOT NULL,
    invocation_source VARCHAR(16) NOT NULL DEFAULT 'APPLICATION',
    alias_id VARCHAR(36),
    alias VARCHAR(64),
    config_snapshot_no BIGINT NOT NULL,
    requested_stream TINYINT(1) NOT NULL DEFAULT 0,
    response_committed TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6),
    total_ms INT,
    first_token_ms INT,
    queued_ms INT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    credential_failover_count INT NOT NULL DEFAULT 0,
    fallback_count INT NOT NULL DEFAULT 0,
    final_attempt_id VARCHAR(36),
    final_provider_id VARCHAR(36),
    final_provider_model_id VARCHAR(36),
    final_credential_id VARCHAR(36),
    access_credential_id VARCHAR(36),
    final_provider_name VARCHAR(128),
    final_provider_model_name VARCHAR(128),
    access_credential_name VARCHAR(128),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    response_input_tokens BIGINT NOT NULL DEFAULT 0,
    response_output_tokens BIGINT NOT NULL DEFAULT 0,
    response_total_tokens BIGINT NOT NULL DEFAULT 0,
    usage_source VARCHAR(12),
    input_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    output_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    total_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    finish_reason VARCHAR(32),
    error_code VARCHAR(64),
    error_category VARCHAR(64),
    error_stage VARCHAR(64),
    error_summary VARCHAR(1000),
    retryable TINYINT(1) NOT NULL DEFAULT 0,
    request_summary JSON,
    client_ip VARCHAR(45),
    user_agent VARCHAR(512),
    owner_instance_id VARCHAR(36),
    lease_expires_at DATETIME(6),
    terminal_version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 16. attempt
CREATE TABLE IF NOT EXISTS attempt (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    `sequence` INT NOT NULL,
    attempt_type VARCHAR(32) NOT NULL DEFAULT 'INITIAL',
    route_candidate_id VARCHAR(36),
    provider_id VARCHAR(36) NOT NULL,
    provider_model_id VARCHAR(36) NOT NULL,
    credential_pool_id VARCHAR(36) NOT NULL,
    credential_id VARCHAR(36) NOT NULL,
    provider_name_snapshot VARCHAR(128) NOT NULL,
    provider_model_name_snapshot VARCHAR(128) NOT NULL,
    model_id_snapshot VARCHAR(128) NOT NULL,
    credential_name_snapshot VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(6) NOT NULL,
    provider_started_at DATETIME(6),
    response_headers_at DATETIME(6),
    first_token_at DATETIME(6),
    ended_at DATETIME(6),
    dispatch_ms INT,
    response_header_ms INT,
    first_token_ms INT,
    total_ms INT,
    endpoint_host VARCHAR(255) NOT NULL,
    http_status INT,
    provider_request_id VARCHAR(256),
    response_committed TINYINT(1) NOT NULL DEFAULT 0,
    finish_reason VARCHAR(32),
    error_code VARCHAR(64),
    error_category VARCHAR(64),
    error_stage VARCHAR(64),
    error_summary VARCHAR(1000),
    retryable TINYINT(1) NOT NULL DEFAULT 0,
    retry_after_ms INT,
    resolved_parameters JSON,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    usage_source VARCHAR(12),
    input_price DECIMAL(20,8),
    output_price DECIMAL(20,8),
    price_unit INT,
    currency CHAR(3),
    input_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    output_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    total_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    settled_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 17. trace_content_sample
CREATE TABLE IF NOT EXISTS trace_content_sample (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    sampled_messages JSON NOT NULL,
    sampled_response LONGTEXT,
    redaction_version VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 18. route_decision
CREATE TABLE IF NOT EXISTS route_decision (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    `sequence` INT NOT NULL,
    route_candidate_id VARCHAR(36),
    decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_detail VARCHAR(1000),
    observed_status VARCHAR(32),
    observed_values JSON
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 19. capacity_reservation
CREATE TABLE IF NOT EXISTS capacity_reservation (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    attempt_id VARCHAR(36),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reserved_tokens BIGINT NOT NULL,
    actual_tokens BIGINT,
    expires_at DATETIME(6) NOT NULL,
    settled_at DATETIME(6),
    released_at DATETIME(6),
    release_reason VARCHAR(64),
    settlement_payload JSON,
    settlement_applied TINYINT(1) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 20. capacity_reservation_item
CREATE TABLE IF NOT EXISTS capacity_reservation_item (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    reservation_id VARCHAR(36) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    window_start BIGINT NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    actual_tokens BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 21. queue_entry
CREATE TABLE IF NOT EXISTS queue_entry (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    queue_position INT NOT NULL DEFAULT 1,
    enqueued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    dequeued_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 22. recovery_decision
CREATE TABLE IF NOT EXISTS recovery_decision (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    `sequence` INT NOT NULL,
    failed_attempt_sequence INT NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    target_candidate_id VARCHAR(36),
    target_credential_id VARCHAR(36),
    reason_code VARCHAR(64) NOT NULL,
    reason_detail VARCHAR(1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 23. circuit_state
CREATE TABLE IF NOT EXISTS circuit_state (
    id VARCHAR(36) PRIMARY KEY,
    provider_model_id VARCHAR(36) NOT NULL,
    credential_id VARCHAR(36) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'CLOSED',
    state_version BIGINT NOT NULL DEFAULT 1,
    policy_snapshot JSON,
    open_source VARCHAR(32),
    last_reason VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_circuit_state_pm_cred (provider_model_id, credential_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 24. circuit_event
CREATE TABLE IF NOT EXISTS circuit_event (
    id VARCHAR(36) PRIMARY KEY,
    event_key VARCHAR(128) NOT NULL UNIQUE,
    circuit_id VARCHAR(36) NOT NULL,
    from_state VARCHAR(16) NOT NULL,
    to_state VARCHAR(16) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    command_id VARCHAR(36),
    error_code VARCHAR(64),
    reason VARCHAR(500),
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 25. circuit_command
CREATE TABLE IF NOT EXISTS circuit_command (
    id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    circuit_id VARCHAR(36) NOT NULL,
    action VARCHAR(32) NOT NULL,
    expected_state_version BIGINT NOT NULL,
    reason VARCHAR(500),
    open_seconds INT,
    operator_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(64),
    applied_at DATETIME(6),
    completed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 26. usage_aggregation_event
CREATE TABLE IF NOT EXISTS usage_aggregation_event (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    locked_by VARCHAR(128),
    locked_at DATETIME(6),
    next_retry_at DATETIME(6),
    completed_at DATETIME(6),
    lock_generation BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 27. usage_aggregate
CREATE TABLE IF NOT EXISTS usage_aggregate (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    granularity VARCHAR(4) NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    bucket_end DATETIME(6) NOT NULL,
    dimension_key CHAR(64) NOT NULL,
    application VARCHAR(64) NOT NULL,
    project VARCHAR(64),
    tenant VARCHAR(64),
    alias_id VARCHAR(36),
    provider_id VARCHAR(36),
    provider_model_id VARCHAR(36),
    credential_pool_id VARCHAR(36),
    credential_id VARCHAR(36),
    trace_status VARCHAR(24) NOT NULL,
    error_code VARCHAR(64),
    usage_source VARCHAR(12),
    requested_stream TINYINT(1) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    dimension_names JSON,
    request_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    cancelled_count BIGINT NOT NULL DEFAULT 0,
    stream_interrupted_count BIGINT NOT NULL DEFAULT 0,
    queued_count BIGINT NOT NULL DEFAULT 0,
    stream_count BIGINT NOT NULL DEFAULT 0,
    attempt_count BIGINT NOT NULL DEFAULT 0,
    initial_count BIGINT NOT NULL DEFAULT 0,
    retry_count BIGINT NOT NULL DEFAULT 0,
    credential_failover_count BIGINT NOT NULL DEFAULT 0,
    fallback_count BIGINT NOT NULL DEFAULT 0,
    half_open_probe_count BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    actual_input_tokens BIGINT NOT NULL DEFAULT 0,
    actual_output_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_input_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_output_tokens BIGINT NOT NULL DEFAULT 0,
    input_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    output_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    total_cost DECIMAL(30,8) NOT NULL DEFAULT 0,
    total_ms_sum BIGINT NOT NULL DEFAULT 0,
    total_ms_count BIGINT NOT NULL DEFAULT 0,
    first_token_ms_sum BIGINT NOT NULL DEFAULT 0,
    first_token_ms_count BIGINT NOT NULL DEFAULT 0,
    queued_ms_sum BIGINT NOT NULL DEFAULT 0,
    latency_histogram JSON,
    first_token_histogram JSON
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 28. config_draft_state
CREATE TABLE IF NOT EXISTS config_draft_state (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    singleton_key INT NOT NULL DEFAULT 1 UNIQUE,
    base_snapshot_no BIGINT NOT NULL DEFAULT 0,
    draft_revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'EDITABLE',
    publish_record_id VARCHAR(36),
    lock_acquired_at DATETIME(6),
    change_count INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 29. draft_change (R-class, no deleted_at)
CREATE TABLE IF NOT EXISTS draft_change (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    entity_name VARCHAR(128) NOT NULL,
    change_type VARCHAR(12) NOT NULL,
    changed_fields JSON,
    modified_by VARCHAR(128) NOT NULL,
    entity_version BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 30. config_validation
CREATE TABLE IF NOT EXISTS config_validation (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    validation_id VARCHAR(36) NOT NULL UNIQUE,
    base_snapshot_no BIGINT NOT NULL,
    target_snapshot_no BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    content_checksum CHAR(64) NOT NULL,
    status VARCHAR(12) NOT NULL,
    error_count INT NOT NULL DEFAULT 0,
    warning_count INT NOT NULL DEFAULT 0,
    validated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    validated_by VARCHAR(128) NOT NULL,
    used_by_publish_id VARCHAR(36),
    change_summary JSON,
    affected_alias_ids JSON,
    target_instances JSON
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 31. config_validation_issue
CREATE TABLE IF NOT EXISTS config_validation_issue (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    validation_id VARCHAR(36) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    code VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(36),
    entity_name VARCHAR(128),
    field_path VARCHAR(256),
    message VARCHAR(1000) NOT NULL,
    suggestion VARCHAR(1000) NOT NULL,
    related_entity_ids JSON
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 32. config_snapshot
CREATE TABLE IF NOT EXISTS config_snapshot (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    snapshot_no BIGINT NOT NULL UNIQUE,
    schema_version INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    content JSON NOT NULL,
    content_checksum CHAR(64) NOT NULL,
    content_summary JSON,
    activated_at DATETIME(6),
    created_by VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 33. publish_record
CREATE TABLE IF NOT EXISTS publish_record (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    validation_id VARCHAR(36) NOT NULL UNIQUE,
    from_snapshot_no BIGINT NOT NULL,
    target_snapshot_no BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PREPARING',
    published_by VARCHAR(128) NOT NULL,
    publish_note VARCHAR(500),
    acknowledged_warning_ids JSON NOT NULL,
    target_instance_ids JSON NOT NULL,
    completed_at DATETIME(6),
    converged_at DATETIME(6),
    duration_ms BIGINT,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    KEY idx_publish_record_created (created_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 34. publish_instance_result
CREATE TABLE IF NOT EXISTS publish_instance_result (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    publish_id VARCHAR(36) NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    from_snapshot_no BIGINT NOT NULL,
    target_snapshot_no BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    load_duration_ms BIGINT,
    reported_at DATETIME(6),
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    UNIQUE KEY uk_publish_instance_result (publish_id, instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 35. runtime_instance
CREATE TABLE IF NOT EXISTS runtime_instance (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    instance_id VARCHAR(36) NOT NULL UNIQUE,
    runtime_mode VARCHAR(24) NOT NULL,
    runtime_version VARCHAR(64) NOT NULL,
    application VARCHAR(64) NOT NULL,
    zone VARCHAR(64),
    supported_schema_versions JSON,
    loaded_adapter_types JSON,
    active_snapshot_no BIGINT NOT NULL DEFAULT 0,
    accepting_requests TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(12) NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat_at DATETIME(6),
    last_error_code VARCHAR(64),
    last_error_summary VARCHAR(1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 36. access_credential
CREATE TABLE IF NOT EXISTS access_credential (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(6),
    name VARCHAR(64) NOT NULL,
    application VARCHAR(64) NOT NULL,
    token_prefix VARCHAR(8) NOT NULL,
    token_hash LONGBLOB NOT NULL,
    token_hash_version INT NOT NULL DEFAULT 1,
    masked_value VARCHAR(128) NOT NULL,
    ip_allowlist JSON,
    expires_at DATETIME(6),
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    rotation_generation BIGINT NOT NULL DEFAULT 1,
    issued_at DATETIME(6) NOT NULL,
    rotated_at DATETIME(6),
    last_used_at DATETIME(6),
    last_used_ip_masked VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 37. access_credential_alias
CREATE TABLE IF NOT EXISTS access_credential_alias (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    access_credential_id VARCHAR(36) NOT NULL,
    alias_id VARCHAR(36) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 38. audit_log
CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(128),
    result VARCHAR(16) NOT NULL,
    changes JSON,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    source_mode VARCHAR(32) NOT NULL,
    source_ip_masked VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 39. retention_impact
CREATE TABLE IF NOT EXISTS retention_impact (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    impact_version VARCHAR(36) NOT NULL UNIQUE,
    draft_revision BIGINT NOT NULL,
    target_values JSON NOT NULL,
    counts JSON NOT NULL,
    estimated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    estimated_by VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 初始种子数据（DATABASE_PLAN §4）
INSERT IGNORE INTO runtime_config (
    id, created_at, updated_at, version, singleton_key, timezone, timezone_locked,
    trace_retention_days, usage_retention_days, audit_retention_days,
    dashboard_refresh_seconds, max_message_chars, max_request_chars,
    diagnostic_sampling_enabled, diagnostic_sample_rate, diagnostic_sample_retention_days,
    diagnostic_sample_max_chars, client_ip_recording_enabled, trusted_proxy_cidrs,
    publish_instance_timeout_seconds, instance_stale_seconds, current_snapshot_no
) VALUES (
    '00000000-0000-0000-0000-000000000001', NOW(6), NOW(6), 1, 1, 'Asia/Shanghai', 0,
    7, 90, 365, 30, 64000, 256000, 0, 0, 3, 4000, 0, '[]',
    300, 45, 0
);

INSERT IGNORE INTO config_draft_state (
    id, created_at, updated_at, singleton_key, base_snapshot_no, draft_revision, status, change_count
) VALUES (
    '00000000-0000-0000-0000-000000000002', NOW(6), NOW(6), 1, 0, 0, 'EDITABLE', 0
);

INSERT IGNORE INTO config_snapshot (
    id, created_at, updated_at, snapshot_no, schema_version, status, content, content_checksum, content_summary, created_by
) VALUES (
    '00000000-0000-0000-0000-000000000003', NOW(6), NOW(6), 0, 1, 'ACTIVE',
    '{"schema_version":1,"providers":[],"credential_pools":[],"credentials":[],"provider_models":[],"model_aliases":[],"route_candidates":[],"limit_policies":[],"reliability_policies":[],"runtime_config":{"timezone":"Asia/Shanghai","trace_retention_days":7,"usage_retention_days":90,"audit_retention_days":365,"dashboard_refresh_seconds":30,"max_message_chars":64000,"max_request_chars":256000,"diagnostic_sampling_enabled":false,"diagnostic_sample_rate":0,"diagnostic_sample_retention_days":3,"diagnostic_sample_max_chars":4000,"client_ip_recording_enabled":false,"trusted_proxy_cidrs":[],"publish_instance_timeout_seconds":300,"instance_stale_seconds":45}}',
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    '{}', 'system'
);
