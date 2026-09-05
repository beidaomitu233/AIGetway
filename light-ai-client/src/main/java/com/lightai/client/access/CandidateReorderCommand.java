package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 候选原子重排命令（BE-018）：必须提交完整候选集合且无重复 ID，
 * 所有 version 核对通过后统一写入；任一冲突整批回滚，weight 保持原值。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CandidateReorderCommand(List<Item> items) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(String id, Integer priority, long version) {
    }
}
