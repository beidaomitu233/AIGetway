package com.lightai.admin.channel;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.channel.ChannelCredentialCreateCommand;
import com.lightai.client.channel.ChannelCredentialDetail;
import com.lightai.client.channel.ChannelCredentialListItem;
import com.lightai.client.channel.ChannelCredentialRotateCommand;
import com.lightai.client.channel.ChannelCredentialUpdateCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.spi.secret.SecretCipher;
import com.lightai.storage.channel.ChannelCredentialRecord;
import com.lightai.storage.channel.ChannelRecord;
import com.lightai.storage.channel.JdbcChannelCredentialRepository;
import com.lightai.storage.channel.JdbcChannelRepository;
import com.lightai.storage.channel.SecretMasker;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 渠道 Key 管理服务（BE-013 / V2 资源域）。
 * 密钥密文（原 credential_secret 保护列）与渠道 Key 同行落库，明文只以 AES-GCM 密文承载；
 * secret_source 由密文列推导且创建后不可切换；轮换即时递增 secret_version 并更新掩码，旧值不回读；
 * 删除在仍被运行占用时返回 CAPACITY_IN_USE（运行占用由容量存储判定，BE-P04 接入）。
 */
public class ChannelCredentialService {

    public static final String ENTITY_TYPE = "CHANNEL_CREDENTIAL";
    private static final int DEFAULT_PRIORITY = 10;
    private static final Set<String> SORTABLE = Set.of("name", "weight", "updated_at", "created_at");
    private static final Set<String> HEALTH_STATUSES = Set.of(
            "HEALTHY", "UNKNOWN", "RATE_LIMITED", "INVALID", "UNAVAILABLE", "DISABLED");

    private final DataSource dataSource;
    private final JdbcChannelCredentialRepository credentialRepository;
    private final JdbcChannelRepository channelRepository;
    private final JdbcObjectRuntimeStateRepository runtimeStateRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final DraftWriteService draftWriteService;
    private final SecretCipher secretCipher;
    private final PageResultFactory pageResultFactory;
    private final String sourceMode;
    private final com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository;
    private final com.lightai.storage.check.JdbcChannelCheckRecordRepository checkRecordRepository;
    private final com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter;
    private final List<com.lightai.spi.check.ProviderCheckExecutor> checkExecutors;

    /** 便捷构造（单测/本地装配）：省略引用、检测记录与运行态写入依赖。 */
    public ChannelCredentialService(DataSource dataSource, JdbcChannelCredentialRepository credentialRepository,
                                    JdbcChannelRepository channelRepository,
                                    JdbcObjectRuntimeStateRepository runtimeStateRepository,
                                    DraftChangeRepository draftChangeRepository,
                                    DraftWriteService draftWriteService, SecretCipher secretCipher,
                                    PageResultFactory pageResultFactory, String sourceMode) {
        this(dataSource, credentialRepository, channelRepository, runtimeStateRepository,
                draftChangeRepository, draftWriteService, secretCipher, pageResultFactory, sourceMode,
                new com.lightai.storage.reference.JdbcConfigReferenceRepository(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                new com.lightai.storage.check.JdbcChannelCheckRecordRepository(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                new com.lightai.storage.runtime.JdbcRuntimeStateWriter(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                List.of());
    }

    public ChannelCredentialService(DataSource dataSource, JdbcChannelCredentialRepository credentialRepository,
                                    JdbcChannelRepository channelRepository,
                                    JdbcObjectRuntimeStateRepository runtimeStateRepository,
                                    DraftChangeRepository draftChangeRepository,
                                    DraftWriteService draftWriteService, SecretCipher secretCipher,
                                    PageResultFactory pageResultFactory, String sourceMode,
                                    com.lightai.storage.reference.JdbcConfigReferenceRepository referenceRepository,
                                    com.lightai.storage.check.JdbcChannelCheckRecordRepository checkRecordRepository,
                                    com.lightai.storage.runtime.JdbcRuntimeStateWriter runtimeStateWriter,
                                    List<com.lightai.spi.check.ProviderCheckExecutor> checkExecutors) {
        this.dataSource = dataSource;
        this.credentialRepository = credentialRepository;
        this.channelRepository = channelRepository;
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

    public PageResult<ChannelCredentialListItem> list(RequestContext context, UUID channelId,
                                                      Map<String, String> params) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(
                params.get("page"), params.get("page_size"), params.get("sort"),
                SORTABLE, "name asc");
        String healthStatus = validateHealth(params.get("health_status"));
        Boolean enabled = parseBoolean(params.get("enabled"));
        try (Connection connection = dataSource.getConnection()) {
            requireChannelLive(connection, channelId);
            List<ChannelCredentialRecord> records = credentialRepository.listByChannel(connection, channelId,
                    healthStatus, enabled, query.sort(), query.limit(), (int) query.offset());
            long total = credentialRepository.countByChannel(connection, channelId, healthStatus, enabled);
            List<ChannelCredentialListItem> items = new ArrayList<>(records.size());
            for (ChannelCredentialRecord record : records) {
                items.add(toListItem(connection, record));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "渠道 Key 列表当前无法读取");
        }
    }

    public ChannelCredentialDetail detail(RequestContext context, String rawChannelId, String rawId) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_VIEW);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawChannelId);
        try (Connection connection = dataSource.getConnection()) {
            ChannelCredentialRecord record = requireInChannel(connection, parentId, requireCredentialLive(connection, id));
            return toDetail(connection, record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "渠道 Key 详情当前无法读取");
        }
    }

