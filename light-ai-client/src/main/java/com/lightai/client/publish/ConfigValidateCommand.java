package com.lightai.client.publish;

/**
 * 固定修订校验命令（BACKEND_PLAN 2 协议字典）：draft_revision 正整数或 0。
 * 校验期间不阻止其他管理员编辑；草稿修订变化后原校验失效（4.5.2.1）。
 */
public record ConfigValidateCommand(long draftRevision) {

    public ConfigValidateCommand {
        if (draftRevision < 0) {
            throw new IllegalArgumentException("draft_revision 必须为正整数或 0");
        }
    }
}
