package com.lightai.admin.alias;

import com.lightai.admin.check.ManagementCheckService;
import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.client.access.ImpactAnalysis;
import com.lightai.client.access.ProviderCheckCommand;
import com.lightai.client.access.RouteCandidateDetail;
import com.lightai.client.access.ProviderCheckRecordView;
import com.lightai.client.access.RouteCandidateCreateCommand;
import com.lightai.client.access.RouteCandidateUpdateCommand;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.alias.ModelAliasRecord;
import com.lightai.storage.alias.ModelAliasRepository;
import com.lightai.storage.alias.RouteCandidateRecord;
import com.lightai.storage.alias.RouteCandidateRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.model.ProviderModelRepository;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Route Candidate 服务（BE-017）：候选增改删与探测。
 * 同 Provider 约束在保存阶段拒绝（发布阶段由校验矩阵再次拦截）；
 * 重复三元组 DUPLICATE_ROUTE_CANDIDATE；更新不可更换 provider_model_id。
 */
public class RouteCandidateService {

    public static final String ENTITY_TYPE = "ROUTE_CANDIDATE";

    private final DataSource dataSource;
    private final DraftWriteService draftWriteService;
    private final RouteCandidateRepository candidateRepository;
    private final ModelAliasRepository aliasRepository;
    private final ProviderModelRepository modelRepository;
    private final ConfigReferenceQuery referenceQuery;
    private final ManagementCheckService checkService;
    private final Clock clock;

    public RouteCandidateService(DataSource dataSource, DraftWriteService draftWriteService,
                                 RouteCandidateRepository candidateRepository,
                                 ModelAliasRepository aliasRepository,
                                 ProviderModelRepository modelRepository,
                                 ConfigReferenceQuery referenceQuery,
                                 ManagementCheckService checkService, Clock clock) {
        this.dataSource = dataSource;
        this.draftWriteService = draftWriteService;
        this.candidateRepository = candidateRepository;
        this.aliasRepository = aliasRepository;
        this.modelRepository = modelRepository;
        this.referenceQuery = referenceQuery;
        this.checkService = checkService;
        this.clock = clock;
    }

