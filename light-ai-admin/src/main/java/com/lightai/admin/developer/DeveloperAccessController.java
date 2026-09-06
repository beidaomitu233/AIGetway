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
    public SseEmitter testChatStream(@RequestBody String body, HttpServletRequest request) throws java.io.IOException {
        ApiTestCommand command = CommandBodies.parse(body, ApiTestCommand.class);
        SseEmitter emitter = new SseEmitter(0L);
        RequestContext context = context(request);
        try {
            service.testChatStream(context, withStream(command), streamBridge(emitter));
            emitter.send(SseEncoder.done());
            emitter.complete();
        } catch (com.lightai.client.error.LightAiException e) {
            try {
                emitter.send(SseEncoder.error(e.toError()));
            } catch (Exception ignored) {
                // 客户端已断开
            }
            emitter.complete();
        }
        return emitter;
    }

    private static ApiTestCommand withStream(ApiTestCommand command) {
        return new ApiTestCommand(command.model(), command.systemMessage(), command.userMessage(), true,
                command.temperature(), command.topP(), command.maxTokens());
    }

    private static com.lightai.runtime.chat.ChatPipeline.StreamListener streamBridge(SseEmitter emitter) {
        return new com.lightai.runtime.chat.ChatPipeline.StreamListener() {
            @Override
            public void onCommit() {
                // SSE 头已由 emitter 提交
            }

            @Override
            public void onChunk(com.lightai.client.chat.UnifiedChatChunk chunk) {
                try {
                    emitter.send(SseEncoder.chunk(chunk));
                } catch (Exception e) {
                    throw new com.lightai.client.error.LightAiException(
                            com.lightai.client.error.ErrorCode.CLIENT_CANCELLED, "客户端断开");
                }
            }

            @Override
            public void onError(com.lightai.client.error.UnifiedError error) {
                try {
                    emitter.send(SseEncoder.error(error));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
            }
        };
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
