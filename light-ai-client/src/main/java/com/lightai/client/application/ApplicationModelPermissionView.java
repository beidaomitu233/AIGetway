package com.lightai.client.application;

/**
 * 应用获授权的虚拟模型。virtualModelCode 对应业务请求中的 model。
 * maxOutputTokens 与 allowStream 为应用级可选上限，null 表示不限（BE-P20-003 统一命名）。
 */
public record ApplicationModelPermissionView(
        String id,
        String virtualModelId,
        String virtualModelCode,
        boolean enabled,
        Integer maxOutputTokens,
        Boolean allowStream,
        long version) {
}
