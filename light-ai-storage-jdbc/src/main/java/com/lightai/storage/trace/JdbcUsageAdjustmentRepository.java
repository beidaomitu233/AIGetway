package com.lightai.storage.trace;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 额度流水合并读取（BE-232；PRD 9.8 /usage/adjustments）。
 * 单条 UNION ALL 合并 quota_adjustment（人工调整/重置）与 usage_ledger
 * （结算等账本事件），统一按 occurred_at desc、id desc 排序；跨应用流水通过
 * JOIN application 以 code 落实身份数据范围。两个数据源均只追加、不可改写。
 */
public class JdbcUsageAdjustmentRepository extends AbstractJdbcRepository {

    public JdbcUsageAdjustmentRepository() {
        super();
    }

    public JdbcUsageAdjustmentRepository(String schemaName) {
        super(schemaName);
    }

    /** 流水查询条件；scopeCodes 为空或含 "*" 表示平台全域身份。 */
    public record AdjustmentFilter(List<String> scopeCodes, UUID applicationId,
                                   String requestId, String source,
                                   OffsetDateTime from, OffsetDateTime to) {
    }

    public record AdjustmentRow(
            UUID id,
            OffsetDateTime occurredAt,
            String source,
            String eventKey,
            String eventType,
            String requestId,
            UUID applicationId,
            String applicationCode,
            String dimension,
            BigDecimal beforeValue,
            BigDecimal deltaValue,
            BigDecimal afterValue,
            Long inputTokens,
            Long outputTokens,
            Long tokenDelta,
            BigDecimal amountDelta,
            String currency,
            String usageSource,
            String operatorId,
            String reason,
            String idempotencyKey) {
    }

    public List<AdjustmentRow> list(Connection connection, AdjustmentFilter filter,
                                    int limit, long offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM (");
        List<Object> params = new ArrayList<>();
        appendUnion(connection, sql, filter, params);
        sql.append(") t ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return execute(connection, sql.toString(), params);
    }

    public long count(Connection connection, AdjustmentFilter filter) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM (");
        List<Object> params = new ArrayList<>();
        appendUnion(connection, sql, filter, params);
        sql.append(") t");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, dialect(connection));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** source 筛选直接裁剪分支；request_id 只属于账本分支。 */
    private void appendUnion(Connection connection, StringBuilder sql, AdjustmentFilter filter,
                             List<Object> params) {
        boolean includeAdjustments = filter.source() == null || filter.source().isBlank()
                || "QUOTA_ADJUSTMENT".equals(filter.source());
        boolean includeLedger = filter.source() == null || filter.source().isBlank()
                || "USAGE_LEDGER".equals(filter.source());
        List<String> branches = new ArrayList<>();
        if (includeAdjustments) {
            StringBuilder branch = new StringBuilder()
                    .append("SELECT qa.created_at AS occurred_at, qa.id AS id, ")
                    .append("'QUOTA_ADJUSTMENT' AS source, NULL AS event_key, NULL AS event_type, NULL AS request_id, ")
                    .append("qa.application_id AS application_id, a.code AS application_code, ")
                    .append("qa.dimension AS dimension, qa.before_value AS before_value, ")
                    .append("qa.delta_value AS delta_value, qa.after_value AS after_value, ")
                    .append("NULL AS input_tokens, NULL AS output_tokens, NULL AS token_delta, ")
                    .append("NULL AS amount_delta, NULL AS currency, NULL AS usage_source, ")
                    .append("qa.operator_id AS operator_id, qa.reason AS reason, ")
                    .append("qa.idempotency_key AS idempotency_key ")
                    .append("FROM ").append(qualify(connection, "quota_adjustment")).append(" qa ")
                    .append("INNER JOIN ").append(qualify(connection, "application")).append(" a ")
                    .append("ON a.id = qa.application_id");
            appendFilters(branch, filter, params, "qa", false);
            branches.add(branch.toString());
        }
        if (includeLedger) {
            StringBuilder branch = new StringBuilder()
                    .append("SELECT ul.created_at AS occurred_at, ul.id AS id, ")
                    .append("'USAGE_LEDGER' AS source, ul.event_key AS event_key, ")
                    .append("ul.event_type AS event_type, ul.request_id AS request_id, ul.application_id AS application_id, ")
                    .append("a.code AS application_code, NULL AS dimension, NULL AS before_value, ")
                    .append("NULL AS delta_value, NULL AS after_value, ")
                    .append("ul.input_tokens AS input_tokens, ul.output_tokens AS output_tokens, ")
                    .append("ul.token_delta AS token_delta, ul.amount_delta AS amount_delta, ")
                    .append("ul.currency AS currency, ul.usage_source AS usage_source, ")
                    .append("NULL AS operator_id, NULL AS reason, NULL AS idempotency_key ")
                    .append("FROM ").append(qualify(connection, "usage_ledger")).append(" ul ")
                    .append("INNER JOIN ").append(qualify(connection, "application")).append(" a ")
                    .append("ON a.id = ul.application_id");
            appendFilters(branch, filter, params, "ul", true);
            branches.add(branch.toString());
        }
        sql.append(String.join(" UNION ALL ", branches));
    }

    private void appendFilters(StringBuilder branch, AdjustmentFilter filter,
                               List<Object> params, String alias, boolean ledgerBranch) {
        branch.append(" WHERE 1=1");
        if (!filter.scopeCodes().isEmpty() && !filter.scopeCodes().contains("*")) {
            branch.append(" AND a.code IN (")
                    .append(inPlaceholders(filter.scopeCodes().size())).append(")");
            params.addAll(filter.scopeCodes());
        }
        if (filter.applicationId() != null) {
            branch.append(" AND ").append(alias).append(".application_id = ?");
            params.add(filter.applicationId());
        }
        if (filter.from() != null) {
            branch.append(" AND ").append(alias).append(".created_at >= ?");
            params.add(filter.from());
        }
        if (filter.to() != null) {
            branch.append(" AND ").append(alias).append(".created_at < ?");
            params.add(filter.to());
        }
        if (ledgerBranch && filter.requestId() != null && !filter.requestId().isBlank()) {
            branch.append(" AND ul.request_id = ?");
            params.add(filter.requestId());
        }
    }

    private List<AdjustmentRow> execute(Connection connection, String sql, List<Object> params)
            throws SQLException {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                List<AdjustmentRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new AdjustmentRow(
                            d.readUuid(rs, "id"),
                            d.readOffsetDateTime(rs, "occurred_at"),
                            rs.getString("source"),
                            rs.getString("event_key"),
                            rs.getString("event_type"),
                            rs.getString("request_id"),
                            d.readUuid(rs, "application_id"),
                            rs.getString("application_code"),
                            rs.getString("dimension"),
                            rs.getBigDecimal("before_value"),
                            rs.getBigDecimal("delta_value"),
                            rs.getBigDecimal("after_value"),
                            getLongOrNull(rs, "input_tokens"),
                            getLongOrNull(rs, "output_tokens"),
                            getLongOrNull(rs, "token_delta"),
                            rs.getBigDecimal("amount_delta"),
                            rs.getString("currency"),
                            rs.getString("usage_source"),
                            rs.getString("operator_id"),
                            rs.getString("reason"),
                            rs.getString("idempotency_key")));
                }
                return List.copyOf(rows);
            }
        }
    }
}
