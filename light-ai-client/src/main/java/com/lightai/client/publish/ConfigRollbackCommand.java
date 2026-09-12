package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 配置回滚命令（BE-233；PRD 9.5）。
 * target_snapshot_no 为空时回滚到当前活动版本的上一个成功发布来源；
 * reason 必填；idempotency_key 作用域为回滚操作+目标快照，重复提交返回既有记录。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfigRollbackCommand(
        Long targetSnapshotNo,
        String reason,
        String idempotencyKey) {
}
