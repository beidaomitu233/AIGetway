package com.lightai.storage.publish;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 草稿撤销依赖查询（BE-038，4.5.1.5）。
 * blockers = 其他「新建草稿」（change_type=CREATE）引用目标对象的关系；
 * 撤销 CREATE 删除草稿对象前必须无此类引用（RV-025）。
 */
public final class JdbcDraftDependencyRepository implements DraftDependencyRepository {

    /** 引用关系：引用表.引用列 → 被引用实体类型。 */
    private static final List<Relation> RELATIONS = List.of(
            new Relation("provider", "credential_pool", "provider_id"),
            new Relation("provider", "provider_model", "provider_id"),
            new Relation("credential_pool", "credential", "pool_id"),
            new Relation("credential_pool", "route_candidate", "credential_pool_id"),
            new Relation("provider_model", "route_candidate", "provider_model_id"),
            new Relation("model_alias", "route_candidate", "alias_id"));

    private final String schemaName;

    public JdbcDraftDependencyRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcDraftDependencyRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 引用目标的 CREATE 草稿对象（dependency_summary / revert_blockers 数据源）。 */
    public List<Blocker> findCreateBlockers(Connection connection, String entityType, UUID entityId) {
        List<Blocker> blockers = new ArrayList<>();
        for (Relation relation : RELATIONS) {
            if (!relation.targetType().equals(entityType)) {
                continue;
            }
            String sql = "SELECT dc.entity_type, dc.entity_id, dc.entity_name FROM " + schemaName
                    + ".draft_change dc WHERE dc.deleted_at IS NULL AND dc.change_type = 'CREATE' "
                    + "AND dc.entity_type = ? AND EXISTS (SELECT 1 FROM " + schemaName + "."
                    + relation.referencingTable() + " t WHERE t.id = dc.entity_id AND t."
                    + relation.referencingColumn() + " = ? AND t.deleted_at IS NULL)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, relation.referencingTable());
                statement.setObject(2, entityId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        blockers.add(new Blocker(
                                rs.getString(1),
                                rs.getObject(2, UUID.class).toString(),
                                rs.getString(3)));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("草稿依赖查询失败：" + e.getClass().getSimpleName(), e);
            }
        }
        return List.copyOf(blockers);
    }


    private record Relation(String targetType, String referencingTable, String referencingColumn) {
    }
}
