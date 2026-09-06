package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** GET /admin/developer-access/context（BE-046）：发布形态与授权 Alias。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DeveloperAccessContext(
        String runtimeMode,
        String baseUrl,
        List<AliasOption> publishedAliases,
        String defaultAliasId,
        OffsetDateTime publishedAt,
        long snapshotNo) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AliasOption(String aliasId, String alias, String displayName, boolean supportStream) {
    }
}
