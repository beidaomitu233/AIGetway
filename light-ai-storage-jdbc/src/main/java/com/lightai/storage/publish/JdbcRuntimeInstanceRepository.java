package com.lightai.storage.publish;

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
public final class JdbcRuntimeInstanceRepository implements RuntimeInstanceRepository {

    private static final String COLUMNS =
            "instance_id, runtime_mode, runtime_version, application, zone, "
                    + "supported_schema_versions, loaded_adapter_types, active_snapshot_no, "
                    + "accepting_requests, status, last_heartbeat_at, last_error_code, "
                    + "last_error_summary, updated_at";

    private final String schemaName;

    public JdbcRuntimeInstanceRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcRuntimeInstanceRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 心跳写入：状态按 accepting_requests 推导（ONLINE/DRAINING），last_heartbeat_at=now。 */
    public void upsertHeartbeat(Connection connection, RuntimeInstanceRecord record) {
        String sql = "INSERT INTO " + schemaName + ".runtime_instance "
                + "(id, instance_id, runtime_mode, runtime_version, application, zone, "
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
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, record.instanceId());
            statement.setString(3, record.runtimeMode());
            statement.setString(4, record.runtimeVersion());
            statement.setString(5, record.application());
            statement.setString(6, record.zone());
            statement.setString(7, StringListJson.write(record.supportedSchemaVersions()));
            statement.setString(8, StringListJson.write(record.loadedAdapterTypes()));
            statement.setLong(9, record.activeSnapshotNo());
            statement.setBoolean(10, record.acceptingRequests());
            statement.setString(11, record.acceptingRequests() ? "ONLINE" : "DRAINING");
            statement.setString(12, record.lastErrorCode());
            statement.setString(13, record.lastErrorSummary());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("实例心跳写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 失联落定：超过 staleSeconds 为 STALE，超过三倍为 OFFLINE（4.5.2.5）。 */
    public int sweepStale(Connection connection, int staleSeconds) {
        String sql = "UPDATE " + schemaName + ".runtime_instance SET status = CASE "
                + "WHEN last_heartbeat_at < now() - make_interval(secs => ?::int * 3) THEN 'OFFLINE' "
                + "WHEN last_heartbeat_at < now() - make_interval(secs => ?::int) THEN 'STALE' "
                + "ELSE status END, updated_at = now() "
                + "WHERE status IN ('ONLINE', 'DRAINING') AND last_heartbeat_at IS NOT NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, staleSeconds);
            statement.setInt(2, staleSeconds);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("实例失联落定失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 固定发布目标：发布开始时的 ONLINE 实例集合。 */
    public List<RuntimeInstanceRecord> findOnline(Connection connection) {
        String sql = "SELECT " + COLUMNS + " FROM " + schemaName
                + ".runtime_instance WHERE status = 'ONLINE' ORDER BY instance_id";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<RuntimeInstanceRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(mapRow(rs));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IllegalStateException("在线实例查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public List<RuntimeInstanceRecord> list(Connection connection, RuntimeInstanceFilter filter,
                                            String sortExpression, int limit, long offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ")
                .append(schemaName).append(".runtime_instance WHERE 1 = 1");
        appendFilter(filter, sql);
        sql.append(" ORDER BY ").append(sortExpression).append(" LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = bindFilter(statement, filter);
            statement.setInt(index++, limit);
            statement.setLong(index, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<RuntimeInstanceRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实例列表查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public long count(Connection connection, RuntimeInstanceFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(schemaName).append(".runtime_instance WHERE 1 = 1");
        appendFilter(filter, sql);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindFilter(statement, filter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实例计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<RuntimeInstanceRecord> find(Connection connection, UUID instanceId) {
        String sql = "SELECT " + COLUMNS + " FROM " + schemaName
                + ".runtime_instance WHERE instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, instanceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实例读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private RuntimeInstanceRecord mapRow(ResultSet rs) throws SQLException {
        return new RuntimeInstanceRecord(
                rs.getObject("instance_id", UUID.class),
                rs.getString("runtime_mode"),
                rs.getString("runtime_version"),
                rs.getString("application"),
                rs.getString("zone"),
                StringListJson.parse(rs.getString("supported_schema_versions")),
                StringListJson.parse(rs.getString("loaded_adapter_types")),
                rs.getLong("active_snapshot_no"),
                rs.getBoolean("accepting_requests"),
                rs.getString("status"),
                rs.getObject("last_heartbeat_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getString("last_error_summary"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static void appendFilter(RuntimeInstanceFilter filter, StringBuilder sql) {
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            sql.append(" AND status = ANY(?)");
        }
        if (filter.runtimeMode() != null && !filter.runtimeMode().isBlank()) {
            sql.append(" AND runtime_mode = ?");
        }
        if (filter.application() != null && !filter.application().isBlank()) {
            sql.append(" AND application = ?");
        }
    }

    private static int bindFilter(PreparedStatement statement, RuntimeInstanceFilter filter)
            throws SQLException {
        int index = 1;
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            statement.setArray(index++, statement.getConnection()
                    .createArrayOf("text", filter.statuses().toArray()));
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
