package com.lightai.client.credential;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Credential 命令校验（BE-013）：来源互斥、weight/限额边界。
 */
class CredentialCreateCommandTest {

    @Test
    void inlineRequiresSecretValue() {
        assertThatCode(() -> new CredentialCreateCommand("key-1", CredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 10, null, null, null, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new CredentialCreateCommand("key-1", CredentialListItem.SOURCE_INLINE,
                "  ", null, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalRequiresSecretRef() {
        assertThatCode(() -> new CredentialCreateCommand("key-2", CredentialListItem.SOURCE_EXTERNAL,
                null, "vault://prod/key", 10, null, null, null, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new CredentialCreateCommand("key-2", CredentialListItem.SOURCE_EXTERNAL,
                null, null, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSourceRejected() {
        assertThatThrownBy(() -> new CredentialCreateCommand("key-3", "PLAIN", "x", null,
                10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weightAndLimitsBounds() {
        assertThatThrownBy(() -> new CredentialCreateCommand("key-4", CredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 0, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CredentialCreateCommand("key-5", CredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 10, 0L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new CredentialCreateCommand("key-6", CredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 100, 1000L, 200000L, 100000, true)).doesNotThrowAnyException();
    }

    @Test
    void nameBounds() {
        assertThatThrownBy(() -> new CredentialCreateCommand("a", CredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
