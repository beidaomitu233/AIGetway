package com.lightai.server.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ModelsService;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.CredentialHealthPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.runtime.ports.RoutingPort;
import com.lightai.runtime.capacity.CapacityStore.CapacityLimitedException;
import com.lightai.runtime.chat.ChatPipeline.ChatContext;
import com.lightai.runtime.trace.InMemoryTraceStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BE-221/BE-222 统一错误契约：错误体注入请求关联 request_id，
 * 容量受限 429 携带 Retry-After 响应头与维度信息，错误禁缓存。
 */
class V1ErrorContractTest {

    @Test
    void errorBodyCarriesRequestIdFromFilterAndCallerHeader() throws Exception {
        ChatPipeline pipeline = mock(ChatPipeline.class);
        AccessTokenPort tokens = mock(AccessTokenPort.class);
        when(tokens.authenticate(any(), any())).thenReturn(new AccessTokenPort.Principal("app", List.of()));
        when(pipeline.chat(any(ChatContext.class)))
                .thenThrow(new LightAiException(ErrorCode.ACCESS_DENIED, "应用未授权访问该模型"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new V1Controller(mock(ModelsService.class), pipeline, tokens))
                .setControllerAdvice(new V1ErrorHandler())
                .addFilters(new com.lightai.admin.web.RequestIdFilter())
                .build();

        MvcResult result = mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .header("X-Request-Id", "req-caller-123")
                        .contentType("application/json")
                        .content("{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("req-caller-123");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"request_id\":\"req-caller-123\"");
        assertThat(body).contains("ACCESS_DENIED");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void capacityLimitedSurfacesRetryAfterHeaderAndDimension() throws Exception {
        // 真实管线在容量受限（非 Key 作用域）路径返回携带 retry_after 的 429
        ConfigSnapshotPort snapshots = () -> new ConfigSnapshotPort.ActiveSnapshot(7, List.of(
                new ConfigSnapshotPort.AliasView("alias-1", "assistant", "助理", true, List.of(
                        candidate()))));
        ChatPipeline realPipeline = new ChatPipeline(
                snapshots, () -> Optional.empty(),
                (alias, request, estimatedInput) -> new RoutingPort.RoutingResult(
                        alias.enabledCandidates(), false, false),
                new CapacityPort() {
                    @Override
                    public CapacityPort.Reservation reserve(String aliasId, String modelId,
                                                            String credentialId, long estimatedTokens) {
                        throw new CapacityLimitedException("APPLICATION", "RPM");
                    }

                    @Override public void settle(String reservationId, long in, long out) { }
                    @Override public void release(String reservationId) { }
                },
                null,
                (channelId, index) -> new CredentialSecretPort.ResolvedCredential(
                        java.util.UUID.randomUUID().toString(), () -> "sk-test".toCharArray()),
                null, type -> Optional.empty(), new InMemoryTraceStore(),
                com.lightai.runtime.ports.ApplicationQuotaPort.unlimited(),
                () -> com.lightai.runtime.chat.ReliabilityBudgets.DEFAULT, 30_000,
                CredentialHealthPort.noop());
        AccessTokenPort tokens = mock(AccessTokenPort.class);
        when(tokens.authenticate(any(), any())).thenReturn(new AccessTokenPort.Principal("app", List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new V1Controller(mock(ModelsService.class), realPipeline, tokens))
                .setControllerAdvice(new V1ErrorHandler())
                .addFilters(new com.lightai.admin.web.RequestIdFilter())
                .build();

        MvcResult result = mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer token")
                        .contentType("application/json")
                        .content("{\"model\":\"assistant\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isStrictlyBetween(0L, 61L);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("CAPACITY_LIMITED");
        assertThat(body).contains("APPLICATION");
        assertThat(body).contains("RPM");
    }

    private static ConfigSnapshotPort.CandidateView candidate() {
        return new ConfigSnapshotPort.CandidateView(
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                "OPENAI", java.util.UUID.randomUUID().toString(), "model-a",
                1, 1, true, "FAKE", 8000L, 512L, true, true, true, true, true,
                null, null, null, null, 4, null, null, null,
                "0.00000015", "0.00000060", 1000, "USD",
                "https://provider.test/v1", null, 3000, 120000, java.util.Map.of());
    }
}
