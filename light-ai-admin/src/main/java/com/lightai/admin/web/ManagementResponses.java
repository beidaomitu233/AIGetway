package com.lightai.admin.web;

import com.lightai.client.json.ProtocolJson;

/**
 * 管理响应封装：成功 {"data": T}，失败 {"error": UnifiedError}（PROJECT_DOCUMENT 第 3 节）。
 * 序列化统一走协议 ObjectMapper，不依赖宿主应用的 Jackson 配置，
 * Embedded 模式下不改变宿主序列化行为。
 */
public final class ManagementResponses {

    public static final String APPLICATION_JSON = "application/json;charset=UTF-8";

    private ManagementResponses() {
    }

    /** 成功响应：{data: T}。 */
    public static String ok(Object data) {
        return toJson(java.util.Map.of("data", data));
    }

    /** 失败响应：{error: UnifiedError}。 */
    public static String error(com.lightai.client.error.UnifiedError error) {
        return toJson(java.util.Map.of("error", error));
    }

    private static String toJson(Object payload) {
        try {
            return ProtocolJson.protocol().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("管理响应序列化失败", e);
        }
    }
}
