package com.lightai.storage.channel;

import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcChannelRepositoryTest {

    @Test
    void roundTripsJsonHeadersOnStandaloneH2Schema() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:provider_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        new DefaultSchemaMigrator(dataSource).migrate();

        UUID id = UUID.randomUUID();
        JdbcChannelRepository repository = new JdbcChannelRepository();
        ChannelRecord record = new ChannelRecord(id, UUID.randomUUID(), "OPENAI", "Stub",
                "http://127.0.0.1:19099", null, 1_000, 10_000, 10_000,
                Map.of("X-Test", "value"), 10, 1, ChannelRecord.STATUS_ACTIVE,
                ChannelRecord.HEALTH_UNKNOWN, 1L, OffsetDateTime.now(), OffsetDateTime.now());

        try (Connection connection = dataSource.getConnection()) {
            repository.insert(connection, record);
            ChannelRecord loaded = repository.findLiveById(connection, id).orElseThrow();
            assertThat(loaded.defaultHeaders()).containsEntry("X-Test", "value");
        }
    }
}