package com.lightai.storage.publish;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * config_validation / config_validation_issue JDBC 实现（DATABASE_PLAN §30/31）。
 * 校验与问题在同一事务插入；EXPIRED 由服务层在读取时按条件惰性落定。
 */
public final class JdbcConfigValidationRepository implements ConfigValidationRepository {

    private static final String COLUMNS =
            "validation_id, base_snapshot_no, target_snapshot_no, draft_revision, content_checksum, "
                    + "status, error_count, warning_count, validated_at, expires_at, validated_by, "
                    + "used_by_publish_id, change_summary, affected_alias_ids, target_instances";

    private final String schemaName;

    public JdbcConfigValidationRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcConfigValidationRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, ConfigValidationRecord record,
                       List<ConfigValidationIssueRecord> issues) {
        String insertSql = "INSERT INTO " + schemaName + ".config_validation (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setObject(1, record.validationId());
            statement.setLong(2, record.baseSnapshotNo());
            statement.setLong(3, record.targetSnapshotNo());
            statement.setLong(4, record.draftRevision());
            statement.setString(5, record.contentChecksum());
            statement.setString(6, record.status());
            statement.setInt(7, record.errorCount());
            statement.setInt(8, record.warningCount());
            statement.setObject(9, record.validatedAt());
            statement.setObject(10, record.expiresAt());
            statement.setString(11, record.validatedBy());
            statement.setObject(12, record.usedByPublishId());
            statement.setString(13, record.changeSummaryJson());
            statement.setString(14, toJsonArray(record.affectedAliasIds()));
            statement.setString(15, record.targetInstancesJson());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("校验写入失败：" + e.getClass().getSimpleName(), e);
        }
        for (ConfigValidationIssueRecord issue : issues) {
            insertIssue(connection, issue);
        }
    }

    private void insertIssue(Connection connection, ConfigValidationIssueRecord issue) {
        String sql = "INSERT INTO " + schemaName + ".config_validation_issue "
                + "(id, validation_id, severity, code, entity_type, entity_id, entity_name, "
                + "field_path, message, suggestion, related_entity_ids) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, issue.validationId());
            statement.setString(3, issue.severity());
            statement.setString(4, issue.code());
            statement.setString(5, issue.entityType());
            statement.setObject(6, issue.entityId());
            statement.setString(7, issue.entityName());
            statement.setString(8, issue.fieldPath());
            statement.setString(9, issue.message());
            statement.setString(10, issue.suggestion());
            statement.setString(11, toJsonArray(issue.relatedEntityIds()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("校验问题写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 按 validation_id 读取（含问题列表，ERROR 在前按 entity_type/field_path 排序）。 */
    public Optional<ValidationWithIssues> find(Connection connection, UUID validationId) {
        String sql = "SELECT " + COLUMNS + " FROM " + schemaName
                + ".config_validation WHERE validation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, validationId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ConfigValidationRecord record = mapRow(rs);
                return Optional.of(new ValidationWithIssues(record, listIssues(connection, validationId)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 发布绑定：单次使用标记（CONFIG_VALIDATION_EXPIRED 防重放由服务层核对）。 */
    public void markUsed(Connection connection, UUID validationId, UUID publishId) {
        String sql = "UPDATE " + schemaName + ".config_validation "
                + "SET used_by_publish_id = ?, updated_at = now() WHERE validation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, publishId);
            statement.setObject(2, validationId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("校验使用标记失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 过期惰性落定：PASSED 且 expires_at 已过的校验标为 EXPIRED。 */
    public void sweepExpired(Connection connection, OffsetDateTime now) {
        String sql = "UPDATE " + schemaName + ".config_validation SET status = 'EXPIRED', updated_at = now() "
                + "WHERE status = 'PASSED' AND expires_at < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("校验过期落定失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private List<ConfigValidationIssueRecord> listIssues(Connection connection, UUID validationId)
            throws SQLException {
        String sql = "SELECT severity, code, entity_type, entity_id, entity_name, field_path, "
                + "message, suggestion, related_entity_ids FROM " + schemaName
                + ".config_validation_issue WHERE validation_id = ? "
                + "ORDER BY CASE severity WHEN 'ERROR' THEN 0 ELSE 1 END, entity_type, field_path";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, validationId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ConfigValidationIssueRecord> issues = new java.util.ArrayList<>();
                while (rs.next()) {
                    issues.add(new ConfigValidationIssueRecord(
                            validationId,
                            rs.getString("severity"),
                            rs.getString("code"),
                            rs.getString("entity_type"),
                            rs.getObject("entity_id", UUID.class),
                            rs.getString("entity_name"),
                            rs.getString("field_path"),
                            rs.getString("message"),
                            rs.getString("suggestion"),
                            fromJsonArray(rs.getString("related_entity_ids"))));
                }
                return List.copyOf(issues);
            }
        }
    }

    private ConfigValidationRecord mapRow(ResultSet rs) throws SQLException {
        return new ConfigValidationRecord(
                rs.getObject("validation_id", UUID.class),
                rs.getLong("base_snapshot_no"),
                rs.getLong("target_snapshot_no"),
                rs.getLong("draft_revision"),
                rs.getString("content_checksum"),
                rs.getString("status"),
                rs.getInt("error_count"),
                rs.getInt("warning_count"),
                rs.getObject("validated_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("validated_by"),
                rs.getObject("used_by_publish_id", UUID.class),
                rs.getString("change_summary"),
                fromJsonArray(rs.getString("affected_alias_ids")),
                rs.getString("target_instances"));
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

    private static List<String> fromJsonArray(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
            return List.of();
        }
        String body = raw.trim();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        for (String item : body.split(",")) {
            values.add(item.trim().replaceAll("^\"|\"$", "").replace("\\\"", "\""));
        }
        return List.copyOf(values);
    }

}
