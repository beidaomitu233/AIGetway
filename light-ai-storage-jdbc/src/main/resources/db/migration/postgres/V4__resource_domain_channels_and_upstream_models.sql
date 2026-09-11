-- Light AI V2 资源域：provider 目录 / channel / channel_credential / upstream_model
--
-- 迁移策略（一次到位，保留历史引用语义）：
--   * channel.id 复用原 provider.id
--   * channel_credential.id 复用原 credential.id
--   * upstream_model.id 复用原 provider_model.id
-- 因此 trace / attempt / usage_aggregate / circuit_state 等历史引用无需改写主键，
-- 只需按新语义重命名列。
--   * provider 由「连接对象」收敛为「协议类型目录」，连接参数迁入 channel
--   * credential_pool 中间层取消，credential 直挂 channel 成为渠道 Key
--   * credential_secret 折叠进 channel_credential
--   * route_candidate 候选路径由「上游模型 + 凭证池」改为「渠道 + 上游模型」
--
-- PostgreSQL DDL 与历史记录在同一事务提交，失败自动回滚。

-- 1. 渠道：承接原 provider 的连接语义，并新增优先级/权重/健康
CREATE TABLE IF NOT EXISTS light_ai.channel (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    provider_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    proxy_url VARCHAR(2048),
    connect_timeout_ms INTEGER NOT NULL DEFAULT 3000,
    read_timeout_ms INTEGER NOT NULL DEFAULT 120000,
    stream_idle_timeout_ms INTEGER NOT NULL DEFAULT 120000,
    default_headers JSONB NOT NULL DEFAULT '{}',
    priority INTEGER NOT NULL DEFAULT 10,
    weight INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    health VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    CONSTRAINT uk_channel_name UNIQUE (name)
);

CREATE INDEX IF NOT EXISTS idx_channel_status ON light_ai.channel (status, health);
CREATE INDEX IF NOT EXISTS idx_channel_provider ON light_ai.channel (provider_id);

INSERT INTO light_ai.channel (
    id, created_at, updated_at, version, deleted_at, provider_id, name, base_url, proxy_url,
    connect_timeout_ms, read_timeout_ms, stream_idle_timeout_ms, default_headers,
    priority, weight, status, health, last_checked_at, last_success_at, last_error_at, last_error)
SELECT p.id, p.created_at, p.updated_at, p.version, p.deleted_at,
       CASE p.type
           WHEN 'OPENAI' THEN '11111111-1111-4111-8111-111111110001'::uuid
           WHEN 'ANTHROPIC' THEN '11111111-1111-4111-8111-111111110002'::uuid
           WHEN 'GEMINI' THEN '11111111-1111-4111-8111-111111110003'::uuid
           WHEN 'DEEPSEEK' THEN '11111111-1111-4111-8111-111111110004'::uuid
           ELSE '11111111-1111-4111-8111-111111110005'::uuid
       END,
       p.name, p.base_url, p.proxy_url,
       p.connect_timeout_ms, p.read_timeout_ms, p.read_timeout_ms, p.default_headers,
       10, 1, CASE WHEN p.enabled THEN 'ACTIVE' ELSE 'DISABLED' END, 'UNKNOWN',
       NULL, NULL, NULL, NULL
FROM light_ai.provider p
WHERE NOT EXISTS (SELECT 1 FROM light_ai.channel);

