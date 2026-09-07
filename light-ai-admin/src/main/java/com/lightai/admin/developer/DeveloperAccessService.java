package com.lightai.admin.developer;

import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.security.ApiTestCommand;
import com.lightai.client.security.ApiTestResult;
import com.lightai.client.security.CodeSampleResult;
import com.lightai.client.security.DeveloperAccessContext;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发接入服务（BE-046/047）：
 * context/code-sample 只从公开配置和授权已发布 Alias 生成，秘密为占位符；
 * 在线测试复用运行管线，管理身份 application=ADMIN_CONSOLE（invocation_source=ADMIN_TEST），
 * 不使用业务 Token 冒充测试。
 */
public class DeveloperAccessService {

    private final ConfigSnapshotPort snapshotPort;
    private final AccessTokenPort.RuntimeConfigPort runtimeConfigPort;
    private final ChatPipeline chatPipeline;
    private final String runtimeMode;
    private final String publicBaseUrl;
    private final Clock clock;

    public DeveloperAccessService(ConfigSnapshotPort snapshotPort,
                                  AccessTokenPort.RuntimeConfigPort runtimeConfigPort,
                                  ChatPipeline chatPipeline, String runtimeMode, String publicBaseUrl,
                                  Clock clock) {
        this.snapshotPort = snapshotPort;
        this.runtimeConfigPort = runtimeConfigPort;
        this.chatPipeline = chatPipeline;
        this.runtimeMode = runtimeMode;
        this.publicBaseUrl = publicBaseUrl;
        this.clock = clock;
    }

    /** 接入上下文：只输出已发布且授权的 Alias；开发仅看授权 Alias。 */
    public DeveloperAccessContext context(RequestContext context, String aliasIdOrNull) {
        RequestPermissions.require(context, Permissions.DEVELOPER_VIEW);
        List<String> allowedScope = context.authContext().applicationScope();
        boolean hasScope = allowedScope != null && !allowedScope.isEmpty() && !allowedScope.contains("*");
        ConfigSnapshotPort.ActiveSnapshot snapshot = snapshotPort.active();
        List<DeveloperAccessContext.AliasOption> options = new ArrayList<>();
        for (ConfigSnapshotPort.AliasView alias : snapshot.aliases()) {
            if (!alias.enabled() || alias.enabledCandidates().isEmpty()) {
                continue;
            }
            if (hasScope && !allowedScope.contains(alias.aliasId()) && !allowedScope.contains(alias.alias())) {
                continue;
            }
            if (aliasIdOrNull != null && !aliasIdOrNull.isBlank()
                    && !alias.aliasId().equals(aliasIdOrNull) && !alias.alias().equals(aliasIdOrNull)) {
                continue;
            }
            options.add(new DeveloperAccessContext.AliasOption(alias.aliasId(), alias.alias(),
                    alias.displayName(), alias.supportsStream(), alias.supportsSystem(),
                    alias.contextWindow() != null ? alias.contextWindow().intValue() : 128000,
                    alias.maxOutputTokens() != null ? alias.maxOutputTokens().intValue() : 16384));
        }
        String defaultAlias = runtimeConfigPort.defaultAliasId()
                .map(aliasId -> findAliasName(snapshot, aliasId))
                .orElse(null);
        return new DeveloperAccessContext(runtimeMode, publicBaseUrl, options, defaultAlias,
                null, snapshot.snapshotNo());
    }

