package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 应用授权候选目录行（BE-P20-003）：来自活动配置快照中已发布且存在可用候选的虚拟模型。
 * max_output_tokens 取候选能力交集的最小值，allow_stream 仅在全部启用候选可流式时为 true。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationModelOptionView(
        String virtualModelId,
        String code,
        Integer maxOutputTokens,
        Boolean allowStream,
        String snapshotNo) {

    /** 候选目录响应：data.items 结构，无可用模型时为空列表。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Options(java.util.List<ApplicationModelOptionView> items) {

        public Options {
            items = items == null ? java.util.List.of() : java.util.List.copyOf(items);
        }
    }
}
