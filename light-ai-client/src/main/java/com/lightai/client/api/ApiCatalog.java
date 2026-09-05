package com.lightai.client.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全量接口目录（BACKEND_PLAN 附录与 2.1 补充 API）。
 * 契约冻结基线：每个 API 有唯一 method+path；静态段路由（如 /export、/default）
 * 按约定在 {id} 变量路由之前匹配。
 */
public final class ApiCatalog {

    /** 接口清单项。module 为任务包归属模块，仅作分组说明。 */
    public record ApiEndpoint(String module, String method, String path) {
    }

    private static final List<ApiEndpoint> ALL = build();

    public static List<ApiEndpoint> all() {
        return List.copyOf(ALL);
    }

    /**
     * 校验目录内不存在重复 method+归一化路径；模板变量名不同但结构相同视为同一资源。
     * 返回冲突列表；空列表表示目录唯一性成立。
     */
    public static List<String> findConflicts() {
        Set<String> seen = new HashSet<>();
        List<String> conflicts = new ArrayList<>();
        for (ApiEndpoint endpoint : ALL) {
            String key = endpoint.method() + " " + normalize(endpoint.path());
            if (!seen.add(key)) {
                conflicts.add(key);
            }
        }
        return conflicts;
    }

    /** 归一化路径：将 {任意变量} 替换为 {}，使 /{poolId}/credentials 与 /{id}/credentials 可比。 */
    public static String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    private static List<ApiEndpoint> build() {
        List<ApiEndpoint> list = new ArrayList<>();
        add(list, "BOOTSTRAP", "GET", "/admin/bootstrap");

        // Provider 与池（BE-P02）
        add(list, "PROVIDER", "GET", "/admin/providers");
        add(list, "PROVIDER", "POST", "/admin/providers");
        add(list, "PROVIDER", "GET", "/admin/providers/{id}");
        add(list, "PROVIDER", "PUT", "/admin/providers/{id}");
        add(list, "PROVIDER", "DELETE", "/admin/providers/{id}");
        add(list, "PROVIDER", "GET", "/admin/providers/{id}/impact");
        add(list, "PROVIDER", "POST", "/admin/providers/{id}/check");
        add(list, "PROVIDER", "POST", "/admin/providers/{id}/enable");
        add(list, "PROVIDER", "POST", "/admin/providers/{id}/disable");
        add(list, "PROVIDER", "GET", "/admin/providers/{id}/available-models");
        add(list, "POOL", "GET", "/admin/credential-pools");
        add(list, "POOL", "POST", "/admin/credential-pools");
        add(list, "POOL", "GET", "/admin/credential-pools/{id}");
        add(list, "POOL", "PUT", "/admin/credential-pools/{id}");
        add(list, "POOL", "DELETE", "/admin/credential-pools/{id}");
        add(list, "POOL", "GET", "/admin/credential-pools/{id}/impact");
        add(list, "POOL", "POST", "/admin/credential-pools/{id}/enable");
        add(list, "POOL", "POST", "/admin/credential-pools/{id}/disable");

        // Credential（BE-P03）
        add(list, "CREDENTIAL", "GET", "/admin/credential-pools/{poolId}/credentials");
        add(list, "CREDENTIAL", "POST", "/admin/credential-pools/{poolId}/credentials");
        add(list, "CREDENTIAL", "GET", "/admin/credentials/{id}");
        add(list, "CREDENTIAL", "PUT", "/admin/credentials/{id}");
        add(list, "CREDENTIAL", "DELETE", "/admin/credentials/{id}");
        add(list, "CREDENTIAL", "POST", "/admin/credentials/{id}/rotate");
        add(list, "CREDENTIAL", "POST", "/admin/credentials/{id}/check");
        add(list, "CREDENTIAL", "POST", "/admin/credentials/{id}/enable");
        add(list, "CREDENTIAL", "POST", "/admin/credentials/{id}/disable");

        // Provider Model / 批量检测（BE-P03）
        add(list, "MODEL", "GET", "/admin/provider-models");
        add(list, "MODEL", "POST", "/admin/provider-models");
        add(list, "MODEL", "GET", "/admin/provider-models/{id}");
        add(list, "MODEL", "PUT", "/admin/provider-models/{id}");
        add(list, "MODEL", "DELETE", "/admin/provider-models/{id}");
        add(list, "MODEL", "GET", "/admin/provider-models/{id}/impact");
        add(list, "MODEL", "POST", "/admin/provider-models/{id}/enable");
        add(list, "MODEL", "POST", "/admin/provider-models/{id}/disable");
        add(list, "MODEL", "POST", "/admin/provider-models/{id}/check");
        add(list, "MODEL", "POST", "/admin/provider-models/import");
        add(list, "MODEL", "POST", "/admin/provider-models/batch-check");
        add(list, "MODEL", "GET", "/admin/batch-check-jobs/{id}");
        add(list, "MODEL", "POST", "/admin/batch-check-jobs/{id}/cancel");

        // Alias 与候选（BE-P03）
        add(list, "ALIAS", "GET", "/admin/model-aliases");
        add(list, "ALIAS", "POST", "/admin/model-aliases");
        add(list, "ALIAS", "GET", "/admin/model-aliases/{id}");
        add(list, "ALIAS", "PUT", "/admin/model-aliases/{id}");
        add(list, "ALIAS", "DELETE", "/admin/model-aliases/{id}");
        add(list, "ALIAS", "GET", "/admin/model-aliases/{id}/impact");
        add(list, "ALIAS", "POST", "/admin/model-aliases/{id}/enable");
        add(list, "ALIAS", "POST", "/admin/model-aliases/{id}/disable");
        add(list, "ALIAS", "GET", "/admin/model-aliases/{id}/candidates");
        add(list, "ALIAS", "POST", "/admin/model-aliases/{id}/candidates");
        add(list, "ALIAS", "PUT", "/admin/model-aliases/{id}/candidates/reorder");
        add(list, "CANDIDATE", "GET", "/admin/route-candidates/{id}");
        add(list, "CANDIDATE", "PUT", "/admin/route-candidates/{id}");
        add(list, "CANDIDATE", "DELETE", "/admin/route-candidates/{id}");
        add(list, "CANDIDATE", "POST", "/admin/route-candidates/{id}/check");

        // 运行治理（BE-P04）
        add(list, "LIMIT", "GET", "/admin/limit-policies");
        add(list, "LIMIT", "POST", "/admin/limit-policies");
        add(list, "LIMIT", "GET", "/admin/limit-policies/{id}");
        add(list, "LIMIT", "PUT", "/admin/limit-policies/{id}");
        add(list, "LIMIT", "DELETE", "/admin/limit-policies/{id}");
        add(list, "LIMIT", "POST", "/admin/limit-policies/{id}/enable");
        add(list, "LIMIT", "POST", "/admin/limit-policies/{id}/disable");
        add(list, "LIMIT", "GET", "/admin/limit-policies/{id}/usage");
        add(list, "LIMIT", "GET", "/admin/limit-policies/{id}/queue");
        add(list, "RELIABILITY", "GET", "/admin/reliability-policies");
        add(list, "RELIABILITY", "POST", "/admin/reliability-policies");
        add(list, "RELIABILITY", "GET", "/admin/reliability-policies/default");
        add(list, "RELIABILITY", "GET", "/admin/reliability-policies/{id}");
        add(list, "RELIABILITY", "PUT", "/admin/reliability-policies/{id}");
        add(list, "RELIABILITY", "DELETE", "/admin/reliability-policies/{id}");
        add(list, "RELIABILITY", "POST", "/admin/reliability-policies/{id}/enable");
        add(list, "RELIABILITY", "POST", "/admin/reliability-policies/{id}/disable");
        add(list, "RELIABILITY", "GET", "/admin/reliability-policies/{id}/recovery-decisions");
        add(list, "CIRCUIT", "GET", "/admin/circuits");
        add(list, "CIRCUIT", "GET", "/admin/circuits/{id}");
        add(list, "CIRCUIT", "GET", "/admin/circuits/{id}/events");
        add(list, "CIRCUIT", "POST", "/admin/circuits/{id}/open");
        add(list, "CIRCUIT", "POST", "/admin/circuits/{id}/recover");
        add(list, "CIRCUIT", "POST", "/admin/circuits/{id}/probe");

        // 调用观测（BE-P06）
        add(list, "OVERVIEW", "GET", "/admin/overview/filters");
        add(list, "OVERVIEW", "GET", "/admin/overview/summary");
        add(list, "OVERVIEW", "GET", "/admin/overview/trends");
        add(list, "OVERVIEW", "GET", "/admin/overview/exceptions");
        add(list, "TRACE", "GET", "/admin/traces");
        add(list, "TRACE", "GET", "/admin/traces/export");
        add(list, "TRACE", "GET", "/admin/traces/{traceId}");
        add(list, "USAGE", "GET", "/admin/usage/summary");
        add(list, "USAGE", "GET", "/admin/usage/trends");
        add(list, "USAGE", "GET", "/admin/usage/groups");
        add(list, "USAGE", "GET", "/admin/usage/export");

        // 草稿与发布（BE-P07）
        add(list, "DRAFT", "GET", "/admin/config/draft-state");
        add(list, "DRAFT", "GET", "/admin/config/draft-changes/summary");
        add(list, "DRAFT", "GET", "/admin/config/draft-changes");
        add(list, "DRAFT", "POST", "/admin/config/draft-changes/{entityType}/{entityId}/revert");
        add(list, "DRAFT", "POST", "/admin/config/draft-changes/revert-all");
        add(list, "PUBLISH", "POST", "/admin/config/validate");
        add(list, "PUBLISH", "POST", "/admin/config/publish");
        add(list, "PUBLISH", "GET", "/admin/config/publish-records");
        add(list, "PUBLISH", "GET", "/admin/config/publish-records/{id}");
        add(list, "PUBLISH", "GET", "/admin/config/snapshots/{snapshotNo}/summary");
        add(list, "RUNTIME", "GET", "/admin/runtime-instances");

        // 运行与安全管理（BE-P08）
        add(list, "RUNTIME", "GET", "/admin/runtime-config");
        add(list, "RUNTIME", "PUT", "/admin/runtime-config");
        add(list, "RUNTIME", "POST", "/admin/runtime-config/retention-impact");
        add(list, "ACCESS", "GET", "/admin/access-credentials");
        add(list, "ACCESS", "POST", "/admin/access-credentials");
        add(list, "ACCESS", "GET", "/admin/access-credentials/{id}");
        add(list, "ACCESS", "PUT", "/admin/access-credentials/{id}");
        add(list, "ACCESS", "DELETE", "/admin/access-credentials/{id}");
        add(list, "ACCESS", "POST", "/admin/access-credentials/{id}/rotate");
        add(list, "ACCESS", "POST", "/admin/access-credentials/{id}/enable");
        add(list, "ACCESS", "POST", "/admin/access-credentials/{id}/disable");
        add(list, "AUDIT", "GET", "/admin/audit-logs");
        add(list, "AUDIT", "GET", "/admin/audit-logs/export");
        add(list, "AUDIT", "GET", "/admin/audit-logs/{id}");
        add(list, "DEVELOPER", "GET", "/admin/developer-access/context");
        add(list, "DEVELOPER", "GET", "/admin/developer-access/code-sample");
        add(list, "DEVELOPER", "POST", "/admin/developer-access/test/chat");
        add(list, "DEVELOPER", "POST", "/admin/developer-access/test/chat/stream");

        // 内部实例接口（BE-P07）
        add(list, "INTERNAL", "POST", "/internal/runtime-instances/heartbeat");
        add(list, "INTERNAL", "GET", "/internal/config-snapshots/{snapshotNo}");
        add(list, "INTERNAL", "POST", "/internal/publish-records/{publishId}/instances/{instanceId}/reports");

        // 业务接口（BE-P05）与健康检查（BE-P10）
        add(list, "BUSINESS", "GET", "/v1/models");
        add(list, "BUSINESS", "POST", "/v1/chat/completions");
        add(list, "HEALTH", "GET", "/health/live");
        add(list, "HEALTH", "GET", "/health/ready");

        return List.copyOf(list);
    }

    private static void add(List<ApiEndpoint> list, String module, String method, String path) {
        list.add(new ApiEndpoint(module, method, path));
    }

    private ApiCatalog() {
    }
}
