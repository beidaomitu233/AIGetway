# 轻享 AI V2.0 后端执行计划

## 1. 执行边界

开发基线统一为 origin/dev。BE-P20 可从 TASK_STATUS.md 领取；BE-201/202 首先处理 COMMUNICATION.md 的 CONTRACT-V2-001，交付应用和密钥的精确字段、响应包装及接口测试供前端使用。身份源等未决项仅限制对应任务；执行环境故障按 ENV-V2-001 验证审批通道。

任务包从 P20 编号，用于避免与既有提交记录中的编号冲突。技术栈沿用 Java 17 字节码、Spring Boot Standalone、模块化 Maven、JDBC、PostgreSQL/MySQL、Redis 原子脚本和 JDK HttpClient。V2 首期权威配置位于集中式服务，公共 client/spi/runtime 不依赖 Spring，Adapter 只承担单次调用与协议映射。

## 2. API 与错误基线

- 业务 API：`GET /v1/models`、`POST /v1/chat/completions`；Bearer 为应用密钥，响应包含 `X-Request-Id`。
- 管理 API：`/admin/applications`、`/admin/channels`、`/admin/upstream-models`、`/admin/virtual-models`、`/admin/config-releases`、`/admin/calls`、`/admin/usage`、`/admin/audit-logs`、`/admin/settings`。
- 错误：`error={code,message,request_id,retryable,details?}`；400 校验、401 业务密钥认证、403 权限、404 对象、409 版本/状态、429 额度/速率、502/503 上游、504 总超时。
- 写接口携带 `version`/ETag；调整、重置、轮换、发布携带幂等键。日志只记录错误分类和 request_id，不记录密钥、认证头、正文、堆栈或上游敏感响应。

## BE-P20 应用、密钥与权限（5 项）

- [ ] 任务编号：BE-201
  模块：应用管理
  目标：复核并完成应用列表、创建、详情、编辑、启停和归档 API。
  接口：`GET/POST /admin/applications`、`GET/PUT /admin/applications/{id}`、状态命令。
  请求/响应：code、name、department、owner、environment、description、status、version 及运行摘要。
  业务流程：校验数据范围 → 唯一 code → 乐观锁写入 → 审计；归档要求已禁用且无运行请求。
  异常处理：重复 code 409、越权 403、版本冲突 409、活动请求阻止归档。
  数据表：application、application_member、audit_log。
  验收/测试：覆盖四角色、跨应用、并发编辑、禁用即时拒绝和归档前置。

- [ ] 任务编号：BE-202
  模块：应用密钥
  目标：完成高熵签发、摘要鉴权、轮换、宽限、禁用、启用、撤销和 IP/CIDR 校验。
  接口：`/admin/applications/{id}/keys/**`；业务鉴权 Bearer。
  响应字段：一次性 secret、key_prefix、status、expires_at、grace_expires_at、version。
  业务流程：生成 → 单向摘要 → 保存 → 一次返回；轮换生成新记录，撤销永久终止。
  异常处理：重复幂等键返回原业务结果但不再暴露 secret；策略越界 400；状态冲突 409。
  数据表：application_key、audit_log。
  验收/测试：数据库无原文；过期/撤销/IP 错误在上游前拒绝；日志脱敏。

- [ ] 任务编号：BE-203
  模块：应用模型权限
  目标：授权已发布且可路由的虚拟模型，并支持应用/密钥级参数收紧。
  接口：`GET/PUT /admin/applications/{id}/models`、`GET /v1/models`。
  请求/响应：virtual_model_id、enabled、max_output_tokens、allow_stream、version。
  业务流程：验证模型活动与路由 → 验证能力边界 → 保存权限 → 新请求即时生效。
  异常处理：无路由、能力越界、应用/密钥范围放宽、版本冲突。
  数据表：application_model_permission、application_key_model_scope、virtual_model。
  验收/测试：`/v1/models` 仅返回授权可用模型；取消授权不终止已准入请求。

