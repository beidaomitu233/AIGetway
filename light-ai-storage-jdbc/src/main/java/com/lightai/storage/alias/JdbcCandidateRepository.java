package com.lightai.storage.alias;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * route_candidate JDBC 仓储（DATABASE_PLAN §7）。
 * V5 起 alias_id 列更名为 virtual_model_id，(virtual_model_id, channel_id, upstream_model_id)
 * 三元组活行唯一由数据库唯一索引保证；status 列与 enabled 同语句双写。
 * 更新不换 model；重排为同事务批量 version 校验后统一写入。
 * 支持 PostgreSQL 与 MySQL 8.0 / H2(MySQL 模式) 双方言自适应。
 */
public class JdbcCandidateRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, virtual_model_id, upstream_model_id, channel_id, priority, weight, enabled, "
                    + "version, created_at, updated_at";

    public JdbcCandidateRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcCandidateRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcCandidateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, CandidateRecord record) {
        DatabaseDialect d = dialect(connection);
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at")) + ", status";
        int count = insertColumns.split(",").length;
        String sql = "INSERT INTO " + qualify(connection, "route_candidate") + " (" + insertColumns + ", created_at, updated_at) "
                + "VALUES (" + inPlaceholders(count) + ", " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, record.id());
            d.bindUuid(statement, 2, record.aliasId());
            d.bindUuid(statement, 3, record.upstreamModelId());
            d.bindUuid(statement, 4, record.channelId());
            statement.setInt(5, record.priority());
            statement.setInt(6, record.weight());
            statement.setBoolean(7, record.enabled());
            statement.setLong(8, record.version());
            statement.setString(9, JdbcAliasRepository.statusOf(record.enabled()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("候选写入失败", e);
        }
    }

    public Optional<CandidateRecord> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "route_candidate")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("候选读取失败", e);
        }
    }

    public Optional<CandidateRecord> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "route_candidate")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("候选锁定失败", e);
        }
    }

    public boolean existsTriple(Connection connection, UUID aliasId, UUID upstreamModelId,
                                UUID channelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "route_candidate")
                + " WHERE virtual_model_id = ? AND upstream_model_id = ? AND channel_id = ?"
                + " AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, aliasId);
            d.bindUuid(statement, 2, upstreamModelId);
            d.bindUuid(statement, 3, channelId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("候选重复检查失败", e);
        }
    }

    public List<CandidateRecord> listLiveByAlias(Connection connection, UUID aliasId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "route_candidate")
                + " WHERE virtual_model_id = ? AND deleted_at IS NULL ORDER BY priority ASC, id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, aliasId);
            try (ResultSet rs = statement.executeQuery()) {
                List<CandidateRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("候选列表查询失败", e);
        }
    }

    public long countLiveByAlias(Connection connection, UUID aliasId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT count(*) FROM " + qualify(connection, "route_candidate")
                + " WHERE virtual_model_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, aliasId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("候选计数失败", e);
        }
    }

    /** 候选更新：仅 priority/weight/enabled；model 与 pool 不可变。 */
    public CandidateRecord update(Connection connection, CandidateRecord record) {
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s
                       SET priority = ?, weight = ?, enabled = ?, status = ?, version = version + 1, updated_at = %s
                     WHERE id = ? AND deleted_at IS NULL
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "route_candidate"), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, record.priority());
                statement.setInt(2, record.weight());
                statement.setBoolean(3, record.enabled());
                statement.setString(4, JdbcAliasRepository.statusOf(record.enabled()));
                d.bindUuid(statement, 5, record.id());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("候选更新未命中活行");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw translate("候选更新失败", e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "route_candidate")
                    + " SET priority = ?, weight = ?, enabled = ?, status = ?, version = version + 1, updated_at = " + d.nowFunction()
                    + " WHERE id = ? AND deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, record.priority());
                statement.setInt(2, record.weight());
                statement.setBoolean(3, record.enabled());
                statement.setString(4, JdbcAliasRepository.statusOf(record.enabled()));
                d.bindUuid(statement, 5, record.id());
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("候选更新未命中活行");
                }
                return findLiveById(connection, record.id())
                        .orElseThrow(() -> new IllegalStateException("候选更新后未找到活行"));
            } catch (SQLException e) {
                throw translate("候选更新失败", e);
            }
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "route_candidate")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("候选删除失败", e);
        }
    }

    /** 模型被引用数（BE-014 删除拦截）。 */
    public long countLiveByProviderModel(Connection connection, UUID upstreamModelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT count(*) FROM " + qualify(connection, "route_candidate")
                + " WHERE upstream_model_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, upstreamModelId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("候选引用计数失败", e);
        }
    }

    /** 按模型列出引用候选（影响分析）。 */
    public List<CandidateRecord> findLiveByProviderModel(Connection connection, UUID upstreamModelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "route_candidate")
                + " WHERE upstream_model_id = ? AND deleted_at IS NULL ORDER BY id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, upstreamModelId);
            try (ResultSet rs = statement.executeQuery()) {
                List<CandidateRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("候选引用查询失败", e);
        }
    }

    private CandidateRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new CandidateRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "virtual_model_id"),
                d.readUuid(rs, "upstream_model_id"),
                d.readUuid(rs, "channel_id"),
                rs.getInt("priority"),
                rs.getInt("weight"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}
