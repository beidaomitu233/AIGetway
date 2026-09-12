package com.lightai.client.channel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Credential 命令校验（BE-212）：来源互斥、priority/weight/限额边界。
 */
class ChannelCredentialCreateCommandTest {

    @Test
    void inlineRequiresSecretValue() {
        assertThatCode(() -> new ChannelCredentialCreateCommand("key-1", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 10, 10, null, null, null, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("key-1", ChannelCredentialListItem.SOURCE_INLINE,
                "  ", null, 10, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalRequiresSecretRef() {
        assertThatCode(() -> new ChannelCredentialCreateCommand("key-2", ChannelCredentialListItem.SOURCE_EXTERNAL,
                null, "vault://prod/key", 10, 10, null, null, null, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("key-2", ChannelCredentialListItem.SOURCE_EXTERNAL,
                null, null, 10, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSourceRejected() {
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("key-3", "PLAIN", "x", null,
                10, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weightAndLimitsBounds() {
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("key-4", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 10, 0, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("key-5", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 10, 10, 0L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ChannelCredentialCreateCommand("key-6", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 100, 100, 1000L, 200000L, 100000, true)).doesNotThrowAnyException();
    }

    @Test
    void priorityBounds() {
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("key-7", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 0, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("key-8", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 101, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ChannelCredentialCreateCommand("key-9", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, null, 10, null, null, null, true)).doesNotThrowAnyException();
    }

    @Test
    void nameBounds() {
        assertThatThrownBy(() -> new ChannelCredentialCreateCommand("a", ChannelCredentialListItem.SOURCE_INLINE,
                "sk-abc12345", null, 10, 10, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
