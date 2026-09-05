package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SchemaGuardTest {

    @Test
    void expectedSchemaContainsThirtyNineTables() {
        assertThat(ExpectedSchema.TABLES).hasSize(39);
        assertThat(ExpectedSchema.TABLES).contains("audit_log", "config_draft_state", "draft_change");
    }

    @Test
    void validatePassesWhenAllProductTablesExist() {
        SchemaGuard guard = new SchemaGuard(emptyDataSource()) {
            @Override
            Set<String> readExistingTables(Connection connection) {
                return ExpectedSchema.TABLES;
            }
        };
        guard.validate();
    }

    @Test
    void validateFailsListingMissingTables() {
        Set<String> existing = new HashSet<>(ExpectedSchema.TABLES);
        existing.remove("audit_log");
        existing.remove("draft_change");
        SchemaGuard guard = new SchemaGuard(emptyDataSource()) {
            @Override
            Set<String> readExistingTables(Connection connection) {
                return existing;
            }
        };
        assertThatThrownBy(guard::validate)
                .isInstanceOf(SchemaNotReadyException.class)
                .hasMessageContaining("缺少 2 张产品表")
                .extracting("missingTables")
                .isEqualTo(List.of("audit_log", "draft_change"));
    }

    @Test
    void migrateModeWithoutMigratorFailsWithClearCause() {
        SchemaGuard guard = new SchemaGuard(emptyDataSource());
        assertThatThrownBy(() -> guard.migrateAndValidate(null))
                .isInstanceOf(SchemaNotReadyException.class)
                .hasMessageContaining("SchemaMigrator");
    }

    @Test
    void migrateRunsMigratorThenValidates() {
        SchemaGuard guard = new SchemaGuard(emptyDataSource()) {
            @Override
            Set<String> readExistingTables(Connection connection) {
                return ExpectedSchema.TABLES;
            }
        };
        boolean[] migrated = {false};
        guard.migrateAndValidate(() -> migrated[0] = true);
        assertThat(migrated[0]).isTrue();
    }

    @Test
    void migratorFailureIsTranslatedToNotReady() {
        SchemaGuard guard = new SchemaGuard(emptyDataSource());
        assertThatThrownBy(() -> guard.migrateAndValidate(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(SchemaNotReadyException.class)
                .hasMessageContaining("迁移执行失败");
    }

    private DataSource emptyDataSource() {
        Connection noOpConnection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType.isPrimitive() && returnType != void.class) {
                        return 0;
                    }
                    return null;
                });
        return (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return noOpConnection;
                    }
                    return null;
                });
    }
}
