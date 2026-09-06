package com.lightai.client.publish;

/**
 * 全量撤销命令（BACKEND_PLAN 2 协议字典）：confirmation_text 固定 REVERT ALL。
 * 全文还原原子完成（4.5.1.5），任一对象恢复失败全部回滚。
 */
public record RevertAllDraftCommand(long draftRevision, String confirmationText, String reason) {

    public static final String CONFIRMATION_TEXT = "REVERT ALL";

    public RevertAllDraftCommand {
        if (draftRevision < 0) {
            throw new IllegalArgumentException("draft_revision 不能为负数");
        }
        if (!CONFIRMATION_TEXT.equals(confirmationText)) {
            throw new IllegalArgumentException("confirmation_text 必须为 " + CONFIRMATION_TEXT);
        }
        RevertDraftCommand.requireReason(reason);
    }
}
