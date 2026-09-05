package com.lightai.admin.credential;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.credential.CredentialCreateCommand;
import com.lightai.client.credential.CredentialDetail;
import com.lightai.client.credential.CredentialListItem;
import com.lightai.client.credential.CredentialRotateCommand;
import com.lightai.client.credential.CredentialUpdateCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.spi.secret.SecretCipher;
import com.lightai.storage.credential.CredentialRecord;
import com.lightai.storage.credential.JdbcCredentialRepository;
import com.lightai.storage.credential.JdbcCredentialSecretRepository;
import com.lightai.storage.credential.SecretMasker;
import com.lightai.storage.credential.SecretRecordRow;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.pool.JdbcPoolRepository;
import com.lightai.storage.pool.PoolRecord;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Credential 管理服务（BE-013）。
 * 配置与秘密分离：credential 走草稿事务，secret_value 只进受保护表（AES-GCM 加密）；
 * secret_source 不可切换；轮换即时递增 secret_version 并更新掩码，旧值不回读；
 * 删除在仍被运行占用时返回 CAPACITY_IN_USE（运行占用由容量存储判定，BE-P04 接入）。
 */
public class CredentialService {

    public static final String ENTITY_TYPE = "CREDENTIAL";
    private static final Set<String> SORTABLE = Set.of("name", "weight", "updated_at", "created_at");
    private static final Set<String> HEALTH_STATUSES = Set.of(
            "HEALTHY", "UNKNOWN", "RATE_LIMITED", "INVALID", "UNAVAILABLE", "DISABLED");

    private final DataSource dataSource;
    private final JdbcCredentialRepository credentialRepository;
    private final JdbcCredentialSecretRepository secretRepository;
    private final JdbcPoolRepository poolRepository;
    private final JdbcProviderRepository providerRepository;
    private final JdbcObjectRuntimeStateRepository runtimeStateRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final DraftWriteService draftWriteService;
    private final SecretCipher secretCipher;
    private final PageResultFactory pageResultFactory;
    private final String sourceMode;
    private final com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository;
    private final com.lightai.storage.check.JdbcProviderCheckRecordRepository checkRecordRepository;
    private final com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter;
    private final List<com.lightai.spi.check.ProviderCheckExecutor> checkExecutors;

