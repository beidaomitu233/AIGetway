package com.lightai.runtime.secret;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.secret.ResolvedSecret;
import com.lightai.spi.secret.SecretProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretManagerTest {

    @Test
    void shouldDetectSecretProviderConflict() {
        SecretProvider p1 = new DummyProvider("vault://", "p1");
        SecretProvider p2 = new DummyProvider("vault://", "p2");

        SecretManager manager = new SecretManager(List.of(p1, p2));

        assertThatThrownBy(() -> manager.resolveSync("vault://app/key"))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.SECRET_PROVIDER_CONFLICT));
    }

    @Test
    void shouldResolveAndCacheSecret() {
        DummyProvider p1 = new DummyProvider("vault://", "p1");
        SecretManager manager = new SecretManager(List.of(p1));

        ResolvedSecret s1 = manager.resolveSync("vault://app/key");
        assertThat(s1).isNotNull();
        assertThat(s1.secret()).isEqualTo("secret-vault://app/key".toCharArray());
        assertThat(p1.resolveCount.get()).isEqualTo(1);

        // Second call should hit cache
        ResolvedSecret s2 = manager.resolveSync("vault://app/key");
        assertThat(s2).isNotNull();
        assertThat(p1.resolveCount.get()).isEqualTo(1); // Not incremented
    }

    @Test
    void shouldInvalidateAndZeroMemory() {
        DummyProvider p1 = new DummyProvider("vault://", "p1");
        SecretManager manager = new SecretManager(List.of(p1));

        ResolvedSecret s1 = manager.resolveSync("vault://app/key");
        assertThat(s1.isCleared()).isFalse();

        manager.invalidate("vault://app/key", 1);
        assertThat(s1.isCleared()).isTrue();

        // After invalidation, should resolve again
        ResolvedSecret s2 = manager.resolveSync("vault://app/key");
        assertThat(p1.resolveCount.get()).isEqualTo(2);
    }

    @Test
    void shouldFailWhenNoProviderSupportsRef() {
        SecretManager manager = new SecretManager(List.of());

        assertThatThrownBy(() -> manager.resolveSync("unknown://app/key"))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.SECRET_RESOLUTION_FAILED));
    }

    @Test
    void shouldClearAllSecretsOnClear() {
        DummyProvider p1 = new DummyProvider("vault://", "p1");
        SecretManager manager = new SecretManager(List.of(p1));

        ResolvedSecret s1 = manager.resolveSync("vault://app/key");
        manager.clear();

        assertThat(s1.isCleared()).isTrue();
    }

    private static class DummyProvider implements SecretProvider {
        private final String prefix;
        private final String name;
        final AtomicInteger resolveCount = new AtomicInteger(0);

        DummyProvider(String prefix, String name) {
            this.prefix = prefix;
            this.name = name;
        }

        @Override
        public boolean supports(String secretRef) {
            return secretRef != null && secretRef.startsWith(prefix);
        }

        @Override
        public Optional<char[]> resolve(String secretRef) {
            resolveCount.incrementAndGet();
            return Optional.of(("secret-" + secretRef).toCharArray());
        }

        @Override
        public void invalidate(String secretRef, int version) {
        }
    }
}