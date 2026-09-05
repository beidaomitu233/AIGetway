package com.lightai.storage.runtimeconfig;

/** 运行指针只读状态（DATABASE_PLAN runtime_config，current_snapshot_no 不进可编辑 DTO）。 */
public record RuntimeConfigState(long currentSnapshotNo, String timezone) {
}
