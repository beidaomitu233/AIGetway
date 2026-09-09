CREATE TABLE IF NOT EXISTS application_key_model_permission (
    id VARCHAR(36) PRIMARY KEY,
    created_at DATETIME(6) NOT NULL,
    application_key_id VARCHAR(36) NOT NULL,
    virtual_model_id VARCHAR(36) NOT NULL,
    UNIQUE KEY uk_application_key_virtual_model (application_key_id, virtual_model_id),
    KEY idx_application_key_model_key (application_key_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
