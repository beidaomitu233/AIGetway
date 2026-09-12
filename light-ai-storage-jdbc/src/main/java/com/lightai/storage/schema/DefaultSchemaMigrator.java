package com.lightai.storage.schema;

import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
import com.lightai.storage.dialect.DialectResolver;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.sql.DataSource;

/**
 * 产品数据库迁移执行器。
 *
 * <p>迁移在数据库级全局锁内顺序执行，并在 {@code light_ai_schema_history}
 * 保存不可变版本与 SHA-256 校验值。PostgreSQL 的 DDL 与历史记录在同一事务提交；
 * MySQL DDL 会隐式提交，因此迁移脚本必须可重复执行，只有全部语句成功后才写成功历史。
 */
public class DefaultSchemaMigrator implements SchemaMigrator {

    static final int LATEST_VERSION = 5;
    private static final long POSTGRES_LOCK_ID = 738_120_426L;
    private static final String MYSQL_LOCK_NAME = "light_ai_schema_migration";
    private static final Migration POSTGRES_BASELINE = new Migration(
            1, "baseline", "db/migration/postgres/V1__baseline.sql");
    private static final Migration MYSQL_BASELINE = new Migration(
            1, "baseline", "db/migration/mysql/V1__baseline.sql");
    private static final Migration POSTGRES_APPLICATION_FOUNDATION = new Migration(
            2, "enterprise_application_foundation",
            "db/migration/postgres/V2__enterprise_application_foundation.sql");
    private static final Migration MYSQL_APPLICATION_FOUNDATION = new Migration(
            2, "enterprise_application_foundation",
            "db/migration/mysql/V2__enterprise_application_foundation.sql");
    private static final Migration POSTGRES_APPLICATION_KEY_MODEL_SCOPE = new Migration(
            3, "application_key_model_scope",
            "db/migration/postgres/V3__application_key_model_scope.sql");
    private static final Migration MYSQL_APPLICATION_KEY_MODEL_SCOPE = new Migration(
            3, "application_key_model_scope",
            "db/migration/mysql/V3__application_key_model_scope.sql");
    private static final Migration POSTGRES_RESOURCE_DOMAIN_CHANNELS = new Migration(
            4, "resource_domain_channels_and_upstream_models",
            "db/migration/postgres/V4__resource_domain_channels_and_upstream_models.sql");
    private static final Migration MYSQL_RESOURCE_DOMAIN_CHANNELS = new Migration(
            4, "resource_domain_channels_and_upstream_models",
            "db/migration/mysql/V4__resource_domain_channels_and_upstream_models.sql");
    private static final Migration POSTGRES_VIRTUAL_MODEL_ROUTES_AND_SYNC = new Migration(
            5, "virtual_model_routes_and_sync",
            "db/migration/postgres/V5__virtual_model_routes_and_sync.sql");
    private static final Migration MYSQL_VIRTUAL_MODEL_ROUTES_AND_SYNC = new Migration(
            5, "virtual_model_routes_and_sync",
            "db/migration/mysql/V5__virtual_model_routes_and_sync.sql");

    private final DataSource dataSource;

