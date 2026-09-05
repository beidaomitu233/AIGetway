package com.lightai.storage.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.json.ProtocolJson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * draft_change JDBC 实现（DATABASE_PLAN §29）。
 * 唯一约束 (entity_type, entity_id) 冲突转为覆盖更新；
 * deleted_at 活行语义由数据库部分唯一索引保证。
 */
public final class JdbcDraftChangeRepository implements DraftChangeRepository {

    private static final String UPSERT = """
            INSERT INTO %s.draft_change
              (id, entity_type, entity_id, entity_name, change_type, changed_fields,
               modified_by, entity_version, draft_revision)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (entity_type, entity_id) DO UPDATE SET
              entity_name = EXCLUDED.entity_name,
              change_type = EXCLUDED.change_type,
              changed_fields = EXCLUDED.changed_fields,
              modified_by = EXCLUDED.modified_by,
              entity_version = EXCLUDED.entity_version,
              draft_revision = EXCLUDED.draft_revision,
              updated_at = now()
            RETURNING (xmax = 0) AS inserted
            """.strip();

    private final String schemaName;

    public JdbcDraftChangeRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcDraftChangeRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public boolean upsert(Connection connection, DraftChangeRecord record) {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT.formatted(schemaName))) {
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
            throw new IllegalStateException("草稿差异写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static String toJson(java.util.List<FieldChange> changes) {
        try {
            return ProtocolJson.protocol().writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("草稿差异序列化失败", e);
        }
    }
}
