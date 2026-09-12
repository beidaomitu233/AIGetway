# 轻享 AI V2.0 后端执行计划

## 1. 执行边界

开发基线统一为 origin/dev。BE-P20 已由 codex-be-0912 领取且保持阻塞，不得重复领取或接管；当前占用以 TASK_STATUS.md 为准。CONTRACT-V2-001 的响应包装和 GET 子资源已部分交付，剩余精确契约按本文 BE-P20 架构处理结论和 COMMUNICATION.md 第 8 节执行。身份源等未决项仅限制对应子项；执行环境故障按 ENV-V2-001 验证审批通道。

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
  接口：`GET/PUT /admin/applications/{id}/models`、`GET /admin/applications/{id}/model-options`、`GET /v1/models`。
  请求/响应：virtual_model_id、enabled、max_output_tokens、allow_stream、version。
  业务流程：验证模型活动与路由 → 验证能力边界 → 保存权限 → 新请求即时生效。
  异常处理：无路由、能力越界、应用/密钥范围放宽、版本冲突。
  数据表：application_model_permission、application_key_model_scope、virtual_model。
  验收/测试：`/v1/models` 仅返回授权可用模型；取消授权不终止已准入请求。

- [ ] 任务编号：BE-204
  模块：应用额度与调整
  目标：完成 Token/金额/RPM/TPM、周期、续期、增减和人工重置。
  接口：`GET/PUT /quota`、`POST /quota/adjustments`、`POST /quota/reset`、`POST /quota/renew`；均位于 `/admin/applications/{id}` 下。
  请求/响应：limit、used、reserved、remaining、currency、period、reset_at、version、reason。
  业务流程：校验 → 幂等调整单 → 原子更新当前策略 → 审计；历史账本不改写。
  异常处理：币种冲突、非法周期、重复请求、版本冲突；减少至已用以下允许保存并拒绝新准入，不作为校验错误。
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

## BE-P20 架构处理结论（2026-09-12）

本节为 BE-P20-001～005 的实施决策，优先于上方历史执行记录中的“待架构确认”。仅确认下列技术实现口径；BP 产品选项和真实环境验收仍保留原状态。BE-P20 保持阻塞、原负责人和占用不变；原负责人可推进已明确子项，依赖迁移完成后再联调。

### BE-P20-001：列表、冲突与归档

- 新增 DUPLICATE_APPLICATION_CODE：HTTP 409、retryable=false、details.field=code。唯一约束并发冲突映射此码；格式非法仍为 FIELD_VALIDATION_FAILED/400。跨应用范围检查先于详情与影响读取。
- 保留 page/page_size，默认 1/20，page_size 最大 100。keyword、status、environment、owner_id 保留；新增 department（精确匹配）、budget_status（NORMAL/EXHAUSTED/UNLIMITED）。任一有限维度 used+reserved>=limit 为 EXHAUSTED；两维都无限制为 UNLIMITED；其余 NORMAL。总数与分页使用相同权限和筛选条件。
- 列表保留已明确基础字段，补 budget_status、requests_24h（非负整数）、success_rate_24h（0～1 decimal 字符串，无请求为 null）；model_count 表示授权且运行可用模型数。按 last_called_at desc、id asc 稳定排序，空最后调用时间排末尾；其他排序必须白名单。
- 统一 query_started_at 为本页统计时间，24h 区间为 [query_started_at-24h,query_started_at)。先分页取应用，再按本页 ID 集合批量查模型、Key、额度和运行摘要；禁止逐应用查询。聚合失败返回明确错误，不填成功零值。
- 归档只允许 DISABLED 且无运行请求/有效预占；存在占用返回 OBJECT_IN_USE/409。归档事务与准入登记统一锁定 application 行：准入在锁内复核 ACTIVE 并创建 Reservation，归档在锁内复核状态及占用；不能先查运行数后无锁写状态。旧请求通过 Reservation 持有占用直到终态，回收完成前阻止归档。
- DB-201 提供按应用范围分页/批量摘要查询及归档互斥所需存储操作；后端可先实现端口与测试，真实并发须双数据库验收。

