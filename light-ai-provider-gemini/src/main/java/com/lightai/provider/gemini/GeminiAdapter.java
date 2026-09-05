package com.lightai.provider.gemini;

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
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * GEMINI Adapter（BE-026）：generateContent / streamGenerateContent 线协议。
 * system 为 systemInstruction；角色 user/model；finishReason 映射
 * STOP→stop、MAX_TOKENS→length、SAFETY/RECITATION→content_filter。
 */
public final class GeminiAdapter implements com.lightai.spi.provider.ProviderAdapter {

    public static final String TYPE = "GEMINI";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/";

    private static final AdapterCapabilities CAPABILITIES = new AdapterCapabilities(
            true, true, true, true,
            List.of("GEMINI"), 5,
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
        return Math.max(1, chars / 4);
    }

    @Override
    public ProviderChatResponse chat(ProviderCallContext context) {
        String body = buildRequestBody(context.request(), false);
        String response = AdapterHttp.postJson(context.config(), generatePath(context.request()), body,
                context.deadlineAt(), authHeaders(context));
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
        InputStream stream = AdapterHttp.postStream(context.config(), streamPath(context.request()), body,
                context.deadlineAt(), authHeaders(context)).body();
        try {
            boolean finishSent = false;
            for (String event : com.lightai.provider.common.SseLineParser.readAllEvents(stream)) {
                for (ProviderStreamChunk chunk : applyStreamEvent(AdapterHttp.parseJson(mapper, event), finishSent)) {
                    if (chunk.type() == ProviderStreamChunk.Type.FINISH) {
                        finishSent = true;
                    }
                    subscriber.onNext(chunk);
                }
            }
        } catch (java.io.IOException e) {
            throw AdapterHttp.transport(
                    ProviderFailure.badResponse("stream read failed: " + e.getClass().getSimpleName()));
        }
    }

    /** 单事件 → 0..n 流块（内容、唯一 FINISH、Usage）。 */
    List<ProviderStreamChunk> applyStreamEvent(JsonNode node, boolean finishAlreadySent) {
        List<ProviderStreamChunk> chunks = new ArrayList<>();
        JsonNode candidates = node.get("candidates");
        if (candidates != null && candidates.isArray() && candidates.size() > 0) {
            JsonNode candidate = candidates.get(0);
            JsonNode parts = candidate.path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.hasNonNull("text") && !part.get("text").asText().isEmpty()) {
                        chunks.add(ProviderStreamChunk.content(part.get("text").asText()));
                    }
                }
            }
            String finishReason = candidate.hasNonNull("finishReason")
                    ? candidate.get("finishReason").asText() : null;
            if (finishReason != null && !finishAlreadySent) {
                chunks.add(ProviderStreamChunk.finish(mapFinishReason(finishReason)));
            }
        }
        JsonNode usage = node.get("usageMetadata");
        if (usage != null && usage.isObject()) {
            chunks.add(ProviderStreamChunk.usage(
                    longOf(usage.get("promptTokenCount")), longOf(usage.get("candidatesTokenCount")),
                    longOf(usage.get("totalTokenCount"))));
        }
        return chunks;
    }

    ProviderChatResponse parseResponse(JsonNode root) {
        if (root.has("error")) {
            throw AdapterHttp.transport(ProviderFailure.badResponse("error in 2xx body"));
        }
        JsonNode candidates = root.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            throw AdapterHttp.transport(ProviderFailure.badResponse("missing candidates"));
        }
        JsonNode candidate = candidates.get(0);
        StringBuilder text = new StringBuilder();
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.hasNonNull("text")) {
                    text.append(part.get("text").asText());
                }
            }
        }
        String finishReason = candidate.hasNonNull("finishReason")
                ? mapFinishReason(candidate.get("finishReason").asText()) : null;
        JsonNode usage = root.get("usageMetadata");
        Long prompt = usage == null ? null : longOf(usage.get("promptTokenCount"));
        Long output = usage == null ? null : longOf(usage.get("candidatesTokenCount"));
        Long total = usage == null ? null : longOf(usage.get("totalTokenCount"));
        return new ProviderChatResponse(text.toString(), finishReason, prompt, output, total, "ACTUAL", null);
    }

    private String mapFinishReason(String finishReason) {
        return switch (finishReason) {
            case "STOP" -> ProviderChatResponse.FINISH_STOP;
            case "MAX_TOKENS" -> ProviderChatResponse.FINISH_LENGTH;
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" ->
                    ProviderChatResponse.FINISH_CONTENT_FILTER;
            default -> throw AdapterHttp.transport(
                    ProviderFailure.badResponse("unmapped finish_reason: " + finishReason));
        };
    }

    String buildRequestBody(ProviderChatRequest request, boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        if (request.systemMessage() != null) {
            ObjectNode instruction = root.putObject("systemInstruction");
            instruction.putArray("parts").addObject().put("text", request.systemMessage());
        }
        ArrayNode contents = root.putArray("contents");
        for (ProviderChatRequest.ChatTurn turn : request.messages()) {
            ObjectNode content = contents.addObject();
            content.put("role", "assistant".equals(turn.role()) ? "model" : "user");
            content.putArray("parts").addObject().put("text", turn.content());
        }
        ObjectNode generation = root.putObject("generationConfig");
        generation.put("maxOutputTokens", request.maxTokens());
        if (request.temperature() != null) {
            generation.set("temperature", mapper.valueToTree(request.temperature()));
        }
        if (request.topP() != null) {
            generation.set("topP", mapper.valueToTree(request.topP()));
        }
        if (!request.stop().isEmpty()) {
            ArrayNode stop = generation.putArray("stopSequences");
            request.stop().forEach(stop::add);
        }
        return writeJson(root);
    }

    @Override
    public ProviderErrorClassification classifyError(ProviderFailure failure) {
        String code;
        if (failure.kind() == ProviderFailure.Kind.HTTP_STATUS && failure.httpStatus() != null) {
            int status = failure.httpStatus();
            if (status == 400 && failure.containsAny("API key not valid")) {
                code = "PROVIDER_AUTH_FAILED";
            } else if (status == 404 && failure.containsAny("model")) {
                code = "PROVIDER_MODEL_NOT_FOUND";
            } else if (status == 429) {
                code = "PROVIDER_RATE_LIMITED";
            } else if (status == 400) {
                code = "PROVIDER_REQUEST_REJECTED";
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
        JsonNode models = root.get("models");
        if (models != null && models.isArray()) {
            for (JsonNode item : models) {
                String name = item.hasNonNull("name") ? item.get("name").asText() : "";
                descriptors.add(ProviderModelDescriptor.minimal(
                        name.startsWith("models/") ? name.substring("models/".length()) : name));
            }
        }
        return descriptors;
    }

    /** x-goog-api-key 头认证；secret 用后清零。 */
    private Map<String, String> authHeaders(ProviderCallContext context) {
        char[] secret = context.secretHandle() == null ? new char[0] : context.secretHandle().readSecret();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("x-goog-api-key", new String(secret));
            return headers;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private String generatePath(ProviderChatRequest request) {
        return "/models/" + request.modelId() + ":generateContent";
    }

    private String streamPath(ProviderChatRequest request) {
        return "/models/" + request.modelId() + ":streamGenerateContent";
    }

    private String writeJson(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("adapter request serialization failed", e);
        }
    }

    private static Long longOf(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }
}
