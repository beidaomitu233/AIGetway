package com.lightai.storage.credential;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 秘密掩码验收（BE-013）：页面仅展示安全掩码，明文与完整引用永不出现。
 */
class SecretMaskerTest {

    @Test
    void longSecretKeepsLastFourOnly() {
        assertThat(SecretMasker.mask("sk-live-abcdef123456".toCharArray())).isEqualTo("****3456");
    }

    @Test
    void shortSecretIsFullyMasked() {
        assertThat(SecretMasker.mask("abc".toCharArray())).isEqualTo("****");
        assertThat(SecretMasker.mask(new char[0])).isEqualTo("****");
        assertThat(SecretMasker.mask(null)).isEqualTo("****");
    }

    @Test
    void externalRefKeepsSchemeOnly() {
        assertThat(SecretMasker.maskRef("vault://prod/openai/key-1")).isEqualTo("vault://…(external)");
        assertThat(SecretMasker.maskRef(null)).isNull();
    }
}
