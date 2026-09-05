package com.lightai.admin.check;

import com.lightai.client.access.CheckMode;
import com.lightai.client.access.ProviderCheckCommand;
import com.lightai.client.access.ProviderCheckRecordView;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.access.ObjectRuntimeStateRepository;
import com.lightai.storage.alias.RouteCandidateRecord;
import com.lightai.storage.alias.RouteCandidateRepository;
import com.lightai.storage.check.CheckRecord;
import com.lightai.storage.check.CheckRecordRepository;
import com.lightai.storage.credential.CredentialRecord;
import com.lightai.storage.credential.CredentialRepository;
import com.lightai.storage.credential.SecretRecord;
import com.lightai.storage.credential.SecretRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.model.ProviderModelRepository;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 管理检测编排（BE-009/013/014/015/017 共用）：
 * 目标关系与同 Provider 校验 → Secret 解析 → 单次 Adapter 调用（CheckInvoker，BE-P05 注入实现）→
 * 独立事务写 ProviderCheckRecord 并回写 object_runtime_state。
 * 检测不修改配置 version 与 draft_changed；密钥不进入记录/日志/异常，用后清零。
 */
public class ManagementCheckService {

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final CheckRecordRepository checkRecordRepository;
    private final SecretRepository secretRepository;
    private final CredentialRepository credentialRepository;
    private final ProviderModelRepository modelRepository;
    private final RouteCandidateRepository candidateRepository;
    private final ConfigReferenceQuery referenceQuery;
    private final ObjectRuntimeStateRepository runtimeStateRepository;
    private final CheckInvoker checkInvoker;
    private final SecretResolver secretResolver;
    private final Clock clock;

    /** Secret 解析端口：INLINE 本地解密；EXTERNAL 由部署 SecretProvider 实现（BE-053 SPI）。 */
    public interface SecretResolver {
        byte[] resolve(SecretRecord record);
    }

