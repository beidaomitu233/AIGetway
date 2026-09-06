package com.lightai.admin.publish;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.provider.ProviderTypeRegistry;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigValidateCommand;
import com.lightai.client.publish.ConfigValidationIssueView;
import com.lightai.client.publish.ConfigValidationResultView;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.check.JdbcProviderCheckRecordRepository;
import com.lightai.storage.draft.DraftChangeQueryRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import com.lightai.storage.publish.ConfigValidationIssueRecord;
import com.lightai.storage.publish.ConfigValidationRecord;
import com.lightai.storage.publish.ConfigSnapshotRepository;
import com.lightai.storage.publish.ConfigValidationRepository;
import com.lightai.storage.publish.RuntimeInstanceRepository;
import com.lightai.storage.publish.SnapshotContentRepository;
import com.lightai.storage.publish.RuntimeInstanceRecord;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 固定修订校验（BE-039，4.5.2.3 发布校验矩阵）。
 * 只读取配置与运行实例能力，不访问外部 Provider、不解析 Secret；
 * 校验有效期 10 分钟（C-015），草稿修订变化即失效。
 */
public class ConfigValidationService {

    private static final int MAX_ERROR_SUMMARY = 1000;
    private static final Duration VALIDITY = Duration.ofMinutes(10);
    private static final Duration CHECK_STALE = Duration.ofHours(24);
    private static final int SNAPSHOT_SCHEMA_VERSION = 1;

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final DraftStateRepository draftStateRepository;
    private final DraftChangeQueryRepository draftChangeQueryRepository;
    private final ConfigSnapshotRepository snapshotRepository;
    private final SnapshotContentRepository snapshotContentRepository;
    private final ConfigValidationRepository validationRepository;
    private final RuntimeInstanceRepository runtimeInstanceRepository;
    private final ProviderTypeRegistry providerTypeRegistry;
    private final JdbcProviderCheckRecordRepository checkRecordRepository;
    private final AuditService auditService;
    private final String timezone;
    private final String sourceMode;

