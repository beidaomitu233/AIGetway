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

    @com.fasterxml.jackson.annotation.JsonProperty("available_models")
    public List<AliasOption> availableModels() {
        return publishedAliases == null ? List.of() : publishedAliases;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("api_base_url")
    public String apiBaseUrl() {
        return baseUrl;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("current_snapshot_no")
    public Long currentSnapshotNo() {
        return snapshotNo;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("selected_alias_id")
    public String selectedAliasId() {
        if (defaultAliasId != null && !defaultAliasId.isBlank()) {
            return defaultAliasId;
        }
        if (publishedAliases != null && !publishedAliases.isEmpty()) {
            return publishedAliases.get(0).aliasId();
        }
        return null;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("authentication_type")
    public String authenticationType() {
        return "STANDALONE_SERVER".equalsIgnoreCase(runtimeMode) ? "BEARER_TOKEN" : "NONE";
    }

    @com.fasterxml.jackson.annotation.JsonProperty("sdk_version")
    public String sdkVersion() {
        return "0.1.0";
    }

    @com.fasterxml.jackson.annotation.JsonProperty("server_version")
    public String serverVersion() {
        return "0.1.0";
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AliasOption(
            String aliasId,
            String alias,
            String displayName,
            boolean supportStream,
            boolean supportSystemMessage,
            Integer contextWindow,
            Integer maxOutputTokens) {

        public AliasOption(String aliasId, String alias, String displayName, boolean supportStream) {
            this(aliasId, alias, displayName, supportStream, true, 128000, 16384);
        }
    }
}
