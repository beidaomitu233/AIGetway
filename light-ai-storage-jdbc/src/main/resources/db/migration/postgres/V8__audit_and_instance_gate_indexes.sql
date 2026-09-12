-- Light AI V8 审计与实例交付门禁索引（PostgreSQL，DB-P23/zcode-db-0912c）
-- 依据 DATABASE_PLAN「DB-P23 数据交付记录」DB-232/DB-235：audit_log 补常用筛选索引，
-- runtime_instance 补实例巡检索引；不修改 V1～V7 已发布迁移，不新增表。
-- 迁移号协调：V5=DB-P21（已合入）、V6=DB-P20（已登记）、V7=DB-P22（已登记）。

-- 1. audit_log 常用筛选索引（DB-232）：时间排序、request_id 精确查询、
--    实体归属、操作者、动作维度（AuditQueryService 筛选口径）。
CREATE INDEX IF NOT EXISTS idx_audit_log_created
    ON light_ai.audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_request
    ON light_ai.audit_log (request_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity
    ON light_ai.audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_operator
    ON light_ai.audit_log (operator_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_action
    ON light_ai.audit_log (action, created_at);

-- 2. runtime_instance 实例巡检索引（DB-231/BE-233 实例收敛）：
--    按状态与最近心跳扫过期实例。
CREATE INDEX IF NOT EXISTS idx_runtime_instance_status_heartbeat
    ON light_ai.runtime_instance (status, last_heartbeat_at);