    public ConfigValidationService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                   Clock clock, DraftStateRepository draftStateRepository,
                                   DraftChangeQueryRepository draftChangeQueryRepository,
                                   ConfigSnapshotRepository snapshotRepository,
                                   SnapshotContentRepository snapshotContentRepository,
                                   ConfigValidationRepository validationRepository,
                                   RuntimeInstanceRepository runtimeInstanceRepository,
                                   ProviderTypeRegistry providerTypeRegistry,
                                   JdbcProviderCheckRecordRepository checkRecordRepository,
                                   AuditService auditService, String timezone, String sourceMode) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.draftStateRepository = draftStateRepository;
        this.draftChangeQueryRepository = draftChangeQueryRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotContentRepository = snapshotContentRepository;
        this.validationRepository = validationRepository;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.providerTypeRegistry = providerTypeRegistry;
        this.checkRecordRepository = checkRecordRepository;
        this.auditService = auditService;
        this.timezone = timezone;
        this.sourceMode = sourceMode;
    }

    public ConfigValidationResultView validate(String requestId, String operatorId,
                                               String sourceIpMasked,
                                               ConfigValidateCommand command) {
        try {
            return transaction.execute(tx -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                OffsetDateTime now = OffsetDateTime.now(clock);
                validationRepository.sweepExpired(connection, now);

                DraftStateSnapshot draft = draftStateRepository.find(connection)
                        .orElse(new DraftStateSnapshot(0, 0, DraftStatus.EDITABLE, null, 0));
                if (draft.changeCount() <= 0) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "当前草稿无待发布变更",
                            List.of(new FieldIssue("draft_revision", "INVALID", "当前草稿无待发布变更")));
                }
                if (draft.draftRevision() != command.draftRevision()) {
                    throw new LightAiException(ErrorCode.CONFIG_DRAFT_CHANGED, "草稿修订已变化，请刷新后重新校验");
                }

                UUID validationId = UUID.randomUUID();
                Map<String, Object> content = snapshotContentRepository.assemble(connection, timezone);
                String canonicalJson = snapshotContentRepository.canonicalJson(content);
                String checksum = sha256Hex(canonicalJson);
                long targetSnapshotNo = snapshotRepository.nextSnapshotNo(connection);

                List<ConfigValidationIssueRecord> issues =
                        collectIssues(connection, validationId, content, now);
                int errorCount = (int) issues.stream()
                        .filter(issue -> ConfigValidationIssueView.SEVERITY_ERROR.equals(issue.severity()))
                        .count();
                int warningCount = issues.size() - errorCount;
                String status = errorCount > 0
                        ? ConfigValidationRecord.STATUS_FAILED
                        : ConfigValidationRecord.STATUS_PASSED;

                ConfigValidationRecord record = new ConfigValidationRecord(
                        validationId, draft.baseSnapshotNo(), targetSnapshotNo, draft.draftRevision(),
                        checksum, status, errorCount, warningCount, now, now.plus(VALIDITY),
                        operatorId, null, changeSummaryJson(connection), affectedAliasIds(content),
                        targetInstancesJson(connection));
                validationRepository.insert(connection, record, issues);
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), requestId, operatorId, "VALIDATE",
                        "config_draft_state", null, List.of(), sourceMode, sourceIpMasked));
                return toView(record, issues, connection);
            });
        } catch (LightAiException e) {
            recordFailure(requestId, operatorId, sourceMode, sourceIpMasked, e);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(requestId, operatorId, sourceMode, sourceIpMasked, e);
            throw e;
        }
    }

    // ---------- 校验矩阵（4.5.2.3） ----------

    private List<ConfigValidationIssueRecord> collectIssues(Connection connection, UUID validationId,
                                                            Map<String, Object> content,
                                                            OffsetDateTime now) {
        List<ConfigValidationIssueRecord> issues = new ArrayList<>();
        Map<String, Map<String, Object>> providers = index(content, "providers");
        Map<String, Map<String, Object>> pools = index(content, "credential_pools");
        Map<String, Map<String, Object>> credentials = index(content, "credentials");
        Map<String, Map<String, Object>> models = index(content, "provider_models");
        Map<String, Map<String, Object>> aliases = index(content, "model_aliases");
        Map<String, Map<String, Object>> candidates = index(content, "route_candidates");

        for (Map<String, Object> provider : providers.values()) {
            if (!truthy(provider.get("enabled"))) {
                continue;
            }
            String type = text(provider.get("type"));
            if (type != null && !providerTypeRegistry.isRegistered(type)) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "ADAPTER_UNAVAILABLE", "provider", provider, null,
                        "Provider 类型 " + type + " 没有已注册 Adapter",
                        "确认 Adapter 已部署，或改用已加载的 Provider 类型"));
            }
        }

        for (Map<String, Object> model : models.values()) {
            if (!truthy(model.get("enabled"))) {
                continue;
            }
            BigDecimal context = decimal(model.get("context_window"));
            BigDecimal maxOutput = decimal(model.get("max_output_tokens"));
            if (text(model.get("tokenizer_family")) == null || context == null || context.signum() <= 0) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "MODEL_CAPABILITY_INVALID", "provider_model", model, "context_window",
                        "启用模型缺少 tokenizer 或上下文配置", "补全 tokenizer_family 与 context_window"));
            } else if (maxOutput != null && context.compareTo(maxOutput) <= 0) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "MODEL_CAPABILITY_INVALID", "provider_model", model, "context_window",
                        "context_window 必须大于 max_output_tokens", "调整上下文或最大输出配置"));
            }
            if (decimal(model.get("input_price")) == null || decimal(model.get("output_price")) == null
                    || text(model.get("price_unit")) == null || text(model.get("currency")) == null) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "PRICE_CONFIGURATION_INVALID", "provider_model", model, "input_price",
                        "启用模型缺少价格或币种配置", "补全 input_price、output_price、price_unit 与 currency"));
            }
            if (!checkRecordRepository.existsSuccessSince(connection, "PROVIDER_MODEL",
                    uuid(model.get("id")), now.minus(CHECK_STALE))) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_WARNING,
                        "CONNECTION_CHECK_STALE", "provider_model", model, null,
                        "模型最近 24 小时无成功检测记录", "发布前执行一次模型检测确认连接可用"));
            }
        }

        for (Map<String, Object> candidate : candidates.values()) {
            Map<String, Object> model = models.get(text(candidate.get("provider_model_id")));
            Map<String, Object> pool = pools.get(text(candidate.get("credential_pool_id")));
            if (model == null || pool == null) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "REFERENCE_INVALID", "route_candidate", candidate, null,
                        "候选引用的模型或凭证池不存在", "修正候选引用关系"));
                continue;
            }
            if (!truthy(model.get("enabled")) || !truthy(pool.get("enabled"))) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "REFERENCE_INVALID", "route_candidate", candidate, null,
                        "候选引用的模型或凭证池已停用", "启用引用对象或调整候选"));
            }
            String modelProviderId = text(model.get("provider_id"));
            String poolProviderId = text(pool.get("provider_id"));
            if (modelProviderId == null || !modelProviderId.equals(poolProviderId)) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "PROVIDER_RELATION_INVALID", "route_candidate", candidate, null,
                        "候选的模型与凭证池属于不同 Provider", "候选的模型与凭证池必须同属一个 Provider"));
            }
        }

        Map<String, String> aliasCurrency = new HashMap<>();
        for (Map<String, Object> candidate : candidates.values()) {
            if (!truthy(candidate.get("enabled"))) {
                continue;
            }
            Map<String, Object> model = models.get(text(candidate.get("provider_model_id")));
            String currency = model == null ? null : text(model.get("currency"));
            if (currency != null) {
                String previous = aliasCurrency.putIfAbsent(text(candidate.get("alias_id")), currency);
                if (previous != null && !previous.equals(currency)) {
                    Map<String, Object> alias = aliases.get(text(candidate.get("alias_id")));
                    issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                            "PRICE_CONFIGURATION_INVALID", "model_alias", alias, null,
                            "同一 Alias 候选存在多种币种", "同一 Alias 的候选模型币种必须一致"));
                }
            }
        }
        Map<String, Long> enabledCandidatesByAlias = new HashMap<>();
        for (Map<String, Object> candidate : candidates.values()) {
            if (truthy(candidate.get("enabled"))) {
                enabledCandidatesByAlias.merge(text(candidate.get("alias_id")), 1L, Long::sum);
            }
        }
        for (Map<String, Object> alias : aliases.values()) {
            if (!truthy(alias.get("enabled"))) {
                continue;
            }
            if (enabledCandidatesByAlias.getOrDefault(text(alias.get("id")), 0L) == 0) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "ALIAS_NO_AVAILABLE_CANDIDATE", "model_alias", alias, null,
                        "启用 Alias 没有启用候选", "为 Alias 配置至少一个启用候选"));
            }
        }

        Map<String, Long> enabledCredentialsByPool = new HashMap<>();
        for (Map<String, Object> credential : credentials.values()) {
            if (!truthy(credential.get("enabled"))) {
                continue;
            }
            enabledCredentialsByPool.merge(text(credential.get("pool_id")), 1L, Long::sum);
            if (!checkRecordRepository.existsSuccessSince(connection, "CREDENTIAL",
                    uuid(credential.get("id")), now.minus(CHECK_STALE))) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_WARNING,
                        "CONNECTION_CHECK_STALE", "credential", credential, null,
                        "凭证最近 24 小时无成功检测记录", "发布前执行一次凭证检测确认连接可用"));
            }
        }
        for (Map<String, Object> pool : pools.values()) {
            if (truthy(pool.get("enabled"))
                    && enabledCredentialsByPool.getOrDefault(text(pool.get("id")), 0L) == 0) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "CREDENTIAL_CONFIGURATION_INVALID", "credential_pool", pool, null,
                        "启用凭证池没有启用的 Credential", "为凭证池配置启用 Credential"));
            }
        }

        Map<String, Integer> enabledLimitByScope = new HashMap<>();
        for (Map<String, Object> policy : index(content, "limit_policies").values()) {
            if (!truthy(policy.get("enabled"))) {
                continue;
            }
            String scopeKey = text(policy.get("scope_type")) + ":" + text(policy.get("scope_id"));
            enabledLimitByScope.merge(scopeKey, 1, Integer::sum);
            if (decimal(policy.get("rpm_limit")) == null && decimal(policy.get("tpm_limit")) == null
                    && decimal(policy.get("concurrent_limit")) == null) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "LIMIT_POLICY_INVALID", "limit_policy", policy, null,
                        "启用的限流策略至少需要一个限额", "配置 RPM、TPM 或并发上限"));
            }
        }
        enabledLimitByScope.forEach((scope, count) -> {
            if (count > 1) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "LIMIT_POLICY_INVALID", "limit_policy",
                        Map.of("id", scope, "name", scope), null,
                        "同一作用对象存在多条启用限流策略", "每个作用对象只保留一条启用策略"));
            }
        });
        Map<String, Integer> enabledReliabilityByAlias = new HashMap<>();
        for (Map<String, Object> policy : index(content, "reliability_policies").values()) {
            if (truthy(policy.get("enabled"))) {
                enabledReliabilityByAlias.merge(text(policy.get("alias_id")), 1, Integer::sum);
            }
        }
        enabledReliabilityByAlias.forEach((aliasId, count) -> {
            if (count > 1) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "RELIABILITY_POLICY_INVALID", "reliability_policy",
                        Map.of("id", aliasId, "name", aliasId), null,
                        "同一 Alias 存在多条启用可靠性策略", "每个 Alias 只保留一条启用策略"));
            }
        });

        List<RuntimeInstanceRecord> online = runtimeInstanceRepository.findOnline(connection);
        for (RuntimeInstanceRecord instance : online) {
            if (!instance.supportedSchemaVersions().isEmpty()
                    && !instance.supportedSchemaVersions().contains(String.valueOf(SNAPSHOT_SCHEMA_VERSION))) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_ERROR,
                        "INSTANCE_VERSION_INCOMPATIBLE", "runtime_instance",
                        Map.of("id", instance.instanceId().toString(),
                                "name", instance.instanceId().toString()), null,
                        "实例 " + instance.instanceId() + " 不支持目标快照结构版本",
                        "升级实例后重新发布"));
            }
        }
        List<RuntimeInstanceRecord> allInstances = runtimeInstanceRepository.list(connection,
                new RuntimeInstanceRepository.RuntimeInstanceFilter(null, null, null),
                "last_heartbeat_at desc", 100, 0);
        for (RuntimeInstanceRecord instance : allInstances) {
            if (!"ONLINE".equals(instance.status())) {
                issues.add(issue(validationId, ConfigValidationIssueView.SEVERITY_WARNING,
                        "INSTANCE_NOT_ONLINE", "runtime_instance",
                        Map.of("id", instance.instanceId().toString(),
                                "name", instance.instanceId().toString()), null,
                        "实例 " + instance.instanceId() + " 当前为 " + instance.status(),
                        "实例恢复 ONLINE 后会自动加载活动快照"));
            }
        }
        return issues;
    }

    private ConfigValidationIssueRecord issue(UUID validationId, String severity, String code,
                                              String entityType, Map<String, Object> entity,
                                              String fieldPath, String message, String suggestion) {
        return new ConfigValidationIssueRecord(validationId, severity, code, entityType,
                uuid(entity == null ? null : entity.get("id")),
                entity == null ? null : text(entity.getOrDefault("name",
                        entity.getOrDefault("display_name",
                                entity.getOrDefault("alias", entity.get("id"))))),
                fieldPath, message, suggestion, List.of());
    }

    private static Map<String, Map<String, Object>> index(Map<String, Object> content, String key) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        if (content.get(key) instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> typed) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) typed;
                    Object id = map.get("id");
                    if (id != null) {
                        index.put(String.valueOf(id), map);
                    }
                }
            }
        }
        return index;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(String.valueOf(number));
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static UUID uuid(Object value) {
        try {
            return value == null ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> affectedAliasIds(Map<String, Object> content) {
        List<String> aliasIds = new ArrayList<>();
        if (content.get("model_aliases") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> typed
                        && truthy(((Map<String, Object>) typed).get("enabled"))) {
                    aliasIds.add(String.valueOf(((Map<String, Object>) typed).get("id")));
                }
            }
        }
        return aliasIds;
    }

    private String changeSummaryJson(Connection connection) {
        Map<String, Map<String, Long>> matrix =
                draftChangeQueryRepository.countByEntityTypeAndChangeType(connection);
        StringBuilder json = new StringBuilder("[");
        matrix.forEach((entityType, counts) -> {
            json.append("{\"entity_type\":\"").append(entityType).append('"');
            for (String changeType : List.of("create", "update", "enable", "disable", "delete")) {
                json.append(",\"").append(changeType).append("_count\":")
                        .append(counts.getOrDefault(changeType.toUpperCase(), 0L));
            }
            json.append("},");
        });
        if (!matrix.isEmpty()) {
            json.setLength(json.length() - 1);
        }
        return json.append(']').toString();
    }

    private String targetInstancesJson(Connection connection) {
        List<RuntimeInstanceRecord> online = runtimeInstanceRepository.findOnline(connection);
        StringBuilder json = new StringBuilder("[");
        for (RuntimeInstanceRecord instance : online) {
            json.append("{\"instance_id\":\"").append(instance.instanceId())
                    .append("\",\"runtime_mode\":\"").append(instance.runtimeMode())
                    .append("\",\"runtime_version\":\"").append(instance.runtimeVersion())
                    .append("\",\"supported_schema_versions\":")
                    .append(jsonArray(instance.supportedSchemaVersions()))
                    .append(",\"loaded_adapter_types\":")
                    .append(jsonArray(instance.loadedAdapterTypes()))
                    .append("},");
        }
        if (!online.isEmpty()) {
            json.setLength(json.length() - 1);
        }
        return json.append(']').toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i)).append('"');
        }
        return json.append(']').toString();
    }

    private ConfigValidationResultView toView(ConfigValidationRecord record,
                                              List<ConfigValidationIssueRecord> issues,
                                              Connection connection) {
        return new ConfigValidationResultView(
                record.validationId().toString(), record.status(),
                record.baseSnapshotNo(), record.targetSnapshotNo(), record.draftRevision(),
                record.contentChecksum(), record.validatedAt(), record.expiresAt(),
                changeSummaryText(connection), record.affectedAliasIds(),
                issues.stream().map(issue -> new ConfigValidationIssueView(
                        issue.code(), issue.severity(), issue.entityType(),
                        issue.entityId() == null ? null : issue.entityId().toString(),
                        issue.entityName(), issue.fieldPath(), issue.message(),
                        issue.suggestion(), issue.relatedEntityIds())).toList());
    }

    private String changeSummaryText(Connection connection) {
        Map<String, Map<String, Long>> matrix =
                draftChangeQueryRepository.countByEntityTypeAndChangeType(connection);
        StringBuilder text = new StringBuilder();
        matrix.forEach((entityType, counts) -> {
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            if (text.length() > 0) {
                text.append("；");
            }
            text.append(entityType).append(" ").append(total).append(" 项变更");
        });
        return text.length() == 0 ? "无变更" : text.toString();
    }

    static String sha256Hex(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void recordFailure(String requestId, String operatorId, String sourceMode,
                               String sourceIpMasked, Exception cause) {
        String code = cause instanceof LightAiException lightAi
                ? lightAi.code().name() : ErrorCode.INTERNAL_ERROR.name();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        auditService.recordFailure(AuditRecord.failed(
                UUID.randomUUID(), requestId, operatorId, "VALIDATE", "config_draft_state", null,
                code, message.length() <= MAX_ERROR_SUMMARY ? message : message.substring(0, MAX_ERROR_SUMMARY),
                sourceMode, sourceIpMasked));
    }
}