### BE-P20-002：一次性密钥与轮换

- 最终响应字段固定为 secret、key_prefix、masked_value；key_value 退出最终接口。创建/轮换 data 包含 key_id、application_id、secret、key_prefix、masked_value、status、issued_at、expires_at、version，以及轮换时 old_key_id、grace_expires_at。普通 Key 视图新增 key_prefix，不包含 secret/digest。
- 创建新增 idempotency_key；轮换 body 为 version、reason、idempotency_key、grace_period_seconds。幂等键为 1～128 字符非空字符串，作用域为应用+操作+目标 Key；同键不同规范化请求返回 IDEMPOTENCY_KEY_CONFLICT/409。
- 轮换必须新增 Key ID 和摘要，复制旧 Key 的收紧策略。grace_period_seconds 默认 0；正值上限读取部署配置，未确认 BP-005 前仅开放 0，24 小时仍是待确认建议。旧 Key 宽限到期按有效截止时间拒绝，撤销立即失效；不可恢复 REVOKED。
- 旧 Key 存 replaced_by_key_id 和 grace_expires_at；已被替换的 Key 不再轮换，只允许撤销。新 Key 到期不得晚于旧 Key 原到期。轮换行锁、旧 Key 版本更新、新 Key、幂等记录及审计处于同一数据库事务。
- 幂等重放返回同一非敏感结果、secret=null、secret_available=false；首次成功 secret_available=true。服务端仅存摘要和非敏感结果，响应丢失后通过新轮换恢复，不持久化可解密原文。前端重放提示原文不可再次获取，不能提示已复制。
- 名称是显示标签，轮换继承名称，不对历史代际强制 application_id+name 唯一；摘要全局唯一，旧 Key 保留历史引用。FE/BE 在同次联调切换 secret 字段，不长期并存两套字段。

### BE-P20-003：模型字段与运行可用性

- allow_stream 为统一 API/约束 JSON 名；stream_allowed 通过新增迁移转换历史 JSON，不能因未知/非法字段回退为无约束。null 表示继承上级，false 表示禁止，true 也不能突破上级能力；max_output_tokens 取所有生效上限的最小值。
- 新增应用授权候选读取 GET /admin/applications/{id}/model-options，返回 data.items，每项 virtual_model_id、code、max_output_tokens、allow_stream、snapshot_no。仅返回身份允许授权且已发布、存在可用候选的模型。GET /models 继续展示已有授权配置，含失效配置及不可用原因，不能用它替代运行目录。
- 后端统一提供运行模型可用性查询：输入应用/密钥上下文与固定活动快照，输出可路由模型、能力上限和不可用原因。授权写、model-options、/v1/models 和 Chat 共用规则；不存在独立前端推算。
- 能力交集取同一快照全部可切换启用候选，不因短时健康下降放宽能力；运行可用性另行检查状态、Key、价格、健康和容量。发布版本中不存在的模型不得授权；共享状态不可查时明确拒绝。
- 应用负责人只能在可信身份赋予的可授权模型集合内变更；无显式集合时只能收紧已有授权，新增授权默认拒绝。管理员仍须通过运行可用性校验。

### BE-P20-004：降低额度、周期与流水

