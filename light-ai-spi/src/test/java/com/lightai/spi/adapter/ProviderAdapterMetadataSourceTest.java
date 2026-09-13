package com.lightai.spi.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.client.bootstrap.AdapterDeclaration;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class ProviderAdapterMetadataSourceTest {

    @Test
    void derivesDeclarationFromLoadedAdapter() {
        ProviderAdapterMetadataSource source = new ProviderAdapterMetadataSource(
                List.of(new StubAdapter("OPENAI", "https://api.openai.com/v1/",
                        new AdapterCapabilities(true, true, true, false,
                                List.of("O200K"), 4, java.util.Set.of(), List.of()))));

        List<AdapterDeclaration> declarations = source.declarations();

        assertThat(declarations).hasSize(1);
        AdapterDeclaration declaration = declarations.get(0);
        assertThat(declaration.providerType()).isEqualTo("OPENAI");
        assertThat(declaration.defaultBaseUrl()).isEqualTo("https://api.openai.com/v1/");
        assertThat(declaration.tokenizerFamilies()).containsExactly("O200K");
        assertThat(declaration.capabilities()).containsExactly("CHAT", "STREAM", "SYSTEM_MESSAGE");
        assertThat(declaration.providerOptionSpecs()).isEmpty();
    }

    @Test
    void deduplicatesByTypeCaseInsensitiveKeepingFirst() {
        ProviderAdapterMetadataSource source = new ProviderAdapterMetadataSource(
                List.of(new StubAdapter("OPENAI", "https://first.example.com/", null),
                        new StubAdapter("openai", "https://second.example.com/", null),
                        new StubAdapter("ANTHROPIC", null, null)));

        List<AdapterDeclaration> declarations = source.declarations();

        assertThat(declarations).hasSize(2);
        assertThat(declarations.get(0).providerType()).isEqualTo("OPENAI");
        assertThat(declarations.get(0).defaultBaseUrl()).isEqualTo("https://first.example.com/");
        assertThat(declarations.get(1).providerType()).isEqualTo("ANTHROPIC");
        assertThat(declarations.get(1).defaultBaseUrl()).isNull();
    }

    @Test
    void skipsBlankTypeAndToleratesNullCapabilities() {
        ProviderAdapterMetadataSource source = new ProviderAdapterMetadataSource(
                List.of(new StubAdapter(" ", "https://ignored.example.com/", null),
                        new StubAdapter(null, "https://ignored.example.com/", null),
                        new StubAdapter("CUSTOM", null, null)));

        List<AdapterDeclaration> declarations = source.declarations();

        assertThat(declarations).hasSize(1);
        assertThat(declarations.get(0).providerType()).isEqualTo("CUSTOM");
        assertThat(declarations.get(0).capabilities()).isEmpty();
        assertThat(declarations.get(0).tokenizerFamilies()).isEmpty();
    }

    @Test
    void emptyAndNullAdapterListsProduceNoDeclarations() {
        assertThat(new ProviderAdapterMetadataSource(List.of()).declarations()).isEmpty();
        assertThat(new ProviderAdapterMetadataSource(null).declarations()).isEmpty();
    }

    private static final class StubAdapter implements ProviderAdapter {

        private final String providerType;
        private final String defaultBaseUrl;
        private final AdapterCapabilities capabilities;

        private StubAdapter(String providerType, String defaultBaseUrl, AdapterCapabilities capabilities) {
            this.providerType = providerType;
            this.defaultBaseUrl = defaultBaseUrl;
            this.capabilities = capabilities;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public AdapterCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public String defaultBaseUrl() {
            return defaultBaseUrl;
        }

        @Override
        public long estimateTokens(ProviderChatRequest request) {
            return 0;
        }

        @Override
        public ProviderChatResponse chat(ProviderCallContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<com.lightai.spi.provider.ProviderStreamChunk> streamChat(ProviderCallContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderErrorClassification classifyError(ProviderFailure failure) {
            throw new UnsupportedOperationException();
        }
    }
}
