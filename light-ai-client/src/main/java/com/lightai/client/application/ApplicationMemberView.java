package com.lightai.client.application;

/**
 * 应用成员（PRD 9.2.7）。成员来源于企业身份系统，平台侧只读展示其角色。
 * role 目前为 OWNER 或 VIEWER；成员维护方式仍属 PRD 待确认事项。
 */
public record ApplicationMemberView(
        String id,
        String subjectId,
        String subjectName,
        String role) {
}
