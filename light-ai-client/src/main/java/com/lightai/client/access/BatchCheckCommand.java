package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 批量检测命令（4.2.9.3）：provider_model_ids 非空去重 ≤100，command 中不携带密钥。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BatchCheckCommand(
        List<String> providerModelIds,
        String credentialId,
        String mode,
        Integer timeoutMs) {
}
