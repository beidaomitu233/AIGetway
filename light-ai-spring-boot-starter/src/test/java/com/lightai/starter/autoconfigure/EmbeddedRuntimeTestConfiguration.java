package com.lightai.starter.autoconfigure;

import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderStreamChunk;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

@Configuration(proxyBeanMethods = false)
class EmbeddedRuntimeTestConfiguration {

    private static final String CREDENTIAL_ID = "00000000-0000-0000-0000-000000000004";

    @Bean
    ConfigSnapshotPort embeddedTestSnapshotPort() {
        ConfigSnapshotPort.CandidateView candidate = new ConfigSnapshotPort.CandidateView(
                "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000005",
                "MOCK",
                "00000000-0000-0000-0000-000000000003",
                "mock-model",
                "00000000-0000-0000-0000-000000000006",
                1L,
                100,
                true,
                "MOCK",
                8_192L,
                1_024L,
                true,
                true,
                true,
                true,
                true,
                BigDecimal.ZERO,
                BigDecimal.valueOf(2),
                BigDecimal.ZERO,
                BigDecimal.ONE,
                4,
                BigDecimal.ONE,
                BigDecimal.ONE,
                512L,
                "0.00",
                "0.00",
                1_000,
                "USD",
                "https://example.invalid",
                null,
                3_000,
                120_000,
                Map.of());
        ConfigSnapshotPort.AliasView alias = new ConfigSnapshotPort.AliasView(
                "00000000-0000-0000-0000-000000000001",
                "demo",
                "Demo",
                true,
                List.of(candidate));
        return () -> new ConfigSnapshotPort.ActiveSnapshot(1L, List.of(alias));
    }

    @Bean
    CredentialSecretPort embeddedTestCredentialSecretPort() {
        return (poolId, failoverIndex) -> new CredentialSecretPort.ResolvedCredential(
                CREDENTIAL_ID,
                () -> "sk-test".toCharArray());
    }

    @Bean
    ProviderAdapter embeddedTestProviderAdapter() {
        return new ProviderAdapter() {
            @Override
            public String providerType() {
                return "MOCK";
            }

            @Override
            public AdapterCapabilities capabilities() {
                return new AdapterCapabilities(
                        true, true, true, false,
                        List.of("MOCK"), 4, Set.of("stop"), List.of());
            }

            @Override
            public long estimateTokens(ProviderChatRequest request) {
                return 10L;
            }

            @Override
            public ProviderChatResponse chat(ProviderCallContext context) {
                return new ProviderChatResponse(
                        "embedded response", "stop",
                        10L, 20L, 30L, "ACTUAL", "mock-request-1");
            }

            @Override
            public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }

            @Override
            public ProviderErrorClassification classifyError(ProviderFailure failure) {
                return new ProviderErrorClassification(
                        "PROVIDER_SERVER_ERROR", true, true, true, true);
            }
        };
    }
}
