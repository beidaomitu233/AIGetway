package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 渠道详情（BACKEND_PLAN BE-211：status/health 分列，timeouts/headers/priority/weight 收口）。
 * version 为配置乐观锁版本，编辑、启停与删除均需回传；详情页据此提交写操作。
 * recent_check_records 为详情页最近检测记录（按创建时间倒序，最多10条）。
 * health 由 object_runtime_state 派生；created_by/updated_by 当前来自
 * draft_change.modified_by 摘要，专用列已登记 COMMUNICATION.md 待确认。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChannelDetail(
        String id,
        String name,
        String providerType,
        String baseUrl,
        String proxy,
        ChannelTimeouts timeouts,
        Map<String, String> headers,
        String status,
        String health,
        int priority,
        int weight,
        long version,
        boolean draftChanged,
        OffsetDateTime lastCheckedAt,
        Long lastCheckLatencyMs,
        String lastErrorCode,
        String createdBy,
        OffsetDateTime createdAt,
        String updatedBy,
        OffsetDateTime updatedAt,
        List<ChannelCheckRecord> recentCheckRecords) {

    public ChannelDetail {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        recentCheckRecords = recentCheckRecords == null ? List.of() : List.copyOf(recentCheckRecords);
    }
}
