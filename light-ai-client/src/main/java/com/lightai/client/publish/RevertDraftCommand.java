package com.lightai.client.publish;

/**
 * 单项撤销命令（BACKEND_PLAN 2 协议字典）：version、draft_revision、reason 1—500。
 * version 为目标草稿对象当前版本；draft_revision 为页面读取的全局修订。
 */
public record RevertDraftCommand(long version, long draftRevision, String reason) {

    public RevertDraftCommand {
        if (version < 1) {
            throw new IllegalArgumentException("version 必须为正整数");
        }
        if (draftRevision < 0) {
            throw new IllegalArgumentException("draft_revision 不能为负数");
        }
        requireReason(reason);
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("reason 必填且长度 1—500");
        }
    }
}
