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
                .addProvider(new LocalRuntimeDefinition.LocalProviderDefinition("p-openai", "OPENAI", "https://api.openai.com", 60000L))
                .addPool(new LocalRuntimeDefinition.LocalPoolDefinition("pool-1", "p-openai", "PRIORITY"))
                .addCredential(new LocalRuntimeDefinition.LocalCredentialDefinition("c-1", "pool-1", "p-openai", "sec-ref-1"))
                .addModel(LocalRuntimeDefinition.LocalModelDefinition.simple("m-gpt4", "p-openai", "gpt-4o"))
                .addAlias(new LocalRuntimeDefinition.LocalAliasDefinition("a-1", "default", "Default Alias", true, List.of(
                        LocalRuntimeDefinition.LocalCandidateDefinition.of("m-gpt4", "pool-1")
                )))
                .build();

        LocalRuntimeValidator.validate(def);
    }

    @Test
    void shouldRejectWhenModelProviderDoesNotExist() {
        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addModel(LocalRuntimeDefinition.LocalModelDefinition.simple("m-gpt4", "non-existent-prov", "gpt-4o"))
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
        LocalRuntimeDefinition.LocalModelDefinition invalidModel = new LocalRuntimeDefinition.LocalModelDefinition(
                "m-gpt4", "p-openai", "gpt-4o",
                1000L, 4000L, true, true, true, true, true,
                BigDecimal.ZERO, BigDecimal.valueOf(2.0),
                BigDecimal.ZERO, BigDecimal.ONE, 4,
                BigDecimal.ONE, BigDecimal.ONE, 2048L,
                "0.00", "0.00", 1000, "USD"
        );

        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addProvider(new LocalRuntimeDefinition.LocalProviderDefinition("p-openai", "OPENAI", "https://api.openai.com", 60000L))
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
    void shouldRejectWhenCandidatePoolMismatchProvider() {
        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addProvider(new LocalRuntimeDefinition.LocalProviderDefinition("p-openai", "OPENAI", "https://api.openai.com", 60000L))
                .addProvider(new LocalRuntimeDefinition.LocalProviderDefinition("p-anthropic", "ANTHROPIC", "https://api.anthropic.com", 60000L))
                .addPool(new LocalRuntimeDefinition.LocalPoolDefinition("pool-anthropic", "p-anthropic", "PRIORITY"))
                .addModel(LocalRuntimeDefinition.LocalModelDefinition.simple("m-gpt4", "p-openai", "gpt-4o"))
                .addAlias(new LocalRuntimeDefinition.LocalAliasDefinition("a-1", "default", "Default", true, List.of(
                        // Model is OpenAI, but pool is Anthropic!
                        LocalRuntimeDefinition.LocalCandidateDefinition.of("m-gpt4", "pool-anthropic")
                )))
                .build();

        assertThatThrownBy(() -> LocalRuntimeValidator.validate(def))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> {
                    LightAiException lae = (LightAiException) e;
                    assertThat(lae.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
                    assertThat(lae.getMessage()).contains("provider 不一致");
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