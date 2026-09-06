package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;

/** GET /admin/developer-access/code-sample：语言示例，秘密为占位符。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CodeSampleResult(
        String language,
        String runtimeMode,
        String alias,
        boolean stream,
        String sample) {
}
