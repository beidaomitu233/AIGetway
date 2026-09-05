package com.lightai.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 模型能力完整性口径验收（BE-014，C-014）：停用导入可缺能力，
 * 启用必完整；context 严格大于 max_output。
 */
class ProviderModelDetailTest {

    private static ProviderModelDetail detail(String tokenizer, Long context, Long output,
                                              Boolean stream) {
        return new ProviderModelDetail("id", "p1", "OpenAI", "gpt-4o", "GPT-4o", "CHAT_TEXT",
                tokenizer, context, output, stream, stream, stream, stream, stream,
                null, null, null, null, 4, 128,
                null, null, null, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, 1000000, "USD", false, null, null,
                "UNKNOWN", false, 1L, null, null);
    }

    @Test
    void importedDisabledModelMayLackCapabilities() {
        ProviderModelDetail imported = detail(null, null, null, null);
        assertThat(imported.capabilitiesComplete()).isFalse();
        assertThat(imported.contextWindowValid()).isTrue();
    }

    @Test
    void enabledModelRequiresFullCapabilities() {
        ProviderModelDetail complete = detail("o200k", 128000L, 16384L, true);
        assertThat(complete.capabilitiesComplete()).isTrue();
        assertThat(complete.contextWindowValid()).isTrue();
    }

    @Test
    void contextMustStrictlyExceedMaxOutput() {
        ProviderModelDetail equal = detail("o200k", 16384L, 16384L, true);
        assertThat(equal.capabilitiesComplete()).isTrue();
        assertThat(equal.contextWindowValid()).isFalse();
    }
}
