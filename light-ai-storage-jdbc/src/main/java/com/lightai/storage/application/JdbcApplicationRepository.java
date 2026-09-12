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
import java.util.Map;
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

    /** 应用成员只读列表；按角色与主体标识稳定排序（PRD 9.2.7）。 */
    public List<ApplicationMemberRecord> listMembers(Connection connection, UUID applicationId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT id, application_id, subject_id, subject_name, role, created_at, updated_at FROM "
                + qualify(connection, "application_member")
                + " WHERE application_id = ? ORDER BY role, subject_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ApplicationMemberRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new ApplicationMemberRecord(
                            dialect.readUuid(resultSet, "id"),
                            dialect.readUuid(resultSet, "application_id"),
                            resultSet.getString("subject_id"),
                            resultSet.getString("subject_name"),
                            resultSet.getString("role"),
                            dialect.readOffsetDateTime(resultSet, "created_at"),
                            dialect.readOffsetDateTime(resultSet, "updated_at")));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("应用成员读取失败", e);
        }
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

    public void insertModelPermission(Connection connection, UUID applicationId, UUID virtualModelId,
                                      String constraintsJson) {
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
            dialect.bindJson(statement, 5, normalizeConstraints(constraintsJson));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("应用模型授权写入失败", e);
        }
    }

    public void updateModelPermission(Connection connection, UUID id, boolean enabled,
                                      String constraintsJson, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_model_permission")
                + " SET enabled = ?, constraints_json = ?, version = version + 1, updated_at = "
                + dialect.nowFunction() + " WHERE id = ? AND version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, enabled);
            dialect.bindJson(statement, 2, normalizeConstraints(constraintsJson));
            dialect.bindUuid(statement, 3, id);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
        } catch (SQLException e) {
            throw translate("应用模型授权更新失败", e);
        }
    }

    private static String normalizeConstraints(String constraintsJson) {
        return constraintsJson == null || constraintsJson.isBlank() ? "{}" : constraintsJson;
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
        String sql = "SELECT p.id, p.application_id, p.virtual_model_id, a.code AS virtual_model_code, "
                + "p.enabled, p.constraints_json, p.version, p.created_at, p.updated_at FROM "
                + qualify(connection, "application_model_permission") + " p LEFT JOIN "
                + qualify(connection, "virtual_model") + " a ON a.id = p.virtual_model_id "
                + "WHERE p.application_id = ? ORDER BY a.code, p.id";
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
        StringBuilder sql = new StringBuilder("SELECT ");
        boolean joinQuota = filter.budgetStatus() != null && !filter.budgetStatus().isBlank();
        if (joinQuota) {
            // JOIN 后 id 等列名有歧义，SELECT 列必须限定表名
            sql.append(qualifyColumns(connection, APPLICATION_COLUMNS));
        } else {
            sql.append(APPLICATION_COLUMNS);
        }
        sql.append(" FROM ").append(qualify(connection, "application"));
        if (joinQuota) {
            sql.append(" LEFT JOIN ").append(qualify(connection, "application_quota_policy"))
                    .append(" q ON q.application_id = ").append(qualify(connection, "application"))
                    .append(".id");
        }
        sql.append(" WHERE 1=1");
        List<Object> values = new ArrayList<>();
        appendFilter(sql, values, filter, dialect);
        sql.append(" ORDER BY ").append(orderExpression(sort)).append(", ")
                .append(qualify(connection, "application")).append(".id ASC ")
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

    private String qualifyColumns(Connection connection, String columns) {
        String table = qualify(connection, "application");
        return java.util.Arrays.stream(columns.split(","))
                .map(String::trim)
                .map(column -> table + "." + column)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * BE-P20-001：默认 last_called_at desc 时空值排在末尾，保证未调用过的应用不压倒活跃应用。
     */
    private static String orderExpression(String sort) {
        String trimmed = sort.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("last_called_at")) {
            String column = trimmed.substring(0, trimmed.indexOf(' ') < 0
                    ? trimmed.length() : trimmed.indexOf(' '));
            String direction = lower.contains(" asc") ? "ASC" : "DESC";
            return "(" + column + " IS NULL) ASC, " + column + " " + direction;
        }
        return trimmed;
    }

    public long count(Connection connection, Filter filter) {
        DatabaseDialect dialect = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "application"));
        if (filter.budgetStatus() != null && !filter.budgetStatus().isBlank()) {
            sql.append(" LEFT JOIN ").append(qualify(connection, "application_quota_policy"))
                    .append(" q ON q.application_id = ").append(qualify(connection, "application"))
                    .append(".id");
        }
        sql.append(" WHERE 1=1");
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

    /**
     * BE-P20-001：按本页应用 ID 集合批量读取额度策略，替代逐应用查询。
     */
    public Map<UUID, ApplicationQuotaRecord> findQuotas(Connection connection,
                                                        java.util.Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) return Map.of();
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT " + QUOTA_COLUMNS + " FROM "
                + qualify(connection, "application_quota_policy")
                + " WHERE application_id IN (" + inPlaceholders(applicationIds.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (UUID id : applicationIds) dialect.bindUuid(statement, index++, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<UUID, ApplicationQuotaRecord> result = new java.util.LinkedHashMap<>();
                while (resultSet.next()) {
                    ApplicationQuotaRecord record = mapQuota(resultSet, dialect);
                    result.put(record.applicationId(), record);
                }
                return java.util.Collections.unmodifiableMap(result);
            }
        } catch (SQLException e) {
            throw translate("应用额度批量读取失败", e);
        }
    }

    /**
     * 授权且运行可用的模型数（BE-P20-001）：授权启用、虚拟模型启用未删除且存在启用的路由候选。
     */
    public Map<UUID, Long> countRoutableEnabledModels(Connection connection,
                                                      java.util.Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) return Map.of();
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT p.application_id, COUNT(DISTINCT p.virtual_model_id) AS model_count FROM "
                + qualify(connection, "application_model_permission") + " p"
                + " JOIN " + qualify(connection, "model_alias")
                + " a ON a.id = p.virtual_model_id AND a.enabled = true AND a.deleted_at IS NULL"
                + " JOIN " + qualify(connection, "route_candidate")
                + " c ON c.alias_id = a.id AND c.enabled = true AND c.deleted_at IS NULL"
                + " WHERE p.enabled = true AND p.application_id IN ("
                + inPlaceholders(applicationIds.size()) + ") GROUP BY p.application_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (UUID id : applicationIds) dialect.bindUuid(statement, index++, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<UUID, Long> result = new java.util.LinkedHashMap<>();
                while (resultSet.next()) {
                    result.put(dialect.readUuid(resultSet, "application_id"), resultSet.getLong("model_count"));
                }
                return java.util.Collections.unmodifiableMap(result);
            }
        } catch (SQLException e) {
            throw translate("应用运行可用模型批量统计失败", e);
        }
    }

    /** 按应用 ID 集合批量统计当前有效密钥数量。 */
    public Map<UUID, Long> countActiveKeysBatch(Connection connection,
                                                java.util.Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) return Map.of();
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT application_id, COUNT(*) AS key_count FROM "
                + qualify(connection, "application_key")
                + " WHERE status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > "
                + dialect.nowFunction() + ") AND application_id IN ("
                + inPlaceholders(applicationIds.size()) + ") GROUP BY application_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (UUID id : applicationIds) dialect.bindUuid(statement, index++, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<UUID, Long> result = new java.util.LinkedHashMap<>();
                while (resultSet.next()) {
                    result.put(dialect.readUuid(resultSet, "application_id"), resultSet.getLong("key_count"));
                }
                return java.util.Collections.unmodifiableMap(result);
            }
        } catch (SQLException e) {
            throw translate("应用有效密钥批量统计失败", e);
        }
    }

    /**
     * 归档与准入互斥（BE-P20-001）：锁定应用行供事务内复核状态与占用。
     * 准入侧 lockAndValidateKey 以同行为锁对象，两者在行锁上串行化。
     */
    public Optional<ApplicationRecord> lockById(Connection connection, UUID id) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT " + APPLICATION_COLUMNS + " FROM "
                + qualify(connection, "application") + " WHERE id = ? " + dialect.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapApplication(resultSet, dialect)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("应用行锁读取失败", e);
        }
    }

    /** 归档前置检查：仍在有效期内的预占数量；终态（RELEASED 等）不计入。 */
    public long countActiveReservations(Connection connection, UUID applicationId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT COUNT(*) FROM " + qualify(connection, "budget_reservation")
                + " WHERE application_id = ? AND status = 'ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("应用预占数量读取失败", e);
        }
    }

    /** 授权写入校验（BE-P20-003）：虚拟模型必须存在启用的路由候选才可授权。 */
    public boolean existsEnabledCandidate(Connection connection, UUID aliasId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT COUNT(*) FROM " + qualify(connection, "route_candidate")
                + " WHERE alias_id = ? AND enabled = true AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, aliasId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw translate("路由候选存在性读取失败", e);
        }
    }

    /** 24h 运行摘要（BE-P20-001）：requests 为窗口内全部请求，terminal/succeeded 用于成功率。 */
    public Map<String, TraceSummary> summarize24h(Connection connection, java.util.Collection<String> codes,
                                                  OffsetDateTime from, OffsetDateTime to) {
        if (codes.isEmpty()) return Map.of();
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT application, COUNT(*) AS requests, "
                + "SUM(CASE WHEN status IN ('SUCCEEDED','FAILED','STREAM_INTERRUPTED') THEN 1 ELSE 0 END) AS terminal, "
                + "SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded FROM "
                + qualify(connection, "trace")
                + " WHERE started_at >= ? AND started_at < ? AND application IN ("
                + inPlaceholders(codes.size()) + ") GROUP BY application";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, from);
            statement.setObject(2, to);
            int index = 3;
            for (String code : codes) statement.setString(index++, code);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, TraceSummary> result = new java.util.LinkedHashMap<>();
                while (resultSet.next()) {
                    result.put(resultSet.getString("application"), new TraceSummary(
                            resultSet.getLong("requests"), resultSet.getLong("terminal"),
                            resultSet.getLong("succeeded")));
                }
                return java.util.Collections.unmodifiableMap(result);
            }
        } catch (SQLException e) {
            throw translate("应用运行摘要读取失败", e);
        }
    }

    /** 影响预览（FE-P20 补充契约）：返回前 limit+1 条有效密钥 ID，调用方以超量判定 has_more。 */
    public List<UUID> listActiveKeyIds(Connection connection, UUID applicationId, int limit) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT id FROM " + qualify(connection, "application_key")
                + " WHERE application_id = ? AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > "
                + dialect.nowFunction() + ") ORDER BY id " + dialect.limitOffsetClause(limit, 0);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (resultSet.next()) ids.add(dialect.readUuid(resultSet, "id"));
                return List.copyOf(ids);
            }
        } catch (SQLException e) {
            throw translate("应用密钥清单读取失败", e);
        }
    }

    /**
     * 模型移除影响（FE-P20 补充契约）：受影响密钥为无独立模型范围（继承应用授权）
     * 或范围命中被移除模型的有效密钥。返回前 limit+1 条用于 has_more 判定。
     */
    public List<UUID> listAffectedKeyIdsForModelRemoval(Connection connection, UUID applicationId,
                                                        java.util.Collection<UUID> removedModelIds,
                                                        int limit) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT k.id FROM " + qualify(connection, "application_key") + " k"
                + " WHERE k.application_id = ? AND k.status = 'ACTIVE'"
                + " AND (k.expires_at IS NULL OR k.expires_at > " + dialect.nowFunction() + ")"
                + " AND (NOT EXISTS (SELECT 1 FROM " + qualify(connection, "application_key_model_permission")
                + " kp WHERE kp.application_key_id = k.id)"
                + (removedModelIds.isEmpty() ? ""
                : " OR EXISTS (SELECT 1 FROM " + qualify(connection, "application_key_model_permission")
                + " kp2 WHERE kp2.application_key_id = k.id AND kp2.virtual_model_id IN ("
                + inPlaceholders(removedModelIds.size()) + "))")
                + ") ORDER BY k.id " + dialect.limitOffsetClause(limit, 0);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            int index = 2;
            for (UUID modelId : removedModelIds) dialect.bindUuid(statement, index++, modelId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (resultSet.next()) ids.add(dialect.readUuid(resultSet, "id"));
                return List.copyOf(ids);
            }
        } catch (SQLException e) {
            throw translate("模型移除影响读取失败", e);
        }
    }

    /** 应用 24h 运行摘要。 */
    public record TraceSummary(long requests, long terminal, long succeeded) {
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
        if (filter.department() != null && !filter.department().isBlank()) {
            sql.append(" AND department = ?");
            values.add(filter.department().trim());
        }
        if (filter.budgetStatus() != null && !filter.budgetStatus().isBlank()) {
            sql.append(" AND CASE WHEN q.token_limit IS NULL AND q.amount_limit IS NULL")
                    .append(" THEN 'UNLIMITED'")
                    .append(" WHEN (q.token_limit IS NOT NULL AND q.tokens_used + q.tokens_reserved >= q.token_limit)")
                    .append(" OR (q.amount_limit IS NOT NULL AND q.amount_used + q.amount_reserved >= q.amount_limit)")
                    .append(" THEN 'EXHAUSTED' ELSE 'NORMAL' END = ?");
            values.add(filter.budgetStatus().trim().toUpperCase());
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
                         String department, String budgetStatus, List<String> allowedCodes) {

        public Filter(String keyword, String status, String environment, String ownerId,
                      List<String> allowedCodes) {
            this(keyword, status, environment, ownerId, null, null, allowedCodes);
        }
    }

    public static final class OptimisticLockException extends RuntimeException {
    }
}
