package com.lightai.client.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.UnifiedError;
import com.lightai.client.error.UnifiedErrorEnvelope;
import com.lightai.client.paging.PageResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolJsonTest {

    private final ObjectMapper mapper = ProtocolJson.protocol();
    private final ObjectMapper strict = ProtocolJson.strictCommands();

    static class Sample {
        public OffsetDateTime queryStartedAt;
        public BigDecimal amount;
        public long bigCount;
    }

    @Test
    void serializesSnakeCaseAndIsoOffsetTime() throws Exception {
        Sample sample = new Sample();
        sample.queryStartedAt = OffsetDateTime.of(2026, 9, 5, 12, 0, 30, 0, ZoneOffset.UTC);
        String json = mapper.writeValueAsString(sample);
        assertThat(json).contains("\"query_started_at\":\"2026-09-05T12:00:30Z\"");
    }

    @Test
    void moneyRoundTripsAsPlainDecimalString() throws Exception {
        Sample sample = new Sample();
        sample.amount = new BigDecimal("0.00012345");
        String json = mapper.writeValueAsString(sample);
        assertThat(json).contains("\"amount\":\"0.00012345\"");

        Sample parsed = mapper.readValue(json, Sample.class);
        assertThat(parsed.amount).isEqualByComparingTo("0.00012345");
        assertThat(parsed.amount.scale()).isEqualTo(8);
    }

    @Test
    void bigintBeyondJsSafeIntegerStaysStringExact() throws Exception {
        Sample sample = new Sample();
        sample.bigCount = 9007199254740993L;
        String json = mapper.writeValueAsString(sample);
        assertThat(json).contains("\"big_count\":9007199254740993");

        // 金额型字段以字符串承载超大数值时不丢精度
        String payload = "{\"amount\":\"90071992547409931234567890\"}";
        Sample parsed = mapper.readValue(payload, Sample.class);
        assertThat(parsed.amount.toPlainString()).isEqualTo("90071992547409931234567890");
    }

    @Test
    void unifiedErrorEnvelopeUsesSnakeCaseAndOmitsAbsentFields() throws Exception {
        UnifiedError error = UnifiedError.builder(ErrorCode.CONFIG_VERSION_CONFLICT, "编辑对象版本已变化")
                .currentVersion(7L)
                .errors(List.of(new FieldIssue("name", "REQUIRED", "名称必填")))
                .build();
        String json = mapper.writeValueAsString(UnifiedErrorEnvelope.of(error));
        assertThat(json).contains("\"code\":\"CONFIG_VERSION_CONFLICT\"");
        assertThat(json).contains("\"type\":\"conflict_error\"");
        assertThat(json).contains("\"retryable\":false");
        assertThat(json).contains("\"current_version\":7");
        assertThat(json).contains("\"errors\":[{\"field\":\"name\",\"code\":\"REQUIRED\",\"message\":\"名称必填\"}]");
        assertThat(json).doesNotContain("trace_id").doesNotContain("retry_after_ms").doesNotContain("param");
    }

    @Test
    void pageResultSerializesContractFields() throws Exception {
        OffsetDateTime now = OffsetDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneOffset.UTC);
        PageResult<String> page = PageResult.of(List.of("a"), 1, 1, 20, "updated_at:desc", now, now);
        String json = mapper.writeValueAsString(page);
        assertThat(json).contains("\"items\"");
        assertThat(json).contains("\"page_size\":20");
        assertThat(json).contains("\"query_started_at\"");
        assertThat(json).contains("\"data_updated_at\"");
    }

    @Test
    void unknownFieldIsSilentlyIgnoredByLenientParser() throws Exception {
        Sample parsed = mapper.readValue("{\"amount\":\"1.5\",\"unknown_key\":1}", Sample.class);
        assertThat(parsed.amount).isEqualByComparingTo("1.5");
    }

    @Test
    void strictCommandParserRejectsUnknownField() {
        assertThatThrownBy(() -> strict.readValue("{\"unknown_key\":1}", Sample.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    void explicitNullForPrimitiveFailsInStrictMode() {
        // 缺失键由命令级必填校验处理；显式 null 覆盖原始类型在解析层直接拒绝
        assertThatThrownBy(() -> strict.readValue("{\"big_count\":null}", Sample.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    void numericJsonTokenAcceptedForMoneyButNeverLosesPrecision() throws Exception {
        Sample parsed = mapper.readValue("{\"amount\":0.00012345}", Sample.class);
        assertThat(parsed.amount.toPlainString()).isEqualTo("0.00012345");
    }
}
