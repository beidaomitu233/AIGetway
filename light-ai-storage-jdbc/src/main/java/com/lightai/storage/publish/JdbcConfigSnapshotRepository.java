package com.lightai.storage.publish;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * config_snapshot JDBC 实现（DATABASE_PLAN §32）。
 * 唯一 ACTIVE 由部分唯一索引兜底；激活事务内先将旧 ACTIVE 改 SUPERSEDED，
 * 再激活目标并更新 runtime_config.current_snapshot_no（4.5.2.4，由服务编排）。
 */
public final class JdbcConfigSnapshotRepository extends AbstractJdbcRepository implements ConfigSnapshotRepository {

    private static final String COLUMNS =
            "snapshot_no, schema_version, status, content, content_checksum, content_summary, "
                    + "activated_at, created_by, created_at, updated_at";

    public JdbcConfigSnapshotRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcConfigSnapshotRepository() {
        super();
    }

    /** 下一快照号：单调递增、允许间隙（ABORTED 编号不回收）。 */
    @Override
    public long nextSnapshotNo(Connection connection) {
        String sql = "SELECT COALESCE(max(snapshot_no), -1) + 1 FROM " + qualify(connection, "config_snapshot");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw translate("快照号分配失败", e);
        }
    }

    @Override
    public void insert(Connection connection, ConfigSnapshotRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "config_snapshot") + " (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, " + d.jsonPlaceholder() + ", ?, " + d.jsonPlaceholder() + ", ?, ?, " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, record.snapshotNo());
            statement.setInt(2, record.schemaVersion());
            statement.setString(3, record.status());
            d.bindJson(statement, 4, record.contentJson());
            statement.setString(5, record.contentChecksum());
            d.bindJson(statement, 6, record.contentSummaryJson());
            statement.setObject(7, record.activatedAt());
            statement.setString(8, record.createdBy());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("快照写入失败", e);
        }
    }

    @Override
    public Optional<ConfigSnapshotRecord> find(Connection connection, long snapshotNo) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "config_snapshot")
                + " WHERE snapshot_no = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, snapshotNo);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(d, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("快照读取失败", e);
        }
    }

    @Override
    public Optional<ConfigSnapshotRecord> findActive(Connection connection) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "config_snapshot")
                + " WHERE status = 'ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(mapRow(d, rs)) : Optional.empty();
        } catch (SQLException e) {
            throw translate("活动快照读取失败", e);
        }
    }

    /** 原子激活：旧 ACTIVE → SUPERSEDED，目标 → ACTIVE；必须位于发布激活事务内。 */
    @Override
    public void activate(Connection connection, long targetSnapshotNo) {
        DatabaseDialect d = dialect(connection);
        String supersede = "UPDATE " + qualify(connection, "config_snapshot")
                + " SET status = 'SUPERSEDED', updated_at = " + d.nowFunction() + " WHERE status = 'ACTIVE'";
        String activate = "UPDATE " + qualify(connection, "config_snapshot")
                + " SET status = 'ACTIVE', activated_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE snapshot_no = ? AND status = 'CREATED'";
        String pointer = "UPDATE " + qualify(connection, "runtime_config")
                + " SET current_snapshot_no = ?, published_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE singleton_key = 1";
        try (PreparedStatement s1 = connection.prepareStatement(supersede);
             PreparedStatement s2 = connection.prepareStatement(activate);
             PreparedStatement s3 = connection.prepareStatement(pointer)) {
            s1.executeUpdate();
            s2.setLong(1, targetSnapshotNo);
            if (s2.executeUpdate() != 1) {
                throw new IllegalStateException("目标快照不存在或状态非 CREATED，激活被拒绝");
            }
            s3.setLong(1, targetSnapshotNo);
            s3.executeUpdate();
        } catch (SQLException e) {
            throw translate("快照激活失败", e);
        }
    }

    /** 状态迁移：目标快照 CREATED→ABORTED 等；状态与行数不匹配时抛出。 */
    @Override
    public void transitionStatus(Connection connection, long snapshotNo, String newStatus) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "config_snapshot") + " SET status = ?, updated_at = " + d.nowFunction()
                + " WHERE snapshot_no = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newStatus);
            statement.setLong(2, snapshotNo);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("快照状态迁移失败", e);
        }
    }

    private ConfigSnapshotRecord mapRow(DatabaseDialect d, ResultSet rs) throws SQLException {
        return new ConfigSnapshotRecord(
                rs.getLong("snapshot_no"),
                rs.getInt("schema_version"),
                rs.getString("status"),
                d.readJson(rs, "content"),
                rs.getString("content_checksum"),
                d.readJson(rs, "content_summary"),
                d.readOffsetDateTime(rs, "activated_at"),
                rs.getString("created_by"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}

