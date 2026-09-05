package com.lightai.storage.draft;

import java.sql.Connection;

/**
 * 草稿差异仓储端口。upsert 需在业务事务内调用；
 * 返回 true 表示新增差异（change_count 同事务 +1），false 表示覆盖既有差异。
 */
public interface DraftChangeRepository {

    boolean upsert(Connection connection, DraftChangeRecord record);
}
