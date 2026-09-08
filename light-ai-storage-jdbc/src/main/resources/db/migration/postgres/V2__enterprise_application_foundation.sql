-- Light AI V2 enterprise application foundation (PostgreSQL)

CREATE TABLE IF NOT EXISTS light_ai.application (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    department VARCHAR(128),
    owner_id VARCHAR(128) NOT NULL,
    owner_name VARCHAR(128) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_called_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_application_status_updated ON light_ai.application(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_application_owner ON light_ai.application(owner_id);

CREATE TABLE IF NOT EXISTS light_ai.application_member (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    CONSTRAINT uk_application_member_subject UNIQUE (application_id, subject_id)
);
CREATE INDEX IF NOT EXISTS idx_application_member_subject ON light_ai.application_member(subject_id);

CREATE TABLE IF NOT EXISTS light_ai.application_quota_policy (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    application_id UUID NOT NULL UNIQUE,
    token_limit BIGINT,
    amount_limit DECIMAL(30,8),
    currency CHAR(3) NOT NULL,
    rpm INTEGER,
    tpm BIGINT,
    period_type VARCHAR(16) NOT NULL,
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    tokens_used BIGINT NOT NULL DEFAULT 0,
    tokens_reserved BIGINT NOT NULL DEFAULT 0,
    amount_used DECIMAL(30,8) NOT NULL DEFAULT 0,
    amount_reserved DECIMAL(30,8) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS light_ai.quota_adjustment (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    dimension VARCHAR(24) NOT NULL,
    before_value DECIMAL(30,8),
    delta_value DECIMAL(30,8),
    after_value DECIMAL(30,8),
    reason VARCHAR(500) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    CONSTRAINT uk_quota_adjustment_idempotency UNIQUE (application_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_quota_adjustment_application_time ON light_ai.quota_adjustment(application_id, created_at);

CREATE TABLE IF NOT EXISTS light_ai.application_model_permission (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    application_id UUID NOT NULL,
    virtual_model_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    constraints_json JSONB,
    CONSTRAINT uk_application_virtual_model UNIQUE (application_id, virtual_model_id)
);
CREATE INDEX IF NOT EXISTS idx_application_model_enabled ON light_ai.application_model_permission(application_id, enabled);

CREATE TABLE IF NOT EXISTS light_ai.application_key (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    application_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    key_prefix VARCHAR(12) NOT NULL,
    masked_value VARCHAR(32) NOT NULL,
    key_digest BYTEA NOT NULL UNIQUE,
    digest_version INTEGER NOT NULL DEFAULT 1,
    rotation_generation BIGINT NOT NULL DEFAULT 1,
    ip_allowlist JSONB,
    expires_at TIMESTAMPTZ,
    rpm INTEGER,
    tpm BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_used_at TIMESTAMPTZ,
    last_used_ip_masked VARCHAR(128),
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_application_key_application ON light_ai.application_key(application_id, status);
CREATE INDEX IF NOT EXISTS idx_application_key_prefix ON light_ai.application_key(key_prefix);

CREATE TABLE IF NOT EXISTS light_ai.budget_reservation (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    request_id VARCHAR(128) NOT NULL UNIQUE,
    application_id UUID NOT NULL,
    application_key_id UUID NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    reserved_amount DECIMAL(30,8) NOT NULL,
    currency CHAR(3) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    terminal_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_budget_reservation_expiry ON light_ai.budget_reservation(status, expires_at);

CREATE TABLE IF NOT EXISTS light_ai.usage_ledger (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    event_key VARCHAR(160) NOT NULL UNIQUE,
    request_id VARCHAR(128),
    application_id UUID NOT NULL,
    application_key_id UUID,
    virtual_model_id UUID,
    channel_id UUID,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    token_delta BIGINT NOT NULL DEFAULT 0,
    amount_delta DECIMAL(30,8) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    usage_source VARCHAR(16) NOT NULL,
    price_snapshot JSONB
);
CREATE INDEX IF NOT EXISTS idx_usage_ledger_application_time ON light_ai.usage_ledger(application_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_ledger_request ON light_ai.usage_ledger(request_id);
