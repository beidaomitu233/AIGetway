package com.lightai.storage.runtimeconfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * runtime_config JDBC 只读实现（DATABASE_PLAN §10）。
 * 单例行 singleton_key=1；timezone 为全局锁定时区。
 */
public final class JdbcRuntimeConfigRepository implements RuntimeConfigRepository {

    private final String schemaName;

    public JdbcRuntimeConfigRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcRuntimeConfigRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<RuntimeConfigState> findRuntimeState(Connection connection) {
        String sql = "SELECT current_snapshot_no, timezone FROM " + schemaName
                + ".runtime_config WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new RuntimeConfigState(rs.getLong("current_snapshot_no"), rs.getString("timezone")));
        } catch (SQLException e) {
            throw new IllegalStateException("运行参数读取失败：" + e.getClass().getSimpleName(), e);
        }
    }
}
