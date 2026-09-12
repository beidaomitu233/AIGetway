package com.lightai.server.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ModelsService;
import com.lightai.runtime.ports.AccessTokenPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * FS-202：OpenAI 兼容请求解析。stream 为可选项，缺省（或显式 null）按非流式处理；
 * 该缺陷曾使标准 OpenAI 客户端（不带 stream）在业务校验前被 400 拒绝。
 */
class V1ChatRequestParsingTest {

    private static MockMvc mvc(ChatPipeline pipeline, AccessTokenPort tokens) {
        return MockMvcBuilders.standaloneSetup(
                        new V1Controller(mock(ModelsService.class), pipeline, tokens))
                .setControllerAdvice(new V1ErrorHandler())
                .build();
    }

    private static AccessTokenPort tokens() {
        AccessTokenPort tokens = mock(AccessTokenPort.class);
        when(tokens.authenticate(any(), any()))
                .thenReturn(new AccessTokenPort.Principal("app", List.of()));
        return tokens;
    }

    private static UnifiedChatResponse response() {
        return new UnifiedChatResponse("trace-1", "chat.completion", 0L, "vm-1",
                List.of(), null, null);
    }

    @Test
    void missingStreamIsTreatedAsNonStreaming() throws Exception {
        ChatPipeline pipeline = mock(ChatPipeline.class);
        when(pipeline.chat(any(ChatPipeline.ChatContext.class))).thenReturn(response());
        MockMvc mvc = mvc(pipeline, tokens());

        MvcResult result = mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"model\":\"vm-1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<ChatPipeline.ChatContext> captor =
                ArgumentCaptor.forClass(ChatPipeline.ChatContext.class);
        verify(pipeline).chat(captor.capture());
        verify(pipeline, never()).chatStream(any(), any());
        UnifiedChatRequest parsed = captor.getValue().request();
        assertThat(parsed.stream()).isFalse();
        assertThat(parsed.model()).isEqualTo("vm-1");
    }

    @Test
    void explicitNullStreamIsTreatedAsNonStreaming() throws Exception {
        ChatPipeline pipeline = mock(ChatPipeline.class);
        when(pipeline.chat(any(ChatPipeline.ChatContext.class))).thenReturn(response());

        MvcResult result = mvc(pipeline, tokens()).perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"model\":\"vm-1\",\"stream\":null,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        verify(pipeline, never()).chatStream(any(), any());
    }

    @Test
    void streamTrueStillUsesStreamingPath() throws Exception {
        ChatPipeline pipeline = mock(ChatPipeline.class);
        MockMvc mvc = mvc(pipeline, tokens());

        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"model\":\"vm-1\",\"stream\":true,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(request().asyncStarted());

        verify(pipeline, never()).chat(any(ChatPipeline.ChatContext.class));
    }

    @Test
    void unknownFieldIsStillRejected() throws Exception {
        MvcResult result = mvc(mock(ChatPipeline.class), tokens()).perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"model\":\"vm-1\",\"messages\":[],\"unexpected\":1}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("UNKNOWN_FIELD");
    }

    @Test
    void malformedMessagesAreStillRejected() throws Exception {
        MvcResult result = mvc(mock(ChatPipeline.class), tokens()).perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"model\":\"vm-1\",\"messages\":\"not-a-list\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("FIELD_VALIDATION_FAILED");
    }
}
