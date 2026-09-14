package com.lightai.storage.application;

import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** 应用模型映射当前集合与配置版本 JDBC 仓储。 */
public final class JdbcApplicationModelMappingRepository extends AbstractJdbcRepository {

    public JdbcApplicationModelMappingRepository() { super(); }
    public JdbcApplicationModelMappingRepository(String schemaName) { super(schemaName); }

    /** 运行时使用的对外模型名到旧快照 Alias 名称映射；仅返回可落到快照的迁移行。 */
    public Map<String, String> runtimeMappings(Connection connection, UUID applicationId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT m.public_model_name, COALESCE(v.code, m.public_model_name) FROM " + qualify(connection, "application_model_mapping")
                + " m LEFT JOIN " + qualify(connection, "virtual_model") + " v ON v.id = m.virtual_model_id"
                + " WHERE m.application_id = ? AND m.status = 'ACTIVE'"
                + " AND (m.virtual_model_id IS NULL OR v.deleted_at IS NULL)";
        Map<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            d.bindUuid(ps, 1, applicationId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.put(rs.getString(1), rs.getString(2)); }
            return Map.copyOf(result);
        } catch (SQLException e) { throw translate("运行时应用模型映射读取失败", e); }
    }

    public Optional<Long> currentRevision(Connection connection, UUID applicationId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT revision FROM " + qualify(connection, "application_config_revision")
                + " WHERE application_id = ? AND status = 'ACTIVE' ORDER BY revision DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            d.bindUuid(ps, 1, applicationId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(); }
        } catch (SQLException e) { throw translate("应用映射版本读取失败", e); }
    }

    public List<ApplicationModelMappingRecord> list(Connection connection, UUID applicationId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT id, application_id, revision_id, virtual_model_id, public_model_name, status, version, created_at, updated_at FROM "
                + qualify(connection, "application_model_mapping") + " WHERE application_id = ? AND status = 'ACTIVE' ORDER BY public_model_name, id";
        Map<UUID, MutableMapping> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            d.bindUuid(ps, 1, applicationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = d.readUuid(rs, "id");
                    rows.put(id, new MutableMapping(id, d.readUuid(rs, "application_id"), d.readUuid(rs, "revision_id"),
                            readUuidNullable(rs, "virtual_model_id"), rs.getString("public_model_name"), rs.getString("status"),
                            rs.getLong("version"), d.readOffsetDateTime(rs, "created_at"), d.readOffsetDateTime(rs, "updated_at"), new ArrayList<>()));
                }
            }
        } catch (SQLException e) { throw translate("应用映射读取失败", e); }
        if (rows.isEmpty()) return List.of();
        String targetSql = "SELECT id, mapping_id, channel_id, upstream_model_id, upstream_model_name, priority, weight, status, policy_json, created_at, updated_at FROM "
                + qualify(connection, "application_model_target") + " WHERE status = 'ACTIVE' AND mapping_id IN (" + inPlaceholders(rows.size()) + ") ORDER BY priority, weight DESC, id";
        try (PreparedStatement ps = connection.prepareStatement(targetSql)) {
            int index = 1; for (UUID id : rows.keySet()) d.bindUuid(ps, index++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MutableMapping mapping = rows.get(d.readUuid(rs, "mapping_id"));
                    if (mapping != null) mapping.targets.add(new ApplicationModelTargetRecord(d.readUuid(rs, "id"), mapping.id,
                            d.readUuid(rs, "channel_id"), readUuidNullable(rs, "upstream_model_id"), rs.getString("upstream_model_name"),
                            rs.getInt("priority"), rs.getInt("weight"), rs.getString("status"), d.readJson(rs, "policy_json"),
                            d.readOffsetDateTime(rs, "created_at"), d.readOffsetDateTime(rs, "updated_at")));
                }
            }
        } catch (SQLException e) { throw translate("应用映射目标读取失败", e); }
        return rows.values().stream().map(MutableMapping::freeze).toList();
    }

    public List<CatalogRow> catalog(Connection connection, List<UUID> channelIds, String query, int limit) {
        return catalog(connection, channelIds, query, limit, 0);
    }

    public List<CatalogRow> catalog(Connection connection, List<UUID> channelIds, String query, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT u.id, u.channel_id, u.model_id, u.display_name, u.status FROM ")
                .append(qualify(connection, "upstream_model")).append(" u JOIN ").append(qualify(connection, "channel"))
                .append(" c ON c.id = u.channel_id WHERE u.deleted_at IS NULL AND c.deleted_at IS NULL AND c.status = 'ACTIVE'");
        List<Object> params = new ArrayList<>();
        if (channelIds != null && !channelIds.isEmpty()) {
            sql.append(" AND u.channel_id IN (").append(inPlaceholders(channelIds.size())).append(")"); params.addAll(channelIds);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (").append(d.ilikeClause("u.model_id")).append(" OR ").append(d.ilikeClause("u.display_name")).append(")");
            params.add("%" + query.strip() + "%"); params.add("%" + query.strip() + "%");
        }
        sql.append(" ORDER BY u.model_id, u.id ").append(d.limitOffsetClause(Math.max(1, Math.min(limit, 500)), Math.max(0, offset)));
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int i = 1; for (Object value : params) bindParameter(ps, i++, value, d);
            try (ResultSet rs = ps.executeQuery()) {
                List<CatalogRow> result = new ArrayList<>();
                while (rs.next()) result.add(new CatalogRow(d.readUuid(rs, "id"), d.readUuid(rs, "channel_id"), rs.getString("model_id"), rs.getString("display_name"), "ACTIVE".equals(rs.getString("status"))));
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw translate("渠道模型目录读取失败", e); }
    }

    public boolean channelActive(Connection connection, UUID channelId) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + qualify(connection, "channel") + " WHERE id = ? AND deleted_at IS NULL AND status = 'ACTIVE'")) {
            d.bindUuid(ps, 1, channelId); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw translate("渠道引用校验失败", e); }
    }

    public Optional<CatalogRow> activeModel(Connection connection, UUID channelId, UUID upstreamModelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT u.id, u.channel_id, u.model_id, u.display_name, u.status FROM " + qualify(connection, "upstream_model")
                + " u JOIN " + qualify(connection, "channel") + " c ON c.id = u.channel_id WHERE u.id = ? AND u.channel_id = ? AND u.deleted_at IS NULL AND u.status = 'ACTIVE' AND c.deleted_at IS NULL AND c.status = 'ACTIVE'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            d.bindUuid(ps, 1, upstreamModelId); d.bindUuid(ps, 2, channelId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(new CatalogRow(d.readUuid(rs, "id"), d.readUuid(rs, "channel_id"), rs.getString("model_id"), rs.getString("display_name"), true)) : Optional.empty(); }
        } catch (SQLException e) { throw translate("上游模型引用校验失败", e); }
    }

    public long nextRevision(Connection connection, UUID applicationId) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(MAX(revision), 0) + 1 FROM " + qualify(connection, "application_config_revision") + " WHERE application_id = ?")) {
            d.bindUuid(ps, 1, applicationId); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 1L; }
        } catch (SQLException e) { throw translate("应用映射版本计算失败", e); }
    }

    /** 完整替换当前集合，但通过状态保留旧行，配置版本记录不可变历史。 */
    public void replace(Connection connection, UUID applicationId, long revision, String reason, String operatorId,
                        List<WriteMapping> mappings) {
        DatabaseDialect d = dialect(connection);
        UUID revisionId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("UPDATE " + qualify(connection, "application_config_revision") + " SET status = 'INACTIVE', updated_at = " + d.nowFunction() + " WHERE application_id = ? AND status = 'ACTIVE'")) {
            d.bindUuid(ps, 1, applicationId); ps.executeUpdate();
        } catch (SQLException e) { throw translate("应用映射旧版本停用失败", e); }
        String revSql = "INSERT INTO " + qualify(connection, "application_config_revision") + " (id, created_at, updated_at, application_id, revision, status, reason, created_by, mapping_count, content_json) VALUES (?, " + d.nowFunction() + ", " + d.nowFunction() + ", ?, ?, 'ACTIVE', ?, ?, ?, " + d.jsonPlaceholder() + ")";
        try (PreparedStatement ps = connection.prepareStatement(revSql)) {
            d.bindUuid(ps, 1, revisionId); d.bindUuid(ps, 2, applicationId); ps.setLong(3, revision); ps.setString(4, reason); ps.setString(5, operatorId); ps.setInt(6, mappings.size()); ps.setString(7, snapshotJson(mappings)); ps.executeUpdate();
        } catch (SQLException e) { throw translate("应用映射版本写入失败", e); }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE " + qualify(connection, "application_model_mapping") + " SET status = 'DISABLED', revision_id = ? WHERE application_id = ?")) {
            d.bindUuid(ps, 1, revisionId); d.bindUuid(ps, 2, applicationId); ps.executeUpdate();
        } catch (SQLException e) { throw translate("应用旧映射停用失败", e); }
        Map<String, UUID> existing = existingMappingIds(connection, applicationId, d);
        String insertMap = "INSERT INTO " + qualify(connection, "application_model_mapping") + " (id, created_at, updated_at, application_id, revision_id, virtual_model_id, public_model_name, status, version) VALUES (?, " + d.nowFunction() + ", " + d.nowFunction() + ", ?, ?, ?, ?, ?, 1)";
        String updateMap = "UPDATE " + qualify(connection, "application_model_mapping") + " SET revision_id = ?, virtual_model_id = ?, status = ?, version = version + 1, updated_at = " + d.nowFunction() + " WHERE id = ?";
        String disableTargets = "UPDATE " + qualify(connection, "application_model_target") + " SET status = 'DISABLED', updated_at = " + d.nowFunction() + " WHERE mapping_id = ?";
        String findTarget = "SELECT id FROM " + qualify(connection, "application_model_target") + " WHERE mapping_id = ? AND channel_id = ? AND upstream_model_name = ?";
        String insertTarget = "INSERT INTO " + qualify(connection, "application_model_target") + " (id, created_at, updated_at, mapping_id, channel_id, upstream_model_id, upstream_model_name, priority, weight, status, policy_json) VALUES (?, " + d.nowFunction() + ", " + d.nowFunction() + ", ?, ?, ?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ")";
        String updateTarget = "UPDATE " + qualify(connection, "application_model_target") + " SET upstream_model_id = ?, priority = ?, weight = ?, status = ?, policy_json = ?, updated_at = " + d.nowFunction() + " WHERE id = ?";
        try (PreparedStatement insert = connection.prepareStatement(insertMap); PreparedStatement update = connection.prepareStatement(updateMap);
             PreparedStatement disable = connection.prepareStatement(disableTargets); PreparedStatement find = connection.prepareStatement(findTarget);
             PreparedStatement ti = connection.prepareStatement(insertTarget); PreparedStatement tu = connection.prepareStatement(updateTarget)) {
            for (WriteMapping mapping : mappings) {
                String mappingKey = mapping.publicModelName().toLowerCase(Locale.ROOT);
                UUID mappingId = existing.getOrDefault(mappingKey, mapping.id());
                if (existing.containsKey(mappingKey)) {
                    d.bindUuid(update, 1, revisionId); bindNullableUuid(update, 2, mapping.virtualModelId(), d); update.setString(3, mapping.status()); d.bindUuid(update, 4, mappingId); update.executeUpdate();
                } else {
                    d.bindUuid(insert, 1, mappingId); d.bindUuid(insert, 2, applicationId); d.bindUuid(insert, 3, revisionId); bindNullableUuid(insert, 4, mapping.virtualModelId(), d); insert.setString(5, mapping.publicModelName()); insert.setString(6, mapping.status()); insert.executeUpdate();
                }
                d.bindUuid(disable, 1, mappingId); disable.executeUpdate();
                for (WriteTarget target : mapping.targets()) {
                    UUID targetId = null; d.bindUuid(find, 1, mappingId); d.bindUuid(find, 2, target.channelId()); find.setString(3, target.upstreamModelName());
                    try (ResultSet rs = find.executeQuery()) { if (rs.next()) targetId = d.readUuid(rs, "id"); }
                    if (targetId == null) {
                        d.bindUuid(ti, 1, target.id()); d.bindUuid(ti, 2, mappingId); d.bindUuid(ti, 3, target.channelId()); bindNullableUuid(ti, 4, target.upstreamModelId(), d); ti.setString(5, target.upstreamModelName()); ti.setInt(6, target.priority()); ti.setInt(7, target.weight()); ti.setString(8, target.status()); ti.setString(9, target.policyJson()); ti.executeUpdate();
                    } else {
                        bindNullableUuid(tu, 1, target.upstreamModelId(), d); tu.setInt(2, target.priority()); tu.setInt(3, target.weight()); tu.setString(4, target.status()); tu.setString(5, target.policyJson()); d.bindUuid(tu, 6, targetId); tu.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) { throw translate("应用映射写入失败", e); }
    }

    private Map<String, UUID> existingMappingIds(Connection c, UUID app, DatabaseDialect d) {
        Map<String, UUID> result = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, public_model_name FROM " + qualify(c, "application_model_mapping") + " WHERE application_id = ?")) {
            d.bindUuid(ps, 1, app); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.put(rs.getString(2).toLowerCase(Locale.ROOT), d.readUuid(rs, "id")); }
            return result;
        } catch (SQLException e) { throw translate("应用映射索引读取失败", e); }
    }

    private static void bindNullableUuid(PreparedStatement ps, int index, UUID value, DatabaseDialect d) throws SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.VARCHAR); else d.bindUuid(ps, index, value);
    }
    private static UUID readUuidNullable(ResultSet rs, String name) throws SQLException { Object value = rs.getObject(name); return value == null ? null : (value instanceof UUID u ? u : UUID.fromString(value.toString())); }

    private static String snapshotJson(List<WriteMapping> mappings) {
        try {
            return ProtocolJson.protocol().writeValueAsString(mappings == null ? List.of() : mappings);
        } catch (Exception e) {
            throw new IllegalStateException("应用映射版本快照序列化失败", e);
        }
    }

    public record WriteMapping(UUID id, UUID virtualModelId, String publicModelName, String status, List<WriteTarget> targets) { public WriteMapping { targets = targets == null ? List.of() : List.copyOf(targets); } }
    public record WriteTarget(UUID id, UUID channelId, UUID upstreamModelId, String upstreamModelName, int priority, int weight, String status, String policyJson) {}
    public record CatalogRow(UUID id, UUID channelId, String modelName, String displayName, boolean active) {}
    private record MutableMapping(UUID id, UUID applicationId, UUID revisionId, UUID virtualModelId, String publicModelName, String status, long version, java.time.OffsetDateTime createdAt, java.time.OffsetDateTime updatedAt, List<ApplicationModelTargetRecord> targets) {
        ApplicationModelMappingRecord freeze() { return new ApplicationModelMappingRecord(id, applicationId, revisionId, virtualModelId, publicModelName, status, version, createdAt, updatedAt, targets); }
    }
}
