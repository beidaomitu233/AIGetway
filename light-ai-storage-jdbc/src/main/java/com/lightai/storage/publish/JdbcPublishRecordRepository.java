package com.lightai.storage.publish;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * publish_record JDBC 实现（DATABASE_PLAN §33）。
 * validation_id 唯一：同一校验重复发布返回既有记录（4.5.2.4，服务层核对）。
 */
public final class JdbcPublishRecordRepository extends AbstractJdbcRepository implements PublishRecordRepository {

    private static final String COLUMNS =
            "id, validation_id, from_snapshot_no, target_snapshot_no, draft_revision, status, "
                    + "published_by, publish_note, acknowledged_warning_ids, target_instance_ids, "
                    + "completed_at, converged_at, duration_ms, error_code, error_summary, "
                    + "created_at, updated_at";

    public JdbcPublishRecordRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcPublishRecordRepository() {
        super();
    }

    @Override
    public void insert(Connection connection, PublishRecordRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "publish_record") + " (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ", " + d.jsonPlaceholder()
                + ", ?, ?, ?, ?, ?, " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, record.id());
            d.bindUuid(statement, 2, record.validationId());
            statement.setLong(3, record.fromSnapshotNo());
            statement.setLong(4, record.targetSnapshotNo());
            statement.setLong(5, record.draftRevision());
            statement.setString(6, record.status());
            statement.setString(7, record.publishedBy());
            statement.setString(8, record.publishNote());
            d.bindJson(statement, 9, toJsonArray(record.acknowledgedWarningIds()));
            d.bindJson(statement, 10, toUuidArray(record.targetInstanceIds()));
            statement.setObject(11, record.completedAt());
            statement.setObject(12, record.convergedAt());
            statement.setObject(13, record.durationMs());
            statement.setString(14, record.errorCode());
            statement.setString(15, record.errorSummary());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("发布记录写入失败", e);
        }
    }

    /** 状态与终态字段更新；duration/completed 只允许设置一次（首轮口径）。 */
    @Override
    public void updateOutcome(Connection connection, UUID id, String status,
                              OffsetDateTime completedAt, OffsetDateTime convergedAt,
                              Long durationMs, String errorCode, String errorSummary) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "publish_record") + " SET status = ?, "
                + "completed_at = COALESCE(completed_at, ?), converged_at = ?, "
                + "duration_ms = COALESCE(duration_ms, ?), error_code = ?, error_summary = ?, updated_at = " + d.nowFunction() + " "
                + "WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setObject(2, completedAt);
            statement.setObject(3, convergedAt);
            statement.setObject(4, durationMs);
            statement.setString(5, errorCode);
            statement.setString(6, errorSummary);
            d.bindUuid(statement, 7, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("发布记录更新失败", e);
        }
    }

    @Override
    public Optional<PublishRecordRecord> find(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "publish_record") + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(d, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("发布记录读取失败", e);
        }
    }

    @Override
    public Optional<PublishRecordRecord> findByValidation(Connection connection, UUID validationId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "publish_record")
                + " WHERE validation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, validationId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(d, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("发布记录读取失败", e);
        }
    }

    /** 发布历史筛选（4.5.2.6）；sort 已由白名单校验后原样进入 ORDER BY。 */
    @Override
    public List<PublishRecordRecord> list(Connection connection, PublishRecordFilter filter,
                                          String sortExpression, int limit, long offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ")
                .append(qualify(connection, "publish_record")).append(" WHERE deleted_at IS NULL");
        appendFilter(d, filter, sql);
        sql.append(" ORDER BY ").append(sortExpression).append(" LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = bindFilter(d, statement, filter);
            statement.setInt(index++, limit);
            statement.setLong(index, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<PublishRecordRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(d, rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("发布历史查询失败", e);
        }
    }

    @Override
    public long count(Connection connection, PublishRecordFilter filter) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "publish_record")).append(" WHERE deleted_at IS NULL");
        appendFilter(d, filter, sql);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindFilter(d, statement, filter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("发布历史计数失败", e);
        }
    }

    /** 超时/重启恢复扫描候选：未终态记录。 */
    @Override
    public List<PublishRecordRecord> listUnfinished(Connection connection) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "publish_record")
                + " WHERE status IN ('PREPARING', 'ACTIVATING') ORDER BY created_at ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<PublishRecordRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(mapRow(d, rs));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw translate("未完成发布查询失败", e);
        }
    }

    private PublishRecordRecord mapRow(DatabaseDialect d, ResultSet rs) throws SQLException {
        return new PublishRecordRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "validation_id"),
                rs.getLong("from_snapshot_no"),
                rs.getLong("target_snapshot_no"),
                rs.getLong("draft_revision"),
                rs.getString("status"),
                rs.getString("published_by"),
                rs.getString("publish_note"),
                fromJsonArray(d.readJson(rs, "acknowledged_warning_ids")),
                fromUuidArray(d.readJson(rs, "target_instance_ids")),
                d.readOffsetDateTime(rs, "completed_at"),
                d.readOffsetDateTime(rs, "converged_at"),
                getLongOrNull(rs, "duration_ms"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }

    private static void appendFilter(DatabaseDialect d, PublishRecordFilter filter, StringBuilder sql) {
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            if (d.supportsArrayType()) {
                sql.append(" AND status = ANY(?)");
            } else {
                sql.append(" AND status IN (").append(inPlaceholders(filter.statuses().size())).append(")");
            }
        }
        if (filter.publishedBy() != null && !filter.publishedBy().isBlank()) {
            sql.append(" AND published_by = ?");
        }
        if (filter.snapshotNo() != null) {
            sql.append(" AND target_snapshot_no = ?");
        }
        if (filter.startFrom() != null) {
            sql.append(" AND created_at >= ?");
        }
        if (filter.startTo() != null) {
            sql.append(" AND created_at < ?");
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND (").append(d.ilikeClause("published_by"))
                    .append(" OR ").append(d.ilikeClause("publish_note")).append(")");
        }
    }

    private static int bindFilter(DatabaseDialect d, PreparedStatement statement, PublishRecordFilter filter)
            throws SQLException {
        int index = 1;
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            if (d.supportsArrayType()) {
                statement.setArray(index++, statement.getConnection()
                        .createArrayOf("text", filter.statuses().toArray()));
            } else {
                for (String st : filter.statuses()) {
                    statement.setString(index++, st);
                }
            }
        }
        if (filter.publishedBy() != null && !filter.publishedBy().isBlank()) {
            statement.setString(index++, filter.publishedBy());
        }
        if (filter.snapshotNo() != null) {
            statement.setLong(index++, filter.snapshotNo());
        }
        if (filter.startFrom() != null) {
            statement.setObject(index++, filter.startFrom());
        }
        if (filter.startTo() != null) {
            statement.setObject(index++, filter.startTo());
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String like = "%" + filter.keyword() + "%";
            statement.setString(index++, like);
            statement.setString(index++, like);
        }
        return index;
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }

    private static String toUuidArray(List<UUID> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i)).append('"');
        }
        return json.append(']').toString();
    }

    private static List<String> fromJsonArray(String raw) {
        return StringListJson.parse(raw);
    }

    private static List<UUID> fromUuidArray(String raw) {
        return StringListJson.parse(raw).stream().map(UUID::fromString).toList();
    }

}

