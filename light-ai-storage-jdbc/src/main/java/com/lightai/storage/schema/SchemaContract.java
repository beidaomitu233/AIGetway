package com.lightai.storage.schema;

import com.lightai.storage.dialect.DatabaseType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从已发布基线迁移提取表、列和命名索引契约，供 VALIDATE 做结构门禁。
 *
 * <p>契约按脚本出现顺序增量演进：CREATE TABLE 建立表，CREATE INDEX 记录命名索引，
 * ALTER TABLE ... RENAME COLUMN / DROP COLUMN / RENAME TO 与 DROP TABLE 依次修正，
 * 因此后续迁移对资源域的改名会被如实反映到期望结构上。
 */
final class SchemaContract {

    /** 单条语句级模式；按文本顺序匹配，各组互斥。 */
    private static final Pattern STATEMENT = Pattern.compile(
            "(?ims)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(?:light_ai\\.)?([a-z_]+)"
                    + "\\s*\\((.*?)^\\s*\\)\\s*(?:ENGINE[^;]*)?;"
                    + "|CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z0-9_]+)"
                    + "\\s+ON\\s+(?:light_ai\\.)?([a-z_]+)"
                    + "|ALTER\\s+TABLE\\s+(?:light_ai\\.)?([a-z_]+)\\s+RENAME\\s+COLUMN\\s+([a-z_]+)\\s+TO\\s+([a-z_]+)"
                    + "|ALTER\\s+TABLE\\s+(?:light_ai\\.)?([a-z_]+)\\s+DROP\\s+COLUMN\\s+([a-z_]+)"
                    + "|ALTER\\s+TABLE\\s+(?:light_ai\\.)?([a-z_]+)\\s+RENAME\\s+TO\\s+([a-z_]+)"
                    + "|DROP\\s+TABLE\\s+IF\\s+EXISTS\\s+(?:light_ai\\.)?([a-z_]+)");

    private static final Pattern INLINE_INDEX = Pattern.compile(
            "(?im)^\\s*KEY\\s+([a-z0-9_]+)\\s*\\(");

    private SchemaContract() {
    }

    static Definition load(DatabaseType type) {
        String folder = type == DatabaseType.MYSQL ? "mysql" : "postgres";
        String scripts = DefaultSchemaMigrator.loadScript(
                "db/migration/" + folder + "/V1__baseline.sql")
                + "\n"
                + DefaultSchemaMigrator.loadScript(
                "db/migration/" + folder + "/V2__enterprise_application_foundation.sql")
                + "\n"
                + DefaultSchemaMigrator.loadScript(
                "db/migration/" + folder + "/V3__application_key_model_scope.sql")
                + "\n"
                + DefaultSchemaMigrator.loadScript(
                "db/migration/" + folder + "/V4__resource_domain_channels_and_upstream_models.sql")
                + "\n"
                + DefaultSchemaMigrator.loadScript(
                "db/migration/" + folder + "/V5__virtual_model_routes_and_sync.sql")
                + "\n"
                + DefaultSchemaMigrator.loadScript(
                "db/migration/" + folder + "/V7__admission_ledger_observation_retention.sql")
                + "\n"
                + DefaultSchemaMigrator.loadScript(
                "db/migration/" + folder + "/V8__audit_and_instance_gate_indexes.sql");
        return parse(scripts);
    }

    static Definition parse(String script) {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        Map<String, Set<String>> indexes = new LinkedHashMap<>();
        Matcher matcher = STATEMENT.matcher(script);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                String table = normalize(matcher.group(1));
                String body = matcher.group(2);
                columns.put(table, parseColumns(body));
                indexes.put(table, parseInlineIndexes(body));
            } else if (matcher.group(3) != null) {
                indexes.computeIfAbsent(normalize(matcher.group(4)), ignored -> new LinkedHashSet<>())
                        .add(normalize(matcher.group(3)));
            } else if (matcher.group(5) != null) {
                renameColumn(columns, matcher.group(5), matcher.group(6), matcher.group(7));
            } else if (matcher.group(8) != null) {
                dropColumn(columns, matcher.group(8), matcher.group(9));
            } else if (matcher.group(10) != null) {
                renameTable(columns, indexes, matcher.group(10), matcher.group(11));
            } else if (matcher.group(12) != null) {
                String table = normalize(matcher.group(12));
                columns.remove(table);
                indexes.remove(table);
            }
        }
        Map<String, Set<String>> immutableIndexes = new LinkedHashMap<>();
        indexes.forEach((table, names) -> immutableIndexes.put(table, Set.copyOf(names)));
        return new Definition(Map.copyOf(columns), Map.copyOf(immutableIndexes));
    }

    private static Set<String> parseColumns(String body) {
        Set<String> tableColumns = new LinkedHashSet<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            String first = trimmed.split("\\s+", 2)[0].replace(",", "");
            String keyword = first.toUpperCase(Locale.ROOT);
            if (!Set.of("PRIMARY", "UNIQUE", "CONSTRAINT", "KEY", "FOREIGN", "CHECK")
                    .contains(keyword)) {
                tableColumns.add(normalize(first));
            }
        }
        return tableColumns;
    }

    private static Set<String> parseInlineIndexes(String body) {
        Set<String> tableIndexes = new LinkedHashSet<>();
        Matcher inline = INLINE_INDEX.matcher(body);
        while (inline.find()) {
            tableIndexes.add(normalize(inline.group(1)));
        }
        return tableIndexes;
    }

    private static void renameColumn(Map<String, Set<String>> columns, String table,
                                     String from, String to) {
        Set<String> current = columns.get(normalize(table));
        if (current == null) {
            return;
        }
        Set<String> updated = new LinkedHashSet<>(current);
        updated.remove(normalize(from));
        updated.add(normalize(to));
        columns.put(normalize(table), updated);
    }

    private static void dropColumn(Map<String, Set<String>> columns, String table, String column) {
        Set<String> current = columns.get(normalize(table));
        if (current == null) {
            return;
        }
        Set<String> updated = new LinkedHashSet<>(current);
        updated.remove(normalize(column));
        columns.put(normalize(table), updated);
    }

    private static void renameTable(Map<String, Set<String>> columns,
                                    Map<String, Set<String>> indexes,
                                    String from, String to) {
        Set<String> movedColumns = columns.remove(normalize(from));
        if (movedColumns != null) {
            columns.put(normalize(to), movedColumns);
        }
        Set<String> movedIndexes = indexes.remove(normalize(from));
        if (movedIndexes != null) {
            indexes.put(normalize(to), movedIndexes);
        }
    }

    private static String normalize(String value) {
        return value.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    record Definition(Map<String, Set<String>> columns, Map<String, Set<String>> indexes) {
    }
}
