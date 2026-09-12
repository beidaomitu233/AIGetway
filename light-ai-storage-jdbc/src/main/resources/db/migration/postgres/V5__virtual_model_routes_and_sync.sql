-- Light AI V2 资源域契约收口（DB-P21 / DB-211～215）：
--   * model_alias → virtual_model：id 不变，route_candidate / application_model_permission /
--     trace.alias_id 等历史引用保持有效；业务码列 alias → code
--   * route_candidate.alias_id → virtual_model_id；新增 conditions 与 status；三元组活行唯一
--   * upstream_model 新增人工锁定字段 locked_fields；同渠道 model_id 活行唯一
--   * channel_credential 同渠道 Key 名称活行唯一（历史同名 Key 为显示标签，以 8 位 id 后缀收敛）
--   * batch_check_job / batch_check_item 列与 JdbcBatchCheckRepository 契约对齐（BE-P21-006）
--   * 新增 model_sync_job / model_sync_item，支撑上游模型同步预览与幂等提交（BE-213）
--
-- 唯一索引统一采用 (业务列…, deleted_at) 活行唯一：活动行 deleted_at 为 NULL 保证唯一，
-- 软删除行不阻塞同名/同三元组重建；PostgreSQL / MySQL 8.0 / H2(MySQL 模式) 均可表达。
-- 收敛规则：重复活动行保留 created_at/id 最小者；identity 重复（upstream_model、route_candidate）
-- 其余行软删除；显示标签重复（channel_credential.name）其余行以 id 后缀重命名。
-- 若唯一索引创建仍失败，说明历史数据存在无法收敛的冲突，迁移失败并阻止升级，需先人工处理。

-- 1. virtual_model：表与业务码列更名；能力交集与状态列；code 活行唯一
ALTER TABLE light_ai.model_alias RENAME TO virtual_model;
ALTER TABLE light_ai.virtual_model RENAME COLUMN alias TO code;
ALTER TABLE light_ai.virtual_model ADD COLUMN capabilities JSONB NOT NULL DEFAULT '{}';
ALTER TABLE light_ai.virtual_model ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
UPDATE light_ai.virtual_model SET status = CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END;
CREATE UNIQUE INDEX IF NOT EXISTS uk_virtual_model_code ON light_ai.virtual_model (code)
    WHERE deleted_at IS NULL;

-- 2. route_candidate：候选归属列更名；条件与状态；三元组活行唯一；反向引用索引
ALTER TABLE light_ai.route_candidate RENAME COLUMN alias_id TO virtual_model_id;
ALTER TABLE light_ai.route_candidate ADD COLUMN conditions JSONB;
ALTER TABLE light_ai.route_candidate ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
UPDATE light_ai.route_candidate SET status = CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END;
UPDATE light_ai.route_candidate rc
SET deleted_at = now(), status = 'DISABLED', updated_at = now()
WHERE rc.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM light_ai.route_candidate k
              WHERE k.virtual_model_id = rc.virtual_model_id
                AND k.channel_id = rc.channel_id
                AND k.upstream_model_id = rc.upstream_model_id
                AND k.deleted_at IS NULL
                AND (k.created_at < rc.created_at
                     OR (k.created_at = rc.created_at AND k.id < rc.id)));
CREATE INDEX IF NOT EXISTS idx_route_candidate_virtual
    ON light_ai.route_candidate (virtual_model_id, status, priority);
CREATE INDEX IF NOT EXISTS idx_route_candidate_channel ON light_ai.route_candidate (channel_id);
CREATE INDEX IF NOT EXISTS idx_route_candidate_upstream ON light_ai.route_candidate (upstream_model_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_route_candidate_triple
    ON light_ai.route_candidate (virtual_model_id, channel_id, upstream_model_id)
    WHERE deleted_at IS NULL;

-- 3. upstream_model：人工锁定字段；同渠道 model_id 活行唯一（重复身份行软删除收敛）
ALTER TABLE light_ai.upstream_model ADD COLUMN locked_fields JSONB NOT NULL DEFAULT '[]';
UPDATE light_ai.upstream_model um
SET deleted_at = now(), status = 'DISABLED', updated_at = now()
WHERE um.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM light_ai.upstream_model k
              WHERE k.channel_id = um.channel_id AND k.model_id = um.model_id
                AND k.deleted_at IS NULL
                AND (k.created_at < um.created_at
                     OR (k.created_at = um.created_at AND k.id < um.id)));
CREATE UNIQUE INDEX IF NOT EXISTS uk_upstream_model_identity
    ON light_ai.upstream_model (channel_id, model_id)
    WHERE deleted_at IS NULL;

-- 4. channel_credential：同渠道 Key 名称活行唯一（重复显示标签以 id 后缀收敛）
UPDATE light_ai.channel_credential cc
SET name = left(cc.name, 52) || '-' || substring(cc.id::text, 1, 8)
WHERE cc.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM light_ai.channel_credential k
              WHERE k.channel_id = cc.channel_id AND k.name = cc.name
                AND k.deleted_at IS NULL
                AND (k.created_at < cc.created_at
                     OR (k.created_at = cc.created_at AND k.id < cc.id)));
CREATE UNIQUE INDEX IF NOT EXISTS uk_channel_credential_name
    ON light_ai.channel_credential (channel_id, name)
    WHERE deleted_at IS NULL;

-- 5. batch_check_job / batch_check_item：列名与 JdbcBatchCheckRepository 契约对齐
ALTER TABLE light_ai.batch_check_job RENAME COLUMN total_items TO total_count;
ALTER TABLE light_ai.batch_check_job RENAME COLUMN completed_items TO completed_count;
ALTER TABLE light_ai.batch_check_job RENAME COLUMN success_items TO success_count;
ALTER TABLE light_ai.batch_check_job RENAME COLUMN failed_items TO failure_count;
ALTER TABLE light_ai.batch_check_job RENAME COLUMN created_by TO operator_id;
ALTER TABLE light_ai.batch_check_job ADD COLUMN cancelled_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE light_ai.batch_check_item ADD COLUMN sequence INTEGER NOT NULL DEFAULT 0;
ALTER TABLE light_ai.batch_check_item ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE light_ai.batch_check_item ADD COLUMN ended_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_batch_check_item_job
    ON light_ai.batch_check_item (job_id, sequence);

-- 6. 模型同步任务：预览 → 幂等提交（BE-213）。job 记录一次同步预览，
--    idempotency_key 作用域为渠道；item 为预览行明细，只追加。
CREATE TABLE IF NOT EXISTS light_ai.model_sync_job (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    channel_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PREVIEWED',
    idempotency_key VARCHAR(128),
    requested_by VARCHAR(128) NOT NULL,
    preview_expires_at TIMESTAMPTZ,
    summary_json JSONB NOT NULL DEFAULT '{}',
    committed_at TIMESTAMPTZ,
    error_code VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_model_sync_job_channel
    ON light_ai.model_sync_job (channel_id, status, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_model_sync_job_idempotency
    ON light_ai.model_sync_job (channel_id, idempotency_key);

CREATE TABLE IF NOT EXISTS light_ai.model_sync_item (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    job_id UUID NOT NULL,
    upstream_model_id UUID,
    model_id VARCHAR(128) NOT NULL,
    change_type VARCHAR(16) NOT NULL,
    before_json JSONB,
    after_json JSONB,
    conflict_fields JSONB
);
CREATE INDEX IF NOT EXISTS idx_model_sync_item_job
    ON light_ai.model_sync_item (job_id, model_id);
