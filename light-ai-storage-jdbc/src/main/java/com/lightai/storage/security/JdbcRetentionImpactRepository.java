package com.lightai.storage.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** retention_impact JDBC 实现（DATABASE_PLAN §39）。 */
public final class JdbcRetentionImpactRepository implements RetentionImpactRepository {

    private static final String COLUMNS = """
            id, impact_version, draft_revision, target_values, counts, estimated_at, expires_at, estimated_by""";

    private final String schemaName;

    public JdbcRetentionImpactRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcRetentionImpactRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public void insert(Connection connection, RetentionImpactRecord record) {
        String sql = "INSERT INTO " + qualified() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, record.id());
            statement.setObject(2, record.impactVersion());
            statement.setLong(3, record.draftRevision());
            statement.setString(4, record.targetValuesJson());
            statement.setString(5, record.countsJson());
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
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE impact_version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, impactVersion);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RetentionImpactRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("impact_version", UUID.class),
                        rs.getLong("draft_revision"),
                        rs.getString("target_values"),
                        rs.getString("counts"),
                        offset(rs.getTimestamp("estimated_at")),
                        offset(rs.getTimestamp("expires_at")),
                        rs.getString("estimated_by")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("retention_impact 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private String qualified() {
        return schemaName + ".retention_impact";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
