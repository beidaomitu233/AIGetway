package com.lightai.admin.draft;

/** 配置写事务结果：新草稿修订号与实体最新版本。 */
public record DraftWriteResult(long draftRevision, long entityVersion) {
}