- PRD 4.5 优先：允许非负新上限低于 used+reserved，保存成功并立即阻止后续新准入。已准入请求按原上下文结算，历史消耗不回滚；GET 返回 tokens_remaining=max(0,limit-used-reserved)，amount_remaining 同理，无限额返回 null。
- 保留 token_limit、tokens_used、tokens_reserved、amount_limit、amount_used、amount_reserved、currency、rpm、tpm、period_type/start/end、version；新增 period_id、policy_version、timezone、reset_at、tokens_remaining、amount_remaining、admission_blocked。金额 decimal 字符串；reset_at 生命周期/自定义周期为 null，自然周期为下一边界。
- 本轮时区从平台配置读取并写入周期快照，前端只读展示；默认自然月/Asia/Shanghai 仍为 BP-004 建议，禁止在未配置时静默猜测。自然周期按该时区日/月边界转 UTC，区间左闭右开；自定义起止必须显式提供。
- PUT /quota 使用 version、reason 和新增 idempotency_key；和调整、重置一样事务内追加变更流水与不可变策略版本，不能只改当前行。每个 Reservation 绑定 period_id/policy_version；晚到结算记回原周期，不能扣到新周期。
- 自然周期到期懒切换与后台滚动共用一个应用行锁和周期唯一约束；历史周期不覆盖。已有消费时 PUT 不允许改写周期/币种，返回 CONFIG_FIELD_IMMUTABLE/409 并指向续期；RPM/TPM 调整不得清空当前速率窗口。
- 新增 POST /quota/renew：body 为 quota_version、reason、idempotency_key、token_limit、amount_limit、currency、period_type、period_end。首期仅立即续期；period_start 由服务端固定为操作生效时刻，自然日/月结束于下一自然边界，生命周期结束为空，自定义 period_end 必填且晚于生效时刻，非自定义不得传 period_end。服务端校验后以新 period_id 和递增 policy_version 建立当前周期、保留旧账本。人工续期与人工重置均在应用行锁内要求无未终态 Reservation，否则 OBJECT_IN_USE/409；用户应先禁用应用并等待排空。自然周期滚动不受此人工操作限制，晚到结算仍归原周期。
- POST /quota/reset 保留 dimension=TOKEN_USAGE 或 AMOUNT_USAGE、confirmation_code（必须等于应用 code）、quota_version、reason、idempotency_key。新周期段保留原币种/时区/策略/下一自然边界，目标维度 opening_used=0，另一维度 opening_used=原已用，预占必须为 0；总已用=opening_used+该段实际消耗。数据库保存前后周期关联和期初余额，账本可解释非重置维度延续，不复制历史请求账本。旧周期只读，不能把重置解释为修改历史请求成本。
- 预约调整通过已有 POST /quota/adjustments 增加可选 effective_at；请求保留 dimension=TOKEN_LIMIT/AMOUNT_LIMIT、delta、reason、idempotency_key、quota_version。只允许正 delta 预约；Token 必须整数，金额为 decimal 字符串，无限额不能做增减。未来时间返回 202/SCHEDULED，立即执行返回 200/APPLIED。到期执行时锁应用、校验策略版本；版本已变化标为 CONFLICT，不静默覆盖。创建预约不改变当前额度；前端须区分已预约和已生效。
- 以上命令统一 data={operation_id,status,effective_at,quota}，未生效时 quota=null；GET /quota/adjustments 的 data.items 合并按权限过滤的预约状态与已生效流水（同一 operation_id 只显示一项），可按 operation_id 查询，无数据返回空列表。服务端将请求 version/quota_version 校验为当前行乐观锁，同时在操作记录保存对应 policy_version；同幂等键先比较请求摘要再重放，不因原版本已变化创建第二次操作。

### BE-P20-005：权限与身份验收边界

- 管理身份、应用密钥完全隔离。管理员之外，创建应用、扩展授权、额度调整分别要求可信身份显式授权；角色映射不自动授予所有敏感操作。应用范围在服务和查询层共同执行。
- 成员读取可继续；成员写入方式、OIDC/SAML/可信网关头的最终选择仍待 BP-001/002 决策。允许通过既有 AuthContextProvider 端口完成权限单测，不能据此验收企业登录。
- 跨应用拒绝返回 ACCESS_DENIED/403，审计只记 actor、action、target_id、request_id、result=DENIED；不得带出他人应用名称、Key 或请求正文。
- BE-205 企业身份验收、真实 Provider 和应用首调 E2E 保持未验收；迁移、真实存储、权限和联调证据满足后由原负责人提请审查，任务表才能变更状态。
