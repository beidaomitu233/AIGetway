package com.lightai.storage.trace;

import static org.assertj.core.api.Assertions.assertThat;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcUsageAggregateRepositoryTest {
    @Test void mysqlCompatibleInsertBindsEveryAggregateColumn() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:usage-aggregate;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
        OffsetDateTime start = OffsetDateTime.of(2026, 9, 8, 0, 0, 0, 0, ZoneOffset.UTC);
        var contribution = new JdbcUsageAggregateRepository.Contribution(
                "HOUR", start, start.plusHours(1), "dimension-key", "orders", null, null,
                null, null, null, null, "SUCCEEDED", null, "ACTUAL", false, "USD", Map.of(),
                1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0,
                10, 2, 12, 10, 2, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                25, 1, 5, 1, 0, Map.of("25", 1L), Map.of("5", 1L));
        var repository = new JdbcUsageAggregateRepository();
        try (Connection connection = dataSource.getConnection()) {
            repository.upsertContribution(connection, contribution);
            repository.upsertContribution(connection, contribution);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("select count(*), max(request_count) from usage_aggregate")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1);
                assertThat(rows.getLong(2)).isEqualTo(2);
            }
        }
    }
}