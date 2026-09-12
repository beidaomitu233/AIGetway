-- Light AI V6 应用域（DATABASE_PLAN DB-201～DB-205，按「BE-P20 数据库交付契约」）
--
-- 范围（增量 DDL + 可证明状态回填，不修改已发布迁移）：
--   * DB-201：audit_log 新增 application_id（可空，仅按可证明关系回填）与
--             (application_id,created_at,id) 索引；application 补 (status,last_called_at,id)
--             分页索引；budget_reservation 的 (application_id,status) 索引已由 V7 交付，不重复创建
--   * DB-202：application_key 新增 replaced_by_key_id / grace_expires_at（轮换代际与宽限）；
--             新建 application_key_operation 幂等操作表（创建 target_key 固定空串）
--   * DB-203：application_quota_policy 新增 current_period_id / policy_version 并回填
--             （NOT NULL 收紧随 BE-P20 切换写入路径时执行，本迁移保持可空）；
--             新建 application_quota_period / application_quota_policy_history；
--             budget_reservation、usage_ledger 新增 period_id / policy_version（可空，新写由服务保证）
--             与 context_origin（存量标 LEGACY_UNKNOWN，新写默认 V2）
--   * DB-204：quota_adjustment 新增 before/after_policy_version 与 change_json；
--             新建 application_quota_operation 幂等操作表（到期扫描索引 (status,effective_at)）
--   * DB-205：application_model_permission.constraints_json 的 stream_allowed → allow_stream
--             规范键转换；同时存在、非布尔值的行经门禁表 CHECK 阻止迁移
--             （constraints_json 由应用写入端以紧凑 JSON 生成，键名文本匹配即为精确判定；
--             冲突行明细请人工查询，不含敏感值）
--
-- 回填口径：仅为每个既有策略建立第 1 周期与策略历史（id 确定性取 application_id，幂等可重跑），
-- 已用/预占显式映射到当前回填周期；不推断已丢失的历史，遗留上下文标 LEGACY_UNKNOWN。
--
-- 迁移号协调：V5=DB-P21、V7=DB-P22、V8=DB-P23 已交付；V6 仅依赖 V1～V4 对象，
-- 与 V5/V7/V8 无顺序耦合。前置条件：MySQL 8.0.16+（CHECK 强制生效）；
-- MySQL DDL 隐式提交，失败重跑需人工核对已完成语句（与 V4/V5/V7 惯例一致）。

-- 1. DB-205 约束键规范转换（置于最前：门禁失败时未应用任何语句，修复数据后可整体重跑）
DROP TABLE IF EXISTS db205_conflict_gate;
CREATE TEMPORARY TABLE db205_conflict_gate (
    id VARCHAR(36) NOT NULL,
    CONSTRAINT chk_db205_no_conflict CHECK (FALSE)
);
INSERT INTO db205_conflict_gate (id)
SELECT id FROM application_model_permission
WHERE constraints_json IS NOT NULL AND (
      (constraints_json LIKE '%"stream_allowed":%' AND constraints_json LIKE '%"allow_stream":%')
   OR (constraints_json LIKE '%"stream_allowed":%'
       AND constraints_json NOT LIKE '%"stream_allowed":true%'
       AND constraints_json NOT LIKE '%"stream_allowed":false%')
   OR (constraints_json LIKE '%"allow_stream":%'
       AND constraints_json NOT LIKE '%"allow_stream":true%'
       AND constraints_json NOT LIKE '%"allow_stream":false%'));

UPDATE application_model_permission
SET constraints_json = REPLACE(constraints_json, '"stream_allowed"', '"allow_stream"')
WHERE constraints_json LIKE '%"stream_allowed":%'
  AND constraints_json NOT LIKE '%"allow_stream":%';
DROP TABLE IF EXISTS db205_conflict_gate;

