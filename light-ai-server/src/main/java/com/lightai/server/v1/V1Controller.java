package com.lightai.server.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import com.lightai.client.error.UnifiedErrorEnvelope;
import com.lightai.client.json.ProtocolJson;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ModelsService;
import com.lightai.runtime.chat.SseEncoder;
import com.lightai.runtime.ports.AccessTokenPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * /v1 业务入口（BE-027/028，4.7.1.1）：Bearer 鉴权、Content-Type/Encoding 校验、
 * X-Trace-Id 一致性、同步 JSON 与流式 SSE；错误统一 UnifiedErrorEnvelope。
 * 响应头 X-Light-AI-Version 恒设；有 Trace 时设 X-Trace-Id；错误禁缓存。
 */
@RestController
public class V1Controller {

    public static final String VERSION_HEADER = "X-Light-AI-Version";
    public static final String SERVER_VERSION = "0.1.0";

    private final ModelsService modelsService;
    private final ChatPipeline chatPipeline;
    private final AccessTokenPort accessTokenPort;
    private final com.lightai.server.lifecycle.ServerLifecycleService lifecycleService;

    public V1Controller(ModelsService modelsService, ChatPipeline chatPipeline, AccessTokenPort accessTokenPort) {
        this(modelsService, chatPipeline, accessTokenPort, null);
    }

