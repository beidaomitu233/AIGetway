package com.lightai.admin.governance;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.governance.CircuitStateDetail;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.runtime.circuit.CircuitKey;
import com.lightai.runtime.circuit.CircuitPolicy;
import com.lightai.runtime.circuit.CircuitSnapshot;
import com.lightai.runtime.circuit.CircuitStateStore;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.governance.JdbcCircuitRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 熔断管理服务（BE-023，C-013）。
 * 人工命令流：PENDING 命令+受理审计同事务 → 共享存储 CAS 应用 →
 * 事件+命令终态+成功审计同事务；CAS 不符返回 CIRCUIT_STATE_CONFLICT
 * 并携带 current_state_version；未应用不返回成功状态。
 */
public class CircuitManagementService {

    private static final Set<String> SORTABLE = Set.of("state", "updated_at");

    private final DataSource dataSource;
    private final JdbcCircuitRepository circuitRepository;
    private final DraftWriteService draftWriteService;
    private final AuditService auditService;
    private final CircuitStateStore circuitStateStore;
    private final CircuitPolicy defaultPolicy;
    private final PageResultFactory pageResultFactory;
    private final String sourceMode;

    public CircuitManagementService(DataSource dataSource, JdbcCircuitRepository circuitRepository,
                                    DraftWriteService draftWriteService, AuditService auditService,
                                    CircuitStateStore circuitStateStore, CircuitPolicy defaultPolicy,
                                    PageResultFactory pageResultFactory, String sourceMode) {
        this.dataSource = dataSource;
        this.circuitRepository = circuitRepository;
        this.draftWriteService = draftWriteService;
        this.auditService = auditService;
        this.circuitStateStore = circuitStateStore;
        this.defaultPolicy = defaultPolicy;
        this.pageResultFactory = pageResultFactory;
        this.sourceMode = sourceMode;
    }

