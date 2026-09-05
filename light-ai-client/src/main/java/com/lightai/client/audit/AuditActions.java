package com.lightai.client.audit;

/**
 * 管理审计动作词汇（DATABASE_PLAN audit_log.action）。
 * 人工命令受理使用独立 action，不声称状态已变更。
 */
public final class AuditActions {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String ENABLE = "ENABLE";
    public static final String DISABLE = "DISABLE";
    public static final String DELETE = "DELETE";
    public static final String ROTATE = "ROTATE";
    public static final String CHECK = "CHECK";
    public static final String REVERT = "REVERT";
    public static final String VALIDATE = "VALIDATE";
    public static final String PUBLISH = "PUBLISH";
    public static final String DIAGNOSTIC_READ = "DIAGNOSTIC_READ";
    public static final String CIRCUIT_COMMAND = "CIRCUIT_COMMAND";

    private AuditActions() {
    }
}
