package com.lightai.storage.schema;

import com.lightai.storage.dialect.DatabaseType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从已发布基线迁移提取表、列和命名索引契约，供 VALIDATE 做结构门禁。 */
final class SchemaContract {

    private static final Pattern TABLE = Pattern.compile(
            "(?ims)^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(?:light_ai\\.)?([a-z_]+)"
                    + "\\s*\\((.*?)^\\s*\\)\\s*(?:ENGINE[^;]*)?;");
    private static final Pattern INDEX = Pattern.compile(
            "(?is)CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)"
                    + "\\s+ON\\s+(?:light_ai\\.)?([a-z_]+)");
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
                "db/migration/" + folder + "/V2__enterprise_application_foundation.sql");
        return parse(scripts);
    }

    static Definition parse(String script) {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        Map<String, Set<String>> indexes = new LinkedHashMap<>();
        Matcher tableMatcher = TABLE.matcher(script);
        while (tableMatcher.find()) {
            String table = normalize(tableMatcher.group(1));
            Set<String> tableColumns = new LinkedHashSet<>();
            String body = tableMatcher.group(2);
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
            columns.put(table, Set.copyOf(tableColumns));
            Set<String> tableIndexes = new LinkedHashSet<>();
            Matcher inline = INLINE_INDEX.matcher(body);
            while (inline.find()) {
                tableIndexes.add(normalize(inline.group(1)));
            }
            indexes.put(table, tableIndexes);
        }
        Matcher indexMatcher = INDEX.matcher(script);
        while (indexMatcher.find()) {
            indexes.computeIfAbsent(normalize(indexMatcher.group(2)), ignored -> new LinkedHashSet<>())
                    .add(normalize(indexMatcher.group(1)));
        }
        Map<String, Set<String>> immutableIndexes = new LinkedHashMap<>();
        indexes.forEach((table, names) -> immutableIndexes.put(table, Set.copyOf(names)));
        return new Definition(Map.copyOf(columns), Map.copyOf(immutableIndexes));
    }

    private static String normalize(String value) {
        return value.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    record Definition(Map<String, Set<String>> columns, Map<String, Set<String>> indexes) {
    }
}
