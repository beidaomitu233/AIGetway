package com.lightai.storage.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.client.json.ProtocolJson;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * provider_model JDBC 仓储（DATABASE_PLAN §5）。
 * (provider_id, model_id) 活行唯一；价格列 numeric(20,8) 以 BigDecimal 承载。
 */
public class JdbcProviderModelRepository {

    private static final String COLUMNS =
            "id, provider_id, model_id, display_name, model_type, tokenizer_family, context_window, "
                    + "max_output_tokens, support_stream, support_system_message, support_temperature, "
                    + "support_top_p, support_stop, temperature_min, temperature_max, top_p_min, top_p_max, "
                    + "max_stop_sequences, max_stop_length, default_temperature, default_top_p, "
                    + "default_max_tokens, default_stop, input_price, output_price, price_unit, currency, "
                    + "enabled, import_source, import_adapter_version, version, created_at, updated_at";

    protected final String schemaName;

    public JdbcProviderModelRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcProviderModelRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, ProviderModelRecord record) {
        // created_at/updated_at 由数据库事务 now 生成
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        String sql = "INSERT INTO %s.provider_model (%s, created_at, updated_at) VALUES (%s, now(), now())"
                .formatted(qualified(), insertColumns, placeholders(insertColumns));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("模型写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, ProviderModelRecord r) throws SQLException {
        statement.setObject(1, r.id());
        statement.setObject(2, r.providerId());
        statement.setString(3, r.modelId());
        statement.setString(4, r.displayName());
        statement.setString(5, r.modelType());
        statement.setString(6, r.tokenizerFamily());
        statement.setObject(7, r.contextWindow());
        statement.setObject(8, r.maxOutputTokens());
        statement.setObject(9, r.supportStream(), java.sql.Types.BOOLEAN);
        statement.setObject(10, r.supportSystemMessage(), java.sql.Types.BOOLEAN);
        statement.setObject(11, r.supportTemperature(), java.sql.Types.BOOLEAN);
        statement.setObject(12, r.supportTopP(), java.sql.Types.BOOLEAN);
        statement.setObject(13, r.supportStop(), java.sql.Types.BOOLEAN);
        statement.setObject(14, r.temperatureMin());
        statement.setObject(15, r.temperatureMax());
        statement.setObject(16, r.topPMin());
        statement.setObject(17, r.topPMax());
        statement.setObject(18, r.maxStopSequences());
        statement.setObject(19, r.maxStopLength());
        statement.setObject(20, r.defaultTemperature());
        statement.setObject(21, r.defaultTopP());
        statement.setObject(22, r.defaultMaxTokens());
        statement.setString(23, toJson(r.defaultStop()));
        statement.setBigDecimal(24, r.inputPrice());
        statement.setBigDecimal(25, r.outputPrice());
        statement.setInt(26, r.priceUnit());
        statement.setString(27, r.currency());
        statement.setBoolean(28, r.enabled());
        statement.setString(29, r.importSource());
        statement.setString(30, r.importAdapterVersion());
        statement.setLong(31, r.version());
        statement.setObject(32, r.createdAt());
        statement.setObject(33, r.updatedAt());
    }

    public Optional<ProviderModelRecord> findLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("模型读取失败", e);
        }
    }

    public Optional<ProviderModelRecord> lockLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE id = ? AND deleted_at IS NULL FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("模型锁定失败", e);
        }
    }

    /** 导入幂等检查：同一 Provider 下外部模型 ID 是否已存在（含软删除）。 */
    public boolean existsByProviderAndModelId(Connection connection, UUID providerId, String modelId) {
        String sql = "SELECT 1 FROM " + qualified()
                + " WHERE provider_id = ? AND model_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerId);
            statement.setString(2, modelId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("模型存在性检查失败", e);
        }
    }

    /** 全字段更新（能力、默认值、价格），version+1；model_id/provider_id 不可变。 */
    public ProviderModelRecord update(Connection connection, ProviderModelRecord record) {
        String sql = """
                UPDATE %s.provider_model SET
                  display_name = ?, tokenizer_family = ?, context_window = ?, max_output_tokens = ?,
                  support_stream = ?, support_system_message = ?, support_temperature = ?,
                  support_top_p = ?, support_stop = ?, temperature_min = ?, temperature_max = ?,
                  top_p_min = ?, top_p_max = ?, max_stop_sequences = ?, max_stop_length = ?,
                  default_temperature = ?, default_top_p = ?, default_max_tokens = ?,
                  default_stop = ?::jsonb, input_price = ?, output_price = ?, price_unit = ?,
                  currency = ?, enabled = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.displayName());
            statement.setString(2, record.tokenizerFamily());
            statement.setObject(3, record.contextWindow());
            statement.setObject(4, record.maxOutputTokens());
            statement.setObject(5, record.supportStream(), java.sql.Types.BOOLEAN);
            statement.setObject(6, record.supportSystemMessage(), java.sql.Types.BOOLEAN);
            statement.setObject(7, record.supportTemperature(), java.sql.Types.BOOLEAN);
            statement.setObject(8, record.supportTopP(), java.sql.Types.BOOLEAN);
            statement.setObject(9, record.supportStop(), java.sql.Types.BOOLEAN);
            statement.setObject(10, record.temperatureMin());
            statement.setObject(11, record.temperatureMax());
            statement.setObject(12, record.topPMin());
            statement.setObject(13, record.topPMax());
            statement.setObject(14, record.maxStopSequences());
            statement.setObject(15, record.maxStopLength());
            statement.setObject(16, record.defaultTemperature());
            statement.setObject(17, record.defaultTopP());
            statement.setObject(18, record.defaultMaxTokens());
            statement.setString(19, toJson(record.defaultStop()));
            statement.setBigDecimal(20, record.inputPrice());
            statement.setBigDecimal(21, record.outputPrice());
            statement.setInt(22, record.priceUnit());
            statement.setString(23, record.currency());
            statement.setBoolean(24, record.enabled());
            statement.setObject(25, record.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("模型更新未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("模型更新失败", e);
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        String sql = "UPDATE %s.provider_model SET deleted_at = now(), updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NULL".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("模型删除失败", e);
        }
    }

    public List<ProviderModelRecord> listByProvider(Connection connection, UUID providerId,
                                                    String keyword, Boolean supportStream,
                                                    Boolean enabled, String sortExpression,
                                                    int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE provider_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(providerId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (model_id ILIKE ? OR display_name ILIKE ?)");
            params.add("%" + keyword.strip() + "%");
            params.add("%" + keyword.strip() + "%");
        }
        if (supportStream != null) {
            sql.append(" AND support_stream = ?");
            params.add(supportStream);
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        sql.append(" ORDER BY ").append(sortExpression).append(", id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<ProviderModelRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("模型列表查询失败", e);
        }
    }

    public long countByProvider(Connection connection, UUID providerId, String keyword,
                                Boolean supportStream, Boolean enabled) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified())
                .append(" WHERE provider_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(providerId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (model_id ILIKE ? OR display_name ILIKE ?)");
            params.add("%" + keyword.strip() + "%");
            params.add("%" + keyword.strip() + "%");
        }
        if (supportStream != null) {
            sql.append(" AND support_stream = ?");
            params.add(supportStream);
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("模型计数失败", e);
        }
    }

    private ProviderModelRecord mapRow(ResultSet rs) throws SQLException {
        return new ProviderModelRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("provider_id", UUID.class),
                rs.getString("model_id"),
                rs.getString("display_name"),
                rs.getString("model_type"),
                rs.getString("tokenizer_family"),
                (Long) rs.getObject("context_window"),
                (Long) rs.getObject("max_output_tokens"),
                (Boolean) rs.getObject("support_stream"),
                (Boolean) rs.getObject("support_system_message"),
                (Boolean) rs.getObject("support_temperature"),
                (Boolean) rs.getObject("support_top_p"),
                (Boolean) rs.getObject("support_stop"),
                rs.getObject("temperature_min") == null ? null : rs.getBigDecimal("temperature_min"),
                rs.getObject("temperature_max") == null ? null : rs.getBigDecimal("temperature_max"),
                rs.getObject("top_p_min") == null ? null : rs.getBigDecimal("top_p_min"),
                rs.getObject("top_p_max") == null ? null : rs.getBigDecimal("top_p_max"),
                (Integer) rs.getObject("max_stop_sequences"),
                (Integer) rs.getObject("max_stop_length"),
                rs.getObject("default_temperature") == null ? null : rs.getBigDecimal("default_temperature"),
                rs.getObject("default_top_p") == null ? null : rs.getBigDecimal("default_top_p"),
                (Long) rs.getObject("default_max_tokens"),
                fromJson(rs.getString("default_stop")),
                rs.getBigDecimal("input_price"),
                rs.getBigDecimal("output_price"),
                rs.getInt("price_unit"),
                rs.getString("currency"),
                rs.getBoolean("enabled"),
                rs.getString("import_source"),
                rs.getString("import_adapter_version"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String toJson(List<String> stops) {
        try {
            return ProtocolJson.protocol().writeValueAsString(stops);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("default_stop 序列化失败", e);
        }
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(ProtocolJson.protocol().readValue(json,
                    ProtocolJson.protocol().getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("default_stop 解析失败", e);
        }
    }

    private static String placeholders(String columns) {
        int count = columns.split(",").length;
        return "(" + "?,".repeat(count - 1) + "?)";
    }

    private String qualified() {
        return schemaName + ".provider_model";
    }

    protected static IllegalStateException translate(String message, SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state)) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