    // ---------- 写入（BE-013） ----------

    public ManagementOperationResult<ChannelCredentialDetail> create(RequestContext context, UUID channelId,
                                                                    ChannelCredentialCreateCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();

        // 受保护秘密先于草稿事务加密，密文与草稿同事务落库，失败一起回滚
        final byte[] ciphertext;
        final byte[] refCiphertext;
        final String maskedValue;
        if (ChannelCredentialListItem.SOURCE_INLINE.equals(command.secretSource())) {
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
                "CREATE", ENTITY_TYPE.toLowerCase(), channelId.toString(), 0, null,
                connection -> {
                    requireChannelLive(connection, channelId);
                    if (credentialRepository.existsByLiveNameInChannel(connection, channelId, command.name())) {
                        throw nameConflict();
                    }
                    ChannelCredentialRecord record = new ChannelCredentialRecord(id, channelId, command.name(),
                            ciphertext, refCiphertext, secretCipher.keyId(), maskedValue, 1L, OffsetDateTime.now(),
                            DEFAULT_PRIORITY, command.weight(), command.rpmLimit(),
                            command.tpmLimit(), command.concurrentLimit(),
                            command.enabled() ? ChannelCredentialRecord.STATUS_ACTIVE
                                    : ChannelCredentialRecord.STATUS_DISABLED,
                            ChannelCredentialRecord.HEALTH_UNKNOWN,
                            1L, OffsetDateTime.now(), OffsetDateTime.now());
                    credentialRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "CREATE", 1L, List.of(FieldChange.sensitiveChanged("secret_value")));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ChannelCredentialRecord record = requireCredentialLive(connection, id);
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "创建结果读取失败");
        }
    }

    public ManagementOperationResult<ChannelCredentialDetail> update(RequestContext context, String rawChannelId, String rawId,
                                                                    ChannelCredentialUpdateCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawChannelId);
        if (command.version() == null || command.version() < 1) {
            throw fieldError("version", "REQUIRED", "编辑操作必须提交正整数 version");
        }
        if (command.name() == null || command.name().strip().length() < 2
                || command.name().strip().length() > 64) {
            throw fieldError("name", "INVALID", "name 长度必须为 2—64");
        }
        if (command.secretRef() != null) {
            throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "秘密引用只能通过轮换修改");
        }
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), command.version(),
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(record -> requireInChannel(connection, parentId, record).version()).orElse(null),
                connection -> {
                    ChannelCredentialRecord current = requireInChannel(connection, parentId,
                            credentialRepository.lockLiveById(connection, id)
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                            "渠道 Key 不存在或已删除")));
                    if (!current.name().equals(command.name().strip())
                            && credentialRepository.existsByLiveNameInChannel(connection, parentId, command.name().strip())) {
                        throw nameConflict();
                    }
                    if (command.weight() == null || command.weight() < ChannelCredentialCreateCommand.WEIGHT_MIN
                            || command.weight() > ChannelCredentialCreateCommand.WEIGHT_MAX) {
                        throw fieldError("weight", "OUT_OF_RANGE", "weight 范围 1—100");
                    }
                    validateLimits(command.rpmLimit(), command.tpmLimit(), command.concurrentLimit());
                    ChannelCredentialRecord updated = credentialRepository.update(connection, new ChannelCredentialRecord(
                            current.id(), current.channelId(), command.name().strip(),
                            current.secretCiphertext(), current.secretRefCiphertext(), current.keyId(),
                            current.maskedValue(), current.secretVersion(), current.rotatedAt(),
                            current.priority(), command.weight(), command.rpmLimit(),
                            command.tpmLimit(), command.concurrentLimit(),
                            command.enabled() ? ChannelCredentialRecord.STATUS_ACTIVE
                                    : ChannelCredentialRecord.STATUS_DISABLED,
                            current.health(), current.version(), current.createdAt(), current.updatedAt()));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "UPDATE", updated.version(), List.of(
                            FieldChange.changed("weight", current.weight(), command.weight()),
                            FieldChange.changed("enabled", current.enabled(), command.enabled())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ChannelCredentialRecord record = requireInChannel(connection, parentId, requireCredentialLive(connection, id));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "更新结果读取失败");
        }
    }

    /** 轮换（BE-013）：秘密独立于草稿事务即时生效；两次输入必须一致。 */
    public ManagementOperationResult<ChannelCredentialDetail> rotate(RequestContext context, String rawChannelId, String rawId,
                                                                    ChannelCredentialRotateCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawChannelId);
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
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), command.version(),
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(record -> requireInChannel(connection, parentId, record).version()).orElse(null),
                connection -> {
                    ChannelCredentialRecord current = requireInChannel(connection, parentId,
                            credentialRepository.lockLiveById(connection, id)
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                            "渠道 Key 不存在或已删除")));
                    // secret_source 不可切换：只写入与既有来源对应的密文列
                    byte[] secret = current.secretRefCiphertext() != null ? null : ciphertext;
                    byte[] ref = current.secretRefCiphertext() != null ? ciphertext : null;
                    String masked = current.secretRefCiphertext() != null
                            ? SecretMasker.maskRef(command.secretValue()) : maskedValue;
                    credentialRepository.updateSecret(connection, id, secret, ref,
                            secretCipher.keyId(), masked);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            "UPDATE", current.version() + 1,
                            List.of(FieldChange.sensitiveChanged("secret_value")));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ChannelCredentialRecord record = requireInChannel(connection, parentId, requireCredentialLive(connection, id));
            return new ManagementOperationResult<>(id.toString(), record.version(),
                    toDetail(connection, record), false, null, requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "轮换结果读取失败");
        }
    }

    public ManagementOperationResult<ChannelCredentialDetail> setEnabled(RequestContext context, String rawChannelId, String rawId,
                                                                        boolean enabled, Long version) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawChannelId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                enabled ? "ENABLE" : "DISABLE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(record -> requireInChannel(connection, parentId, record).version()).orElse(null),
                connection -> {
                    ChannelCredentialRecord current = requireInChannel(connection, parentId,
                            credentialRepository.lockLiveById(connection, id)
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                            "渠道 Key 不存在或已删除")));
                    ChannelCredentialRecord saved = credentialRepository.setEnabled(connection, id, enabled);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            enabled ? "ENABLE" : "DISABLE", saved.version(),
                            List.of(FieldChange.changed("enabled", current.enabled(), enabled)));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ChannelCredentialRecord record = requireInChannel(connection, parentId, requireCredentialLive(connection, id));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "操作结果读取失败");
        }
    }

    public ManagementOperationResult<ChannelCredentialDetail> delete(RequestContext context, String rawChannelId, String rawId,
                                                                    Long version) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_MANAGE);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawChannelId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> credentialRepository.lockLiveById(connection, id)
                        .map(record -> requireInChannel(connection, parentId, record).version()).orElse(null),
                connection -> {
                    ChannelCredentialRecord current = requireInChannel(connection, parentId,
                            credentialRepository.lockLiveById(connection, id)
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                            "渠道 Key 不存在或已删除")));
                    // 运行占用检查：仍有并发 Attempt 占用时不能删除（容量运行时 BE-P04 提供判定）
                    long activeReservations = countActiveReservations(connection, id);
                    if (activeReservations > 0) {
                        throw new LightAiException(ErrorCode.CAPACITY_IN_USE,
                                "渠道 Key 仍有运行中调用占用，不能删除");
                    }
                    credentialRepository.markDeleted(connection, id);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            "DELETE", current.version(), List.of());
                }));

        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    // ---------- 内部 ----------

    /** 渠道 Key 检测（BE-013）：目标为所属渠道，检测记录 target_type=CHANNEL_CREDENTIAL。 */
    public com.lightai.client.channel.ChannelCheckRecord check(RequestContext context, String rawChannelId, String rawId,
                                                               com.lightai.client.channel.ChannelCheckCommand command) {
        RequestPermissions.require(context, Permissions.CREDENTIAL_CHECK);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawChannelId);
        OffsetDateTime startedAt = OffsetDateTime.now();
        ChannelCredentialRecord credential;
        ChannelRecord channel;
        try (Connection connection = dataSource.getConnection()) {
            credential = requireInChannel(connection, parentId, requireCredentialLive(connection, id));
            channel = channelRepository.findLiveById(connection, credential.channelId())
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "渠道不存在或已删除"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测目标解析失败");
        }

        com.lightai.spi.check.ProviderCheckExecutor executor = checkExecutors.stream()
                .filter(candidate -> candidate.supports(channel.providerType()))
                .findFirst()
                .orElseThrow(() -> new LightAiException(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND,
                        "协议类型未加载对应 Adapter：" + channel.providerType()));

        com.lightai.spi.check.ProviderCheckExecutor.CheckOutcome outcome;
        try {
            outcome = executor.execute(new com.lightai.spi.check.ProviderCheckExecutor.CheckInvocation(
                    channel.providerType(), channel.baseUrl(), channel.proxyUrl(),
                    channel.connectTimeoutMs(), channel.readTimeoutMs(), channel.defaultHeaders(),
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
                UUID.randomUUID(), com.lightai.storage.check.CheckRecordRow.TARGET_CHANNEL_CREDENTIAL, id,
                command.resolvedMode(), outcome.succeeded() ? "SUCCEEDED" : "FAILED",
                context.authContext().userId(), outcome.traceId(), outcome.attemptId(),
                startedAt, endedAt, totalMs, outcome.usage(), outcome.channelRequestId(),
                outcome.errorCode(), outcome.errorSummary());
        try (Connection connection = dataSource.getConnection()) {
            checkRecordRepository.insert(connection, row);
            runtimeStateWriter.upsertCredentialHealth(connection, id,
                    outcome.succeeded() ? "HEALTHY" : "INVALID", endedAt,
                    outcome.errorCode(), outcome.errorSummary());
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测记录写入失败");
        }
        return new com.lightai.client.channel.ChannelCheckRecord(
                row.id().toString(), row.targetType(), row.targetId().toString(), row.mode(),
                row.status(), row.startedAt(), row.endedAt(), row.totalMs(), row.traceId(),
                row.attemptId(), row.usage(), row.errorCode(), row.errorSummary(),
                row.channelRequestId());
    }

    private long countActiveReservations(Connection connection, UUID channelCredentialId) {
        String sql = "SELECT count(*) FROM " + schemaName()
                + ".capacity_reservation_item WHERE scope_type = 'CHANNEL_CREDENTIAL' AND scope_id = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, channelCredentialId.toString());
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "无法确认渠道 Key 运行占用，拒绝删除");
        }
    }

    private ChannelCredentialListItem toListItem(Connection connection, ChannelCredentialRecord record) {
        var state = runtimeStateRepository.findByEntity(connection, ENTITY_TYPE, record.id()).orElse(null);
        return new ChannelCredentialListItem(
                record.id().toString(), record.channelId().toString(), record.name(),
                maskedValueOf(record), secretRefDisplay(record),
                record.secretSource(), record.weight(),
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

    private ChannelCredentialDetail toDetail(Connection connection, ChannelCredentialRecord record) {
        var state = runtimeStateRepository.findByEntity(connection, ENTITY_TYPE, record.id()).orElse(null);
        return new ChannelCredentialDetail(
                record.id().toString(), record.channelId().toString(), record.name(),
                maskedValueOf(record), secretRefDisplay(record),
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

    private String maskedValueOf(ChannelCredentialRecord record) {
        // 掩码为受保护列生成值；缺失时以通用掩码兜底，不返回明文
        return record.maskedValue() == null || record.maskedValue().isBlank() ? "****" : record.maskedValue();
    }

    private String secretRefDisplay(ChannelCredentialRecord record) {
        if (record.secretRefCiphertext() == null) {
            return null;
        }
        return maskedValueOf(record);
    }

    private ChannelCredentialRecord requireInChannel(Connection connection, UUID parentId,
                                                        ChannelCredentialRecord record) {
        requireChannelLive(connection, parentId);
        if (!parentId.equals(record.channelId())) {
            throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID, "渠道 Key 不属于指定渠道");
        }
        return record;
    }
    private void requireChannelLive(Connection connection, UUID channelId) {
        channelRepository.findLiveById(connection, channelId)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "渠道不存在或已删除"));
    }

    private ChannelCredentialRecord requireCredentialLive(Connection connection, UUID id) {
        return credentialRepository.findLiveById(connection, id)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "渠道 Key 不存在或已删除"));
    }

    private void validateLimits(Long rpm, Long tpm, Integer concurrent) {
        if ((rpm != null && rpm <= 0) || (tpm != null && tpm <= 0)) {
            throw fieldError("rpm_limit", "INVALID", "限额为空表示不限，0 不合法");
        }
        if (concurrent != null && (concurrent < 1
                || concurrent > ChannelCredentialCreateCommand.CONCURRENT_LIMIT_MAX)) {
            throw fieldError("concurrent_limit", "OUT_OF_RANGE",
                    "concurrent_limit 范围 1—" + ChannelCredentialCreateCommand.CONCURRENT_LIMIT_MAX);
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
        return com.lightai.admin.web.ResourceIds.parse(rawId);
    }

    private static LightAiException nameConflict() {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "渠道 Key 名称已存在",
                List.of(new FieldIssue("name", "DUPLICATED", "同一渠道下名称唯一")));
    }

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "查询参数不合法",
                List.of(new FieldIssue(field, code, message)));
    }

    private String schemaName() {
        return com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME;
    }
}
