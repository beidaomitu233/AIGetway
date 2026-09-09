package com.lightai.storage.application;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 企业应用、当前治理策略和模型授权的 JDBC 仓储。 */
public final class JdbcApplicationRepository extends AbstractJdbcRepository {

    private static final String APPLICATION_COLUMNS =
            "id, code, name, department, owner_id, owner_name, environment, description, "
                    + "status, last_called_at, version, created_at, updated_at";
    private static final String QUOTA_COLUMNS =
            "id, application_id, token_limit, amount_limit, currency, rpm, tpm, period_type, "
                    + "period_start, period_end, tokens_used, tokens_reserved, amount_used, "
                    + "amount_reserved, version, created_at, updated_at";

    public JdbcApplicationRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcApplicationRepository() {
        super();
    }

    public boolean existsByCode(Connection connection, String code) {
        String sql = "SELECT 1 FROM " + qualify(connection, "application") + " WHERE code = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw translate("应用编码检查失败", e);
        }
    }

    public List<String> findCodesForSubject(Connection connection, String subjectId) {
        String sql = "SELECT a.code FROM " + qualify(connection, "application") + " a JOIN "
                + qualify(connection, "application_member")
                + " m ON m.application_id = a.id WHERE m.subject_id = ? ORDER BY a.code";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, subjectId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> codes = new ArrayList<>();
                while (resultSet.next()) codes.add(resultSet.getString(1));
                return List.copyOf(codes);
            }
        } catch (SQLException e) {
            throw translate("应用成员范围读取失败", e);
        }
    }

    public void insert(Connection connection, ApplicationRecord record) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "application")
                + " (" + APPLICATION_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + dialect.nowFunction() + ", " + dialect.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, record.id());
            statement.setString(2, record.code());
            statement.setString(3, record.name());
            statement.setString(4, record.department());
            statement.setString(5, record.ownerId());
            statement.setString(6, record.ownerName());
            statement.setString(7, record.environment());
            statement.setString(8, record.description());
            statement.setString(9, record.status());
            bindTime(statement, 10, record.lastCalledAt());
            statement.setLong(11, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("应用写入失败", e);
        }
    }

    public void insertOwner(Connection connection, UUID applicationId, String ownerId, String ownerName) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "application_member")
                + " (id, created_at, updated_at, application_id, subject_id, subject_name, role) "
                + "VALUES (?, " + dialect.nowFunction() + ", " + dialect.nowFunction() + ", ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, UUID.randomUUID());
            dialect.bindUuid(statement, 2, applicationId);
            statement.setString(3, ownerId);
            statement.setString(4, ownerName);
            statement.setString(5, "OWNER");
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("应用负责人写入失败", e);
        }
    }

    public void replaceOwner(Connection connection, UUID applicationId, String ownerId, String ownerName) {
        DatabaseDialect dialect = dialect(connection);
        String deleteSql = "DELETE FROM " + qualify(connection, "application_member")
                + " WHERE application_id = ? AND role = 'OWNER'";
        try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            dialect.bindUuid(statement, 1, applicationId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("应用负责人更新失败", e);
        }
        insertOwner(connection, applicationId, ownerId, ownerName);
    }

    public void insertQuota(Connection connection, ApplicationQuotaRecord record) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "application_quota_policy")
                + " (" + QUOTA_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + dialect.nowFunction() + ", " + dialect.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, record.id());
            dialect.bindUuid(statement, 2, record.applicationId());
            bindLong(statement, 3, record.tokenLimit());
            bindDecimal(statement, 4, record.amountLimit());
            statement.setString(5, record.currency());
            bindInteger(statement, 6, record.rpm());
            bindLong(statement, 7, record.tpm());
            statement.setString(8, record.periodType());
            bindTime(statement, 9, record.periodStart());
            bindTime(statement, 10, record.periodEnd());
            statement.setLong(11, record.tokensUsed());
            statement.setLong(12, record.tokensReserved());
            statement.setBigDecimal(13, record.amountUsed());
            statement.setBigDecimal(14, record.amountReserved());
            statement.setLong(15, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("应用治理策略写入失败", e);
        }
    }

    public void updateQuota(Connection connection, ApplicationQuotaRecord record, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_quota_policy")
                + " SET token_limit = ?, amount_limit = ?, currency = ?, rpm = ?, tpm = ?, "
                + "period_type = ?, period_start = ?, period_end = ?, version = version + 1, updated_at = "
                + dialect.nowFunction() + " WHERE application_id = ? AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindLong(statement, 1, record.tokenLimit());
            bindDecimal(statement, 2, record.amountLimit());
            statement.setString(3, record.currency());
            bindInteger(statement, 4, record.rpm());
            bindLong(statement, 5, record.tpm());
            statement.setString(6, record.periodType());
            bindTime(statement, 7, record.periodStart());
            bindTime(statement, 8, record.periodEnd());
            dialect.bindUuid(statement, 9, record.applicationId());
            statement.setLong(10, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
        } catch (SQLException e) {
            throw translate("应用治理策略更新失败", e);
        }
    }

    public void resetUsage(
            Connection connection, UUID applicationId, boolean tokenUsage, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String column = tokenUsage ? "tokens_used" : "amount_used";
        String sql = "UPDATE " + qualify(connection, "application_quota_policy")
                + " SET " + column + " = 0, version = version + 1, updated_at = "
                + dialect.nowFunction() + " WHERE application_id = ? AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            statement.setLong(2, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
        } catch (SQLException e) {
            throw translate("应用用量重置失败", e);
        }
    }

    public void insertModelPermission(Connection connection, UUID applicationId, UUID virtualModelId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "application_model_permission")
                + " (id, created_at, updated_at, version, application_id, virtual_model_id, enabled, constraints_json) "
                + "VALUES (?, " + dialect.nowFunction() + ", " + dialect.nowFunction() + ", 1, ?, ?, ?, "
                + dialect.jsonPlaceholder() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, UUID.randomUUID());
            dialect.bindUuid(statement, 2, applicationId);
            dialect.bindUuid(statement, 3, virtualModelId);
            statement.setBoolean(4, true);
            dialect.bindJson(statement, 5, "{}");
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("应用模型授权写入失败", e);
        }
    }

    public void updateModelPermission(Connection connection, UUID id, boolean enabled, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_model_permission")
                + " SET enabled = ?, version = version + 1, updated_at = " + dialect.nowFunction()
                + " WHERE id = ? AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, enabled);
            dialect.bindUuid(statement, 2, id);
            statement.setLong(3, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
        } catch (SQLException e) {
            throw translate("应用模型授权更新失败", e);
        }
    }

    public void bumpApplicationVersion(Connection connection, UUID id, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application")
                + " SET version = version + 1, updated_at = " + dialect.nowFunction()
                + " WHERE id = ? AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, id);
            statement.setLong(2, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
        } catch (SQLException e) {
            throw translate("应用版本更新失败", e);
        }
    }

    public Optional<ApplicationRecord> findById(Connection connection, UUID id) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT " + APPLICATION_COLUMNS + " FROM " + qualify(connection, "application")
                + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapApplication(resultSet, dialect)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("应用读取失败", e);
        }
    }

    public Optional<ApplicationQuotaRecord> findQuota(Connection connection, UUID applicationId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT " + QUOTA_COLUMNS + " FROM "
                + qualify(connection, "application_quota_policy") + " WHERE application_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapQuota(resultSet, dialect)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("应用治理策略读取失败", e);
        }
    }

    public ApplicationQuotaRecord lockQuota(Connection connection, UUID applicationId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT " + QUOTA_COLUMNS + " FROM "
                + qualify(connection, "application_quota_policy") + " WHERE application_id = ? "
                + dialect.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new IllegalStateException("应用缺少额度策略");
                return mapQuota(resultSet, dialect);
            }
        } catch (SQLException e) {
            throw translate("应用治理策略锁定失败", e);
        }
    }

    public Optional<QuotaAdjustmentRecord> findAdjustment(
            Connection connection, UUID applicationId, String idempotencyKey) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT id, application_id, dimension, before_value, delta_value, "
                + "after_value, reason, effective_at, operator_id, idempotency_key, created_at FROM "
                + qualify(connection, "quota_adjustment")
                + " WHERE application_id=? AND idempotency_key=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            statement.setString(2, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(mapAdjustment(resultSet, dialect)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("应用额度调整流水读取失败", e);
        }
    }

    public List<QuotaAdjustmentRecord> listAdjustments(
            Connection connection, UUID applicationId, int limit) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT id, application_id, dimension, before_value, delta_value, "
                + "after_value, reason, effective_at, operator_id, idempotency_key, created_at FROM "
                + qualify(connection, "quota_adjustment") + " WHERE application_id=? "
                + "ORDER BY created_at DESC, id DESC " + dialect.limitOffsetClause(limit, 0);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<QuotaAdjustmentRecord> records = new ArrayList<>();
                while (resultSet.next()) records.add(mapAdjustment(resultSet, dialect));
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("应用额度调整流水读取失败", e);
        }
    }

    public void insertAdjustment(Connection connection, QuotaAdjustmentRecord record) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "quota_adjustment")
                + " (id, created_at, application_id, dimension, before_value, delta_value, "
                + "after_value, reason, effective_at, operator_id, idempotency_key) VALUES (?, "
                + dialect.nowFunction() + ", ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, record.id());
            dialect.bindUuid(statement, 2, record.applicationId());
            statement.setString(3, record.dimension());
            bindDecimal(statement, 4, record.beforeValue());
            bindDecimal(statement, 5, record.deltaValue());
            bindDecimal(statement, 6, record.afterValue());
            statement.setString(7, record.reason());
            bindTime(statement, 8, record.effectiveAt());
            statement.setString(9, record.operatorId());
            statement.setString(10, record.idempotencyKey());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("应用额度调整流水写入失败", e);
        }
    }

    public List<ApplicationModelPermissionRecord> listModelPermissions(
            Connection connection, UUID applicationId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT p.id, p.application_id, p.virtual_model_id, a.alias AS virtual_model_code, "
                + "p.enabled, p.constraints_json, p.version, p.created_at, p.updated_at FROM "
                + qualify(connection, "application_model_permission") + " p LEFT JOIN "
                + qualify(connection, "model_alias") + " a ON a.id = p.virtual_model_id "
                + "WHERE p.application_id = ? ORDER BY a.alias, p.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ApplicationModelPermissionRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new ApplicationModelPermissionRecord(
                            dialect.readUuid(resultSet, "id"), dialect.readUuid(resultSet, "application_id"),
                            dialect.readUuid(resultSet, "virtual_model_id"), resultSet.getString("virtual_model_code"),
                            resultSet.getBoolean("enabled"), dialect.readJson(resultSet, "constraints_json"),
                            resultSet.getLong("version"), dialect.readOffsetDateTime(resultSet, "created_at"),
                            dialect.readOffsetDateTime(resultSet, "updated_at")));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("应用模型授权读取失败", e);
        }
    }

    public List<ApplicationRecord> list(Connection connection, Filter filter, String sort,
                                        int limit, long offset) {
        DatabaseDialect dialect = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(APPLICATION_COLUMNS)
                .append(" FROM ").append(qualify(connection, "application")).append(" WHERE 1=1");
        List<Object> values = new ArrayList<>();
        appendFilter(sql, values, filter, dialect);
        sql.append(" ORDER BY ").append(sort).append(", id ASC ")
                .append(dialect.limitOffsetClause(limit, offset));
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, values, dialect);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ApplicationRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(mapApplication(resultSet, dialect));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("应用列表读取失败", e);
        }
    }

    public long count(Connection connection, Filter filter) {
        DatabaseDialect dialect = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "application")).append(" WHERE 1=1");
        List<Object> values = new ArrayList<>();
        appendFilter(sql, values, filter, dialect);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, values, dialect);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("应用数量读取失败", e);
        }
    }

    public long countEnabledModels(Connection connection, UUID applicationId) {
        return countByApplication(connection, "application_model_permission", applicationId,
                " AND enabled = true");
    }

    public long countActiveKeys(Connection connection, UUID applicationId) {
        return countByApplication(connection, "application_key", applicationId,
                " AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > "
                        + dialect(connection).nowFunction() + ")");
    }

    public ApplicationRecord update(Connection connection, ApplicationRecord record, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application")
                + " SET name=?, department=?, owner_id=?, owner_name=?, environment=?, description=?, "
                + "version=version+1, updated_at=" + dialect.nowFunction() + " WHERE id=? AND version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.name());
            statement.setString(2, record.department());
            statement.setString(3, record.ownerId());
            statement.setString(4, record.ownerName());
            statement.setString(5, record.environment());
            statement.setString(6, record.description());
            dialect.bindUuid(statement, 7, record.id());
            statement.setLong(8, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(connection, record.id()).orElseThrow();
        } catch (OptimisticLockException e) {
            throw e;
        } catch (SQLException e) {
            throw translate("应用更新失败", e);
        }
    }

    public ApplicationRecord updateStatus(Connection connection, UUID id, String status,
                                          long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application")
                + " SET status=?, version=version+1, updated_at=" + dialect.nowFunction()
                + " WHERE id=? AND version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            dialect.bindUuid(statement, 2, id);
            statement.setLong(3, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
            return findById(connection, id).orElseThrow();
        } catch (OptimisticLockException e) {
            throw e;
        } catch (SQLException e) {
            throw translate("应用状态更新失败", e);
        }
    }

    private long countByApplication(Connection connection, String table, UUID applicationId,
                                    String suffix) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT count(*) FROM " + qualify(connection, table)
                + " WHERE application_id = ?" + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("应用关联数量读取失败", e);
        }
    }

    private void appendFilter(StringBuilder sql, List<Object> values, Filter filter,
                              DatabaseDialect dialect) {
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND (").append(dialect.ilikeClause("name"))
                    .append(" OR ").append(dialect.ilikeClause("code")).append(")");
            String keyword = "%" + filter.keyword().trim() + "%";
            values.add(keyword);
            values.add(keyword);
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND status = ?");
            values.add(filter.status());
        }
        if (filter.environment() != null && !filter.environment().isBlank()) {
            sql.append(" AND environment = ?");
            values.add(filter.environment());
        }
        if (filter.ownerId() != null && !filter.ownerId().isBlank()) {
            sql.append(" AND owner_id = ?");
            values.add(filter.ownerId());
        }
        if (filter.allowedCodes() != null && !filter.allowedCodes().isEmpty()) {
            sql.append(" AND code IN (").append(inPlaceholders(filter.allowedCodes().size())).append(")");
            values.addAll(filter.allowedCodes());
        }
    }

    private ApplicationRecord mapApplication(ResultSet resultSet, DatabaseDialect dialect)
            throws SQLException {
        return new ApplicationRecord(
                dialect.readUuid(resultSet, "id"), resultSet.getString("code"),
                resultSet.getString("name"), resultSet.getString("department"),
                resultSet.getString("owner_id"), resultSet.getString("owner_name"),
                resultSet.getString("environment"), resultSet.getString("description"),
                resultSet.getString("status"), dialect.readOffsetDateTime(resultSet, "last_called_at"),
                resultSet.getLong("version"), dialect.readOffsetDateTime(resultSet, "created_at"),
                dialect.readOffsetDateTime(resultSet, "updated_at"));
    }

    private ApplicationQuotaRecord mapQuota(ResultSet resultSet, DatabaseDialect dialect)
            throws SQLException {
        return new ApplicationQuotaRecord(
                dialect.readUuid(resultSet, "id"), dialect.readUuid(resultSet, "application_id"),
                getLongOrNull(resultSet, "token_limit"), resultSet.getBigDecimal("amount_limit"),
                resultSet.getString("currency"), getIntOrNull(resultSet, "rpm"),
                getLongOrNull(resultSet, "tpm"), resultSet.getString("period_type"),
                dialect.readOffsetDateTime(resultSet, "period_start"),
                dialect.readOffsetDateTime(resultSet, "period_end"),
                resultSet.getLong("tokens_used"), resultSet.getLong("tokens_reserved"),
                resultSet.getBigDecimal("amount_used"), resultSet.getBigDecimal("amount_reserved"),
                resultSet.getLong("version"), dialect.readOffsetDateTime(resultSet, "created_at"),
                dialect.readOffsetDateTime(resultSet, "updated_at"));
    }

    private QuotaAdjustmentRecord mapAdjustment(ResultSet resultSet, DatabaseDialect dialect)
            throws SQLException {
        return new QuotaAdjustmentRecord(
                dialect.readUuid(resultSet, "id"),
                dialect.readUuid(resultSet, "application_id"),
                resultSet.getString("dimension"),
                resultSet.getBigDecimal("before_value"),
                resultSet.getBigDecimal("delta_value"),
                resultSet.getBigDecimal("after_value"),
                resultSet.getString("reason"),
                dialect.readOffsetDateTime(resultSet, "effective_at"),
                resultSet.getString("operator_id"),
                resultSet.getString("idempotency_key"),
                dialect.readOffsetDateTime(resultSet, "created_at"));
    }

    private static void bindLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    private static void bindInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    private static void bindDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) statement.setNull(index, Types.DECIMAL); else statement.setBigDecimal(index, value);
    }

    private static void bindTime(PreparedStatement statement, int index, OffsetDateTime value) throws SQLException {
        if (value == null) statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE); else statement.setObject(index, value);
    }

    public record Filter(String keyword, String status, String environment, String ownerId,
                         List<String> allowedCodes) {
    }

    public static final class OptimisticLockException extends RuntimeException {
    }
}
