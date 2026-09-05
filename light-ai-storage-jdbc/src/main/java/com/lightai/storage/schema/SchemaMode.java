package com.lightai.storage.schema;

/** 启动 schema-mode（BE-003）：VALIDATE 校验既有结构，MIGRATE 执行迁移后再校验。 */
public enum SchemaMode {
    VALIDATE,
    MIGRATE
}
