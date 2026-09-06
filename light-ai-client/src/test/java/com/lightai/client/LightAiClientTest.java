package com.lightai.client;

import com.lightai.client.chat.ChatMessage;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightAiClientTest {

    @Test
    void shouldValidateBuilderConfiguration() {
        // Missing both baseUrl and definition
        assertThatThrownBy(() -> LightAiClient.builder().build())
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));

        // Missing token for standalone
        assertThatThrownBy(() -> LightAiClient.builder().baseUrl("http://localhost:8080").build())
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }

    @Test
    void shouldBuildChatRequestConveniently() {
        ChatRequest req = ChatRequest.builder()
                .model("gpt-4o")
                .addSystemMessage("You are a helpful assistant")
                .addUserMessage("Hello world")
                .addAssistantMessage("Hi!")
                .temperature(0.7)
                .maxTokens(100)
                .stream(false)
                .build();

        assertThat(req.model()).isEqualTo("gpt-4o");
        assertThat(req.messages()).hasSize(3);
        assertThat(req.messages().get(0).role()).isEqualTo("system");
        assertThat(req.messages().get(0).content()).isEqualTo("You are a helpful assistant");
        assertThat(req.messages().get(1).role()).isEqualTo("user");
        assertThat(req.messages().get(1).content()).isEqualTo("Hello world");
        assertThat(req.messages().get(2).role()).isEqualTo("assistant");
        assertThat(req.temperature()).isEqualByComparingTo("0.7");
        assertThat(req.maxTokens()).isEqualTo(100);
        assertThat(req.stream()).isFalse();

        // Check immutability
        assertThatThrownBy(() -> req.messages().add(new ChatMessage("user", "extra")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldBuildChatResponse() {
        ChatResponse resp = ChatResponse.builder()
                .id("chatcmpl-123")
                .model("gpt-4o")
                .content("Hello there!")
                .finishReason("stop")
                .usage(10, 20, 30)
                .build();

        assertThat(resp.id()).isEqualTo("chatcmpl-123");
        assertThat(resp.model()).isEqualTo("gpt-4o");
        assertThat(resp.content()).isEqualTo("Hello there!");
        assertThat(resp.finishReason()).isEqualTo("stop");
        assertThat(resp.usage()).isNotNull();
        assertThat(resp.usage().promptTokens()).isEqualTo(10);
        assertThat(resp.usage().completionTokens()).isEqualTo(20);
        assertThat(resp.usage().totalTokens()).isEqualTo(30);
    }

    @Test
    void shouldThrowClientClosedWhenClientIsClosed() {
        LightAiClient client = LightAiClient.builder()
                .baseUrl("http://localhost:8080")
                .token("test-token")
                .build();

        assertThat(client.isClosed()).isFalse();
        client.close();
        assertThat(client.isClosed()).isTrue();

        ChatRequest req = ChatRequest.builder().model("default").addUserMessage("hi").build();

        assertThatThrownBy(client::models)
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.CLIENT_CLOSED));

        assertThatThrownBy(() -> client.chat(req))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.CLIENT_CLOSED));

        assertThatThrownBy(() -> client.chatAsync(req))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.CLIENT_CLOSED));

        assertThatThrownBy(() -> client.stream(req))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> assertThat(((LightAiException) e).code()).isEqualTo(ErrorCode.CLIENT_CLOSED));
    }
}