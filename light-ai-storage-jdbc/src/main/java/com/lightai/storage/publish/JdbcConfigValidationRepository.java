package com.lightai.storage.publish;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
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
public final class JdbcConfigValidationRepository extends AbstractJdbcRepository implements ConfigValidationRepository {

    private static final String COLUMNS =
            "validation_id, base_snapshot_no, target_snapshot_no, draft_revision, content_checksum, "
                    + "status, error_count, warning_count, validated_at, expires_at, validated_by, "
                    + "used_by_publish_id, change_summary, affected_alias_ids, target_instances";

    public JdbcConfigValidationRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcConfigValidationRepository() {
        super();
    }

    @Override
    public void insert(Connection connection, ConfigValidationRecord record,
                       List<ConfigValidationIssueRecord> issues) {
        DatabaseDialect d = dialect(connection);
        String insertSql = "INSERT INTO " + qualify(connection, "config_validation") + " (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + d.jsonPlaceholder() + ", " + d.jsonPlaceholder() + ", " + d.jsonPlaceholder() + ")";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            d.bindUuid(statement, 1, record.validationId());
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
            d.bindUuid(statement, 12, record.usedByPublishId());
            d.bindJson(statement, 13, record.changeSummaryJson());
            d.bindJson(statement, 14, toJsonArray(record.affectedAliasIds()));
            d.bindJson(statement, 15, record.targetInstancesJson());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("校验写入失败", e);
        }
        for (ConfigValidationIssueRecord issue : issues) {
            insertIssue(connection, issue);
        }
    }

    private void insertIssue(Connection connection, ConfigValidationIssueRecord issue) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "config_validation_issue")
                + " (id, validation_id, severity, code, entity_type, entity_id, entity_name, "
                + "field_path, message, suggestion, related_entity_ids) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, UUID.randomUUID());
            d.bindUuid(statement, 2, issue.validationId());
            statement.setString(3, issue.severity());
            statement.setString(4, issue.code());
            statement.setString(5, issue.entityType());
            d.bindUuid(statement, 6, issue.entityId());
            statement.setString(7, issue.entityName());
            statement.setString(8, issue.fieldPath());
            statement.setString(9, issue.message());
            statement.setString(10, issue.suggestion());
            d.bindJson(statement, 11, toJsonArray(issue.relatedEntityIds()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("校验问题写入失败", e);
        }
    }

    /** 按 validation_id 读取（含问题列表，ERROR 在前按 entity_type/field_path 排序）。 */
    @Override
    public Optional<ValidationWithIssues> find(Connection connection, UUID validationId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "config_validation")
                + " WHERE validation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, validationId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ConfigValidationRecord record = mapRow(d, rs);
                return Optional.of(new ValidationWithIssues(record, listIssues(connection, validationId)));
            }
        } catch (SQLException e) {
            throw translate("校验读取失败", e);
        }
    }

    /** 发布绑定：单次使用标记（CONFIG_VALIDATION_EXPIRED 防重放由服务层核对）。 */
    @Override
    public void markUsed(Connection connection, UUID validationId, UUID publishId) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "config_validation")
                + " SET used_by_publish_id = ?, updated_at = " + d.nowFunction() + " WHERE validation_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, publishId);
            d.bindUuid(statement, 2, validationId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("校验使用标记失败", e);
        }
    }

    /** 过期惰性落定：PASSED 且 expires_at 已过的校验标为 EXPIRED。 */
    @Override
    public void sweepExpired(Connection connection, OffsetDateTime now) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "config_validation") + " SET status = 'EXPIRED', updated_at = " + d.nowFunction()
                + " WHERE status = 'PASSED' AND expires_at < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("校验过期落定失败", e);
        }
    }

    private List<ConfigValidationIssueRecord> listIssues(Connection connection, UUID validationId)
            throws SQLException {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT severity, code, entity_type, entity_id, entity_name, field_path, "
                + "message, suggestion, related_entity_ids FROM " + qualify(connection, "config_validation_issue")
                + " WHERE validation_id = ? "
                + "ORDER BY CASE severity WHEN 'ERROR' THEN 0 ELSE 1 END, entity_type, field_path";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, validationId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ConfigValidationIssueRecord> issues = new java.util.ArrayList<>();
                while (rs.next()) {
                    issues.add(new ConfigValidationIssueRecord(
                            validationId,
                            rs.getString("severity"),
                            rs.getString("code"),
                            rs.getString("entity_type"),
                            d.readUuid(rs, "entity_id"),
                            rs.getString("entity_name"),
                            rs.getString("field_path"),
                            rs.getString("message"),
                            rs.getString("suggestion"),
                            fromJsonArray(d.readJson(rs, "related_entity_ids"))));
                }
                return List.copyOf(issues);
            }
        }
    }

    private ConfigValidationRecord mapRow(DatabaseDialect d, ResultSet rs) throws SQLException {
        return new ConfigValidationRecord(
                d.readUuid(rs, "validation_id"),
                rs.getLong("base_snapshot_no"),
                rs.getLong("target_snapshot_no"),
                rs.getLong("draft_revision"),
                rs.getString("content_checksum"),
                rs.getString("status"),
                rs.getInt("error_count"),
                rs.getInt("warning_count"),
                d.readOffsetDateTime(rs, "validated_at"),
                d.readOffsetDateTime(rs, "expires_at"),
                rs.getString("validated_by"),
                d.readUuid(rs, "used_by_publish_id"),
                d.readJson(rs, "change_summary"),
                fromJsonArray(d.readJson(rs, "affected_alias_ids")),
                d.readJson(rs, "target_instances"));
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

