package com.lightai.server.runtime;

import com.lightai.runtime.ports.CredentialHealthPort;
import com.lightai.storage.runtime.JdbcRuntimeStateWriter;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渠道 Key 运行健康回写（BE-224）：调用链路 429/认证失败写入共享运行状态，
 * 参与后续 Key 选择；只写健康维度，不覆盖渠道 Key 的人工配置状态。
 */
public final class JdbcCredentialHealthPort implements CredentialHealthPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcCredentialHealthPort.class);

    private final javax.sql.DataSource dataSource;
    private final JdbcRuntimeStateWriter stateWriter;

    public JdbcCredentialHealthPort(javax.sql.DataSource dataSource, JdbcRuntimeStateWriter stateWriter) {
        this.dataSource = dataSource;
        this.stateWriter = stateWriter;
    }

    @Override
    public void markRateLimited(UUID channelCredentialId, Instant resetAt, String errorCode, String summary) {
        write(connection -> stateWriter.upsertCredentialRateLimit(connection, channelCredentialId,
                resetAt == null ? null : resetAt.atOffset(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC), errorCode, summary));
    }

    @Override
    public void markAuthFailed(UUID channelCredentialId, String errorCode, String summary) {
        write(connection -> stateWriter.upsertCredentialHealth(connection, channelCredentialId,
                "INVALID", OffsetDateTime.now(ZoneOffset.UTC), errorCode, summary));
    }

    /** 健康回写失败不影响主调用链路；下一次故障仍会重试写入。 */
    private void write(SqlConsumer consumer) {
        try (Connection connection = dataSource.getConnection()) {
            consumer.accept(connection);
        } catch (Exception e) {
            log.warn("Key 健康回写失败 exception={}", e.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface SqlConsumer {
        void accept(Connection connection) throws Exception;
    }
}
