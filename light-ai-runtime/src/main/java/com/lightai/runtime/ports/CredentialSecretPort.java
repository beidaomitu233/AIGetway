package com.lightai.runtime.ports;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.provider.ProviderChatRequest.SecretHandle;
import java.util.function.Supplier;

/**
 * 凭证秘密解析端口（BE-P04 CredentialSelector / SecretProvider 接线）：
 * 返回短期句柄，Adapter 只能在构造认证材料的最小作用域内读取。
 */
public interface CredentialSecretPort {

    /** 解析池内一个可用凭证的秘密句柄；failoverIndex 供凭证级 Failover 选择下一凭证。 */
    ResolvedCredential resolve(String channelId, int failoverIndex);

    /**
     * 已选中的渠道 Key：id 供 Attempt 与限流维度绑定，maskedValue 供时间线展示
     * （掩码快照，不含原文）；实现无法提供掩码时为 null。
     */
    record ResolvedCredential(String channelCredentialId, SecretHandle secretHandle, String maskedValue) {

        public ResolvedCredential(String channelCredentialId, SecretHandle secretHandle) {
            this(channelCredentialId, secretHandle, null);
        }
    }

    static CredentialSecretPort inMemory(java.util.Map<String, String> poolSecrets) {
        return (channelId, failoverIndex) -> new ResolvedCredential(channelId + "-credential-" + failoverIndex,
                () -> {
                    String secret = poolSecrets.get(channelId);
                    if (secret == null) {
                        throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE, "当前候选的凭证池没有可用 Credential");
                    }
                    char[] chars = secret.toCharArray();
                    return chars;
                });
    }
}