- [ ] 任务编号：BE-204
  模块：应用额度与调整
  目标：完成 Token/金额/RPM/TPM、周期、续期、增减和人工重置。
  接口：`GET/PUT /quota`、`POST /quota/adjustments`、`POST /quota/reset`。
  请求/响应：limit、used、reserved、remaining、currency、period、reset_at、version、reason。
  业务流程：校验 → 幂等调整单 → 原子更新当前策略 → 审计；历史账本不改写。
  异常处理：币种冲突、非法周期、减少至已用以下、重复请求、版本冲突。
  数据表：application_quota_policy、quota_adjustment、usage_ledger。
  验收/测试：重复提交不重复加额；降低后立即拒绝新请求；自然周期按统一时区滚动。

- [ ] 任务编号：BE-205
  模块：企业身份与应用成员
  目标：接入确认的企业身份源，建立管理会话、RBAC 和应用成员数据范围。
  接口：登录/回调/退出/bootstrap、`/admin/applications/{id}/members`。
  业务流程：验证身份 → 映射角色/组 → 合并权限与范围 → 请求层和查询层双重执行。
  异常处理：身份源超时、回调非法、无角色、会话过期、跨应用访问。
  数据表：application_member、audit_log；外部身份只存稳定 subject id。
  验收/测试：四角色矩阵、403 审计、应用密钥与管理身份隔离。

## BE-P21 渠道、模型与路由（5 项）

- [ ] 任务编号：BE-211
  模块：渠道管理
  目标：收口 Provider→Channel 资源域，完成渠道 CRUD、状态、影响和检测。
  接口：`/admin/channels/**`。字段：provider_type、base_url、proxy、timeouts、headers、priority、weight、status、health、version。
  业务流程：Provider 能力校验 → URL 安全校验 → 草稿写入或即时状态命令 → 审计。
  异常处理：危险目标/重定向、未知 Provider、活动引用、版本冲突。
  数据表：channel、channel_check_record、draft_change。
  验收/测试：SSRF/DNS 重绑定/TLS、状态与健康分离、禁用影响。

- [ ] 任务编号：BE-212
  模块：渠道多 Key
  目标：完成加密写入、轮换、启停、检测、优先级/权重、Key 级 RPM/TPM、冷却和健康。
  接口：`/admin/channels/{id}/credentials/**`。
  业务流程：受保护写入 → 加密/Secret 引用 → 单 Key 检测 → 调度选择；认证失败只摘除当前 Key。
  异常处理：KMS 不可用 fail-closed、429 冷却、重复名称、最后可用 Key 影响。
  数据表：channel_credential、channel_check_record、audit_log。
  验收/测试：原文不可读出；同级权重分布；单 Key 故障切换。

- [ ] 任务编号：BE-213
  模块：上游模型
  目标：完成同步预览/提交、人工维护、能力与价格校验、人工锁定字段。
  接口：`/admin/upstream-models/**`、渠道同步接口。
  请求/响应：channel_id、model_id、capabilities、context_window、max_output、prices、currency、source、locked_fields、status。
  业务流程：Adapter 获取 → diff → 预览 → 幂等提交；启用前验证完整价格和能力。
  异常处理：渠道不可用、同步冲突、模型下线、价格缺失。
  数据表：upstream_model、model_sync_job/item。
  验收/测试：不自动覆盖锁定字段；同渠道 model_id 唯一。

- [ ] 任务编号：BE-214
  模块：虚拟模型
  目标：提供稳定 code、能力安全交集、应用授权影响和状态管理。
  接口：`/admin/virtual-models/**`。
  业务流程：计算候选能力交集 → 应用显式收紧 → 发布校验 → 对外模型目录。
  异常处理：code 重复、候选能力不相容、无活动路由、被应用引用。
  数据表：virtual_model、application_model_permission、route_candidate。
  验收/测试：不支持显式参数在路由前拒绝；code 不随上游迁移改变。

