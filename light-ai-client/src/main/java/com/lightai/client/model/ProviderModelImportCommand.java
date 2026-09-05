package com.lightai.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 模型导入命令（BACKEND_PLAN 2 协议字典 ProviderModelImportCommand）。
 * source=PROVIDER_API 时 credential_id 必填；逐对象事务提交（C-005）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderModelImportCommand(
        String providerId,
        String source,
        String credentialId,
        List<String> modelIds,
        boolean applyKnownDefaults,
        boolean enabled) {

    public static final String SOURCE_PROVIDER_API = "PROVIDER_API";
    public static final String SOURCE_ADAPTER_PRESET = "ADAPTER_PRESET";
    public static final int MAX_BATCH = 100;

    public ProviderModelImportCommand {
        modelIds = modelIds == null ? List.of() : List.copyOf(modelIds);
        if (modelIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException("model_ids 最多 " + MAX_BATCH + " 项");
        }
    }

    /** 去重后的模型 ID 列表。 */
    public List<String> distinctModelIds() {
        return modelIds.stream().distinct().toList();
    }
}
