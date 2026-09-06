package com.lightai.client.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 统一时间线项（BACKEND_PLAN 协议字典；BE-032）。
 * type 取值：TRACE_CREATED、QUEUE_ENTERED、QUEUE_ACQUIRED、QUEUE_ENDED、
 * ROUTE_DECISION、ATTEMPT_STARTED、ATTEMPT_FIRST_TOKEN、ATTEMPT_ENDED、
 * RECOVERY_DECIDED、CIRCUIT_CHANGED、TRACE_ENDED。
 * occurred_at 升序；同时间按服务端固定优先级与来源 sequence 排序，前端不得重排（FE-028）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TraceTimelineItem(
        String id,
        String type,
        OffsetDateTime occurredAt,
        String sourceId,
        Long sequence,
        String attemptId,
        String reasonCode) {

    /** 同 occurred_at 时的固定类型优先级（值小者在前）。 */
    public static int orderOfType(String type) {
        return switch (type == null ? "" : type) {
            case "TRACE_CREATED" -> 0;
            case "QUEUE_ENTERED", "QUEUE_ACQUIRED", "QUEUE_ENDED" -> 1;
            case "ROUTE_DECISION" -> 2;
            case "ATTEMPT_STARTED" -> 3;
            case "ATTEMPT_FIRST_TOKEN" -> 4;
            case "ATTEMPT_ENDED" -> 5;
            case "RECOVERY_DECIDED" -> 6;
            case "CIRCUIT_CHANGED" -> 7;
            case "TRACE_ENDED" -> 8;
            default -> 9;
        };
    }
}
