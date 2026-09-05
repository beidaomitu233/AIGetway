package com.lightai.admin.credential;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.secret.SecretManager;
import com.lightai.client.access.CredentialCreateCommand;
import com.lightai.client.access.CredentialDetail;
import com.lightai.client.access.CredentialListItem;
import com.lightai.client.access.CredentialRotateCommand;
import com.lightai.client.access.CredentialUpdateCommand;
import com.lightai.client.access.ImpactAnalysis;
import com.lightai.client.access.SecretSource;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.access.ObjectRuntimeStateRepository;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.check.CheckRecordRepository;
import com.lightai.storage.credential.CredentialRecord;
import com.lightai.storage.credential.CredentialRepository;
import com.lightai.storage.credential.SecretRecord;
import com.lightai.storage.credential.SecretRepository;
import com.lightai.storage.draft.DraftChangeRepository;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credential 服务（BE-013）：查询/新增/编辑/轮换/启停/删除。
 * 创建与编辑走草稿事务（DraftWriteService：草稿+差异+revision+同事务审计）；
 * 轮换独立事务即时生效（secret_version+1，不产生草稿差异）；
 * 删除前校验运行占用（CAPACITY_IN_USE）。明文密钥永不进入响应/审计/日志。
 */
public class CredentialService {

    public static final String ENTITY_TYPE = "CREDENTIAL";
    public static final Set<String> SORTABLE = Set.of(
            "name", "secret_source", "weight", "rpm_limit", "tpm_limit",
            "concurrent_limit", "enabled", "updated_at");

    /** 运行占用端口：BE-P04 容量组件接入前返回 0（contract-first）。 */
    public interface RunningAttemptCounter {
        int countRunning(UUID credentialId);
    }

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final DraftWriteService draftWriteService;
    private final CredentialRepository credentialRepository;
    private final SecretRepository secretRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final CheckRecordRepository checkRecordRepository;
    private final ConfigReferenceQuery referenceQuery;
    private final ObjectRuntimeStateRepository runtimeStateRepository;
    private final SecretManager secretManager;
    private final AuditService auditService;
    private final ImpactService impactService;
    private final RunningAttemptCounter runningAttemptCounter;
    private final Clock clock;

