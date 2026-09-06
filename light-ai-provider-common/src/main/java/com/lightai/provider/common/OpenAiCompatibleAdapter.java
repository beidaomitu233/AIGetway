package com.lightai.provider.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderStreamChunk;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * OpenAI 兼容线协议（OPENAI/DEEPSEEK 共用）：/chat/completions 同步与流式。
 * 每次调用恰好一次外部请求；Provider 无法转换的终态（tool_calls 等）
 * 映射 PROVIDER_BAD_RESPONSE，不静默当成功。
 */
public class OpenAiCompatibleAdapter implements com.lightai.spi.provider.ProviderAdapter {

    protected final String providerType;
    protected final String defaultBaseUrl;
    protected final AdapterCapabilities capabilities;
    protected final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleAdapter(String providerType, String defaultBaseUrl, AdapterCapabilities capabilities) {
        this.providerType = providerType;
        this.defaultBaseUrl = defaultBaseUrl;
        this.capabilities = capabilities;
    }

    @Override
    public String providerType() {
        return providerType;
    }

    @Override
    public AdapterCapabilities capabilities() {
        return capabilities;
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
        return Math.max(1, chars / 4);
    }

    @Override
    public ProviderChatResponse chat(ProviderCallContext context) {
        String body = buildRequestBody(context.request(), false);
        String response = AdapterHttp.postJson(context.config(), chatPath(), body, context.deadlineAt(),
                authorization(context));
        return parseResponse(AdapterHttp.parseJson(mapper, response));
    }

    @Override
    public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            volatile boolean done;

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

    protected void streamOnce(ProviderCallContext context, Flow.Subscriber<? super ProviderStreamChunk> subscriber) {
        ProviderChatRequest request = context.request();
        String body = buildRequestBody(request, true);
        java.io.InputStream stream = AdapterHttp
                .postStream(context.config(), chatPath(), body, context.deadlineAt(),
                        authorization(context)).body();
        try {
            for (String event : SseLineParser.readAllEvents(stream)) {
                if (event.equals("[DONE]")) {
                    break;
                }
                parseStreamEvent(AdapterHttp.parseJson(mapper, event)).forEach(subscriber::onNext);
            }
        } catch (java.io.IOException e) {
            throw new AdapterHttp.TransportException(
                    ProviderFailure.badResponse("stream read failed: " + e.getClass().getSimpleName()), e);
        }
    }

