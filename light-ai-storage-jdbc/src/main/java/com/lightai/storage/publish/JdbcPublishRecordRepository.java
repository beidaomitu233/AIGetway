package com.lightai.storage.publish;

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
public final class JdbcPublishRecordRepository implements PublishRecordRepository {

    private static final String COLUMNS =
            "id, validation_id, from_snapshot_no, target_snapshot_no, draft_revision, status, "
                    + "published_by, publish_note, acknowledged_warning_ids, target_instance_ids, "
                    + "completed_at, converged_at, duration_ms, error_code, error_summary, "
                    + "created_at, updated_at";

    private final String schemaName;

    public JdbcPublishRecordRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcPublishRecordRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, PublishRecordRecord record) {
        String sql = "INSERT INTO " + schemaName + ".publish_record (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, now(), now())";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, record.id());
            statement.setObject(2, record.validationId());
            statement.setLong(3, record.fromSnapshotNo());
            statement.setLong(4, record.targetSnapshotNo());
            statement.setLong(5, record.draftRevision());
            statement.setString(6, record.status());
            statement.setString(7, record.publishedBy());
            statement.setString(8, record.publishNote());
            statement.setString(9, toJsonArray(record.acknowledgedWarningIds()));
            statement.setString(10, toUuidArray(record.targetInstanceIds()));
            statement.setObject(11, record.completedAt());
            statement.setObject(12, record.convergedAt());
            statement.setObject(13, record.durationMs());
            statement.setString(14, record.errorCode());
            statement.setString(15, record.errorSummary());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("发布记录写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 状态与终态字段更新；duration/completed 只允许设置一次（首轮口径）。 */
    public void updateOutcome(Connection connection, UUID id, String status,
                              OffsetDateTime completedAt, OffsetDateTime convergedAt,
                              Long durationMs, String errorCode, String errorSummary) {
        String sql = "UPDATE " + schemaName + ".publish_record SET status = ?, "
                + "completed_at = COALESCE(completed_at, ?), converged_at = ?, "
                + "duration_ms = COALESCE(duration_ms, ?), error_code = ?, error_summary = ?, updated_at = now() "
                + "WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setObject(2, completedAt);
            statement.setObject(3, convergedAt);
            statement.setObject(4, durationMs);
            statement.setString(5, errorCode);
            statement.setString(6, errorSummary);
            statement.setObject(7, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("发布记录更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<PublishRecordRecord> find(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + schemaName + ".publish_record WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("发布记录读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<PublishRecordRecord> findByValidation(Connection connection, UUID validationId) {
        String sql = "SELECT " + COLUMNS + " FROM " + schemaName
                + ".publish_record WHERE validation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, validationId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("发布记录读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 发布历史筛选（4.5.2.6）；sort 已由白名单校验后原样进入 ORDER BY。 */
    public List<PublishRecordRecord> list(Connection connection, PublishRecordFilter filter,
                                          String sortExpression, int limit, long offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ")
                .append(schemaName).append(".publish_record WHERE deleted_at IS NULL");
        appendFilter(filter, sql);
        sql.append(" ORDER BY ").append(sortExpression).append(" LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = bindFilter(statement, filter);
            statement.setInt(index++, limit);
            statement.setLong(index, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<PublishRecordRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("发布历史查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public long count(Connection connection, PublishRecordFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(schemaName).append(".publish_record WHERE deleted_at IS NULL");
        appendFilter(filter, sql);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindFilter(statement, filter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("发布历史计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 超时/重启恢复扫描候选：未终态记录。 */
    public List<PublishRecordRecord> listUnfinished(Connection connection) {
        String sql = "SELECT " + COLUMNS + " FROM " + schemaName + ".publish_record "
                + "WHERE status IN ('PREPARING', 'ACTIVATING') ORDER BY created_at ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<PublishRecordRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(mapRow(rs));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IllegalStateException("未完成发布查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private PublishRecordRecord mapRow(ResultSet rs) throws SQLException {
        return new PublishRecordRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("validation_id", UUID.class),
                rs.getLong("from_snapshot_no"),
                rs.getLong("target_snapshot_no"),
                rs.getLong("draft_revision"),
                rs.getString("status"),
                rs.getString("published_by"),
                rs.getString("publish_note"),
                fromJsonArray(rs.getString("acknowledged_warning_ids")),
                fromUuidArray(rs.getString("target_instance_ids")),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("converged_at", OffsetDateTime.class),
                rs.getObject("duration_ms", Long.class),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static void appendFilter(PublishRecordFilter filter, StringBuilder sql) {
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            sql.append(" AND status = ANY(?)");
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
            sql.append(" AND (published_by ILIKE ? OR publish_note ILIKE ?)");
        }
    }

    private static int bindFilter(PreparedStatement statement, PublishRecordFilter filter)
            throws SQLException {
        int index = 1;
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            statement.setArray(index++, statement.getConnection()
                    .createArrayOf("text", filter.statuses().toArray()));
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
