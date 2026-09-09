CREATE TABLE IF NOT EXISTS light_ai.application_key_model_permission (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    application_key_id UUID NOT NULL,
    virtual_model_id UUID NOT NULL,
    CONSTRAINT uk_application_key_virtual_model UNIQUE (application_key_id, virtual_model_id)
);
CREATE INDEX IF NOT EXISTS idx_application_key_model_key
    ON light_ai.application_key_model_permission(application_key_id);