- [ ] 任务编号：BE-215
  模块：路由候选
  目标：按优先级和同级权重配置候选，完成能力、Key、价格和应用影响校验。
  接口：`/admin/virtual-models/{id}/routes/**`。
  业务流程：重复/匹配校验 → 草稿保存 → 发布校验 → 快照生效。
  异常处理：重复候选、渠道模型不匹配、无 Key、价格缺失、版本冲突。
  数据表：route_candidate、draft_change、config_snapshot。
  验收/测试：零权重不接正常流量；权重不跨优先级；切换建立新 Attempt。

## BE-P22 网关准入、恢复与结算（5 项）

- [ ] 任务编号：BE-221
  模块：统一业务协议
  目标：稳定实现 `GET /v1/models` 与同步/SSE Chat，校验虚拟模型和显式参数。
  请求/响应：OpenAI 兼容字段、Usage、finish_reason、单一 `[DONE]`、`X-Request-Id`。
  业务流程：请求校验 → ApplicationContext → 准入 → Runtime → 统一序列化。
  异常处理：未知字段策略、非法 request_id、客户端取消、流内错误。
  数据表：无直接管理写入；形成 Trace。
  验收/测试：协议兼容、chunk 顺序、单终态、取消传播。

- [ ] 任务编号：BE-222
  模块：原子准入
  目标：同时执行应用/密钥模型、Token、金额、RPM 和 TPM 的原子预占。
  业务流程：保守估算 → Redis 原子窗口/容量 → 数据库 Reservation → 成功放行或完整回滚。
  异常处理：共享状态不可用、预算边界、并发超额、预占写失败。
  数据表：budget_reservation；Redis 应用和密钥限额键。
  验收/测试：治理绕过为 0；其他应用不受影响；429 返回维度和 retry_after。

- [ ] 任务编号：BE-223
  模块：路由与 Key 选择
  目标：过滤状态、健康、权限、能力、容量和价格后，先候选再 Key。
  业务流程：锁定快照 → 候选优先级/权重 → Key 优先级/权重 → 建立 Attempt。
  异常处理：无候选、无 Key、容量不足、快照不一致。
  数据表：config_snapshot、request_attempt；共享健康/容量状态。
  验收/测试：同 request 的每个 Attempt 只有单渠道/模型/Key；选择结果可复现。

- [ ] 任务编号：BE-224
  模块：恢复与流式边界
  目标：统一同 Key 重试、换 Key、同级候选和下一优先级预算。
  业务流程：错误分类 → 检查独立次数与总时限 → 新 Attempt → 成功或终止。
  异常处理：上游认证、429、超时、5xx、流首块后错误。
  数据表：request_attempt、recovery_decision、circuit_state。
  验收/测试：首块后不切换；总时限覆盖所有尝试；健康更新不覆盖人工状态。

- [ ] 任务编号：BE-225
  模块：结算与恢复
  目标：按实际 Usage 和价格快照结算，幂等释放剩余预占并写账本。
  业务流程：锁定价格 → 实际/估算 Usage → 原子补扣/释放 → ledger → Trace 终态。
  异常处理：Usage 缺失、实际超预占、账本写失败、进程崩溃、重复回调。
  数据表：usage_ledger、budget_reservation、request_trace、price_snapshot。
  验收/测试：成功/失败/取消/超时唯一终态；重复扣减 0；恢复任务可收敛。

## BE-P23 观测、发布与交付（5 项）

- [ ] 任务编号：BE-231
  模块：调用与时间线
  目标：按应用范围查询 Trace，并组合准入、路由、Attempt、恢复、流提交、结算和终态。
  接口：`GET /admin/calls`、`GET /admin/calls/{requestId}`、受控导出。
  异常处理：运行中数据、观测延迟、过期、越权。数据表：request_trace、request_attempt、recovery_decision。
  验收/测试：request_id 贯穿；默认无正文；导出字段与权限一致。

- [ ] 任务编号：BE-232
  模块：用量成本
  目标：以 usage_ledger 为事实源，按应用、密钥、模型、渠道和时间聚合。
  接口：`/admin/usage/summary|trend|breakdown|adjustments|export`。
  异常处理：多币种、估算 Usage、聚合延迟、重放事件。
  数据表：usage_ledger、usage_aggregate、quota_adjustment。
  验收/测试：聚合可从账本重算；币种不混加；ACTUAL/ESTIMATED 可区分。

