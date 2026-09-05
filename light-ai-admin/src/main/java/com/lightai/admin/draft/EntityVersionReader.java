package com.lightai.admin.draft;

import java.sql.Connection;

/**
 * 实体当前版本读取端口（BE-006 乐观版本）。
 * 返回 null 表示对象不存在或已删除（OBJECT_NOT_FOUND）；
 * CREATE 流程不提供本端口，跳过版本比对。
 */
@FunctionalInterface
public interface EntityVersionReader {

    Long currentVersion(Connection connection);
}
