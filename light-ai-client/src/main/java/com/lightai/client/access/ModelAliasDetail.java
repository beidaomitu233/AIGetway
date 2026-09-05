package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** Model Alias 详情（4.2.8.1）；alias 创建后不可变。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelAliasDetail(
        String id,
        String alias,
        String displayName,
        String description,
        String routeStrategy,
        Boolean enabled,
        int candidateCount,
        int streamCandidateCount,
        Boolean draftChanged,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {
}
