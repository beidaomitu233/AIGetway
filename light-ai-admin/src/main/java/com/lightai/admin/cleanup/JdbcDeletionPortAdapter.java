package com.lightai.admin.cleanup;

import com.lightai.storage.cleanup.RetentionDeletionRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;

/**
 * RetentionCleanupService.DeletionPort 的 JDBC 实现适配器（BE-048 / CR-014）。
 */
public class JdbcDeletionPortAdapter implements RetentionCleanupService.DeletionPort {

    private final DataSource dataSource;
    private final RetentionDeletionRepository repository;

    public JdbcDeletionPortAdapter(DataSource dataSource, RetentionDeletionRepository repository) {
        this.dataSource = dataSource;
        this.repository = repository;
    }

    @Override
    public List<String> findExpiredTraceIds(OffsetDateTime cutoff, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            return repository.findExpiredTraceIds(connection, cutoff, limit);
        } catch (Exception e) {
            throw new RuntimeException("findExpiredTraceIds failed", e);
        }
    }

    @Override
    public int deleteTraceDetails(List<String> traceIds) {
        try (Connection connection = dataSource.getConnection()) {
            return repository.deleteTraceDetails(connection, traceIds);
        } catch (Exception e) {
            throw new RuntimeException("deleteTraceDetails failed", e);
        }
    }

    @Override
    public int deleteTraces(List<String> traceIds) {
        try (Connection connection = dataSource.getConnection()) {
            return repository.deleteTraces(connection, traceIds);
        } catch (Exception e) {
            throw new RuntimeException("deleteTraces failed", e);
        }
    }

    @Override
    public long deleteExpiredAudit(OffsetDateTime cutoff) {
        try (Connection connection = dataSource.getConnection()) {
            return repository.deleteExpiredAudit(connection, cutoff);
        } catch (Exception e) {
            throw new RuntimeException("deleteExpiredAudit failed", e);
        }
    }

    @Override
    public long deleteExpiredSamples(OffsetDateTime cutoff) {
        try (Connection connection = dataSource.getConnection()) {
            return repository.deleteExpiredSamples(connection, cutoff);
        } catch (Exception e) {
            throw new RuntimeException("deleteExpiredSamples failed", e);
        }
    }

    @Override
    public long deleteExpiredUsage(OffsetDateTime cutoff) {
        try (Connection connection = dataSource.getConnection()) {
            return repository.deleteExpiredUsage(connection, cutoff);
        } catch (Exception e) {
            throw new RuntimeException("deleteExpiredUsage failed", e);
        }
    }

    @Override
    public long pendingAggregationEvents() {
        try (Connection connection = dataSource.getConnection()) {
            return repository.pendingAggregationEvents(connection);
        } catch (Exception e) {
            return 0;
        }
    }
}
