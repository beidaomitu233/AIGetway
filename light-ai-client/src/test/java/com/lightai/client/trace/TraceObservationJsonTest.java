package com.lightai.client.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.client.json.ProtocolJson;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 观测 DTO 序列化契约：snake_case、OffsetDateTime ISO-8601、金额十进制字符串。
 */
class TraceObservationJsonTest {

    @Test
    void traceListItemSerializesSnakeCaseAndAmountAsString() throws Exception {
        TraceListItem item = new TraceListItem(
                "trace-1", OffsetDateTime.parse("2026-09-06T00:00:00Z"), "EMBEDDED",
                "standalone-key", "app-a", "proj", "tenant", "alias-x",
                "provider-b", "model-b", true, "SUCCEEDED", false,
                2, 1, 0, 1, 120L, 800L, 9000L, "MIXED", 300L,
                new BigDecimal("0.003"), "USD", null);
        String json = ProtocolJson.protocol().writeValueAsString(item);
        JsonNode node = ProtocolJson.protocol().readTree(json);

        assertThat(node.has("trace_id")).isTrue();
        assertThat(node.has("source_mode")).isTrue();
        assertThat(node.has("requested_stream")).isTrue();
        assertThat(node.has("total_tokens")).isTrue();
        assertThat(node.get("started_at").asText()).isEqualTo("2026-09-06T00:00:00Z");
        // 金额以十进制字符串传输，不丢精度、不用浮点
        assertThat(node.get("total_cost").isTextual()).isTrue();
        assertThat(node.get("total_cost").asText()).isEqualTo("0.003");

        TraceListItem decoded = ProtocolJson.protocol().readValue(json, TraceListItem.class);
        assertThat(decoded).isEqualTo(item);
    }

    @Test
    void traceTimelineItemSerializesTypeAndOrderFields() throws Exception {
        TraceTimelineItem item = new TraceTimelineItem("ATTEMPT_STARTED:abc", "ATTEMPT_STARTED",
                OffsetDateTime.parse("2026-09-06T00:00:01Z"), "abc", 1L, "abc", null);
        JsonNode node = ProtocolJson.protocol().readTree(
                ProtocolJson.protocol().writeValueAsString(item));
        assertThat(node.get("occurred_at").asText()).isEqualTo("2026-09-06T00:00:01Z");
        assertThat(node.get("source_id").asText()).isEqualTo("abc");
        assertThat(node.get("attempt_id").asText()).isEqualTo("abc");
        assertThat(node.get("reason_code").isNull()).isTrue();
    }
}
