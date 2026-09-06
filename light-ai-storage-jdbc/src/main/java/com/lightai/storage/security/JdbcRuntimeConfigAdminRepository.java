package com.lightai.storage.security;

import java.math.BigDecimal;
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

/** runtime_config JDBC 管理读写实现（DATABASE_PLAN §10）。 */
public final class JdbcRuntimeConfigAdminRepository implements RuntimeConfigAdminRepository {

    private static final String COLUMNS = """
            id, timezone, timezone_locked, trace_retention_days, usage_retention_days, audit_retention_days,
            dashboard_refresh_seconds, max_message_chars, max_request_chars, diagnostic_sampling_enabled,
            diagnostic_sample_rate, diagnostic_sample_retention_days, diagnostic_sample_max_chars,
            client_ip_recording_enabled, trusted_proxy_cidrs, publish_instance_timeout_seconds,
            instance_stale_seconds, default_alias_id, current_snapshot_no, published_at, version,
            created_at, updated_at""";

    private final String schemaName;

    public JdbcRuntimeConfigAdminRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcRuntimeConfigAdminRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<RuntimeConfigRow> find(Connection connection) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("runtime_config 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void update(Connection connection, RuntimeConfigRow row) {
        String sql = """
                UPDATE %s SET timezone=?, timezone_locked=?, trace_retention_days=?, usage_retention_days=?,
                audit_retention_days=?, dashboard_refresh_seconds=?, max_message_chars=?, max_request_chars=?,
                diagnostic_sampling_enabled=?, diagnostic_sample_rate=?, diagnostic_sample_retention_days=?,
                diagnostic_sample_max_chars=?, client_ip_recording_enabled=?, trusted_proxy_cidrs=?,
                publish_instance_timeout_seconds=?, instance_stale_seconds=?, default_alias_id=?, version=?,
                updated_at=? WHERE singleton_key=1""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, row.timezone());
            statement.setBoolean(i++, row.timezoneLocked());
            statement.setInt(i++, row.traceRetentionDays());
            statement.setInt(i++, row.usageRetentionDays());
            statement.setInt(i++, row.auditRetentionDays());
            statement.setInt(i++, row.dashboardRefreshSeconds());
            statement.setInt(i++, row.maxMessageChars());
            statement.setInt(i++, row.maxRequestChars());
            statement.setBoolean(i++, row.diagnosticSamplingEnabled());
            statement.setBigDecimal(i++, row.diagnosticSampleRate());
            statement.setInt(i++, row.diagnosticSampleRetentionDays());
            statement.setInt(i++, row.diagnosticSampleMaxChars());
            statement.setBoolean(i++, row.clientIpRecordingEnabled());
            statement.setString(i++, toJson(row.trustedProxyCidrs()));
            statement.setInt(i++, row.publishInstanceTimeoutSeconds());
            statement.setInt(i++, row.instanceStaleSeconds());
            statement.setObject(i++, row.defaultAliasId());
            statement.setLong(i++, row.version());
            statement.setTimestamp(i, Timestamp.from(row.updatedAt().toInstant()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("runtime_config 更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<Long> findVersion(Connection connection) {
        String sql = "SELECT version FROM " + qualified() + " WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("runtime_config 版本读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static RuntimeConfigRow mapRow(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        java.sql.Array cidrs = rs.getArray("trusted_proxy_cidrs");
        List<String> cidrList = new ArrayList<>();
        if (cidrs != null) {
            try {
                for (String value : (String[]) cidrs.getArray()) {
                    cidrList.add(value);
                }
            } catch (Exception ignored) {
                // jsonb 数组以字符串解析；空数组保持默认
            }
        }
        return new RuntimeConfigRow(
                rs.getObject("id", UUID.class),
                rs.getString("timezone"),
                rs.getBoolean("timezone_locked"),
                rs.getInt("trace_retention_days"),
                rs.getInt("usage_retention_days"),
                rs.getInt("audit_retention_days"),
                rs.getInt("dashboard_refresh_seconds"),
                rs.getInt("max_message_chars"),
                rs.getInt("max_request_chars"),
                rs.getBoolean("diagnostic_sampling_enabled"),
                rs.getBigDecimal("diagnostic_sample_rate"),
                rs.getInt("diagnostic_sample_retention_days"),
                rs.getInt("diagnostic_sample_max_chars"),
                rs.getBoolean("client_ip_recording_enabled"),
                cidrList,
                rs.getInt("publish_instance_timeout_seconds"),
                rs.getInt("instance_stale_seconds"),
                rs.getObject("default_alias_id", UUID.class),
                rs.getLong("current_snapshot_no"),
                publishedAt == null ? null : offset(publishedAt),
                rs.getLong("version"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at")));
    }

    private static String toJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < (values == null ? 0 : values.size()); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }

    private String qualified() {
        return schemaName + ".runtime_config";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
