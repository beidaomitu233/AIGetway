package com.lightai.client.protocol;

/**
 * 角色代码（PROJECT_DOCUMENT 2.4.1；C-022 与前端共享的稳定字符串）。
 */
public final class Roles {

    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String APPLICATION_OWNER = "APPLICATION_OWNER";
    public static final String AUDITOR = "AUDITOR";
    /** 旧身份适配器兼容角色，后续部署迁移到 APPLICATION_OWNER。 */
    public static final String DEVELOPER = "DEVELOPER";
    /** 旧身份适配器兼容角色，后续部署迁移到 AUDITOR。 */
    public static final String VIEWER = "VIEWER";

    private Roles() {
    }
}
