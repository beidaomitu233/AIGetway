package com.lightai.storage.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * audit_log JDBC 实现（DATABASE_PLAN §38）。
 * created_at 由数据库事务 now 生成；changes 以 jsonb/json 落库；
 * 绑定值不进入任何日志。
 */
public final class JdbcAuditRepository extends AbstractJdbcRepository implements AuditRepository {

    public JdbcAuditRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcAuditRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcAuditRepository() {
        super();
    }

    @Override
    public void insert(Connection connection, AuditRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "audit_log")
                + " (id, request_id, operator_id, action, entity_type, entity_id, "
                + "result, changes, error_code, error_summary, source_mode, source_ip_masked) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ", ?, ?, ?, ?)";
        String changesJson = toJson(record.changes());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, record.id());
            statement.setString(2, record.requestId());
            statement.setString(3, record.operatorId());
            statement.setString(4, record.action());
            statement.setString(5, record.entityType());
            statement.setString(6, record.entityId());
            statement.setString(7, record.result());
            d.bindJson(statement, 8, changesJson);
            statement.setString(9, record.errorCode());
            statement.setString(10, record.errorSummary());
            statement.setString(11, record.sourceMode());
            statement.setString(12, record.sourceIpMasked());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("审计写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static String toJson(java.util.List<FieldChange> changes) {
        try {
            return ProtocolJson.protocol().writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            // changes 只含标量与数组，序列化失败属于编程错误，转译为确定异常
            throw new IllegalStateException("审计变更序列化失败", e);
        }
    }

    /** 供测试与唯一约束冲突断言使用。 */
    static UUID newId() {
        return UUID.randomUUID();
    }
}
