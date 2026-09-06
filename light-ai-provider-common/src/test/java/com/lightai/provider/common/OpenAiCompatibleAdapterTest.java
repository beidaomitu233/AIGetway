package com.lightai.provider.common;

import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderStreamChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI 兼容线协议夹具（BE-026）：OPENAI/DEEPSEEK 共用；
 * 请求映射、响应解析（Usage/finish_reason）、SSE 事件转换与 [DONE] 终止。
 */
class OpenAiCompatibleAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class TestAdapter extends OpenAiCompatibleAdapter {
        TestAdapter(String type, String baseUrl) {
            super(type, baseUrl, new com.lightai.spi.provider.AdapterCapabilities(
                    true, true, true, true,
                    List.of("O200K"), 4,
                    java.util.Set.of("stop", "length", "content_filter"), List.of()));
        }
    }

    private ProviderChatRequest request() {
        return new ProviderChatRequest("gpt-test", "你是助手",
                List.of(ProviderChatRequest.ChatTurn.user("你好"),
                        ProviderChatRequest.ChatTurn.assistant("你好！"),
                        ProviderChatRequest.ChatTurn.user("再见")),
                256L, new java.math.BigDecimal("0.9"), null, List.of("STOP"), java.util.Map.of());
    }

    @Test
    void buildsChatCompletionsBody() throws Exception {
        TestAdapter adapter = new TestAdapter("OPENAI", "https://api.openai.com/v1/");
        JsonNode body = mapper.readTree(adapter.buildRequestBody(request(), false));
        assertThat(body.get("model").asText()).isEqualTo("gpt-test");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(256L);
        // system 作为首条消息注入
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").size()).isEqualTo(4);
        assertThat(body.get("stop").get(0).asText()).isEqualTo("STOP");
        assertThat(body.has("stream")).isFalse();
    }

    @Test
    void parsesResponseFixture() throws Exception {
        TestAdapter adapter = new TestAdapter("OPENAI", "https://api.openai.com/v1/");
        String fixture = """
                {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,
                 "model":"gpt-test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"你好！"},
                   "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""";
        ProviderChatResponse response = adapter.parseResponse(mapper.readTree(fixture));
        assertThat(response.content()).isEqualTo("你好！");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.inputTokens()).isEqualTo(10L);
        assertThat(response.outputTokens()).isEqualTo(5L);
        assertThat(response.totalTokens()).isEqualTo(15L);
        assertThat(response.providerRequestId()).isEqualTo("chatcmpl-1");
    }

    @Test
    void unmappedFinishReasonIsBadResponse() throws Exception {
        TestAdapter adapter = new TestAdapter("OPENAI", "https://api.openai.com/v1/");
        String fixture = """
                {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"c"},
                  "finish_reason":"tool_calls"}],"usage":{}}""";
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter.parseResponse(mapper.readTree(fixture)))
                .isInstanceOf(com.lightai.spi.provider.ProviderTransportException.class);
    }

    @Test
    void classifiesPerBaseline() {
        TestAdapter adapter = new TestAdapter("OPENAI", "https://api.openai.com/v1/");
        assertThat(adapter.classifyError(ProviderFailure.http(401, null, "invalid_api_key")).unifiedCode())
                .isEqualTo("PROVIDER_AUTH_FAILED");
        assertThat(adapter.classifyError(ProviderFailure.http(429, null, "rate limit")).unifiedCode())
                .isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(adapter.classifyError(ProviderFailure.http(404, null, "model_not_found")).unifiedCode())
                .isEqualTo("PROVIDER_MODEL_NOT_FOUND");
        assertThat(adapter.classifyError(ProviderFailure.http(400, null, "invalid_request")).unifiedCode())
                .isEqualTo("PROVIDER_REQUEST_REJECTED");
        ProviderErrorClassification server = adapter.classifyError(ProviderFailure.http(503, null, "overloaded"));
        assertThat(server.unifiedCode()).isEqualTo("PROVIDER_SERVER_ERROR");
        assertThat(server.retryable()).isTrue();
        assertThat(server.countsTowardCircuit()).isTrue();
        // 鉴权失败不计熔断
        assertThat(adapter.classifyError(ProviderFailure.http(401, null, "x")).countsTowardCircuit()).isFalse();
    }

    @Test
    void sseEventsConvertAndStopAtDone() throws Exception {
        TestAdapter adapter = new TestAdapter("DEEPSEEK", "https://api.deepseek.com/v1/");
        assertThat(adapter.providerType()).isEqualTo("DEEPSEEK");
        String sse = """
                data: {"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"}}]}

                data: {"id":"c1","choices":[{"index":0,"delta":{"content":"早"}}]}

                data: {"id":"c1","choices":[{"index":0,"delta":{"content":"安"}}]}

                data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":6,"completion_tokens":2,"total_tokens":8}}

                data: [DONE]

                data: {"ignored":"after-done"}
                """;
        List<String> events = SseLineParser.readAllEvents(
                new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // [DONE] 之后的事件被忽略
        assertThat(events).hasSize(6);
        List<ProviderStreamChunk> chunks = events.stream()
                .takeWhile(event -> !event.equals("[DONE]"))
                .map(event -> {
                    try {
                        return adapter.parseStreamEvent(mapper.readTree(event));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .flatMap(java.util.List::stream)
                .toList();
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).type()).isEqualTo(ProviderStreamChunk.Type.CONTENT);
        assertThat(chunks.get(0).content()).isEqualTo("早");
        assertThat(chunks.get(1).content()).isEqualTo("安");
        assertThat(chunks.get(2).type()).isEqualTo(ProviderStreamChunk.Type.USAGE);
        assertThat(chunks.get(3).type()).isEqualTo(ProviderStreamChunk.Type.FINISH);
        assertThat(chunks.get(3).finishReason()).isEqualTo("stop");
    }
}
