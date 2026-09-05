package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * metadata 检索标签（PROJECT_DOCUMENT 边界：不构成租户权限系统）。
 * application 只能等于身份推导值；不透传 Provider。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestMetadata(
        String application,
        String project,
        String tenant,
        String user,
        java.util.Map<String, String> tags) {
}