    public V1Controller(ModelsService modelsService, ChatPipeline chatPipeline, AccessTokenPort accessTokenPort,
                        com.lightai.server.lifecycle.ServerLifecycleService lifecycleService) {
        this.modelsService = modelsService;
        this.chatPipeline = chatPipeline;
        this.accessTokenPort = accessTokenPort;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/v1/models")
    public ResponseEntity<String> models(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        checkAcceptingRequests();
        AccessTokenPort.Principal principal = authenticate(authorization);
        return ResponseEntity.ok()
                .header(VERSION_HEADER, SERVER_VERSION)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(json(modelsService.list(principal)));
    }

    @PostMapping(value = "/v1/chat/completions", produces = "application/json")
    public ResponseEntity<String> chat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            @RequestHeader(value = "X-Trace-Id", required = false) String headerTraceId,
            @RequestBody String body) {
        checkAcceptingRequests();
        checkProtocol(contentType, contentEncoding);
        AccessTokenPort.Principal principal = authenticate(authorization);
        UnifiedChatRequest request = parseRequest(body);
        if (headerTraceId != null && request.traceId() != null && !headerTraceId.equals(request.traceId())) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "X-Trace-Id 与请求体 trace_id 不一致", "trace_id");
        }
        if (headerTraceId != null && request.traceId() == null) {
            request = withTraceId(request, headerTraceId);
        }
        com.lightai.server.lifecycle.ServerLifecycleService.ActiveRequestHandle handle = null;
        if (lifecycleService != null) {
            handle = lifecycleService.trackRequestStart(request.traceId(), null, null);
        }
        try {
            ChatPipeline.ChatContext context = new ChatPipeline.ChatContext(principal, request, null);
            return ResponseEntity.ok()
                    .header(VERSION_HEADER, SERVER_VERSION)
                    .header("X-Trace-Id", request.traceId() != null ? request.traceId() : "")
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .body(json(chatPipeline.chat(context)));
        } finally {
            if (lifecycleService != null && handle != null) {
                lifecycleService.trackRequestEnd(handle.requestId());
            }
        }
    }

    @PostMapping(value = "/v1/chat/completions", produces = "text/event-stream")
    public SseEmitter chatStream(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
            @RequestHeader(value = "X-Trace-Id", required = false) String headerTraceId,
            @RequestBody String body) throws IOException {
        checkAcceptingRequests();
        checkProtocol(contentType, contentEncoding);
        AccessTokenPort.Principal principal = authenticate(authorization);
        UnifiedChatRequest request = parseRequest(body);
        if (!request.stream()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "stream=true 才使用 SSE 响应", "stream");
        }
        if (headerTraceId != null && request.traceId() != null && !headerTraceId.equals(request.traceId())) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "X-Trace-Id 与请求体 trace_id 不一致", "trace_id");
        }
        SseEmitter emitter = new SseEmitter(0L);
        emitter.send(SseEmitter.event().comment("light-ai stream open"));
        com.lightai.server.lifecycle.ServerLifecycleService.ActiveRequestHandle handle = null;
        if (lifecycleService != null) {
            handle = lifecycleService.trackRequestStart(request.traceId(), null, () -> {
                try {
                    emitter.completeWithError(new LightAiException(ErrorCode.SERVER_DRAINING, "Server 停机中断连接"));
                } catch (Exception ignored) {
                }
            });
        }
        try {
            ChatPipeline.ChatContext context = new ChatPipeline.ChatContext(principal, request, null);
            try {
                chatPipeline.chatStream(context, new ChatPipeline.StreamListener() {
                    @Override
                    public void onCommit() {
                        // 响应头由 SseEmitter 机制提交；此处为提交边界标记
                    }

                    @Override
                    public void onChunk(UnifiedChatChunk chunk) {
                        try {
                            emitter.send(SseEmitter.event().data(SseEncoder.chunk(chunk)));
                        } catch (IOException e) {
                            throw new LightAiException(ErrorCode.CLIENT_CANCELLED, "客户端断开");
                        }
                    }

                    @Override
                    public void onError(UnifiedError error) {
                        try {
                            emitter.send(SseEmitter.event().data(SseEncoder.error(error)));
                        } catch (IOException ignored) {
                            // 客户端已断开
                        }
                        emitter.complete();
                    }
                });
                emitter.send(SseEmitter.event().data(SseEncoder.done()));
                emitter.complete();
            } catch (LightAiException e) {
                // 提交前失败：发送错误事件并关闭（无 DONE）
                emitter.send(SseEmitter.event().data(SseEncoder.error(e.toError())));
                emitter.complete();
            }
        } finally {
            if (lifecycleService != null && handle != null) {
                lifecycleService.trackRequestEnd(handle.requestId());
            }
        }
        return emitter;
    }

    private void checkAcceptingRequests() {
        if (lifecycleService != null && !lifecycleService.isAcceptingRequests()) {
            throw new LightAiException(ErrorCode.SERVER_DRAINING, "Server 正在优雅停机摘流中，拒绝新请求");
        }
    }

    private AccessTokenPort.Principal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "缺少 Bearer 业务访问凭证");
        }
        return accessTokenPort.authenticate(authorization.substring("Bearer ".length()));
    }

    private static void checkProtocol(String contentType, String contentEncoding) {
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new LightAiException(ErrorCode.UNSUPPORTED_CONTENT_TYPE,
                    "请求 Content-Type 不是 application/json");
        }
        if (contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity")) {
            throw new LightAiException(ErrorCode.UNSUPPORTED_CONTENT_ENCODING, "V1.0 不接受压缩请求体");
        }
    }

    private static UnifiedChatRequest parseRequest(String body) {
        try {
            JsonNode node = ProtocolJson.strictCommands().readTree(body);
            if (node == null || node.isEmpty()) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求体不能为空");
            }
            return ProtocolJson.strictCommands().treeToValue(node, UnifiedChatRequest.class);
        } catch (LightAiException e) {
            throw e;
        } catch (com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException e) {
            String field = e.getPropertyName();
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "未知字段: " + field, new FieldIssue(field, "UNKNOWN_FIELD", "不接受未知可写字段") == null
                            ? List.of() : List.of(new FieldIssue(field, "UNKNOWN_FIELD", "不接受未知可写字段")));
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "请求体解析失败: " + e.getClass().getSimpleName());
        }
    }

    private static UnifiedChatRequest withTraceId(UnifiedChatRequest request, String traceId) {
        return new UnifiedChatRequest(request.model(), request.messages(), request.stream(),
                request.temperature(), request.topP(), request.maxTokens(), request.stop(),
                traceId, request.metadata(), request.providerOptions(), request.streamOptions());
    }

    private static String json(Object value) {
        try {
            return ProtocolJson.protocol().writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /** 错误响应体（供异常映射器使用）。 */
    static String errorBody(LightAiException e) {
        UnifiedErrorEnvelope envelope = UnifiedErrorEnvelope.of(e.toError());
        return json(Map.of("error", envelope.error()));
    }
}