- [ ] 任务编号：BE-233
  模块：配置发布与回滚
  目标：将 Channel/Upstream/Virtual/Route 形成不可变完整快照并使实例收敛。
  接口：`/admin/config-releases/**` 与内部实例拉取/确认接口。
  业务流程：草稿 → 校验 → READY → 激活 → 实例确认；失败保留旧活动版本；回滚生成新记录。
  异常处理：引用、能力、价格、无 Key、实例不兼容、部分加载失败。
  数据表：config_snapshot、publish_record、publish_instance_result、runtime_instance。
  验收/测试：新请求新快照、运行请求旧快照、失败无部分激活。

- [ ] 任务编号：BE-234
  模块：系统设置、审计与安全
  目标：管理时区、留存、诊断、网络策略、告警和运行默认值，并覆盖关键操作审计。
  异常处理：越权、版本冲突、非法安全降级、审计写失败。
  数据表：runtime_setting、audit_log、retention_policy。
  验收/测试：关键操作审计 100%；安全设置默认拒绝；日志/导出扫描无敏感值。

- [ ] 任务编号：BE-235
  模块：后端交付门禁
  目标：完成单测、管理 API、业务协议、权限、事务、并发、流式、迁移、Redis、真实 Provider 和 E2E。
  实现说明：分别报告 PostgreSQL、MySQL、Redis 和 Provider 环境；Mock 不替代真实结果。
  验收标准：应用创建到用量结算闭环；附加延迟、吞吐和流并发只报告实测值。
  测试要求：无未解释跳过；失败项写入 COMMUNICATION 并保持任务未勾选。

## BE-P20 本次执行记录（2026-09-12）

负责人：后端执行模型 codex-be-0912。分支：feature/backend-p20-codex-be-0912。领取提交 ea68d2e 已推送 dev。以下为已验证子项，不等同 BE-201～205 全量验收；主任务均保持未勾选，待确认差异见 COMMUNICATION.md 第 7 节。

| 任务 | 本次结果 | 尚未满足的验收 |
|---|---|---|
| BE-201 | 应用请求体为空统一 400，UUID 严格校验；应用列表/详情/分页/范围接口回归 | 重复 code 409 精确错误码、归档运行请求互斥、列表筛选/摘要与 N+1 |
| BE-202 | 全部密钥接口 data 包装及 snake_case；no-store；非法 ID/请求体 400；签发/启停/版本冲突/旧轮换/撤销 HTTP 回归 | 新记录轮换/宽限/幂等与 secret/key_prefix 契约；本次旧轮换测试仅验证响应与版本，不代表通过 V2 轮换验收 |
| BE-203 | 新增 GET models，复用授权视图与模型查看权限，验证范围和空列表 | 已发布可路由模型、能力交集与 allow_stream 字段收口 |
| BE-204 | 新增 GET quota，复用额度查看权限和 decimal 字符串；缺策略明确 503 | 降低到已用以下、周期历史与自动滚动、PUT 调整账本及剩余额度字段 |
| BE-205 | API 回归验证四角色读取、只读角色拒绝写入、成员/应用数据范围 | 企业身份源、显式敏感授权、跨应用拒绝审计；测试身份不是企业登录实现 |

### 本次可联调接口与当前 DTO 证据

公共前缀 `/admin/applications/{id}`。path id/keyId 必须为标准 UUID。管理身份沿用现有管理认证拦截器；新 GET 在服务层再次校验 `APPLICATION_MODEL_VIEW`/`APPLICATION_QUOTA_VIEW` 和应用范围。接口成功为 `{data: T}`，错误为 `{error: ...}`，字段序列化统一 snake_case。下列现有 DTO 与计划不一致之处仍需架构确认，不据此修改新版产品要求。

