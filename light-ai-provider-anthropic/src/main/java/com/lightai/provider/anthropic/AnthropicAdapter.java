package com.lightai.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lightai.provider.common.AdapterHttp;
import com.lightai.provider.common.ErrorClassificationBaseline;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderModelDescriptor;
import com.lightai.spi.provider.ProviderStreamChunk;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * ANTHROPIC Adapter（BE-026）：Messages API 线协议。
 * system 为顶层字段；max_tokens 必填；stop_reason 映射 end_turn/stop_sequence→stop、
 * max_tokens→length； refusal 终态按 PROVIDER_BAD_RESPONSE 失败，不静默当成功。
 */
public final class AnthropicAdapter implements com.lightai.spi.provider.ProviderAdapter {

    public static final String TYPE = "ANTHROPIC";
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/";
    public static final String API_VERSION = "2023-06-01";

    private static final AdapterCapabilities CAPABILITIES = new AdapterCapabilities(
            true, true, true, true,
            List.of("ANTHROPIC"), 4,
            Set.of(ProviderChatResponse.FINISH_STOP, ProviderChatResponse.FINISH_LENGTH,
                    ProviderChatResponse.FINISH_CONTENT_FILTER),
            List.of());

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String providerType() {
        return TYPE;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public long estimateTokens(ProviderChatRequest request) {
        long chars = 0;
        if (request.systemMessage() != null) {
            chars += request.systemMessage().length();
        }
        for (ProviderChatRequest.ChatTurn turn : request.messages()) {
            chars += turn.content() == null ? 0 : turn.content().length();
        }
        return Math.max(1, chars / 3);
    }

    @Override
    public ProviderChatResponse chat(ProviderCallContext context) {
        String body = buildRequestBody(context.request(), false);
        String response = AdapterHttp.postJson(context.config(), "/messages", body, context.deadlineAt(),
                authHeaders(context));
        return parseResponse(AdapterHttp.parseJson(mapper, response));
    }

    @Override
    public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public void request(long n) {
                if (done || n <= 0) {
                    return;
                }
                done = true;
                try {
                    streamOnce(context, subscriber);
                } catch (AdapterHttp.TransportException e) {
                    subscriber.onError(e);
                } catch (Exception e) {
                    subscriber.onError(new AdapterHttp.TransportException(
                            ProviderFailure.badResponse(e.getClass().getSimpleName()), e));
                }
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }

    private void streamOnce(ProviderCallContext context, Flow.Subscriber<? super ProviderStreamChunk> subscriber) {
        String body = buildRequestBody(context.request(), true);
        InputStream stream = AdapterHttp.postStream(context.config(), "/messages", body, context.deadlineAt(),
                authHeaders(context)).body();
        try {
            StreamEventMapper eventMapper = new StreamEventMapper();
            for (String event : SseEvents.readAll(stream)) {
                eventMapper.apply(AdapterHttp.parseJson(mapper, event))
                        .forEach(subscriber::onNext);
            }
        } catch (java.io.IOException e) {
            throw AdapterHttp.transport(ProviderFailure.badResponse("stream read failed: " + e.getClass().getSimpleName()));
        }
    }

    /** 单事件 → 0..n 流块；有状态保存 message_start 的输入 Token。 */
    StreamEventMapper streamEventMapper() {
        return new StreamEventMapper();
    }

    static final class StreamEventMapper {
        private Long inputTokens;

        java.util.List<ProviderStreamChunk> apply(JsonNode node) {
            String type = node.hasNonNull("type") ? node.get("type").asText() : "";
            switch (type) {
                case "message_start" -> {
                    JsonNode usage = node.path("message").path("usage");
                    inputTokens = usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asLong() : null;
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.path("delta");
                    if ("text_delta".equals(delta.path("type").asText())
                            && delta.hasNonNull("text") && !delta.get("text").asText().isEmpty()) {
                        return List.of(ProviderStreamChunk.content(delta.get("text").asText()));
                    }
                }
                case "message_delta" -> {
                    JsonNode delta = node.path("delta");
                    String stopReason = delta.hasNonNull("stop_reason") ? delta.get("stop_reason").asText() : null;
                    JsonNode usage = node.path("usage");
                    Long output = usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asLong() : null;
                    // 输入/输出 Token 作为 USAGE 事件先行，随后唯一 FINISH
                    List<ProviderStreamChunk> chunks = new java.util.ArrayList<>();
                    chunks.add(ProviderStreamChunk.usage(inputTokens, output, null));
                    if (stopReason != null) {
                        chunks.add(ProviderStreamChunk.finish(mapFinishReasonStatic(stopReason)));
                    }
                    return chunks;
                }
                case "error" -> throw AdapterHttp.transport(ProviderFailure.badResponse("error event in stream"));
                default -> {
                    // message_stop / ping / content_block_start / content_block_stop 不产生内容事件
                }
            }
            return List.of();
        }
    }

    ProviderChatResponse parseResponse(JsonNode root) {
        if (root.hasNonNull("type") && "error".equals(root.get("type").asText())) {
            throw AdapterHttp.transport(ProviderFailure.badResponse("error in 2xx body"));
        }
        JsonNode content = root.get("content");
        StringBuilder text = new StringBuilder();
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                    text.append(block.get("text").asText());
                } else {
                    // 工具/多模态块在 V1.0 不可转换
                    throw AdapterHttp.transport(ProviderFailure.badResponse("unsupported content block"));
                }
            }
        }
        String stopReason = root.hasNonNull("stop_reason") ? mapFinishReason(root.get("stop_reason").asText()) : null;
        JsonNode usage = root.get("usage");
        Long input = usage == null ? null : usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asLong() : null;
        Long output = usage == null ? null : usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asLong() : null;
        return new ProviderChatResponse(text.toString(), stopReason, input, output,
                input != null && output != null ? input + output : null, "ACTUAL",
                root.hasNonNull("id") ? root.get("id").asText() : null);
    }

    private String mapFinishReason(String stopReason) {
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> ProviderChatResponse.FINISH_STOP;
            case "max_tokens" -> ProviderChatResponse.FINISH_LENGTH;
            case "refusal" -> throw AdapterHttp.transport(
                    ProviderFailure.badResponse("unmapped finish_reason: refusal"));
            default -> throw AdapterHttp.transport(
                    ProviderFailure.badResponse("unmapped finish_reason: " + stopReason));
        };
    }

    String buildRequestBody(ProviderChatRequest request, boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.modelId());
        root.put("max_tokens", request.maxTokens());
        if (request.systemMessage() != null) {
            root.put("system", request.systemMessage());
        }
        ArrayNode messages = root.putArray("messages");
        for (ProviderChatRequest.ChatTurn turn : request.messages()) {
            messages.addObject().put("role", turn.role()).put("content", turn.content());
        }
        if (request.temperature() != null) {
            root.set("temperature", mapper.valueToTree(request.temperature()));
        }
        if (request.topP() != null) {
            root.set("top_p", mapper.valueToTree(request.topP()));
        }
        if (!request.stop().isEmpty()) {
            ArrayNode stop = root.putArray("stop_sequences");
            request.stop().forEach(stop::add);
        }
        if (stream) {
            root.put("stream", true);
        }
        return writeJson(root);
    }

    @Override
    public ProviderErrorClassification classifyError(ProviderFailure failure) {
        String code;
        if (failure.kind() == ProviderFailure.Kind.HTTP_STATUS && failure.httpStatus() != null) {
            int status = failure.httpStatus();
            if (status == 400 && failure.containsAny("max_tokens", "stop_sequence", "temperature", "top_p")) {
                code = "PROVIDER_REQUEST_REJECTED";
            } else if (status == 404 && failure.containsAny("model")) {
                code = "PROVIDER_MODEL_NOT_FOUND";
            } else {
                code = ErrorClassificationBaseline.codeForStatus(status);
            }
        } else {
            code = switch (failure.kind()) {
                case NETWORK -> "NETWORK_ERROR";
                case CONNECT_TIMEOUT -> "CONNECT_TIMEOUT";
                case READ_TIMEOUT, FIRST_TOKEN_TIMEOUT -> "FIRST_TOKEN_TIMEOUT";
                case BAD_RESPONSE -> "PROVIDER_BAD_RESPONSE";
                default -> "PROVIDER_SERVER_ERROR";
            };
        }
        return ErrorClassificationBaseline.classify(failure, code);
    }

    @Override
    public List<ProviderModelDescriptor> listModels(ProviderCallContext context) {
        String response = AdapterHttp.postJson(context.config(), "/models", "{}", context.deadlineAt(),
                authHeaders(context));
        JsonNode root = AdapterHttp.parseJson(mapper, response);
        List<ProviderModelDescriptor> descriptors = new ArrayList<>();
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            data.forEach(item -> descriptors.add(
                    ProviderModelDescriptor.minimal(item.hasNonNull("id") ? item.get("id").asText() : "")));
        }
        return descriptors;
    }

    /** 静态 finish_reason 映射（供事件映射器复用）。 */
    static String mapFinishReasonStatic(String stopReason) {
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> ProviderChatResponse.FINISH_STOP;
            case "max_tokens" -> ProviderChatResponse.FINISH_LENGTH;
            default -> throw AdapterHttp.transport(
                    ProviderFailure.badResponse("unmapped finish_reason: " + stopReason));
        };
    }

    /** x-api-key + anthropic-version；secret 用后清零。 */
    private java.util.Map<String, String> authHeaders(ProviderCallContext context) {
        char[] secret = context.secretHandle() == null ? new char[0] : context.secretHandle().readSecret();
        try {
            return java.util.Map.of("x-api-key", new String(secret), "anthropic-version", API_VERSION);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private String writeJson(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("adapter request serialization failed", e);
        }
    }

    /** 多头认证的传输辅助标记：由 AdapterHttp 支持多行头拆分。 */
    static final class SseEvents {
        private SseEvents() {
        }

        static List<String> readAll(InputStream stream) throws java.io.IOException {
            return com.lightai.provider.common.SseLineParser.readAllEvents(stream);
        }
    }
}
