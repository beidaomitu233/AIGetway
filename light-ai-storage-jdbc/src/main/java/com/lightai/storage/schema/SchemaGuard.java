package com.lightai.storage.schema;

import com.lightai.storage.dialect.DatabaseType;
import com.lightai.storage.dialect.DialectResolver;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * 启动结构装配契约（BE-003）。
 * VALIDATE：核对产品表是否齐备，缺表抛出 SchemaNotReadyException 阻止就绪；
 * MIGRATE：先执行注入的 SchemaMigrator（由数据库执行方提供），再执行同口径校验。
 * 迁移文件与版本管理归数据库执行方所有，本模块只定义边界。
 */
public class SchemaGuard {

    private final DataSource dataSource;
    private final String schemaName;

    public SchemaGuard(DataSource dataSource) {
        this(dataSource, ExpectedSchema.SCHEMA_NAME);
    }

    public SchemaGuard(DataSource dataSource, String schemaName) {
        this.dataSource = dataSource;
        this.schemaName = schemaName;
    }

    public void validate() {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> existing = readExistingTables(connection);
            var missing = ExpectedSchema.missingTables(existing);
            if (!missing.isEmpty()) {
                throw new SchemaNotReadyException(
                        "schema " + schemaName + " 缺少 " + missing.size() + " 张产品表，阻止就绪", missing);
            }
            if (connection.getMetaData() != null) {
                validateMigrationVersion(connection);
                validateColumnsAndIndexes(connection);
            }
        } catch (SchemaNotReadyException e) {
            throw e;
        } catch (SQLException e) {
            throw new SchemaNotReadyException("数据库结构核对失败：" + safeMessage(e));
        }
    }

    private void validateMigrationVersion(Connection connection) throws SQLException {
        DatabaseType type = DialectResolver.resolve(connection).databaseType();
        String history = type == DatabaseType.MYSQL
                ? "light_ai_schema_history" : schemaName + ".light_ai_schema_history";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT MAX(version) FROM " + history + " WHERE success = ?")) {
            statement.setBoolean(1, true);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != DefaultSchemaMigrator.LATEST_VERSION) {
                    throw new SchemaNotReadyException("数据库 schema 版本不匹配，期望 V"
                            + DefaultSchemaMigrator.LATEST_VERSION);
                }
            }
        } catch (SQLException e) {
            throw new SchemaNotReadyException("数据库缺少有效迁移历史或版本不可读：" + safeMessage(e));
        }
    }

    private void validateColumnsAndIndexes(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        DatabaseType type = DialectResolver.resolve(connection).databaseType();
        SchemaContract.Definition expected = SchemaContract.load(type);
        String productName = metadata.getDatabaseProductName();
        boolean mysql = productName != null && (productName.toLowerCase(Locale.ROOT).contains("mysql")
                || productName.toLowerCase(Locale.ROOT).contains("mariadb"));
        boolean h2 = productName != null && productName.toLowerCase(Locale.ROOT).contains("h2");
        String catalog = mysql ? connection.getCatalog() : null;
        String schema = (mysql || h2) ? null : schemaName;
        Map<String, Set<String>> actualColumns = readColumns(metadata, catalog, schema);
        for (Map.Entry<String, Set<String>> entry : expected.columns().entrySet()) {
            Set<String> actual = actualColumns.getOrDefault(entry.getKey(), Set.of());
            Set<String> missing = new HashSet<>(entry.getValue());
            missing.removeAll(actual);
            if (!missing.isEmpty()) {
                throw new SchemaNotReadyException("表 " + entry.getKey() + " 缺少列 "
                        + missing.stream().sorted().toList());
            }
        }
        for (Map.Entry<String, Set<String>> entry : expected.indexes().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Set<String> actual = readIndexes(metadata, catalog, schema, entry.getKey());
            Set<String> missing = new HashSet<>(entry.getValue());
            missing.removeAll(actual);
            if (!missing.isEmpty()) {
                throw new SchemaNotReadyException("表 " + entry.getKey() + " 缺少索引 "
                        + missing.stream().sorted().toList());
            }
        }
    }

    private static Map<String, Set<String>> readColumns(DatabaseMetaData metadata,
                                                         String catalog, String schema)
            throws SQLException {
        Map<String, Set<String>> result = new HashMap<>();
        try (ResultSet columns = metadata.getColumns(catalog, schema, "%", "%")) {
            while (columns.next()) {
                String table = columns.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                String column = columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                result.computeIfAbsent(table, ignored -> new HashSet<>()).add(column);
            }
        }
        return result;
    }

    private static Set<String> readIndexes(DatabaseMetaData metadata, String catalog,
                                           String schema, String table) throws SQLException {
        Set<String> result = new HashSet<>();
        try (ResultSet indexes = metadata.getIndexInfo(catalog, schema, table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null) {
                    result.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (result.isEmpty()) {
            try (ResultSet indexes = metadata.getIndexInfo(catalog, schema,
                    table.toUpperCase(Locale.ROOT), false, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    if (name != null) {
                        result.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return result;
    }

    public void migrateAndValidate(SchemaMigrator migrator) {
        if (migrator == null) {
            throw new SchemaNotReadyException(
                    "schema-mode=MIGRATE 需要数据库执行方提供 SchemaMigrator 实现，未装配迁移模块");
        }
        try {
            migrator.migrate();
        } catch (SchemaNotReadyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SchemaNotReadyException("迁移执行失败：" + safeMessage(e));
        }
        validate();
    }

    /** 表清单读取；独立方法便于契约测试覆写。 */
    Set<String> readExistingTables(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        Set<String> tables = new HashSet<>();
        String productName = metaData != null ? metaData.getDatabaseProductName() : null;
        boolean isMySql = productName != null && (productName.toLowerCase().contains("mysql") || productName.toLowerCase().contains("mariadb"));
        boolean isH2 = productName != null && productName.toLowerCase().contains("h2");
        String catalog = isMySql ? connection.getCatalog() : null;
        String schema = (isMySql || isH2) ? null : schemaName;
        try (ResultSet rs = metaData.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }
        return tables;
    }

    private static String safeMessage(SQLException e) {
        return safeMessage((Exception) e);
    }

    private static String safeMessage(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
    }
}
