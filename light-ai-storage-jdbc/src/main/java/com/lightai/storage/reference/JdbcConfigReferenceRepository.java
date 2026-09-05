package com.lightai.storage.reference;

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
 * 涉及 provider_model、credential、route_candidate 表（迁移由 DB-P02/P03 提供）；
 * 历史对象使用逻辑 ID，不级联配置清理。
 */
public class JdbcConfigReferenceRepository {

    private final String schemaName;

    public JdbcConfigReferenceRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcConfigReferenceRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public long countProviderModels(Connection connection, UUID providerId) {
        String sql = "SELECT count(*) FROM " + schemaName
                + ".provider_model WHERE provider_id = ? AND deleted_at IS NULL";
        return count(connection, sql, providerId);
    }

    public long countPools(Connection connection, UUID providerId) {
        String sql = "SELECT count(*) FROM " + schemaName
                + ".credential_pool WHERE provider_id = ? AND deleted_at IS NULL";
        return count(connection, sql, providerId);
    }

    /** 池内凭证明细：总数与启用数（健康计数由 object_runtime_state 组合）。 */
    public CredentialCounts countCredentialsByPool(Connection connection, UUID poolId) {
        String sql = "SELECT count(*) AS total, count(*) FILTER (WHERE enabled) AS enabled_count "
                + "FROM " + schemaName + ".credential WHERE pool_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, poolId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new CredentialCounts(rs.getLong("total"), rs.getLong("enabled_count"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("凭证计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public long countRouteCandidates(Connection connection, UUID poolId) {
        String sql = "SELECT count(*) FROM " + schemaName
                + ".route_candidate WHERE credential_pool_id = ? AND deleted_at IS NULL";
        return count(connection, sql, poolId);
    }

    /** 池被引用的不同 Alias 数（BE-012 model_alias_count）。 */
    public long countAliasesByPool(Connection connection, UUID poolId) {
        String sql = "SELECT count(DISTINCT alias_id) FROM " + schemaName
                + ".route_candidate WHERE credential_pool_id = ? AND deleted_at IS NULL";
        return count(connection, sql, poolId);
    }

    /** Provider 被引用的 Alias 集合（经模型候选推导，BE-010 affected_alias_ids）。 */
    public List<UUID> aliasIdsByProvider(Connection connection, UUID providerId) {
        String sql = """
                SELECT DISTINCT rc.alias_id
                  FROM %s.route_candidate rc
                  JOIN %s.provider_model pm ON pm.id = rc.provider_model_id AND pm.deleted_at IS NULL
                 WHERE pm.provider_id = ? AND rc.deleted_at IS NULL
                """.strip().formatted(schemaName, schemaName);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerId);
            try (ResultSet rs = statement.executeQuery()) {
                List<UUID> aliasIds = new ArrayList<>();
                while (rs.next()) {
                    aliasIds.add(rs.getObject("alias_id", UUID.class));
                }
                return List.copyOf(aliasIds);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Alias引用查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 引用明细：id → 名称，用于 ImpactAnalysis.references。 */
    public Map<UUID, String> poolNamesByProvider(Connection connection, UUID providerId) {
        String sql = "SELECT id, name FROM " + schemaName
                + ".credential_pool WHERE provider_id = ? AND deleted_at IS NULL ORDER BY name";
        return nameMap(connection, sql, providerId);
    }

    public Map<UUID, String> providerModelNamesByProvider(Connection connection, UUID providerId) {
        String sql = "SELECT id, display_name FROM " + schemaName
                + ".provider_model WHERE provider_id = ? AND deleted_at IS NULL ORDER BY display_name";
        return nameMap(connection, sql, providerId);
    }

    public Map<UUID, String> credentialNamesByPool(Connection connection, UUID poolId) {
        String sql = "SELECT id, name FROM " + schemaName
                + ".credential WHERE pool_id = ? AND deleted_at IS NULL ORDER BY name";
        return nameMap(connection, sql, poolId);
    }

    private Map<UUID, String> nameMap(Connection connection, String sql, UUID id) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, String> names = new LinkedHashMap<>();
                while (rs.next()) {
                    names.put(rs.getObject(1, UUID.class), rs.getString(2));
                }
                return java.util.Collections.unmodifiableMap(names);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("引用名称查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 批量计数（列表组合引用数，避免 N+1）。 */
    public Map<UUID, Long> countProviderModelsByProviders(Connection connection, List<UUID> providerIds) {
        String sql = "SELECT provider_id, count(*) FROM " + schemaName
                + ".provider_model WHERE deleted_at IS NULL AND provider_id = ANY(?) GROUP BY provider_id";
        return countGrouped(connection, sql, providerIds);
    }

    public Map<UUID, Long> countPoolsByProviders(Connection connection, List<UUID> providerIds) {
        String sql = "SELECT provider_id, count(*) FROM " + schemaName
                + ".credential_pool WHERE deleted_at IS NULL AND provider_id = ANY(?) GROUP BY provider_id";
        return countGrouped(connection, sql, providerIds);
    }

    /** 检测命令目标解析：Provider 下的模型（BE-009）。 */
    public Optional<UUID> findModelIdByProviderAndModelId(Connection connection, UUID providerId,
                                                          String externalModelId) {
        String sql = "SELECT id FROM " + schemaName
                + ".provider_model WHERE provider_id = ? AND model_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerId);
            statement.setString(2, externalModelId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("模型查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 检测命令目标解析：凭证须属于该 Provider 的池（BE-009）。 */
    public boolean credentialBelongsToProvider(Connection connection, UUID credentialId, UUID providerId) {
        String sql = """
                SELECT 1 FROM %s.credential c
                  JOIN %s.credential_pool p ON p.id = c.pool_id AND p.deleted_at IS NULL
                 WHERE c.id = ? AND p.provider_id = ? AND c.deleted_at IS NULL
                """.strip().formatted(schemaName, schemaName);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, credentialId);
            statement.setObject(2, providerId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("凭证归属检查失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 池候选引用明细：候选 id → Alias 名称（BE-012 blockers）。 */
    public Map<UUID, String> candidateNamesByPool(Connection connection, UUID poolId) {
        String sql = """
                SELECT rc.id, COALESCE(ma.alias, ma.display_name, rc.id::text) AS name
                  FROM %s.route_candidate rc
                  LEFT JOIN %s.model_alias ma ON ma.id = rc.alias_id
                 WHERE rc.credential_pool_id = ? AND rc.deleted_at IS NULL
                 ORDER BY name
                """.strip().formatted(schemaName, schemaName);
        return nameMap(connection, sql, poolId);
    }

    private Map<UUID, Long> countGrouped(Connection connection, String sql, List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray(UUID[]::new)));
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, Long> counts = new LinkedHashMap<>();
                while (rs.next()) {
                    counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
                }
                return java.util.Collections.unmodifiableMap(counts);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("引用计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private long count(Connection connection, String sql, UUID id) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
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
