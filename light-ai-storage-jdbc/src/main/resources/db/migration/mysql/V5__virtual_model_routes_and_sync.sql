-- Light AI V2 资源域契约收口（DB-P21 / DB-211～215）：
--   * model_alias → virtual_model：id 不变，route_candidate / application_model_permission /
--     trace.alias_id 等历史引用保持有效；业务码列 alias → code
--   * route_candidate.alias_id → virtual_model_id；新增 conditions 与 status；三元组活行唯一
--   * upstream_model 新增人工锁定字段 locked_fields；同渠道 model_id 活行唯一
--   * channel_credential 同渠道 Key 名称活行唯一（历史同名 Key 为显示标签，以 8 位 id 后缀收敛）
--   * batch_check_job / batch_check_item 列与 JdbcBatchCheckRepository 契约对齐（BE-P21-006）
--   * 新增 model_sync_job / model_sync_item，支撑上游模型同步预览与幂等提交（BE-213）
--
-- 前置条件：MySQL 8.0+（与 V4 一致，使用 RENAME COLUMN）。JSON 列不设数据库默认值
-- （沿用 V4 方言口径），由仓储显式写入；收敛 UPDATE 对同表子查询使用派生表规避 MySQL 1093。
-- 唯一索引统一采用 (业务列…, deleted_at) 活行唯一；若历史数据存在无法收敛的冲突，
-- 索引创建失败并阻止升级，需先人工处理。MySQL DDL 隐式提交，中断后重跑可收敛。

-- 1. virtual_model：表与业务码列更名；能力交集与状态列；code 活行唯一
ALTER TABLE model_alias RENAME TO virtual_model;
ALTER TABLE virtual_model RENAME COLUMN alias TO code;
ALTER TABLE virtual_model ADD COLUMN capabilities JSON NULL;
ALTER TABLE virtual_model ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
UPDATE virtual_model SET status = CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END;
UPDATE virtual_model SET capabilities = '{}' WHERE capabilities IS NULL;
ALTER TABLE virtual_model ADD COLUMN active_token VARCHAR(40)
    GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE id END);
CREATE UNIQUE INDEX uk_virtual_model_code ON virtual_model (code, active_token);

-- 2. route_candidate：候选归属列更名；条件与状态；三元组活行唯一；反向引用索引
ALTER TABLE route_candidate RENAME COLUMN alias_id TO virtual_model_id;
ALTER TABLE route_candidate ADD COLUMN conditions JSON NULL;
ALTER TABLE route_candidate ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
UPDATE route_candidate SET status = CASE WHEN enabled THEN 'ACTIVE' ELSE 'DISABLED' END;
UPDATE route_candidate rc
SET deleted_at = NOW(), status = 'DISABLED', updated_at = NOW()
WHERE rc.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM (
                SELECT k.virtual_model_id, k.channel_id, k.upstream_model_id, k.created_at, k.id
                FROM route_candidate k WHERE k.deleted_at IS NULL) kx
              WHERE kx.virtual_model_id = rc.virtual_model_id
                AND kx.channel_id = rc.channel_id
                AND kx.upstream_model_id = rc.upstream_model_id
                AND (kx.created_at < rc.created_at
                     OR (kx.created_at = rc.created_at AND kx.id < rc.id)));
CREATE INDEX idx_route_candidate_virtual ON route_candidate (virtual_model_id, status, priority);
CREATE INDEX idx_route_candidate_channel ON route_candidate (channel_id);
CREATE INDEX idx_route_candidate_upstream ON route_candidate (upstream_model_id);
ALTER TABLE route_candidate ADD COLUMN active_token VARCHAR(40)
    GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE id END);
CREATE UNIQUE INDEX uk_route_candidate_triple
    ON route_candidate (virtual_model_id, channel_id, upstream_model_id, active_token);