| 方法与路径 | 参数位置 | data 类型与状态 | 权限 |
|---|---|---|---|
| GET /models | path id | 200；ApplicationModelPermissionView[]，直接复用详情 models | APPLICATION_MODEL_VIEW + 应用范围 |
| GET /quota | path id | 200；ApplicationQuotaPolicyView，直接复用详情 quota；缺策略 503 | APPLICATION_QUOTA_VIEW + 应用范围 |
| GET /keys | path id | 200；ApplicationKeyView[]，无原文/摘要 | APPLICATION_KEY_VIEW + 应用范围 |
| POST /keys | body ApplicationKeyCreateCommand | 201；ApplicationKeySecretResult，一次返回 key_value | APPLICATION_KEY_MANAGE + 应用范围 |
| POST /keys/{keyId}/rotate | body version（正整数）、reason（必填） | 200；ApplicationKeySecretResult；新记录/幂等契约待确认 | APPLICATION_KEY_MANAGE + 应用范围 |
| POST /keys/{keyId}/status | body status、version、reason | 200；ManagementOperationResult<ApplicationKeyView> | APPLICATION_KEY_MANAGE + 应用范围 |
| POST /keys/{keyId}/revoke | body version、reason | 200；ManagementOperationResult<ApplicationKeyView> | APPLICATION_KEY_MANAGE + 应用范围 |

- models 当前字段：id、virtual_model_id、virtual_model_code（字符串），enabled（布尔），max_output_tokens（可空整数），stream_allowed（可空布尔），version（整数）。这是授权配置，不能作为已发布可调用目录。
- quota 当前字段：id、token_limit（可空整数）、tokens_used、tokens_reserved（整数），amount_limit（可空 decimal 字符串）、amount_used、amount_reserved（decimal 字符串），currency（字符串）、rpm/tpm（可空整数）、period_type、period_start/period_end（可空 ISO 时间）、version。
- key 创建 body：name（必填），ip_allowlist（可选字符串数组），expires_at（可选 ISO 时间），rpm/tpm（可选正整数且不放宽应用），virtual_model_ids（可选 UUID 数组）。结果当前字段 key_id、application_id、key_value、masked_value、issued_at、rotation_generation、version；计划要求的 secret/key_prefix/grace_expires_at 未冒充已交付。
- status 仅 ACTIVE/DISABLED；REVOKED 不可恢复。写操作冲突返回 409 CONFIG_VERSION_CONFLICT/CONFIG_FIELD_IMMUTABLE。非法请求返回 400 FIELD_VALIDATION_FAILED，权限 403 ACCESS_DENIED，不存在 404 OBJECT_NOT_FOUND，读取异常 503 CONFIG_DATA_UNAVAILABLE。
- GET applications 当前 query：page 默认 1，page_size 默认 20/最大 100，sort 白名单默认 updated_at desc，keyword/status/environment/owner_id；data 为 items/total/page/page_size/sort/query_started_at/data_updated_at。部门、预算和 24h 摘要缺口已登记。
- 密钥成功响应 Cache-Control: no-store；解析错误不回传原始字段值。所有管理命令拒绝空白/null 请求体，未知字段及类型错误保持严格拒绝。

### 测试证据

Windows、Temurin Java 17.0.19，Maven 使用 `D:/IntelliJ IDEA 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd`。

