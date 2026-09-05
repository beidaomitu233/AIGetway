package com.lightai.storage.schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
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
        Set<String> existing;
        try (Connection connection = dataSource.getConnection()) {
            existing = readExistingTables(connection);
        } catch (SQLException e) {
            throw new SchemaNotReadyException("数据库结构核对失败：" + safeMessage(e));
        }
        var missing = ExpectedSchema.missingTables(existing);
        if (!missing.isEmpty()) {
            throw new SchemaNotReadyException(
                    "schema " + schemaName + " 缺少 " + missing.size() + " 张产品表，阻止就绪", missing);
        }
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
        try (ResultSet rs = metaData.getTables(null, schemaName, "%", new String[] {"TABLE"})) {
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
