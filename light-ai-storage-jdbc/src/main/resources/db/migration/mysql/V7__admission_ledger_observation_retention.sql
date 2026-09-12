-- Light AI V7 准入、账本与观测域（DATABASE_PLAN DB-221～DB-225）
--
-- 范围（一次到位，全部为增量 DDL，不修改已发布迁移）：
--   * budget_reservation：状态词汇约束与应用维度索引（DB-221 唯一状态机、过期回收）
--   * usage_ledger：event_type 事件类型与渠道归属/事件类型索引（DB-223 不可覆盖事件账本）
--   * trace：application_id / application_key_id 维度列与索引（DB-222 应用/密钥字段补齐）
--   * attempt：(trace_id, sequence) 唯一约束（DB-222 Attempt 序号防重）
--   * usage_aggregate：application_key_id 维度列、聚合幂等唯一键与时间维度索引（DB-224）
--   * retention_deletion_batch：删除批次表（DB-225 批次幂等，批次键 domain+cutoff 唯一）
--
-- 迁移号协调：V5=DB-P21（渠道/模型/路由域）、V6=DB-P20（应用域）已登记占用；
-- 本迁移仅依赖 V1～V4 已有对象，与 V5/V6 无顺序耦合。
-- 前置条件：MySQL 8.0.16+（CHECK 约束强制生效）；MySQL DDL 隐式提交，
-- 首次执行失败重跑前需人工核对已完成语句（与 V4 惯例一致）。

-- 1. DB-221 预算预占：状态机词汇约束 + 应用维度索引（过期回收沿用 V2 的 (status, expires_at) 索引）
ALTER TABLE budget_reservation
    ADD CONSTRAINT ck_budget_reservation_status
    CHECK (status IN ('ACTIVE', 'SETTLED', 'RELEASED', 'EXPIRED'));

CREATE INDEX idx_budget_reservation_application ON budget_reservation (application_id, status);

-- 2. DB-223 Usage Ledger：事件类型（RESERVE/SETTLE/RELEASE/PERIOD_RESET/ADJUSTMENT）
ALTER TABLE usage_ledger ADD COLUMN event_type VARCHAR(24) NOT NULL DEFAULT 'SETTLE';

CREATE INDEX idx_usage_ledger_event_type ON usage_ledger (event_type, created_at);
CREATE INDEX idx_usage_ledger_channel_time ON usage_ledger (channel_id, created_at);

-- 3. DB-222 Trace/Attempt：应用与密钥维度（application 原为名称列，UUID 维度按需回填）
ALTER TABLE trace ADD COLUMN application_id VARCHAR(36) NULL;
ALTER TABLE trace ADD COLUMN application_key_id VARCHAR(36) NULL;

CREATE INDEX idx_trace_application_time ON trace (application_id, created_at);
CREATE INDEX idx_trace_application_key ON trace (application_key_id);

-- Attempt 序号防重：同一 trace 内 sequence 唯一
CREATE UNIQUE INDEX uk_attempt_trace_sequence ON attempt (trace_id, sequence);

-- 4. DB-224 聚合与索引：密钥维度 + 聚合幂等唯一键（JdbcUsageAggregateRepository
--    的 ON CONFLICT/ON DUPLICATE 依赖该键）+ 查询时间维度索引
ALTER TABLE usage_aggregate ADD COLUMN application_key_id VARCHAR(36) NULL;

CREATE UNIQUE INDEX uk_usage_aggregate_bucket_dimension
    ON usage_aggregate (granularity, bucket_start, dimension_key, currency);
CREATE INDEX idx_usage_aggregate_application_bucket ON usage_aggregate (application, bucket_start);
CREATE INDEX idx_usage_aggregate_channel_bucket ON usage_aggregate (channel_id, bucket_start);
CREATE INDEX idx_usage_aggregate_model_bucket ON usage_aggregate (upstream_model_id, bucket_start);

-- 5. DB-225 留存与清理：删除批次（策略口径沿用 runtime_config 各 retention_days 字段，
--    影响报告沿用 retention_impact 表）；批次幂等键为 (domain, cutoff_at)，重试复用同批次行
CREATE TABLE IF NOT EXISTS retention_deletion_batch (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    domain VARCHAR(24) NOT NULL,
    cutoff_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    scanned_rows BIGINT NOT NULL DEFAULT 0,
    deleted_rows BIGINT NOT NULL DEFAULT 0,
    error_summary VARCHAR(1000),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    operator VARCHAR(128),
    CONSTRAINT ck_retention_deletion_batch_domain
        CHECK (domain IN ('TRACE', 'TRACE_CONTENT_SAMPLE', 'USAGE_LEDGER', 'AUDIT_LOG', 'USAGE_AGGREGATE')),
    CONSTRAINT ck_retention_deletion_batch_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    UNIQUE KEY uk_retention_deletion_batch_domain_cutoff (domain, cutoff_at),
    KEY idx_retention_deletion_batch_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