1. `mvn -B -pl light-ai-admin -am -Dtest=ApplicationApiContractTest,ApplicationServiceTest,ApplicationKeyServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：16 项通过，无失败/跳过。新增 ApplicationApiContractTest 6 项，使用真实 service/repository、H2 迁移、MockMvc、测试身份上下文。
2. `mvn -B verify`：14 模块构建成功；441 项中 425 通过、16 跳过、0 失败/错误。覆盖 Java 编译类型检查、单元/API 回归和构建。
3. `git diff --check`：通过。仓库未配置独立后端 lint 任务，不将空白检查冒称 lint 通过；未新增框架。
4. 真实环境跳过：MySQL 2 项缺 LAI_IT_MYSQL_URL，PostgreSQL 3 项缺 LAI_IT_DB_URL，Redis 11 项缺 LAI_IT_REDIS_URI/lightai.it.redis-uri。真实 Provider、企业身份登录、前后端首调 E2E 和性能场景未执行。

未修改数据库 schema/迁移及 DATABASE_PLAN 勾选；本次读取 application、application_member、application_model_permission、application_quota_policy，密钥回归使用 application_key 与 audit_log。未提交前端或临时日志。

## BE-P21 本次执行与验收记录（2026-09-12）

负责人 codex-be-0912；领取 BE-211～BE-215，领取提交 45ce9c9；runtime 与凭证 JDBC 修复范围已分别单独登记并普通推送。独立目录 .worktrees/backend-p21-codex-0912。本轮只修改后端和执行文档，无前端与数据库迁移。

| 任务 | 本次交付 | 未满足验收，保持未勾选 |
|---|---|---|
| BE-211 | /admin/channels CRUD/状态/影响/检测路径收口；规范 UUID、鉴权、SSRF、敏感头和版本回归 | 精确 V2 DTO、即时状态/广播及运行影响契约 BE-P21-001 |
| BE-212 | 嵌套 credentials 全操作校验渠道归属；加密插入 SQL 修复；轮换递增 version/secret_version、拒绝旧版本；掩码读取、更新校验和删除存储失败拒绝 | priority/冷却/最后可用 Key 与共享占用互斥 BE-P21-002；真实 Provider 未执行 |
| BE-213 | /admin/upstream-models 和渠道 models；真实 model_id 必填、启用价格完整、路径不可暗换；批量 DTO/归属/事务/连接释放修复 | 同步预览/锁定字段与批量表冲突 BE-P21-003/006，当前合法批量请求明确返回 503 |
| BE-214 | /admin/virtual-models 路径、ID/权限回归 | 能力交集、显式收紧和应用影响 BE-P21-004 |
| BE-215 | 嵌套路由归属、不可更改渠道/模型、重复候选与参数校验；weight=0 保存且运行主选/回退均排除 | 活动快照、真实健康/容量和发布验收 BE-P21-005 |

主要文件：admin 下 ChannelController/Service、ChannelCredentialController/Service、UpstreamModelController/Service、ModelImportService、ModelAliasController/Service、RouteCandidateService、ResourceIds；client/RouteCandidateSaveCommand；runtime/RouteService；storage-jdbc/JdbcChannelCredentialRepository；ResourceApiContractTest、RouteServiceTest。关联表 channel、channel_credential、upstream_model、virtual_model、route_candidate、draft_state、draft_change、audit_log、batch_check_job/item、object_runtime_state、channel_check_record、capacity_reservation_item；未改变表结构或 DATABASE_PLAN 执行状态。

测试环境 Windows / Java 17.0.19 / 项目 Maven 与 JUnit5、MockMvc、H2 迁移、真实 JDBC/服务/AES-GCM。新增 11 项 API 测试及 1 项零权重运行测试，覆盖鉴权、只读角色、非法 ID/空请求、SSRF、敏感头、跨父资源、重复名称/路由、不可变字段、价格、加密轮换与旧版本、审计失败事务回滚、真实 schema 不可用时拒绝成功。

功能目录执行 mvn -B verify：14 模块 BUILD SUCCESS；453 项中 437 通过、16 跳过，0 失败/错误。包含 Java 编译类型检查、单元/API 测试及构建。仓库无独立后端 lint 命令，使用 git diff --check 检查补丁格式，不能冒称独立 lint 通过。MySQL 2、PostgreSQL 3、Redis 11 环境测试因缺 LAI_IT_MYSQL_URL/LAI_IT_DB_URL/LAI_IT_REDIS_URI 跳过；真实 Provider、企业身份、前后端 E2E、多节点容量和性能未执行。H2 验证不能替代真实数据库验收。

COMMUNICATION.md 已登记 BE-P21-001～006，均待确认。BE-211～215 均未达到整项完成标准，不勾选；仅交付以上已验证子项，整包阻塞并保留原负责人，避免其他 Agent 重复实现。后续数据库迁移与契约确认后由原负责人继续验收。
