package com.lightai.admin.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** SecretManager/SecretCipher 语义（BE-013）：加密往返、二次确认、掩码与来源互斥。 */
class SecretManagerTest {

    private final SecretCipher cipher = new SecretCipher(
            SecretCipher.fixedKeyProvider("test-key", SecretCipher.randomBase64Key().get()));
    private final SecretManager manager = new SecretManager(cipher);

    @Test
    void inlineRoundTripDecrypts() {
        SecretManager.Prepared prepared = manager.prepareInline("sk-live-abcdef123456", "sk-live-abcdef123456");
        assertThat(prepared.inline()).isTrue();
        assertThat(prepared.secretRefCiphertext()).isNull();
        byte[] decrypted = cipher.decrypt(prepared.secretCiphertext());
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("sk-live-abcdef123456");
    }

    @Test
    void confirmMismatchRejected() {
        assertThatThrownBy(() -> manager.prepareInline("secret-a", "secret-b"))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SECRET_CONFIRM_MISMATCH));
    }

    @Test
    void externalRefEncryptedAndMasked() {
        SecretManager.Prepared prepared = manager.prepareExternal("vault://prod/openai-key-1");
        assertThat(prepared.inline()).isFalse();
        assertThat(prepared.secretRefCiphertext()).isNotNull();
        assertThat(prepared.maskedValue()).startsWith("vault://").endsWith("****");
        assertThat(new String(cipher.decrypt(prepared.secretRefCiphertext()), StandardCharsets.UTF_8))
                .isEqualTo("vault://prod/openai-key-1");
    }

    @Test
    void maskHidesMiddleSegment() {
        assertThat(SecretManager.mask("sk-1234567890abcd")).startsWith("sk-").endsWith("abcd").contains("*");
        assertThat(SecretManager.mask("short")).isEqualTo("*****");
        assertThat(SecretManager.maskRef("vault://prod/key-1")).doesNotContain("key-1");
    }

    @Test
    void refLengthValidated() {
        String tooLong = "r".repeat(513);
        assertThatThrownBy(() -> manager.prepareExternal(tooLong))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }
}
