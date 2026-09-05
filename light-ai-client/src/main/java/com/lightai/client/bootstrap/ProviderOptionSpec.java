package com.lightai.client.bootstrap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Provider Option 声明：key/type/required/default/min/max/enum_values/description。
 * default/min/max 的类型由 type 决定（STRING/INTEGER/DECIMAL/BOOLEAN），
 * 以 JsonNode 承载原始字面量，未声明的 key 一律拒绝。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderOptionSpec(
        String key,
        String type,
        boolean required,
        JsonNode defaultValue,
        JsonNode min,
        JsonNode max,
        List<String> enumValues,
        String description) {

    public ProviderOptionSpec {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }
}
