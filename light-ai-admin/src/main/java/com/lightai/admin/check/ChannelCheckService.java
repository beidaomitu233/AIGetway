package com.lightai.admin.check;

import com.lightai.admin.channel.ChannelService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.channel.ChannelCheckCommand;
import com.lightai.client.channel.ChannelCheckRecord;
import com.lightai.client.protocol.Permissions;
import com.lightai.spi.check.ProviderCheckExecutor;
import com.lightai.storage.check.CheckRecordRow;
import com.lightai.storage.check.JdbcChannelCheckRecordRepository;
import com.lightai.storage.channel.JdbcChannelRepository;
import com.lightai.storage.channel.ChannelRecord;
import com.lightai.storage.reference.JdbcConfigReferenceRepository;
import com.lightai.storage.runtime.JdbcRuntimeStateWriter;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Provider 检测编排（BE-009）。
 * 命令目标必须解析到同一 Provider（CHECK_TARGET_INVALID）；一次命令至多一次
 * Adapter 外部调用；检测不改配置 version，失败结果同样落检测记录并收敛
 * 运行状态；无已加载 Adapter 时返回 PROVIDER_ADAPTER_NOT_FOUND，不伪造记录。
 */
public class ChannelCheckService {

    private final DataSource dataSource;
    private final JdbcChannelRepository providerRepository;
    private final JdbcConfigReferenceRepository referenceRepository;
    private final JdbcChannelCheckRecordRepository checkRecordRepository;
    private final JdbcRuntimeStateWriter runtimeStateWriter;
    private final List<ProviderCheckExecutor> executors;
    private final String sourceMode;

    public ChannelCheckService(DataSource dataSource, JdbcChannelRepository providerRepository,
                                JdbcConfigReferenceRepository referenceRepository,
                                JdbcChannelCheckRecordRepository checkRecordRepository,
                                JdbcRuntimeStateWriter runtimeStateWriter,
                                List<ProviderCheckExecutor> executors, String sourceMode) {
        this.dataSource = dataSource;
        this.providerRepository = providerRepository;
        this.referenceRepository = referenceRepository;
        this.checkRecordRepository = checkRecordRepository;
        this.runtimeStateWriter = runtimeStateWriter;
        this.executors = executors == null ? List.of() : List.copyOf(executors);
        this.sourceMode = sourceMode;
    }

    public ChannelCheckRecord check(RequestContext context, String rawId, ChannelCheckCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_CHECK);
        UUID channelId = ChannelService.parseId(rawId);
        validateCommand(command);

        OffsetDateTime startedAt = OffsetDateTime.now();
        ChannelRecord provider;
        UUID modelId = null;
        UUID channelCredentialId = null;
        try (Connection connection = dataSource.getConnection()) {
            provider = providerRepository.findLiveById(connection, channelId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "Provider不存在或已删除"));
            if (command.upstreamModelId() != null && !command.upstreamModelId().isBlank()) {
                UUID parsed = ChannelService.parseId(command.upstreamModelId());
                if (!modelBelongsToProvider(connection, parsed, channelId)) {
                    throw targetInvalid("upstream_model_id", "模型不属于该 Provider");
                }
                modelId = parsed;
            } else if (command.modelId() != null && !command.modelId().isBlank()) {
                modelId = referenceRepository
                        .findModelIdByChannelAndModelId(connection, channelId, command.modelId())
                        .orElseThrow(() -> targetInvalid("model_id", "模型不属于该 Provider"));
            }
            if (command.channelCredentialId() != null && !command.channelCredentialId().isBlank()) {
                UUID parsed = ChannelService.parseId(command.channelCredentialId());
                if (!referenceRepository.credentialBelongsToChannel(connection, parsed, channelId)) {
                    throw targetInvalid("channel_credential_id", "凭证不属于该 Provider 的凭证池");
                }
                channelCredentialId = parsed;
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测目标解析失败");
        }

        ProviderCheckExecutor executor = executors.stream()
                .filter(candidate -> candidate.supports(provider.providerType()))
                .findFirst()
                .orElseThrow(() -> new LightAiException(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND,
                        "Provider 类型未加载对应 Adapter：" + provider.providerType()));

        ProviderCheckExecutor.CheckInvocation invocation = new ProviderCheckExecutor.CheckInvocation(
                provider.providerType(), provider.baseUrl(), provider.proxyUrl(),
                provider.connectTimeoutMs(), provider.readTimeoutMs(), provider.defaultHeaders(),
                modelId == null ? null : modelId.toString(), channelCredentialId,
                command.resolvedMode(), command.resolvedTimeoutMs());

