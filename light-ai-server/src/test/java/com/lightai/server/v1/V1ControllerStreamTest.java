package com.lightai.server.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ModelsService;
import com.lightai.runtime.ports.AccessTokenPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class V1ControllerStreamTest {
    @Test void doneIsSentOnlyAfterRuntimeCompletes() throws Exception {
        ChatPipeline pipeline = mock(ChatPipeline.class);
        AccessTokenPort tokens = mock(AccessTokenPort.class);
        when(tokens.authenticate(any(), any())).thenReturn(new AccessTokenPort.Principal("app", List.of()));
        AtomicReference<ChatPipeline.StreamListener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(1));
            return null;
        }).when(pipeline).chatStream(any(), any());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new V1Controller(mock(ModelsService.class), pipeline, tokens)).build();

        MvcResult pending = mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(pending.getResponse().getContentAsString()).doesNotContain("[DONE]");

        listener.get().onComplete();
        MvcResult completed = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
        assertThat(completed.getResponse().getContentAsString()).contains("[DONE]");
    }
}