package com.lightai.storage.reference;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 配置引用关系查询（BE-010/BE-012 影响分析与引用计数）。
 * V2 资源域：涉及 upstream_model、channel_credential、route_candidate 表；
 * 历史对象使用逻辑 ID，不级联配置清理。
 */
public class JdbcConfigReferenceRepository extends AbstractJdbcRepository {

    public JdbcConfigReferenceRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcConfigReferenceRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcConfigReferenceRepository() {
        super();
    }

    public long countUpstreamModels(Connection connection, UUID channelId) {
        String sql = "SELECT count(*) FROM " + qualify(connection, "upstream_model")
                + " WHERE channel_id = ? AND deleted_at IS NULL";
        return count(connection, sql, channelId);
    }

    /** 渠道下渠道 Key 数（V2 无凭证池中间层）。 */
    public long countChannelCredentials(Connection connection, UUID channelId) {
        String sql = "SELECT count(*) FROM " + qualify(connection, "channel_credential")
                + " WHERE channel_id = ? AND deleted_at IS NULL";
        return count(connection, sql, channelId);
    }

    /** 渠道内 Key 明细：总数与启用数（健康计数由 object_runtime_state 组合）。 */
    public CredentialCounts countCredentialsByChannel(Connection connection, UUID channelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT count(*) AS total, COUNT(CASE WHEN status = 'ACTIVE' THEN 1 END) AS enabled_count "
                + "FROM " + qualify(connection, "channel_credential") + " WHERE channel_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, channelId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new CredentialCounts(rs.getLong("total"), rs.getLong("enabled_count"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("凭证计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public long countRouteCandidates(Connection connection, UUID channelId) {
        String sql = "SELECT count(*) FROM " + qualify(connection, "route_candidate")
                + " WHERE channel_id = ? AND deleted_at IS NULL";
        return count(connection, sql, channelId);
    }

    /** 渠道被引用的不同 Alias 数（BE-012 model_alias_count）。 */
    public long countAliasesByChannel(Connection connection, UUID channelId) {
        String sql = "SELECT count(DISTINCT virtual_model_id) FROM " + qualify(connection, "route_candidate")
                + " WHERE channel_id = ? AND deleted_at IS NULL";
        return count(connection, sql, channelId);
    }

    /** 渠道被引用的 Alias 集合（经模型候选推导，BE-010 affected_alias_ids）。 */
    public List<UUID> aliasIdsByChannel(Connection connection, UUID channelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT DISTINCT rc.alias_id FROM "
                + qualify(connection, "route_candidate") + " rc JOIN "
                + qualify(connection, "upstream_model") + " pm ON pm.id = rc.upstream_model_id AND pm.deleted_at IS NULL "
                + "WHERE pm.channel_id = ? AND rc.deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, channelId);
            try (ResultSet rs = statement.executeQuery()) {
                List<UUID> aliasIds = new ArrayList<>();
                while (rs.next()) {
                    aliasIds.add(d.readUuid(rs, "alias_id"));
                }
                return List.copyOf(aliasIds);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Alias引用查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Map<UUID, String> upstreamModelNamesByChannel(Connection connection, UUID channelId) {
        String sql = "SELECT id, display_name FROM " + qualify(connection, "upstream_model")
                + " WHERE channel_id = ? AND deleted_at IS NULL ORDER BY display_name";
        return nameMap(connection, sql, channelId);
    }

    /** 渠道 Key 引用明细：id → 名称，用于 ImpactAnalysis.references。 */
    public Map<UUID, String> credentialNamesByChannel(Connection connection, UUID channelId) {
        String sql = "SELECT id, name FROM " + qualify(connection, "channel_credential")
                + " WHERE channel_id = ? AND deleted_at IS NULL ORDER BY name";
        return nameMap(connection, sql, channelId);
    }

    private Map<UUID, String> nameMap(Connection connection, String sql, UUID id) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, String> names = new LinkedHashMap<>();
                while (rs.next()) {
                    names.put(d.readUuid(rs, 1), rs.getString(2));
                }
                return java.util.Collections.unmodifiableMap(names);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("引用名称查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 批量计数（列表组合引用数，避免 N+1）。 */
    public Map<UUID, Long> countUpstreamModelsByChannels(Connection connection, List<UUID> channelIds) {
        return countGrouped(connection, "upstream_model", "channel_id", channelIds);
    }

    public Map<UUID, Long> countCredentialsByChannels(Connection connection, List<UUID> channelIds) {
        return countGrouped(connection, "channel_credential", "channel_id", channelIds);
    }

    /** 检测命令目标解析：渠道下的上游模型（BE-009）。 */
    public Optional<UUID> findModelIdByChannelAndModelId(Connection connection, UUID channelId,
                                                        String externalModelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT id FROM " + qualify(connection, "upstream_model")
                + " WHERE channel_id = ? AND model_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, channelId);
            statement.setString(2, externalModelId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(d.readUuid(rs, 1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("模型查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 检测命令目标解析：渠道 Key 须直挂该渠道（BE-009）。 */
    public boolean credentialBelongsToChannel(Connection connection, UUID channelCredentialId, UUID channelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "channel_credential")
                + " WHERE id = ? AND channel_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, channelCredentialId);
            d.bindUuid(statement, 2, channelId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("渠道 Key 归属检查失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 渠道候选引用明细：候选 id → Alias 名称（BE-012 blockers）。 */
    public Map<UUID, String> candidateNamesByChannel(Connection connection, UUID channelId) {
        DatabaseDialect d = dialect(connection);
        String castExpr = (d.databaseType() == DatabaseType.POSTGRESQL) ? "rc.id::text" : "CAST(rc.id AS CHAR)";
        String sql = "SELECT rc.id, COALESCE(ma.code, ma.display_name, " + castExpr + ") AS name FROM "
                + qualify(connection, "route_candidate") + " rc LEFT JOIN "
                + qualify(connection, "virtual_model") + " ma ON ma.id = rc.virtual_model_id "
                + "WHERE rc.channel_id = ? AND rc.deleted_at IS NULL ORDER BY name";
        return nameMap(connection, sql, channelId);
    }

    private Map<UUID, Long> countGrouped(Connection connection, String tableName, String column, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        DatabaseDialect d = dialect(connection);
        String qualifiedTable = qualify(connection, tableName);
        if (d.supportsArrayType()) {
            String sql = "SELECT " + column + ", count(*) FROM " + qualifiedTable
                    + " WHERE deleted_at IS NULL AND " + column + " = ANY(?) GROUP BY " + column;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setArray(1, connection.createArrayOf("uuid", ids.toArray(UUID[]::new)));
                return executeCountGrouped(d, statement);
            } catch (SQLException e) {
                throw new IllegalStateException("引用计数失败：" + e.getClass().getSimpleName(), e);
            }
        } else {
            String sql = "SELECT " + column + ", count(*) FROM " + qualifiedTable
                    + " WHERE deleted_at IS NULL AND " + column + " IN (" + inPlaceholders(ids.size()) + ") GROUP BY " + column;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < ids.size(); i++) {
                    d.bindUuid(statement, i + 1, ids.get(i));
                }
                return executeCountGrouped(d, statement);
            } catch (SQLException e) {
                throw new IllegalStateException("引用计数失败：" + e.getClass().getSimpleName(), e);
            }
        }
    }

    private Map<UUID, Long> executeCountGrouped(DatabaseDialect d, PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            Map<UUID, Long> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(d.readUuid(rs, 1), rs.getLong(2));
            }
            return java.util.Collections.unmodifiableMap(counts);
        }
    }

    private long count(Connection connection, String sql, UUID id) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("引用计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public record CredentialCounts(long total, long enabledCount) {
    }
}
