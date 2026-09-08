package com.lightai.storage.publish;

import com.lightai.storage.model.JdbcProviderModelRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.provider.ProviderRecord;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSnapshotContentRepositoryTest {

    @Test
    void normalizesH2TinyIntFlagsToBooleans() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:snapshot_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        new DefaultSchemaMigrator(dataSource).migrate();

        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            new JdbcProviderRepository().insert(connection, new ProviderRecord(providerId,
                    "Stub", "OPENAI", "http://127.0.0.1:19099", null, 1_000, 10_000,
                    Map.of(), true, 1L, OffsetDateTime.now(), OffsetDateTime.now()));
            new JdbcProviderModelRepository().insert(connection, new ProviderModelRecord(modelId,
                    providerId, "stub-model", "Stub Model", "CHAT_TEXT", "cl100k",
                    8_192L, 4_096L, true, true, true, true, true,
                    BigDecimal.ZERO, BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.ONE,
                    4, 128, BigDecimal.valueOf(0.7), BigDecimal.ONE, 1_024L, List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, 1_000_000, "USD", true,
                    null, null, 1L, OffsetDateTime.now(), OffsetDateTime.now()));

            Map<String, Object> content = new JdbcSnapshotContentRepository().assemble(connection, "Asia/Shanghai");
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) ((List<?>) content.get("providers")).get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) ((List<?>) content.get("provider_models")).get(0);
            assertThat(provider.get("enabled")).isEqualTo(true);
            assertThat(model.get("support_stream")).isEqualTo(true);
            assertThat(model.get("enabled")).isEqualTo(true);
        }
    }
}