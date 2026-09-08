package com.lightai.storage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcApplicationRepositoryTest {

    @Test
    void createsListsUpdatesAndScopesEnterpriseApplications() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:application_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
        JdbcApplicationRepository repository = new JdbcApplicationRepository();
        UUID applicationId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            repository.insert(connection, new ApplicationRecord(
                    applicationId, "order-assistant", "订单助手", "供应链", "owner-1", "张三",
                    "PROD", "订单场景 AI 接入", "ACTIVE", null, 1L, null, null));
            repository.insertOwner(connection, applicationId, "owner-1", "张三");
            repository.insertQuota(connection, new ApplicationQuotaRecord(
                    UUID.randomUUID(), applicationId, 1_000_000L, new BigDecimal("500.00000000"),
                    "CNY", 120, 100_000L, "MONTH", null, null, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, 1L, null, null));

            assertThat(repository.existsByCode(connection, "order-assistant")).isTrue();
            assertThat(repository.findCodesForSubject(connection, "owner-1"))
                    .containsExactly("order-assistant");
            assertThat(repository.list(connection,
                    new JdbcApplicationRepository.Filter("订单", "ACTIVE", "PROD", null, List.of()),
                    "updated_at desc", 20, 0)).hasSize(1);
            assertThat(repository.findQuota(connection, applicationId).orElseThrow().rpm()).isEqualTo(120);

            ApplicationRecord current = repository.findById(connection, applicationId).orElseThrow();
            ApplicationRecord updated = repository.update(connection, new ApplicationRecord(
                    current.id(), current.code(), "订单智能助手", current.department(), current.ownerId(),
                    current.ownerName(), current.environment(), current.description(), current.status(),
                    current.lastCalledAt(), current.version(), current.createdAt(), current.updatedAt()), 1L);
            assertThat(updated.name()).isEqualTo("订单智能助手");
            assertThat(updated.version()).isEqualTo(2L);

            assertThatThrownBy(() -> repository.updateStatus(
                    connection, applicationId, "DISABLED", 1L))
                    .isInstanceOf(JdbcApplicationRepository.OptimisticLockException.class);
            assertThat(repository.updateStatus(connection, applicationId, "DISABLED", 2L).status())
                    .isEqualTo("DISABLED");
        }
    }
}
