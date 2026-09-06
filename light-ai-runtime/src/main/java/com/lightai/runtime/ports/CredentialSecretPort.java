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
    ResolvedCredential resolve(String poolId, int failoverIndex);

    record ResolvedCredential(String credentialId, SecretHandle secretHandle) {
    }

    static CredentialSecretPort inMemory(java.util.Map<String, String> poolSecrets) {
        return (poolId, failoverIndex) -> new ResolvedCredential(poolId + "-credential-" + failoverIndex,
                () -> {
                    String secret = poolSecrets.get(poolId);
                    if (secret == null) {
                        throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE, "当前候选的凭证池没有可用 Credential");
                    }
                    char[] chars = secret.toCharArray();
                    return chars;
                });
    }
}
