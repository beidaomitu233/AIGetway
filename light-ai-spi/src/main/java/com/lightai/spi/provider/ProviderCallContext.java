package com.lightai.spi.provider;

import java.time.Instant;

/**
 * Provider 调用上下文（4.7.2.1/4.7.2.3）：连接配置、秘密句柄与总期限。
 * Runtime 先选定候选与凭证并创建 Attempt 后构造本上下文；
 * 不向 Adapter 提供其他 Credential 或候选列表。
 */
public record ProviderCallContext(
        ProviderConfigView config,
        ProviderChatRequest request,
        ProviderChatRequest.SecretHandle secretHandle,
        Instant deadlineAt) {
}