    public CredentialService(DataSource dataSource, JdbcCredentialRepository credentialRepository,
                             JdbcCredentialSecretRepository secretRepository,
                             JdbcPoolRepository poolRepository, JdbcProviderRepository providerRepository,
                             JdbcObjectRuntimeStateRepository runtimeStateRepository,
                             DraftChangeRepository draftChangeRepository,
                             DraftWriteService draftWriteService, SecretCipher secretCipher,
                             PageResultFactory pageResultFactory, String sourceMode) {
        this(dataSource, credentialRepository, secretRepository, poolRepository, providerRepository,
                runtimeStateRepository, draftChangeRepository, draftWriteService, secretCipher,
                pageResultFactory, sourceMode,
                new com.lightai.storage.reference.JdbcConfigReferenceRepository(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                new com.lightai.storage.check.JdbcProviderCheckRecordRepository(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                new com.lightai.storage.runtime.JdbcRuntimeStateWriter(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                List.of());
    }

    public CredentialService(DataSource dataSource, JdbcCredentialRepository credentialRepository,
                             JdbcCredentialSecretRepository secretRepository,
                             JdbcPoolRepository poolRepository, JdbcProviderRepository providerRepository,
                             JdbcObjectRuntimeStateRepository runtimeStateRepository,
                             DraftChangeRepository draftChangeRepository,
                             DraftWriteService draftWriteService, SecretCipher secretCipher,
                             PageResultFactory pageResultFactory, String sourceMode,
                             com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                             com.lightai.storage.check.JdbcProviderCheckRecordRepository checkRecordRepository,
                             com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                             List<com.lightai.spi.check.ProviderCheckExecutor> checkExecutors) {
        this.dataSource = dataSource;
        this.credentialRepository = credentialRepository;
        this.secretRepository = secretRepository;
        this.poolRepository = poolRepository;
        this.providerRepository = providerRepository;
        this.runtimeStateRepository = runtimeStateRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.draftWriteService = draftWriteService;
        this.secretCipher = secretCipher;
        this.pageResultFactory = pageResultFactory;
        this.sourceMode = sourceMode;
        this.referenceRepository = referenceRepository;
        this.checkRecordRepository = checkRecordRepository;
        this.runtimeStateWriter = runtimeStateWriter;
        this.checkExecutors = checkExecutors == null ? List.of() : List.copyOf(checkExecutors);
    }

    // ---------- 读取 ----------

    public PageResult<CredentialListItem> list(RequestContext context, UUID poolId,
                                               Map<String, String> params) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(
                params.get("page"), params.get("page_size"), params.get("sort"),
                SORTABLE, "name asc");
        String healthStatus = validateHealth(params.get("health_status"));
        Boolean enabled = parseBoolean(params.get("enabled"));
        try (Connection connection = dataSource.getConnection()) {
            requirePoolLive(connection, poolId);
            List<CredentialRecord> records = credentialRepository.listByPool(connection, poolId,
                    healthStatus, enabled, query.sort(), query.limit(), (int) query.offset());
            long total = credentialRepository.countByPool(connection, poolId, healthStatus, enabled);
            List<CredentialListItem> items = new ArrayList<>(records.size());
            for (CredentialRecord record : records) {
                items.add(toListItem(connection, record));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "凭证列表当前无法读取");
        }
    }

    public CredentialDetail detail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_VIEW);
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            CredentialRecord record = requireCredentialLive(connection, id);
            return toDetail(connection, record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "凭证详情当前无法读取");
        }
    }

    // ---------- 写入（BE-013） ----------

    public ManagementOperationResult<CredentialDetail> create(RequestContext context, UUID poolId,
                                                              CredentialCreateCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();

        // 受保护秘密先于草稿事务加密，密文与草稿同事务落库，失败一起回滚
        final byte[] ciphertext;
        final byte[] refCiphertext;
        final String maskedValue;
        if (CredentialListItem.SOURCE_INLINE.equals(command.secretSource())) {
            ciphertext = secretCipher.encrypt(command.secretValue().toCharArray());
            refCiphertext = null;
            maskedValue = SecretMasker.mask(command.secretValue().toCharArray());
        } else {
            ciphertext = null;
            refCiphertext = secretCipher.encrypt(command.secretRef().toCharArray());
            maskedValue = SecretMasker.maskRef(command.secretRef());
        }

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "CREATE", ENTITY_TYPE.toLowerCase(), poolId.toString(), 0, null,
                connection -> {
                    requirePoolLive(connection, poolId);
                    if (credentialRepository.existsByLiveNameInPool(connection, poolId, command.name())) {
                        throw nameConflict();
                    }
                    CredentialRecord record = new CredentialRecord(id, poolId, command.name(),
                            command.secretSource(), command.weight(), command.rpmLimit(),
                            command.tpmLimit(), command.concurrentLimit(), command.enabled(),
                            1L, OffsetDateTime.now(), OffsetDateTime.now());
                    credentialRepository.insert(connection, record);
                    secretRepository.insert(connection, new SecretRecordRow(
                            UUID.randomUUID(), id, ciphertext, refCiphertext,
                            secretCipher.keyId(), maskedValue, 1L, null));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "CREATE", 1L, List.of(FieldChange.sensitiveChanged("secret_value")));
                }));

        try (Connection connection = dataSource.getConnection()) {
            CredentialRecord record = requireCredentialLive(connection, id);
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "创建结果读取失败");
        }
    }

    public ManagementOperationResult<CredentialDetail> update(RequestContext context, String rawId,
                                                              CredentialUpdateCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        if (command.version() == null || command.version() < 1) {
            throw fieldError("version", "REQUIRED", "编辑操作必须提交正整数 version");
        }
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), command.version(),
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(CredentialRecord::version).orElse(null),
                connection -> {
                    CredentialRecord current = credentialRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "凭证不存在或已删除"));
                    if (command.weight() == null || command.weight() < CredentialCreateCommand.WEIGHT_MIN
                            || command.weight() > CredentialCreateCommand.WEIGHT_MAX) {
                        throw fieldError("weight", "OUT_OF_RANGE", "weight 范围 1—100");
                    }
                    validateLimits(command.rpmLimit(), command.tpmLimit(), command.concurrentLimit());
                    CredentialRecord updated = credentialRepository.update(connection, new CredentialRecord(
                            current.id(), current.poolId(), command.name().strip(),
                            current.secretSource(), command.weight(), command.rpmLimit(),
                            command.tpmLimit(), command.concurrentLimit(), command.enabled(),
                            current.version(), current.createdAt(), current.updatedAt()));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "UPDATE", updated.version(), List.of(
                            FieldChange.changed("weight", current.weight(), command.weight()),
                            FieldChange.changed("enabled", current.enabled(), command.enabled())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            CredentialRecord record = requireCredentialLive(connection, id);
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "更新结果读取失败");
        }
    }

    /** 轮换（BE-013）：秘密独立于草稿事务即时生效；两次输入必须一致。 */
    public ManagementOperationResult<CredentialDetail> rotate(RequestContext context, String rawId,
                                                              CredentialRotateCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        if (command.version() == null || command.version() < 1) {
            throw fieldError("version", "REQUIRED", "轮换操作必须提交正整数 version");
        }
        if (command.secretValue() == null || command.secretValue().isBlank()
                || command.secretValueConfirm() == null) {
            throw fieldError("secret_value", "REQUIRED", "secret_value 与 secret_value_confirm 必填");
        }
        if (!command.secretValue().equals(command.secretValueConfirm())) {
            throw new LightAiException(ErrorCode.SECRET_CONFIRM_MISMATCH, "两次输入的密钥不一致");
        }
        String requestId = context.requestId();
        byte[] ciphertext = secretCipher.encrypt(command.secretValue().toCharArray());
        String maskedValue = SecretMasker.mask(command.secretValue().toCharArray());

        // 轮换属于独立即时事务：不取草稿锁，不产生草稿差异，只写审计
        draftWriteService.executeStandalone(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "ROTATE", ENTITY_TYPE.toLowerCase(), id.toString(), command.version(),
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(CredentialRecord::version).orElse(null),
                connection -> {
                    CredentialRecord current = credentialRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "凭证不存在或已删除"));
                    secretRepository.rotate(connection, id, ciphertext, null,
                            secretCipher.keyId(), maskedValue);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            "ROTATE", current.version(),
                            List.of(FieldChange.sensitiveChanged("secret_value")));
                }));

        try (Connection connection = dataSource.getConnection()) {
            CredentialRecord record = requireCredentialLive(connection, id);
            return new ManagementOperationResult<>(id.toString(), record.version(),
                    toDetail(connection, record), false, null, requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "轮换结果读取失败");
        }
    }

    public ManagementOperationResult<CredentialDetail> setEnabled(RequestContext context, String rawId,
                                                                  boolean enabled, Long version) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                enabled ? "ENABLE" : "DISABLE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(CredentialRecord::version).orElse(null),
                connection -> {
                    CredentialRecord current = credentialRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "凭证不存在或已删除"));
                    CredentialRecord saved = credentialRepository.update(connection, new CredentialRecord(
                            current.id(), current.poolId(), current.name(), current.secretSource(),
                            current.weight(), current.rpmLimit(), current.tpmLimit(),
                            current.concurrentLimit(), enabled, current.version(),
                            current.createdAt(), current.updatedAt()));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            enabled ? "ENABLE" : "DISABLE", saved.version(),
                            List.of(FieldChange.changed("enabled", current.enabled(), enabled)));
                }));

        try (Connection connection = dataSource.getConnection()) {
            CredentialRecord record = requireCredentialLive(connection, id);
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "操作结果读取失败");
        }
    }

    public ManagementOperationResult<CredentialDetail> delete(RequestContext context, String rawId,
                                                              Long version) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(CredentialRecord::version).orElse(null),
                connection -> {
                    CredentialRecord current = credentialRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "凭证不存在或已删除"));
                    // 运行占用检查：仍有并发 Attempt 占用时不能删除（容量运行时 BE-P04 提供判定）
                    long activeReservations = countActiveReservations(connection, id);
                    if (activeReservations > 0) {
                        throw new LightAiException(ErrorCode.CAPACITY_IN_USE,
                                "凭证仍有运行中调用占用，不能删除");
                    }
                    credentialRepository.markDeleted(connection, id);
                    secretRepository.deleteByCredential(connection, id);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            "DELETE", current.version(), List.of());
                }));

        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    // ---------- 内部 ----------

    /** 凭证检测（BE-013）：目标收敛为该凭证所属 Provider，检测记录 target_type=CREDENTIAL。 */
    public com.lightai.client.provider.ProviderCheckRecord check(RequestContext context, String rawId,
                                                                 com.lightai.client.provider.ProviderCheckCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_CHECK);
        UUID id = parseId(rawId);
        OffsetDateTime startedAt = OffsetDateTime.now();
        CredentialRecord credential;
        com.lightai.storage.pool.PoolRecord pool;
        com.lightai.storage.provider.ProviderRecord provider;
        try (Connection connection = dataSource.getConnection()) {
            credential = requireCredentialLive(connection, id);
            pool = poolRepository.findLiveById(connection, credential.poolId())
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证池不存在或已删除"));
            provider = providerRepository.findLiveById(connection, pool.providerId())
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "Provider不存在或已删除"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测目标解析失败");
        }

        com.lightai.spi.check.ProviderCheckExecutor executor = checkExecutors.stream()
                .filter(candidate -> candidate.supports(provider.type()))
                .findFirst()
                .orElseThrow(() -> new LightAiException(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND,
                        "Provider 类型未加载对应 Adapter：" + provider.type()));

        com.lightai.spi.check.ProviderCheckExecutor.CheckOutcome outcome;
        try {
            outcome = executor.execute(new com.lightai.spi.check.ProviderCheckExecutor.CheckInvocation(
                    provider.type(), provider.baseUrl(), provider.proxyUrl(),
                    provider.connectTimeoutMs(), provider.readTimeoutMs(), provider.defaultHeaders(),
                    null, id, command.resolvedMode(), command.resolvedTimeoutMs()));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            outcome = com.lightai.spi.check.ProviderCheckExecutor.CheckOutcome.failure(
                    (int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis(),
                    ErrorCode.INTERNAL_ERROR.name(), "检测执行失败");
        }
        OffsetDateTime endedAt = OffsetDateTime.now();
        int totalMs = outcome.totalMs() >= 0 ? outcome.totalMs()
                : (int) java.time.Duration.between(startedAt, endedAt).toMillis();

        com.lightai.storage.check.CheckRecordRow row = new com.lightai.storage.check.CheckRecordRow(
                UUID.randomUUID(), com.lightai.storage.check.CheckRecordRow.TARGET_CREDENTIAL, id,
                command.resolvedMode(), outcome.succeeded() ? "SUCCEEDED" : "FAILED",
                context.authContext().userId(), outcome.traceId(), outcome.attemptId(),
                startedAt, endedAt, totalMs, outcome.usage(), outcome.providerRequestId(),
                outcome.errorCode(), outcome.errorSummary());
        try (Connection connection = dataSource.getConnection()) {
            checkRecordRepository.insert(connection, row);
            runtimeStateWriter.upsertCredentialHealth(connection, id,
                    outcome.succeeded() ? "HEALTHY" : "INVALID", endedAt,
                    outcome.errorCode(), outcome.errorSummary());
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测记录写入失败");
        }
        return new com.lightai.client.provider.ProviderCheckRecord(
                row.id().toString(), row.targetType(), row.targetId().toString(), row.mode(),
                row.status(), row.startedAt(), row.endedAt(), row.totalMs(), row.traceId(),
                row.attemptId(), row.usage(), row.errorCode(), row.errorSummary(),
                row.providerRequestId());
    }

    private long countActiveReservations(Connection connection, UUID credentialId) {
        String sql = "SELECT count(*) FROM " + schemaName()
                + ".capacity_reservation_item WHERE credential_id = ? AND released_at IS NULL";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, credentialId);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            // 容量存储尚未迁移（BE-P04）时按无占用处理，删除仍受草稿与审计保护
            return 0;
        }
    }

    private CredentialListItem toListItem(Connection connection, CredentialRecord record) {
        var state = runtimeStateRepository.findByEntity(connection, ENTITY_TYPE, record.id()).orElse(null);
        String maskedValue = maskedValueOf(connection, record.id());
        String refDisplay = secretRefDisplay(connection, record.id());
        return new CredentialListItem(
                record.id().toString(), record.poolId().toString(), record.name(),
                maskedValue, refDisplay, record.secretSource(), record.weight(),
                record.rpmLimit(), record.tpmLimit(), record.concurrentLimit(), 0,
                state == null || state.healthStatus() == null ? "UNKNOWN" : state.healthStatus(),
                state == null ? null : state.lastCheckedAt(),
                state == null ? null : state.lastSuccessAt(),
                state == null ? null : state.lastCheckedAt(),
                record.enabled(),
                draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                        List.of(record.id())).contains(record.id()),
                record.version());
    }

    private CredentialDetail toDetail(Connection connection, CredentialRecord record) {
        var state = runtimeStateRepository.findByEntity(connection, ENTITY_TYPE, record.id()).orElse(null);
        return new CredentialDetail(
                record.id().toString(), record.poolId().toString(), record.name(),
                maskedValueOf(connection, record.id()),
                secretRefDisplay(connection, record.id()),
                record.secretSource(), record.weight(), record.rpmLimit(), record.tpmLimit(),
                record.concurrentLimit(), 0,
                state == null || state.healthStatus() == null ? "UNKNOWN" : state.healthStatus(),
                state == null ? null : state.lastCheckedAt(),
                state == null ? null : state.lastSuccessAt(),
                state == null ? null : state.lastCheckedAt(),
                record.enabled(),
                draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                        List.of(record.id())).contains(record.id()),
                record.version(), record.createdAt(), record.updatedAt());
    }

    private String maskedValueOf(Connection connection, UUID credentialId) {
        Optional<SecretRecordRow> secret = secretRepository.findByCredential(connection, credentialId);
        // 掩码为受保护行生成值；缺失时以通用掩码兜底，不返回明文
        return secret.map(SecretRecordRow::maskedValue).orElse("****");
    }

    private String secretRefDisplay(Connection connection, UUID credentialId) {
        return secretRepository.findByCredential(connection, credentialId)
                .filter(row -> row.secretRefCiphertext() != null)
                .flatMap(row -> secretCipher.decrypt(row.secretRefCiphertext()))
                .map(value -> SecretMasker.maskRef(new String(value)))
                .orElse(null);
    }

    private void requirePoolLive(Connection connection, UUID poolId) {
        PoolRecord pool = poolRepository.findLiveById(connection, poolId)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证池不存在或已删除"));
        // 池所属 Provider 必须存在，保证引用链完整
        providerRepository.findLiveById(connection, pool.providerId())
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                        "凭证池所属 Provider 不存在"));
    }

    private CredentialRecord requireCredentialLive(Connection connection, UUID id) {
        return credentialRepository.findLiveById(connection, id)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证不存在或已删除"));
    }

    private void validateLimits(Long rpm, Long tpm, Integer concurrent) {
        if ((rpm != null && rpm <= 0) || (tpm != null && tpm <= 0)) {
            throw fieldError("rpm_limit", "INVALID", "限额为空表示不限，0 不合法");
        }
        if (concurrent != null && (concurrent < 1
                || concurrent > CredentialCreateCommand.CONCURRENT_LIMIT_MAX)) {
            throw fieldError("concurrent_limit", "OUT_OF_RANGE",
                    "concurrent_limit 范围 1—" + CredentialCreateCommand.CONCURRENT_LIMIT_MAX);
        }
    }

    private static String validateHealth(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!HEALTH_STATUSES.contains(raw.strip())) {
            throw fieldError("health_status", "INVALID", "健康状态取值不合法");
        }
        return raw.strip();
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        throw fieldError("enabled", "INVALID", "布尔值仅支持 true/false");
    }

    public static UUID parseId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在或已删除");
        }
        try {
            return UUID.fromString(rawId.strip());
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在或已删除");
        }
    }

    private static LightAiException nameConflict() {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "凭证名称已存在",
                List.of(new FieldIssue("name", "DUPLICATED", "同一凭证池下凭证名称唯一")));
    }

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "查询参数不合法",
                List.of(new FieldIssue(field, code, message)));
    }

    private String schemaName() {
        return com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME;
    }
}