-- 3. upstream_model：人工锁定字段；同渠道 model_id 活行唯一（重复身份行软删除收敛）
ALTER TABLE upstream_model ADD COLUMN locked_fields JSON NULL;
UPDATE upstream_model SET locked_fields = '[]' WHERE locked_fields IS NULL;
UPDATE upstream_model um
SET deleted_at = NOW(), status = 'DISABLED', updated_at = NOW()
WHERE um.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM (
                SELECT k.channel_id, k.model_id, k.created_at, k.id
                FROM upstream_model k WHERE k.deleted_at IS NULL) kx
              WHERE kx.channel_id = um.channel_id AND kx.model_id = um.model_id
                AND (kx.created_at < um.created_at
                     OR (kx.created_at = um.created_at AND kx.id < um.id)));
ALTER TABLE upstream_model ADD COLUMN active_token VARCHAR(40)
    GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE id END);
CREATE UNIQUE INDEX uk_upstream_model_identity ON upstream_model (channel_id, model_id, active_token);

-- 4. channel_credential：同渠道 Key 名称活行唯一（重复显示标签以 id 后缀收敛）
UPDATE channel_credential cc
SET name = CONCAT(LEFT(cc.name, 52), '-', SUBSTRING(cc.id, 1, 8))
WHERE cc.deleted_at IS NULL
  AND EXISTS (SELECT 1 FROM (
                SELECT k.channel_id, k.name, k.created_at, k.id
                FROM channel_credential k WHERE k.deleted_at IS NULL) kx
              WHERE kx.channel_id = cc.channel_id AND kx.name = cc.name
                AND (kx.created_at < cc.created_at
                     OR (kx.created_at = cc.created_at AND kx.id < cc.id)));
ALTER TABLE channel_credential ADD COLUMN active_token VARCHAR(40)
    GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE id END);
CREATE UNIQUE INDEX uk_channel_credential_name
    ON channel_credential (channel_id, name, active_token);

-- 5. batch_check_job / batch_check_item：列名与 JdbcBatchCheckRepository 契约对齐
ALTER TABLE batch_check_job RENAME COLUMN total_items TO total_count;
ALTER TABLE batch_check_job RENAME COLUMN completed_items TO completed_count;
ALTER TABLE batch_check_job RENAME COLUMN success_items TO success_count;
ALTER TABLE batch_check_job RENAME COLUMN failed_items TO failure_count;
ALTER TABLE batch_check_job RENAME COLUMN created_by TO operator_id;
ALTER TABLE batch_check_job ADD COLUMN cancelled_count INT NOT NULL DEFAULT 0;
ALTER TABLE batch_check_item ADD COLUMN `sequence` INT NOT NULL DEFAULT 0;
ALTER TABLE batch_check_item ADD COLUMN started_at DATETIME(6) NULL;
ALTER TABLE batch_check_item ADD COLUMN ended_at DATETIME(6) NULL;
CREATE INDEX idx_batch_check_item_job ON batch_check_item (job_id, `sequence`);

-- 6. 模型同步任务：预览 → 幂等提交（BE-213）。job 记录一次同步预览，
--    idempotency_key 作用域为渠道；item 为预览行明细，只追加。
CREATE TABLE IF NOT EXISTS model_sync_job (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    channel_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PREVIEWED',
    idempotency_key VARCHAR(128),
    requested_by VARCHAR(128) NOT NULL,
    preview_expires_at DATETIME(6),
    summary_json JSON,
    committed_at DATETIME(6),
    error_code VARCHAR(64),
    UNIQUE KEY uk_model_sync_job_idempotency (channel_id, idempotency_key),
    KEY idx_model_sync_job_channel (channel_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_sync_item (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    upstream_model_id VARCHAR(36),
    model_id VARCHAR(128) NOT NULL,
    change_type VARCHAR(16) NOT NULL,
    before_json JSON,
    after_json JSON,
    conflict_fields JSON,
    KEY idx_model_sync_item_job (job_id, model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
