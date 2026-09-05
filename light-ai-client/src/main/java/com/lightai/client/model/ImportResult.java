package com.lightai.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 模型导入结果（C-005：逐对象事务，单项失败保留其余成功）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ImportResult(
        List<CreatedModel> created,
        List<SkippedModel> skipped,
        List<FailedModel> failed) {

    public ImportResult {
        created = created == null ? List.of() : List.copyOf(created);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        failed = failed == null ? List.of() : List.copyOf(failed);
    }

    public record CreatedModel(String modelId, String id, long version) {
    }

    public record SkippedModel(String modelId, String reason) {
    }

    public record FailedModel(String modelId, String error) {
    }
}
