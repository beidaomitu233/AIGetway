# 轻享 AI 项目执行总文档

## 1. 文档定位与范围

需求基线为《轻享AI-产品需求说明书-PRD-V1.0.md》。本文件与 FRONTEND_PLAN.md、BACKEND_PLAN.md、DATABASE_PLAN.md、COMMUNICATION.md 共同构成执行契约。当前目录没有已有业务代码、构建文件或数据库；下述目录、技术栈和物理表均为实施设计，不能视为已实现能力。所有未勾选项交给执行模型完成，本轮只交付文档。

目标：业务应用通过稳定 Alias 调用文本模型，运行时统一完成 Provider 适配、多密钥选择、路由、容量、可靠性、Usage/Cost 与 Trace；管理员通过草稿校验和发布控制配置生效。

交付：Java SDK、Spring Boot Starter/Embedded Admin UI、Standalone Server，共享 Java 17 协议与运行内核。验证 Java 17/21、Boot 3.3/3.4/3.5、Servlet/Reactive。内置 OPENAI、ANTHROPIC、GEMINI、DEEPSEEK Adapter。

边界：仅 system/user/assistant 文本 Chat；同步、异步、流式。不建设用户注册、充值、订阅、商业计费、组织多租户、Prompt、知识库、MCP、Workflow、Agent、多模态、工具调用、Embedding、Realtime、Responses。metadata.tenant 是检索标签，不构成租户权限系统。

## 2. 工程结构与职责

| 目录/发布物 | 职责与依赖 | 执行所有者 |
|---|---|---|
| light-ai-client | 不可变协议对象、远程 HTTP Client、Flow 流；无 Spring、数据库和 Provider 类型 | 后端 |
| light-ai-spi | 依赖 client 协议；ProviderAdapter、SecretProvider、AuthContextProvider、TraceExporter | 后端 |
| light-ai-runtime | 依赖 client、spi；路由、容量、恢复、计价、LocalRuntimeDefinition；无管理发布实现 | 后端 |
| light-ai-provider-* | 分别实现四个内置 Adapter；每次方法只执行一次外部请求 | 后端 |
| light-ai-storage-jdbc | 管理配置、Trace、审计、聚合仓储；事务边界由业务服务定义 | 后端与数据库 |
| light-ai-storage-redis | 集群容量、FIFO、熔断原子状态；无产品权限逻辑 | 后端 |
| light-ai-admin | 管理服务、发布协调、权限与静态资源挂载 | 后端 |
| light-ai-admin-ui | Vue 管理前端，输出同一份可嵌入静态资源 | 前端 |
| light-ai-spring-boot-starter | 条件装配与宿主扩展；复用宿主资源 | 后端 |
| light-ai-server | Standalone 入口、管理身份集成、业务 Token、实例认证、健康检查 | 后端 |
| light-ai-storage-jdbc/src/main/resources/db/migration | 版本化迁移与最小初始数据，独占数据库模型修改范围 | 数据库 |
| docs/contracts | 执行阶段维护 OpenAPI 3.1、DTO 字典和协议样例；不得独立变更字段语义 | 后端主责，前端/数据库审查 |

默认技术选择：Vue 3 + TypeScript；Java 17 + Maven 多模块；服务入口使用 Spring Boot 3.5 兼容基线；JDBC 持久化采用数据库方言抽象（DatabaseDialect），支持 PostgreSQL、MySQL 8.0 与 MySQL 5.7 自由切换；Starter 支持 dynamic-datasource 多数据源动态路由与运行时方言解析；Redis 原子共享状态。具体补丁版本在实施任务中锁定，不用动态版本。普通 Java Local Runtime 无数据库依赖；Standalone Client 不执行本地治理；Embedded 单实例允许进程内原子状态，集群必须共享状态。

## 3. 接口与字段协作总则

