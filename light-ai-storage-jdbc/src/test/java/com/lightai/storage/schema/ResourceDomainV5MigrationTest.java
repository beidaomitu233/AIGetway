package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.storage.alias.AliasRecord;
import com.lightai.storage.alias.CandidateRecord;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.alias.JdbcCandidateRepository;
import com.lightai.storage.batch.BatchItemRecord;
import com.lightai.storage.batch.BatchJobRecord;
import com.lightai.storage.batch.JdbcBatchCheckRepository;
import com.lightai.storage.channel.ChannelRecord;
import com.lightai.storage.channel.JdbcChannelRepository;
import com.lightai.storage.upstream.JdbcUpstreamModelRepository;
import com.lightai.storage.upstream.UpstreamModelRecord;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * V5 资源域契约收口迁移回归（DB-P21）：
 * 虚拟模型表/列更名后仓储读写、业务码与三元组活行唯一、status 双写、
 * 批量检测列与仓储契约对齐（BE-P21-006）、模型同步任务存储。
 */
class ResourceDomainV5MigrationTest {

    @Test
    void virtualModelStoresCodeAndKeepsAliasAccessors() throws Exception {
        JdbcDataSource dataSource = dataSource();
        new DefaultSchemaMigrator(dataSource).migrate();
        JdbcAliasRepository repository = new JdbcAliasRepository();
        UUID id = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            repository.insert(connection, new AliasRecord(id, "gpt-4o", "GPT-4o", null,
                    "PRIORITY_WEIGHTED", true, 1L, OffsetDateTime.now(), OffsetDateTime.now()));

            AliasRecord loaded = repository.findLiveById(connection, id).orElseThrow();
            assertThat(loaded.alias()).isEqualTo("gpt-4o");
            assertThat(loaded.displayName()).isEqualTo("GPT-4o");
            assertThat(loaded.enabled()).isTrue();
            assertThat(status(connection, id)).isEqualTo("ACTIVE");

            repository.update(connection, new AliasRecord(id, "gpt-4o", "GPT-4o 更名", null,
                    "PRIORITY_WEIGHTED", false, 2L, OffsetDateTime.now(), OffsetDateTime.now()));
            assertThat(status(connection, id)).isEqualTo("DISABLED");
        }
    }

    @Test
    void virtualModelCodeIsUniqueAmongLiveRows() throws Exception {
        JdbcDataSource dataSource = dataSource();
        new DefaultSchemaMigrator(dataSource).migrate();
        JdbcAliasRepository repository = new JdbcAliasRepository();

        try (Connection connection = dataSource.getConnection()) {
            repository.insert(connection, new AliasRecord(UUID.randomUUID(), "claude", "Claude", null,
                    "PRIORITY_WEIGHTED", true, 1L, OffsetDateTime.now(), OffsetDateTime.now()));
            assertThatThrownBy(() -> repository.insert(connection, new AliasRecord(UUID.randomUUID(),
                    "claude", "Claude 重复", null, "PRIORITY_WEIGHTED", true, 1L,
                    OffsetDateTime.now(), OffsetDateTime.now())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void routeCandidateTripleIsUniqueAmongLiveRows() throws Exception {
        JdbcDataSource dataSource = dataSource();
        new DefaultSchemaMigrator(dataSource).migrate();
        JdbcCandidateRepository repository = new JdbcCandidateRepository();
        UUID aliasId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            repository.insert(connection, new CandidateRecord(UUID.randomUUID(), aliasId, modelId,
                    channelId, 10, 1, true, 1L, OffsetDateTime.now(), OffsetDateTime.now()));
            assertThatThrownBy(() -> repository.insert(connection, new CandidateRecord(UUID.randomUUID(),
                    aliasId, modelId, channelId, 20, 1, true, 1L,
                    OffsetDateTime.now(), OffsetDateTime.now())))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(repository.existsTriple(connection, aliasId, modelId, channelId)).isTrue();
            List<CandidateRecord> candidates = repository.listLiveByAlias(connection, aliasId);
            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).aliasId()).isEqualTo(aliasId);
            assertThat(candidates.get(0).channelId()).isEqualTo(channelId);
        }
    }

    @Test
    void upstreamModelIdentityIsUniqueAmongLiveRows() throws Exception {
        JdbcDataSource dataSource = dataSource();
        new DefaultSchemaMigrator(dataSource).migrate();
        JdbcUpstreamModelRepository repository = new JdbcUpstreamModelRepository();
        UUID channelId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            repository.insert(connection, model(channelId, "gpt-4o-mini", UUID.randomUUID()));
            assertThatThrownBy(() -> repository.insert(connection, model(channelId, "gpt-4o-mini", UUID.randomUUID())))
                    .isInstanceOf(IllegalStateException.class);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT locked_fields FROM upstream_model WHERE channel_id = ?")) {
                statement.setString(1, channelId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // MySQL/H2 方言 JSON 列无默认值（V4 口径），新行为 NULL 表示无锁定字段；
                    // PostgreSQL 行为 NOT NULL DEFAULT '[]'。
                    String lockedFields = rs.getString("locked_fields");
                    assertThat(lockedFields == null || lockedFields.equals("[]")).isTrue();
                }
            }
        }
    }

    @Test
    void channelCredentialNameIsUniqueAmongLiveRows() throws Exception {
        JdbcDataSource dataSource = dataSource();
        new DefaultSchemaMigrator(dataSource).migrate();
        UUID channelId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            insertCredential(connection, UUID.randomUUID(), channelId, "primary-key");
            assertThatThrownBy(() -> insertCredential(connection, UUID.randomUUID(), channelId, "primary-key"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void batchCheckRepositoryMatchesV5Columns() throws Exception {
        JdbcDataSource dataSource = dataSource();
        new DefaultSchemaMigrator(dataSource).migrate();
        JdbcBatchCheckRepository repository = new JdbcBatchCheckRepository();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            repository.insertJob(connection, new BatchJobRecord(jobId, "PENDING", "operator-1",
                    1, 0, 0, 0, 0, null, null, "{\"upstream_model_ids\":[\"x\"]}",
                    OffsetDateTime.now(), OffsetDateTime.now()));
            repository.insertItem(connection, new BatchItemRecord(itemId, jobId, modelId,
                    1, "PENDING", null, null, null, null));

            BatchJobRecord job = repository.findJobById(connection, jobId).orElseThrow();
            assertThat(job.operatorId()).isEqualTo("operator-1");
            assertThat(job.totalCount()).isEqualTo(1);
            assertThat(job.cancelledCount()).isZero();

            repository.updateItemStatus(connection, itemId, "SUCCEEDED", null, null);
            repository.refreshJobSummary(connection, jobId);

            BatchJobRecord done = repository.findJobById(connection, jobId).orElseThrow();
            assertThat(done.status()).isEqualTo("SUCCEEDED");
            assertThat(done.completedCount()).isEqualTo(1);
            assertThat(done.successCount()).isEqualTo(1);
            assertThat(done.failureCount()).isZero();
            assertThat(done.cancelledCount()).isZero();

            List<BatchItemRecord> items = repository.findItemsByJob(connection, jobId);
            assertThat(items).hasSize(1);
            assertThat(items.get(0).sequence()).isEqualTo(1);
            assertThat(items.get(0).status()).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void modelSyncJobSupportsIdempotentPreview() throws Exception {
        JdbcDataSource dataSource = dataSource();
        new DefaultSchemaMigrator(dataSource).migrate();
        com.lightai.storage.upstream.JdbcModelSyncJobRepository repository =
                new com.lightai.storage.upstream.JdbcModelSyncJobRepository();
        UUID jobId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            repository.insertJob(connection, new com.lightai.storage.upstream.ModelSyncJobRecord(
                    jobId, channelId, "PREVIEWED", "sync-key-1", "operator-1",
                    null, "{\"added\":1,\"changed\":0,\"retired\":0,\"conflict\":0}",
                    null, null, OffsetDateTime.now(), OffsetDateTime.now()));
            repository.insertItem(connection, new com.lightai.storage.upstream.ModelSyncItemRecord(
                    UUID.randomUUID(), jobId, null, "new-model", "NEW",
                    null, "{\"model_id\":\"new-model\"}", null));

            assertThat(repository.findByChannelAndIdempotencyKey(connection, channelId, "sync-key-1"))
                    .isPresent();
            assertThatThrownBy(() -> repository.insertJob(connection,
                    new com.lightai.storage.upstream.ModelSyncJobRecord(
                            UUID.randomUUID(), channelId, "PREVIEWED", "sync-key-1", "operator-1",
                            null, "{}", null, null, OffsetDateTime.now(), OffsetDateTime.now())))
                    .isInstanceOf(IllegalStateException.class);

            repository.transitionJob(connection, jobId, "COMMITTED", null);
            com.lightai.storage.upstream.ModelSyncJobRecord committed =
                    repository.findJobById(connection, jobId).orElseThrow();
            assertThat(committed.status()).isEqualTo("COMMITTED");
            assertThat(committed.committedAt()).isNotNull();

            assertThat(repository.findItemsByJob(connection, jobId)).hasSize(1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotKeepsVirtualModelJsonKeysAcrossRename() throws Exception {
        JdbcDataSource source = dataSource();
        new DefaultSchemaMigrator(source).migrate();
        UUID channelId = UUID.randomUUID();
        UUID aliasId = UUID.randomUUID();
        UUID upstreamModelId = UUID.randomUUID();

        try (Connection connection = source.getConnection()) {
            new JdbcChannelRepository().insert(connection, new ChannelRecord(channelId,
                    UUID.randomUUID(), "OPENAI", "Stub", "http://127.0.1:19099", null,
                    1_000, 10_000, 10_000, Map.of(), 10, 1,
                    ChannelRecord.STATUS_ACTIVE, ChannelRecord.HEALTH_UNKNOWN, 1L,
                    OffsetDateTime.now(), OffsetDateTime.now()));
            new JdbcUpstreamModelRepository().insert(connection, model(channelId, "stub-model", upstreamModelId));
            new JdbcAliasRepository().insert(connection, new AliasRecord(aliasId, "stub-alias", "Stub", null,
                    "PRIORITY_WEIGHTED", true, 1L, OffsetDateTime.now(), OffsetDateTime.now()));
            new JdbcCandidateRepository().insert(connection, new CandidateRecord(UUID.randomUUID(),
                    aliasId, upstreamModelId, channelId, 10, 1, true, 1L,
                    OffsetDateTime.now(), OffsetDateTime.now()));

            com.lightai.storage.publish.JdbcSnapshotContentRepository snapshots =
                    new com.lightai.storage.publish.JdbcSnapshotContentRepository();
            Map<String, Object> content = snapshots.assemble(connection, "Asia/Shanghai");

            List<Map<String, Object>> aliases = (List<Map<String, Object>>) content.get("model_aliases");
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) content.get("route_candidates");
            assertThat(aliases).hasSize(1);
            assertThat(aliases.get(0)).containsEntry("alias", "stub-alias").doesNotContainKey("code");
            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0)).containsEntry("alias_id", aliasId.toString())
                    .doesNotContainKey("virtual_model_id");
        }
    }

    private static UpstreamModelRecord model(UUID channelId, String modelId, UUID id) {
        return new UpstreamModelRecord(id, channelId, modelId, "Model", "CHAT_TEXT", "cl100k",
                8_192L, 4_096L, true, true, true, true, true,
                BigDecimal.ZERO, BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.ONE,
                4, 128, BigDecimal.valueOf(0.7), BigDecimal.ONE, 1_024L, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, 1_000_000, "USD", "ACTIVE",
                null, null, 1L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static void insertCredential(Connection connection, UUID id, UUID channelId, String name)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO channel_credential (id, created_at, updated_at, channel_id, name, masked_value) "
                        + "VALUES (?, NOW(), NOW(), ?, ?, 'sk-***')")) {
            statement.setString(1, id.toString());
            statement.setString(2, channelId.toString());
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private static String status(Connection connection, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM virtual_model WHERE id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString("status");
            }
        }
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v5domain_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }
}