    public CredentialService(DataSource dataSource, PlatformTransactionManager transactionManager,
                             DraftWriteService draftWriteService, CredentialRepository credentialRepository,
                             SecretRepository secretRepository, DraftChangeRepository draftChangeRepository,
                             CheckRecordRepository checkRecordRepository,
                             ConfigReferenceQuery referenceQuery,
                             ObjectRuntimeStateRepository runtimeStateRepository,
                             SecretManager secretManager, AuditService auditService,
                             ImpactService impactService, RunningAttemptCounter runningAttemptCounter,
                             Clock clock) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.draftWriteService = draftWriteService;
        this.credentialRepository = credentialRepository;
        this.secretRepository = secretRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.checkRecordRepository = checkRecordRepository;
        this.referenceQuery = referenceQuery;
        this.runtimeStateRepository = runtimeStateRepository;
        this.secretManager = secretManager;
        this.auditService = auditService;
        this.impactService = impactService;
        this.runningAttemptCounter = runningAttemptCounter;
        this.clock = clock;
    }

    /** 池下分页列表：enabled 走 SQL 过滤；health_status 为运行组合字段，内存过滤。 */
    public PageResult<CredentialListItem> list(UUID poolId, String healthStatus, Boolean enabled,
                                               ListQuerySupport.ListQuery query) {
        try (Connection connection = dataSource.getConnection()) {
            requirePool(connection, poolId);
            List<Object> filters = new ArrayList<>();
            StringBuilder filterSql = new StringBuilder();
            if (enabled != null) {
                filterSql.append("enabled = ?");
                filters.add(enabled);
            }
            String filter = filterSql.toString().trim();
            List<CredentialRepository.CredentialRow> rows = credentialRepository.listByPool(
                    connection, poolId, filter, filters, query.sort(), query.offset(), query.limit());
            long total = credentialRepository.countByPool(connection, poolId, filter, filters);

            List<UUID> ids = rows.stream().map(row -> row.record().id()).toList();
            Map<UUID, ObjectRuntimeStateRepository.RuntimeStateRow> states =
                    runtimeStateRepository.find(connection, ENTITY_TYPE, ids);
            Set<UUID> draftChanged = draftChangeRepository.findExistingEntityIds(connection, ENTITY_TYPE, ids);

            OffsetDateTime now = OffsetDateTime.now(clock);
            List<CredentialListItem> items = rows.stream()
                    .filter(row -> healthStatus == null || healthStatus.isBlank()
                            || healthStatus.equals(healthOf(states, row.record().id())))
                    .map(row -> toListItem(row, states.get(row.record().id()),
                            draftChanged.contains(row.record().id())))
                    .toList();
            return new PageResult<>(items, total, query.page(), query.pageSize(), query.sort(), now, now);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "凭证列表读取失败");
        }
    }

    public CredentialDetail get(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            CredentialRecord record = credentialRepository.find(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
            SecretRecord secret = secretRepository.find(connection, id).orElse(null);
            ObjectRuntimeStateRepository.RuntimeStateRow state =
                    runtimeStateRepository.find(connection, ENTITY_TYPE, List.of(id)).get(id);
            boolean draftChanged = draftChangeRepository.existsByEntity(connection, ENTITY_TYPE, id);
            return toDetail(record, secret, state, draftChanged);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "凭证详情读取失败");
        }
    }

    public ManagementOperationResult<CredentialDetail> create(UUID poolId, CredentialCreateCommand command,
                                                              WriteContext ctx) {
        validateCommon(command.weight(), command.rpmLimit(), command.tpmLimit(), command.concurrentLimit());
        String name = requireName(command.name());
        SecretSource source = parseSource(command.secretSource());
        SecretManager.Prepared prepared = source == SecretSource.INLINE_ENCRYPTED
                ? secretManager.prepareInline(command.secretValue(), command.secretValueConfirm())
                : secretManager.prepareExternal(command.secretRef());

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        CredentialRecord record = new CredentialRecord(
                id, poolId, name, source.name(),
                command.weight() == null ? 1 : command.weight(),
                command.rpmLimit(), command.tpmLimit(), command.concurrentLimit(),
                command.enabled() == null || command.enabled(),
                1L, now, now, null);

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "CREATE", ENTITY_TYPE, id.toString(), 0L,
                null,
                connection -> {
                    requirePool(connection, poolId);
                    if (credentialRepository.existsAliveByName(connection, poolId, name)) {
                        throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                                "同池下凭证名称已存在", "name");
                    }
                    credentialRepository.insert(connection, record);
                    secretRepository.upsert(connection, new SecretRecord(
                            id, prepared.secretCiphertext(), prepared.secretRefCiphertext(),
                            prepared.encryptionKeyId(), prepared.maskedValue(),
                            1L, null, now));
                    return new DraftEntityChange(ENTITY_TYPE, id, name, "CREATE", 1L, List.of(
                            FieldChange.changed("name", null, name),
                            FieldChange.changed("secret_source", null, source.name()),
                            FieldChange.sensitiveChanged("secret_value"),
                            FieldChange.changed("weight", null, record.weight()),
                            FieldChange.changed("enabled", null, record.enabled())));
                }));
        return new ManagementOperationResult<>(id.toString(), result.entityVersion(), get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<CredentialDetail> update(UUID id, CredentialUpdateCommand command,
                                                              WriteContext ctx) {
        validateCommon(command.weight(), command.rpmLimit(), command.tpmLimit(), command.concurrentLimit());
        String name = requireName(command.name());
        SecretSource currentSource = SecretSource.valueOf(load(id).secretSource());
        if (currentSource == SecretSource.EXTERNAL_REF && command.secretRef() == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "EXTERNAL_REF 凭证必须提交 secret_ref", "secret_ref");
        }
        SecretManager.Prepared refPrepared = currentSource == SecretSource.EXTERNAL_REF
                ? secretManager.prepareExternal(command.secretRef())
                : null;

        long newVersion = command.version() + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE, id.toString(), command.version(),
                connection -> credentialRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    CredentialRecord persisted = credentialRepository.find(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
                    if (!persisted.name().equals(name)
                            && credentialRepository.existsAliveByName(connection, persisted.poolId(), name)) {
                        throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                                "同池下凭证名称已存在", "name");
                    }
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    CredentialRecord updated = new CredentialRecord(
                            persisted.id(), persisted.poolId(), name, persisted.secretSource(),
                            command.weight() == null ? persisted.weight() : command.weight(),
                            command.rpmLimit(), command.tpmLimit(), command.concurrentLimit(),
                            command.enabled() == null ? persisted.enabled() : command.enabled(),
                            newVersion, persisted.createdAt(), now, null);
                    credentialRepository.update(connection, updated);
                    if (refPrepared != null) {
                        SecretRecord secret = secretRepository.find(connection, id)
                                .orElseThrow(() -> new LightAiException(ErrorCode.SECRET_RESOLUTION_FAILED,
                                        "凭证秘密尚未写入"));
                        secretRepository.upsert(connection, new SecretRecord(id, null,
                                refPrepared.secretRefCiphertext(), refPrepared.encryptionKeyId(),
                                refPrepared.maskedValue(), secret.secretVersion(), secret.rotatedAt(), now));
                    }
                    return new DraftEntityChange(ENTITY_TYPE, id, name, "UPDATE", newVersion, List.of(
                            FieldChange.changed("name", persisted.name(), name),
                            FieldChange.changed("weight", persisted.weight(), updated.weight()),
                            FieldChange.changed("enabled", persisted.enabled(), updated.enabled())));
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    /** 轮换：独立事务、即时生效；不进入草稿差异，secret_version+1。 */
    public ManagementOperationResult<CredentialDetail> rotate(UUID id, CredentialRotateCommand command,
                                                              WriteContext ctx) {
        transaction.executeWithoutResult(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            CredentialRecord current = credentialRepository.find(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
            if (current.version() != command.version()) {
                throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                        null, ctx.requestId(), null, current.version(), null, null);
            }
            if (!SecretSource.INLINE_ENCRYPTED.name().equals(current.secretSource())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                        "EXTERNAL_REF 凭证不支持密钥轮换，请通过编辑更新引用", "secret_source");
            }
            SecretManager.Prepared prepared = secretManager.prepareInline(
                    command.secretValue(), command.secretValueConfirm());
            SecretRecord existing = secretRepository.find(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.SECRET_RESOLUTION_FAILED, "凭证秘密尚未写入"));
            OffsetDateTime now = OffsetDateTime.now(clock);
            secretRepository.upsert(connection, new SecretRecord(
                    id, prepared.secretCiphertext(), null, prepared.encryptionKeyId(),
                    prepared.maskedValue(), existing.secretVersion() + 1, now, now));
            credentialRepository.update(connection, new CredentialRecord(
                    current.id(), current.poolId(), current.name(), current.secretSource(),
                    current.weight(), current.rpmLimit(), current.tpmLimit(), current.concurrentLimit(),
                    current.enabled(), current.version() + 1, current.createdAt(), now, null));
            auditService.recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), ctx.requestId(), ctx.operatorId(), "ROTATE",
                    ENTITY_TYPE, id.toString(), List.of(FieldChange.sensitiveChanged("secret_value")),
                    ctx.sourceMode(), ctx.sourceIpMasked()));
        });
        return new ManagementOperationResult<>(id.toString(), load(id).version(), get(id),
                false, null, ctx.requestId());
    }

    public ManagementOperationResult<CredentialDetail> changeEnabled(UUID id, boolean enable, long version,
                                                                     String confirmedImpactVersion,
                                                                     WriteContext ctx) {
        if (!enable && confirmedImpactVersion != null && !confirmedImpactVersion.isBlank()) {
            assertImpactConfirmed(id, confirmedImpactVersion);
        }
        long newVersion = version + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                enable ? "ENABLE" : "DISABLE", ENTITY_TYPE, id.toString(), version,
                connection -> credentialRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    CredentialRecord persisted = credentialRepository.find(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
                    CredentialRecord updated = new CredentialRecord(
                            persisted.id(), persisted.poolId(), persisted.name(), persisted.secretSource(),
                            persisted.weight(), persisted.rpmLimit(), persisted.tpmLimit(), persisted.concurrentLimit(),
                            enable, newVersion, persisted.createdAt(), OffsetDateTime.now(clock), null);
                    credentialRepository.update(connection, updated);
                    return new DraftEntityChange(ENTITY_TYPE, id, persisted.name(),
                            enable ? "ENABLE" : "DISABLE", newVersion, List.of(
                            FieldChange.changed("enabled", persisted.enabled(), enable)));
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<CredentialDetail> delete(UUID id, long version, WriteContext ctx) {
        if (runningAttemptCounter.countRunning(id) > 0) {
            throw new LightAiException(ErrorCode.CAPACITY_IN_USE, "凭证仍有运行中调用，不能删除");
        }
        long newVersion = version + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "DELETE", ENTITY_TYPE, id.toString(), version,
                connection -> credentialRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    CredentialRecord persisted = credentialRepository.find(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    credentialRepository.update(connection, new CredentialRecord(
                            persisted.id(), persisted.poolId(), persisted.name(), persisted.secretSource(),
                            persisted.weight(), persisted.rpmLimit(), persisted.tpmLimit(), persisted.concurrentLimit(),
                            persisted.enabled(), newVersion, persisted.createdAt(), now, now));
                    return new DraftEntityChange(ENTITY_TYPE, id, persisted.name(), "DELETE", newVersion,
                            List.of());
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, null,
                true, result.draftRevision(), ctx.requestId());
    }

    public ImpactAnalysis impact(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            credentialRepository.find(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
            return impactService.analyze(connection, ENTITY_TYPE, id);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析读取失败");
        }
    }

    public List<com.lightai.client.access.ProviderCheckRecordView> recentChecks(UUID id, int limit,
                                                                                boolean includeProviderRequestId) {
        try (Connection connection = dataSource.getConnection()) {
            return checkRecordRepository.findLatestByTarget(connection, id, limit).stream()
                    .map(record -> com.lightai.admin.check.ManagementCheckService.toView(record, includeProviderRequestId))
                    .toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "检测记录读取失败");
        }
    }

    private void assertImpactConfirmed(UUID id, String confirmedImpactVersion) {
        try (Connection connection = dataSource.getConnection()) {
            impactService.assertConfirmed(connection, ENTITY_TYPE, id, confirmedImpactVersion);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析读取失败");
        }
    }

    private void requirePool(Connection connection, UUID poolId) {
        referenceQuery.findPool(connection, poolId)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证池不存在或已删除"));
    }

    private CredentialRecord load(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            return credentialRepository.find(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "凭证读取失败");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.trim().length() < 2 || name.trim().length() > 64) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "name 长度 2—64", "name");
        }
        return name.trim();
    }

    private static SecretSource parseSource(String source) {
        if (source == null) {
            return SecretSource.INLINE_ENCRYPTED;
        }
        try {
            return SecretSource.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "secret_source 仅支持 INLINE_ENCRYPTED/EXTERNAL_REF", "secret_source");
        }
    }

    private static void validateCommon(Integer weight, Long rpm, Long tpm, Integer concurrent) {
        if (weight != null && (weight < 1 || weight > 100)) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "weight 范围 1—100", "weight");
        }
        if (rpm != null && rpm <= 0) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "rpm_limit 必须为正整数或空", "rpm_limit");
        }
        if (tpm != null && tpm <= 0) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "tpm_limit 必须为正整数或空", "tpm_limit");
        }
        if (concurrent != null && (concurrent < 1 || concurrent > 100000)) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "concurrent_limit 范围 1—100000 或空",
                    "concurrent_limit");
        }
    }

    private static String healthOf(Map<UUID, ObjectRuntimeStateRepository.RuntimeStateRow> states, UUID id) {
        ObjectRuntimeStateRepository.RuntimeStateRow state = states.get(id);
        if (state == null || state.healthStatus() == null) {
            return "UNKNOWN";
        }
        return state.healthStatus();
    }

    private static CredentialListItem toListItem(CredentialRepository.CredentialRow row,
                                                 ObjectRuntimeStateRepository.RuntimeStateRow state,
                                                 boolean draftChanged) {
        CredentialRecord record = row.record();
        return new CredentialListItem(
                record.id().toString(), record.poolId().toString(), record.name(), record.secretSource(),
                row.maskedValue(),
                record.weight(), record.rpmLimit(), record.tpmLimit(), record.concurrentLimit(),
                state == null || state.healthStatus() == null ? "UNKNOWN" : state.healthStatus(),
                state == null ? null : state.resetAt(),
                state == null ? null : state.lastSuccessAt(),
                state == null ? null : state.lastCheckedAt(),
                record.enabled(), draftChanged, record.version(), record.updatedAt());
    }

    private static CredentialDetail toDetail(CredentialRecord record, SecretRecord secret,
                                             ObjectRuntimeStateRepository.RuntimeStateRow state,
                                             boolean draftChanged) {
        return new CredentialDetail(
                record.id().toString(), record.poolId().toString(), record.name(), record.secretSource(),
                secret == null ? null : secret.maskedValue(),
                record.weight(), record.rpmLimit(), record.tpmLimit(), record.concurrentLimit(),
                record.enabled(),
                state == null || state.healthStatus() == null ? "UNKNOWN" : state.healthStatus(),
                state == null ? null : state.resetAt(),
                state == null ? null : state.lastSuccessAt(),
                state == null ? null : state.lastCheckedAt(),
                state == null ? null : state.lastFailedAt(),
                state == null ? null : state.lastErrorCode(),
                state == null ? null : state.lastErrorSummary(),
                draftChanged,
                secret == null ? 0L : secret.secretVersion(),
                secret == null ? null : secret.rotatedAt(),
                record.createdAt(), record.updatedAt(), record.version());
    }
}
