package com.lightai.client.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 统一请求结构校验（BE-001，BACKEND_PLAN 2.2 / C-015）：
 * 消息角色组合、stop 边界、trace_id 字符集、metadata 限制与
 * stream_options 组合规则。
 */
class ChatRequestValidatorTest {

    private static UnifiedChatRequest request(List<ChatMessage> messages) {
        return new UnifiedChatRequest("gpt4o", messages, false, null, null,
                null, null, null, null, null, null);
    }

    private static List<String> issueFields(LightAiException e) {
        return e.issues().stream().map(FieldIssue::field).toList();
    }

    @Test
    void minimalUserMessagePasses() {
        assertThatCode(() -> ChatRequestValidator.validate(
                request(List.of(new ChatMessage("user", "你好"))), true))
                .doesNotThrowAnyException();
    }

    @Test
    void systemMustBeFirstAndAtMostOne() {
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(request(List.of(
                        new ChatMessage("user", "hi"),
                        new ChatMessage("system", "sys"))), true),
                LightAiException.class);
        assertThat(issueFields(e)).contains("messages[1]");

        LightAiException twice = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(request(List.of(
                        new ChatMessage("system", "a"),
                        new ChatMessage("system", "b"),
                        new ChatMessage("user", "hi"))), true),
                LightAiException.class);
        assertThat(issueFields(twice)).contains("messages");
    }

    @Test
    void atLeastOneUserMessageRequired() {
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(request(List.of(
                        new ChatMessage("assistant", "hi"))), true),
                LightAiException.class);
        assertThat(issueFields(e)).contains("messages");
    }

    @Test
    void unknownRoleRejected() {
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(request(List.of(
                        new ChatMessage("tool", "x"),
                        new ChatMessage("user", "hi"))), true),
                LightAiException.class);
        assertThat(issueFields(e)).contains("messages[0].role");
    }

    @Test
    void stopBoundsAndDuplication() {
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(new UnifiedChatRequest("m",
                        List.of(new ChatMessage("user", "hi")), false, null, null, null,
                        List.of("a", "a", "", "x".repeat(129), "b", "c", "d", "e"),
                        null, null, null, null), true),
                LightAiException.class);
        assertThat(issueFields(e)).contains("stop[1]", "stop[2]", "stop[3]", "stop");
    }

    @Test
    void traceIdCharacterSetEnforced() {
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(new UnifiedChatRequest("m",
                        List.of(new ChatMessage("user", "hi")), false, null, null, null, null,
                        "bad id!空间", null, null, null), true),
                LightAiException.class);
        assertThat(issueFields(e)).contains("trace_id");

        assertThatCode(() -> ChatRequestValidator.validate(new UnifiedChatRequest("m",
                List.of(new ChatMessage("user", "hi")), false, null, null, null, null,
                "trace-01_a.b", null, null, null), true))
                .doesNotThrowAnyException();
    }

    @Test
    void sensitiveTagKeysAreRejectedAndTagsBounded() {
        Map<String, String> tags = new HashMap<>();
        tags.put("authorization", "Bearer x");
        tags.put("password", "1");
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(new UnifiedChatRequest("m",
                        List.of(new ChatMessage("user", "hi")), false, null, null, null, null,
                        null, new RequestMetadata("console", null, null, null, tags), null, null),
                        true),
                LightAiException.class);
        assertThat(issueFields(e)).contains("metadata.tags[authorization]", "metadata.tags[password]");
    }

    @Test
    void streamOptionsOnlyWithStreamingBusinessRequest() {
        LightAiException off = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(new UnifiedChatRequest("m",
                        List.of(new ChatMessage("user", "hi")), false, null, null, null, null,
                        null, null, null, new StreamOptions(false)), true),
                LightAiException.class);
        assertThat(issueFields(off)).contains("stream_options");

        LightAiException admin = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(new UnifiedChatRequest("m",
                        List.of(new ChatMessage("user", "hi")), true, null, null, null, null,
                        null, null, null, new StreamOptions(false)), false),
                LightAiException.class);
        assertThat(issueFields(admin)).contains("stream_options");
    }

    @Test
    void samplingBounds() {
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ChatRequestValidator.validate(new UnifiedChatRequest("m",
                        List.of(new ChatMessage("user", "hi")), false,
                        new BigDecimal("-0.1"), new BigDecimal("0.333"), -5,
                        null, null, null, null, null), true),
                LightAiException.class);
        assertThat(issueFields(e)).contains("temperature", "top_p", "max_tokens");
    }
}
