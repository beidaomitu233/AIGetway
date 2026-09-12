package com.lightai.admin.usage;

import com.lightai.admin.trace.TraceService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.usage.UsageResults.UsageAdjustmentItem;
import com.lightai.client.usage.UsageResults.UsageAdjustmentsResult;
import com.lightai.storage.trace.JdbcUsageAdjustmentRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 额度流水查询（BE-232；PRD 9.8 /usage/adjustments）。
 * quota_adjustment 与 usage_ledger 为事实源：人工调整/重置/续期与账本事件
 * 合并返回，跨应用流水按身份数据范围过滤；币种随行返回，不做跨币种换算。
 * 分页过深（超出合并窗口）时明确报错，不返回静默截断的假分页。
 */
public class UsageAdjustmentService {

    /** 单分支最大合并窗口：page * page_size 超过该值时要求缩小时间范围。 */
    static final int MAX_WINDOW_ROWS = 5000;

    private final DataSource dataSource;
    private final JdbcUsageAdjustmentRepository repository;
    private final Clock clock;

    public UsageAdjustmentService(DataSource dataSource,
                                  JdbcUsageAdjustmentRepository repository, Clock clock) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.clock = clock;
    }

    public UsageAdjustmentsResult adjustments(RequestContext context,
                                              Map<String, String> params) {
        RequestPermissions.require(context, Permissions.USAGE_VIEW);
        int page = parsePositive(params.get("page"), "page", 1, 100000);
        int pageSize = parsePositive(params.get("page_size"), "page_size", 20, 100);
        if ((long) page * pageSize > MAX_WINDOW_ROWS) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "分页过深，请缩小时间范围或减小页号",
                    List.of(new FieldIssue("page", "INVALID",
                            "page * page_size 最大 " + MAX_WINDOW_ROWS)));
        }
        var filter = filterOf(context, params);
        OffsetDateTime queryStartedAt = OffsetDateTime.now(clock);
        try (Connection connection = dataSource.getConnection()) {
            long total = repository.count(connection, filter);
            List<JdbcUsageAdjustmentRepository.AdjustmentRow> rows =
                    repository.list(connection, filter, pageSize, (long) (page - 1) * pageSize);
            OffsetDateTime dataUpdatedAt = rows.isEmpty() ? queryStartedAt
                    : rows.get(0).occurredAt();
            List<UsageAdjustmentItem> items = new ArrayList<>(rows.size());
            for (var row : rows) {
                items.add(toItem(row));
            }
            return new UsageAdjustmentsResult(total, page, pageSize, queryStartedAt,
                    dataUpdatedAt, items);
        } catch (LightAiException e) {
            throw e;
        } catch (SQLException e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE,
                    "额度流水当前无法读取");
        }
    }

    private JdbcUsageAdjustmentRepository.AdjustmentFilter filterOf(RequestContext context,
                                                                    Map<String, String> params) {
        return new JdbcUsageAdjustmentRepository.AdjustmentFilter(
                TraceService.scopeApplications(context),
                parseUuidOrNull(params.get("application_id"), "application_id"),
                params.get("request_id"),
                normalizeSource(params.get("source")),
                parseTime(params.get("from"), "from"),
                parseTime(params.get("to"), "to"));
    }

    private UsageAdjustmentItem toItem(JdbcUsageAdjustmentRepository.AdjustmentRow row) {
        String eventType;
        if ("USAGE_LEDGER".equals(row.source())) {
            String key = row.eventKey() == null ? "" : row.eventKey();
            int split = key.indexOf(':');
            eventType = split > 0 ? key.substring(0, split) : key;
        } else {
            eventType = row.dimension();
        }
        return new UsageAdjustmentItem(
                row.id() == null ? null : row.id().toString(),
                row.occurredAt(),
                row.source(),
                eventType,
                row.requestId(),
                row.applicationId() == null ? null : row.applicationId().toString(),
                row.applicationCode(),
                row.dimension(),
                decimalString(row.beforeValue()),
                decimalString(row.deltaValue()),
                decimalString(row.afterValue()),
                row.inputTokens(),
                row.outputTokens(),
                row.tokenDelta(),
                decimalString(row.amountDelta()),
                row.currency(),
                row.usageSource(),
                row.operatorId(),
                row.reason(),
                row.idempotencyKey());
    }

    private static String decimalString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!Set.of("QUOTA_ADJUSTMENT", "USAGE_LEDGER").contains(raw)) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "source 不合法",
                    List.of(new FieldIssue("source", "INVALID",
                            "source 仅支持 QUOTA_ADJUSTMENT 或 USAGE_LEDGER")));
        }
        return raw;
    }

    private static UUID parseUuidOrNull(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 不合法",
                    List.of(new FieldIssue(field, "INVALID", field + " 必须是 UUID")));
        }
    }

    private static OffsetDateTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 不合法",
                    List.of(new FieldIssue(field, "INVALID",
                            field + " 必须为 ISO-8601 时间")));
        }
    }

    private static int parsePositive(String raw, String field, int defaultValue, int maxValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > maxValue) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 不合法",
                    List.of(new FieldIssue(field, "INVALID",
                            field + " 必须是 1～" + maxValue + " 的整数")));
        }
    }
}
