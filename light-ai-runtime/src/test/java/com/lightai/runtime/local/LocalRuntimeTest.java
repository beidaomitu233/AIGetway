package com.lightai.runtime.local;

import com.lightai.client.ChatRequest;
import com.lightai.client.ChatResponse;
import com.lightai.client.LightAiClient;
import com.lightai.client.ModelInfo;
import com.lightai.client.StreamEvent;
import com.lightai.client.StreamEventType;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderStreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalRuntimeTest {

    @Test
    void shouldExecuteEndToEndLocalRuntime() throws InterruptedException {
        LocalRuntimeDefinition def = LocalRuntimeDefinition.builder()
                .addProvider(new LocalRuntimeDefinition.LocalProviderDefinition("p-openai", "OPENAI", "https://api.openai.com", 60000L))
                .addPool(new LocalRuntimeDefinition.LocalPoolDefinition("pool-1", "p-openai", "PRIORITY"))
                .addCredential(new LocalRuntimeDefinition.LocalCredentialDefinition("c-1", "pool-1", "p-openai", null))
                .addModel(LocalRuntimeDefinition.LocalModelDefinition.simple("m-gpt4", "p-openai", "gpt-4o"))
                .addAlias(new LocalRuntimeDefinition.LocalAliasDefinition("a-1", "default", "Default Alias", true, List.of(
                        LocalRuntimeDefinition.LocalCandidateDefinition.of("m-gpt4", "pool-1")
                )))
                .build();

        MockAdapter adapter = new MockAdapter();

        LightAiClient client = LightAiClient.builder()
                .localRuntimeDefinition(def)
                .credentialSecretSuppliers(Map.of("pool-1", () -> "sk-fake".toCharArray()))
                .adapters(List.of(adapter))
                .build();

        assertThat(client.isClosed()).isFalse();

        // 1. Models
        List<ModelInfo> models = client.models();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo("default");
        assertThat(models.get(0).displayName()).isEqualTo("Default Alias");

        // 2. Sync Chat
        ChatRequest req = ChatRequest.builder().model("default").addUserMessage("Hello").build();
        ChatResponse resp = client.chat(req);
        assertThat(resp).isNotNull();
        assertThat(resp.content()).isEqualTo("Local runtime says hello");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage()).isNotNull();
        assertThat(resp.usage().promptTokens()).isEqualTo(10L);
        assertThat(resp.usage().completionTokens()).isEqualTo(20L);

        // 3. Stream Chat
        ChatRequest streamReq = ChatRequest.builder().model("default").addUserMessage("Hello").stream(true).build();
        Flow.Publisher<StreamEvent> publisher = client.stream(streamReq);

        List<StreamEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(10);
            }

            @Override
            public void onNext(StreamEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).type()).isEqualTo(StreamEventType.START);
        List<String> deltas = events.stream()
                .filter(e -> e.type() == StreamEventType.DELTA)
                .map(StreamEvent::deltaContent)
                .toList();
        assertThat(deltas).contains("Local ", "world!");

        // 4. Close
        client.close();
        assertThat(client.isClosed()).isTrue();

        assertThatThrownBy(() -> client.chat(req))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.CLIENT_CLOSED));
    }

    private static class MockAdapter implements ProviderAdapter {
        @Override
        public String providerType() {
            return "OPENAI";
        }

        @Override
        public AdapterCapabilities capabilities() {
            return new AdapterCapabilities(true, true, true, true, List.of("gpt-4"), 4, Set.of("stop"), List.of());
        }

        @Override
        public long estimateTokens(ProviderChatRequest request) {
            return 10L;
        }

        @Override
        public ProviderChatResponse chat(ProviderCallContext context) {
            return new ProviderChatResponse("Local runtime says hello", "stop", 10L, 20L, 30L, "ACTUAL", "mock-req-1");
        }

        @Override
        public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private int step = 0;
                private boolean cancelled = false;

                @Override
                public void request(long n) {
                    if (cancelled) return;
                    if (step == 0) {
                        step = 1;
                        subscriber.onNext(ProviderStreamChunk.content("Local "));
                    }
                    if (step == 1) {
                        step = 2;
                        subscriber.onNext(ProviderStreamChunk.content("world!"));
                        subscriber.onNext(ProviderStreamChunk.finish("stop"));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }

        @Override
        public ProviderErrorClassification classifyError(ProviderFailure failure) {
            return new ProviderErrorClassification("PROVIDER_SERVER_ERROR", true, true, true, false);
        }
    }
}