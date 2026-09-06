package com.lightai.storage.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * provider_model JDBC 仓储（DATABASE_PLAN §5）。
 * (provider_id, model_id) 活行唯一；价格列 numeric(20,8) 以 BigDecimal 承载。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public class JdbcProviderModelRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, provider_id, model_id, display_name, model_type, tokenizer_family, context_window, "
                    + "max_output_tokens, support_stream, support_system_message, support_temperature, "
                    + "support_top_p, support_stop, temperature_min, temperature_max, top_p_min, top_p_max, "
                    + "max_stop_sequences, max_stop_length, default_temperature, default_top_p, "
                    + "default_max_tokens, default_stop, input_price, output_price, price_unit, currency, "
                    + "enabled, import_source, import_adapter_version, version, created_at, updated_at";

    public JdbcProviderModelRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcProviderModelRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcProviderModelRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, ProviderModelRecord record) {
        DatabaseDialect d = dialect(connection);
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        String sql = "INSERT INTO " + qualify(connection, "provider_model") + " (" + insertColumns + ", created_at, updated_at) "
                + "VALUES (" + insertPlaceholders(insertColumns, d) + ", " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record, d);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("模型写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, ProviderModelRecord r, DatabaseDialect d) throws SQLException {
        d.bindUuid(statement, 1, r.id());
        d.bindUuid(statement, 2, r.providerId());
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
    }

    public Optional<ProviderModelRecord> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "provider_model")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("模型读取失败", e);
        }
    }

    public Optional<ProviderModelRecord> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "provider_model")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("模型锁定失败", e);
        }
    }

    /** 导入幂等检查：同一 Provider 下外部模型 ID 是否已存在（含软删除）。 */
    public boolean existsByProviderAndModelId(Connection connection, UUID providerId, String modelId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "provider_model")
                + " WHERE provider_id = ? AND model_id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, providerId);
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
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s SET
                      display_name = ?, tokenizer_family = ?, context_window = ?, max_output_tokens = ?,
                      support_stream = ?, support_system_message = ?, support_temperature = ?,
                      support_top_p = ?, support_stop = ?, temperature_min = ?, temperature_max = ?,
                      top_p_min = ?, top_p_max = ?, max_stop_sequences = ?, max_stop_length = ?,
                      default_temperature = ?, default_top_p = ?, default_max_tokens = ?,
                      default_stop = %s, input_price = ?, output_price = ?, price_unit = ?,
                      currency = ?, enabled = ?, version = version + 1, updated_at = %s
                    WHERE id = ? AND deleted_at IS NULL
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "provider_model"), d.jsonPlaceholder(), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindUpdateParams(statement, record, d);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("模型更新未命中活行");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw translate("模型更新失败", e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "provider_model") + " SET "
                    + "display_name = ?, tokenizer_family = ?, context_window = ?, max_output_tokens = ?, "
                    + "support_stream = ?, support_system_message = ?, support_temperature = ?, "
                    + "support_top_p = ?, support_stop = ?, temperature_min = ?, temperature_max = ?, "
                    + "top_p_min = ?, top_p_max = ?, max_stop_sequences = ?, max_stop_length = ?, "
                    + "default_temperature = ?, default_top_p = ?, default_max_tokens = ?, "
                    + "default_stop = " + d.jsonPlaceholder() + ", input_price = ?, output_price = ?, price_unit = ?, "
                    + "currency = ?, enabled = ?, version = version + 1, updated_at = " + d.nowFunction() + " "
                    + "WHERE id = ? AND deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindUpdateParams(statement, record, d);
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("模型更新未命中活行");
                }
                return findLiveById(connection, record.id())
                        .orElseThrow(() -> new IllegalStateException("模型更新后未找到活行"));
            } catch (SQLException e) {
                throw translate("模型更新失败", e);
            }
        }
    }

    private void bindUpdateParams(PreparedStatement statement, ProviderModelRecord record, DatabaseDialect d) throws SQLException {
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
        d.bindUuid(statement, 25, record.id());
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "provider_model")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("模型删除失败", e);
        }
    }

    public List<ProviderModelRecord> listByProvider(Connection connection, UUID providerId,
                                                    String keyword, Boolean supportStream,
                                                    Boolean enabled, String sortExpression,
                                                    int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualify(connection, "provider_model")).append(" WHERE provider_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(providerId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (").append(d.ilikeClause("model_id"))
                    .append(" OR ").append(d.ilikeClause("display_name")).append(")");
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
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<ProviderModelRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("模型列表查询失败", e);
        }
    }

    public long countByProvider(Connection connection, UUID providerId, String keyword,
                                Boolean supportStream, Boolean enabled) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "provider_model")).append(" WHERE provider_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(providerId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (").append(d.ilikeClause("model_id"))
                    .append(" OR ").append(d.ilikeClause("display_name")).append(")");
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
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("模型计数失败", e);
        }
    }

    private ProviderModelRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new ProviderModelRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "provider_id"),
                rs.getString("model_id"),
                rs.getString("display_name"),
                rs.getString("model_type"),
                rs.getString("tokenizer_family"),
                getLongOrNull(rs, "context_window"),
                getLongOrNull(rs, "max_output_tokens"),
                (Boolean) rs.getObject("support_stream"),
                (Boolean) rs.getObject("support_system_message"),
                (Boolean) rs.getObject("support_temperature"),
                (Boolean) rs.getObject("support_top_p"),
                (Boolean) rs.getObject("support_stop"),
                rs.getObject("temperature_min") == null ? null : rs.getBigDecimal("temperature_min"),
                rs.getObject("temperature_max") == null ? null : rs.getBigDecimal("temperature_max"),
                rs.getObject("top_p_min") == null ? null : rs.getBigDecimal("top_p_min"),
                rs.getObject("top_p_max") == null ? null : rs.getBigDecimal("top_p_max"),
                getIntOrNull(rs, "max_stop_sequences"),
                getIntOrNull(rs, "max_stop_length"),
                rs.getObject("default_temperature") == null ? null : rs.getBigDecimal("default_temperature"),
                rs.getObject("default_top_p") == null ? null : rs.getBigDecimal("default_top_p"),
                getLongOrNull(rs, "default_max_tokens"),
                fromJson(rs.getString("default_stop")),
                rs.getBigDecimal("input_price"),
                rs.getBigDecimal("output_price"),
                rs.getInt("price_unit"),
                rs.getString("currency"),
                rs.getBoolean("enabled"),
                rs.getString("import_source"),
                rs.getString("import_adapter_version"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
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

    private static String insertPlaceholders(String columns, DatabaseDialect d) {
        String[] parts = columns.split(", ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if ("default_stop".equals(parts[i].trim())) {
                sb.append(d.jsonPlaceholder());
            } else {
                sb.append("?");
            }
        }
        return sb.toString();
    }
}
