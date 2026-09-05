package com.lightai.client.impact;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ImpactReference(
        String entityType,
        String id,
        String name,
        String relation) {
}
