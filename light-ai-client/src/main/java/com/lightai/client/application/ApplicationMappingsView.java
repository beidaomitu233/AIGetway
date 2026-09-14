package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** 应用当前映射及不可变配置版本。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationMappingsView(
        String applicationId,
        long revision,
        long applicationVersion,
        OffsetDateTime updatedAt,
        List<ApplicationModelMappingView> mappings) {

    public ApplicationMappingsView {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }
}
