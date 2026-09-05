package com.lightai.storage.schema;

/**
 * 迁移执行端口：由数据库执行方的迁移模块实现（如 Flyway 装配）。
 * 后端不自带迁移文件，MIGRATE 模式缺少该实现时启动失败并给出明确原因。
 */
public interface SchemaMigrator {

    /** 执行版本化迁移；不得修改已发布迁移。 */
    void migrate();
}
