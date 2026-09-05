package com.lightai.admin.batch;

import com.lightai.admin.check.CheckInvoker;
import com.lightai.admin.check.ManagementCheckService;
import com.lightai.client.access.BatchCheckCommand;
import com.lightai.client.access.BatchCheckJobView;
import com.lightai.client.access.CheckMode;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.check.BatchCheckItemRecord;
import com.lightai.storage.check.BatchCheckJobRecord;
import com.lightai.storage.check.BatchCheckJobRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.model.ProviderModelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量检测服务（BE-015）：任务创建（PENDING，202 受理）、逐项执行、取消传播。
 * 约束：model_ids 非空去重 ≤100、同 Provider、检测并发上限 3（FRONTEND 4.2.5.3）；
 * 取消只阻止未开始项（JOB_ALREADY_FINISHED 表示任务已终态）；
 * 单项失败不终止其余项；command JSON 不携带密钥。
 */
public class BatchCheckService {

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final BatchCheckJobRepository jobRepository;
    private final ProviderModelRepository modelRepository;
    private final ConfigReferenceQuery referenceQuery;
    private final ManagementCheckService checkService;
    private final Supplier<ManagementCheckService.SecretResolver> resolverSupplier;
    private ExecutorService executor;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BatchCheckService(DataSource dataSource, PlatformTransactionManager transactionManager,
                             BatchCheckJobRepository jobRepository, ProviderModelRepository modelRepository,
                             ConfigReferenceQuery referenceQuery, ManagementCheckService checkService,
                             Supplier<ManagementCheckService.SecretResolver> resolverSupplier,
                             ExecutorService executor, Clock clock) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.jobRepository = jobRepository;
        this.modelRepository = modelRepository;
        this.referenceQuery = referenceQuery;
        this.checkService = checkService;
        this.resolverSupplier = resolverSupplier;
        this.executor = executor;
        this.clock = clock;
    }

    /** 创建任务并异步执行；返回任务 ID（HTTP 202 受理）。 */
    public UUID create(BatchCheckCommand command, String operatorId, String requestId) {
        if (command.providerModelIds() == null || command.providerModelIds().isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "provider_model_ids 非空数组",
                    "provider_model_ids");
        }
        List<UUID> modelIds = command.providerModelIds().stream().distinct()
                .map(id -> parseId(id, "provider_model_ids")).toList();
        if (modelIds.size() > 100) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "provider_model_ids 最多 100 项",
                    "provider_model_ids");
        }
        String mode = command.mode() == null ? CheckMode.MINIMAL_CHAT.name() : command.mode();
        int timeout = command.timeoutMs() == null
                ? com.lightai.client.access.ProviderCheckCommand.DEFAULT_TIMEOUT_MS : command.timeoutMs();

        UUID jobId = UUID.randomUUID();
        List<UUID> orderedModels = validateAndOrder(modelIds);
        OffsetDateTime now = OffsetDateTime.now(clock);
        String commandJson = serialize(new BatchCheckCommand(
                orderedModels.stream().map(UUID::toString).toList(),
                command.credentialId(), mode, timeout));

        transaction.executeWithoutResult(status -> {
            try (Connection connection = dataSource.getConnection()) {
                List<BatchCheckItemRecord> items = new ArrayList<>();
                int sequence = 1;
                for (UUID modelId : orderedModels) {
                    items.add(new BatchCheckItemRecord(UUID.randomUUID(), jobId, modelId,
                            sequence++, BatchCheckItemRecord.STATUS_PENDING, null, null, null, null));
                }
                jobRepository.insert(connection, new BatchCheckJobRecord(
                        jobId, BatchCheckJobRecord.STATUS_PENDING, operatorId,
                        orderedModels.size(), 0, 0, 0, 0, null, null, commandJson, now, now), items);
            } catch (LightAiException e) {
                throw e;
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "批量检测任务写入失败");
            }
        });
        executor.submit(() -> run(jobId, mode, timeout, command.credentialId()));
        return jobId;
    }

    public BatchCheckJobView get(UUID jobId) {
        try (Connection connection = dataSource.getConnection()) {
            BatchCheckJobRecord job = jobRepository.find(connection, jobId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "检测任务不存在"));
            List<BatchCheckItemRecord> items = jobRepository.listItems(connection, jobId);
            return toView(job, items);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "检测任务读取失败");
        }
    }

    /** 取消未开始项；已终态任务返回 JOB_ALREADY_FINISHED。 */
    public BatchCheckJobView cancel(UUID jobId) {
        transaction.executeWithoutResult(tx -> {
            try (Connection connection = dataSource.getConnection()) {
                BatchCheckJobRecord job = jobRepository.find(connection, jobId)
                        .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "检测任务不存在"));
                if (isTerminal(job.status())) {
                    throw new LightAiException(ErrorCode.JOB_ALREADY_FINISHED, "批量检测任务已结束，不能取消");
                }
                int cancelled = jobRepository.cancelPendingItems(connection, jobId);
                int newCancelled = job.cancelledCount() + cancelled;
                int newCompleted = job.completedCount() + cancelled;
                String status = job.status().equals(BatchCheckJobRecord.STATUS_RUNNING)
                        ? BatchCheckJobRecord.STATUS_RUNNING
                        : BatchCheckJobRecord.STATUS_CANCELLED;
                jobRepository.updateSummary(connection, new BatchCheckJobRecord(
                        job.id(), status, job.operatorId(), job.totalCount(),
                        newCompleted, job.successCount(), job.failureCount(), newCancelled,
                        job.startedAt(),
                        status.equals(BatchCheckJobRecord.STATUS_CANCELLED) ? OffsetDateTime.now(clock) : null,
                        job.commandJson(), job.createdAt(), OffsetDateTime.now(clock)));
            } catch (LightAiException e) {
                throw e;
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "批量检测取消失败");
            }
        });
        return get(jobId);
    }

    private void run(UUID jobId, String mode, int timeout, String credentialId) {
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        // 快照遍历：明细更新不与迭代冲突；JDBC 实现每行为独立语句
        for (BatchCheckItemRecord item : List.copyOf(listItems(jobId))) {
            if (!item.status().equals(BatchCheckItemRecord.STATUS_PENDING)) {
                continue;
            }
            if (isJobCancelled(jobId)) {
                break;
            }
            String resultStatus;
            String errorCode = null;
            try {
                checkService.check(operatorOf(jobId), "PROVIDER_MODEL", item.providerModelId(),
                        new com.lightai.client.access.ProviderCheckCommand(null, item.providerModelId().toString(),
                                credentialId, mode, timeout));
                resultStatus = BatchCheckItemRecord.STATUS_SUCCEEDED;
            } catch (LightAiException e) {
                resultStatus = BatchCheckItemRecord.STATUS_FAILED;
                errorCode = e.code().name();
            } catch (Exception e) {
                resultStatus = BatchCheckItemRecord.STATUS_FAILED;
                errorCode = ErrorCode.INTERNAL_ERROR.name();
            }
            recordItemResult(jobId, item.id(), resultStatus, errorCode);
        }
        finalizeJob(jobId, startedAt);
    }

    /** 单项结果落库：明细为准，任务汇总在 finalize 统一重算。 */
    private void recordItemResult(UUID jobId, UUID itemId, String status, String errorCode) {
        try (Connection connection = dataSource.getConnection()) {
            jobRepository.updateItem(connection, new BatchCheckItemRecord(
                    itemId, jobId, null, 0, status, null, OffsetDateTime.now(clock),
                    OffsetDateTime.now(clock), errorCode));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "检测明细更新失败");
        }
    }

    /** 任务汇总：从明细重算计数与终态；已终态任务跳过（取消场景）。 */
    private void finalizeJob(UUID jobId, OffsetDateTime startedAt) {
        try (Connection connection = dataSource.getConnection()) {
            BatchCheckJobRecord job = jobRepository.find(connection, jobId).orElse(null);
            if (job == null || isTerminal(job.status())) {
                return;
            }
            List<BatchCheckItemRecord> items = jobRepository.listItems(connection, jobId);
            int finalSuccess = (int) items.stream().filter(i -> i.status().equals(BatchCheckItemRecord.STATUS_SUCCEEDED)).count();
            int finalFailed = (int) items.stream().filter(i -> i.status().equals(BatchCheckItemRecord.STATUS_FAILED)).count();
            int finalCancelled = (int) items.stream().filter(i -> i.status().equals(BatchCheckItemRecord.STATUS_CANCELLED)).count();
            String status = finalCancelled == items.size() && finalSuccess == 0 && finalFailed == 0
                    ? BatchCheckJobRecord.STATUS_CANCELLED
                    : finalFailed == 0 && finalCancelled == 0 ? BatchCheckJobRecord.STATUS_SUCCEEDED
                    : finalSuccess == 0 && finalCancelled == 0 ? BatchCheckJobRecord.STATUS_FAILED
                    : BatchCheckJobRecord.STATUS_PARTIAL_FAILED;
            jobRepository.updateSummary(connection, new BatchCheckJobRecord(
                    job.id(), status, job.operatorId(), job.totalCount(),
                    items.size(), finalSuccess, finalFailed, finalCancelled,
                    job.startedAt() == null ? startedAt : job.startedAt(), OffsetDateTime.now(clock),
                    job.commandJson(), job.createdAt(), OffsetDateTime.now(clock)));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "检测任务汇总失败");
        }
    }

    /** 校验模型存在且同 Provider；返回按 Provider 分组的有序 ID 列表。 */
    private List<UUID> validateAndOrder(List<UUID> modelIds) {
        try (Connection connection = dataSource.getConnection()) {
            List<ProviderModelRecord> records = modelRepository.list(connection,
                    "id IN (" + placeholders(modelIds.size()) + ")",
                    new ArrayList<Object>(modelIds), "created_at asc", 0, modelIds.size());
            if (records.size() != modelIds.size()) {
                throw new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "部分模型不存在或已删除");
            }
            UUID providerId = records.get(0).providerId();
            for (ProviderModelRecord record : records) {
                if (!record.providerId().equals(providerId)) {
                    throw new LightAiException(ErrorCode.CHECK_TARGET_INVALID, "批量检测模型必须属于同一 Provider");
                }
            }
            return records.stream().map(ProviderModelRecord::id).toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "批量检测目标读取失败");
        }
    }

    private List<BatchCheckItemRecord> listItems(UUID jobId) {
        try (Connection connection = dataSource.getConnection()) {
            return jobRepository.listItems(connection, jobId);
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "检测明细读取失败");
        }
    }

    private boolean isJobCancelled(UUID jobId) {
        try (Connection connection = dataSource.getConnection()) {
            return jobRepository.find(connection, jobId)
                    .map(job -> job.status().equals(BatchCheckJobRecord.STATUS_CANCELLED))
                    .orElse(true);
        } catch (Exception e) {
            return true;
        }
    }

    private String operatorOf(UUID jobId) {
        try (Connection connection = dataSource.getConnection()) {
            return jobRepository.find(connection, jobId).map(BatchCheckJobRecord::operatorId).orElse("system");
        } catch (Exception e) {
            return "system";
        }
    }

    private static BatchCheckJobView toView(BatchCheckJobRecord job, List<BatchCheckItemRecord> items) {
        List<BatchCheckJobView.ItemView> itemViews = items.stream()
                .map(item -> new BatchCheckJobView.ItemView(item.id().toString(),
                        item.providerModelId().toString(), item.sequence(), item.status(), item.errorCode()))
                .toList();
        return new BatchCheckJobView(job.id().toString(), job.status(), job.totalCount(),
                job.completedCount(), job.successCount(), job.failureCount(), job.cancelledCount(),
                job.startedAt(), job.endedAt(), itemViews);
    }

    private static boolean isTerminal(String status) {
        return status.equals(BatchCheckJobRecord.STATUS_SUCCEEDED)
                || status.equals(BatchCheckJobRecord.STATUS_PARTIAL_FAILED)
                || status.equals(BatchCheckJobRecord.STATUS_FAILED)
                || status.equals(BatchCheckJobRecord.STATUS_CANCELLED);
    }

    private String serialize(BatchCheckCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (Exception e) {
            throw new IllegalStateException("批量检测命令序列化失败", e);
        }
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ",?");
        }
        return builder.toString();
    }

    private static UUID parseId(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 不是合法ID", field);
        }
    }
}