管理 API 路径沿用 PRD `/admin/...`，业务 API 为 `/v1/...`，内部实例 API 为 `/internal/...`。页面路径统一使用 `/ui/...`，避免 SPA fallback 吞掉 API。Embedded 默认挂载根 `/light-ai`：页面 `/light-ai/ui/...`，管理 `/light-ai/admin/...`，不暴露宿主 `/v1`。根路径可配置，启动时检测冲突。

管理成功结构统一 `{data: T}`；失败 `{error: UnifiedError}`。ManagementOperationResult 的 data 字段为 id、version、entity（创建/更新的非敏感详情，删除为 null）、draft_changed、draft_revision（即时操作为 null）、request_id。PageResult 为 items、total、page、page_size、sort、query_started_at、data_updated_at。检测与列表不递增配置 version。

业务成功对象直接输出 UnifiedModelList / UnifiedChatResponse / UnifiedChatChunk，不包 data。UnifiedError 包含 code、type、message、retryable，按条件包含 param、errors（field、code、message）、trace_id、retry_after_ms、request_id、current_version、current_state_version；禁止返回原始异常。错误 HTTP 状态以 BACKEND_PLAN 附录为准。

ID 使用不透明字符串，外部不得解析 ID；数据库 UUID 经 API 作为字符串。trace_id 为 1—128 字符且唯一。UTC 存储，ISO 8601 带偏移传输；查询区间左闭右开。金额、价格及可能超过 JS 安全整数的 bigint 以十进制字符串传输，前端禁止 Number 化后运算；小范围页码和毫秒使用整数。百分比界面 50% 对应接口 circuit_failure_rate=0.5，jitter_percent 仍为 0—100 整数。

PUT 提交完整可编辑对象与 version；不可变字段不出现在可编辑 DTO；未提交可空字段按 null 清除，前端不能把 secret_ref 掩码当新引用提交。EXTERNAL_REF 未修改引用时省略 secret_ref 并保留原值；显式 null 不允许清除。所有未知写入字段拒绝。DELETE 使用 JSON 命令体携带 version 和影响确认值，反向代理必须透传；不能只凭 URL 删除。

查询白名单、数组最大数量、排序列、字段权限由服务端校验。前端 URL 只持久化非敏感筛选，Token、消息正文、client_ip 不写 URL；敏感 IP 筛选保留当前页内存。API 客户端只对读取提供手动重试，写入和模型调用不自动重放。网络超时不等于写入失败，应先读取对象/发布状态核对。

## 4. 必须统一的实现口径

以下为规划假设，记录在 COMMUNICATION.md；未确认前按此实现契约和测试夹具，涉及产品语义调整须审查确认后合并。

