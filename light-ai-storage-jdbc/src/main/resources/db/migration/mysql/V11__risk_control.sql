-- P3 风险控制策略、关键词、应用白名单与命中事件。
CREATE TABLE IF NOT EXISTS risk_policy_revision (
    id VARCHAR(36) PRIMARY KEY, created_at DATETIME(6) NOT NULL, version BIGINT NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL, keyword_action VARCHAR(16) NOT NULL, anomaly_window_seconds INT NOT NULL,
    anomaly_request_threshold BIGINT, anomaly_token_threshold BIGINT, anomaly_amount_threshold DECIMAL(20,8),
    anomaly_block_seconds INT NOT NULL, whitelist_mode VARCHAR(16) NOT NULL, content_json TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS risk_policy (
    id VARCHAR(36) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1, enabled BOOLEAN NOT NULL DEFAULT FALSE,
    keyword_action VARCHAR(16) NOT NULL DEFAULT 'BLOCK',
    anomaly_window_seconds INT NOT NULL DEFAULT 60,
    anomaly_request_threshold BIGINT, anomaly_token_threshold BIGINT,
    anomaly_amount_threshold DECIMAL(20,8), anomaly_block_seconds INT NOT NULL DEFAULT 300,
    whitelist_mode VARCHAR(16) NOT NULL DEFAULT 'OFF'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS risk_keyword_rule (
    id VARCHAR(36) PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    policy_id VARCHAR(36) NOT NULL, keyword VARCHAR(128) NOT NULL, match_type VARCHAR(16) NOT NULL DEFAULT 'CONTAINS',
    ignore_case BOOLEAN NOT NULL DEFAULT TRUE, application_id VARCHAR(36), action VARCHAR(16) NOT NULL DEFAULT 'BLOCK',
    enabled BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE KEY uk_risk_keyword_rule (policy_id, keyword, application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS risk_application_whitelist (
    policy_id VARCHAR(36) NOT NULL, application_id VARCHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (policy_id, application_id), KEY idx_risk_whitelist_application (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS risk_event (
    id VARCHAR(36) PRIMARY KEY, created_at DATETIME(6) NOT NULL, application_id VARCHAR(36), request_id VARCHAR(128),
    event_type VARCHAR(32) NOT NULL, action VARCHAR(16) NOT NULL, rule_id VARCHAR(36), reason VARCHAR(255),
    KEY idx_risk_event_created (created_at), KEY idx_risk_event_application (application_id, created_at), KEY idx_risk_event_type (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
