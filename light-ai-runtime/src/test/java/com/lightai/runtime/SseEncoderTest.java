package com.lightai.runtime;

import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.UnifiedError;
import com.lightai.runtime.chat.SseEncoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE 编码语义（BE-028）：块序列连续、[DONE] 分隔、错误无 DONE。 */
class SseEncoderTest {

    @Test
    void chunkEncodesAsDataLine() {
        UnifiedChatChunk chunk = new UnifiedChatChunk("t-1", "chat.completion.chunk", 100, "alias",
                java.util.List.of(new UnifiedChatChunk.ChunkChoice(0,
                        new UnifiedChatChunk.Delta("assistant", null), null)), null,
                new UnifiedChatChunk.ChunkTraceInfo("t-1", 0, null, null, null));
        String encoded = SseEncoder.chunk(chunk);
        assertThat(encoded).startsWith("data: {").endsWith("\n\n");
        assertThat(encoded).contains("\"role\":\"assistant\"");
        assertThat(encoded).doesNotContain("DONE");
    }

    @Test
    void doneIsSeparatorNotJson() {
        assertThat(SseEncoder.done()).isEqualTo("data: [DONE]\n\n");
    }

    @Test
    void errorEncodesEnvelopeWithoutDone() {
        UnifiedError error = UnifiedError.builder(ErrorCode.STREAM_INTERRUPTED, "流式输出中断")
                .traceId("t-2").build();
        String encoded = SseEncoder.error(error);
        assertThat(encoded).startsWith("data: {");
        assertThat(encoded).contains("STREAM_INTERRUPTED");
        assertThat(encoded).doesNotContain("DONE");
    }
}