    /** 代码示例：三模式模板，秘密一律 lai_YOUR_TOKEN / YOUR_API_KEY 占位。 */
    public CodeSampleResult codeSample(RequestContext context, String alias, String language, boolean stream) {
        RequestPermissions.require(context, Permissions.DEVELOPER_VIEW);
        List<String> allowedScope = context.authContext().applicationScope();
        boolean hasScope = allowedScope != null && !allowedScope.isEmpty() && !allowedScope.contains("*");
        ConfigSnapshotPort.ActiveSnapshot snapshot = snapshotPort.active();
        ConfigSnapshotPort.AliasView view = snapshot.alias(alias)
                .orElseThrow(() -> new LightAiException(ErrorCode.MODEL_ALIAS_NOT_FOUND,
                        "Alias 不存在或未发布: " + alias));
        if (!view.enabled() || (hasScope && !allowedScope.contains(view.aliasId()) && !allowedScope.contains(view.alias()))) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "Alias 未授权: " + alias);
        }
        String languageKey = language == null ? "curl" : language.trim().toLowerCase();
        String sample = buildSample(languageKey, alias, stream);
        return new CodeSampleResult(languageKey, runtimeMode, alias, stream, sample);
    }

    /** 在线测试：管理身份构造 ChatContext；来源可识别（invocation_source=ADMIN_TEST）。 */
    public ApiTestResult testChat(RequestContext requestContext, ApiTestCommand command) {
        RequestPermissions.require(requestContext, Permissions.DEVELOPER_TEST);
        long started = clock.millis();
        UnifiedRequestPair pair = toUnifiedRequest(command);
        AccessTokenPort.Principal principal = principalForTest(requestContext, command.model());
        ChatPipeline.ChatContext pipelineContext =
                new ChatPipeline.ChatContext(principal, pair.request(), null);
        UnifiedChatResponse response = chatPipeline.chat(pipelineContext);
        return new ApiTestResult(response, response.id(), clock.millis() - started);
    }

    /** 流式在线测试：经管线流式路径输出。 */
    public void testChatStream(RequestContext requestContext, ApiTestCommand command,
                               ChatPipeline.StreamListener listener) {
        RequestPermissions.require(requestContext, Permissions.DEVELOPER_TEST);
        UnifiedRequestPair pair = toUnifiedRequest(command);
        AccessTokenPort.Principal principal = principalForTest(requestContext, command.model());
        ChatPipeline.ChatContext pipelineContext =
                new ChatPipeline.ChatContext(principal, pair.request(), null);
        chatPipeline.chatStream(pipelineContext, listener);
    }

    private AccessTokenPort.Principal principalForTest(RequestContext context, String model) {
        ConfigSnapshotPort.AliasView alias = snapshotPort.active().alias(model)
                .orElseThrow(() -> ConfigSnapshotPort.aliasNotFound(model));
        List<String> allowedScope = context.authContext().applicationScope();
        boolean hasScope = allowedScope != null && !allowedScope.isEmpty() && !allowedScope.contains("*");
        if (hasScope && !allowedScope.contains(alias.aliasId()) && !allowedScope.contains(alias.alias())) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "当前管理身份未授权该 Alias");
        }
        String app = context.authContext().userId() != null && !context.authContext().userId().isBlank()
                ? context.authContext().userId()
                : "ADMIN_CONSOLE";
        return new AccessTokenPort.Principal(app, List.of(alias.aliasId(), alias.alias()));
    }

    private UnifiedRequestPair toUnifiedRequest(ApiTestCommand command) {
        List<ChatMessage> messages = new ArrayList<>();
        if (command.systemMessage() != null && !command.systemMessage().isBlank()) {
            messages.add(new ChatMessage("system", command.systemMessage()));
        }
        messages.add(new ChatMessage("user", command.userMessage()));
        UnifiedChatRequest request = new UnifiedChatRequest(command.model(), messages, command.stream(),
                command.temperature(), command.topP(), command.maxTokens(), null, null, null, null, null);
        return new UnifiedRequestPair(request);
    }

    private record UnifiedRequestPair(UnifiedChatRequest request) {
    }

    private String findAliasName(ConfigSnapshotPort.ActiveSnapshot snapshot, String aliasId) {
        return snapshot.aliases().stream()
                .filter(alias -> alias.aliasId().equals(aliasId))
                .map(ConfigSnapshotPort.AliasView::alias)
                .findFirst().orElse(null);
    }

    private String buildSample(String language, String alias, boolean stream) {
        String url = publicBaseUrl + "/v1/chat/completions";
        return switch (language) {
            case "curl" -> """
                    curl -X POST %s \\
                      -H "Authorization: Bearer lai_YOUR_TOKEN" \\
                      -H "Content-Type: application/json" \\
                      -d '{"model":"%s","messages":[{"role":"user","content":"你好"}],"stream":%s}'
                    """.formatted(url, alias, stream);
            case "java" -> """
                    LightAiClient client = LightAiClient.builder()
                        .baseUrl("%s")
                        .tokenSupplier(() -> "lai_YOUR_TOKEN")
                        .build();
                    client.chat(UnifiedChatRequest.builder()
                        .model("%s")
                        .addUserMessage("你好")
                        .stream(%s)
                        .build());
                    """.formatted(publicBaseUrl, alias, stream);
            case "python" -> """
                    import requests
                    resp = requests.post("%s",
                        headers={"Authorization": "Bearer lai_YOUR_TOKEN"},
                        json={"model": "%s", "messages": [{"role": "user", "content": "你好"}], "stream": %s})
                    print(resp.json())
                    """.formatted(url, alias, stream);
            default -> throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "language 支持 curl/java/python", "language");
        };
    }
}
