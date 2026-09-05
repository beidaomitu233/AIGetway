package com.lightai.storage.draft;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * config_draft_state JDBC 实现（DATABASE_PLAN §28）。
 * 单例行 singleton_key=1；lock 使用 FOR UPDATE，事务边界由业务服务定义。
 */
public final class JdbcDraftStateRepository implements DraftStateRepository {

    private static final String COLUMNS = "base_snapshot_no, draft_revision, status, publish_record_id, change_count";

    private final String schemaName;

    public JdbcDraftStateRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcDraftStateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<DraftStateSnapshot> find(Connection connection) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(mapRow(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("草稿状态读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public DraftStateSnapshot lock(Connection connection) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE singleton_key = 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("config_draft_state 单例行缺失，数据库初始化不完整");
            }
            return mapRow(rs);
        } catch (SQLException e) {
            throw new IllegalStateException("草稿锁读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public DraftStateSnapshot bumpRevision(Connection connection, int changeCountDelta) {
        String sql = """
                UPDATE %s
                   SET draft_revision = draft_revision + 1,
                       change_count = change_count + ?,
                       updated_at = now()
                 WHERE singleton_key = 1
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, changeCountDelta);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("config_draft_state 单例行缺失，草稿修订未递增");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("草稿修订更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private DraftStateSnapshot mapRow(ResultSet rs) throws SQLException {
        long revision = rs.getLong("draft_revision");
        UUID publishRecordId = rs.getObject("publish_record_id", UUID.class);
        return new DraftStateSnapshot(
                rs.getLong("base_snapshot_no"),
                revision,
                DraftStatus.valueOf(rs.getString("status")),
                publishRecordId,
                rs.getInt("change_count"));
    }

    private String qualified() {
        return schemaName + ".config_draft_state";
    }
}
