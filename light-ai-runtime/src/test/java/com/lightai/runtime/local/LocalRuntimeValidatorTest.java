package com.lightai.runtime.local;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalRuntimeValidatorTest {

    @Test
    void shouldPassValidDefinition() {
        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addChannel(new LocalRuntimeDefinition.LocalChannelDefinition("chan-openai", "OPENAI", "https://api.openai.com", 60000L))
                .addCredential(new LocalRuntimeDefinition.LocalChannelCredentialDefinition("c-1", "chan-openai", "sec-ref-1"))
                .addModel(LocalRuntimeDefinition.LocalUpstreamModelDefinition.simple("m-gpt4", "chan-openai", "gpt-4o"))
                .addAlias(new LocalRuntimeDefinition.LocalAliasDefinition("a-1", "default", "Default Alias", true, List.of(
                        LocalRuntimeDefinition.LocalCandidateDefinition.of("m-gpt4", "chan-openai")
                )))
                .build();

        LocalRuntimeValidator.validate(def);
    }

    @Test
    void shouldRejectWhenModelChannelDoesNotExist() {
        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addModel(LocalRuntimeDefinition.LocalUpstreamModelDefinition.simple("m-gpt4", "non-existent-chan", "gpt-4o"))
                .addAlias(new LocalRuntimeDefinition.LocalAliasDefinition("a-1", "default", "Default", true, List.of(
                        LocalRuntimeDefinition.LocalCandidateDefinition.of("m-gpt4", null)
                )))
                .build();

        assertThatThrownBy(() -> LocalRuntimeValidator.validate(def))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }

    @Test
    void shouldRejectWhenContextWindowLessThanMaxOutputTokens() {
        LocalRuntimeDefinition.LocalUpstreamModelDefinition invalidModel = new LocalRuntimeDefinition.LocalUpstreamModelDefinition(
                "m-gpt4", "chan-openai", "gpt-4o",
                1000L, 4000L, true, true, true, true, true,
                BigDecimal.ZERO, BigDecimal.valueOf(2.0),
                BigDecimal.ZERO, BigDecimal.ONE, 4,
                BigDecimal.ONE, BigDecimal.ONE, 2048L,
                "0.00", "0.00", 1000, "USD"
        );

        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addChannel(new LocalRuntimeDefinition.LocalChannelDefinition("chan-openai", "OPENAI", "https://api.openai.com", 60000L))
                .addModel(invalidModel)
                .addAlias(new LocalRuntimeDefinition.LocalAliasDefinition("a-1", "default", "Default", true, List.of(
                        LocalRuntimeDefinition.LocalCandidateDefinition.of("m-gpt4", null)
                )))
                .build();

        assertThatThrownBy(() -> LocalRuntimeValidator.validate(def))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> {
                    LightAiException lae = (LightAiException) e;
                    assertThat(lae.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
                    assertThat(lae.getMessage()).contains("context_window");
                });
    }

    @Test
    void shouldRejectWhenCandidateChannelMismatchModel() {
        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addChannel(new LocalRuntimeDefinition.LocalChannelDefinition("chan-openai", "OPENAI", "https://api.openai.com", 60000L))
                .addChannel(new LocalRuntimeDefinition.LocalChannelDefinition("chan-anthropic", "ANTHROPIC", "https://api.anthropic.com", 60000L))
                .addModel(LocalRuntimeDefinition.LocalUpstreamModelDefinition.simple("m-gpt4", "chan-openai", "gpt-4o"))
                .addAlias(new LocalRuntimeDefinition.LocalAliasDefinition("a-1", "default", "Default", true, List.of(
                        // 模型属于 OpenAI 渠道，但候选绑定的是 Anthropic 渠道
                        LocalRuntimeDefinition.LocalCandidateDefinition.of("m-gpt4", "chan-anthropic")
                )))
                .build();

        assertThatThrownBy(() -> LocalRuntimeValidator.validate(def))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> {
                    LightAiException lae = (LightAiException) e;
                    assertThat(lae.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
                    assertThat(lae.getMessage()).contains("渠道不一致");
                });
    }

    @Test
    void shouldRejectWhenEmptyAliases() {
        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder().build();

        assertThatThrownBy(() -> LocalRuntimeValidator.validate(def))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }
}
