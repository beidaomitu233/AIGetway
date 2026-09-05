package com.lightai.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储装配属性：schema 归属独立 light_ai schema（DATABASE_PLAN 第 1 节），
 * schema-mode 见 SchemaMode（BE-003）。
 */
@ConfigurationProperties(prefix = "light-ai.storage")
public class StorageProperties {

    private boolean enabled = true;
    private String schemaName = "light_ai";
    private String schemaMode = "VALIDATE";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getSchemaMode() {
        return schemaMode;
    }

    public void setSchemaMode(String schemaMode) {
        this.schemaMode = schemaMode;
    }
}
