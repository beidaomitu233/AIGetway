package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.client.json.ProtocolJson;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用对某个虚拟模型配置的可选请求参数上限（PRD 9.2.5）。
 * 任一维度为 null 表示不施加该维度限制；两个维度都为 null 表示无上限。
 * 应用上限只能收紧，不能突破虚拟模型与上游候选的能力边界。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationModelConstraint(
        String virtualModelId,
        Integer maxOutputTokens,
        Boolean streamAllowed) {

    public ApplicationModelConstraint {
        virtualModelId = virtualModelId == null ? null : virtualModelId.trim();
    }

    /** 两个维度都未配置时视为无上限。 */
    public boolean isEmpty() {
        return maxOutputTokens == null && streamAllowed == null;
    }

    /** 序列化为 application_model_permission.constraints_json；无上限写空对象。 */
    public String toJson() {
        if (isEmpty()) {
            return "{}";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (maxOutputTokens != null) {
            payload.put("max_output_tokens", maxOutputTokens);
        }
        if (streamAllowed != null) {
            payload.put("stream_allowed", streamAllowed);
        }
        try {
            return ProtocolJson.protocol().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("应用模型参数上限序列化失败", e);
        }
    }

    /** 解析已持久化的 constraints_json；缺失或非法内容按无上限处理。 */
    public static ApplicationModelConstraint fromJson(String virtualModelId, String json) {
        if (json == null || json.isBlank()) {
            return new ApplicationModelConstraint(virtualModelId, null, null);
        }
        try {
            var node = ProtocolJson.protocol().readTree(json);
            Integer maxOutputTokens = node.hasNonNull("max_output_tokens")
                    ? node.get("max_output_tokens").asInt() : null;
            Boolean streamAllowed = node.hasNonNull("stream_allowed")
                    ? node.get("stream_allowed").asBoolean() : null;
            return new ApplicationModelConstraint(virtualModelId, maxOutputTokens, streamAllowed);
        } catch (RuntimeException | JsonProcessingException e) {
            return new ApplicationModelConstraint(virtualModelId, null, null);
        }
    }
}
