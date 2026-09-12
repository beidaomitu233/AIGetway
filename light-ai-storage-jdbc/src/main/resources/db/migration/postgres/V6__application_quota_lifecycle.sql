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
--             规范键转换；同时存在、非布尔值的行阻止迁移并给出不含敏感值的示例 id 报告
--
-- 回填口径：仅为每个既有策略建立第 1 周期与策略历史（id 确定性取 application_id，幂等可重跑），
-- 已用/预占显式映射到当前回填周期；不推断已丢失的历史，遗留上下文标 LEGACY_UNKNOWN。
--
-- 迁移号协调：V5=DB-P21、V7=DB-P22、V8=DB-P23 已交付；V6 仅依赖 V1～V4 对象，
-- 与 V5/V7/V8 无顺序耦合。PostgreSQL DDL 与历史记录在同一事务提交，失败自动回滚。

-- 1. DB-205 约束键规范转换（置于最前：门禁失败时未应用任何语句，修复数据后可整体重跑）
-- 门禁以单语句表达（迁移器按分号切分，不使用 DO 块）：存在冲突行时 CASE 命中
-- 非法整数转换并中止迁移，错误消息携带不含敏感值的示例 id；无冲突时短路返回 0。
WITH conflicts AS (
    SELECT id FROM light_ai.application_model_permission
    WHERE constraints_json IS NOT NULL AND (
        (constraints_json ? 'allow_stream' AND constraints_json ? 'stream_allowed')
        OR (constraints_json ? 'allow_stream'
            AND jsonb_typeof(constraints_json -> 'allow_stream') <> 'boolean')
        OR (constraints_json ? 'stream_allowed'
            AND jsonb_typeof(constraints_json -> 'stream_allowed') <> 'boolean')))
SELECT CASE
    WHEN (SELECT count(*) FROM conflicts) = 0 THEN 0
    ELSE ('DB-205 约束键转换存在冲突或类型错误行，人工修复后重跑迁移（示例 id：'
          || COALESCE((SELECT string_agg(id::text, ',') FROM
              (SELECT id FROM conflicts ORDER BY id LIMIT 20) s), '无'))::integer
END AS db205_gate;

UPDATE light_ai.application_model_permission
SET constraints_json = jsonb_set(constraints_json - 'stream_allowed', '{allow_stream}',
        constraints_json -> 'stream_allowed')
WHERE constraints_json ? 'stream_allowed' AND NOT constraints_json ? 'allow_stream';

