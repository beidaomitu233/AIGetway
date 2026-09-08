-- Light AI V2 enterprise application foundation (MySQL 5.7 / 8.0)

CREATE TABLE IF NOT EXISTS application (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    department VARCHAR(128),
    owner_id VARCHAR(128) NOT NULL,
    owner_name VARCHAR(128) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_called_at DATETIME(6),
    UNIQUE KEY uk_application_code (code),
    KEY idx_application_status_updated (status, updated_at),
    KEY idx_application_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_member (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_application_member_subject (application_id, subject_id),
    KEY idx_application_member_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_quota_policy (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    application_id VARCHAR(36) NOT NULL,
    token_limit BIGINT,
    amount_limit DECIMAL(30,8),
    currency CHAR(3) NOT NULL,
    rpm INT,
    tpm BIGINT,
    period_type VARCHAR(16) NOT NULL,
    period_start DATETIME(6),
    period_end DATETIME(6),
    tokens_used BIGINT NOT NULL DEFAULT 0,
    tokens_reserved BIGINT NOT NULL DEFAULT 0,
    amount_used DECIMAL(30,8) NOT NULL DEFAULT 0,
    amount_reserved DECIMAL(30,8) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_application_quota_policy (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quota_adjustment (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    dimension VARCHAR(24) NOT NULL,
    before_value DECIMAL(30,8),
    delta_value DECIMAL(30,8),
    after_value DECIMAL(30,8),
    reason VARCHAR(500) NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    UNIQUE KEY uk_quota_adjustment_idempotency (application_id, idempotency_key),
    KEY idx_quota_adjustment_application_time (application_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_model_permission (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    application_id VARCHAR(36) NOT NULL,
    virtual_model_id VARCHAR(36) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    constraints_json JSON,
    UNIQUE KEY uk_application_virtual_model (application_id, virtual_model_id),
    KEY idx_application_model_enabled (application_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_key (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    application_id VARCHAR(36) NOT NULL,
    name VARCHAR(64) NOT NULL,
    key_prefix VARCHAR(12) NOT NULL,
    masked_value VARCHAR(32) NOT NULL,
    key_digest BINARY(32) NOT NULL,
    digest_version INT NOT NULL DEFAULT 1,
    rotation_generation BIGINT NOT NULL DEFAULT 1,
    ip_allowlist JSON,
    expires_at DATETIME(6),
    rpm INT,
    tpm BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_used_at DATETIME(6),
    last_used_ip_masked VARCHAR(128),
    rotated_at DATETIME(6),
    revoked_at DATETIME(6),
    UNIQUE KEY uk_application_key_digest (key_digest),
    KEY idx_application_key_application (application_id, status),
    KEY idx_application_key_prefix (key_prefix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS budget_reservation (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    application_key_id VARCHAR(36) NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    reserved_amount DECIMAL(30,8) NOT NULL,
    currency CHAR(3) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    terminal_at DATETIME(6),
    UNIQUE KEY uk_budget_reservation_request (request_id),
    KEY idx_budget_reservation_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS usage_ledger (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    event_key VARCHAR(160) NOT NULL,
    request_id VARCHAR(128),
    application_id VARCHAR(36) NOT NULL,
    application_key_id VARCHAR(36),
    virtual_model_id VARCHAR(36),
    channel_id VARCHAR(36),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    token_delta BIGINT NOT NULL DEFAULT 0,
    amount_delta DECIMAL(30,8) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    usage_source VARCHAR(16) NOT NULL,
    price_snapshot JSON,
    UNIQUE KEY uk_usage_ledger_event (event_key),
    KEY idx_usage_ledger_application_time (application_id, created_at),
    KEY idx_usage_ledger_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
