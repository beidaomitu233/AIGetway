package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Credential 列表项（BACKEND_PLAN BE-212：Key 级 priority/weight 与限额、冷却可见）。
 * 响应只含 masked_value/secret_source/secret_ref_display，
 * secret_value 与 token_hash 永不出现在任何响应中。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChannelCredentialListItem(
        String id,
        String channelId,
        String name,
        String maskedValue,
        String secretRefDisplay,
        String secretSource,
        int priority,
        int weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        long currentConcurrency,
        String healthStatus,
        OffsetDateTime rateLimitResetAt,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastCheckAt,
        boolean enabled,
        boolean draftChanged,
        long version) {

    public static final String SOURCE_INLINE = "INLINE_ENCRYPTED";
    public static final String SOURCE_EXTERNAL = "EXTERNAL_REF";
}