| 编号 | 采用口径 | 依据/解决范围 |
|---|---|---|
| C-002 | 采用 DatabaseDialect SPI 支持 PostgreSQL、MySQL 8.0 与 MySQL 5.7 自由切换，Starter 支持 dynamic-datasource 动态数据源路由 | 用户明确要求适配多数据库与 dynamic-datasource，仓储层消除 CTE/UPDATE-FROM/SKIP LOCKED/FILTER 等语法差异，全量测试保证兼容性 |
| C-003 | source_mode 固定 LOCAL_RUNTIME、EMBEDDED、STANDALONE_SERVER；管理测试增加 invocation_source=ADMIN_TEST，普通调用 APPLICATION | 统一 2.6、4.4、4.6 的 SDK/LOCAL_RUNTIME 等歧义，不混入部署模式 |
| C-004 | 写入及筛选字段均为 overflow_strategy | 4.3.1 表单；修正列表中的 overflow_action |
| C-005 | 模型导入逐对象事务并返回 created/skipped/failed；每个成功对象与审计原子 | 采用 4.2.9.5，避免一个错误对象撤销所有成功导入 |
| C-006 | max_tokens 显式值超上限过滤；缺省时取 default_max_tokens 或 max_output_tokens，再收紧到剩余上下文 | 采用更详细的 4.7.1.5；不能静默裁剪显式请求 |
| C-007 | 校验失败只保存 Validation；非法发布命令不创建 PublishRecord；准备阶段失败保留 ABORTED 快照和 FAILED 发布 | 采用 4.5.2，快照号单调允许间隙 |
| C-008 | Circuit 键为 provider_model_id+credential_id；共享该路径的 Alias 共用健康窗口，按当前 Trace 策略评估阈值 | 需确认不同 Alias 熔断策略冲突；不新增候选级熔断维度 |
| C-009 | Usage 默认多币种时使用 REQUEST_COUNT desc；只有选单币种后允许 TOTAL_COST desc | 4.4.4.2 的币种限制优先于表单默认值 |
| C-010 | 默认 Alias 为可选 RuntimeConfig.default_alias_id；未配置且请求缺 model 返回字段错误 | PRD 提到默认 Alias但未给运行配置字段，需确认 |
| C-011 | 补齐 Provider Model impact、审计 export、管理 bootstrap 三个辅助端点；不扩展业务范围 | 原文操作/权限已要求但 API 清单缺入口；具体字段见后端 |
| C-012 | 所有角色的 Credential 原文永不读取；开发与只读不接收 Credential ID/名称/掩码 | 权限表优先于时间线的泛化描述 |
| C-013 | 熔断人工操作采用数据库命令/审计事务 + Redis 原子应用并收敛；未应用不返回已成功状态 | Redis 与 SQL 无共同事务，不能宣称跨存储原子提交 |
| C-014 | 导入的停用模型允许能力缺失；启用与发布前严格补齐；context_window 必须大于 max_output_tokens | 4.2.5/4.2.6 的导入和启用规则 |

## 5. 关键业务流程与一致性

首次接入：Provider → Pool → Credential → Model → Alias → Candidate → 策略 → 固定草稿修订校验 → 确认警告 → 全部实例 READY → 原子激活 → 实例 LOADED → 模型目录与真实调用验收。

配置实体表保存工作草稿；Runtime 只读不可变活动快照。草稿事务锁 ConfigDraftState，校验实体 version，更新实体、DraftChange、draft_revision 与成功 AuditLog。失败业务回滚后以相同 request_id 独立写失败审计；审计失败告警，不伪造成功记录。Credential 密钥和完整引用存独立受保护表，轮换即时生效，不进入快照与差异；正在执行的 Attempt 保留已取得句柄。Access Credential 独立即时事务，不参与草稿锁。

发布：校验 revision/checksum/期限/警告/未使用 validation → 锁草稿 → CREATED 快照/PREPARING 记录 → 固定 ONLINE 目标 → READY → 数据库原子更新活动指针、快照状态、草稿基线 → 实例引用替换。准备失败保留草稿和旧 ACTIVE；激活后超时为 PARTIAL_FAILED 并后台收敛，不自动回滚。实例加载旧快照时不得宣称新配置已生效；负载入口只有完成当前活动快照核对的实例可恢复接入。

一次调用：身份/IP/JSON/Alias → trace_id 唯一占位并固定 snapshot_no → 能力/上下文过滤 → 路径与凭证选择 → 三层原子容量 → Attempt → Adapter → 结算 → 恢复决策或最终化。过滤无 Attempt，真实外部请求才计 RPM。共享状态不可用时不创建新 Trace/预占；有 Trace 后所有失败都必须最终化。同步最终事务失败返回 INTERNAL_ERROR。

恢复预算按整个 Trace 累计：最多 1+max_retries+max_credential_failovers+max_fallbacks 次外部尝试，各动作预算独立。先结算再下一次 Attempt。429 优先换 Credential，再 Fallback，最后才允许预算内 Retry-After；内容过滤为成功 finish_reason；鉴权与参数错误不 RETRY。首个 SSE 业务块提交后不恢复路径，不发送成功 DONE。HTTP Chunk 与 Java StreamEvent 分层转换，避免把 Java 事件格式当 HTTP 协议。

