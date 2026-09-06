package com.lightai.admin.runtimeconfig;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.security.RetentionImpactCommand;
import com.lightai.client.security.RetentionImpactResult;
import com.lightai.client.security.RuntimeConfigUpdateCommand;
import com.lightai.client.security.RuntimeConfigView;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.security.RetentionImpactRepository;
import com.lightai.storage.security.RuntimeConfigAdminRepository;
import com.lightai.storage.security.RuntimeConfigAdminRepository.RuntimeConfigRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 运行参数服务（BE-043）：完整可编辑对象 + version 提交；
 * 范围校验按 DATABASE_PLAN runtime_config；timezone 首次聚合锁定后不可逆；
 * 缩短留存必须回传有效影响确认票据（绑定目标值 + revision，10 分钟）。
 */
public class RuntimeConfigAdminService {

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final RuntimeConfigAdminRepository repository;
    private final AuditService auditService;
    private final Clock clock;
    private final Supplier<Long> draftRevisionSupplier;
    private final String sourceMode;
    private final DraftWriteService draftWriteService;

    public RuntimeConfigAdminService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                     RuntimeConfigAdminRepository repository, AuditService auditService,
                                     Clock clock, Supplier<Long> draftRevisionSupplier,
                                     String sourceMode, DraftWriteService draftWriteService) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
        this.draftRevisionSupplier = draftRevisionSupplier;
        this.sourceMode = sourceMode;
        this.draftWriteService = draftWriteService;
    }

    public RuntimeConfigAdminService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                     RuntimeConfigAdminRepository repository, AuditService auditService,
                                     Clock clock, Supplier<Long> draftRevisionSupplier,
                                     String sourceMode) {
        this(dataSource, transactionManager, repository, auditService, clock, draftRevisionSupplier, sourceMode, null);
    }

    public RuntimeConfigView get(RequestContext context) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.RUNTIME_CONFIG_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            RuntimeConfigRow row = repository.find(connection)
                    .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "运行参数尚未初始化"));
            return toView(row);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "运行参数读取失败");
        }
    }

    /** 保留影响估算：票据 10 分钟有效，绑定目标值与当前草稿修订。 */
    public RetentionImpactResult retentionImpact(RequestContext context, RetentionImpactCommand command) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.RUNTIME_CONFIG_MANAGE);
        validateRetention(command);
        long revision = draftRevisionSupplier.get();
        UUID impactVersion = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expires = now.plusMinutes(10);
        RetentionImpactResult.TargetValues targetValues = new RetentionImpactResult.TargetValues(
                command.traceRetentionDays(),
                command.usageRetentionDays(),
                command.auditRetentionDays(),
                command.diagnosticSampleRetentionDays());

        long traceCount = 0;
        long usageCount = 0;
        long auditCount = 0;
        long sampleCount = 0;
        OffsetDateTime earliestRemainingAt = null;

        try (Connection connection = dataSource.getConnection()) {
            traceCount = countOlderThan(connection, "trace", "started_at", now.minusDays(command.traceRetentionDays()));
            usageCount = countOlderThan(connection, "usage_aggregate", "bucket_end", now.minusDays(command.usageRetentionDays()));
            auditCount = countOlderThan(connection, "audit_log", "created_at", now.minusDays(command.auditRetentionDays()));
            sampleCount = countOlderThan(connection, "trace_content_sample", "created_at", now.minusDays(command.diagnosticSampleRetentionDays()));
            earliestRemainingAt = findEarliestAfter(connection, "trace", "started_at", now.minusDays(command.traceRetentionDays()));

            String targetJson = String.format(
                    "{\"trace_retention_days\":%d,\"usage_retention_days\":%d,\"audit_retention_days\":%d,\"diagnostic_sample_retention_days\":%d}",
                    command.traceRetentionDays(), command.usageRetentionDays(), command.auditRetentionDays(), command.diagnosticSampleRetentionDays());
            String countsJson = String.format(
                    "{\"trace\":%d,\"usage\":%d,\"audit\":%d,\"sample\":%d}",
                    traceCount, usageCount, auditCount, sampleCount);

            transaction.executeWithoutResult(status -> new RetentionImpactRepositoryAccess()
                    .save(connection, new RetentionImpactRepository.RetentionImpactRecord(
                            UUID.randomUUID(), impactVersion, revision, targetJson, countsJson, now, expires,
                            operatorId(context))));
            return new RetentionImpactResult(impactVersion.toString(), now, expires, targetValues,
                    new RetentionImpactResult.Counts(traceCount, usageCount, auditCount, sampleCount), earliestRemainingAt);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "保留影响估算失败");
        }
    }

    private long countOlderThan(Connection connection, String table, String column, OffsetDateTime cutoff) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " < ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(cutoff.toInstant()));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private OffsetDateTime findEarliestAfter(Connection connection, String table, String column, OffsetDateTime cutoff) {
        String sql = "SELECT MIN(" + column + ") FROM " + table + " WHERE " + column + " >= ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(cutoff.toInstant()));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts != null) {
                        return OffsetDateTime.ofInstant(ts.toInstant(), clock.getZone());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 影响确认仓储访问的薄封装（保持事务边界在服务内）。 */
    final class RetentionImpactRepositoryAccess {
        private final RetentionImpactRepository repository =
                new com.lightai.storage.security.JdbcRetentionImpactRepository();

        void save(Connection connection,
                  RetentionImpactRepository.RetentionImpactRecord record) {
            repository.insert(connection, record);
        }

        Optional<RetentionImpactRepository.RetentionImpactRecord> find(
                Connection connection, UUID impactVersion) {
            return repository.find(connection, impactVersion);
        }
    }

    public ManagementOperationResult<RuntimeConfigView> put(RequestContext context, RuntimeConfigUpdateCommand command) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.RUNTIME_CONFIG_MANAGE);
        validate(command);
        RuntimeConfigRow current;
        try (Connection connection = dataSource.getConnection()) {
            current = repository.find(connection)
                    .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "运行参数尚未初始化"));
            if (current.version() != command.version()) {
                throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                        null, context.requestId(), null, current.version(), null, null);
            }
            // timezone 锁定后不可逆
            if (current.timezoneLocked() && !current.timezone().equals(command.timezone())) {
                throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                        "timezone 已锁定，不能修改", "timezone");
            }
            boolean shortened = command.traceRetentionDays() < current.traceRetentionDays()
                    || command.usageRetentionDays() < current.usageRetentionDays()
                    || command.auditRetentionDays() < current.auditRetentionDays()
                    || command.diagnosticSampleRetentionDays() < current.diagnosticSampleRetentionDays();
            if (shortened) {
                verifyImpactTicket(current, command);
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "运行参数校验失败");
        }

        if (draftWriteService != null) {
            DraftWriteCommand writeCommand = new DraftWriteCommand(
                    context.requestId(),
                    operatorId(context),
                    sourceMode,
                    context.sourceIpMasked(),
                    "UPDATE",
                    "runtime_config",
                    current.id().toString(),
                    command.version(),
                    conn -> repository.find(conn).map(RuntimeConfigRow::version).orElse(null),
                    conn -> {
                        RuntimeConfigRow currentInTx = repository.find(conn)
                                .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "运行参数尚未初始化"));
                        if (currentInTx.version() != command.version()) {
                            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                                    null, context.requestId(), null, currentInTx.version(), null, null);
                        }
                        if (currentInTx.timezoneLocked() && !currentInTx.timezone().equals(command.timezone())) {
                            throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "timezone 已锁定，不能修改", "timezone");
                        }
                        OffsetDateTime now = OffsetDateTime.now(clock);
                        RuntimeConfigRow updated = new RuntimeConfigRow(
                                currentInTx.id(), command.timezone(), currentInTx.timezoneLocked(),
                                command.traceRetentionDays(), command.usageRetentionDays(), command.auditRetentionDays(),
                                command.dashboardRefreshSeconds(), command.maxMessageChars(), command.maxRequestChars(),
                                command.diagnosticSamplingEnabled(), command.diagnosticSamplingEnabled()
                                ? command.diagnosticSampleRate() : BigDecimal.ZERO,
                                command.diagnosticSampleRetentionDays(), command.diagnosticSampleMaxChars(),
                                command.clientIpRecordingEnabled(),
                                command.trustedProxyCidrs() == null ? List.of() : List.copyOf(command.trustedProxyCidrs()),
                                command.publishInstanceTimeoutSeconds(), command.instanceStaleSeconds(),
                                currentInTx.defaultAliasId(), currentInTx.currentSnapshotNo(), currentInTx.publishedAt(),
                                command.version() + 1, currentInTx.createdAt(), now);
                        if (!repository.updateIfVersionMatches(conn, updated, command.version())) {
                            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象已被其他请求更新，请刷新后重试",
                                    null, context.requestId(), null, command.version() + 1, null, null);
                        }
                        List<FieldChange> diffs = computeChanges(currentInTx, command);
                        return new DraftEntityChange("runtime_config", currentInTx.id(), "runtime_config", "UPDATE",
                                command.version() + 1, diffs);
                    });
            DraftWriteResult dwResult = draftWriteService.execute(writeCommand);
            RuntimeConfigView view = get(context);
            return new ManagementOperationResult<>(current.id().toString(), dwResult.entityVersion(), view, true,
                    dwResult.draftRevision(), context.requestId());
        }

        transaction.executeWithoutResult(status -> {
            try {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                RuntimeConfigRow currentInTx = repository.find(connection)
                        .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "运行参数尚未初始化"));
                if (currentInTx.version() != command.version()) {
                    throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                            null, context.requestId(), null, currentInTx.version(), null, null);
                }
                if (currentInTx.timezoneLocked() && !currentInTx.timezone().equals(command.timezone())) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "timezone 已锁定，不能修改", "timezone");
                }
                OffsetDateTime now = OffsetDateTime.now(clock);
                RuntimeConfigRow updated = new RuntimeConfigRow(
                        currentInTx.id(), command.timezone(), currentInTx.timezoneLocked(),
                        command.traceRetentionDays(), command.usageRetentionDays(), command.auditRetentionDays(),
                        command.dashboardRefreshSeconds(), command.maxMessageChars(), command.maxRequestChars(),
                        command.diagnosticSamplingEnabled(), command.diagnosticSamplingEnabled()
                        ? command.diagnosticSampleRate() : BigDecimal.ZERO,
                        command.diagnosticSampleRetentionDays(), command.diagnosticSampleMaxChars(),
                        command.clientIpRecordingEnabled(),
                        command.trustedProxyCidrs() == null ? List.of() : List.copyOf(command.trustedProxyCidrs()),
                        command.publishInstanceTimeoutSeconds(), command.instanceStaleSeconds(),
                        currentInTx.defaultAliasId(), currentInTx.currentSnapshotNo(), currentInTx.publishedAt(),
                        command.version() + 1, currentInTx.createdAt(), now);
                if (!repository.updateIfVersionMatches(connection, updated, command.version())) {
                    throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象已被其他请求更新，请刷新后重试",
                            null, context.requestId(), null, command.version() + 1, null, null);
                }
                List<FieldChange> diffs = computeChanges(currentInTx, command);
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context), "UPDATE", "RUNTIME_CONFIG", null,
                        diffs, sourceMode, context.sourceIpMasked()));
            } catch (LightAiException e) {
                throw e;
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "运行参数写入失败");
            }
        });
        RuntimeConfigView view = get(context);
        return new ManagementOperationResult<>(current.id().toString(), command.version() + 1, view, false, null,
                context.requestId());
    }

    /** 票据校验：存在、未过期、修订未变、目标值一致；否则 RETENTION_IMPACT_EXPIRED。 */
    private void verifyImpactTicket(RuntimeConfigRow current, RuntimeConfigUpdateCommand command) {
        if (command.confirmedImpactVersion() == null || command.confirmedImpactVersion().isBlank()) {
            throw new LightAiException(ErrorCode.RETENTION_IMPACT_EXPIRED,
                    "缩短留存需要有效的影响确认票据", "confirmed_impact_version");
        }
        RetentionImpactRepositoryAccess access = new RetentionImpactRepositoryAccess();
        UUID ticket;
        try {
            ticket = UUID.fromString(command.confirmedImpactVersion());
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.RETENTION_IMPACT_EXPIRED, "影响确认票据不合法",
                    "confirmed_impact_version");
        }
        try (Connection connection = dataSource.getConnection()) {
            Optional<RetentionImpactRepository.RetentionImpactRecord> recordOpt =
                    access.find(connection, ticket);
            if (recordOpt.isEmpty() || recordOpt.get().expiresAt().isBefore(OffsetDateTime.now(clock))) {
                throw new LightAiException(ErrorCode.RETENTION_IMPACT_EXPIRED, "影响确认票据已过期，请重新估算",
                        "confirmed_impact_version");
            }
            RetentionImpactRepository.RetentionImpactRecord record = recordOpt.get();
            long currentRevision = draftRevisionSupplier.get();
            if (record.draftRevision() != currentRevision) {
                throw new LightAiException(ErrorCode.RETENTION_IMPACT_EXPIRED, "草稿修订已变化，请重新估算",
                        "confirmed_impact_version");
            }
            String targetJson = record.targetValuesJson();
            if (!targetJson.contains("\"trace_retention_days\":" + command.traceRetentionDays())
                    || !targetJson.contains("\"usage_retention_days\":" + command.usageRetentionDays())
                    || !targetJson.contains("\"audit_retention_days\":" + command.auditRetentionDays())
                    || !targetJson.contains("\"diagnostic_sample_retention_days\":" + command.diagnosticSampleRetentionDays())) {
                throw new LightAiException(ErrorCode.RETENTION_IMPACT_EXPIRED, "目标参数已变化，请重新估算",
                        "confirmed_impact_version");
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响确认票据读取失败");
        }
    }

    /** 范围校验（DATABASE_PLAN runtime_config）。 */
    private void validate(RuntimeConfigUpdateCommand command) {
        List<FieldIssue> issues = new ArrayList<>();
        if (command.timezone() == null || command.timezone().isBlank()) {
            issues.add(new FieldIssue("timezone", "REQUIRED", "timezone 必填"));
        }
        if (command.traceRetentionDays() < 1 || command.traceRetentionDays() > 365) {
            issues.add(new FieldIssue("trace_retention_days", "RANGE", "范围 1—365"));
        }
        if (command.usageRetentionDays() < 30 || command.usageRetentionDays() > 3650
                || command.usageRetentionDays() < command.traceRetentionDays()) {
            issues.add(new FieldIssue("usage_retention_days", "RANGE", "范围 30—3650 且不少于 Trace"));
        }
        if (command.auditRetentionDays() < 365 || command.auditRetentionDays() > 3650) {
            issues.add(new FieldIssue("audit_retention_days", "RANGE", "范围 365—3650"));
        }
        if (command.dashboardRefreshSeconds() < 10 || command.dashboardRefreshSeconds() > 300) {
            issues.add(new FieldIssue("dashboard_refresh_seconds", "RANGE", "范围 10—300"));
        }
        if (command.maxMessageChars() < 1000 || command.maxMessageChars() > 1000000) {
            issues.add(new FieldIssue("max_message_chars", "RANGE", "范围 1000—1000000"));
        }
        if (command.maxRequestChars() < command.maxMessageChars() || command.maxRequestChars() > 5000000) {
            issues.add(new FieldIssue("max_request_chars", "RANGE", "不少于 message 且 ≤5000000"));
        }
        if (command.diagnosticSamplingEnabled()) {
            if (command.diagnosticSampleRate() == null
                    || command.diagnosticSampleRate().signum() < 0
                    || command.diagnosticSampleRate().compareTo(BigDecimal.ONE) > 0) {
                issues.add(new FieldIssue("diagnostic_sample_rate", "RANGE", "范围 0—1"));
            }
        } else if (command.diagnosticSampleRate() != null
                && command.diagnosticSampleRate().signum() != 0) {
            issues.add(new FieldIssue("diagnostic_sample_rate", "RANGE", "关闭采样时必须为 0"));
        }
        if (command.diagnosticSampleRetentionDays() < 1 || command.diagnosticSampleRetentionDays() > 30
                || command.diagnosticSampleRetentionDays() > command.traceRetentionDays()) {
            issues.add(new FieldIssue("diagnostic_sample_retention_days", "RANGE", "范围 1—30 且不超过 Trace"));
        }
        if (command.diagnosticSampleMaxChars() < 100 || command.diagnosticSampleMaxChars() > 10000) {
            issues.add(new FieldIssue("diagnostic_sample_max_chars", "RANGE", "范围 100—10000"));
        }
        if (command.trustedProxyCidrs() != null && command.trustedProxyCidrs().size() > 100) {
            issues.add(new FieldIssue("trusted_proxy_cidrs", "RANGE", "最多 100 项"));
        }
        if (command.publishInstanceTimeoutSeconds() < 10 || command.publishInstanceTimeoutSeconds() > 300) {
            issues.add(new FieldIssue("publish_instance_timeout_seconds", "RANGE", "范围 10—300"));
        }
        if (command.instanceStaleSeconds() < 30 || command.instanceStaleSeconds() > 600) {
            issues.add(new FieldIssue("instance_stale_seconds", "RANGE", "范围 30—600"));
        }
        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "运行参数校验失败", issues);
        }
    }

    private void validateRetention(RetentionImpactCommand command) {
        List<FieldIssue> issues = new ArrayList<>();
        if (command.traceRetentionDays() < 1 || command.traceRetentionDays() > 365) {
            issues.add(new FieldIssue("trace_retention_days", "RANGE", "范围 1—365"));
        }
        if (command.usageRetentionDays() < 30 || command.usageRetentionDays() > 3650
                || command.usageRetentionDays() < command.traceRetentionDays()) {
            issues.add(new FieldIssue("usage_retention_days", "RANGE", "范围 30—3650 且不少于 Trace"));
        }
        if (command.auditRetentionDays() < 365 || command.auditRetentionDays() > 3650) {
            issues.add(new FieldIssue("audit_retention_days", "RANGE", "范围 365—3650"));
        }
        if (command.diagnosticSampleRetentionDays() < 1 || command.diagnosticSampleRetentionDays() > 30
                || command.diagnosticSampleRetentionDays() > command.traceRetentionDays()) {
            issues.add(new FieldIssue("diagnostic_sample_retention_days", "RANGE", "范围 1—30 且不超过 Trace"));
        }
        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "保留天数校验失败", issues);
        }
    }

    private static RuntimeConfigView toView(RuntimeConfigRow row) {
        return new RuntimeConfigView(row.timezone(), row.timezoneLocked(), row.traceRetentionDays(),
                row.usageRetentionDays(), row.auditRetentionDays(), row.dashboardRefreshSeconds(),
                row.maxMessageChars(), row.maxRequestChars(), row.diagnosticSamplingEnabled(),
                row.diagnosticSampleRate(), row.diagnosticSampleRetentionDays(), row.diagnosticSampleMaxChars(),
                row.clientIpRecordingEnabled(), row.trustedProxyCidrs(), row.publishInstanceTimeoutSeconds(),
                row.instanceStaleSeconds(), row.defaultAliasId() == null ? null : row.defaultAliasId().toString(),
                row.currentSnapshotNo(), row.publishedAt(), row.version());
    }

    private static String operatorId(RequestContext context) {
        return context.authContext().userId() == null ? "system" : context.authContext().userId();
    }

    private static List<FieldChange> computeChanges(RuntimeConfigRow current, RuntimeConfigUpdateCommand command) {
        List<FieldChange> changes = new ArrayList<>();
        compareAndAdd(changes, "timezone", current.timezone(), command.timezone());
        compareAndAdd(changes, "trace_retention_days", current.traceRetentionDays(), command.traceRetentionDays());
        compareAndAdd(changes, "usage_retention_days", current.usageRetentionDays(), command.usageRetentionDays());
        compareAndAdd(changes, "audit_retention_days", current.auditRetentionDays(), command.auditRetentionDays());
        compareAndAdd(changes, "dashboard_refresh_seconds", current.dashboardRefreshSeconds(), command.dashboardRefreshSeconds());
        compareAndAdd(changes, "max_message_chars", current.maxMessageChars(), command.maxMessageChars());
        compareAndAdd(changes, "max_request_chars", current.maxRequestChars(), command.maxRequestChars());
        compareAndAdd(changes, "diagnostic_sampling_enabled", current.diagnosticSamplingEnabled(), command.diagnosticSamplingEnabled());
        compareAndAdd(changes, "diagnostic_sample_rate", current.diagnosticSampleRate(), command.diagnosticSampleRate());
        compareAndAdd(changes, "diagnostic_sample_retention_days", current.diagnosticSampleRetentionDays(), command.diagnosticSampleRetentionDays());
        compareAndAdd(changes, "diagnostic_sample_max_chars", current.diagnosticSampleMaxChars(), command.diagnosticSampleMaxChars());
        compareAndAdd(changes, "client_ip_recording_enabled", current.clientIpRecordingEnabled(), command.clientIpRecordingEnabled());
        compareAndAdd(changes, "trusted_proxy_cidrs", current.trustedProxyCidrs(), command.trustedProxyCidrs());
        compareAndAdd(changes, "publish_instance_timeout_seconds", current.publishInstanceTimeoutSeconds(), command.publishInstanceTimeoutSeconds());
        compareAndAdd(changes, "instance_stale_seconds", current.instanceStaleSeconds(), command.instanceStaleSeconds());
        return changes;
    }

    private static void compareAndAdd(List<FieldChange> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(FieldChange.changed(field, before, after));
        }
    }
}
