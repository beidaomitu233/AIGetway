package com.lightai.spi.provider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Provider Chat 请求（4.7.2.3）：Runtime 已完成候选过滤与参数解析，
 * Adapter 只做协议转换与一次外部调用。协议级字段不含连接配置与凭证
 * （见 ProviderCallContext）。
 *
 * @param modelId         外部模型 ID（保持大小写）
 * @param systemMessage   顶级 system 消息（Gemini/Anthropic 语义），可空
 * @param messages        user/assistant 轮次（不含 system）
 * @param maxTokens       解析后的输出上限（必填）
 * @param temperature     可空采样参数
 * @param topP            可空采样参数
 * @param stop            停止序列（去重，≤4，各≤128）
 * @param providerOptions 仅保留与当前 Adapter 匹配 key 的受控选项
 */
public record ProviderChatRequest(
        String modelId,
        String systemMessage,
        List<ChatTurn> messages,
        long maxTokens,
        BigDecimal temperature,
        BigDecimal topP,
        List<String> stop,
        Map<String, Object> providerOptions) {

    public ProviderChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        stop = stop == null ? List.of() : List.copyOf(stop);
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(providerOptions);
    }

    /** 凭证秘密句柄：读取方负责在使用后清空返回的字符数组。 */
    @FunctionalInterface
    public interface SecretHandle {
        char[] readSecret();
    }

    /** user/assistant 轮次。 */
    public record ChatTurn(String role, String content) {

        public static ChatTurn user(String content) {
            return new ChatTurn("user", content);
        }

        public static ChatTurn assistant(String content) {
            return new ChatTurn("assistant", content);
        }
    }
}
