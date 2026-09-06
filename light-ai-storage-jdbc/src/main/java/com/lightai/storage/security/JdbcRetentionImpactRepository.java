package com.lightai.storage.security;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** retention_impact JDBC 实现（DATABASE_PLAN §39）。 */
public final class JdbcRetentionImpactRepository extends AbstractJdbcRepository implements RetentionImpactRepository {

    private static final String COLUMNS = """
            id, impact_version, draft_revision, target_values, counts, estimated_at, expires_at, estimated_by""";

    public JdbcRetentionImpactRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcRetentionImpactRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcRetentionImpactRepository() {
        super();
    }

    @Override
    public void insert(Connection connection, RetentionImpactRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "retention_impact") + " (" + COLUMNS + ") VALUES (?,?,?,"
                + d.jsonPlaceholder() + "," + d.jsonPlaceholder() + ",?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, record.id());
            d.bindUuid(statement, 2, record.impactVersion());
            statement.setLong(3, record.draftRevision());
            d.bindJson(statement, 4, record.targetValuesJson());
            d.bindJson(statement, 5, record.countsJson());
            statement.setTimestamp(6, Timestamp.from(record.estimatedAt().toInstant()));
            statement.setTimestamp(7, Timestamp.from(record.expiresAt().toInstant()));
            statement.setString(8, record.estimatedBy());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("retention_impact 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<RetentionImpactRecord> find(Connection connection, UUID impactVersion) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "retention_impact") + " WHERE impact_version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, impactVersion);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RetentionImpactRecord(
                        d.readUuid(rs, "id"),
                        d.readUuid(rs, "impact_version"),
                        rs.getLong("draft_revision"),
                        d.readJson(rs, "target_values"),
                        d.readJson(rs, "counts"),
                        d.readOffsetDateTime(rs, "estimated_at"),
                        d.readOffsetDateTime(rs, "expires_at"),
                        rs.getString("estimated_by")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("retention_impact 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }
}