-- 2. DB-201 审计应用归属与应用列表分页索引
ALTER TABLE light_ai.audit_log ADD COLUMN application_id UUID;
CREATE INDEX IF NOT EXISTS idx_audit_log_application
    ON light_ai.audit_log (application_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_application_status_called
    ON light_ai.application (status, last_called_at, id);

-- 3. DB-202 密钥代际与宽限 + 幂等操作表
ALTER TABLE light_ai.application_key ADD COLUMN replaced_by_key_id UUID;
ALTER TABLE light_ai.application_key ADD COLUMN grace_expires_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS light_ai.application_key_operation (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL,
    target_key VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_key_id UUID NOT NULL,
    result_json JSONB NOT NULL,
    CONSTRAINT uk_application_key_operation_idempotency
        UNIQUE (application_id, operation, target_key, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_application_key_operation_time
    ON light_ai.application_key_operation (application_id, created_at);

-- 4. DB-203 额度周期与策略历史
CREATE TABLE IF NOT EXISTS light_ai.application_quota_period (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    period_no BIGINT NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    previous_period_id UUID,
    opening_tokens_used BIGINT NOT NULL DEFAULT 0,
    tokens_used BIGINT NOT NULL DEFAULT 0,
    tokens_reserved BIGINT NOT NULL DEFAULT 0,
    opening_amount_used DECIMAL(30,8) NOT NULL DEFAULT 0,
    amount_used DECIMAL(30,8) NOT NULL DEFAULT 0,
    amount_reserved DECIMAL(30,8) NOT NULL DEFAULT 0,
    CONSTRAINT ck_application_quota_period_status CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    CONSTRAINT uk_application_quota_period_no UNIQUE (application_id, period_no)
);
CREATE INDEX IF NOT EXISTS idx_application_quota_period_scan
    ON light_ai.application_quota_period (status, period_end);

CREATE TABLE IF NOT EXISTS light_ai.application_quota_policy_history (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    policy_version BIGINT NOT NULL,
    period_id UUID NOT NULL,
    policy_json JSONB NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    CONSTRAINT uk_application_quota_policy_history_version UNIQUE (application_id, policy_version)
);

-- 5. DB-203 当前策略入口列（回填既有策略；NOT NULL 收紧随 BE-P20 写入切换执行）
ALTER TABLE light_ai.application_quota_policy ADD COLUMN current_period_id UUID;
ALTER TABLE light_ai.application_quota_policy ADD COLUMN policy_version BIGINT;

-- 6. DB-203/204 预占与账本上下文标记
ALTER TABLE light_ai.budget_reservation ADD COLUMN period_id UUID;
ALTER TABLE light_ai.budget_reservation ADD COLUMN policy_version BIGINT;
ALTER TABLE light_ai.budget_reservation ADD COLUMN context_origin VARCHAR(16);
ALTER TABLE light_ai.usage_ledger ADD COLUMN period_id UUID;
ALTER TABLE light_ai.usage_ledger ADD COLUMN policy_version BIGINT;
ALTER TABLE light_ai.usage_ledger ADD COLUMN context_origin VARCHAR(16);

-- 7. DB-204 额度调整列 + 幂等操作表
ALTER TABLE light_ai.quota_adjustment ADD COLUMN before_policy_version BIGINT;
ALTER TABLE light_ai.quota_adjustment ADD COLUMN after_policy_version BIGINT;
ALTER TABLE light_ai.quota_adjustment ADD COLUMN change_json JSONB;

CREATE TABLE IF NOT EXISTS light_ai.application_quota_operation (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    application_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    command_json JSONB NOT NULL,
    expected_policy_version BIGINT NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_json JSONB,
    CONSTRAINT ck_application_quota_operation_status
        CHECK (status IN ('SCHEDULED', 'APPLIED', 'CONFLICT', 'FAILED')),
    CONSTRAINT uk_application_quota_operation_idempotency UNIQUE (application_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_application_quota_operation_scan
    ON light_ai.application_quota_operation (status, effective_at);

-- 8. 回填：为既有策略建立第 1 周期（已用/预占显式映射；id 确定性取 application_id）
INSERT INTO light_ai.application_quota_period (
    id, created_at, updated_at, application_id, period_no, period_type, timezone, currency,
    status, period_start, period_end, previous_period_id,
    opening_tokens_used, tokens_used, tokens_reserved,
    opening_amount_used, amount_used, amount_reserved)
SELECT p.application_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, p.application_id, 1,
       p.period_type, COALESCE(rc.timezone, 'Asia/Shanghai'), p.currency, 'OPEN',
       p.period_start, p.period_end, NULL,
       0, p.tokens_used, p.tokens_reserved, 0, p.amount_used, p.amount_reserved
FROM light_ai.application_quota_policy p
LEFT JOIN light_ai.runtime_config rc ON rc.singleton_key = 1
WHERE NOT EXISTS (SELECT 1 FROM light_ai.application_quota_period q
                  WHERE q.application_id = p.application_id);

UPDATE light_ai.application_quota_policy p
SET current_period_id = q.id, policy_version = 1
FROM light_ai.application_quota_period q
WHERE q.application_id = p.application_id AND q.period_no = 1
  AND (p.current_period_id IS NULL OR p.policy_version IS NULL);

-- 9. 回填：策略历史第 1 版（policy_json 固定存策略与周期口径，不存累计用量）
INSERT INTO light_ai.application_quota_policy_history (
    id, created_at, application_id, policy_version, period_id, policy_json, operator_id, reason)
SELECT p.application_id, CURRENT_TIMESTAMP, p.application_id, 1, p.current_period_id,
       jsonb_build_object(
           'token_limit', p.token_limit, 'amount_limit', p.amount_limit, 'currency', p.currency,
           'rpm', p.rpm, 'tpm', p.tpm, 'period_type', p.period_type,
           'timezone', q.timezone, 'period_start', p.period_start, 'period_end', p.period_end),
       'system-migration', 'DB-203 回填当前策略'
FROM light_ai.application_quota_policy p
JOIN light_ai.application_quota_period q
  ON q.application_id = p.application_id AND q.period_no = 1
WHERE p.current_period_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM light_ai.application_quota_policy_history h
                  WHERE h.application_id = p.application_id AND h.policy_version = 1);

-- 10. 回填：上下文标记（遗留行标 LEGACY_UNKNOWN，新写默认 V2）
UPDATE light_ai.budget_reservation SET context_origin = 'LEGACY_UNKNOWN' WHERE context_origin IS NULL;
UPDATE light_ai.usage_ledger SET context_origin = 'LEGACY_UNKNOWN' WHERE context_origin IS NULL;
ALTER TABLE light_ai.budget_reservation ALTER COLUMN context_origin SET NOT NULL;
ALTER TABLE light_ai.budget_reservation ALTER COLUMN context_origin SET DEFAULT 'V2';
ALTER TABLE light_ai.usage_ledger ALTER COLUMN context_origin SET NOT NULL;
ALTER TABLE light_ai.usage_ledger ALTER COLUMN context_origin SET DEFAULT 'V2';
ALTER TABLE light_ai.budget_reservation
    ADD CONSTRAINT ck_budget_reservation_context_origin CHECK (context_origin IN ('V2', 'LEGACY_UNKNOWN'));
ALTER TABLE light_ai.usage_ledger
    ADD CONSTRAINT ck_usage_ledger_context_origin CHECK (context_origin IN ('V2', 'LEGACY_UNKNOWN'));

CREATE INDEX IF NOT EXISTS idx_budget_reservation_period
    ON light_ai.budget_reservation (application_id, period_id);
CREATE INDEX IF NOT EXISTS idx_usage_ledger_period
    ON light_ai.usage_ledger (application_id, period_id);

-- 11. DB-201 审计归属回填（仅按可证明关系：应用自身事件与密钥事件关联，禁止模糊匹配文案）
UPDATE light_ai.audit_log a
SET application_id = a.entity_id::uuid
WHERE LOWER(a.entity_type) = 'application' AND a.application_id IS NULL
  AND a.entity_id ~ '^[0-9a-fA-F-]{36}$';

UPDATE light_ai.audit_log a
SET application_id = k.application_id
FROM light_ai.application_key k
WHERE LOWER(a.entity_type) = 'application_key' AND a.application_id IS NULL
  AND a.entity_id = k.id::text;
