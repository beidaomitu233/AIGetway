package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 模型导入结果（C-005）：逐对象事务，每个成功对象与审计原子；
 * 单项失败保留其余成功项，重复导入 skipped。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ImportResult(
        List<CreatedEntry> created,
        List<SkippedEntry> skipped,
        List<FailedEntry> failed) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CreatedEntry(String modelId, String id, long version) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SkippedEntry(String modelId, String reason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FailedEntry(String modelId, String error) {
    }
}
