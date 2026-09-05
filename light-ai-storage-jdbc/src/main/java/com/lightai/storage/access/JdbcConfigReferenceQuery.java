package com.lightai.storage.access;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ConfigReferenceQuery JDBC 实现：跨表只读 SQL。
 * 影响分析、检测编排与引用校验专用，不做写入；SQL 异常映射为确定异常。
 */
public final class JdbcConfigReferenceQuery implements ConfigReferenceQuery {

    private final String schemaName;

    public JdbcConfigReferenceQuery(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcConfigReferenceQuery() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<ProviderSummary> findProviderSummary(Connection connection, UUID providerId) {
        String sql = "SELECT id, name, type, base_url, enabled FROM " + q("provider")
                + " WHERE id = ? AND deleted_at IS NULL";
        return findOneProvider(connection, sql, providerId);
    }

    @Override
    public Optional<ProviderSummary> findProviderSummaryOfPool(Connection connection, UUID poolId) {
        String sql = """
                SELECT p.id, p.name, p.type, p.base_url, p.enabled FROM %s p
                JOIN %s c ON c.provider_id = p.id
                WHERE c.id = ? AND p.deleted_at IS NULL AND c.deleted_at IS NULL"""
                .formatted(q("provider"), q("credential_pool"));
        return findOneProvider(connection, sql, poolId);
    }

    @Override
    public Optional<EntitySummary> findPool(Connection connection, UUID poolId) {
        return findOne(connection, "SELECT id, name FROM " + q("credential_pool")
                + " WHERE id = ? AND deleted_at IS NULL", poolId, "CREDENTIAL_POOL");
    }

    @Override
    public List<EntitySummary> listPoolRefsOfProvider(Connection connection, UUID providerId) {
        return findList(connection, "SELECT id, name FROM " + q("credential_pool")
                + " WHERE provider_id = ? AND deleted_at IS NULL ORDER BY name", providerId, "CREDENTIAL_POOL");
    }

    @Override
    public List<EntitySummary> listModelRefsOfProvider(Connection connection, UUID providerId) {
        return findList(connection, "SELECT id, display_name FROM " + q("provider_model")
                + " WHERE provider_id = ? AND deleted_at IS NULL ORDER BY display_name", providerId, "PROVIDER_MODEL");
    }

    @Override
    public List<EntitySummary> listCredentialRefsOfPool(Connection connection, UUID poolId) {
        return findList(connection, "SELECT id, name FROM " + q("credential")
                + " WHERE pool_id = ? AND deleted_at IS NULL ORDER BY name", poolId, "CREDENTIAL");
    }

    @Override
    public List<EntitySummary> listCandidateRefsOfModel(Connection connection, UUID modelId) {
        return findList(connection, """
                SELECT c.id, a.display_name FROM %s c
                JOIN %s a ON a.id = c.alias_id AND a.deleted_at IS NULL
                WHERE c.provider_model_id = ? AND c.deleted_at IS NULL
                ORDER BY a.display_name""".formatted(q("route_candidate"), q("model_alias")),
                modelId, "ROUTE_CANDIDATE");
    }

    @Override
    public List<EntitySummary> listCandidateRefsOfPool(Connection connection, UUID poolId) {
        return findList(connection, """
                SELECT c.id, a.display_name FROM %s c
                JOIN %s a ON a.id = c.alias_id AND a.deleted_at IS NULL
                WHERE c.credential_pool_id = ? AND c.deleted_at IS NULL
                ORDER BY a.display_name""".formatted(q("route_candidate"), q("model_alias")),
                poolId, "ROUTE_CANDIDATE");
    }

    @Override
    public List<EntitySummary> listAliasGovernanceRefs(Connection connection, UUID aliasId) {
        List<EntitySummary> refs = new ArrayList<>();
        refs.addAll(findList(connection, "SELECT id, scope_id FROM " + q("limit_policy")
                + " WHERE scope_type = 'MODEL_ALIAS' AND scope_id = ? AND deleted_at IS NULL", aliasId, "LIMIT_POLICY"));
        refs.addAll(findList(connection, "SELECT id, alias_id FROM " + q("reliability_policy")
                + " WHERE alias_id = ? AND deleted_at IS NULL", aliasId, "RELIABILITY_POLICY"));
        refs.addAll(findList(connection, "SELECT credential_id, alias_id FROM " + q("access_credential_alias")
                + " WHERE alias_id = ?", aliasId, "ACCESS_CREDENTIAL"));
        return List.copyOf(refs);
    }

    @Override
    public List<UUID> listAliasIdsReferencingModel(Connection connection, UUID modelId) {
        return findUuids(connection, """
                SELECT DISTINCT c.alias_id FROM %s c
                WHERE c.provider_model_id = ? AND c.deleted_at IS NULL
                ORDER BY c.alias_id""".formatted(q("route_candidate")), modelId);
    }

    @Override
    public List<UUID> listAliasIdsReferencingPool(Connection connection, UUID poolId) {
        return findUuids(connection, """
                SELECT DISTINCT c.alias_id FROM %s c
                WHERE c.credential_pool_id = ? AND c.deleted_at IS NULL
                ORDER BY c.alias_id""".formatted(q("route_candidate")), poolId);
    }

    @Override
    public int countAliveCredentialsOfPool(Connection connection, UUID poolId) {
        String sql = "SELECT count(*) FROM " + q("credential")
                + " WHERE pool_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, poolId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? (int) rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("池凭证计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<UUID> findFirstAliveCredentialIdOfPool(Connection connection, UUID poolId) {
        String sql = "SELECT id FROM " + q("credential")
                + " WHERE pool_id = ? AND deleted_at IS NULL AND enabled = true ORDER BY created_at LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, poolId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("池凭证选择失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private Optional<ProviderSummary> findOneProvider(Connection connection, String sql, Object param) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ProviderSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("base_url"),
                        rs.getBoolean("enabled")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Provider 摘要查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private Optional<EntitySummary> findOne(Connection connection, String sql, Object param, String relation) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EntitySummary(rs.getObject(1, UUID.class), rs.getString(2), relation));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("引用查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private List<EntitySummary> findList(Connection connection, String sql, Object param, String relation) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                List<EntitySummary> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new EntitySummary(rs.getObject(1, UUID.class), rs.getString(2), relation));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("引用列表查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private List<UUID> findUuids(Connection connection, String sql, Object param) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
                return List.copyOf(ids);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Alias 归属查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private String q(String table) {
        return schemaName + "." + table;
    }
}
