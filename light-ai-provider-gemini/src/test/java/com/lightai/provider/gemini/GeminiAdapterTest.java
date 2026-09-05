package com.lightai.provider.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderStreamChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** GEMINI 线协议夹具（BE-026）：请求映射、响应解析、流事件、错误分类。 */
class GeminiAdapterTest {

    private final GeminiAdapter adapter = new GeminiAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    private ProviderChatRequest request() {
        return new ProviderChatRequest("gemini-1.5-test", "你是助手",
                List.of(ProviderChatRequest.ChatTurn.user("你好")),
                512L, new java.math.BigDecimal("0.5"), null, List.of("END"), java.util.Map.of());
    }

    @Test
    void buildsGenerateContentBodyWithSystemInstruction() throws Exception {
        String body = adapter.buildRequestBody(request(), false);
        assertThat(body).contains("\"systemInstruction\"");
        assertThat(body).contains("\"role\":\"user\"");
        assertThat(body).contains("\"maxOutputTokens\":512");
        assertThat(body).contains("\"stopSequences\"");
    }

    @Test
    void parsesResponseWithUsageMetadata() throws Exception {
        String fixture = """
                {"candidates":[{"content":{"parts":[{"text":"你好！"}],"role":"model"},
                  "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":6,"totalTokenCount":16}}""";
        ProviderChatResponse response = adapter.parseResponse(mapper.readTree(fixture));
        assertThat(response.content()).isEqualTo("你好！");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.inputTokens()).isEqualTo(10L);
        assertThat(response.outputTokens()).isEqualTo(6L);
        assertThat(response.totalTokens()).isEqualTo(16L);
    }

    @Test
    void mapsSafetyFinishToContentFilter() throws Exception {
        String fixture = """
                {"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"SAFETY"}],
                 "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":0,"totalTokenCount":1}}""";
        assertThat(adapter.parseResponse(mapper.readTree(fixture)).finishReason()).isEqualTo("content_filter");
    }

    @Test
    void classifiesPerBaseline() {
        assertThat(adapter.classifyError(ProviderFailure.http(400, null, "API key not valid")).unifiedCode())
                .isEqualTo("PROVIDER_AUTH_FAILED");
        assertThat(adapter.classifyError(ProviderFailure.http(429, null, "quota")).unifiedCode())
                .isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(adapter.classifyError(ProviderFailure.http(404, null, "model not found")).unifiedCode())
                .isEqualTo("PROVIDER_MODEL_NOT_FOUND");
        assertThat(adapter.classifyError(ProviderFailure.http(400, null, "Invalid value at")).unifiedCode())
                .isEqualTo("PROVIDER_REQUEST_REJECTED");
        assertThat(adapter.classifyError(ProviderFailure.network("reset")).unifiedCode())
                .isEqualTo("NETWORK_ERROR");
    }

    @Test
    void streamEventProducesContentFinishAndUsage() throws Exception {
        String event = """
                {"candidates":[{"content":{"parts":[{"text":"早"}],"role":"model"},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}""";
        List<ProviderStreamChunk> chunks = adapter.applyStreamEvent(mapper.readTree(event), false);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).content()).isEqualTo("早");
        assertThat(chunks.get(1).type()).isEqualTo(ProviderStreamChunk.Type.FINISH);
        assertThat(chunks.get(2).totalTokens()).isEqualTo(7L);
        // FINISH 只发一次
        assertThat(adapter.applyStreamEvent(mapper.readTree(event), true)).hasSize(2);
    }
}