        ProviderCheckExecutor.CheckOutcome outcome;
        try {
            outcome = executor.execute(invocation);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            // Adapter 抛出的未分类异常按检测失败收敛，不向客户端泄漏内部细节
            outcome = ProviderCheckExecutor.CheckOutcome.failure(
                    (int) Duration.between(startedAt, OffsetDateTime.now()).toMillis(),
                    ErrorCode.INTERNAL_ERROR.name(), "检测执行失败");
        }
        OffsetDateTime endedAt = OffsetDateTime.now();
        int totalMs = outcome.totalMs() >= 0 ? outcome.totalMs()
                : (int) Duration.between(startedAt, endedAt).toMillis();

        CheckRecordRow row = new CheckRecordRow(
                UUID.randomUUID(), CheckRecordRow.TARGET_CHANNEL, channelId,
                command.resolvedMode(), outcome.succeeded() ? "SUCCEEDED" : "FAILED",
                context.authContext().userId(), outcome.traceId(), outcome.attemptId(),
                startedAt, endedAt, totalMs, outcome.usage(), outcome.channelRequestId(),
                outcome.errorCode(), outcome.errorSummary());
        try (Connection connection = dataSource.getConnection()) {
            checkRecordRepository.insert(connection, row);
            runtimeStateWriter.upsertProviderState(connection, channelId,
                    outcome.succeeded() ? "AVAILABLE" : "UNAVAILABLE",
                    endedAt, outcome.errorCode(), outcome.errorSummary());
        } catch (Exception e) {
            // 检测事实与状态收敛失败必须暴露，不得静默丢失
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测记录写入失败");
        }

        return new ChannelCheckRecord(row.id().toString(), row.targetType(),
                row.targetId().toString(), row.mode(), row.status(), row.startedAt(),
                row.endedAt(), row.totalMs(), row.traceId(), row.attemptId(), row.usage(),
                row.errorCode(), row.errorSummary(), row.channelRequestId());
    }

    private void validateCommand(ChannelCheckCommand command) {
        List<FieldIssue> issues = new java.util.ArrayList<>();
        if (!command.hasModelTarget()) {
            issues.add(new FieldIssue("model_id", "REQUIRED", "model_id 或 upstream_model_id 必填其一"));
        }
        if (command.hasBothModelTargets()) {
            issues.add(new FieldIssue("model_id", "INVALID", "model_id 与 upstream_model_id 只能提供其一"));
        }
        if (!command.resolvedMode().equals(ChannelCheckCommand.MODE_MINIMAL_CHAT)
                && !command.resolvedMode().equals(ChannelCheckCommand.MODE_CONNECTION_ONLY)) {
            issues.add(new FieldIssue("mode", "INVALID", "mode 仅支持 MINIMAL_CHAT/CONNECTION_ONLY"));
        }
        if (command.timeoutMs() != null && (command.timeoutMs() < ChannelCheckCommand.TIMEOUT_MIN_MS
                || command.timeoutMs() > ChannelCheckCommand.TIMEOUT_MAX_MS)) {
            issues.add(new FieldIssue("timeout_ms", "OUT_OF_RANGE",
                    "timeout_ms 范围 " + ChannelCheckCommand.TIMEOUT_MIN_MS + "—"
                            + ChannelCheckCommand.TIMEOUT_MAX_MS));
        }
        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "检测命令不合法", issues);
        }
    }

    private boolean modelBelongsToProvider(Connection connection, UUID modelId, UUID channelId) {
        String sql = "SELECT 1 FROM " + com.lightai.storage.dialect.SqlNames.table(schemaName(), "upstream_model")
                + " WHERE id = ? AND channel_id = ? AND deleted_at IS NULL";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, modelId.toString());
            statement.setString(2, channelId.toString());
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测目标解析失败");
        }
    }

    private static LightAiException targetInvalid(String field, String message) {
        return new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测目标不合法",
                List.of(new FieldIssue(field, "INVALID", message)));
    }

    private String schemaName() {
        return com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME;
    }

    /** 供测试与诊断：已加载执行器数量。 */
    public Optional<ProviderCheckExecutor> executorFor(String providerType) {
        return executors.stream().filter(candidate -> candidate.supports(providerType)).findFirst();
    }
}
