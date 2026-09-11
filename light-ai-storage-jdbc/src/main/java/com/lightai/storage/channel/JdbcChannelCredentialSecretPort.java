package com.lightai.storage.channel;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.spi.secret.SecretCipher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * 渠道 Key 秘密解析端口（BE-P04 CredentialSelector / SecretProvider 接线）。
 * V2：密钥密文与渠道 Key 同行，无独立保护表；解密发生在端口内部，
 * 返回值只为短期句柄，Adapter 仅在构造认证材料的最小作用域内读取。
 */
public final class JdbcChannelCredentialSecretPort implements CredentialSecretPort {

    private static final System.Logger log =
            System.getLogger(JdbcChannelCredentialSecretPort.class.getName());
    private static final int SELECT_LIMIT = 200;

    private final DataSource dataSource;
    private final JdbcChannelCredentialRepository credentialRepository;
    private final SecretCipher secretCipher;

    public JdbcChannelCredentialSecretPort(DataSource dataSource,
                                           JdbcChannelCredentialRepository credentialRepository,
                                           SecretCipher secretCipher) {
        this.dataSource = dataSource;
        this.credentialRepository = credentialRepository;
        this.secretCipher = secretCipher;
    }

    @Override
    public ResolvedCredential resolve(String channelId, int failoverIndex) {
        UUID channelUuid = parse(channelId);
        if (channelUuid == null) {
            throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE, "渠道 ID 不合法");
        }
        try (Connection connection = dataSource.getConnection()) {
            List<ChannelCredentialRecord> credentials = credentialRepository.listSelectableByChannel(
                    connection, channelUuid, "priority ASC, weight DESC", SELECT_LIMIT, 0);
            if (credentials.isEmpty()) {
                throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                        "渠道没有可调度的 Key: " + channelId);
            }
            ChannelCredentialRecord chosen = credentials.get(Math.floorMod(failoverIndex, credentials.size()));
            byte[] ciphertext = chosen.secretRefCiphertext() != null
                    ? chosen.secretRefCiphertext() : chosen.secretCiphertext();
            if (ciphertext == null) {
                throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                        "渠道 Key 缺少密钥密文: " + chosen.id());
            }
            char[] secret = secretCipher.decrypt(ciphertext)
                    .orElseThrow(() -> new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                            "渠道 Key 解密失败"));
            return new ResolvedCredential(chosen.id().toString(), () -> secret);
        } catch (LightAiException e) {
            log.log(System.Logger.Level.WARNING, "渠道 Key 解析被拒绝 channel_id={0} code={1}",
                    channelId, e.code().name());
            throw e;
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "渠道 Key 解析失败 channel_id={0} exception={1}",
                    channelId, e.getClass().getSimpleName());
            throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                    "渠道 Key 解析失败: " + e.getClass().getSimpleName());
        }
    }

    private static UUID parse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
