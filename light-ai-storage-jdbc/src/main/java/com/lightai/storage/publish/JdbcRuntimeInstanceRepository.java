package com.lightai.storage.publish;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * runtime_instance JDBC 实现（DATABASE_PLAN §35）。
 * 心跳 upsert 按 instance_id 幂等；失联状态由 sweepStale 按失联时长落定。
 */
public final class JdbcRuntimeInstanceRepository extends AbstractJdbcRepository implements RuntimeInstanceRepository {

    private static final String COLUMNS =
            "instance_id, runtime_mode, runtime_version, application, zone, "
                    + "supported_schema_versions, loaded_adapter_types, active_snapshot_no, "
                    + "accepting_requests, status, last_heartbeat_at, last_error_code, "
                    + "last_error_summary, updated_at";

    public JdbcRuntimeInstanceRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcRuntimeInstanceRepository() {
        super();
    }

    /** 心跳写入：状态按 accepting_requests 推导（ONLINE/DRAINING），last_heartbeat_at=now。 */
    @Override
    public void upsertHeartbeat(Connection connection, RuntimeInstanceRecord record) {
        DatabaseDialect d = dialect(connection);
        String table = qualify(connection, "runtime_instance");
        String sql;
        if (d.databaseType() == DatabaseType.POSTGRESQL) {
            sql = "INSERT INTO " + table
                    + " (id, instance_id, runtime_mode, runtime_version, application, zone, "
                    + "supported_schema_versions, loaded_adapter_types, active_snapshot_no, "
                    + "accepting_requests, status, last_heartbeat_at, last_error_code, last_error_summary, "
                    + "created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, now(), ?, ?, now(), now()) "
                    + "ON CONFLICT (instance_id) DO UPDATE SET "
                    + "runtime_mode = EXCLUDED.runtime_mode, runtime_version = EXCLUDED.runtime_version, "
                    + "application = EXCLUDED.application, zone = EXCLUDED.zone, "
                    + "supported_schema_versions = EXCLUDED.supported_schema_versions, "
                    + "loaded_adapter_types = EXCLUDED.loaded_adapter_types, "
                    + "active_snapshot_no = EXCLUDED.active_snapshot_no, "
                    + "accepting_requests = EXCLUDED.accepting_requests, "
                    + "status = CASE WHEN EXCLUDED.accepting_requests THEN 'ONLINE' ELSE 'DRAINING' END, "
                    + "last_heartbeat_at = now(), last_error_code = EXCLUDED.last_error_code, "
                    + "last_error_summary = EXCLUDED.last_error_summary, updated_at = now()";
        } else {
            sql = "INSERT INTO " + table
                    + " (id, instance_id, runtime_mode, runtime_version, application, zone, "
                    + "supported_schema_versions, loaded_adapter_types, active_snapshot_no, "
                    + "accepting_requests, status, last_heartbeat_at, last_error_code, last_error_summary, "
                    + "created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + d.nowFunction() + ", ?, ?, " + d.nowFunction() + ", " + d.nowFunction() + ") "
                    + "ON DUPLICATE KEY UPDATE "
                    + "runtime_mode = VALUES(runtime_mode), runtime_version = VALUES(runtime_version), "
                    + "application = VALUES(application), zone = VALUES(zone), "
                    + "supported_schema_versions = VALUES(supported_schema_versions), "
                    + "loaded_adapter_types = VALUES(loaded_adapter_types), "
                    + "active_snapshot_no = VALUES(active_snapshot_no), "
                    + "accepting_requests = VALUES(accepting_requests), "
                    + "status = CASE WHEN VALUES(accepting_requests) THEN 'ONLINE' ELSE 'DRAINING' END, "
                    + "last_heartbeat_at = " + d.nowFunction() + ", last_error_code = VALUES(last_error_code), "
                    + "last_error_summary = VALUES(last_error_summary), updated_at = " + d.nowFunction();
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, UUID.randomUUID());
            d.bindUuid(statement, 2, record.instanceId());
            statement.setString(3, record.runtimeMode());
            statement.setString(4, record.runtimeVersion());
            statement.setString(5, record.application());
            statement.setString(6, record.zone());
            d.bindJson(statement, 7, StringListJson.write(record.supportedSchemaVersions()));
            d.bindJson(statement, 8, StringListJson.write(record.loadedAdapterTypes()));
            statement.setLong(9, record.activeSnapshotNo());
            statement.setBoolean(10, record.acceptingRequests());
            statement.setString(11, record.acceptingRequests() ? "ONLINE" : "DRAINING");
            statement.setString(12, record.lastErrorCode());
            statement.setString(13, record.lastErrorSummary());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("实例心跳写入失败", e);
        }
    }

    /** 失联落定：超过 staleSeconds 为 STALE，超过三倍为 OFFLINE（4.5.2.5）。 */
    @Override
    public int sweepStale(Connection connection, int staleSeconds) {
        DatabaseDialect d = dialect(connection);
        String table = qualify(connection, "runtime_instance");
        String sql;
        if (d.databaseType() == DatabaseType.POSTGRESQL) {
            sql = "UPDATE " + table + " SET status = CASE "
                    + "WHEN last_heartbeat_at < now() - make_interval(secs => ?::int * 3) THEN 'OFFLINE' "
                    + "WHEN last_heartbeat_at < now() - make_interval(secs => ?::int) THEN 'STALE' "
                    + "ELSE status END, updated_at = now() "
                    + "WHERE status IN ('ONLINE', 'DRAINING') AND last_heartbeat_at IS NOT NULL";
        } else {
            sql = "UPDATE " + table + " SET status = CASE "
                    + "WHEN last_heartbeat_at < DATE_SUB(" + d.nowFunction() + ", INTERVAL (? * 3) SECOND) THEN 'OFFLINE' "
                    + "WHEN last_heartbeat_at < DATE_SUB(" + d.nowFunction() + ", INTERVAL ? SECOND) THEN 'STALE' "
                    + "ELSE status END, updated_at = " + d.nowFunction() + " "
                    + "WHERE status IN ('ONLINE', 'DRAINING') AND last_heartbeat_at IS NOT NULL";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, staleSeconds);
            statement.setInt(2, staleSeconds);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("实例失联落定失败", e);
        }
    }

    /** 固定发布目标：发布开始时的 ONLINE 实例集合。 */
    @Override
    public List<RuntimeInstanceRecord> findOnline(Connection connection) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "runtime_instance")
                + " WHERE status = 'ONLINE' ORDER BY instance_id";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<RuntimeInstanceRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(mapRow(d, rs));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw translate("在线实例查询失败", e);
        }
    }

    @Override
    public List<RuntimeInstanceRecord> list(Connection connection, RuntimeInstanceFilter filter,
                                            String sortExpression, int limit, long offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ")
                .append(qualify(connection, "runtime_instance")).append(" WHERE 1 = 1");
        appendFilter(d, filter, sql);
        sql.append(" ORDER BY ").append(sortExpression).append(" LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = bindFilter(d, statement, filter);
            statement.setInt(index++, limit);
            statement.setLong(index, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<RuntimeInstanceRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(d, rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("实例列表查询失败", e);
        }
    }

    @Override
    public long count(Connection connection, RuntimeInstanceFilter filter) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "runtime_instance")).append(" WHERE 1 = 1");
        appendFilter(d, filter, sql);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindFilter(d, statement, filter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("实例计数失败", e);
        }
    }

    @Override
    public Optional<RuntimeInstanceRecord> find(Connection connection, UUID instanceId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "runtime_instance")
                + " WHERE instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, instanceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(d, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("实例读取失败", e);
        }
    }

    private RuntimeInstanceRecord mapRow(DatabaseDialect d, ResultSet rs) throws SQLException {
        return new RuntimeInstanceRecord(
                d.readUuid(rs, "instance_id"),
                rs.getString("runtime_mode"),
                rs.getString("runtime_version"),
                rs.getString("application"),
                rs.getString("zone"),
                StringListJson.parse(d.readJson(rs, "supported_schema_versions")),
                StringListJson.parse(d.readJson(rs, "loaded_adapter_types")),
                rs.getLong("active_snapshot_no"),
                rs.getBoolean("accepting_requests"),
                rs.getString("status"),
                d.readOffsetDateTime(rs, "last_heartbeat_at"),
                rs.getString("last_error_code"),
                rs.getString("last_error_summary"),
                d.readOffsetDateTime(rs, "updated_at"));
    }

    private static void appendFilter(DatabaseDialect d, RuntimeInstanceFilter filter, StringBuilder sql) {
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            if (d.supportsArrayType()) {
                sql.append(" AND status = ANY(?)");
            } else {
                sql.append(" AND status IN (").append(inPlaceholders(filter.statuses().size())).append(")");
            }
        }
        if (filter.runtimeMode() != null && !filter.runtimeMode().isBlank()) {
            sql.append(" AND runtime_mode = ?");
        }
        if (filter.application() != null && !filter.application().isBlank()) {
            sql.append(" AND application = ?");
        }
    }

    private static int bindFilter(DatabaseDialect d, PreparedStatement statement, RuntimeInstanceFilter filter)
            throws SQLException {
        int index = 1;
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            if (d.supportsArrayType()) {
                statement.setArray(index++, statement.getConnection()
                        .createArrayOf("text", filter.statuses().toArray()));
            } else {
                for (String st : filter.statuses()) {
                    statement.setString(index++, st);
                }
            }
        }
        if (filter.runtimeMode() != null && !filter.runtimeMode().isBlank()) {
            statement.setString(index++, filter.runtimeMode());
        }
        if (filter.application() != null && !filter.application().isBlank()) {
            statement.setString(index++, filter.application());
        }
        return index;
    }

}