-- 2. DB-201 审计应用归属与应用列表分页索引
ALTER TABLE audit_log ADD COLUMN application_id VARCHAR(36) NULL;
CREATE INDEX idx_audit_log_application ON audit_log (application_id, created_at, id);
CREATE INDEX idx_application_status_called ON application (status, last_called_at, id);

-- 3. DB-202 密钥代际与宽限 + 幂等操作表
ALTER TABLE application_key ADD COLUMN replaced_by_key_id VARCHAR(36) NULL;
ALTER TABLE application_key ADD COLUMN grace_expires_at DATETIME(6) NULL;

CREATE TABLE IF NOT EXISTS application_key_operation (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    target_key VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_key_id VARCHAR(36) NOT NULL,
    result_json JSON NOT NULL,
    UNIQUE KEY uk_application_key_operation_idempotency (application_id, operation, target_key, idempotency_key),
    KEY idx_application_key_operation_time (application_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. DB-203 额度周期与策略历史
CREATE TABLE IF NOT EXISTS application_quota_period (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    period_no BIGINT NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    previous_period_id VARCHAR(36) NULL,
    opening_tokens_used BIGINT NOT NULL DEFAULT 0,
    tokens_used BIGINT NOT NULL DEFAULT 0,
    tokens_reserved BIGINT NOT NULL DEFAULT 0,
    opening_amount_used DECIMAL(30,8) NOT NULL DEFAULT 0,
    amount_used DECIMAL(30,8) NOT NULL DEFAULT 0,
    amount_reserved DECIMAL(30,8) NOT NULL DEFAULT 0,
    CONSTRAINT ck_application_quota_period_status CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    UNIQUE KEY uk_application_quota_period_no (application_id, period_no),
    KEY idx_application_quota_period_scan (status, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS application_quota_policy_history (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    policy_version BIGINT NOT NULL,
    period_id VARCHAR(36) NOT NULL,
    policy_json JSON NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    UNIQUE KEY uk_application_quota_policy_history_version (application_id, policy_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. DB-203 当前策略入口列（回填既有策略；NOT NULL 收紧随 BE-P20 写入切换执行）
ALTER TABLE application_quota_policy ADD COLUMN current_period_id VARCHAR(36) NULL;
ALTER TABLE application_quota_policy ADD COLUMN policy_version BIGINT NULL;

-- 6. DB-203/204 预占与账本上下文标记
ALTER TABLE budget_reservation ADD COLUMN period_id VARCHAR(36) NULL;
ALTER TABLE budget_reservation ADD COLUMN policy_version BIGINT NULL;
ALTER TABLE budget_reservation ADD COLUMN context_origin VARCHAR(16) NULL;
ALTER TABLE usage_ledger ADD COLUMN period_id VARCHAR(36) NULL;
ALTER TABLE usage_ledger ADD COLUMN policy_version BIGINT NULL;
ALTER TABLE usage_ledger ADD COLUMN context_origin VARCHAR(16) NULL;

-- 7. DB-204 额度调整列 + 幂等操作表
ALTER TABLE quota_adjustment ADD COLUMN before_policy_version BIGINT NULL;
ALTER TABLE quota_adjustment ADD COLUMN after_policy_version BIGINT NULL;
ALTER TABLE quota_adjustment ADD COLUMN change_json JSON NULL;

CREATE TABLE IF NOT EXISTS application_quota_operation (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    application_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    command_json JSON NOT NULL,
    expected_policy_version BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_json JSON NULL,
    CONSTRAINT ck_application_quota_operation_status CHECK (status IN ('SCHEDULED', 'APPLIED', 'CONFLICT', 'FAILED')),
    UNIQUE KEY uk_application_quota_operation_idempotency (application_id, idempotency_key),
    KEY idx_application_quota_operation_scan (status, effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8. 回填：为既有策略建立第 1 周期（已用/预占显式映射；id 确定性取 application_id）
INSERT INTO application_quota_period (
    id, created_at, updated_at, application_id, period_no, period_type, timezone, currency,
    status, period_start, period_end, previous_period_id,
    opening_tokens_used, tokens_used, tokens_reserved,
    opening_amount_used, amount_used, amount_reserved)
SELECT p.application_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, p.application_id, 1,
       p.period_type, COALESCE(rc.timezone, 'Asia/Shanghai'), p.currency, 'OPEN',
       p.period_start, p.period_end, NULL,
       0, p.tokens_used, p.tokens_reserved, 0, p.amount_used, p.amount_reserved
FROM application_quota_policy p
LEFT JOIN runtime_config rc ON rc.singleton_key = 1
WHERE NOT EXISTS (SELECT 1 FROM application_quota_period q
                  WHERE q.application_id = p.application_id);

UPDATE application_quota_policy p
SET current_period_id = (SELECT q.id FROM application_quota_period q
        WHERE q.application_id = p.application_id AND q.period_no = 1),
    policy_version = 1
WHERE (p.current_period_id IS NULL OR p.policy_version IS NULL)
  AND EXISTS (SELECT 1 FROM application_quota_period q
        WHERE q.application_id = p.application_id AND q.period_no = 1);

-- 9. 回填：策略历史第 1 版（policy_json 固定存策略与周期口径，不存累计用量）
INSERT INTO application_quota_policy_history (
    id, created_at, application_id, policy_version, period_id, policy_json, operator_id, reason)
SELECT p.application_id, CURRENT_TIMESTAMP, p.application_id, 1, p.current_period_id,
       JSON_OBJECT('token_limit', p.token_limit, 'amount_limit', p.amount_limit, 'currency', p.currency,
                   'rpm', p.rpm, 'tpm', p.tpm, 'period_type', p.period_type,
                   'timezone', q.timezone, 'period_start', p.period_start, 'period_end', p.period_end),
       'system-migration', 'DB-203 回填当前策略'
FROM application_quota_policy p
JOIN application_quota_period q ON q.application_id = p.application_id AND q.period_no = 1
WHERE p.current_period_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM application_quota_policy_history h
                  WHERE h.application_id = p.application_id AND h.policy_version = 1);

-- 10. 回填：上下文标记（遗留行标 LEGACY_UNKNOWN，新写默认 V2）
UPDATE budget_reservation SET context_origin = 'LEGACY_UNKNOWN' WHERE context_origin IS NULL;
UPDATE usage_ledger SET context_origin = 'LEGACY_UNKNOWN' WHERE context_origin IS NULL;
ALTER TABLE budget_reservation MODIFY COLUMN context_origin VARCHAR(16) NOT NULL DEFAULT 'V2';
ALTER TABLE usage_ledger MODIFY COLUMN context_origin VARCHAR(16) NOT NULL DEFAULT 'V2';
ALTER TABLE budget_reservation
    ADD CONSTRAINT ck_budget_reservation_context_origin CHECK (context_origin IN ('V2', 'LEGACY_UNKNOWN'));
ALTER TABLE usage_ledger
    ADD CONSTRAINT ck_usage_ledger_context_origin CHECK (context_origin IN ('V2', 'LEGACY_UNKNOWN'));

CREATE INDEX idx_budget_reservation_period ON budget_reservation (application_id, period_id);
CREATE INDEX idx_usage_ledger_period ON usage_ledger (application_id, period_id);

-- 11. DB-201 审计归属回填（仅按可证明关系：应用自身事件与密钥事件关联，禁止模糊匹配文案）
UPDATE audit_log SET application_id = entity_id
WHERE LOWER(entity_type) = 'application' AND application_id IS NULL
  AND CHAR_LENGTH(entity_id) = 36;

UPDATE audit_log a
SET application_id = (SELECT k.application_id FROM application_key k
        WHERE k.id = a.entity_id)
WHERE LOWER(a.entity_type) = 'application_key' AND a.application_id IS NULL
  AND EXISTS (SELECT 1 FROM application_key k WHERE k.id = a.entity_id);
