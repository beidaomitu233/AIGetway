package com.lightai.client.application;

/** 应用获授权的虚拟模型。virtualModelCode 对应业务请求中的 model。 */
public record ApplicationModelPermissionView(
        String id,
        String virtualModelId,
        String virtualModelCode,
        boolean enabled,
        long version) {
}
