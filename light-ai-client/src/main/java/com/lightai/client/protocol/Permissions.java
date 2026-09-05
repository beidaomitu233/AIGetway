package com.lightai.client.protocol;

import java.util.Set;

/**
 * 权限码字典：与前端 light-ai-admin-ui/src/app/permissions.ts 保持一致（C-022）。
 * 码值采用"资源.动作"点分格式，只在此冻结，不在业务代码中硬编码字符串。
 */
public final class Permissions {

    public static final String OVERVIEW_VIEW = "overview.view";

    public static final String PROVIDER_VIEW = "provider.view";
    public static final String PROVIDER_MANAGE = "provider.manage";
    public static final String PROVIDER_CHECK = "provider.check";

    public static final String CREDENTIAL_VIEW = "credential.view";
    public static final String CREDENTIAL_MANAGE = "credential.manage";
    public static final String CREDENTIAL_CHECK = "credential.check";

    public static final String MODEL_VIEW = "model.view";
    public static final String MODEL_MANAGE = "model.manage";
    public static final String MODEL_IMPORT = "model.import";

    public static final String ALIAS_VIEW = "alias.view";
    public static final String ALIAS_MANAGE = "alias.manage";

    public static final String LIMIT_VIEW = "limit.view";
    public static final String LIMIT_MANAGE = "limit.manage";

    public static final String RELIABILITY_VIEW = "reliability.view";
    public static final String RELIABILITY_MANAGE = "reliability.manage";

    public static final String CIRCUIT_VIEW = "circuit.view";
    public static final String CIRCUIT_OPERATE = "circuit.operate";

    public static final String TRACE_VIEW = "trace.view";
    public static final String TRACE_DIAGNOSTICS = "trace.diagnostics";
    public static final String TRACE_EXPORT = "trace.export";

    public static final String USAGE_VIEW = "usage.view";
    public static final String USAGE_EXPORT = "usage.export";

    public static final String DRAFT_VIEW = "draft.view";
    public static final String DRAFT_REVERT = "draft.revert";

    public static final String PUBLISH_VIEW = "publish.view";
    public static final String PUBLISH_MANAGE = "publish.manage";

    public static final String RUNTIME_CONFIG_VIEW = "runtimeconfig.view";
    public static final String RUNTIME_CONFIG_MANAGE = "runtimeconfig.manage";

    public static final String ACCESS_VIEW = "access.view";
    public static final String ACCESS_MANAGE = "access.manage";

    public static final String AUDIT_VIEW = "audit.view";
    public static final String AUDIT_EXPORT = "audit.export";

    public static final String DEVELOPER_VIEW = "developer.view";
    public static final String DEVELOPER_TEST = "developer.test";

    private Permissions() {
    }

    /** 全量权限码集合，供角色矩阵与契约测试引用。 */
    public static Set<String> all() {
        return Set.of(
                OVERVIEW_VIEW,
                PROVIDER_VIEW, PROVIDER_MANAGE, PROVIDER_CHECK,
                CREDENTIAL_VIEW, CREDENTIAL_MANAGE, CREDENTIAL_CHECK,
                MODEL_VIEW, MODEL_MANAGE, MODEL_IMPORT,
                ALIAS_VIEW, ALIAS_MANAGE,
                LIMIT_VIEW, LIMIT_MANAGE,
                RELIABILITY_VIEW, RELIABILITY_MANAGE,
                CIRCUIT_VIEW, CIRCUIT_OPERATE,
                TRACE_VIEW, TRACE_DIAGNOSTICS, TRACE_EXPORT,
                USAGE_VIEW, USAGE_EXPORT,
                DRAFT_VIEW, DRAFT_REVERT,
                PUBLISH_VIEW, PUBLISH_MANAGE,
                RUNTIME_CONFIG_VIEW, RUNTIME_CONFIG_MANAGE,
                ACCESS_VIEW, ACCESS_MANAGE,
                AUDIT_VIEW, AUDIT_EXPORT,
                DEVELOPER_VIEW, DEVELOPER_TEST);
    }
}
