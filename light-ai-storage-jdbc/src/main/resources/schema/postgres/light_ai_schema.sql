-- Light AI PostgreSQL Schema Migration DDL (DATABASE_PLAN §2)
-- 包含全部 39 张表定义与初始种子数据

CREATE SCHEMA IF NOT EXISTS light_ai;

-- 1. provider
CREATE TABLE IF NOT EXISTS light_ai.provider (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(64) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    proxy_url VARCHAR(2048),
    connect_timeout_ms INTEGER NOT NULL DEFAULT 3000,
    read_timeout_ms INTEGER NOT NULL DEFAULT 120000,
    default_headers JSONB NOT NULL DEFAULT '{}',
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- 2. credential_pool
CREATE TABLE IF NOT EXISTS light_ai.credential_pool (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    provider_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    selection_strategy VARCHAR(32) NOT NULL DEFAULT 'LEAST_CONCURRENT',
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- 3. credential
CREATE TABLE IF NOT EXISTS light_ai.credential (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    pool_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    secret_source VARCHAR(24) NOT NULL DEFAULT 'INLINE_ENCRYPTED',
    weight INTEGER NOT NULL DEFAULT 1,
    rpm_limit BIGINT,
    tpm_limit BIGINT,
    concurrent_limit INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- 4. credential_secret
CREATE TABLE IF NOT EXISTS light_ai.credential_secret (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    credential_id UUID NOT NULL,
    secret_ciphertext BYTEA,
    secret_ref_ciphertext BYTEA,
    encryption_key_id VARCHAR(128) NOT NULL,
    masked_value VARCHAR(128) NOT NULL,
    secret_version BIGINT NOT NULL DEFAULT 1,
    rotated_at TIMESTAMPTZ
);

-- 5. provider_model
CREATE TABLE IF NOT EXISTS light_ai.provider_model (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    provider_id UUID NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    model_type VARCHAR(16) NOT NULL DEFAULT 'CHAT_TEXT',
    tokenizer_family VARCHAR(64),
    context_window BIGINT,
    max_output_tokens BIGINT,
    support_stream BOOLEAN,
    support_system_message BOOLEAN,
    support_temperature BOOLEAN,
    support_top_p BOOLEAN,
    support_stop BOOLEAN,
    temperature_min NUMERIC(9,4),
    temperature_max NUMERIC(9,4),
    top_p_min NUMERIC(9,4),
    top_p_max NUMERIC(9,4),
    max_stop_sequences INTEGER,
    max_stop_length INTEGER,
    default_temperature NUMERIC(9,4),
    default_top_p NUMERIC(9,4),
    default_max_tokens BIGINT,
    default_stop JSONB NOT NULL DEFAULT '[]',
    input_price NUMERIC(20,8) NOT NULL DEFAULT 0,
    output_price NUMERIC(20,8) NOT NULL DEFAULT 0,
    price_unit INTEGER NOT NULL DEFAULT 1000000,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    import_source VARCHAR(24),
    import_adapter_version VARCHAR(64)
);

-- 6. model_alias
CREATE TABLE IF NOT EXISTS light_ai.model_alias (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    alias VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    description VARCHAR(500),
    route_strategy VARCHAR(32) NOT NULL DEFAULT 'PRIORITY_WEIGHTED',
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- 7. route_candidate
CREATE TABLE IF NOT EXISTS light_ai.route_candidate (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    alias_id UUID NOT NULL,
    provider_model_id UUID NOT NULL,
    credential_pool_id UUID NOT NULL,
    priority INTEGER NOT NULL DEFAULT 10,
    weight INTEGER NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- 8. limit_policy
CREATE TABLE IF NOT EXISTS light_ai.limit_policy (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    scope_type VARCHAR(24) NOT NULL,
    scope_id UUID NOT NULL,
    rpm_limit BIGINT,
    tpm_limit BIGINT,
    concurrent_limit INTEGER,
    queue_timeout_ms INTEGER,
    overflow_strategy VARCHAR(24) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- 9. reliability_policy
CREATE TABLE IF NOT EXISTS light_ai.reliability_policy (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    alias_id UUID NOT NULL,
    retry_max_attempts INTEGER NOT NULL DEFAULT 2,
    retry_backoff_base_ms INTEGER NOT NULL DEFAULT 200,
    retry_backoff_max_ms INTEGER NOT NULL DEFAULT 2000,
    retry_backoff_multiplier NUMERIC(4,2) NOT NULL DEFAULT 2.0,
    retryable_error_categories JSONB NOT NULL DEFAULT '[]',
    credential_failover_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    credential_failover_max_attempts INTEGER NOT NULL DEFAULT 2,
    fallback_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    fallback_max_attempts INTEGER NOT NULL DEFAULT 1,
    circuit_breaker_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    circuit_failure_rate_threshold NUMERIC(5,2) NOT NULL DEFAULT 0.5,
    circuit_minimum_requests INTEGER NOT NULL DEFAULT 10,
    circuit_sliding_window_seconds INTEGER NOT NULL DEFAULT 60,
    circuit_open_duration_seconds INTEGER NOT NULL DEFAULT 30,
    circuit_half_open_probes INTEGER NOT NULL DEFAULT 3,
    circuit_half_open_success_threshold INTEGER NOT NULL DEFAULT 2,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

-- 10. runtime_config
CREATE TABLE IF NOT EXISTS light_ai.runtime_config (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    singleton_key INTEGER NOT NULL DEFAULT 1 UNIQUE,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    timezone_locked BOOLEAN NOT NULL DEFAULT FALSE,
    trace_retention_days INTEGER NOT NULL DEFAULT 7,
    usage_retention_days INTEGER NOT NULL DEFAULT 90,
    audit_retention_days INTEGER NOT NULL DEFAULT 365,
    dashboard_refresh_seconds INTEGER NOT NULL DEFAULT 30,
    max_message_chars INTEGER NOT NULL DEFAULT 64000,
    max_request_chars INTEGER NOT NULL DEFAULT 256000,
    diagnostic_sampling_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    diagnostic_sample_rate NUMERIC(5,4) NOT NULL DEFAULT 0,
    diagnostic_sample_retention_days INTEGER NOT NULL DEFAULT 3,
    diagnostic_sample_max_chars INTEGER NOT NULL DEFAULT 4000,
    client_ip_recording_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    trusted_proxy_cidrs JSONB NOT NULL DEFAULT '[]',
    publish_instance_timeout_seconds INTEGER NOT NULL DEFAULT 300,
    instance_stale_seconds INTEGER NOT NULL DEFAULT 45,
    default_alias_id UUID,
    current_snapshot_no BIGINT NOT NULL DEFAULT 0,
    published_at TIMESTAMPTZ
);

-- 11. object_runtime_state
CREATE TABLE IF NOT EXISTS light_ai.object_runtime_state (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id UUID NOT NULL,
    health_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_check_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_error_summary VARCHAR(1000)
);

-- 12. provider_check_record
CREATE TABLE IF NOT EXISTS light_ai.provider_check_record (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    check_type VARCHAR(32) NOT NULL,
    provider_id UUID,
    credential_id UUID,
    provider_model_id UUID,
    status VARCHAR(24) NOT NULL,
    latency_ms INTEGER,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    usage JSONB,
    checked_by VARCHAR(128) NOT NULL
);

-- 13. batch_check_job
CREATE TABLE IF NOT EXISTS light_ai.batch_check_job (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    total_items INTEGER NOT NULL DEFAULT 0,
    completed_items INTEGER NOT NULL DEFAULT 0,
    success_items INTEGER NOT NULL DEFAULT 0,
    failed_items INTEGER NOT NULL DEFAULT 0,
    timeout_ms INTEGER NOT NULL DEFAULT 60000,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_by VARCHAR(128) NOT NULL,
    command JSONB NOT NULL
);

-- 14. batch_check_item
CREATE TABLE IF NOT EXISTS light_ai.batch_check_item (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    job_id UUID NOT NULL,
    provider_model_id UUID NOT NULL,
    credential_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    latency_ms INTEGER,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    check_record_id UUID
);

-- 15. trace
CREATE TABLE IF NOT EXISTS light_ai.trace (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL UNIQUE,
    application VARCHAR(64) NOT NULL,
    project VARCHAR(64),
    tenant VARCHAR(64),
    request_user VARCHAR(128),
    tags JSONB NOT NULL DEFAULT '{}',
    source_mode VARCHAR(32) NOT NULL,
    invocation_source VARCHAR(16) NOT NULL DEFAULT 'APPLICATION',
    alias_id UUID,
    alias VARCHAR(64),
    config_snapshot_no BIGINT NOT NULL,
    requested_stream BOOLEAN NOT NULL DEFAULT FALSE,
    response_committed BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    total_ms INTEGER,
    first_token_ms INTEGER,
    queued_ms INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    credential_failover_count INTEGER NOT NULL DEFAULT 0,
    fallback_count INTEGER NOT NULL DEFAULT 0,
    final_attempt_id UUID,
    final_provider_id UUID,
    final_provider_model_id UUID,
    final_credential_id UUID,
    access_credential_id UUID,
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
    input_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    output_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    total_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    finish_reason VARCHAR(32),
    error_code VARCHAR(64),
    error_category VARCHAR(64),
    error_stage VARCHAR(64),
    error_summary VARCHAR(1000),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    request_summary JSONB NOT NULL DEFAULT '{}',
    client_ip VARCHAR(45),
    user_agent VARCHAR(512),
    owner_instance_id UUID,
    lease_expires_at TIMESTAMPTZ,
    terminal_version BIGINT NOT NULL DEFAULT 0
);

-- 16. attempt
CREATE TABLE IF NOT EXISTS light_ai.attempt (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    sequence INTEGER NOT NULL,
    attempt_type VARCHAR(32) NOT NULL DEFAULT 'INITIAL',
    route_candidate_id UUID,
    provider_id UUID NOT NULL,
    provider_model_id UUID NOT NULL,
    credential_pool_id UUID NOT NULL,
    credential_id UUID NOT NULL,
    provider_name_snapshot VARCHAR(128) NOT NULL,
    provider_model_name_snapshot VARCHAR(128) NOT NULL,
    model_id_snapshot VARCHAR(128) NOT NULL,
    credential_name_snapshot VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMPTZ NOT NULL,
    provider_started_at TIMESTAMPTZ,
    response_headers_at TIMESTAMPTZ,
    first_token_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    dispatch_ms INTEGER,
    response_header_ms INTEGER,
    first_token_ms INTEGER,
    total_ms INTEGER,
    endpoint_host VARCHAR(255) NOT NULL,
    http_status INTEGER,
    provider_request_id VARCHAR(256),
    response_committed BOOLEAN NOT NULL DEFAULT FALSE,
    finish_reason VARCHAR(32),
    error_code VARCHAR(64),
    error_category VARCHAR(64),
    error_stage VARCHAR(64),
    error_summary VARCHAR(1000),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    retry_after_ms INTEGER,
    resolved_parameters JSONB NOT NULL DEFAULT '{}',
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    usage_source VARCHAR(12),
    input_price NUMERIC(20,8),
    output_price NUMERIC(20,8),
    price_unit INTEGER,
    currency CHAR(3),
    input_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    output_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    total_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    settled_at TIMESTAMPTZ
);

-- 17. trace_content_sample
CREATE TABLE IF NOT EXISTS light_ai.trace_content_sample (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    sampled_messages JSONB NOT NULL,
    sampled_response TEXT,
    redaction_version VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

-- 18. route_decision
CREATE TABLE IF NOT EXISTS light_ai.route_decision (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    sequence INTEGER NOT NULL,
    route_candidate_id UUID,
    decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_detail VARCHAR(1000),
    observed_status VARCHAR(32),
    observed_values JSONB NOT NULL DEFAULT '{}'
);

-- 19. capacity_reservation
CREATE TABLE IF NOT EXISTS light_ai.capacity_reservation (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    attempt_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reserved_tokens BIGINT NOT NULL,
    actual_tokens BIGINT,
    expires_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    release_reason VARCHAR(64),
    settlement_payload JSONB,
    settlement_applied BOOLEAN NOT NULL DEFAULT FALSE
);

-- 20. capacity_reservation_item
CREATE TABLE IF NOT EXISTS light_ai.capacity_reservation_item (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    reservation_id UUID NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id UUID NOT NULL,
    window_start BIGINT NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    actual_tokens BIGINT
);

-- 21. queue_entry
CREATE TABLE IF NOT EXISTS light_ai.queue_entry (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    queue_position INTEGER NOT NULL DEFAULT 1,
    enqueued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    dequeued_at TIMESTAMPTZ
);

-- 22. recovery_decision
CREATE TABLE IF NOT EXISTS light_ai.recovery_decision (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    sequence INTEGER NOT NULL,
    failed_attempt_sequence INTEGER NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    target_candidate_id UUID,
    target_credential_id UUID,
    reason_code VARCHAR(64) NOT NULL,
    reason_detail VARCHAR(1000)
);

-- 23. circuit_state
CREATE TABLE IF NOT EXISTS light_ai.circuit_state (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    scope_key VARCHAR(128) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL DEFAULT 'CLOSED',
    state_version BIGINT NOT NULL DEFAULT 1,
    opened_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    probe_count INTEGER NOT NULL DEFAULT 0,
    probe_success_count INTEGER NOT NULL DEFAULT 0,
    policy_snapshot JSONB NOT NULL,
    last_event_id UUID
);

-- 24. circuit_event
CREATE TABLE IF NOT EXISTS light_ai.circuit_event (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    scope_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_state VARCHAR(16) NOT NULL,
    to_state VARCHAR(16) NOT NULL,
    trigger_reason VARCHAR(64) NOT NULL,
    metrics_snapshot JSONB NOT NULL DEFAULT '{}'
);

-- 25. circuit_command
CREATE TABLE IF NOT EXISTS light_ai.circuit_command (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    scope_key VARCHAR(128) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    expected_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    operator_id VARCHAR(128) NOT NULL,
    executed_at TIMESTAMPTZ,
    error_summary VARCHAR(1000)
);

-- 26. usage_aggregation_event
CREATE TABLE IF NOT EXISTS light_ai.usage_aggregation_event (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    locked_by VARCHAR(128),
    locked_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    lock_generation BIGINT NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000)
);

-- 27. usage_aggregate
CREATE TABLE IF NOT EXISTS light_ai.usage_aggregate (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    granularity VARCHAR(4) NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    bucket_end TIMESTAMPTZ NOT NULL,
    dimension_key CHAR(64) NOT NULL,
    application VARCHAR(64) NOT NULL,
    project VARCHAR(64),
    tenant VARCHAR(64),
    alias_id UUID,
    provider_id UUID,
    provider_model_id UUID,
    credential_pool_id UUID,
    credential_id UUID,
    trace_status VARCHAR(24) NOT NULL,
    error_code VARCHAR(64),
    usage_source VARCHAR(12),
    requested_stream BOOLEAN NOT NULL DEFAULT FALSE,
    currency CHAR(3) NOT NULL,
    dimension_names JSONB NOT NULL DEFAULT '{}',
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
    input_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    output_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    total_cost NUMERIC(30,8) NOT NULL DEFAULT 0,
    total_ms_sum BIGINT NOT NULL DEFAULT 0,
    total_ms_count BIGINT NOT NULL DEFAULT 0,
    first_token_ms_sum BIGINT NOT NULL DEFAULT 0,
    first_token_ms_count BIGINT NOT NULL DEFAULT 0,
    queued_ms_sum BIGINT NOT NULL DEFAULT 0,
    latency_histogram JSONB NOT NULL DEFAULT '{}',
    first_token_histogram JSONB NOT NULL DEFAULT '{}'
);

-- 28. config_draft_state
CREATE TABLE IF NOT EXISTS light_ai.config_draft_state (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    singleton_key INTEGER NOT NULL DEFAULT 1 UNIQUE,
    base_snapshot_no BIGINT NOT NULL DEFAULT 0,
    draft_revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'EDITABLE',
    publish_record_id UUID,
    lock_acquired_at TIMESTAMPTZ,
    change_count INTEGER NOT NULL DEFAULT 0
);

-- 29. draft_change (R-class, no deleted_at)
CREATE TABLE IF NOT EXISTS light_ai.draft_change (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id UUID NOT NULL,
    entity_name VARCHAR(128) NOT NULL,
    change_type VARCHAR(12) NOT NULL,
    changed_fields JSONB NOT NULL DEFAULT '[]',
    modified_by VARCHAR(128) NOT NULL,
    entity_version BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL
);

-- 30. config_validation
CREATE TABLE IF NOT EXISTS light_ai.config_validation (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    validation_id UUID NOT NULL UNIQUE,
    base_snapshot_no BIGINT NOT NULL,
    target_snapshot_no BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    content_checksum CHAR(64) NOT NULL,
    status VARCHAR(12) NOT NULL,
    error_count INTEGER NOT NULL DEFAULT 0,
    warning_count INTEGER NOT NULL DEFAULT 0,
    validated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    validated_by VARCHAR(128) NOT NULL,
    used_by_publish_id UUID,
    change_summary JSONB NOT NULL DEFAULT '[]',
    affected_alias_ids JSONB NOT NULL DEFAULT '[]',
    target_instances JSONB NOT NULL DEFAULT '[]'
);

-- 31. config_validation_issue
CREATE TABLE IF NOT EXISTS light_ai.config_validation_issue (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    validation_id UUID NOT NULL,
    severity VARCHAR(8) NOT NULL,
    code VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id UUID,
    entity_name VARCHAR(128),
    field_path VARCHAR(256),
    message VARCHAR(1000) NOT NULL,
    suggestion VARCHAR(1000) NOT NULL,
    related_entity_ids JSONB NOT NULL DEFAULT '[]'
);

-- 32. config_snapshot
CREATE TABLE IF NOT EXISTS light_ai.config_snapshot (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    snapshot_no BIGINT NOT NULL UNIQUE,
    schema_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    content JSONB NOT NULL,
    content_checksum CHAR(64) NOT NULL,
    content_summary JSONB NOT NULL DEFAULT '{}',
    activated_at TIMESTAMPTZ,
    created_by VARCHAR(128) NOT NULL
);

-- 33. publish_record
CREATE TABLE IF NOT EXISTS light_ai.publish_record (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    publish_id UUID NOT NULL UNIQUE,
    validation_id UUID NOT NULL,
    base_snapshot_no BIGINT NOT NULL,
    target_snapshot_no BIGINT NOT NULL,
    draft_revision BIGINT NOT NULL,
    phase VARCHAR(24) NOT NULL DEFAULT 'PREPARING',
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    initiated_by VARCHAR(128) NOT NULL,
    timeout_at TIMESTAMPTZ NOT NULL,
    total_instances INTEGER NOT NULL DEFAULT 0,
    ready_instances INTEGER NOT NULL DEFAULT 0,
    failed_instances INTEGER NOT NULL DEFAULT 0,
    timeout_instances INTEGER NOT NULL DEFAULT 0,
    error_summary VARCHAR(1000)
);

-- 34. publish_instance_result
CREATE TABLE IF NOT EXISTS light_ai.publish_instance_result (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    publish_id UUID NOT NULL,
    instance_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    target_snapshot_no BIGINT NOT NULL,
    acked_snapshot_no BIGINT,
    latency_ms INTEGER,
    error_code VARCHAR(64),
    error_summary VARCHAR(1000)
);

-- 35. runtime_instance
CREATE TABLE IF NOT EXISTS light_ai.runtime_instance (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    instance_id UUID NOT NULL UNIQUE,
    runtime_mode VARCHAR(24) NOT NULL,
    runtime_version VARCHAR(64) NOT NULL,
    application VARCHAR(64) NOT NULL,
    zone VARCHAR(64),
    supported_schema_versions JSONB NOT NULL DEFAULT '[]',
    loaded_adapter_types JSONB NOT NULL DEFAULT '[]',
    active_snapshot_no BIGINT NOT NULL DEFAULT 0,
    accepting_requests BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(12) NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_error_summary VARCHAR(1000)
);

-- 36. access_credential
CREATE TABLE IF NOT EXISTS light_ai.access_credential (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    name VARCHAR(64) NOT NULL,
    application VARCHAR(64) NOT NULL,
    token_prefix VARCHAR(8) NOT NULL,
    token_hash BYTEA NOT NULL,
    token_hash_version INTEGER NOT NULL DEFAULT 1,
    masked_value VARCHAR(128) NOT NULL,
    ip_allowlist JSONB NOT NULL DEFAULT '[]',
    expires_at TIMESTAMPTZ,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    rotation_generation BIGINT NOT NULL DEFAULT 1,
    issued_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    last_used_ip_masked VARCHAR(128)
);

-- 37. access_credential_alias
CREATE TABLE IF NOT EXISTS light_ai.access_credential_alias (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    access_credential_id UUID NOT NULL,
    alias_id UUID NOT NULL
);

-- 38. audit_log
CREATE TABLE IF NOT EXISTS light_ai.audit_log (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(128),
    result VARCHAR(16) NOT NULL,
    changes JSONB NOT NULL DEFAULT '[]',
    error_code VARCHAR(64),
    error_summary VARCHAR(1000),
    source_mode VARCHAR(32) NOT NULL,
    source_ip_masked VARCHAR(128)
);

-- 39. retention_impact
CREATE TABLE IF NOT EXISTS light_ai.retention_impact (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    impact_version UUID NOT NULL UNIQUE,
    draft_revision BIGINT NOT NULL,
    target_values JSONB NOT NULL,
    counts JSONB NOT NULL,
    estimated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    estimated_by VARCHAR(128) NOT NULL
);

-- 初始种子数据（DATABASE_PLAN §4）
INSERT INTO light_ai.runtime_config (
    id, created_at, updated_at, version, singleton_key, timezone, timezone_locked,
    trace_retention_days, usage_retention_days, audit_retention_days,
    dashboard_refresh_seconds, max_message_chars, max_request_chars,
    diagnostic_sampling_enabled, diagnostic_sample_rate, diagnostic_sample_retention_days,
    diagnostic_sample_max_chars, client_ip_recording_enabled, trusted_proxy_cidrs,
    publish_instance_timeout_seconds, instance_stale_seconds, current_snapshot_no
) VALUES (
    '00000000-0000-0000-0000-000000000001', NOW(), NOW(), 1, 1, 'Asia/Shanghai', FALSE,
    7, 90, 365, 30, 64000, 256000, FALSE, 0, 3, 4000, FALSE, '[]'::jsonb,
    300, 45, 0
) ON CONFLICT (singleton_key) DO NOTHING;

INSERT INTO light_ai.config_draft_state (
    id, created_at, updated_at, singleton_key, base_snapshot_no, draft_revision, status, change_count
) VALUES (
    '00000000-0000-0000-0000-000000000002', NOW(), NOW(), 1, 0, 0, 'EDITABLE', 0
) ON CONFLICT (singleton_key) DO NOTHING;

INSERT INTO light_ai.config_snapshot (
    id, created_at, updated_at, snapshot_no, schema_version, status, content, content_checksum, content_summary, created_by
) VALUES (
    '00000000-0000-0000-0000-000000000003', NOW(), NOW(), 0, 1, 'ACTIVE',
    '{"schema_version":1,"providers":[],"credential_pools":[],"credentials":[],"provider_models":[],"model_aliases":[],"route_candidates":[],"limit_policies":[],"reliability_policies":[],"runtime_config":{"timezone":"Asia/Shanghai","trace_retention_days":7,"usage_retention_days":90,"audit_retention_days":365,"dashboard_refresh_seconds":30,"max_message_chars":64000,"max_request_chars":256000,"diagnostic_sampling_enabled":false,"diagnostic_sample_rate":0,"diagnostic_sample_retention_days":3,"diagnostic_sample_max_chars":4000,"client_ip_recording_enabled":false,"trusted_proxy_cidrs":[],"publish_instance_timeout_seconds":300,"instance_stale_seconds":45}}'::jsonb,
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    '{}'::jsonb, 'system'
) ON CONFLICT (snapshot_no) DO NOTHING;
