package com.lightai.client.internal;

import com.lightai.client.ChatRequest;
import com.lightai.client.ChatResponse;
import com.lightai.client.ModelInfo;
import com.lightai.client.StreamEvent;
import com.lightai.client.StreamEventType;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandaloneLightAiClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchModelsAndTolerateUnknownFields() {
        server.createContext("/v1/models", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = """
                    {
                      "object": "list",
                      "data": [
                        {
                          "id": "gpt-4o",
                          "light_ai": {
                            "display_name": "GPT 4o",
                            "support_stream": true,
                            "support_system": true
                          },
                          "unknown_field_1": "some_value",
                          "unknown_field_2": 12345
                        }
                      ],
                      "extra_root_prop": true
                    }
                    """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        StandaloneLightAiClient client = new StandaloneLightAiClient(
                "http://127.0.0.1:" + port,
                () -> "test-token-123",
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                0,
                1000L
        );

        List<ModelInfo> models = client.models();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo("gpt-4o");
        assertThat(models.get(0).displayName()).isEqualTo("GPT 4o");
        assertThat(models.get(0).supportStream()).isTrue();
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer test-token-123");

        client.close();
    }

    @Test
    void shouldExecuteSyncChatSuccessfully() {
        server.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = """
                    {
                      "id": "chatcmpl-test-001",
                      "model": "gpt-4o",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "Hello from Standalone Server!"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 12,
                        "completion_tokens": 8,
                        "total_tokens": 20
                      },
                      "unknown_stat": 99
                    }
                    """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        StandaloneLightAiClient client = new StandaloneLightAiClient(
                "http://127.0.0.1:" + port,
                () -> "bearer-token",
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                0,
                1000L
        );

        ChatRequest req = ChatRequest.builder().model("gpt-4o").addUserMessage("Hi").build();
        ChatResponse resp = client.chat(req);

        assertThat(resp.id()).isEqualTo("chatcmpl-test-001");
        assertThat(resp.content()).isEqualTo("Hello from Standalone Server!");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage()).isNotNull();
        assertThat(resp.usage().promptTokens()).isEqualTo(12);
        assertThat(resp.usage().completionTokens()).isEqualTo(8);
        assertThat(resp.usage().totalTokens()).isEqualTo(20);

        client.close();
    }

    @Test
    void shouldStreamChatEvents() throws InterruptedException {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // chunked
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("data: {\"choices\":[{\"delta\":{\"content\":\"World!\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        });
        server.start();

        StandaloneLightAiClient client = new StandaloneLightAiClient(
                "http://127.0.0.1:" + port,
                () -> "bearer-token",
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                0,
                1000L
        );

        ChatRequest req = ChatRequest.builder().model("gpt-4o").addUserMessage("Hi").stream(true).build();
        Flow.Publisher<StreamEvent> publisher = client.stream(req);

        List<StreamEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(100);
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

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(events).isNotEmpty();
        // Should have START, DELTA, DELTA, DONE
        assertThat(events.get(0).type()).isEqualTo(StreamEventType.START);
        List<String> deltas = events.stream()
                .filter(e -> e.type() == StreamEventType.DELTA)
                .map(StreamEvent::deltaContent)
                .toList();
        assertThat(deltas).containsExactly("Hello ", "World!");
        assertThat(events.get(events.size() - 1).type()).isEqualTo(StreamEventType.DONE);

        client.close();
    }

    @Test
    void shouldHandleServerProtocolErrorCleanly() {
        server.createContext("/v1/chat/completions", exchange -> {
            String errorJson = "{\"error\":{\"code\":\"UPSTREAM_TIMEOUT\",\"message\":\"Model upstream timeout\"}}";
            byte[] bytes = errorJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(504, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        StandaloneLightAiClient client = new StandaloneLightAiClient(
                "http://127.0.0.1:" + port,
                () -> "bearer-token",
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                0,
                1000L
        );

        ChatRequest req = ChatRequest.builder().model("gpt-4o").addUserMessage("Hi").build();
        assertThatThrownBy(() -> client.chat(req))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> {
                    LightAiException lae = (LightAiException) e;
                    assertThat(lae.code()).isEqualTo(ErrorCode.SERVER_PROTOCOL_ERROR);
                    assertThat(lae.getMessage()).contains("504");
                    assertThat(lae.getMessage()).contains("Model upstream timeout");
                });

        client.close();
    }

    @Test
    void shouldInvokeDynamicTokenSupplierPerRequest() {
        AtomicInteger tokenSeq = new AtomicInteger(1);
        server.createContext("/v1/models", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        StandaloneLightAiClient client = new StandaloneLightAiClient(
                "http://127.0.0.1:" + port,
                () -> "dynamic-token-" + tokenSeq.getAndIncrement(),
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                0,
                1000L
        );

        client.models();
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer dynamic-token-1");

        client.models();
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer dynamic-token-2");

        client.close();
    }
}