package com.lightai.storage.alias;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * route_candidate JDBC 仓储（DATABASE_PLAN §7）。
 * (alias_id, provider_model_id, credential_pool_id) 活行唯一；
 * 更新不换 model；重排为同事务批量 version 校验后统一写入。
 */
public class JdbcCandidateRepository {

    private static final String COLUMNS =
            "id, alias_id, provider_model_id, credential_pool_id, priority, weight, enabled, "
                    + "version, created_at, updated_at";

    protected final String schemaName;

    public JdbcCandidateRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcCandidateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, CandidateRecord record) {
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        String sql = "INSERT INTO %s.route_candidate (%s, created_at, updated_at) VALUES (%s, now(), now())"
                .formatted(qualified(), insertColumns, placeholders(insertColumns));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, record.id());
            statement.setObject(2, record.aliasId());
            statement.setObject(3, record.providerModelId());
            statement.setObject(4, record.credentialPoolId());
            statement.setInt(5, record.priority());
            statement.setInt(6, record.weight());
            statement.setBoolean(7, record.enabled());
            statement.setLong(8, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("候选写入失败", e);
        }
    }

    public Optional<CandidateRecord> findLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("候选读取失败", e);
        }
    }

    public Optional<CandidateRecord> lockLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE id = ? AND deleted_at IS NULL FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("候选锁定失败", e);
        }
    }

    public boolean existsTriple(Connection connection, UUID aliasId, UUID providerModelId,
                                UUID credentialPoolId) {
        String sql = "SELECT 1 FROM " + qualified()
                + " WHERE alias_id = ? AND provider_model_id = ? AND credential_pool_id = ?"
                + " AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, aliasId);
            statement.setObject(2, providerModelId);
            statement.setObject(3, credentialPoolId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("候选重复检查失败", e);
        }
    }

    public List<CandidateRecord> listLiveByAlias(Connection connection, UUID aliasId) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE alias_id = ? AND deleted_at IS NULL ORDER BY priority ASC, id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, aliasId);
            try (ResultSet rs = statement.executeQuery()) {
                List<CandidateRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("候选列表查询失败", e);
        }
    }

    public long countLiveByAlias(Connection connection, UUID aliasId) {
        String sql = "SELECT count(*) FROM " + qualified()
                + " WHERE alias_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, aliasId);
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
        String sql = """
                UPDATE %s.route_candidate
                   SET priority = ?, weight = ?, enabled = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, record.priority());
            statement.setInt(2, record.weight());
            statement.setBoolean(3, record.enabled());
            statement.setObject(4, record.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("候选更新未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("候选更新失败", e);
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        String sql = "UPDATE %s.route_candidate SET deleted_at = now(), updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NULL".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("候选删除失败", e);
        }
    }

    /** 模型被引用数（BE-014 删除拦截）。 */
    public long countLiveByProviderModel(Connection connection, UUID providerModelId) {
        String sql = "SELECT count(*) FROM " + qualified()
                + " WHERE provider_model_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerModelId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("候选引用计数失败", e);
        }
    }

    /** 按模型列出引用候选（影响分析）。 */
    public List<CandidateRecord> findLiveByProviderModel(Connection connection, UUID providerModelId) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE provider_model_id = ? AND deleted_at IS NULL ORDER BY id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerModelId);
            try (ResultSet rs = statement.executeQuery()) {
                List<CandidateRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("候选引用查询失败", e);
        }
    }

    private CandidateRecord mapRow(ResultSet rs) throws SQLException {
        return new CandidateRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("alias_id", UUID.class),
                rs.getObject("provider_model_id", UUID.class),
                rs.getObject("credential_pool_id", UUID.class),
                rs.getInt("priority"),
                rs.getInt("weight"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String placeholders(String columns) {
        int count = columns.split(",").length;
        return "(" + "?,".repeat(count - 1) + "?)";
    }

    private String qualified() {
        return schemaName + ".route_candidate";
    }

    protected static IllegalStateException translate(String message, SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state)) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
