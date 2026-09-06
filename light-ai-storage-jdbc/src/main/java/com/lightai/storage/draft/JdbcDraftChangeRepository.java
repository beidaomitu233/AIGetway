package com.lightai.storage.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * draft_change JDBC 实现（DATABASE_PLAN §29）。
 * 唯一约束 (entity_type, entity_id) 冲突转为覆盖更新；
 * deleted_at 活行语义由数据库部分唯一索引保证。
 */
public final class JdbcDraftChangeRepository extends AbstractJdbcRepository
        implements DraftChangeRepository, DraftChangeQueryRepository {

    public JdbcDraftChangeRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcDraftChangeRepository() {
        super();
    }

    @Override
    public boolean upsert(Connection connection, DraftChangeRecord record) {
        DatabaseDialect d = dialect(connection);
        String table = qualify(connection, "draft_change");
        String jsonPh = d.jsonPlaceholder();
        if (d.supportsReturning()) {
            String sql = """
                    INSERT INTO %s
                      (id, entity_type, entity_id, entity_name, change_type, changed_fields,
                       modified_by, entity_version, draft_revision)
                    VALUES (?, ?, ?, ?, ?, %s, ?, ?, ?)
                    ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                      entity_name = EXCLUDED.entity_name,
                      change_type = EXCLUDED.change_type,
                      changed_fields = EXCLUDED.changed_fields,
                      modified_by = EXCLUDED.modified_by,
                      entity_version = EXCLUDED.entity_version,
                      draft_revision = EXCLUDED.draft_revision,
                      updated_at = now()
                    RETURNING (xmax = 0) AS inserted
                    """.formatted(table, jsonPh).strip();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, record.id());
                statement.setString(2, record.entityType());
                statement.setObject(3, record.entityId());
                statement.setString(4, record.entityName());
                statement.setString(5, record.changeType());
                statement.setString(6, toJson(record.changedFields()));
                statement.setString(7, record.modifiedBy());
                statement.setLong(8, record.entityVersion());
                statement.setLong(9, record.draftRevision());
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getBoolean("inserted");
                }
            } catch (SQLException e) {
                throw translate("草稿差异写入失败", e);
            }
        } else {
            String sql = """
                    INSERT INTO %s
                      (id, entity_type, entity_id, entity_name, change_type, changed_fields,
                       modified_by, entity_version, draft_revision)
                    VALUES (?, ?, ?, ?, ?, %s, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      entity_name = VALUES(entity_name),
                      change_type = VALUES(change_type),
                      changed_fields = VALUES(changed_fields),
                      modified_by = VALUES(modified_by),
                      entity_version = VALUES(entity_version),
                      draft_revision = VALUES(draft_revision),
                      updated_at = CURRENT_TIMESTAMP
                    """.formatted(table, jsonPh).strip();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, record.id());
                statement.setString(2, record.entityType());
                statement.setObject(3, record.entityId());
                statement.setString(4, record.entityName());
                statement.setString(5, record.changeType());
                statement.setString(6, toJson(record.changedFields()));
                statement.setString(7, record.modifiedBy());
                statement.setLong(8, record.entityVersion());
                statement.setLong(9, record.draftRevision());
                int affected = statement.executeUpdate();
                return affected == 1;
            } catch (SQLException e) {
                throw translate("草稿差异写入失败", e);
            }
        }
    }

    private static String toJson(List<FieldChange> changes) {
        try {
            return ProtocolJson.protocol().writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("草稿差异序列化失败", e);
        }
    }

    /** 批量查询存在差异的对象 id（列表 draft_changed 标记，避免 N+1）。 */
    public Set<UUID> findChangedEntityIds(Connection connection, String entityType,
                                          Collection<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Set.of();
        }
        DatabaseDialect d = dialect(connection);
        List<UUID> idList = new ArrayList<>(entityIds);
        StringBuilder sql = new StringBuilder("SELECT entity_id FROM ")
                .append(qualify(connection, "draft_change"))
                .append(" WHERE entity_type = ? AND entity_id ");
        if (d.supportsArrayType()) {
            sql.append("= ANY(?)");
        } else {
            sql.append("IN (").append(inPlaceholders(idList.size())).append(")");
        }

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, entityType);
            if (d.supportsArrayType()) {
                statement.setArray(2, connection.createArrayOf("uuid", idList.toArray(UUID[]::new)));
            } else {
                for (int i = 0; i < idList.size(); i++) {
                    statement.setObject(2 + i, idList.get(i));
                }
            }
            try (var rs = statement.executeQuery()) {
                Set<UUID> ids = new java.util.HashSet<>();
                while (rs.next()) {
                    ids.add(d.readUuid(rs, 1));
                }
                return Set.copyOf(ids);
            }
        } catch (SQLException e) {
            throw translate("草稿差异查询失败", e);
        }
    }

    /** 最近差异摘要（详情页操作者展示来源）。 */
    public Optional<String> findLatestModifier(Connection connection, String entityType, UUID entityId) {
        String sql = "SELECT modified_by FROM " + qualify(connection, "draft_change")
                + " WHERE entity_type = ? AND entity_id = ? ORDER BY updated_at DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setObject(2, entityId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("差异操作者查询失败", e);
        }
    }

    // ---------- DraftChangeQueryRepository（BE-037/BE-038） ----------

    private static final String QUERY_COLUMNS =
            "id, entity_type, entity_id, entity_name, change_type, changed_fields, "
                    + "modified_by, entity_version, draft_revision, created_at, updated_at";

    @Override
    public List<DraftChangeRow> list(Connection connection, DraftChangeFilter filter,
                                     String sortExpression, int limit, long offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT " + QUERY_COLUMNS + " FROM ")
                .append(qualify(connection, "draft_change")).append(" WHERE deleted_at IS NULL");
        appendFilter(d, filter, sql);
        sql.append(" ORDER BY ").append(sortExpression).append(" LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = bindFilter(d, statement, filter);
            statement.setInt(index++, limit);
            statement.setLong(index, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<DraftChangeRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(d, rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("草稿差异列表查询失败", e);
        }
    }

    @Override
    public long count(Connection connection, DraftChangeFilter filter) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "draft_change")).append(" WHERE deleted_at IS NULL");
        appendFilter(d, filter, sql);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindFilter(d, statement, filter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("草稿差异计数失败", e);
        }
    }

    @Override
    public DraftChangeSummaryCounts summary(Connection connection) {
        String sql = "SELECT change_type, count(*) FROM " + qualify(connection, "draft_change")
                + " WHERE deleted_at IS NULL GROUP BY change_type";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            long create = 0;
            long update = 0;
            long enable = 0;
            long disable = 0;
            long delete = 0;
            while (rs.next()) {
                switch (rs.getString(1)) {
                    case "CREATE" -> create = rs.getLong(2);
                    case "UPDATE" -> update = rs.getLong(2);
                    case "ENABLE" -> enable = rs.getLong(2);
                    case "DISABLE" -> disable = rs.getLong(2);
                    case "DELETE" -> delete = rs.getLong(2);
                    default -> {
                        // 未知 change_type 不计入已知汇总
                    }
                }
            }
            return new DraftChangeSummaryCounts(create + update + enable + disable + delete,
                    create, update, enable, disable, delete);
        } catch (SQLException e) {
            throw translate("草稿差异摘要失败", e);
        }
    }

    @Override
    public java.util.Map<String, Long> countByEntityType(Connection connection) {
        String sql = "SELECT entity_type, count(*) FROM " + qualify(connection, "draft_change")
                + " WHERE deleted_at IS NULL GROUP BY entity_type";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getLong(2));
            }
            return counts;
        } catch (SQLException e) {
            throw translate("草稿差异分类计数失败", e);
        }
    }

    @Override
    public java.util.Map<String, java.util.Map<String, Long>> countByEntityTypeAndChangeType(
            Connection connection) {
        String sql = "SELECT entity_type, change_type, count(*) FROM " + qualify(connection, "draft_change")
                + " WHERE deleted_at IS NULL GROUP BY entity_type, change_type";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            java.util.Map<String, java.util.Map<String, Long>> counts = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                counts.computeIfAbsent(rs.getString(1), key -> new java.util.LinkedHashMap<>())
                        .put(rs.getString(2), rs.getLong(3));
            }
            return counts;
        } catch (SQLException e) {
            throw translate("草稿差异分组计数失败", e);
        }
    }

    @Override
    public Optional<ModifiedRange> modifiedRange(Connection connection) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT min(updated_at), max(updated_at) FROM " + qualify(connection, "draft_change")
                + " WHERE deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            OffsetDateTime first = d.readOffsetDateTime(rs, 1);
            OffsetDateTime last = d.readOffsetDateTime(rs, 2);
            if (first == null || last == null) {
                return Optional.empty();
            }
            return Optional.of(new ModifiedRange(first, last));
        } catch (SQLException e) {
            throw translate("草稿修改时间范围查询失败", e);
        }
    }

    @Override
    public Optional<DraftChangeRow> find(Connection connection, String entityType, UUID entityId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + QUERY_COLUMNS + " FROM " + qualify(connection, "draft_change")
                + " WHERE deleted_at IS NULL AND entity_type = ? AND entity_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setObject(2, entityId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(d, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("草稿差异查询失败", e);
        }
    }

    @Override
    public int delete(Connection connection, String entityType, UUID entityId) {
        String sql = "DELETE FROM " + qualify(connection, "draft_change")
                + " WHERE entity_type = ? AND entity_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setObject(2, entityId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("草稿差异删除失败", e);
        }
    }

    @Override
    public long deleteAll(Connection connection) {
        String sql = "DELETE FROM " + qualify(connection, "draft_change") + " WHERE deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("草稿差异清空失败", e);
        }
    }

    private DraftChangeRow mapRow(DatabaseDialect d, ResultSet rs) throws SQLException {
        List<FieldChange> changes = List.of();
        String raw = rs.getString("changed_fields");
        if (raw != null && !raw.isBlank()) {
            try {
                changes = ProtocolJson.protocol().readValue(raw, ProtocolJson.protocol()
                        .getTypeFactory().constructCollectionType(List.class, FieldChange.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("草稿差异反序列化失败", e);
            }
        }
        return new DraftChangeRow(
                d.readUuid(rs, "id"),
                rs.getString("entity_type"),
                d.readUuid(rs, "entity_id"),
                rs.getString("entity_name"),
                rs.getString("change_type"),
                changes,
                rs.getString("modified_by"),
                rs.getLong("entity_version"),
                rs.getLong("draft_revision"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }

    private static void appendFilter(DatabaseDialect d, DraftChangeFilter filter, StringBuilder sql) {
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND ").append(d.ilikeClause("entity_name"));
        }
        appendIn(d, sql, "entity_type", filter.entityTypes());
        appendIn(d, sql, "change_type", filter.changeTypes());
        appendIn(d, sql, "modified_by", filter.modifiedBy());
        if (filter.modifiedFrom() != null) {
            sql.append(" AND updated_at >= ?");
        }
        if (filter.modifiedTo() != null) {
            sql.append(" AND updated_at < ?");
        }
    }

    private static int bindFilter(DatabaseDialect d, PreparedStatement statement, DraftChangeFilter filter)
            throws SQLException {
        int index = 1;
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            statement.setString(index++, "%" + filter.keyword() + "%");
        }
        index = bindIn(d, statement, index, filter.entityTypes());
        index = bindIn(d, statement, index, filter.changeTypes());
        index = bindIn(d, statement, index, filter.modifiedBy());
        if (filter.modifiedFrom() != null) {
            statement.setObject(index++, filter.modifiedFrom());
        }
        if (filter.modifiedTo() != null) {
            statement.setObject(index++, filter.modifiedTo());
        }
        return index;
    }

    private static void appendIn(DatabaseDialect d, StringBuilder sql, String column, Set<String> values) {
        if (!values.isEmpty()) {
            if (d.supportsArrayType()) {
                sql.append(" AND ").append(column).append(" = ANY(?)");
            } else {
                sql.append(" AND ").append(column).append(" IN (")
                        .append(inPlaceholders(values.size())).append(")");
            }
        }
    }

    private static int bindIn(DatabaseDialect d, PreparedStatement statement, int index, Set<String> values)
            throws SQLException {
        if (values.isEmpty()) {
            return index;
        }
        if (d.supportsArrayType()) {
            statement.setArray(index, statement.getConnection().createArrayOf("text", values.toArray()));
            return index + 1;
        } else {
            for (String val : values) {
                statement.setString(index++, val);
            }
            return index;
        }
    }
}

