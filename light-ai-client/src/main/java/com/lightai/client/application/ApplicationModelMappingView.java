package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationModelMappingView(
        String id,
        String publicModelName,
        String status,
        long version,
        List<ApplicationModelTargetView> targets) {

    public ApplicationModelMappingView {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
