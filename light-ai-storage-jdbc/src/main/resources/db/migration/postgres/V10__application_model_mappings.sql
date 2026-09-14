-- 应用模型映射（P1）：应用对外模型名映射到渠道和真实上游模型。
CREATE TABLE IF NOT EXISTS light_ai.application_config_revision (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reason VARCHAR(500),
    created_by VARCHAR(128),
    mapping_count INTEGER NOT NULL DEFAULT 0,
    content_json JSONB,
    UNIQUE (application_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_application_config_revision_active
    ON light_ai.application_config_revision (application_id, status, revision);

CREATE TABLE IF NOT EXISTS light_ai.application_model_mapping (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    virtual_model_id UUID,
    public_model_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    UNIQUE (application_id, public_model_name)
);
CREATE INDEX IF NOT EXISTS idx_application_model_mapping_revision
    ON light_ai.application_model_mapping (revision_id, status);
CREATE INDEX IF NOT EXISTS idx_application_model_mapping_virtual
    ON light_ai.application_model_mapping (virtual_model_id);

CREATE TABLE IF NOT EXISTS light_ai.application_model_target (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    mapping_id UUID NOT NULL,
    channel_id UUID NOT NULL,
    upstream_model_id UUID,
    upstream_model_name VARCHAR(128) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 10,
    weight INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    policy_json JSONB,
    UNIQUE (mapping_id, channel_id, upstream_model_name)
);
CREATE INDEX IF NOT EXISTS idx_application_model_target_channel
    ON light_ai.application_model_target (channel_id, status);
CREATE INDEX IF NOT EXISTS idx_application_model_target_mapping
    ON light_ai.application_model_target (mapping_id, status);

-- 将旧应用模型权限和候选路由迁移为同名映射；历史 ID 复用保证幂等和可追溯。
INSERT INTO light_ai.application_config_revision
    (id, created_at, updated_at, application_id, revision, status, reason, created_by, mapping_count, content_json)
SELECT a.id, a.created_at, a.updated_at, a.id, 1, 'ACTIVE', 'V10 legacy model mapping migration', NULL,
       (SELECT COUNT(*) FROM light_ai.application_model_permission p
        WHERE p.application_id = a.id AND p.enabled = TRUE), '{}'::jsonb
FROM light_ai.application a
WHERE NOT EXISTS (SELECT 1 FROM light_ai.application_config_revision r WHERE r.application_id = a.id);

INSERT INTO light_ai.application_model_mapping
    (id, created_at, updated_at, application_id, revision_id, virtual_model_id, public_model_name, status, version)
SELECT p.id, p.created_at, p.updated_at, p.application_id, p.application_id, p.virtual_model_id,
       v.code, CASE WHEN p.enabled THEN 'ACTIVE' ELSE 'DISABLED' END, p.version
FROM light_ai.application_model_permission p
LEFT JOIN light_ai.virtual_model v ON v.id = p.virtual_model_id
WHERE v.code IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM light_ai.application_model_mapping m WHERE m.id = p.id);

INSERT INTO light_ai.application_model_target
    (id, created_at, updated_at, mapping_id, channel_id, upstream_model_id, upstream_model_name,
     priority, weight, status, policy_json)
SELECT c.id, c.created_at, c.updated_at, p.id, c.channel_id, c.upstream_model_id,
       u.model_id, c.priority, c.weight, CASE WHEN c.enabled THEN 'ACTIVE' ELSE 'DISABLED' END, c.conditions
FROM light_ai.application_model_permission p
JOIN light_ai.application_model_mapping m ON m.id = p.id
JOIN light_ai.route_candidate c ON c.virtual_model_id = p.virtual_model_id
JOIN light_ai.upstream_model u ON u.id = c.upstream_model_id
WHERE p.enabled
  AND c.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM light_ai.application_model_target t WHERE t.id = c.id);
