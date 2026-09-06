package com.lightai.storage.trace;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 观测相关运行参数读取（DATABASE_PLAN runtime_config 单例行，BE-032/035/036）。
 * 缺行时返回空，由服务层回退部署默认值，不虚构配置。
 */
public class JdbcObservationConfigReader {

    public record ObservationConfig(
            String timezone,
            int traceRetentionDays,
            int usageRetentionDays,
            boolean diagnosticSamplingEnabled,
            int diagnosticSampleRetentionDays) {
    }

    private final String schemaName;

    public JdbcObservationConfigReader(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcObservationConfigReader() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public Optional<ObservationConfig> read(Connection connection) {
        String sql = "SELECT timezone, trace_retention_days, usage_retention_days, "
                + "diagnostic_sampling_enabled, diagnostic_sample_retention_days FROM "
                + schemaName + ".runtime_config WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new ObservationConfig(
                    rs.getString("timezone"),
                    rs.getInt("trace_retention_days"),
                    rs.getInt("usage_retention_days"),
                    rs.getBoolean("diagnostic_sampling_enabled"),
                    rs.getInt("diagnostic_sample_retention_days")));
        } catch (SQLException e) {
            throw new IllegalStateException("运行参数读取失败：" + e.getClass().getSimpleName(), e);
        }
    }
}
