package com.lightai.admin.risk;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.risk.RiskApplicationView;
import com.lightai.client.risk.RiskEventView;
import com.lightai.client.risk.RiskKeywordRuleView;
import com.lightai.client.risk.RiskPolicyUpdateCommand;
import com.lightai.client.risk.RiskPolicyView;
import com.lightai.storage.risk.JdbcRiskControlRepository;
import com.lightai.storage.risk.RiskEventRecord;
import com.lightai.storage.risk.RiskKeywordRuleRecord;
import com.lightai.storage.risk.RiskPolicyRecord;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 风险策略版本、白名单和命中事件管理；所有写入受管理权限和版本 CAS 保护。 */
public final class RiskControlService {
    private final DataSource dataSource;
    private final JdbcRiskControlRepository repository;
    private final TransactionTemplate transaction;
    private final AuditService auditService;
    private final String schemaName;

    public RiskControlService(DataSource dataSource, JdbcRiskControlRepository repository,
                              PlatformTransactionManager tx, AuditService auditService, Clock clock) {
        this(dataSource, repository, tx, auditService, clock,
                com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public RiskControlService(DataSource dataSource, JdbcRiskControlRepository repository,
                              PlatformTransactionManager tx, AuditService auditService,
                              Clock clock, String schemaName) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.transaction = new TransactionTemplate(tx);
        this.auditService = auditService;
        this.schemaName = schemaName == null || schemaName.isBlank()
                ? com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME : schemaName;
    }

    public RiskPolicyView policy(RequestContext context) {
        RequestPermissions.require(context, Permissions.RISK_CONTROL_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            return view(ensure(connection));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "风险策略当前无法读取");
        }
    }

