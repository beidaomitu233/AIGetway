package com.lightai.storage.check;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** provider_check_record JDBC 实现（DATABASE_PLAN §12）。 */
public final class JdbcCheckRecordRepository implements CheckRecordRepository {

    private static final String COLUMNS = """
            id, target_type, target_id, mode, status, operator_id, trace_id, attempt_id,
            started_at, ended_at, total_ms, usage, provider_request_id, error_code, error_summary""";

    private final String schemaName;

    public JdbcCheckRecordRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcCheckRecordRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public void insert(Connection connection, CheckRecord record) {
        String sql = "INSERT INTO " + qualified() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setObject(i++, record.id());
            statement.setString(i++, record.targetType());
            statement.setObject(i++, record.targetId());
            statement.setString(i++, record.mode());
            statement.setString(i++, record.status());
            statement.setString(i++, record.operatorId());
            statement.setString(i++, record.traceId());
            statement.setObject(i++, record.attemptId());
            statement.setTimestamp(i++, Timestamp.from(record.startedAt().toInstant()));
            statement.setTimestamp(i++, Timestamp.from(record.endedAt().toInstant()));
            statement.setInt(i++, record.totalMs());
            if (record.usageInputTokens() == null && record.usageOutputTokens() == null) {
                statement.setNull(i++, java.sql.Types.OTHER);
            } else {
                statement.setString(i++, usageJson(record));
            }
            statement.setString(i++, record.providerRequestId());
            statement.setString(i++, record.errorCode());
            statement.setString(i++, record.errorSummary());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("provider_check_record 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<CheckRecord> find(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("provider_check_record 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<CheckRecord> findLatestByTarget(Connection connection, UUID targetId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE target_id = ? ORDER BY started_at DESC, created_at DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, targetId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<CheckRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("provider_check_record 历史读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static String usageJson(CheckRecord record) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        first = appendToken(json, first, "input_tokens", record.usageInputTokens());
        first = appendToken(json, first, "output_tokens", record.usageOutputTokens());
        first = appendToken(json, first, "total_tokens", record.usageTotalTokens());
        if (record.usageSource() != null) {
            json.append(first ? "" : ",").append("\"source\":\"").append(record.usageSource()).append("\"");
        }
        return json.append('}').toString();
    }

    private static boolean appendToken(StringBuilder json, boolean first, String key, Long value) {
        if (value == null) {
            return first;
        }
        json.append(first ? "" : ",").append("\"").append(key).append("\":").append(value);
        return false;
    }

    private static CheckRecord mapRow(ResultSet rs) throws SQLException {
        return new CheckRecord(
                rs.getObject("id", UUID.class),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("mode"),
                rs.getString("status"),
                rs.getString("operator_id"),
                rs.getString("trace_id"),
                rs.getObject("attempt_id", UUID.class),
                offset(rs.getTimestamp("started_at")),
                offset(rs.getTimestamp("ended_at")),
                rs.getInt("total_ms"),
                null,
                null,
                null,
                null,
                rs.getString("provider_request_id"),
                rs.getString("error_code"),
                rs.getString("error_summary"));
    }

    private String qualified() {
        return schemaName + ".provider_check_record";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
