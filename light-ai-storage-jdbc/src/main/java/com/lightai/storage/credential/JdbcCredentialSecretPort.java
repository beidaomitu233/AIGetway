package com.lightai.storage.credential;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.spi.secret.SecretCipher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/** Resolves encrypted JDBC credentials for runtime provider calls. */
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
            List<CredentialRecord> credentials = credentialRepository.listSelectableByPool(
                    connection, poolUuid, "weight DESC, name ASC", 200, 0);
            if (credentials.isEmpty()) {
                throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                        "凭证池没有可用 Credential: " + poolId);
            }
            CredentialRecord chosen = credentials.get(Math.floorMod(failoverIndex, credentials.size()));
            byte[] ciphertext = secretRepository.findByCredential(connection, chosen.id())
                    .orElseThrow(() -> new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                            "Credential 缺少秘密记录: " + chosen.id()))
                    .secretCiphertext();
            char[] secret = secretCipher.decrypt(ciphertext)
                    .orElseThrow(() -> new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                            "Credential 秘密解密失败"));
            return new ResolvedCredential(chosen.id().toString(), () -> secret);
        } catch (LightAiException e) {
            log.log(System.Logger.Level.WARNING, "凭证解析被拒绝 pool_id={0} code={1}",
                    poolId, e.code().name());
            throw e;
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "凭证解析失败 pool_id={0} exception={1}",
                    poolId, e.getClass().getSimpleName());
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
