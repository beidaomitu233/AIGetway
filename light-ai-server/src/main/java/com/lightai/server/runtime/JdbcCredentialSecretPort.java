package com.lightai.server.runtime;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.storage.credential.CredentialRecord;
import com.lightai.storage.credential.JdbcCredentialRepository;
import com.lightai.storage.credential.JdbcCredentialSecretRepository;
import com.lightai.spi.secret.SecretCipher;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC 凭证秘密端口（BE-020/BE-P04 接线）：
 * 按池内启用凭证的权重顺序解析第 failoverIndex 个凭证；
 * 秘密经 AES-GCM 解密为短期句柄，仅允许 Adapter 在构造认证头时读取一次；
 * 无可用凭证抛 CREDENTIAL_NOT_AVAILABLE，不伪造 Attempt。
 */
public final class JdbcCredentialSecretPort implements CredentialSecretPort {

    private static final System.Logger log = System.getLogger(JdbcCredentialSecretPort.class.getName());

    private final DataSource dataSource;
    private final JdbcCredentialRepository credentialRepository;
    private final JdbcCredentialSecretRepository secretRepository;
    private final SecretCipher secretCipher;

    public JdbcCredentialSecretPort(DataSource dataSource,
                                    JdbcCredentialRepository credentialRepository,
                                    JdbcCredentialSecretRepository secretRepository,
                                    SecretCipher secretCipher) {
        this.dataSource = dataSource;
        this.credentialRepository = credentialRepository;
        this.secretRepository = secretRepository;
        this.secretCipher = secretCipher;
    }

    @Override
    public ResolvedCredential resolve(String poolId, int failoverIndex) {
        UUID poolUuid = parse(poolId);
        if (poolUuid == null) {
            throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE, "凭证池 ID 不合法");
        }
        try (Connection connection = dataSource.getConnection()) {
            // PRD：HEALTHY 优先，UNKNOWN 可参与但排在其后；不可用与未过复位时间的 RATE_LIMITED 不参与。
            List<CredentialRecord> credentials = credentialRepository.listSelectableByPool(
                    connection, poolUuid, "weight DESC, name ASC", 200, 0);
            if (credentials.isEmpty()) {
                throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                        "凭证池没有可用 Credential: " + poolId);
            }
            int index = Math.floorMod(failoverIndex, credentials.size());
            CredentialRecord chosen = credentials.get(index);
            byte[] ciphertext = secretRepository.findByCredential(connection, chosen.id())
                    .orElseThrow(() -> new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                            "Credential 缺少秘密记录: " + chosen.id()))
                    .secretCiphertext();
            char[] secret = secretCipher.decrypt(ciphertext)
                    .orElseThrow(() -> new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                            "Credential 秘密解密失败"));
            return new ResolvedCredential(chosen.id().toString(), () -> secret);
        } catch (LightAiException e) {
            log.log(System.Logger.Level.WARNING, "凭证解析被拒绝 pool_id={0} reason={1}",
                    poolId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "凭证解析失败 pool_id={0} exception={1}: {2}",
                    poolId, e.getClass().getSimpleName(), e.getMessage());
            throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                    "凭证解析失败: " + e.getClass().getSimpleName());
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
