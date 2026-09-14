package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 映射校验结果。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationMappingsValidationView(
        boolean valid,
        List<String> issues,
        long applicationVersion) {

    public ApplicationMappingsValidationView {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
