package com.lightai.client.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 限流策略命令（BACKEND_PLAN 4.3.1；C-004：写入与筛选均用 overflow_strategy）。
 * 启用要求至少一个限额；scope_type/scope_id 创建后不可改。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LimitPolicySaveCommand(
        String name,
        String scopeType,
        String scopeId,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        String overflowStrategy,
        Integer queueTimeoutMs,
        Integer queueMaxSize,
        boolean enabled,
        Long version) {

    public static final String OVERFLOW_REJECT = "REJECT";
    public static final String OVERFLOW_QUEUE = "QUEUE";

    public void validate() {
        if (name == null || name.strip().length() < 2 || name.strip().length() > 64) {
            throw new IllegalArgumentException("name 长度必须为 2—64");
        }
        if (!"MODEL_ALIAS".equals(scopeType) && !"PROVIDER_MODEL".equals(scopeType)
                && !"CREDENTIAL".equals(scopeType)) {
            throw new IllegalArgumentException("scope_type 仅支持 MODEL_ALIAS/PROVIDER_MODEL/CREDENTIAL");
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scope_id 必填");
        }
        boolean hasLimit = rpmLimit != null || tpmLimit != null || concurrentLimit != null;
        if (enabled && !hasLimit) {
            throw new IllegalArgumentException("启用的策略至少需要一个限额");
        }
        if (rpmLimit != null && (rpmLimit <= 0 || rpmLimit > 1000000000L)) {
            throw new IllegalArgumentException("rpm_limit 为正数且不超过 10^9");
        }
        if (tpmLimit != null && (tpmLimit <= 0 || tpmLimit > 1000000000L)) {
            throw new IllegalArgumentException("tpm_limit 为正数且不超过 10^9");
        }
        if (concurrentLimit != null && (concurrentLimit < 1 || concurrentLimit > 100000)) {
            throw new IllegalArgumentException("concurrent_limit 范围 1—100000");
        }
        boolean queue = OVERFLOW_QUEUE.equals(overflowStrategy);
        if (!queue && !OVERFLOW_REJECT.equals(overflowStrategy)) {
            throw new IllegalArgumentException("overflow_strategy 仅支持 REJECT/QUEUE");
        }
        if (queue && (queueTimeoutMs == null || queueMaxSize == null)) {
            throw new IllegalArgumentException("QUEUE 策略必须提供 queue_timeout_ms 与 queue_max_size");
        }
        if (!queue && (queueTimeoutMs != null || queueMaxSize != null)) {
            throw new IllegalArgumentException("REJECT 策略不允许携带队列参数");
        }
        if (queueMaxSize != null && queueMaxSize < 1000) {
            throw new IllegalArgumentException("queue_max_size 不小于最小允许值 1000");
        }
    }
}
