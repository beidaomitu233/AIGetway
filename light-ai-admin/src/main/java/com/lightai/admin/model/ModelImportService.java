package com.lightai.admin.model;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.provider.ProviderService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.model.ImportResult;
import com.lightai.client.model.ProviderModelImportCommand;
import com.lightai.client.protocol.Permissions;
import com.lightai.spi.check.ProviderCheckExecutor;
import com.lightai.storage.batch.BatchItemRecord;
import com.lightai.storage.batch.BatchJobRecord;
import com.lightai.storage.batch.JdbcBatchCheckRepository;
import com.lightai.storage.model.JdbcProviderModelRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.provider.ProviderRecord;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 模型导入与批量检测（BE-015）。
 * 导入逐对象事务（C-005）：单项失败保留其余成功，重复导入 skipped，
 * 每个成功对象与审计原子；导入强制 enabled=false（C-014 启用前补齐能力）。
 * 批量检测不写草稿；取消只阻止尚未开始项；available-models 与检测执行
 * 依赖 Adapter SPI，未加载时按契约返回对应错误，不伪造结果。
 */
public class ModelImportService {

    private final DataSource dataSource;
    private final JdbcProviderRepository providerRepository;
    private final JdbcProviderModelRepository modelRepository;
    private final DraftWriteService draftWriteService;
    private final JdbcBatchCheckRepository batchCheckRepository;
    private final List<ProviderCheckExecutor> executors;
    private final String sourceMode;

    public ModelImportService(DataSource dataSource, JdbcProviderRepository providerRepository,
                              JdbcProviderModelRepository modelRepository,
                              DraftWriteService draftWriteService,
                              JdbcBatchCheckRepository batchCheckRepository,
                              List<ProviderCheckExecutor> executors, String sourceMode) {
        this.dataSource = dataSource;
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.draftWriteService = draftWriteService;
        this.batchCheckRepository = batchCheckRepository;
        this.executors = executors == null ? List.of() : List.copyOf(executors);
        this.sourceMode = sourceMode;
    }

    // ---------- 导入 ----------