    public RiskPolicyView replace(RequestContext context, RiskPolicyUpdateCommand command) {
        RequestPermissions.require(context, Permissions.RISK_CONTROL_MANAGE);
        validate(command);
        final RiskPolicyView[] result = new RiskPolicyView[1];
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                RiskPolicyRecord current = ensure(connection);
                if (command.version() != current.version()) {
                    throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                            "风险策略版本已变化，请刷新后重试");
                }
                List<RiskKeywordRuleRecord> rules = rules(command);
                List<UUID> applications = applicationIds(command);
                validateApplicationIds(connection, applications);
                RiskPolicyRecord next = new RiskPolicyRecord(UUID.randomUUID(), current.version() + 1,
                        command.enabled(), normalize(command.keywordAction(), "BLOCK"),
                        command.anomalyWindowSeconds(), command.anomalyRequestThreshold(),
                        command.anomalyTokenThreshold(), command.anomalyAmountThreshold(),
                        command.anomalyBlockSeconds(), normalize(command.whitelistMode(), "OFF"),
                        null, null, rules, applications);
                repository.replace(connection, next, rules, applications);
                if (auditService != null) {
                    auditService.recordSuccess(connection,
                            com.lightai.storage.audit.AuditRecord.succeeded(UUID.randomUUID(),
                                    context.requestId(), context.authContext().userId(),
                                    "RISK_POLICY_REPLACE", "RISK_POLICY", next.id().toString(),
                                    List.of(), "MANAGEMENT", context.sourceIpMasked()));
                }
                result[0] = view(next);
            });
            return result[0];
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "风险策略保存失败");
        }
    }

    public List<RiskEventView> events(RequestContext context, int limit, String applicationId,
                                      String eventType, Instant from, Instant to) {
        RequestPermissions.require(context, Permissions.RISK_CONTROL_VIEW);
        UUID app = parseOptionalUuid(applicationId, "application_id");
        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("to", "结束时间必须晚于开始时间");
        }
        try (Connection connection = dataSource.getConnection()) {
            return repository.listEvents(connection, limit, app, blankToNull(eventType), from, to)
                    .stream().map(this::event).toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "风险事件当前无法读取");
        }
    }

    public List<RiskEventView> events(RequestContext context, int limit) {
        return events(context, limit, null, null, null, null);
    }

    public List<RiskApplicationView> applications(RequestContext context) {
        RequestPermissions.require(context, Permissions.RISK_CONTROL_VIEW);
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT id,code,name,status FROM "
                     + com.lightai.storage.dialect.DialectResolver.resolve(connection).qualify(schemaName, "application")
                     + " ORDER BY name,id");
             var rs = statement.executeQuery()) {
            List<RiskApplicationView> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new RiskApplicationView(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
            }
            return result;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用候选当前无法读取");
        }
    }

    private RiskPolicyRecord ensure(Connection connection) {
        RiskPolicyRecord found = repository.findPolicy(connection);
        if (found != null) return found;
        RiskPolicyRecord defaults = new RiskPolicyRecord(UUID.randomUUID(), 1, false,
                "BLOCK", 60, null, null, null, 300, "OFF", null, null, List.of(), List.of());
        repository.replace(connection, defaults, List.of(), List.of());
        return defaults;
    }

    private List<RiskKeywordRuleRecord> rules(RiskPolicyUpdateCommand command) {
        List<RiskKeywordRuleRecord> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        List<RiskPolicyUpdateCommand.RiskKeywordRuleCommand> input = command.keywords() == null
                ? List.of() : command.keywords();
        for (RiskPolicyUpdateCommand.RiskKeywordRuleCommand rule : input) {
            if (rule == null || rule.keyword() == null || rule.keyword().isBlank()
                    || rule.keyword().trim().length() > 128) {
                throw invalid("keywords", "关键词不能为空且不得超过 128 个字符");
            }
            String keyword = rule.keyword().trim();
            String matchType = normalize(rule.matchType(), "CONTAINS");
            String action = normalize(rule.action(), command.keywordAction());
            if (!Set.of("CONTAINS", "EXACT").contains(matchType)) {
                throw invalid("keywords.match_type", "匹配方式只能是 CONTAINS 或 EXACT");
            }
            if (!Set.of("BLOCK", "RECORD").contains(action)) {
                throw invalid("keywords.action", "命中动作只能是 BLOCK 或 RECORD");
            }
            UUID applicationId = parseOptionalUuid(rule.applicationId(), "keywords.application_id");
            String key = (applicationId == null ? "*" : applicationId) + ":"
                    + matchType + ":" + keyword.toLowerCase(Locale.ROOT);
            if (!unique.add(key)) throw invalid("keywords", "不能重复配置相同关键词规则");
            result.add(new RiskKeywordRuleRecord(parseRuleId(rule.id()), keyword, matchType,
                    rule.ignoreCase() == null || rule.ignoreCase(), applicationId, action,
                    rule.enabled() == null || rule.enabled()));
        }
        return List.copyOf(result);
    }

    private List<UUID> applicationIds(RiskPolicyUpdateCommand command) {
        List<UUID> result = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        for (String raw : command.whitelistApplicationIds() == null
                ? List.<String>of() : command.whitelistApplicationIds()) {
            UUID id = parseOptionalUuid(raw, "whitelist_application_ids");
            if (id != null && unique.add(id)) result.add(id);
        }
        return List.copyOf(result);
    }

    private void validateApplicationIds(Connection connection, List<UUID> ids) {
        for (UUID id : ids) {
            var dialect = com.lightai.storage.dialect.DialectResolver.resolve(connection);
            try (var statement = connection.prepareStatement("SELECT 1 FROM " + dialect.qualify(schemaName, "application")
                    + " WHERE id=?")) {
                dialect.bindUuid(statement, 1, id);
                try (var rs = statement.executeQuery()) {
                    if (!rs.next()) throw invalid("whitelist_application_ids", "白名单应用不存在或已删除");
                }
            } catch (LightAiException e) {
                throw e;
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "白名单应用校验失败");
            }
        }
    }

    private RiskPolicyView view(RiskPolicyRecord policy) {
        return new RiskPolicyView(policy.id().toString(), policy.version(), policy.enabled(),
                policy.keywordAction(), policy.anomalyWindowSeconds(), policy.anomalyRequestThreshold(),
                policy.anomalyTokenThreshold(), policy.anomalyAmountThreshold(), policy.anomalyBlockSeconds(),
                policy.whitelistMode(), policy.keywords().stream().map(rule -> new RiskKeywordRuleView(
                        rule.id().toString(), rule.keyword(), rule.matchType(), rule.ignoreCase(),
                        rule.applicationId() == null ? null : rule.applicationId().toString(),
                        rule.action(), rule.enabled())).toList(),
                policy.whitelistApplicationIds().stream().map(UUID::toString).toList());
    }

    private RiskEventView event(RiskEventRecord event) {
        return new RiskEventView(event.id().toString(), event.createdAt(),
                event.applicationId() == null ? null : event.applicationId().toString(), event.requestId(),
                event.eventType(), event.action(), event.ruleId() == null ? null : event.ruleId().toString(),
                event.reason());
    }

    private static UUID parseRuleId(String raw) {
        return raw == null || raw.isBlank() ? UUID.randomUUID() : parseOptionalUuid(raw, "keywords.id");
    }

    private static UUID parseOptionalUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (RuntimeException e) { throw invalid(field, "ID 不合法"); }
    }

    private static String normalize(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LightAiException invalid(String field, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, message,
                List.of(new FieldIssue(field, "INVALID", message)));
    }

    private static void validate(RiskPolicyUpdateCommand command) {
        if (command == null) throw invalid("policy", "策略不能为空");
        if (command.version() < 1) throw invalid("version", "策略版本必须为正数");
        if (command.anomalyWindowSeconds() < 1 || command.anomalyWindowSeconds() > 86_400) {
            throw invalid("anomaly_window_seconds", "时间窗口必须在 1—86400 秒之间");
        }
        if (command.anomalyBlockSeconds() < 1 || command.anomalyBlockSeconds() > 86_400) {
            throw invalid("anomaly_block_seconds", "阻断时长必须在 1—86400 秒之间");
        }
        if (command.anomalyRequestThreshold() != null && command.anomalyRequestThreshold() < 1) {
            throw invalid("anomaly_request_threshold", "请求阈值必须为正数");
        }
        if (command.anomalyTokenThreshold() != null && command.anomalyTokenThreshold() < 1) {
            throw invalid("anomaly_token_threshold", "Token 阈值必须为正数");
        }
        if (command.anomalyAmountThreshold() != null && command.anomalyAmountThreshold().signum() < 0) {
            throw invalid("anomaly_amount_threshold", "金额阈值不能为负数");
        }
        String keywordAction = normalize(command.keywordAction(), "BLOCK");
        if (!Set.of("BLOCK", "RECORD").contains(keywordAction)) {
            throw invalid("keyword_action", "关键词默认动作只能是 BLOCK 或 RECORD");
        }
        String whitelistMode = normalize(command.whitelistMode(), "OFF");
        if (!Set.of("OFF", "RECORD", "ENFORCE").contains(whitelistMode)) {
            throw invalid("whitelist_mode", "白名单模式只能是 OFF、RECORD 或 ENFORCE");
        }
        if (command.keywords() != null && command.keywords().size() > 500) {
            throw invalid("keywords", "关键词规则数量不能超过 500");
        }
    }
}
