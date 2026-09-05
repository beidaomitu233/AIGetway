package com.lightai.client.access;

import java.time.OffsetDateTime;

/** Model Alias 列表项（4.2.7.1）；实时可用数来自候选运行状态聚合。 */
public record ModelAliasListItem(
        String id,
        String alias,
        String displayName,
        String routeStrategy,
        int candidateCount,
        Integer availableCandidateCount,
        Boolean supportStream,
        int streamCandidateCount,
        Boolean enabled,
        Boolean draftChanged,
        OffsetDateTime updatedAt) {
}
