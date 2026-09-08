package com.lightai.admin.developer;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.security.ApiTestCommand;
import com.lightai.runtime.chat.SseEncoder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.lightai.client.StreamEvent;
import com.lightai.client.StreamEventType;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.json.ProtocolJson;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 开发接入接口（BE-046/047，4.6.5）：context/code-sample 与在线测试。
 * 在线测试用管理身份（application=ADMIN_CONSOLE，invocation_source=ADMIN_TEST），
 * 不接受业务 Token 字段。
 */
@RestController
public class DeveloperAccessController {

    private final DeveloperAccessService service;

    public DeveloperAccessController(DeveloperAccessService service) {
        this.service = service;
    }

    @GetMapping("/admin/developer-access/context")
    public ResponseEntity<String> context(@RequestParam(required = false) String alias_id,
                                          HttpServletRequest request) {
        return json(ManagementResponses.ok(service.context(context(request), alias_id)));
    }

    @GetMapping("/admin/developer-access/code-sample")
    public ResponseEntity<String> codeSample(@RequestParam(required = false) String alias_id,
                                             @RequestParam(defaultValue = "curl") String language,
                                             @RequestParam(defaultValue = "false") boolean stream,
                                             HttpServletRequest request) {
        return json(ManagementResponses.ok(service.codeSample(context(request), alias_id, language, stream)));
    }

    @PostMapping("/admin/developer-access/test/chat")
    public ResponseEntity<String> testChat(@RequestBody String body, HttpServletRequest request) {
        ApiTestCommand command = CommandBodies.parse(body, ApiTestCommand.class);
        if (command.stream()) {
            throw new com.lightai.client.error.LightAiException(
                    com.lightai.client.error.ErrorCode.FIELD_VALIDATION_FAILED,
                    "流式测试请使用 /test/chat/stream", "stream");
        }
        return json(ManagementResponses.ok(service.testChat(context(request), command)));
    }

    @PostMapping("/admin/developer-access/test/chat/stream")
    public SseEmitter testChatStream(@RequestBody String body, HttpServletRequest request) {
        ApiTestCommand command = CommandBodies.parse(body, ApiTestCommand.class);
        SseEmitter emitter = new SseEmitter(0L);
        RequestContext context = context(request);
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>(command.model());
        AtomicReference<String> provider = new AtomicReference<>();
        AtomicReference<String> providerModel = new AtomicReference<>();
        AtomicLong sequence = new AtomicLong();
        try {
            service.testChatStream(context, withStream(command), streamBridge(emitter, terminal, started,
                    traceId, model, provider, providerModel, sequence));
        } catch (com.lightai.client.error.LightAiException e) {
            if (terminal.compareAndSet(false, true)) {
                try {
                    emitter.send(SseEmitter.event().data(json(Map.of("error", e.toError()))));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.complete();
            }
        }
        return emitter;
    }
    private static ApiTestCommand withStream(ApiTestCommand command) {
        return new ApiTestCommand(command.model(), command.systemMessage(), command.userMessage(), true,
                command.temperature(), command.topP(), command.maxTokens());
    }

    private static com.lightai.runtime.chat.ChatPipeline.StreamListener streamBridge(
            SseEmitter emitter, AtomicBoolean terminal, AtomicBoolean started,
            AtomicReference<String> traceIdRef, AtomicReference<String> modelRef,
            AtomicReference<String> providerRef, AtomicReference<String> providerModelRef,
            AtomicLong sequenceRef) {
        return new com.lightai.runtime.chat.ChatPipeline.StreamListener() {
            @Override public void onCommit() { }

            @Override
            public void onChunk(UnifiedChatChunk chunk) {
                if (terminal.get()) return;
                try {
                    String traceId = chunk.lightAi() == null ? null : chunk.lightAi().traceId();
                    String model = chunk.model();
                    String provider = chunk.lightAi() == null ? null : chunk.lightAi().provider();
                    String providerModel = chunk.lightAi() == null ? null : chunk.lightAi().providerModel();
                    long sequence = chunk.lightAi() == null ? 0 : chunk.lightAi().sequence();
                    traceIdRef.set(traceId);
                    modelRef.set(model);
                    providerRef.set(provider);
                    providerModelRef.set(providerModel);
                    sequenceRef.set(Math.max(sequenceRef.get(), sequence));
                    if (started.compareAndSet(false, true)) {
                        emitter.send(SseEmitter.event().data(json(StreamEvent.start(traceId, model, provider, providerModel))));
                    }
                    if (chunk.usage() != null) {
                        emitter.send(SseEmitter.event().data(json(StreamEvent.usage(traceId, sequence, model, provider,
                                providerModel, chunk.usage(), chunk.lightAi() == null ? null : chunk.lightAi().cost()))));
                    }
                    for (UnifiedChatChunk.ChunkChoice choice : chunk.choices()) {
                        String delta = choice.delta() == null ? null : choice.delta().content();
                        if (delta != null && !delta.isEmpty()) {
                            emitter.send(SseEmitter.event().data(json(StreamEvent.delta(traceId, sequence, model, provider,
                                    providerModel, delta))));
                        }
                    }
                } catch (Exception e) {
                    terminal.set(true);
                    throw new com.lightai.client.error.LightAiException(
                            com.lightai.client.error.ErrorCode.CLIENT_CANCELLED, "客户端断开");
                }
            }

            @Override
            public void onError(com.lightai.client.error.UnifiedError error) {
                if (!terminal.compareAndSet(false, true)) return;
                try {
                    emitter.send(SseEmitter.event().data(json(Map.of("error", error))));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.complete();
            }

            @Override
            public void onComplete() {
                if (!terminal.compareAndSet(false, true)) return;
                try {
                    emitter.send(SseEmitter.event().data(json(StreamEvent.done(traceIdRef.get(), sequenceRef.get() + 1,
                            modelRef.get(), providerRef.get(), providerModelRef.get(), "stop", null))));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.complete();
            }
        };
    }
    private static String json(Object value) {
        try {
            return ProtocolJson.protocol().writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("流式事件编码失败", e);
        }
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new com.lightai.client.error.LightAiException(
                    com.lightai.client.error.ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", com.lightai.admin.web.ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
