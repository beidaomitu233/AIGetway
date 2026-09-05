package com.lightai.storage.model;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * provider_model 表 JDBC 实现（DATABASE_PLAN §5）。
 * U(provider_id, model_id)、U(provider_id, display_name) 活行唯一由唯一索引保证，
 * 服务层先查后插给出确定错误码。
 */
public final class JdbcProviderModelRepository implements ProviderModelRepository {

    private static final String COLUMNS = """
            id, provider_id, model_id, display_name, model_type, tokenizer_family, context_window,
            max_output_tokens, support_stream, support_system_message, support_temperature, support_top_p,
            support_stop, temperature_min, temperature_max, top_p_min, top_p_max, max_stop_sequences,
            max_stop_length, default_temperature, default_top_p, default_max_tokens, default_stop,
            input_price, output_price, price_unit, currency, enabled, import_source, import_adapter_version,
            version, created_at, updated_at, deleted_at""";

    private final String schemaName;

    public JdbcProviderModelRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcProviderModelRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<ProviderModelRecord> find(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("provider_model 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<Long> findAliveVersion(Connection connection, UUID id) {
        String sql = "SELECT version FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("provider_model 版本读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean existsAliveByModelId(Connection connection, UUID providerId, String modelId) {
        String sql = "SELECT 1 FROM " + qualified()
                + " WHERE provider_id = ? AND model_id = ? AND deleted_at IS NULL LIMIT 1";
        return exists(connection, sql, providerId, modelId);
    }

    @Override
    public boolean existsAliveByDisplayName(Connection connection, UUID providerId, String displayName) {
        String sql = "SELECT 1 FROM " + qualified()
                + " WHERE provider_id = ? AND display_name = ? AND deleted_at IS NULL LIMIT 1";
        return exists(connection, sql, providerId, displayName);
    }

    @Override
    public void insert(Connection connection, ProviderModelRecord record) {
        String sql = "INSERT INTO " + qualified() + " (" + COLUMNS + ") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("provider_model 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void update(Connection connection, ProviderModelRecord record) {
        String sql = """
                UPDATE %s SET model_id=?, display_name=?, model_type=?, tokenizer_family=?, context_window=?,
                max_output_tokens=?, support_stream=?, support_system_message=?, support_temperature=?,
                support_top_p=?, support_stop=?, temperature_min=?, temperature_max=?, top_p_min=?, top_p_max=?,
                max_stop_sequences=?, max_stop_length=?, default_temperature=?, default_top_p=?,
                default_max_tokens=?, default_stop=?, input_price=?, output_price=?, price_unit=?, currency=?,
                enabled=?, import_source=?, import_adapter_version=?, version=?, updated_at=?, deleted_at=?
                WHERE id=?""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, record.modelId());
            statement.setString(i++, record.displayName());
            statement.setString(i++, record.modelType());
            setNullableString(statement, i++, record.tokenizerFamily());
            setNullableLong(statement, i++, record.contextWindow());
            setNullableLong(statement, i++, record.maxOutputTokens());
            setNullableBoolean(statement, i++, record.supportStream());
            setNullableBoolean(statement, i++, record.supportSystemMessage());
            setNullableBoolean(statement, i++, record.supportTemperature());
            setNullableBoolean(statement, i++, record.supportTopP());
            setNullableBoolean(statement, i++, record.supportStop());
            setNullableDecimal(statement, i++, record.temperatureMin());
            setNullableDecimal(statement, i++, record.temperatureMax());
            setNullableDecimal(statement, i++, record.topPMin());
            setNullableDecimal(statement, i++, record.topPMax());
            setNullableInt(statement, i++, record.maxStopSequences());
            setNullableInt(statement, i++, record.maxStopLength());
            setNullableDecimal(statement, i++, record.defaultTemperature());
            setNullableDecimal(statement, i++, record.defaultTopP());
            setNullableLong(statement, i++, record.defaultMaxTokens());
            statement.setString(i++, toJson(record.defaultStop()));
            statement.setBigDecimal(i++, record.inputPrice());
            statement.setBigDecimal(i++, record.outputPrice());
            statement.setInt(i++, record.priceUnit());
            statement.setString(i++, record.currency());
            statement.setBoolean(i++, record.enabled());
            setNullableString(statement, i++, record.importSource());
            setNullableString(statement, i++, record.importAdapterVersion());
            statement.setLong(i++, record.version());
            statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
            statement.setTimestamp(i++, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
            statement.setObject(i, record.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("provider_model 更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<ProviderModelRecord> list(Connection connection, String filterSql, List<Object> filterValues,
                                          String orderSql, long offset, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql)
                + " ORDER BY " + orderSql + " OFFSET ? LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            statement.setLong(i++, offset);
            statement.setInt(i, limit);
            return mapList(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("provider_model 列表读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long count(Connection connection, String filterSql, List<Object> filterValues) {
        String sql = "SELECT count(*) FROM " + qualified() + " WHERE deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("provider_model 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private List<ProviderModelRecord> mapList(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            List<ProviderModelRecord> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
            return rows;
        }
    }

    private boolean exists(Connection connection, String sql, Object... params) throws IllegalStateException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("provider_model 存在性检查失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static ProviderModelRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new ProviderModelRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("provider_id", UUID.class),
                rs.getString("model_id"),
                rs.getString("display_name"),
                rs.getString("model_type"),
                rs.getString("tokenizer_family"),
                getNullableLong(rs, "context_window"),
                getNullableLong(rs, "max_output_tokens"),
                getNullableBoolean(rs, "support_stream"),
                getNullableBoolean(rs, "support_system_message"),
                getNullableBoolean(rs, "support_temperature"),
                getNullableBoolean(rs, "support_top_p"),
                getNullableBoolean(rs, "support_stop"),
                getNullableDecimal(rs, "temperature_min"),
                getNullableDecimal(rs, "temperature_max"),
                getNullableDecimal(rs, "top_p_min"),
                getNullableDecimal(rs, "top_p_max"),
                getNullableInteger(rs, "max_stop_sequences"),
                getNullableInteger(rs, "max_stop_length"),
                getNullableDecimal(rs, "default_temperature"),
                getNullableDecimal(rs, "default_top_p"),
                getNullableLong(rs, "default_max_tokens"),
                fromJson(rs.getString("default_stop")),
                rs.getBigDecimal("input_price"),
                rs.getBigDecimal("output_price"),
                rs.getInt("price_unit"),
                rs.getString("currency"),
                rs.getBoolean("enabled"),
                rs.getString("import_source"),
                rs.getString("import_adapter_version"),
                rs.getLong("version"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at")),
                deletedAt == null ? null : offset(deletedAt));
    }

    private static void bind(PreparedStatement statement, ProviderModelRecord record) throws SQLException {
        int i = 1;
        statement.setObject(i++, record.id());
        statement.setObject(i++, record.providerId());
        statement.setString(i++, record.modelId());
        statement.setString(i++, record.displayName());
        statement.setString(i++, record.modelType());
        setNullableString(statement, i++, record.tokenizerFamily());
        setNullableLong(statement, i++, record.contextWindow());
        setNullableLong(statement, i++, record.maxOutputTokens());
        setNullableBoolean(statement, i++, record.supportStream());
        setNullableBoolean(statement, i++, record.supportSystemMessage());
        setNullableBoolean(statement, i++, record.supportTemperature());
        setNullableBoolean(statement, i++, record.supportTopP());
        setNullableBoolean(statement, i++, record.supportStop());
        setNullableDecimal(statement, i++, record.temperatureMin());
        setNullableDecimal(statement, i++, record.temperatureMax());
        setNullableDecimal(statement, i++, record.topPMin());
        setNullableDecimal(statement, i++, record.topPMax());
        setNullableInt(statement, i++, record.maxStopSequences());
        setNullableInt(statement, i++, record.maxStopLength());
        setNullableDecimal(statement, i++, record.defaultTemperature());
        setNullableDecimal(statement, i++, record.defaultTopP());
        setNullableLong(statement, i++, record.defaultMaxTokens());
        statement.setString(i++, toJson(record.defaultStop()));
        statement.setBigDecimal(i++, record.inputPrice());
        statement.setBigDecimal(i++, record.outputPrice());
        statement.setInt(i++, record.priceUnit());
        statement.setString(i++, record.currency());
        statement.setBoolean(i++, record.enabled());
        setNullableString(statement, i++, record.importSource());
        setNullableString(statement, i++, record.importAdapterVersion());
        statement.setLong(i++, record.version());
        statement.setTimestamp(i++, Timestamp.from(record.createdAt().toInstant()));
        statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
        statement.setTimestamp(i, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
    }

    private static String toJson(List<String> stops) {
        if (stops == null || stops.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < stops.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(stops.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        String body = json.trim();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : body.split(",")) {
            values.add(part.trim().replaceFirst("^\"", "").replaceFirst("\"$", "").replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return List.copyOf(values);
    }

    private String qualified() {
        return schemaName + ".provider_model";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getNullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean getNullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static BigDecimal getNullableDecimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BOOLEAN);
        } else {
            statement.setBoolean(index, value);
        }
    }

    private static void setNullableDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