    public PageResult<CircuitStateDetail> list(RequestContext context, Map<String, String> params) {
        RequestPermissions.require(context, Permissions.CIRCUIT_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(params.get("page"),
                params.get("page_size"), params.get("sort"), SORTABLE, "state asc");
        try (Connection connection = dataSource.getConnection()) {
            List<JdbcCircuitRepository.StateRow> rows = circuitRepository.listStates(connection,
                    params.get("state"), query.limit(), (int) query.offset());
            long total = circuitRepository.countStates(connection, params.get("state"));
            List<CircuitStateDetail> items = new ArrayList<>(rows.size());
            for (var row : rows) {
                items.add(toDetail(row, null));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "熔断状态读取失败");
        }
    }

    public CircuitStateDetail detail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.CIRCUIT_VIEW);
        UUID id = GovernanceAdminService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            var row = findStateRow(connection, id);
            return toDetail(row, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "熔断详情读取失败");
        }
    }

    public PageResult<JdbcCircuitRepository.EventRow> events(RequestContext context, String rawId,
                                                             Map<String, String> params) {
        RequestPermissions.require(context, Permissions.CIRCUIT_VIEW);
        UUID id = GovernanceAdminService.parseId(rawId);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(params.get("page"),
                params.get("page_size"), "created_at desc", Set.of("created_at"), "created_at desc");
        try (Connection connection = dataSource.getConnection()) {
            findStateRow(connection, id);
            List<JdbcCircuitRepository.EventRow> events = circuitRepository.listEvents(connection,
                    id, params.get("trigger_type"), query.limit(), (int) query.offset());
            return pageResultFactory.create(events, events.size(), query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "熔断事件读取失败");
        }
    }

    /** 人工打开/恢复（C-013）：CAS 应用失败返回 CONFLICT 并携带当前版本。 */
    public CircuitStateDetail applyManual(RequestContext context, String rawId, String action,
                                          String reason, Integer openSeconds, Long stateVersion) {
        RequestPermissions.require(context, Permissions.CIRCUIT_OPERATE);
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw fieldError("reason", "REQUIRED", "reason 必填（1—500 字符）");
        }
        if (stateVersion == null || stateVersion < 1) {
            throw fieldError("state_version", "REQUIRED", "state_version 必填");
        }
        UUID circuitId = GovernanceAdminService.parseId(rawId);
        String requestId = context.requestId();
        UUID commandId = UUID.randomUUID();
        var commandAction = "MANUAL_OPEN".equals(action)
                ? CircuitStateStore.ManualCommand.Action.MANUAL_OPEN
                : "MANUAL_RECOVER".equals(action)
                ? CircuitStateStore.ManualCommand.Action.MANUAL_RECOVER
                : null;
        if (commandAction == null) {
            throw fieldError("action", "INVALID", "action 仅支持 MANUAL_OPEN/MANUAL_RECOVER");
        }

        // 1. PENDING 命令 + 受理审计（同事务）
        try (Connection connection = dataSource.getConnection()) {
            circuitRepository.insertCommand(connection, commandId, circuitId, requestId, action,
                    stateVersion, reason, openSeconds, context.authContext().userId());
            auditService.recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), requestId, context.authContext().userId(),
                    com.lightai.client.audit.AuditActions.CIRCUIT_COMMAND, "circuit",
                    circuitId.toString(), List.of(), sourceMode, context.sourceIpMasked()));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "命令受理失败");
        }

        // 2. 共享存储 CAS 应用
        CircuitKey key = keyOf(circuitId);
        var applied = circuitStateStore.applyManualCommand(key, policyOf(circuitId),
                new CircuitStateStore.ManualCommand(commandId, commandAction, stateVersion,
                        reason, openSeconds, java.time.Instant.now()),
                java.time.Instant.now());
        if (applied.isEmpty()) {
            long current = circuitStateStore.snapshot(key, policyOf(circuitId),
                    java.time.Instant.now()).stateVersion();
            // 命令未应用：标记 FAILED 并返回冲突版本，不声称成功
            try (Connection connection = dataSource.getConnection()) {
                circuitRepository.completeCommand(connection, commandId, "FAILED",
                        ErrorCode.CIRCUIT_STATE_CONFLICT.name());
            } catch (Exception ignored) {
                // 终态写入失败由部署侧按 command_id 补记（C-013）
            }
            LightAiException conflict = new LightAiException(ErrorCode.CIRCUIT_STATE_CONFLICT,
                    "熔断状态已变化，请刷新后重试");
            throw conflict;
        }

        // 3. 事件 + 命令终态（同事务）
        CircuitSnapshot snapshot = applied.orElseThrow();
        try (Connection connection = dataSource.getConnection()) {
            circuitRepository.insertEvent(connection,
                    snapshot.circuitId() + ":" + snapshot.stateVersion(),
                    snapshot.circuitId(), "UNKNOWN", snapshot.state(), commandAction.name(),
                    commandId, null, reason, OffsetDateTime.now());
            circuitRepository.completeCommand(connection, commandId, "SUCCEEDED", null);
            circuitRepository.upsertState(connection, snapshot.circuitId(), key.providerModelId(),
                    key.credentialId(), snapshot.state(), snapshot.stateVersion(),
                    "{}", snapshot.openSource(), reason);
            auditService.recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), requestId, context.authContext().userId(),
                    com.lightai.client.audit.AuditActions.CIRCUIT_COMMAND, "circuit",
                    circuitId.toString(), List.of(
                            com.lightai.client.changes.FieldChange.changed("state", null,
                                    snapshot.state())), sourceMode, context.sourceIpMasked()));
        } catch (Exception e) {
            // 应用已成功但落库未收敛：按 command_id 补记（C-013），返回 202 未收敛
            return pendingDetail(circuitId, key, commandId);
        }
        return detailById(snapshot);
    }

    /** 探测名额（BE-023）：HALF_OPEN 且有名额时取得，真实外部调用待 BE-P05 Adapter。 */
    public boolean acquireProbe(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.CIRCUIT_OPERATE);
        UUID circuitId = GovernanceAdminService.parseId(rawId);
        CircuitKey key = keyOf(circuitId);
        return circuitStateStore.tryAcquireProbe(key, policyOf(circuitId),
                java.time.Instant.now()).isPresent();
    }

    private CircuitStateDetail pendingDetail(UUID circuitId, CircuitKey key, UUID commandId) {
        return new CircuitStateDetail(circuitId.toString(), key.providerModelId().toString(),
                key.credentialId().toString(), "****", "PENDING_APPLY", 0, "{}",
                null, 0, 0, 0, 0, null, null, null, null, null, false, null,
                new CircuitStateDetail.PendingCommand(commandId.toString(), "PENDING", null));
    }

    private CircuitStateDetail detailById(CircuitSnapshot snapshot) {
        return new CircuitStateDetail(snapshot.circuitId().toString(),
                snapshot.key().providerModelId().toString(),
                snapshot.key().credentialId().toString(), "****", snapshot.state(),
                snapshot.stateVersion(), "{}", snapshot.windowStartedAt(),
                snapshot.requestCount(), snapshot.failureCount(), snapshot.probeInflight(),
                snapshot.probeSuccessCount(), snapshot.openedAt(), snapshot.nextProbeAt(),
                snapshot.openSource(), snapshot.lastReason(), null, false, null, null);
    }

    private CircuitStateDetail toDetail(JdbcCircuitRepository.StateRow row,
                                        CircuitStateDetail.PendingCommand pendingCommand) {
        return new CircuitStateDetail(row.id().toString(),
                row.providerModelId() == null ? null : row.providerModelId().toString(),
                row.credentialId() == null ? null : row.credentialId().toString(),
                "****", row.state(), row.stateVersion(), row.policySnapshot(),
                null, 0, 0, 0, 0, null, null, row.openSource(), row.lastReason(),
                null, false, row.updatedAt(), pendingCommand);
    }

    private JdbcCircuitRepository.StateRow findStateRow(Connection connection, UUID id) {
        // 单条查询复用列表过滤（state 为空时全量，取第一匹配）
        List<JdbcCircuitRepository.StateRow> rows = circuitRepository.listStates(connection,
                null, 1000, 0);
        return rows.stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                        "熔断状态不存在"));
    }

    private CircuitKey keyOf(UUID circuitId) {
        try (Connection connection = dataSource.getConnection()) {
            var row = findStateRow(connection, circuitId);
            return new CircuitKey(row.providerModelId(), row.credentialId());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "熔断路径解析失败");
        }
    }

    private CircuitPolicy policyOf(UUID circuitId) {
        return defaultPolicy;
    }

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "字段校验失败",
                List.of(new FieldIssue(field, code, message)));
    }
}