    /** 单个 SSE data 事件 → 0..n 流块（内容、Usage、唯一 FINISH）。 */
    List<ProviderStreamChunk> parseStreamEvent(JsonNode node) {
        List<ProviderStreamChunk> chunks = new ArrayList<>();
        JsonNode usage = node.get("usage");
        if (usage != null && usage.isObject()) {
            chunks.add(ProviderStreamChunk.usage(
                    longOf(usage.get("prompt_tokens")), longOf(usage.get("completion_tokens")),
                    longOf(usage.get("total_tokens"))));
        }
        JsonNode choices = node.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta != null && delta.has("content") && delta.get("content").isTextual()
                    && !delta.get("content").asText().isEmpty()) {
                chunks.add(ProviderStreamChunk.content(delta.get("content").asText()));
            }
            JsonNode finish = choice.get("finish_reason");
            if (finish != null && finish.isTextual()) {
                chunks.add(ProviderStreamChunk.finish(mapFinishReason(finish.asText())));
            }
        }
        return chunks;
    }

    protected ProviderChatResponse parseResponse(JsonNode root) {
        JsonNode error = root.get("error");
        if (error != null) {
            throw new AdapterHttp.TransportException(ProviderFailure.badResponse("error in 2xx body"), null);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new AdapterHttp.TransportException(ProviderFailure.badResponse("missing choices"), null);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        String content = message != null && message.has("content") && message.get("content").isTextual()
                ? message.get("content").asText() : null;
        String finishReason = choice.has("finish_reason") && choice.get("finish_reason").isTextual()
                ? mapFinishReason(choice.get("finish_reason").asText()) : null;
        JsonNode usage = root.get("usage");
        Long prompt = usage == null ? null : longOf(usage.get("prompt_tokens"));
        Long completion = usage == null ? null : longOf(usage.get("completion_tokens"));
        Long total = usage == null ? null : longOf(usage.get("total_tokens"));
        String requestId = root.hasNonNull("id") ? root.get("id").asText() : null;
        return new ProviderChatResponse(content, finishReason, prompt, completion, total, "ACTUAL", requestId);
    }

    protected String mapFinishReason(String providerFinish) {
        return switch (providerFinish) {
            case "stop" -> ProviderChatResponse.FINISH_STOP;
            case "length" -> ProviderChatResponse.FINISH_LENGTH;
            case "content_filter" -> ProviderChatResponse.FINISH_CONTENT_FILTER;
            default -> throw new AdapterHttp.TransportException(
                    ProviderFailure.badResponse("unmapped finish_reason: " + providerFinish), null);
        };
    }

    @Override
    public ProviderErrorClassification classifyError(ProviderFailure failure) {
        String code = switch (failure.kind()) {
            case NETWORK -> "NETWORK_ERROR";
            case CONNECT_TIMEOUT -> "CONNECT_TIMEOUT";
            case READ_TIMEOUT, FIRST_TOKEN_TIMEOUT -> "FIRST_TOKEN_TIMEOUT";
            case BAD_RESPONSE -> "PROVIDER_BAD_RESPONSE";
            case HTTP_STATUS -> statusToCode(failure);
        };
        return ErrorClassificationBaseline.classify(failure, code, code.equals("PROVIDER_MODEL_NOT_FOUND"));
    }

    /** 404 语义细分：OpenAI/DeepSeek 明确 model_not_found 时为模型不存在。 */
    protected String statusToCode(ProviderFailure failure) {
        Integer status = failure.httpStatus();
        if (status == null) {
            return "PROVIDER_SERVER_ERROR";
        }
        if (status == 404 && failure.containsAny("model", "model_not_found")) {
            return "PROVIDER_MODEL_NOT_FOUND";
        }
        return ErrorClassificationBaseline.codeForStatus(status);
    }

    protected String buildRequestBody(ProviderChatRequest request, boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", request.modelId());
        ArrayNode messages = root.putArray("messages");
        if (request.systemMessage() != null) {
            messages.addObject().put("role", "system").put("content", request.systemMessage());
        }
        for (ProviderChatRequest.ChatTurn turn : request.messages()) {
            messages.addObject().put("role", turn.role()).put("content", turn.content());
        }
        root.put("max_tokens", request.maxTokens());
        if (request.temperature() != null) {
            root.set("temperature", mapper.valueToTree(request.temperature()));
        }
        if (request.topP() != null) {
            root.set("top_p", mapper.valueToTree(request.topP()));
        }
        if (!request.stop().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            request.stop().forEach(stop::add);
        }
        if (stream) {
            root.put("stream", true);
        }
        applyProviderOptions(root, request.providerOptions());
        return writeJson(root);
    }

    protected void applyProviderOptions(ObjectNode root, java.util.Map<String, Object> options) {
        for (java.util.Map.Entry<String, Object> option : options.entrySet()) {
            root.set(option.getKey(), mapper.valueToTree(option.getValue()));
        }
    }

    protected String writeJson(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("adapter request serialization failed", e);
        }
    }

    protected static Long longOf(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    /** 认证头构造：Bearer {secret}；secret 用后清零。 */
    protected java.util.Map<String, String> authorization(ProviderCallContext context) {
        char[] secret = context.secretHandle() == null ? new char[0] : context.secretHandle().readSecret();
        try {
            return java.util.Map.of("Authorization", "Bearer " + new String(secret));
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    protected String chatPath() {
        return "/chat/completions";
    }

    @Override
    public List<com.lightai.spi.provider.ProviderModelDescriptor> listModels(ProviderCallContext context) {
        String response = AdapterHttp.postJson(context.config(), "/models", "{}", context.deadlineAt(),
                authorization(context));
        JsonNode root = AdapterHttp.parseJson(mapper, response);
        JsonNode data = root.get("data");
        List<com.lightai.spi.provider.ProviderModelDescriptor> descriptors = new ArrayList<>();
        if (data != null && data.isArray()) {
            data.forEach(item -> descriptors.add(com.lightai.spi.provider.ProviderModelDescriptor.minimal(
                    item.hasNonNull("id") ? item.get("id").asText() : "")));
        }
        return descriptors;
    }

}
