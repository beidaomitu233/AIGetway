package com.lightai.client.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Provider 详情（BACKEND_PLAN 4.2.9.1；字段对齐 FE-009）。
 * recent_check_records 为详情页最近检测记录（C-024：按创建时间倒序，最多10条）。
 * created_by/updated_by 当前来自 draft_change.modified_by 摘要，
 * 专用列已登记 COMMUNICATION.md 待 DB-P02 确认。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderDetail(
        String id,
        String name,
        String type,
        String baseUrl,
        String proxyUrl,
        String connectionStatus,
        OffsetDateTime lastCheckAt,
        Long lastCheckLatencyMs,
        String lastErrorCode,
        boolean enabled,
        boolean draftChanged,
        long version,
        int connectTimeoutMs,
        int readTimeoutMs,
        Map<String, String> defaultHeaders,
        String createdBy,
        OffsetDateTime createdAt,
        String updatedBy,
        OffsetDateTime updatedAt,
        List<ProviderCheckRecord> recentCheckRecords) {

    public ProviderDetail {
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        recentCheckRecords = recentCheckRecords == null ? List.of() : List.copyOf(recentCheckRecords);
    }
}
