package com.lightai.storage.publish;

import com.lightai.storage.upstream.JdbcUpstreamModelRepository;
import com.lightai.storage.upstream.UpstreamModelRecord;
import com.lightai.storage.channel.JdbcChannelRepository;
import com.lightai.storage.channel.JdbcChannelCredentialRepository;
import com.lightai.storage.channel.ChannelCredentialRecord;
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

    @Test
    void derivesCompatibilityViewsForValidationAndRuntime() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:snapshot_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        new DefaultSchemaMigrator(dataSource).migrate();

        // V4 种子目录：…0001=OPENAI、…0004=DEEPSEEK
        UUID openAiProviderId = UUID.fromString("11111111-1111-4111-8111-111111110001");
        UUID deepSeekProviderId = UUID.fromString("11111111-1111-4111-8111-111111110004");
        UUID activeChannelId = UUID.randomUUID();
        UUID disabledChannelId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        try (Connection connection = dataSource.getConnection()) {
            new JdbcChannelRepository().insert(connection, new ChannelRecord(activeChannelId,
                    openAiProviderId, "OPENAI", "Active", "http://127.0.0.1:19091", null,
                    1_000, 10_000, 10_000, Map.of("X-Env", "probe"), 10, 1,
                    ChannelRecord.STATUS_ACTIVE, ChannelRecord.HEALTH_UNKNOWN, 1L, now, now));
            new JdbcChannelRepository().insert(connection, new ChannelRecord(disabledChannelId,
                    deepSeekProviderId, "DEEPSEEK", "Disabled", "http://127.0.0.1:19092", null,
                    1_000, 10_000, 10_000, Map.of(), 10, 1,
                    ChannelRecord.STATUS_DISABLED, ChannelRecord.HEALTH_UNKNOWN, 1L, now, now));
            new JdbcChannelCredentialRepository().insert(connection, new ChannelCredentialRecord(
                    credentialId, activeChannelId, "key-1", "cipher".getBytes(), null, null,
                    "sk-***1234", 1L, null, 10, 1, 60L, 50000L, 4,
                    ChannelCredentialRecord.STATUS_ACTIVE, ChannelCredentialRecord.HEALTH_UNKNOWN,
                    1L, now, now));

            Map<String, Object> content = new JdbcSnapshotContentRepository().assemble(connection, "Asia/Shanghai");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> providers = (List<Map<String, Object>>) content.get("providers");
            assertThat(providers).hasSize(2);
            Map<String, Object> activeProvider = providers.stream()
                    .filter(row -> activeChannelId.toString().equals(row.get("id")))
                    .findFirst().orElseThrow();
            assertThat(activeProvider.get("type")).isEqualTo("OPENAI");
            assertThat(activeProvider.get("enabled")).isEqualTo(true);
            assertThat(activeProvider.get("connect_timeout_ms")).isEqualTo(1_000);
            assertThat(activeProvider.get("default_headers")).isEqualTo(Map.of("X-Env", "probe"));
            Map<String, Object> disabledProvider = providers.stream()
                    .filter(row -> disabledChannelId.toString().equals(row.get("id")))
                    .findFirst().orElseThrow();
            assertThat(disabledProvider.get("type")).isEqualTo("DEEPSEEK");
            assertThat(disabledProvider.get("enabled")).isEqualTo(false);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pools = (List<Map<String, Object>>) content.get("credential_pools");
            assertThat(pools).hasSize(1);
            Map<String, Object> pool = pools.get(0);
            assertThat(pool.get("id")).isEqualTo(activeChannelId.toString());
            assertThat(pool.get("channel_id")).isEqualTo(activeChannelId.toString());
            assertThat(pool.get("enabled")).isEqualTo(true);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> credentials = (List<Map<String, Object>>) content.get("credentials");
            assertThat(credentials).hasSize(1);
            assertThat(credentials.get(0).get("id")).isEqualTo(credentialId.toString());
            assertThat(credentials.get(0).get("enabled")).isEqualTo(true);
            assertThat(credentials.get(0)).doesNotContainKey("secret_ciphertext");
        }
    }
}
