package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 模型导入命令（BACKEND_PLAN 2 协议字典，C-005）：
 * model_ids 非空去重 ≤100；PROVIDER_API 时 credential_id 必填；
 * 未知能力不可 enabled；逐对象事务提交。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderModelImportCommand(
        String providerId,
        String source,
        String credentialId,
        List<String> modelIds,
        Boolean applyKnownDefaults,
        Boolean enabled) {
}