    public ManagementCheckService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  CheckRecordRepository checkRecordRepository, SecretRepository secretRepository,
                                  CredentialRepository credentialRepository,
                                  ProviderModelRepository modelRepository,
                                  RouteCandidateRepository candidateRepository,
                                  ConfigReferenceQuery referenceQuery,
                                  ObjectRuntimeStateRepository runtimeStateRepository,
                                  CheckInvoker checkInvoker, SecretResolver secretResolver, Clock clock) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.checkRecordRepository = checkRecordRepository;
        this.secretRepository = secretRepository;
        this.credentialRepository = credentialRepository;
        this.modelRepository = modelRepository;
        this.candidateRepository = candidateRepository;
        this.referenceQuery = referenceQuery;
        this.runtimeStateRepository = runtimeStateRepository;
        this.checkInvoker = checkInvoker;
        this.secretResolver = secretResolver;
        this.clock = clock;
    }

    /** 目标关系解析产物：Provider 摘要 + 检测模型 + 检测凭证。 */
    record ResolvedTarget(ConfigReferenceQuery.ProviderSummary provider,
                          String modelId,
                          UUID credentialId) {
    }

    public ProviderCheckRecordView check(String operatorId, String targetType, UUID targetId,
                                         ProviderCheckCommand command) {
        int timeoutMs = resolveTimeout(command);
        String mode = resolveMode(command);
        ResolvedTarget target = resolveTarget(targetType, targetId, command);
        byte[] secret = resolveSecret(target.credentialId());

        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        CheckInvoker.Outcome outcome;
        try {
            CheckInvoker.Invocation invocation = new CheckInvoker.Invocation(
                    target.provider().type(), target.provider().baseUrl(), target.modelId(),
                    target.credentialId(), secret, mode, timeoutMs);
            outcome = checkInvoker.supports(target.provider().type())
                    ? checkInvoker.invoke(invocation)
                    : CheckInvoker.Outcome.unsupported(target.provider().type());
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
        OffsetDateTime endedAt = OffsetDateTime.now(clock);

        Long totalTokens = outcome.totalTokens() != null
                ? outcome.totalTokens()
                : sumTokens(outcome.inputTokens(), outcome.outputTokens());
        CheckRecord record = new CheckRecord(
                UUID.randomUUID(), targetType, targetId, mode,
                outcome.succeeded() ? CheckRecord.STATUS_SUCCEEDED : CheckRecord.STATUS_FAILED,
                operatorId, outcome.traceId(), outcome.attemptId(),
                startedAt, endedAt,
                (int) Duration.between(startedAt, endedAt).toMillis(),
                outcome.inputTokens(), outcome.outputTokens(), totalTokens,
                outcome.usageSource(), outcome.providerRequestId(),
                outcome.errorCode(), outcome.errorSummary());
        persist(record, targetType, targetId, outcome.succeeded());
        return toView(record, true);
    }

    private ResolvedTarget resolveTarget(String targetType, UUID targetId, ProviderCheckCommand command) {
        try (Connection connection = dataSource.getConnection()) {
            return switch (targetType) {
                case "CREDENTIAL" -> {
                    CredentialRecord credential = credentialRepository.find(connection, targetId)
                            .orElseThrow(() -> new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测凭证不存在"));
                    ConfigReferenceQuery.ProviderSummary provider =
                            referenceQuery.findProviderSummaryOfPool(connection, credential.poolId())
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                            "凭证所属池或 Provider 不存在"));
                    String modelId = resolveCommandModelId(connection, command, provider.id());
                    yield new ResolvedTarget(provider, modelId, credential.id());
                }
                case "PROVIDER_MODEL" -> {
                    ProviderModelRecord model = modelRepository.find(connection, targetId)
                            .orElseThrow(() -> new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测模型不存在"));
                    ConfigReferenceQuery.ProviderSummary provider =
                            referenceQuery.findProviderSummary(connection, model.providerId())
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                            "模型所属 Provider 不存在"));
                    yield new ResolvedTarget(provider, model.modelId(), resolveCredential(connection, command, provider.id()));
                }
                case "ROUTE_CANDIDATE" -> {
                    RouteCandidateRecord candidate = candidateRepository.find(connection, targetId)
                            .orElseThrow(() -> new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测候选不存在"));
                    ProviderModelRecord model = modelRepository.find(connection, candidate.providerModelId())
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID, "候选模型不存在"));
                    ConfigReferenceQuery.ProviderSummary provider =
                            referenceQuery.findProviderSummary(connection, model.providerId())
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                            "候选 Provider 不存在"));
                    ConfigReferenceQuery.ProviderSummary poolProvider =
                            referenceQuery.findProviderSummaryOfPool(connection, candidate.credentialPoolId())
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                            "候选凭证池所属 Provider 不存在"));
                    if (!provider.id().equals(poolProvider.id())) {
                        throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                "候选模型与凭证池不属于同一 Provider");
                    }
                    UUID credentialId = command.credentialId() != null
                            ? parseUuid(command.credentialId(), "credential_id")
                            : referenceQuery.findFirstAliveCredentialIdOfPool(connection, candidate.credentialPoolId())
                            .orElseThrow(() -> new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                                    "候选池内没有可用 Credential"));
                    yield new ResolvedTarget(provider, model.modelId(), credentialId);
                }
                default -> throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                        "不支持的检测目标: " + targetType, "target_type");
            };
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测目标读取失败");
        }
    }

    /** CREDENTIAL 目标：命令中的 model_id/provider_model_id 必须解析到同一 Provider（CHECK_TARGET_INVALID）。 */
    private String resolveCommandModelId(Connection connection, ProviderCheckCommand command, UUID providerId) {
        String modelRef = command.providerModelId() != null ? command.providerModelId() : command.modelId();
        if (modelRef == null) {
            return "";
        }
        UUID modelPk = parseUuid(modelRef, command.providerModelId() != null ? "provider_model_id" : "model_id");
        ProviderModelRecord model = modelRepository.find(connection, modelPk)
                .orElseThrow(() -> new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测模型不存在"));
        if (!model.providerId().equals(providerId)) {
            throw new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测模型与凭证不属于同一 Provider");
        }
        return model.modelId();
    }

    /** 模型/候选目标：命令可指定凭证；未指定时选择同 Provider 池内第一个可用凭证。 */
    private UUID resolveCredential(Connection connection, ProviderCheckCommand command, UUID providerId) {
        if (command.credentialId() != null) {
            UUID credentialId = parseUuid(command.credentialId(), "credential_id");
            CredentialRecord credential = credentialRepository.find(connection, credentialId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测凭证不存在"));
            ConfigReferenceQuery.ProviderSummary credentialProvider =
                    referenceQuery.findProviderSummaryOfPool(connection, credential.poolId())
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                    "凭证所属 Provider 不存在"));
            if (!credentialProvider.id().equals(providerId)) {
                throw new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "检测模型与凭证不属于同一 Provider");
            }
            return credentialId;
        }
        for (ConfigReferenceQuery.EntitySummary pool : referenceQuery.listPoolRefsOfProvider(connection, providerId)) {
            UUID credentialId = referenceQuery.findFirstAliveCredentialIdOfPool(connection, pool.id()).orElse(null);
            if (credentialId != null) {
                return credentialId;
            }
        }
        throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE, "该 Provider 下没有可用 Credential");
    }

    private byte[] resolveSecret(UUID credentialId) {
        try (Connection connection = dataSource.getConnection()) {
            SecretRecord record = secretRepository.find(connection, credentialId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.SECRET_RESOLUTION_FAILED, "凭证秘密尚未写入"));
            return secretResolver.resolve(record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.SECRET_RESOLUTION_FAILED, "Secret 解析失败");
        }
    }

    private void persist(CheckRecord record, String targetType, UUID targetId, boolean succeeded) {
        transaction.executeWithoutResult(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            checkRecordRepository.insert(connection, record);
            String connectionStatus = succeeded ? "AVAILABLE" : "UNAVAILABLE";
            String healthStatus = succeeded ? "HEALTHY" : healthForErrorCode(record.errorCode());
            runtimeStateRepository.upsertAfterCheck(connection, targetType, targetId,
                    connectionStatus, healthStatus, succeeded, record.errorCode(), record.errorSummary());
        });
    }

    private static String healthForErrorCode(String errorCode) {
        if (errorCode == null) {
            return "UNKNOWN";
        }
        return switch (errorCode) {
            case "PROVIDER_AUTH_FAILED" -> "INVALID";
            case "PROVIDER_RATE_LIMITED" -> "RATE_LIMITED";
            case "NETWORK_ERROR", "CONNECT_TIMEOUT", "TOTAL_TIMEOUT", "PROVIDER_SERVER_ERROR" -> "UNAVAILABLE";
            default -> "UNKNOWN";
        };
    }

    public static ProviderCheckRecordView toView(CheckRecord record, boolean includeProviderRequestId) {
        ProviderCheckRecordView.UsageView usage =
                record.usageInputTokens() == null && record.usageOutputTokens() == null && record.usageTotalTokens() == null
                        ? null
                        : new ProviderCheckRecordView.UsageView(record.usageInputTokens(),
                        record.usageOutputTokens(), record.usageTotalTokens(), record.usageSource());
        return new ProviderCheckRecordView(
                record.id().toString(), record.targetType(), record.targetId().toString(),
                record.mode(), record.status(), record.startedAt(), record.endedAt(), record.totalMs(),
                record.traceId(), record.attemptId() == null ? null : record.attemptId().toString(),
                usage,
                includeProviderRequestId ? record.providerRequestId() : null,
                record.errorCode(), record.errorSummary());
    }

    private static int resolveTimeout(ProviderCheckCommand command) {
        int timeout = command.timeoutMs() == null ? ProviderCheckCommand.DEFAULT_TIMEOUT_MS : command.timeoutMs();
        if (timeout < ProviderCheckCommand.MIN_TIMEOUT_MS || timeout > ProviderCheckCommand.MAX_TIMEOUT_MS) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "timeout_ms 范围 " + ProviderCheckCommand.MIN_TIMEOUT_MS + "—"
                            + ProviderCheckCommand.MAX_TIMEOUT_MS, "timeout_ms");
        }
        return timeout;
    }

    private static String resolveMode(ProviderCheckCommand command) {
        String mode = command.mode() == null ? CheckMode.MINIMAL_CHAT.name() : command.mode();
        try {
            CheckMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "mode 仅支持 MINIMAL_CHAT/CONNECTION_ONLY", "mode");
        }
        return mode;
    }

    private static Long sumTokens(Long a, Long b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 不是合法ID", field);
        }
    }
}
