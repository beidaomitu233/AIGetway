package com.lightai.provider.anthropic;

import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderStreamChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ANTHROPIC 线协议夹具（BE-026）：请求映射、响应解析、SSE 事件、错误分类基线。 */
class AnthropicAdapterTest {

    private final AnthropicAdapter adapter = new AnthropicAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    private ProviderChatRequest request() {
        return new ProviderChatRequest("claude-3-test", "你是助手",
                List.of(ProviderChatRequest.ChatTurn.user("你好")),
                512L, new java.math.BigDecimal("0.7"), null, List.of("STOP"), java.util.Map.of());
    }

    private ProviderCallContext context() {
        return new ProviderCallContext(
                new com.lightai.spi.provider.ProviderConfigView(AnthropicAdapter.TYPE,
                        AnthropicAdapter.DEFAULT_BASE_URL, null, 3000, 120000, java.util.Map.of()),
                request(), () -> "sk-ant-test".toCharArray(),
                java.time.Instant.now().plusSeconds(30));
    }

    @Test
    void buildsMessagesApiBodyWithTopLevelSystem() throws Exception {
        JsonNode body = mapper.readTree(adapter.buildRequestBody(request(), false));
        assertThat(body.get("model").asText()).isEqualTo("claude-3-test");
        assertThat(body.get("max_tokens").asLong()).isEqualTo(512L);
        assertThat(body.get("system").asText()).isEqualTo("你是助手");
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").toString()).doesNotContain("你是助手");
        assertThat(body.has("temperature")).isTrue();
        assertThat(body.get("stop_sequences").get(0).asText()).isEqualTo("STOP");
        assertThat(body.toString()).doesNotContain("sk-ant-test");
    }

    @Test
    void parsesResponseWithUsageAndStopReason() throws Exception {
        String fixture = """
                {"id":"msg_1","type":"message","role":"assistant",
                 "content":[{"type":"text","text":"你好！"}],
                 "stop_reason":"end_turn",
                 "usage":{"input_tokens":12,"output_tokens":8}}""";
        ProviderChatResponse response = adapter.parseResponse(mapper.readTree(fixture));
        assertThat(response.content()).isEqualTo("你好！");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.inputTokens()).isEqualTo(12L);
        assertThat(response.outputTokens()).isEqualTo(8L);
        assertThat(response.totalTokens()).isEqualTo(20L);
        assertThat(response.providerRequestId()).isEqualTo("msg_1");
    }

    @Test
    void mapsToolUseBlocksToBadResponse() throws Exception {
        String fixture = """
                {"id":"msg_2","content":[{"type":"tool_use","id":"t1","name":"f","input":{}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""";
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter.parseResponse(mapper.readTree(fixture)))
                .isInstanceOf(com.lightai.spi.provider.ProviderTransportException.class);
    }

    @Test
    void classifiesPerBaseline() {
        assertThat(adapter.classifyError(ProviderFailure.http(401, null, "invalid x-api-key")).unifiedCode())
                .isEqualTo("PROVIDER_AUTH_FAILED");
        assertThat(adapter.classifyError(ProviderFailure.http(429, null, "rate limited")).unifiedCode())
                .isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(adapter.classifyError(ProviderFailure.http(404, null, "model not found: claude-x")).unifiedCode())
                .isEqualTo("PROVIDER_MODEL_NOT_FOUND");
        assertThat(adapter.classifyError(ProviderFailure.http(400, null, "max_tokens: too large")).unifiedCode())
                .isEqualTo("PROVIDER_REQUEST_REJECTED");
        ProviderErrorClassification server = adapter.classifyError(ProviderFailure.http(500, null, "oops"));
        assertThat(server.unifiedCode()).isEqualTo("PROVIDER_SERVER_ERROR");
        assertThat(server.retryable()).isTrue();
        assertThat(server.countsTowardCircuit()).isTrue();
        assertThat(adapter.classifyError(ProviderFailure.connectTimeout("t")).unifiedCode())
                .isEqualTo("CONNECT_TIMEOUT");
    }

    @Test
    void streamEventsConvertToContentUsageFinish() throws Exception {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":9}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"早"}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"安"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        AnthropicAdapter.StreamEventMapper eventMapper = adapter.streamEventMapper();
        List<ProviderStreamChunk> chunks = com.lightai.provider.common.SseLineParser.readAllEvents(
                new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .stream()
                .map(event -> {
                    try {
                        return eventMapper.apply(mapper.readTree(event));
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .flatMap(java.util.List::stream)
                .toList();
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).type()).isEqualTo(ProviderStreamChunk.Type.CONTENT);
        assertThat(chunks.get(0).content()).isEqualTo("早");
        assertThat(chunks.get(1).content()).isEqualTo("安");
        assertThat(chunks.get(2).type()).isEqualTo(ProviderStreamChunk.Type.USAGE);
        assertThat(chunks.get(2).inputTokens()).isEqualTo(9L);
        assertThat(chunks.get(3).type()).isEqualTo(ProviderStreamChunk.Type.FINISH);
        assertThat(chunks.get(3).finishReason()).isEqualTo("stop");
    }
}