    public ManagementOperationResult<RouteCandidateDetail> create(UUID aliasId, RouteCandidateCreateCommand command,
                                                                  WriteContext ctx) {
        UUID modelId = requireId(command.providerModelId(), "provider_model_id");
        UUID poolId = requireId(command.credentialPoolId(), "credential_pool_id");
        int priority = command.priority() == null ? 10 : command.priority();
        int weight = command.weight() == null ? 1 : command.weight();
        validatePriorityWeight(priority, weight);
        boolean enabled = command.enabled() == null || command.enabled();

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "CREATE", ENTITY_TYPE, id.toString(), 0L,
                null,
                connection -> {
                    ModelAliasRecord alias = aliasRepository.find(connection, aliasId)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "别名不存在或已删除"));
                    ProviderModelRecord model = modelRepository.find(connection, modelId)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                    "模型不存在或已删除"));
                    ConfigReferenceQuery.ProviderSummary poolProvider =
                            referenceQuery.findProviderSummaryOfPool(connection, poolId)
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                            "凭证池不存在或所属 Provider 缺失"));
                    ConfigReferenceQuery.ProviderSummary modelProvider =
                            referenceQuery.findProviderSummary(connection, model.providerId())
                                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                            "模型所属 Provider 缺失"));
                    if (!poolProvider.id().equals(modelProvider.id())) {
                        throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                "候选模型与凭证池不属于同一 Provider");
                    }
                    if (candidateRepository.existsAliveByTriple(connection, aliasId, modelId, poolId)) {
                        throw new LightAiException(ErrorCode.DUPLICATE_ROUTE_CANDIDATE,
                                "相同模型与凭证池组合已存在");
                    }
                    RouteCandidateRecord record = new RouteCandidateRecord(
                            id, aliasId, modelId, poolId, priority, weight, enabled,
                            1L, now, now, null);
                    candidateRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE, id,
                            alias.alias() + "/" + model.displayName(), "CREATE", 1L, List.of(
                            FieldChange.changed("provider_model_id", null, modelId.toString()),
                            FieldChange.changed("credential_pool_id", null, poolId.toString()),
                            FieldChange.changed("priority", null, priority),
                            FieldChange.changed("weight", null, weight),
                            FieldChange.changed("enabled", null, enabled)));
                }));
        return new ManagementOperationResult<>(id.toString(), result.entityVersion(), null,
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<RouteCandidateDetail> update(UUID id, RouteCandidateUpdateCommand command,
                                                                  WriteContext ctx) {
        int priority = command.priority() == null ? 10 : command.priority();
        int weight = command.weight() == null ? 1 : command.weight();
        validatePriorityWeight(priority, weight);
        long newVersion = command.version() + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE, id.toString(), command.version(),
                connection -> candidateRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    RouteCandidateRecord persisted = candidateRepository.find(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "候选不存在或已删除"));
                    UUID poolId = requireId(command.credentialPoolId(), "credential_pool_id");
                    ProviderModelRecord model = modelRepository.find(connection, persisted.providerModelId())
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID, "候选模型不存在"));
                    if (!poolId.equals(persisted.credentialPoolId())) {
                        ConfigReferenceQuery.ProviderSummary poolProvider =
                                referenceQuery.findProviderSummaryOfPool(connection, poolId)
                                        .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                                "凭证池不存在或所属 Provider 缺失"));
                        ConfigReferenceQuery.ProviderSummary modelProvider =
                                referenceQuery.findProviderSummary(connection, model.providerId())
                                        .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                                "模型所属 Provider 缺失"));
                        if (!poolProvider.id().equals(modelProvider.id())) {
                            throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                    "候选模型与凭证池不属于同一 Provider");
                        }
                    }
                    if (!poolId.equals(persisted.credentialPoolId())
                            && candidateRepository.existsAliveByTriple(connection, persisted.aliasId(),
                            persisted.providerModelId(), poolId)) {
                        throw new LightAiException(ErrorCode.DUPLICATE_ROUTE_CANDIDATE, "相同模型与凭证池组合已存在");
                    }
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    RouteCandidateRecord updated = new RouteCandidateRecord(
                            persisted.id(), persisted.aliasId(), persisted.providerModelId(), poolId,
                            priority, weight, command.enabled() == null ? persisted.enabled() : command.enabled(),
                            newVersion, persisted.createdAt(), now, null);
                    candidateRepository.update(connection, updated);
                    return new DraftEntityChange(ENTITY_TYPE, id, model.displayName(), "UPDATE", newVersion,
                            List.of(FieldChange.changed("priority", persisted.priority(), priority),
                                    FieldChange.changed("weight", persisted.weight(), weight),
                                    FieldChange.changed("enabled", persisted.enabled(), updated.enabled())));
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, null,
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<RouteCandidateDetail> delete(UUID id, long version, WriteContext ctx) {
        long newVersion = version + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "DELETE", ENTITY_TYPE, id.toString(), version,
                connection -> candidateRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    RouteCandidateRecord persisted = candidateRepository.find(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "候选不存在或已删除"));
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    candidateRepository.update(connection, new RouteCandidateRecord(
                            persisted.id(), persisted.aliasId(), persisted.providerModelId(),
                            persisted.credentialPoolId(), persisted.priority(), persisted.weight(),
                            persisted.enabled(), newVersion, persisted.createdAt(), now, now));
                    return new DraftEntityChange(ENTITY_TYPE, id, persisted.id().toString(), "DELETE",
                            newVersion, List.of());
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, null,
                true, result.draftRevision(), ctx.requestId());
    }

    /** 候选探测：模型+池同 Provider 校验由 ManagementCheckService 完成。 */
    public ProviderCheckRecordView check(UUID id, ProviderCheckCommand command, WriteContext ctx) {
        return checkService.check(ctx.operatorId(), "ROUTE_CANDIDATE", id, command);
    }

    private static void validatePriorityWeight(int priority, int weight) {
        if (priority < 1 || priority > 100) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "priority 范围 1—100", "priority");
        }
        if (weight < 1 || weight > 100) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "weight 范围 1—100", "weight");
        }
    }

    private static UUID requireId(String value, String field) {
        if (value == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 必填", field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 不是合法ID", field);
        }
    }
}
