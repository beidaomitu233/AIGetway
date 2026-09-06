package com.lightai.storage.security;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * runtime_config 管理读写端口（BE-043）：全字段草稿读取与乐观锁更新；
 * current_snapshot_no/published_at 为运行指针，不进入可编辑 DTO。
 */
public interface RuntimeConfigAdminRepository {

    Optional<RuntimeConfigRow> find(Connection connection);

    /** 更新可编辑字段与 version；timezone_locked 由服务判定透传。 */
    void update(Connection connection, RuntimeConfigRow row);

    Optional<Long> findVersion(Connection connection);

    /** 运行参数行（C类单例）。 */
    record RuntimeConfigRow(
            UUID id,
            String timezone,
            boolean timezoneLocked,
            int traceRetentionDays,
            int usageRetentionDays,
            int auditRetentionDays,
            int dashboardRefreshSeconds,
            int maxMessageChars,
            int maxRequestChars,
            boolean diagnosticSamplingEnabled,
            java.math.BigDecimal diagnosticSampleRate,
            int diagnosticSampleRetentionDays,
            int diagnosticSampleMaxChars,
            boolean clientIpRecordingEnabled,
            java.util.List<String> trustedProxyCidrs,
            int publishInstanceTimeoutSeconds,
            int instanceStaleSeconds,
            UUID defaultAliasId,
            long currentSnapshotNo,
            java.time.OffsetDateTime publishedAt,
            long version,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime updatedAt) {
    }
}
