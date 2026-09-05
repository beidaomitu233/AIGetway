package com.lightai.storage.schema;

import java.util.List;
import java.util.Set;

/**
 * 产品必须存在的 39 张表（DATABASE_PLAN 第 2 节）。
 * VALIDATE 按此清单核对 information_schema；缺表或结构不符阻止就绪。
 */
public final class ExpectedSchema {

    public static final String SCHEMA_NAME = "light_ai";

    public static final Set<String> TABLES = Set.of(
            "provider",
            "credential_pool",
            "credential",
            "credential_secret",
            "provider_model",
            "model_alias",
            "route_candidate",
            "limit_policy",
            "reliability_policy",
            "runtime_config",
            "object_runtime_state",
            "provider_check_record",
            "batch_check_job",
            "batch_check_item",
            "trace",
            "attempt",
            "trace_content_sample",
            "route_decision",
            "capacity_reservation",
            "capacity_reservation_item",
            "queue_entry",
            "recovery_decision",
            "circuit_state",
            "circuit_event",
            "circuit_command",
            "usage_aggregation_event",
            "usage_aggregate",
            "config_draft_state",
            "draft_change",
            "config_validation",
            "config_validation_issue",
            "config_snapshot",
            "publish_record",
            "publish_instance_result",
            "runtime_instance",
            "access_credential",
            "access_credential_alias",
            "audit_log",
            "retention_impact");

    private ExpectedSchema() {
    }

    /** 返回 missing - existing 的差集，用于就绪校验输出。 */
    public static List<String> missingTables(Set<String> existing) {
        return TABLES.stream()
                .filter(table -> !existing.contains(table))
                .sorted()
                .toList();
    }
}
