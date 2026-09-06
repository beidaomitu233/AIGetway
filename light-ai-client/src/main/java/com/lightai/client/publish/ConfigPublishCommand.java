package com.lightai.client.publish;

import java.util.List;

/**
 * 发布命令（BACKEND_PLAN 2 协议字典）。发布人取当前管理身份，不接收 operator_id。
 * acknowledged_warning_ids 必须覆盖校验结果全部 WARNING；版本条件不满足返回
 * CONFIG_VALIDATION_EXPIRED / CONFIG_DRAFT_CHANGED，均不创建 PublishRecord（C-007）。
 */
public record ConfigPublishCommand(
        String validationId,
        long draftRevision,
        List<String> acknowledgedWarningIds,
        String publishNote) {

    public ConfigPublishCommand {
        if (validationId == null || validationId.isBlank()) {
            throw new IllegalArgumentException("validation_id 必填");
        }
        if (draftRevision < 0) {
            throw new IllegalArgumentException("draft_revision 不能为负数");
        }
        acknowledgedWarningIds = acknowledgedWarningIds == null ? List.of() : List.copyOf(acknowledgedWarningIds);
        if (publishNote != null && publishNote.length() > 500) {
            throw new IllegalArgumentException("publish_note 长度上限 500");
        }
    }
}