容量：Unix 对齐 60 秒固定窗口，Alias/Model/Credential 同时预占，部分失败全不计数。Credential 字段限额与同维度 LimitPolicy 同时存在时取各非空上限最小值，只计一份该维度计数。实际或估算用量结算在原预占窗口，不扣新窗口；并发各终止路径只释放一次。FIFO 按 Alias，唤醒重新选完整路径，最小 queue_max_size 限制，deadline 受总超时约束。Watchdog 按到期和运行租约判断失联 Attempt，先最终化孤儿调用，再释放异常预占，避免永久 RUNNING。

观测：每个 Trace 一条请求贡献，按最终路径归因；每个 Attempt 一条执行贡献，按实际路径计 Token/成本。全部使用 Trace.started_at 时间桶。HOUR 与 DAY 以及事件成功同事务提交，重放不重复增量。响应 Usage 来自成功 Attempt，Trace 总量为全部 Attempt；相同 Alias 候选币种必须一致，跨 Alias 不合币种。成功率=SUCCEEDED/(SUCCEEDED+FAILED+STREAM_INTERRUPTED)，分母 0 返回 null。

## 6. 安全、性能与发布验收

管理身份来自部署认证适配（默认拒绝匿名）；不新增用户密码表和注册页面。前端守卫控制导航/按钮，后端同时控制操作、application_scope、Alias 范围、字段及导出。Embedded 缺 AuthContextProvider 默认拒绝，仅显式本地访问配置可建立 LOCAL_ADMIN。

安全边界分别为管理身份、业务 Access Token、Provider Secret、实例 mTLS/服务身份。Access Token 使用 32 字节随机 + lai_，存 HMAC-SHA256 摘要与 pepper 版本，轮换立即废除旧代次。Secret 主密钥不进数据库，读取均掩码。采样默认关闭；诊断访问单独授权并审计；日志只记关联 ID/错误码/耗时。管理 Cookie 会话写请求检查 CSRF，同源部署优先。Provider URL/代理只允许部署方允许目的地址，防止 SSRF；内部网段需部署显式许可，重定向重新检查目标，不传递认证头。

| 验收面 | 可测标准 | 负责人 |
|---|---|---|
| 热路径 | 扣除 Provider 耗时，路由/选凭证/容量/Trace 初始化附加 P95 ≤20ms；至少 200 并发流连接 | 后端，审查复核压测环境与原始结果 |
| 背压 | 每订阅最多 32 事件；request(n) 决定下发量；取消无后续事件 | 后端 SDK与Adapter |
| 聚合 | 可复算、不重计，展示延迟≤2×dashboard_refresh_seconds | 后端/数据库 |
| 可靠性 | 三类预算不越界，所有结束路径并发释放一次，已提交流不拼接 | 后端 |
| 发布 | 两实例准备失败不激活，全部 READY 才原子激活，旧 Trace 固定旧快照 | 后端/数据库 |
| 留存 | Trace 30天、Usage 365天、Audit≥365天；快照至少最近100成功版本与近365天全部发布 | 数据库 |
| 可用性 | 服务自身月目标99.9%；压测不能证明月SLA，部署后持续计量 | 运维 |
| 权限 | 四角色页面/API/字段矩阵；伪造 application 和跨 Trace 子实体均不能越权 | 前后端 |

压测基线假设：4 vCPU/8GiB 应用、同网数据库和共享 Redis、固定延迟的 Stub Provider，预热2分钟、稳态10分钟，分别测同步和200流连接；记录数据量、硬件、P50/P95/P99、错误率、CPU/内存、连接数、队列和存储延迟。真实 Provider 联调由部署方提供测试凭证，记录模型和调用成本，不把供应商延迟混进服务附加耗时。

## 7. Git 与执行交付规则

main 保存稳定发布，dev 保存集成代码。AGENTS.md 与本文件统一使用 dev 作为开发集成分支。功能从 dev 创建 feature/frontend-模块名、feature/backend-模块名、feature/database-模块名；文档使用 docs/architecture-plan。release/<version> 从 dev 创建，验证后合并 main 与 dev 并在 main 打 Tag；hotfix/<name> 从 main 创建，验证后回合 main 与 dev。

