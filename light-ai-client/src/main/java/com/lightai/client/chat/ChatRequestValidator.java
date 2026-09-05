package com.lightai.client.chat;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一请求结构校验（BACKEND_PLAN 2.2）：roles/content、stop、trace_id、
 * metadata 边界与 stream_options 组合。能力与上下文校验由运行内核执行，
 * RuntimeConfig 的消息长度限制在调用侧叠加。
 */
public final class ChatRequestValidator {

    private static final int MAX_STOP_ITEMS = 4;
    private static final int MAX_STOP_LENGTH = 128;
    private static final int MAX_TRACE_ID = 128;
    private static final int MAX_PROJECT_TENANT = 64;
    private static final int MAX_USER = 128;
    private static final int MAX_TAG_ENTRIES = 20;
    private static final int MAX_TAG_KEY = 64;
    private static final int MAX_TAG_VALUE = 256;
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-]+$");

    private static final Set<String> SENSITIVE_TAG_KEYS = Set.of(
            "authorization", "token", "secret", "password", "api_key", "apikey", "credential");

    private ChatRequestValidator() {
    }

    public static void validate(UnifiedChatRequest request, boolean businessApi) {
        List<FieldIssue> issues = new ArrayList<>();
        validateMessages(request, issues);
        validateStop(request, issues);
        validateTraceId(request, issues);
        validateMetadata(request, issues);
        validateStreamOptions(request, businessApi, issues);
        validateSampling(request, issues);
        rejectIssues(issues);
    }

    private static void validateMessages(UnifiedChatRequest request, List<FieldIssue> issues) {
        List<ChatMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            issues.add(new FieldIssue("messages", "REQUIRED", "messages 不能为空"));
            return;
        }
        int systemCount = 0;
        int userCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            String field = "messages[" + i + "]";
            if (message == null || message.role() == null || message.content() == null
                    || message.content().isEmpty()) {
                issues.add(new FieldIssue(field, "INVALID", "消息必须包含 role 与非空 content"));
                continue;
            }
            switch (message.role()) {
                case ChatMessage.ROLE_SYSTEM -> {
                    systemCount++;
                    if (i != 0) {
                        issues.add(new FieldIssue(field, "INVALID", "system 消息只能是首项"));
                    }
                }
                case ChatMessage.ROLE_USER -> userCount++;
                case ChatMessage.ROLE_ASSISTANT -> {
                }
                default -> issues.add(new FieldIssue(field + ".role", "INVALID",
                        "role 仅允许 system/user/assistant"));
            }
        }
        if (systemCount > 1) {
            issues.add(new FieldIssue("messages", "INVALID", "system 消息最多一条"));
        }
        if (userCount < 1) {
            issues.add(new FieldIssue("messages", "INVALID", "至少需要一条 user 消息"));
        }
    }

    private static void validateStop(UnifiedChatRequest request, List<FieldIssue> issues) {
        List<String> stop = request.stop();
        if (stop == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < stop.size(); i++) {
            String item = stop.get(i);
            String field = "stop[" + i + "]";
            if (item == null || item.isEmpty()) {
                issues.add(new FieldIssue(field, "INVALID", "stop 项不能为空"));
            } else if (item.length() > MAX_STOP_LENGTH) {
                issues.add(new FieldIssue(field, "TOO_LONG", "stop 项最长 " + MAX_STOP_LENGTH + " 字符"));
            } else if (!seen.add(item)) {
                issues.add(new FieldIssue(field, "DUPLICATED", "stop 项不能重复"));
            }
        }
        if (stop.size() > MAX_STOP_ITEMS) {
            issues.add(new FieldIssue("stop", "TOO_MANY", "stop 最多 " + MAX_STOP_ITEMS + " 项"));
        }
    }

    private static void validateTraceId(UnifiedChatRequest request, List<FieldIssue> issues) {
        String traceId = request.traceId();
        if (traceId == null) {
            return;
        }
        if (traceId.isEmpty() || traceId.length() > MAX_TRACE_ID || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            issues.add(new FieldIssue("trace_id", "INVALID",
                    "trace_id 为 1-" + MAX_TRACE_ID + " 字符，仅允许字母数字点短横线下划线"));
        }
    }

    private static void validateMetadata(UnifiedChatRequest request, List<FieldIssue> issues) {
        var metadata = request.metadata();
        if (metadata == null) {
            return;
        }
        checkLength(issues, "metadata.project", metadata.project(), MAX_PROJECT_TENANT);
        checkLength(issues, "metadata.tenant", metadata.tenant(), MAX_PROJECT_TENANT);
        checkLength(issues, "metadata.user", metadata.user(), MAX_USER);
        Map<String, String> tags = metadata.tags();
        if (tags == null) {
            return;
        }
        if (tags.size() > MAX_TAG_ENTRIES) {
            issues.add(new FieldIssue("metadata.tags", "TOO_MANY", "tags 最多 " + MAX_TAG_ENTRIES + " 项"));
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String keyField = "metadata.tags[" + entry.getKey() + "]";
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getKey().length() > MAX_TAG_KEY) {
                issues.add(new FieldIssue(keyField, "INVALID", "tag key 为 1-" + MAX_TAG_KEY + " 字符"));
            }
            if (SENSITIVE_TAG_KEYS.contains(entry.getKey() == null ? "" : entry.getKey().toLowerCase())) {
                issues.add(new FieldIssue(keyField, "SENSITIVE", "tag key 不允许携带敏感语义"));
            }
            if (entry.getValue() != null && entry.getValue().length() > MAX_TAG_VALUE) {
                issues.add(new FieldIssue(keyField, "TOO_LONG", "tag value 最长 " + MAX_TAG_VALUE + " 字符"));
            }
        }
    }

    private static void validateStreamOptions(UnifiedChatRequest request, boolean businessApi,
                                              List<FieldIssue> issues) {
        if (request.streamOptions() == null) {
            return;
        }
        if (!request.stream()) {
            issues.add(new FieldIssue("stream_options", "INVALID",
                    "stream_options 仅在 stream=true 时允许提供"));
        }
        if (!businessApi) {
            issues.add(new FieldIssue("stream_options", "INVALID",
                    "管理测试请求不接收 stream_options"));
        }
    }

    private static void validateSampling(UnifiedChatRequest request, List<FieldIssue> issues) {
        checkRange(issues, "temperature", request.temperature(), 2);
        checkRange(issues, "top_p", request.topP(), 2);
        Integer maxTokens = request.maxTokens();
        if (maxTokens != null && maxTokens <= 0) {
            issues.add(new FieldIssue("max_tokens", "INVALID", "max_tokens 必须为正整数"));
        }
    }

    private static void checkRange(List<FieldIssue> issues, String field, java.math.BigDecimal value, int scale) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            issues.add(new FieldIssue(field, "INVALID", field + " 不能为负数"));
        }
        if (value.stripTrailingZeros().scale() > scale) {
            issues.add(new FieldIssue(field, "INVALID", field + " 小数位最多 " + scale + " 位"));
        }
    }

    private static void checkLength(List<FieldIssue> issues, String field, String value, int max) {
        if (value != null && (value.isEmpty() || value.length() > max)) {
            issues.add(new FieldIssue(field, "INVALID", field + " 为 1-" + max + " 字符"));
        }
    }

    private static void rejectIssues(List<FieldIssue> issues) {
        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求字段校验失败", issues);
        }
    }
}
