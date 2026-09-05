# 轻享 AI 管理协议契约（BE-P01 冻结基线 + BE-P02 交付）

本目录为执行阶段冻结的公共协议契约夹具。每个 API 有唯一 method+path（`ApiCatalog` 为代码内唯一来源）；
BE-P01 交付共享错误信封、分页结构与 GET /admin/bootstrap；BE-P02 交付 Provider 与凭证池管理接口
（BACKEND_PLAN 4.2.9.1/4.2.9.2 中的 GET/POST/PUT/DELETE /admin/providers*、/admin/credential-pools*），
DTO 与前端 FE-007~FE-012 对齐（light-ai-client 的 provider/pool/impact 包为代码内来源）。
后续任务包在各自交付时按同一风格追加，不独立变更字段语义。

## 已冻结口径

| 项 | 口径 | 来源 |
|---|---|---|
| 管理成功结构 | `{data: T}` | PROJECT_DOCUMENT 第 3 节 |
| 管理失败结构 | `{error: UnifiedError}` | PROJECT_DOCUMENT 第 3 节 |
| 字段命名 | 全字段 snake_case | BACKEND_PLAN 第 2 节 |
| 未知写入字段 | 严格拒绝（FAIL_ON_UNKNOWN_PROPERTIES） | BACKEND_PLAN 第 2 节 |
| 金额/大数 | 十进制字符串传输，JS 侧禁止 Number 化运算 | PROJECT_DOCUMENT 第 3 节 |
| 时间 | UTC 存储，ISO 8601 带偏移传输 | PROJECT_DOCUMENT 第 3 节 |
| 错误码→HTTP | BACKEND_PLAN 4.7.3 全表，代码内来源 `ErrorCode` | BACKEND_PLAN 4.7.3 |
| UnifiedError.type | invalid_request_error / authentication_error / permission_error / not_found_error / conflict_error / rate_limit_error / timeout_error / cancelled_error / api_error | C-015 补充假设（待确认） |
| 权限码/角色码 | `资源.动作` 点分格式；SYSTEM_ADMIN/OPERATOR/DEVELOPER/VIEWER | C-022（后端已对齐，待冻结确认） |
| CSRF 请求头 | X-CSRF-Token；会话认证时 bootstrap 返回 csrf_token | C-022 |
| 请求关联头 | X-Request-Id（服务端回显，缺失时生成 UUID） | 前端 http.ts 口径 |
| 列表排序参数 | `列 方向`（asc/desc），列命中白名单，PRD 4.3.5.5 | ListQuerySupport |
| 分页边界 | page 从 1 起；page_size 默认 20，范围 1—100 | PRD 4.3.5.5 |
| PageResult | items/total/page/page_size/sort/query_started_at/data_updated_at | PROJECT_DOCUMENT 第 3 节 |
| bootstrap adapters | 仅在装配 AdapterMetadataSource 且非空时输出 | BACKEND_PLAN 检测与适配器元数据补充 |

## GET /admin/bootstrap

权限：已认证管理身份（未认证 403 ACCESS_DENIED）。

```json
{
  "data": {
    "user": {"id": "op-1", "display_name": "系统管理员"},
    "roles": ["SYSTEM_ADMIN"],
    "permissions": ["overview.view", "provider.manage"],
    "application_scope": ["console"],
    "allowed_alias_ids": [],
    "runtime_mode": "EMBEDDED",
    "ui_base_path": "",
    "admin_api_base_path": "",
    "timezone": "Asia/Shanghai",
    "current_snapshot_no": 12,
    "draft_revision": 5,
    "draft_change_count": 3,
    "csrf_token": "仅会话认证时返回",
    "adapters": [{"provider_type": "OPENAI", "adapter_version": "1.0.0",
                   "default_base_url": "https://api.openai.com/v1",
                   "tokenizer_families": ["o200k"], "capabilities": ["CHAT", "STREAM"],
                   "provider_option_specs": []}]
  }
}
```

失败样例（未认证）：

```json
{
  "error": {
    "code": "ACCESS_DENIED",
    "type": "permission_error",
    "message": "管理身份未认证或无权限",
    "retryable": false,
    "request_id": "b7c9d1e0-3f2a-4c8b-9d0e-1234567890ab"
  }
}
```

## 错误样例（版本冲突，供前端夹具）

```json
{
  "error": {
    "code": "CONFIG_VERSION_CONFLICT",
    "type": "conflict_error",
    "message": "配置对象版本已变化，请刷新后重试",
    "retryable": false,
    "request_id": "req-err-1",
    "current_version": 7
  }
}
```

## 机器可读规范

`light-ai-protocol.yaml` 为 OpenAPI 3.1 子集（共享 schema + bootstrap path）。
以 Java 常量（`ErrorCode`/`Permissions`/`Roles`/`ApiCatalog`）与本文档为双重来源；
不一致时以代码常量与本 README 表格核对后登记 COMMUNICATION.md。