每个任务包一次独立提交；若修复审查意见追加单一目的提交，禁止混入无关文件。格式：docs: update architecture plan；feat(frontend): complete login page（仅格式示例，本项目不建设登录页）；feat(backend): complete auth api；test: add auth tests；fix: resolve review issue。

完成任务包的顺序：运行相关测试 → 逐项核对验收 → 在 Plan 勾选已验收任务 → 同次提交代码、测试及 Plan → 在 COMMUNICATION 登记分支、commit、测试证据和审查结论 → 审查通过合入 dev。缺依赖可提交契约/Mock，不能把未联调任务勾选完成。未授权前不推送远程、不发布制品。

前端只改 UI；后端改 Java 与接口字典；数据库模型负责迁移与索引。接口字段变更先登记沟通记录，后端给具体 DTO，前端及数据库确认并同批更新三个 Plan。合同冲突禁止自行扩大范围。审查模型检查需求覆盖、接口一致、关键状态、敏感信息与测试证据。

## 8. 执行顺序与依赖门禁

| 阶段 | 可执行任务包 | 放行条件 |
|---|---|---|
| P0 契约/工程 | FE-P01、BE-P01、DB-P01 | 补充接口和待确认口径登记，公共DTO/字段/身份适配一致 |
| P1 配置接入 | FE-P02/P03、BE-P02/P03、DB-P02 | Provider到候选草稿链、秘密存储与审计可用 |
| P2 运行内核 | FE-P04、BE-P04/P05、DB-P03 | 路由、容量、恢复、终止与四Adapter契约通过 |
| P3 观测 | FE-P05/P06、BE-P06、DB-P04 | Trace最终化和Usage事件可复算 |
| P4 发布/安全管理 | FE-P07/P08、BE-P07/P08、DB-P05 | 发布锁、两阶段激活、Token即时失效与留存检查通过 |
| P5 交付形态 | FE-P09、BE-P09/P10 | Java/Boot模式矩阵、管理测试、健康、性能验收通过 |

任务编号在各 Plan 内唯一；任务包在契约冻结后可以分别编写，前端可在契约冻结后按相同夹具执行，正式完成必须依赖接口通过验收。

## 9. 待确认问题与资料索引

完整待确认问题及当前采用假设见 COMMUNICATION.md；没有确认不会阻止无关文档规划，不能据此增加 PRD 外功能。详细页面和 API 字段在对应 Plan 的“需求契约附录”，附录保留 PRD 原编号用于定位；出现明确歧义时以本文件第4节的采用口径及沟通记录为准。物理字段以 DATABASE_PLAN.md 为准，派生展示字段不得直接变成可写数据库字段。


## 10. 角色权限矩阵与状态机完整基线

权限与状态沿用PRD下表，字段裁剪和已明确歧义按第4节执行。


### 2.4.1 角色列表

| 角色 | 职责 |
|---|---|
| 系统管理员 | 维护 Provider、Credential、模型、别名、治理策略、运行配置和访问凭证，并执行配置发布。 |
| 运维人员 | 查看运行概览、Trace、Usage、审计与熔断状态，执行连接检测、候选探测和熔断恢复。 |
| 开发人员 | 查看已发布的模型别名、接入文档和调用记录，使用 SDK 或 HTTP API 联调。 |
| 只读人员 | 查看运行概览、模型配置摘要、Trace 和 Usage，不执行任何变更操作。 |

### 2.4.2 功能权限