-- 2. 渠道 Key：原 credential 直挂渠道，并折叠密钥密文
CREATE TABLE IF NOT EXISTS light_ai.channel_credential (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    channel_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    secret_ciphertext BYTEA,
    secret_ref_ciphertext BYTEA,
    key_id VARCHAR(128),
    masked_value VARCHAR(128) NOT NULL DEFAULT '',
    secret_version BIGINT NOT NULL DEFAULT 1,
    rotated_at TIMESTAMPTZ,
    priority INTEGER NOT NULL DEFAULT 10,
    weight INTEGER NOT NULL DEFAULT 1,
    rpm_limit BIGINT,
    tpm_limit BIGINT,
    concurrent_limit INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    health VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    rate_limit_reset_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_at TIMESTAMPTZ,
    last_error VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_channel_credential_channel
    ON light_ai.channel_credential (channel_id, status, priority);
CREATE INDEX IF NOT EXISTS idx_channel_credential_name
    ON light_ai.channel_credential (channel_id, name);

INSERT INTO light_ai.channel_credential (
    id, created_at, updated_at, version, deleted_at, channel_id, name,
    secret_ciphertext, secret_ref_ciphertext, key_id, masked_value, secret_version, rotated_at,
    priority, weight, rpm_limit, tpm_limit, concurrent_limit,
    status, health, rate_limit_reset_at, last_success_at, last_error_at, last_error)
SELECT c.id, c.created_at, c.updated_at, c.version, c.deleted_at, pl.provider_id, c.name,
       s.secret_ciphertext, s.secret_ref_ciphertext, s.encryption_key_id,
       COALESCE(s.masked_value, ''), COALESCE(s.secret_version, 1), s.rotated_at,
       10, c.weight, c.rpm_limit, c.tpm_limit, c.concurrent_limit,
       CASE WHEN c.enabled THEN 'ACTIVE' ELSE 'DISABLED' END, 'UNKNOWN',
       NULL, NULL, NULL, NULL
FROM light_ai.credential c
JOIN light_ai.credential_pool pl ON pl.id = c.pool_id
LEFT JOIN light_ai.credential_secret s ON s.credential_id = c.id
WHERE NOT EXISTS (SELECT 1 FROM light_ai.channel_credential);

-- 3. 上游模型：原 provider_model 绑定渠道
CREATE TABLE IF NOT EXISTS light_ai.upstream_model (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    channel_id UUID NOT NULL,
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
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    import_source VARCHAR(24),
    import_adapter_version VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_upstream_model_channel ON light_ai.upstream_model (channel_id, status);
CREATE INDEX IF NOT EXISTS idx_upstream_model_model ON light_ai.upstream_model (channel_id, model_id);

INSERT INTO light_ai.upstream_model (
    id, created_at, updated_at, version, deleted_at, channel_id, model_id, display_name,
    model_type, tokenizer_family, context_window, max_output_tokens,
    support_stream, support_system_message, support_temperature, support_top_p, support_stop,
    temperature_min, temperature_max, top_p_min, top_p_max, max_stop_sequences, max_stop_length,
    default_temperature, default_top_p, default_max_tokens, default_stop,
    input_price, output_price, price_unit, currency, status,
    import_source, import_adapter_version)
SELECT m.id, m.created_at, m.updated_at, m.version, m.deleted_at, m.provider_id, m.model_id, m.display_name,
       m.model_type, m.tokenizer_family, m.context_window, m.max_output_tokens,
       m.support_stream, m.support_system_message, m.support_temperature, m.support_top_p, m.support_stop,
       m.temperature_min, m.temperature_max, m.top_p_min, m.top_p_max, m.max_stop_sequences, m.max_stop_length,
       m.default_temperature, m.default_top_p, m.default_max_tokens, m.default_stop,
       m.input_price, m.output_price, m.price_unit, m.currency,
       CASE WHEN m.enabled THEN 'ACTIVE' ELSE 'DISABLED' END,
       m.import_source, m.import_adapter_version
FROM light_ai.provider_model m
WHERE NOT EXISTS (SELECT 1 FROM light_ai.upstream_model);

-- 4. 路由候选：候选路径由「上游模型 + 凭证池」改为「渠道 + 上游模型」
UPDATE light_ai.route_candidate
SET credential_pool_id = (
    SELECT pl.provider_id FROM light_ai.credential_pool pl
    WHERE pl.id = light_ai.route_candidate.credential_pool_id)
WHERE EXISTS (
    SELECT 1 FROM light_ai.credential_pool pl
    WHERE pl.id = light_ai.route_candidate.credential_pool_id);

ALTER TABLE light_ai.route_candidate RENAME COLUMN credential_pool_id TO channel_id;
ALTER TABLE light_ai.route_candidate RENAME COLUMN provider_model_id TO upstream_model_id;

-- 5. 历史与观测表按新语义重命名列（主键值不变，仅名称收敛）
ALTER TABLE light_ai.trace RENAME COLUMN final_provider_id TO final_channel_id;
ALTER TABLE light_ai.trace RENAME COLUMN final_provider_model_id TO final_upstream_model_id;
ALTER TABLE light_ai.trace RENAME COLUMN final_credential_id TO final_channel_credential_id;
ALTER TABLE light_ai.trace RENAME COLUMN final_provider_name TO final_channel_name;
ALTER TABLE light_ai.trace RENAME COLUMN final_provider_model_name TO final_upstream_model_name;

ALTER TABLE light_ai.attempt DROP COLUMN credential_pool_id;
ALTER TABLE light_ai.attempt RENAME COLUMN provider_id TO channel_id;
ALTER TABLE light_ai.attempt RENAME COLUMN provider_model_id TO upstream_model_id;
ALTER TABLE light_ai.attempt RENAME COLUMN credential_id TO channel_credential_id;
ALTER TABLE light_ai.attempt RENAME COLUMN provider_name_snapshot TO channel_name_snapshot;
ALTER TABLE light_ai.attempt RENAME COLUMN provider_model_name_snapshot TO upstream_model_name_snapshot;
ALTER TABLE light_ai.attempt RENAME COLUMN credential_name_snapshot TO channel_credential_name_snapshot;
ALTER TABLE light_ai.attempt RENAME COLUMN provider_started_at TO channel_started_at;
ALTER TABLE light_ai.attempt RENAME COLUMN provider_request_id TO channel_request_id;

ALTER TABLE light_ai.usage_aggregate DROP COLUMN credential_pool_id;
ALTER TABLE light_ai.usage_aggregate RENAME COLUMN provider_id TO channel_id;
ALTER TABLE light_ai.usage_aggregate RENAME COLUMN provider_model_id TO upstream_model_id;
ALTER TABLE light_ai.usage_aggregate RENAME COLUMN credential_id TO channel_credential_id;

ALTER TABLE light_ai.circuit_state RENAME COLUMN provider_model_id TO upstream_model_id;
ALTER TABLE light_ai.batch_check_item RENAME COLUMN provider_model_id TO upstream_model_id;

ALTER TABLE light_ai.provider_check_record RENAME COLUMN provider_request_id TO channel_request_id;
ALTER TABLE light_ai.provider_check_record RENAME TO channel_check_record;

-- 6. 删除被替代的 V1 资源表
DROP TABLE IF EXISTS light_ai.credential_secret;
DROP TABLE IF EXISTS light_ai.credential;
DROP TABLE IF EXISTS light_ai.credential_pool;
DROP TABLE IF EXISTS light_ai.provider_model;
DROP TABLE IF EXISTS light_ai.provider;

-- 7. Provider 收敛为协议类型目录
CREATE TABLE IF NOT EXISTS light_ai.provider (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    type VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    adapter_type VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uk_provider_type UNIQUE (type)
);

INSERT INTO light_ai.provider (id, created_at, updated_at, version, type, name, adapter_type, status) VALUES
    ('11111111-1111-4111-8111-111111110001', now(), now(), 1, 'OPENAI', 'OpenAI', 'OPENAI', 'ACTIVE'),
    ('11111111-1111-4111-8111-111111110002', now(), now(), 1, 'ANTHROPIC', 'Anthropic', 'ANTHROPIC', 'ACTIVE'),
    ('11111111-1111-4111-8111-111111110003', now(), now(), 1, 'GEMINI', 'Google Gemini', 'GEMINI', 'ACTIVE'),
    ('11111111-1111-4111-8111-111111110004', now(), now(), 1, 'DEEPSEEK', 'DeepSeek', 'DEEPSEEK', 'ACTIVE'),
    ('11111111-1111-4111-8111-111111110005', now(), now(), 1, 'OPENAI_COMPATIBLE', 'OpenAI Compatible', 'OPENAI_COMPATIBLE', 'ACTIVE')
ON CONFLICT (type) DO NOTHING;

-- 8. 运行态与草稿中的实体类型随资源域改名
UPDATE light_ai.object_runtime_state SET entity_type = 'CHANNEL' WHERE entity_type = 'PROVIDER';
UPDATE light_ai.object_runtime_state SET entity_type = 'CHANNEL_CREDENTIAL' WHERE entity_type = 'CREDENTIAL';
UPDATE light_ai.object_runtime_state SET entity_type = 'UPSTREAM_MODEL' WHERE entity_type = 'PROVIDER_MODEL';
UPDATE light_ai.draft_change SET entity_type = 'CHANNEL' WHERE entity_type = 'PROVIDER';
UPDATE light_ai.draft_change SET entity_type = 'CHANNEL_CREDENTIAL' WHERE entity_type = 'CREDENTIAL';
UPDATE light_ai.draft_change SET entity_type = 'UPSTREAM_MODEL' WHERE entity_type = 'PROVIDER_MODEL';
