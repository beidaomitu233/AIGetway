-- 应用模型映射（P1）：应用对外模型名映射到渠道和真实上游模型。
-- application_model_mapping 保存当前集合，application_config_revision 保存不可变版本摘要。
CREATE TABLE IF NOT EXISTS application_config_revision (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reason VARCHAR(500),
    created_by VARCHAR(128),
    mapping_count INT NOT NULL DEFAULT 0,
    content_json JSON,
    UNIQUE KEY uk_application_config_revision (application_id, revision),
    KEY idx_application_config_revision_active (application_id, status, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_model_mapping (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    revision_id VARCHAR(36) NOT NULL,
    virtual_model_id VARCHAR(36),
    public_model_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_application_model_mapping_name (application_id, public_model_name),
    KEY idx_application_model_mapping_revision (revision_id, status),
    KEY idx_application_model_mapping_virtual (virtual_model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_model_target (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    mapping_id VARCHAR(36) NOT NULL,
    channel_id VARCHAR(36) NOT NULL,
    upstream_model_id VARCHAR(36),
    upstream_model_name VARCHAR(128) NOT NULL,
    priority INT NOT NULL DEFAULT 10,
    weight INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    policy_json JSON,
    UNIQUE KEY uk_application_model_target (mapping_id, channel_id, upstream_model_name),
    KEY idx_application_model_target_channel (channel_id, status),
    KEY idx_application_model_target_mapping (mapping_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 将旧应用模型权限和候选路由迁移为同名映射；历史 ID 复用保证幂等和可追溯。
INSERT INTO application_config_revision (
    id, created_at, updated_at, application_id, revision, status, reason, created_by, mapping_count, content_json)
SELECT a.id, a.created_at, a.updated_at, a.id, 1, 'ACTIVE', 'V10 legacy model mapping migration', NULL,
       (SELECT COUNT(*) FROM application_model_permission p WHERE p.application_id = a.id AND p.enabled = 1), '{}'
FROM application a
WHERE NOT EXISTS (SELECT 1 FROM application_config_revision r WHERE r.application_id = a.id);

INSERT INTO application_model_mapping (
    id, created_at, updated_at, application_id, revision_id, virtual_model_id, public_model_name, status, version)
SELECT p.id, p.created_at, p.updated_at, p.application_id, p.application_id, p.virtual_model_id,
       v.code, CASE WHEN p.enabled = 1 THEN 'ACTIVE' ELSE 'DISABLED' END, p.version
FROM application_model_permission p
LEFT JOIN virtual_model v ON v.id = p.virtual_model_id
WHERE v.code IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM application_model_mapping m WHERE m.id = p.id);

INSERT INTO application_model_target (
    id, created_at, updated_at, mapping_id, channel_id, upstream_model_id, upstream_model_name,
    priority, weight, status, policy_json)
SELECT c.id, c.created_at, c.updated_at, p.id, c.channel_id, c.upstream_model_id,
       u.model_id, c.priority, c.weight, CASE WHEN c.enabled = 1 THEN 'ACTIVE' ELSE 'DISABLED' END, c.conditions
FROM application_model_permission p
JOIN application_model_mapping m ON m.id = p.id
JOIN route_candidate c ON c.virtual_model_id = p.virtual_model_id
JOIN upstream_model u ON u.id = c.upstream_model_id
WHERE p.enabled = 1
  AND c.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM application_model_target t WHERE t.id = c.id);