| 功能 | 系统管理员 | 运维人员 | 开发人员 | 只读人员 |
|---|---|---|---|---|
| 运行概览与趋势 | 查看 | 查看 | 查看 | 查看 |
| Provider、模型、别名编辑 | 管理 | 查看与检测 | 查看 | 查看 |
| Provider Model 导入 | 管理 | 无 | 无 | 无 |
| Provider、Credential、候选检测 | 管理 | 检测 | 无 | 无 |
| Credential 明文写入 | 管理 | 无 | 无 | 无 |
| Credential 脱敏信息 | 查看 | 查看 | 无 | 无 |
| 限流与可靠性策略 | 管理 | 查看 | 查看 | 查看 |
| 熔断状态 | 查看 | 查看 | 查看授权 Alias | 查看 |
| 熔断人工操作 | 管理 | 管理 | 无 | 无 |
| Trace 与 Usage | 查看 | 查看 | 查看本应用 | 查看 |
| Trace 受控诊断字段 | 查看并记录审计 | 查看并记录审计 | 无 | 无 |
| Trace 与 Usage 导出 | 导出 | 导出 | 无 | 无 |
| 待发布变更 | 查看、撤销 | 查看 | 查看 | 查看 |
| 配置发布 | 管理 | 无 | 无 | 无 |
| 运行参数 | 管理 | 查看 | 查看 | 查看 |
| Standalone Access Credential | 管理 | 查看脱敏信息 | 无 | 无 |
| 审计日志 | 查看 | 查看 | 无 | 无 |
| 接入说明与代码示例 | 查看 | 查看 | 查看授权 Alias | 查看脱敏示例 |
| 在线测试 | 测试 | 测试 | 测试授权 Alias | 无 |

Standalone Mode 使用产品自身权限时按上表鉴权；Embedded Mode 可以由宿主应用把已登录用户映射为上述角色。权限校验同时作用于页面、管理接口和敏感字段返回，Credential 密钥在任何角色的读取接口中均不返回原文。

## 2.5 业务流程
### 2.5.5 业务状态

| 对象 | 状态 | 进入条件 | 可执行操作 |
|---|---|---|---|
| Provider | ENABLED、DISABLED | 管理员启停 | 编辑、检测、停用或启用 |
| Provider 连接 | UNKNOWN、AVAILABLE、UNAVAILABLE | 尚未检测、检测成功或检测失败 | 检测、查看最近错误 |
| Credential Pool | AVAILABLE、PARTIAL_AVAILABLE、UNAVAILABLE、DISABLED | 池启停状态与池内 Credential 实时健康状态共同计算 | 编辑、启停、查看 Credential |
| Provider Model 连接 | UNKNOWN、AVAILABLE、UNAVAILABLE | 尚未检测、模型检测成功或检测失败 | 检测、编辑、查看引用候选 |
| Credential | HEALTHY、UNKNOWN、RATE_LIMITED、INVALID、UNAVAILABLE、DISABLED | 检测结果、运行错误、限额复位、Secret 解析结果或人工启停 | 检测、编辑、轮换、停用、启用 |
| Route Candidate | AVAILABLE、CAPACITY_EXHAUSTED、CIRCUIT_OPEN、DISABLED、UNAVAILABLE | 配置与实时运行状态共同决定 | 编辑、探测、查看 Trace |
| Circuit | CLOSED、OPEN、HALF_OPEN | 失败率、等待期、探测结果或人工操作 | 人工打开、恢复、立即探测 |
| Capacity Reservation | ACTIVE、SETTLED、RELEASED、EXPIRED | 外部调用前预占，调用后结算、释放或由 Watchdog 回收 | 查看关联 Trace 与 Attempt |
| Queue Entry | WAITING、ACQUIRED、TIMEOUT、REJECTED、CANCELLED | 容量不足进入队列，取得容量、超时、队列已满或客户端取消后结束 | 查看关联 Trace 和阻塞策略 |
| Recovery Decision | RETRY、CREDENTIAL_FAILOVER、FALLBACK、FAIL | Attempt 失败后根据错误分类、剩余预算和可用路径生成 | 查看来源 Attempt 与目标路径 |
| Trace | QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED、STREAM_INTERRUPTED | 调用生命周期 | 查看详情 |
| Attempt | RUNNING、SUCCEEDED、FAILED、CANCELLED | 外部调用开始、正常完成、失败分类或取消传播 | 查看时间线与恢复动作 |
| Provider Check Record | SUCCEEDED、FAILED | 主动检测结束 | 查看检测对象、耗时、Usage、Provider Request ID 与错误 |
| Batch Check Job | PENDING、RUNNING、SUCCEEDED、PARTIAL_FAILED、FAILED、CANCELLED | 批量任务创建、执行、汇总或取消 | 查看任务明细、取消未开始项 |
| Config Draft State | EDITABLE、PUBLISHING | 正常编辑或发布取得全局草稿锁 | 编辑与撤销；PUBLISHING 时只读 |
| Config Validation Result | PASSED、FAILED、EXPIRED | 校验通过、校验失败、超时或草稿修订变化 | PASSED 且未过期时发布 |
| Config Snapshot | CREATED、ACTIVE、SUPERSEDED、ABORTED | 快照生成、原子激活、被新快照替代或准备失败 | 内部加载、查看摘要 |
| Publish Record | PREPARING、ACTIVATING、SUCCEEDED、PARTIAL_FAILED、FAILED | 实例准备、快照激活和实例确认结果 | 查看详情；失败时修复草稿后重新校验发布 |
| Publish Instance Result | PENDING、PREPARING、READY、ACTIVATING、LOADED、FAILED、TIMED_OUT | 目标实例接收指令、构建配置、等待激活、完成加载或超时失败 | 查看实例结果与错误 |
| Runtime Instance | ONLINE、DRAINING、STALE、OFFLINE | 心跳、accepting_requests、快照加载和失联时长共同决定 | 查看实例与快照；DRAINING、STALE、OFFLINE 不接收新发布目标 |
| Standalone Access Credential | ACTIVE、DISABLED、EXPIRED、DELETED | 创建、人工启停、到期或软删除 | 编辑、轮换、启停、删除、查看 Trace |
| Usage Aggregation Event | PENDING、PROCESSING、SUCCEEDED、FAILED | Trace 最终化、聚合器取得任务、幂等写入成功或重试耗尽 | 查看聚合延迟与失败告警 |