    public ImportResult importModels(RequestContext context, ProviderModelImportCommand command) {
        RequestPermissions.require(context, Permissions.MODEL_IMPORT);
        UUID providerId = ProviderService.parseId(command.providerId());
        if (!ProviderModelImportCommand.SOURCE_PROVIDER_API.equals(command.source())
                && !ProviderModelImportCommand.SOURCE_ADAPTER_PRESET.equals(command.source())) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "导入来源不合法",
                    List.of(new com.lightai.client.error.FieldIssue("source", "INVALID",
                            "source 仅支持 PROVIDER_API/ADAPTER_PRESET")));
        }
        List<String> modelIds = command.distinctModelIds();
        if (modelIds.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "model_ids 不能为空",
                    List.of(new com.lightai.client.error.FieldIssue("model_ids", "REQUIRED",
                            "model_ids 非空数组")));
        }
        ProviderRecord provider = requireProvider(providerId);

        List<ImportResult.CreatedModel> created = new ArrayList<>();
        List<ImportResult.SkippedModel> skipped = new ArrayList<>();
        List<ImportResult.FailedModel> failed = new ArrayList<>();

        // 逐对象事务：每个成功对象与审计原子（C-005）
        for (String modelId : modelIds) {
            try {
                if (modelExists(providerId, modelId)) {
                    skipped.add(new ImportResult.SkippedModel(modelId, "已存在同 ID 模型"));
                    continue;
                }
                UUID id = UUID.randomUUID();
                draftWriteService.execute(new DraftWriteCommand(
                        context.requestId(), context.authContext().userId(), sourceMode,
                        context.sourceIpMasked(), "CREATE", "provider_model", providerId.toString(),
                        0, null, connection -> {
                            // 导入强制停用：启用前必须补齐能力（C-014）
                            ProviderModelRecord record = new ProviderModelRecord(
                                    id, providerId, modelId, defaultDisplayName(modelId),
                                    "CHAT_TEXT", null, null, null, null, null, null, null, null,
                                    null, null, null, null, null, null, null, null, null, List.of(),
                                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 1000000,
                                    "USD", false, command.source(), null, 1L,
                                    OffsetDateTime.now(), OffsetDateTime.now());
                            modelRepository.insert(connection, record);
                            return new DraftEntityChange("provider_model", id, modelId, "CREATE", 1L,
                                    List.of(com.lightai.client.changes.FieldChange.changed(
                                            "model_id", null, modelId)));
                        }));
                created.add(new ImportResult.CreatedModel(modelId, id.toString(), 1L));
            } catch (LightAiException e) {
                failed.add(new ImportResult.FailedModel(modelId, e.code().name()));
            } catch (Exception e) {
                failed.add(new ImportResult.FailedModel(modelId, ErrorCode.INTERNAL_ERROR.name()));
            }
        }
        return new ImportResult(created, skipped, failed);
    }

    /** 外部模型目录（available-models）：依赖 Adapter SPI，未加载返回 MODEL_LIST_NOT_SUPPORTED。 */
    public List<String> availableModelIds(RequestContext context, UUID providerId,
                                          String credentialId) {
        RequestPermissions.require(context, Permissions.MODEL_IMPORT);
        ProviderRecord provider = requireProvider(providerId);
        ProviderCheckExecutor executor = executors.stream()
                .filter(candidate -> candidate.supports(provider.type()))
                .findFirst()
                .orElseThrow(() -> new LightAiException(ErrorCode.MODEL_LIST_NOT_SUPPORTED,
                        "该 Provider 类型未加载支持模型列表的 Adapter"));
        // V1：目录拉取由 Adapter 内部完成；此处仅做能力探测，真实列表在 BE-P05 交付
        throw new LightAiException(ErrorCode.MODEL_LIST_NOT_SUPPORTED,
                "Adapter 未提供模型目录来源：" + provider.type());
    }

    // ---------- 批量检测 ----------

    public BatchJobRecord createBatchCheck(RequestContext context, UUID providerId,
                                           List<UUID> providerModelIds, UUID credentialId,
                                           String mode, Integer timeoutMs) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        if (providerModelIds == null || providerModelIds.isEmpty()
                || providerModelIds.size() > ProviderModelImportCommand.MAX_BATCH) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "provider_model_ids 不合法",
                    List.of(new com.lightai.client.error.FieldIssue("provider_model_ids", "INVALID",
                            "1—" + ProviderModelImportCommand.MAX_BATCH + " 项")));
        }
        UUID jobId = UUID.randomUUID();
        String commandJson = batchCommandJson(providerModelIds, credentialId, mode, timeoutMs);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            batchCheckRepository.insertJob(connection, new BatchJobRecord(
                    jobId, BatchJobRecord.STATUS_PENDING, context.authContext().userId(),
                    providerModelIds.size(), 0, 0, 0, 0, null, null, commandJson,
                    OffsetDateTime.now(), OffsetDateTime.now()));
            int sequence = 1;
            for (UUID modelId : providerModelIds) {
                batchCheckRepository.insertItem(connection, new BatchItemRecord(
                        UUID.randomUUID(), jobId, modelId, sequence++,
                        BatchItemRecord.STATUS_PENDING, null, null, null, null));
            }
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测任务创建失败");
        }
        return batchCheckRepository.findJobById(openConnection(), jobId)
                .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "检测任务读取失败"));
    }

    public BatchJobRecord job(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        UUID jobId = ProviderService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            return batchCheckRepository.findJobById(connection, jobId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "检测任务不存在"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测任务读取失败");
        }
    }

    public List<BatchItemRecord> jobItems(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        UUID jobId = ProviderService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            batchCheckRepository.findJobById(connection, jobId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "检测任务不存在"));
            return batchCheckRepository.findItemsByJob(connection, jobId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测明细读取失败");
        }
    }

    public BatchJobRecord cancel(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        UUID jobId = ProviderService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            BatchJobRecord job = batchCheckRepository.findJobById(connection, jobId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "检测任务不存在"));
            if (isFinished(job)) {
                throw new LightAiException(ErrorCode.JOB_ALREADY_FINISHED, "任务已结束，不能取消");
            }
            batchCheckRepository.cancelPendingItems(connection, jobId);
            batchCheckRepository.refreshJobSummary(connection, jobId);
            return batchCheckRepository.findJobById(connection, jobId).orElse(job);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "任务取消失败");
        }
    }

    private static boolean isFinished(BatchJobRecord job) {
        return BatchJobRecord.STATUS_SUCCEEDED.equals(job.status())
                || BatchJobRecord.STATUS_FAILED.equals(job.status())
                || BatchJobRecord.STATUS_PARTIAL_FAILED.equals(job.status())
                || BatchJobRecord.STATUS_CANCELLED.equals(job.status());
    }

    private ProviderRecord requireProvider(UUID providerId) {
        try (Connection connection = dataSource.getConnection()) {
            return providerRepository.findLiveById(connection, providerId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                            "引用的 Provider 不存在或已删除"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "引用校验失败");
        }
    }

    private boolean modelExists(UUID providerId, String modelId) {
        try (Connection connection = dataSource.getConnection()) {
            return modelRepository.existsByProviderAndModelId(connection, providerId, modelId);
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型检查失败");
        }
    }

    private Connection openConnection() {
        try {
            return dataSource.getConnection();
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "数据源不可用");
        }
    }

    private static String defaultDisplayName(String modelId) {
        return modelId.length() <= 64 ? modelId : modelId.substring(0, 64);
    }

    private static String batchCommandJson(List<UUID> modelIds, UUID credentialId, String mode,
                                           Integer timeoutMs) {
        StringBuilder json = new StringBuilder("{\"provider_model_ids\":[");
        for (int i = 0; i < modelIds.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(modelIds.get(i)).append('"');
        }
        json.append("],\"credential_id\":")
                .append(credentialId == null ? "null" : "\"" + credentialId + "\"")
                .append(",\"mode\":\"").append(mode == null ? "MINIMAL_CHAT" : mode)
                .append("\",\"timeout_ms\":").append(timeoutMs == null ? 10000 : timeoutMs)
                .append('}');
        return json.toString();
    }
}
