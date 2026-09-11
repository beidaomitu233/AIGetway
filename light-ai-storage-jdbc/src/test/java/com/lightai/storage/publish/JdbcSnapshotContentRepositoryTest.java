package com.lightai.storage.publish;

import com.lightai.storage.upstream.JdbcUpstreamModelRepository;
import com.lightai.storage.upstream.UpstreamModelRecord;
import com.lightai.storage.channel.JdbcChannelRepository;
import com.lightai.storage.channel.ChannelRecord;
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

        UUID channelId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            new JdbcChannelRepository().insert(connection, new ChannelRecord(channelId,
                    UUID.randomUUID(), "OPENAI", "Stub", "http://127.0.0.1:19099", null,
                    1_000, 10_000, 10_000, Map.of(), 10, 1,
                    ChannelRecord.STATUS_ACTIVE, ChannelRecord.HEALTH_UNKNOWN, 1L,
                    OffsetDateTime.now(), OffsetDateTime.now()));
            new JdbcUpstreamModelRepository().insert(connection, new UpstreamModelRecord(modelId,
                    channelId, "stub-model", "Stub Model", "CHAT_TEXT", "cl100k",
                    8_192L, 4_096L, true, true, true, true, true,
                    BigDecimal.ZERO, BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.ONE,
                    4, 128, BigDecimal.valueOf(0.7), BigDecimal.ONE, 1_024L, List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, 1_000_000, "USD", "ACTIVE",
                    null, null, 1L, OffsetDateTime.now(), OffsetDateTime.now()));

            Map<String, Object> content = new JdbcSnapshotContentRepository().assemble(connection, "Asia/Shanghai");
            @SuppressWarnings("unchecked")
            Map<String, Object> channel = (Map<String, Object>) ((List<?>) content.get("channels")).get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) ((List<?>) content.get("upstream_models")).get(0);
            assertThat(channel.get("status")).isEqualTo("ACTIVE");
            assertThat(model.get("support_stream")).isEqualTo(true);
            assertThat(model.get("status")).isEqualTo("ACTIVE");
        }
    }
}