配置启停与运行健康状态相互独立。Provider、Credential Pool、Credential、Provider Model、Model Alias 和 Route Candidate 的 enabled 变更先进入草稿，发布成功后才影响新 Trace；connection_status、health_status、runtime_status 和 CircuitState 属于运行状态，由检测、外部调用、容量和熔断结果即时更新，不进入配置快照。页面必须分别显示“配置启停”“待发布变更”和“当前运行状态”，不能把最近一次检测成功等同于对象已经发布可用。

Trace 创建后处于 RUNNING；只有因容量 QUEUE 等待时使用 QUEUED，取得容量后回到 RUNNING。每次实际向 Provider 发出请求前创建 RUNNING Attempt，完成后只能进入 SUCCEEDED、FAILED 或 CANCELLED。Trace 在全部业务处理结束后进入一个最终状态并写 ended_at；已经结束的 Trace 和 Attempt 不允许回退到运行状态。RecoveryDecision 是失败后的不可变决策记录，不存在二次状态迁移。

配置发布从 EDITABLE 草稿开始。校验通过只生成 PASSED ConfigValidationResult，不修改活动配置；发布取得锁后草稿进入 PUBLISHING，创建 CREATED ConfigSnapshot 与 PREPARING PublishRecord。任一目标实例准备失败时快照进入 ABORTED、发布进入 FAILED、草稿恢复 EDITABLE；全部 READY 后快照原子进入 ACTIVE，上一快照进入 SUPERSEDED。激活后实例未全部 LOADED 时发布暂为 PARTIAL_FAILED，实例后续收敛后可以进入 SUCCEEDED，但活动快照不回退。

Runtime Instance 正常心跳且 accepting_requests=true 时为 ONLINE，关闭接入时为 DRAINING，超过失联阈值为 STALE 或 OFFLINE。Standalone Access Credential 到达 expires_at 后由读取逻辑计算为 EXPIRED；轮换不改变 ACTIVE 或 DISABLED 状态，只递增 rotation_generation 并立即使旧 Token 失效；DELETED 为不可恢复终态。

## 2.6 信息结构
