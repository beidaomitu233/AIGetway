package com.lightai.storage.draft;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * config_draft_state JDBC 实现（DATABASE_PLAN §28）。
 * 单例行 singleton_key=1；lock 使用 FOR UPDATE，事务边界由业务服务定义。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public final class JdbcDraftStateRepository extends AbstractJdbcRepository implements DraftStateRepository, DraftPublishStateRepository {

    private static final String COLUMNS = "base_snapshot_no, draft_revision, status, publish_record_id, change_count";

    public JdbcDraftStateRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcDraftStateRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcDraftStateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<DraftStateSnapshot> find(Connection connection) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "config_draft_state") + " WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(mapRow(rs, d));
        } catch (SQLException e) {
            throw new IllegalStateException("草稿状态读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public DraftStateSnapshot lock(Connection connection) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "config_draft_state")
                + " WHERE singleton_key = 1 " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("config_draft_state 单例行缺失，数据库初始化不完整");
            }
            return mapRow(rs, d);
        } catch (SQLException e) {
            throw new IllegalStateException("草稿锁读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public DraftStateSnapshot bumpRevision(Connection connection, int changeCountDelta) {
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s
                       SET draft_revision = draft_revision + 1,
                           change_count = change_count + ?,
                           updated_at = %s
                     WHERE singleton_key = 1
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "config_draft_state"), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, changeCountDelta);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("config_draft_state 单例行缺失，草稿修订未递增");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("草稿修订更新失败：" + e.getClass().getSimpleName(), e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "config_draft_state")
                    + " SET draft_revision = draft_revision + 1, change_count = change_count + ?, updated_at = " + d.nowFunction()
                    + " WHERE singleton_key = 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, changeCountDelta);
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("config_draft_state 单例行缺失，草稿修订未递增");
                }
                return find(connection)
                        .orElseThrow(() -> new IllegalStateException("草稿修订更新后无法找到状态行"));
            } catch (SQLException e) {
                throw new IllegalStateException("草稿修订更新失败：" + e.getClass().getSimpleName(), e);
            }
        }
    }

    private DraftStateSnapshot mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        long revision = rs.getLong("draft_revision");
        UUID publishRecordId = d.readUuid(rs, "publish_record_id");
        return new DraftStateSnapshot(
                rs.getLong("base_snapshot_no"),
                revision,
                DraftStatus.valueOf(rs.getString("status")),
                publishRecordId,
                rs.getInt("change_count"));
    }

    // ---------- DraftPublishStateRepository（BE-040） ----------

    @Override
    public void markPublishing(Connection connection, UUID publishRecordId) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "config_draft_state")
                + " SET status = 'PUBLISHING', publish_record_id = ?, lock_acquired_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction() + " "
                + "WHERE singleton_key = 1 AND status = 'EDITABLE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, publishRecordId);
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException("草稿发布锁状态不满足 EDITABLE，无法开始发布");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("草稿发布锁更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void releaseToEditable(Connection connection) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "config_draft_state")
                + " SET status = 'EDITABLE', publish_record_id = NULL, lock_acquired_at = NULL, updated_at = " + d.nowFunction() + " "
                + "WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("草稿发布锁释放失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void activateBaseline(Connection connection, long targetSnapshotNo) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "config_draft_state")
                + " SET base_snapshot_no = ?, change_count = 0, status = 'EDITABLE', "
                + "publish_record_id = NULL, lock_acquired_at = NULL, updated_at = " + d.nowFunction() + " "
                + "WHERE singleton_key = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, targetSnapshotNo);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("草稿基线切换失败：" + e.getClass().getSimpleName(), e);
        }
    }
}
