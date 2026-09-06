package com.lightai.storage.publish;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * config_validation 仓储端口（BE-039）。
 * 校验与问题同事务写入；EXPIRED 由服务层惰性落定。
 */
public interface ConfigValidationRepository {

    void insert(Connection connection, ConfigValidationRecord record,
                List<ConfigValidationIssueRecord> issues);

    Optional<ValidationWithIssues> find(Connection connection, UUID validationId);

    void markUsed(Connection connection, UUID validationId, UUID publishId);

    void sweepExpired(Connection connection, OffsetDateTime now);

    /** 校验记录与问题集合。 */
    record ValidationWithIssues(ConfigValidationRecord validation,
                                List<ConfigValidationIssueRecord> issues) {
    }
}
