package com.lightai.client.management;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 管理写操作统一结果（BACKEND_PLAN 2 协议字典）。
 * entity：创建/更新的非敏感详情，删除为 null；
 * draft_revision：即时操作（如凭证轮换）为 null。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ManagementOperationResult<T>(
        String id,
        long version,
        T entity,
        boolean draftChanged,
        Long draftRevision,
        String requestId) {
}
