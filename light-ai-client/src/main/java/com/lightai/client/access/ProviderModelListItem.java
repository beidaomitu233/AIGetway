package com.lightai.client.access;

import java.time.OffsetDateTime;

/** Provider Model 列表项（4.2.5.1）；价格以十进制字符串传输保证精度。 */
public record ProviderModelListItem(
        String id,
        String providerId,
        String providerName,
        String displayName,
        String modelId,
        Long contextWindow,
        Long maxOutputTokens,
        Boolean supportStream,
        String inputPrice,
        String outputPrice,
        String currency,
        String connectionStatus,
        OffsetDateTime lastCheckAt,
        int routeCandidateCount,
        Boolean enabled,
        Boolean draftChanged,
        OffsetDateTime updatedAt) {
}
