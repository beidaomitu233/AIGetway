package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/** 在线测试命令（4.6.5）：管理身份；system_message 位于首项，user_message 位于末项。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiTestCommand(
        String model,
        String systemMessage,
        String userMessage,
        boolean stream,
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxTokens) {
}
