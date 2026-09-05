package com.lightai.admin.check;

import com.lightai.admin.provider.ProviderService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.provider.ProviderCheckCommand;
import com.lightai.client.provider.ProviderCheckRecord;
import com.lightai.client.protocol.Permissions;
import com.lightai.spi.check.ProviderCheckExecutor;
import com.lightai.storage.check.CheckRecordRow;
import com.lightai.storage.check.JdbcProviderCheckRecordRepository;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.provider.ProviderRecord;
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
public class ProviderCheckService {

    private final DataSource dataSource;
    private final JdbcProviderRepository providerRepository;
    private final JdbcConfigReferenceRepository referenceRepository;
    private final JdbcProviderCheckRecordRepository checkRecordRepository;
    private final JdbcRuntimeStateWriter runtimeStateWriter;
    private final List<ProviderCheckExecutor> executors;
    private final String sourceMode;

    public ProviderCheckService(DataSource dataSource, JdbcProviderRepository providerRepository,
                                JdbcConfigReferenceRepository referenceRepository,
                                JdbcProviderCheckRecordRepository checkRecordRepository,
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

    public ProviderCheckRecord check(RequestContext context, String rawId, ProviderCheckCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_CHECK);
        UUID providerId = ProviderService.parseId(rawId);
        validateCommand(command);

        OffsetDateTime startedAt = OffsetDateTime.now();
        ProviderRecord provider;
        UUID modelId = null;
        UUID credentialId = null;
        try (Connection connection = dataSource.getConnection()) {
            provider = providerRepository.findLiveById(connection, providerId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "Provider不存在或已删除"));
            if (command.providerModelId() != null && !command.providerModelId().isBlank()) {
                UUID parsed = ProviderService.parseId(command.providerModelId());
                if (!modelBelongsToProvider(connection, parsed, providerId)) {
                    throw targetInvalid("provider_model_id", "模型不属于该 Provider");
                }
                modelId = parsed;
            } else if (command.modelId() != null && !command.modelId().isBlank()) {
                modelId = referenceRepository
                        .findModelIdByProviderAndModelId(connection, providerId, command.modelId())
                        .orElseThrow(() -> targetInvalid("model_id", "模型不属于该 Provider"));
            }
            if (command.credentialId() != null && !command.credentialId().isBlank()) {
                UUID parsed = ProviderService.parseId(command.credentialId());
                if (!referenceRepository.credentialBelongsToProvider(connection, parsed, providerId)) {
                    throw targetInvalid("credential_id", "凭证不属于该 Provider 的凭证池");
                }
                credentialId = parsed;
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测目标解析失败");
        }

        ProviderCheckExecutor executor = executors.stream()
                .filter(candidate -> candidate.supports(provider.type()))
                .findFirst()
                .orElseThrow(() -> new LightAiException(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND,
                        "Provider 类型未加载对应 Adapter：" + provider.type()));

        ProviderCheckExecutor.CheckInvocation invocation = new ProviderCheckExecutor.CheckInvocation(
                provider.type(), provider.baseUrl(), provider.proxyUrl(),
                provider.connectTimeoutMs(), provider.readTimeoutMs(), provider.defaultHeaders(),
                modelId == null ? null : modelId.toString(), credentialId,
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
                UUID.randomUUID(), CheckRecordRow.TARGET_PROVIDER, providerId,
                command.resolvedMode(), outcome.succeeded() ? "SUCCEEDED" : "FAILED",
                context.authContext().userId(), outcome.traceId(), outcome.attemptId(),
                startedAt, endedAt, totalMs, outcome.usage(), outcome.providerRequestId(),
                outcome.errorCode(), outcome.errorSummary());
        try (Connection connection = dataSource.getConnection()) {
            checkRecordRepository.insert(connection, row);
            runtimeStateWriter.upsertProviderState(connection, providerId,
                    outcome.succeeded() ? "AVAILABLE" : "UNAVAILABLE",
                    endedAt, outcome.errorCode(), outcome.errorSummary());
        } catch (Exception e) {
            // 检测事实与状态收敛失败必须暴露，不得静默丢失
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测记录写入失败");
        }

        return new ProviderCheckRecord(row.id().toString(), row.targetType(),
                row.targetId().toString(), row.mode(), row.status(), row.startedAt(),
                row.endedAt(), row.totalMs(), row.traceId(), row.attemptId(), row.usage(),
                row.errorCode(), row.errorSummary(), row.providerRequestId());
    }

    private void validateCommand(ProviderCheckCommand command) {
        List<FieldIssue> issues = new java.util.ArrayList<>();
        if (!command.hasModelTarget()) {
            issues.add(new FieldIssue("model_id", "REQUIRED", "model_id 或 provider_model_id 必填其一"));
        }
        if (command.hasBothModelTargets()) {
            issues.add(new FieldIssue("model_id", "INVALID", "model_id 与 provider_model_id 只能提供其一"));
        }
        if (!command.resolvedMode().equals(ProviderCheckCommand.MODE_MINIMAL_CHAT)
                && !command.resolvedMode().equals(ProviderCheckCommand.MODE_CONNECTION_ONLY)) {
            issues.add(new FieldIssue("mode", "INVALID", "mode 仅支持 MINIMAL_CHAT/CONNECTION_ONLY"));
        }
        if (command.timeoutMs() != null && (command.timeoutMs() < ProviderCheckCommand.TIMEOUT_MIN_MS
                || command.timeoutMs() > ProviderCheckCommand.TIMEOUT_MAX_MS)) {
            issues.add(new FieldIssue("timeout_ms", "OUT_OF_RANGE",
                    "timeout_ms 范围 " + ProviderCheckCommand.TIMEOUT_MIN_MS + "—"
                            + ProviderCheckCommand.TIMEOUT_MAX_MS));
        }
        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "检测命令不合法", issues);
        }
    }

    private boolean modelBelongsToProvider(Connection connection, UUID modelId, UUID providerId) {
        String sql = "SELECT 1 FROM " + schemaName()
                + ".provider_model WHERE id = ? AND provider_id = ? AND deleted_at IS NULL";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, modelId);
            statement.setObject(2, providerId);
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