    public DefaultSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseDialect dialect = DialectResolver.resolve(connection);
            boolean h2 = isH2(connection);
            boolean originalAutoCommit = connection.getAutoCommit();
            if (dialect.databaseType() == DatabaseType.POSTGRESQL) {
                connection.setAutoCommit(false);
            }
            acquireLock(connection, dialect, h2);
            try {
                ensureHistoryTable(connection, dialect);
                if (dialect.databaseType() == DatabaseType.MYSQL) {
                    apply(connection, MYSQL_BASELINE, h2);
                    apply(connection, MYSQL_APPLICATION_FOUNDATION, h2);
                    apply(connection, MYSQL_APPLICATION_KEY_MODEL_SCOPE, h2);
                    apply(connection, MYSQL_RESOURCE_DOMAIN_CHANNELS, h2);
                    apply(connection, MYSQL_VIRTUAL_MODEL_ROUTES_AND_SYNC, h2);
                } else {
                    apply(connection, POSTGRES_BASELINE, h2);
                    apply(connection, POSTGRES_APPLICATION_FOUNDATION, h2);
                    apply(connection, POSTGRES_APPLICATION_KEY_MODEL_SCOPE, h2);
                    apply(connection, POSTGRES_RESOURCE_DOMAIN_CHANNELS, h2);
                    apply(connection, POSTGRES_VIRTUAL_MODEL_ROUTES_AND_SYNC, h2);
                }
                if (dialect.databaseType() == DatabaseType.POSTGRESQL) {
                    connection.commit();
                }
            } catch (Exception e) {
                rollbackQuietly(connection, dialect);
                throw e;
            } finally {
                releaseLock(connection, dialect, h2);
                if (dialect.databaseType() == DatabaseType.POSTGRESQL) {
                    connection.setAutoCommit(originalAutoCommit);
                }
            }
        } catch (SchemaNotReadyException e) {
            throw e;
        } catch (SQLException e) {
            throw new SchemaNotReadyException("数据库结构迁移执行失败: " + safeMessage(e));
        } catch (Exception e) {
            throw new SchemaNotReadyException("数据库结构迁移脚本加载或解析失败: " + safeMessage(e));
        }
    }

    private void apply(Connection connection, Migration migration, boolean h2) throws Exception {
        String source = loadScript(migration.path());
        String checksum = checksum(source);
        AppliedMigration applied = findApplied(connection, migration.version());
        if (applied != null) {
            if (!applied.success() || !checksum.equals(applied.checksum())) {
                throw new SchemaNotReadyException(
                        "数据库迁移 V" + migration.version() + " 校验值不一致或历史状态失败，拒绝启动");
            }
            return;
        }

        String executable = h2 ? adaptMySqlScriptForH2(source) : source;
        for (String sql : splitStatements(executable)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
        insertApplied(connection, migration, checksum);
    }

    private static void ensureHistoryTable(Connection connection, DatabaseDialect dialect)
            throws SQLException {
        String sql;
        if (dialect.databaseType() == DatabaseType.MYSQL) {
            sql = """
                    CREATE TABLE IF NOT EXISTS light_ai_schema_history (
                        version INT PRIMARY KEY,
                        description VARCHAR(200) NOT NULL,
                        checksum CHAR(64) NOT NULL,
                        installed_on TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                        success BOOLEAN NOT NULL
                    )
                    """;
        } else {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS light_ai");
            }
            sql = """
                    CREATE TABLE IF NOT EXISTS light_ai.light_ai_schema_history (
                        version INTEGER PRIMARY KEY,
                        description VARCHAR(200) NOT NULL,
                        checksum CHAR(64) NOT NULL,
                        installed_on TIMESTAMPTZ NOT NULL DEFAULT now(),
                        success BOOLEAN NOT NULL
                    )
                    """;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static AppliedMigration findApplied(Connection connection, int version)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum, success FROM " + historyTable(connection) + " WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new AppliedMigration(resultSet.getString(1), resultSet.getBoolean(2));
            }
        }
    }

    private static void insertApplied(Connection connection, Migration migration, String checksum)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + historyTable(connection)
                        + " (version, description, checksum, success) VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, checksum);
            statement.setBoolean(4, true);
            statement.executeUpdate();
        }
    }

    private static String historyTable(Connection connection) throws SQLException {
        return DialectResolver.resolve(connection).databaseType() == DatabaseType.MYSQL
                ? "light_ai_schema_history" : "light_ai.light_ai_schema_history";
    }

    private static void acquireLock(Connection connection, DatabaseDialect dialect, boolean h2)
            throws SQLException {
        if (h2) {
            return;
        }
        String sql = dialect.databaseType() == DatabaseType.MYSQL
                ? "SELECT GET_LOCK('" + MYSQL_LOCK_NAME + "', 60)"
                : "SELECT pg_advisory_lock(" + POSTGRES_LOCK_ID + ")";
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            if (dialect.databaseType() == DatabaseType.MYSQL
                    && (!resultSet.next() || resultSet.getInt(1) != 1)) {
                throw new SchemaNotReadyException("等待数据库迁移全局锁超时");
            }
        }
    }

    private static void releaseLock(Connection connection, DatabaseDialect dialect, boolean h2) {
        if (h2) {
            return;
        }
        String sql = dialect.databaseType() == DatabaseType.MYSQL
                ? "SELECT RELEASE_LOCK('" + MYSQL_LOCK_NAME + "')"
                : "SELECT pg_advisory_unlock(" + POSTGRES_LOCK_ID + ")";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ignored) {
            // 连接关闭时数据库会自动释放会话锁；保留原始迁移异常。
        }
    }

    private static void rollbackQuietly(Connection connection, DatabaseDialect dialect) {
        if (dialect.databaseType() != DatabaseType.POSTGRESQL) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始迁移异常。
        }
    }

    static String loadScript(String path) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = DefaultSchemaMigrator.class.getClassLoader();
        }
        try (InputStream input = classLoader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("未找到迁移脚本资源: " + path);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append('\n');
                }
                return result.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取迁移脚本失败: " + path, e);
        }
    }

    static String adaptMySqlScriptForH2(String script) {
        return script.replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
    }

    private static boolean isH2(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        return productName != null && productName.toLowerCase().contains("h2");
    }

    static String checksum(String script) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算迁移校验值", e);
        }
    }

    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;
        for (int i = 0; i < script.length(); i++) {
            char currentChar = script.charAt(i);
            if (inLineComment) {
                if (currentChar == '\n' || currentChar == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (!inSingleQuote && currentChar == '-' && i + 1 < script.length()
                    && script.charAt(i + 1) == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (currentChar == '\'') {
                if (inSingleQuote && i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                current.append(currentChar);
                continue;
            }
            if (currentChar == ';' && !inSingleQuote) {
                String sql = current.toString().trim();
                if (!sql.isEmpty()) {
                    statements.add(sql);
                }
                current.setLength(0);
                continue;
            }
            current.append(currentChar);
        }
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            statements.add(remaining);
        }
        return statements;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private record Migration(int version, String description, String path) {
    }

    private record AppliedMigration(String checksum, boolean success) {
    }
}
