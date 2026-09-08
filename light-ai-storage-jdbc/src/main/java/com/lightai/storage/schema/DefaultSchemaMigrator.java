package com.lightai.storage.schema;

import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
import com.lightai.storage.dialect.DialectResolver;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * 默认数据库迁移执行器（BE-003 / CR-015）：
 * 根据当前连接方言自动选择并执行版本化迁移脚本（PostgreSQL / MySQL 5.7 / MySQL 8.0）。
 * 初始化产品所需的全部 39 张表结构与初始种子数据。
 */
public class DefaultSchemaMigrator implements SchemaMigrator {

    private static final String POSTGRES_SCRIPT = "schema/postgres/light_ai_schema.sql";
    private static final String MYSQL_SCRIPT = "schema/mysql/light_ai_schema.sql";

    private final DataSource dataSource;

    public DefaultSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseDialect dialect = DialectResolver.resolve(connection);
            String scriptPath = dialect.databaseType() == DatabaseType.MYSQL ? MYSQL_SCRIPT : POSTGRES_SCRIPT;
            String script = loadScript(scriptPath);
            if (isH2(connection)) {
                script = adaptMySqlScriptForH2(script);
            }
            List<String> statements = splitStatements(script);
            for (String sql : statements) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(sql);
                }
            }
        } catch (SQLException e) {
            throw new SchemaNotReadyException("数据库结构迁移执行失败: " + safeMessage(e));
        } catch (Exception e) {
            throw new SchemaNotReadyException("数据库结构迁移脚本加载或解析失败: " + e.getMessage());
        }
    }

    static String loadScript(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = DefaultSchemaMigrator.class.getClassLoader();
        }
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("未找到迁移脚本资源: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取迁移脚本失败: " + path, e);
        }
    }

    static String adaptMySqlScriptForH2(String script) {
        // H2 将通过 setString 写入 JSON 列的对象再次编码为 JSON 字符串；
        // 默认 Standalone 存储使用文本列保持与 MySQL JDBC JSON 读写语义一致。
        return script.replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
    }

    private static boolean isH2(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        return productName != null && productName.toLowerCase().contains("h2");
    }

    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (!inSingleQuote && c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                if (inSingleQuote && i + 1 < script.length() && script.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }
            if (c == ';' && !inSingleQuote) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            statements.add(remaining);
        }
        return statements;
    }

    private static String safeMessage(SQLException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
    }
}
