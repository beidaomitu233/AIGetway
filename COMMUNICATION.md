# 轻享 AI V2.0 协作沟通与审查记录

## 1. 文档规则（2026-09-12）

发现 PRD、API、页面、数据或实现冲突时先登记，不私自扩大范围。状态只使用待确认、已确认、执行中、已完成、驳回；“已完成”必须附分支、commit、测试命令和结果。

## 2. 快速检查记录

| 日期 | 基线 | 动作 | 结果 |
|---|---|---|---|
| 2026-09-12 | dev | `git pull --ff-only` | `Already up to date` |
| 2026-09-12 | dev vs origin/fix/fullstack-review-optimization | PRD 2.0、提交、文件树、实体/API/页面/迁移/测试静态检查 | 形成 FE/BE/DB-P20 起剩余任务包；未执行功能测试 |
| 2026-09-12 | 工作区 | 未跟踪文件保护 | `scripts/e2e-openrouter.sh` 保留且未修改 |

## 3. 问题台账

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
|---|---|---|---|---|---|---|---|---|---|
| C-V2-001 | 架构 | 基线 | 远程 dev 缺 V2 基线，导致前端无法领取 | 将 c69af23 与远程登记 7669954 合并，统一 dev | 全部 | 全部 | 全部 | 已完成 | 用户已指定 V2 为开发主线并要求启动开发；本次提交统一基线，推送后以 origin/dev 为唯一领取起点。业务验收仍按任务执行。 |
| C-V2-002 | 产品 | 身份 | 企业身份使用 OIDC、SAML 或可信网关头未定 | 推荐 OIDC；网关头仅限受信代理 | login/bootstrap | auth/bootstrap | application_member、audit_log | 待确认 | 对应 BP-001 |
| C-V2-003 | 产品 | 权限 | 应用负责人能否自主创建应用及维护成员未定 | 创建权和成员维护权拆分 | applications | application service | application、member | 待确认 | 对应 BP-002 |
| C-V2-004 | 产品 | 治理 | 生产应用是否强制 RPM 或 TPM 未定 | 推荐生产至少一项，测试可继承默认值 | quota form | admission | quota_policy | 待确认 | 对应 BP-003 |
| C-V2-005 | 产品 | 周期 | 预算默认周期与统一时区未定 | 推荐自然月、Asia/Shanghai；存 UTC | quota/usage | quota service | quota、ledger | 待确认 | 对应 BP-004 |
| C-V2-006 | 安全 | 密钥 | 轮换宽限期上限未定 | 推荐最大 24 小时，撤销不可恢复 | key dialog | key service | application_key | 待确认 | 对应 BP-005 |
| C-V2-007 | 产品 | 额度 | 是否支持密钥级独立 Token/金额预算未定 | 首期只支持密钥级 RPM/TPM 和模型收紧 | key/quota | admission | key、quota | 待确认 | 对应 BP-006 |
| C-V2-008 | 架构 | Provider | 首批 Provider 未冻结 | 推荐 OpenAI Compatible、OpenAI、Anthropic、Gemini、DeepSeek | channel | adapter | channel、upstream_model | 待确认 | 对应 BP-007 |
| C-V2-009 | 产品 | 成本 | 跨币种汇总口径 | 无可信汇率时分币种展示，禁止直接求和 | usage | settlement | usage_ledger | 已确认 | PRD 9.8/10.2 已明确 |
| C-V2-010 | 安全 | 留存 | Trace、账本、审计保留周期未定 | 三类分别配置，清理保留证据 | calls/audit | cleanup | trace、ledger、audit | 待确认 | 对应 BP-009 |
| C-V2-011 | 运维 | 性能 | 吞吐与最大流式并发未定 | 先固定压测口径，确认目标后门禁 | overview | gateway、metrics | runtime state | 待确认 | 对应 BP-010 |
| C-V2-012 | 数据库 | 迁移 | V1 数据迁移与旧运行历史处置未定 | 仅迁移校验通过的资源，旧历史归档只读 | migration notice | migration | V1 全表 | 待确认 | 对应 BP-011 |
| C-V2-013 | 架构 | 范围 | Embedded/Local Runtime 去留未定 | 不纳入 V2 首期验收 | 无 | starter/runtime | 无 | 待确认 | 对应 BP-012 |
| C-V2-014 | 审查 | 集成 | V2 应用与渠道资产已集成，仍缺逐包业务验收记录 | 按 P20—P23 逐包复核，不重复实现通过项 | applications 等 | application/channel 等 | V2/V3/V4 migration | 执行中 | 当前只确认静态资产存在 |

## 4. 任务包交接模板

| 字段 | 必填内容 |
|---|---|
| 任务包 | FE/BE/DB/RV-Pxx |
| 分支与 commit | 精确分支和提交 |
| 已完成任务 | 已勾选编号 |
| 契约版本 | OpenAPI、迁移号、快照 schema |
| 测试证据 | 命令、环境、通过/失败/跳过 |
| 未验证项 | 环境缺口与替代证据 |
| 风险与回滚 | 影响对象与回滚方式 |
| 审查结论 | 通过或退回及理由 |

## 5. 跨端验收包

#### RV-P20 应用接入（6 项）

- [ ] RV-201 创建应用、授权模型、配置额度并签发一次性密钥。
- [ ] RV-202 应用负责人跨应用访问返回 403 并形成越权审计。
- [ ] RV-203 `/v1/models` 只返回已授权且有活动路由的虚拟模型。
- [ ] RV-204 无效、过期、撤销或跨 IP 密钥在上游调用前拒绝。
- [ ] RV-205 轮换宽限与立即撤销按确认口径生效，原文不可恢复。
- [ ] RV-206 应用列表、详情、接入页的 loading/empty/error/403 与真实状态一致。

#### RV-P21 资源与路由（6 项）

- [ ] RV-211 渠道多 Key 按优先级/权重选择，单 Key 429 可换健康 Key。
- [ ] RV-212 Base URL 拒绝危险目标、重定向和 DNS 重绑定。
- [ ] RV-213 上游模型同步先预览再提交，不覆盖人工锁定字段。
- [ ] RV-214 虚拟模型能力使用安全交集，显式不支持参数在路由前拒绝。
- [ ] RV-215 禁用/删除渠道、Key 或模型前完成引用影响分析。
- [ ] RV-216 发布后新请求用新快照，运行中请求保持旧快照。

#### RV-P22 准入、恢复与结算（6 项）

- [ ] RV-221 Token/金额边界原子拒绝，无重复扣减或无限透支。
- [ ] RV-222 应用/密钥 RPM/TPM 同时生效，429 返回维度和 retry_after。
- [ ] RV-223 成功、失败、取消、超时均使 Reservation 进入唯一终态。
- [ ] RV-224 恢复顺序和独立预算正确，所有 Attempt 受总时限约束。
- [ ] RV-225 流首块前可切换，首块后不拼接其他路径且只有一个终态。
- [ ] RV-226 Usage、价格快照、币种、账本与 Trace/Attempt 一致。

#### RV-P23 观测、权限与交付（6 项）

- [ ] RV-231 调用详情完整展示准入、Attempt、恢复、流提交、结算和终态。
- [ ] RV-232 聚合可从账本重算，ACTUAL/ESTIMATED 与多币种清晰区分。
- [ ] RV-233 四角色的入口、按钮、API 和数据范围一致。
- [ ] RV-234 日志、Trace、导出、指标和浏览器存储无密钥、认证头和默认正文。
- [ ] RV-235 PostgreSQL、MySQL、Redis、真实 Provider 同步/流式与 E2E 通过。
- [ ] RV-236 1280/1920 页面无非预期溢出，异步任务只在真实完成后提示成功。

## 6. 开发阻塞解除与执行交接（2026-09-12）

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
|---|---|---|---|---|---|---|---|---|---|
| FE-V2-BASELINE-001 | 前端执行模型 | 执行基线与任务领取 | 7669954 登记远程 V1 基线阻塞，FE-201～205 未领取 | 保留原提交历史，任务表重建为 V2 P20～P23 | applications | application/auth/quota | application 等 | 已完成 | 合并 c69af23 与 7669954；推送后从 origin/dev 创建功能分支，按 TASK_STATUS.md 领取。未产生前端代码占用。 |
| ENV-V2-001 | 后端执行模型 | 本地执行环境 | 默认命令与专用 apply_patch 报 helper_unknown_error: setup refresh had errors | 失败后经 require_escalated 审批重试；补丁可直接调用已安装 codex.exe 的 --codex-run-as-apply-patch | 全部 | 全部 | 无 | 执行中 | 本机审批通道已验证可读写 Git/文档并启动 Node 20.19.6、Java 17.0.19；默认沙箱故障仍在。其他任务须各自验证，不得宣称其环境已恢复。 |
| CONTRACT-V2-001 | 架构 | 应用接口收口 | ApplicationController 使用 data 包装，ApplicationKeyController 直接返回对象；计划部分 GET 子资源尚无对应映射 | BE-201/202 优先补精确请求响应契约及接口测试；前端以同一契约夹具推进 | api/applications.ts、应用页 | ApplicationController、ApplicationKeyController、client/application | 应用域 | 执行中 | 现有代码只作为差异证据；按 PRD data/error 契约收口，不能新增长期双结构兼容。仅相关接口联调等待契约，其余任务可继续。 |

原阻塞登记提交 7669954 保留在 Git 历史；旧 V1 任务完成记录不迁入当前任务表。此前只完成静态资产检查，当前未宣布 V2 全量业务测试通过。

开发假设按 PRD 建议用于实现和测试，仍需在相应任务验收前确认：身份接入可先通过可替换测试身份上下文验证四角色；创建/成员管理按显式权限控制，成员写入暂不开放；周期建议自然月、Asia/Shanghai，须显式传参；轮换建议最大 24 小时；密钥预算暂归应用、密钥只收紧模型与速率。这些假设不等于产品决策已确认，也不阻塞列表、表单、权限隔离和已明确业务规则的开发。

当前应用接口证据：
- ApplicationController：GET/POST /admin/applications，GET/PUT /admin/applications/{id}，POST /status，PUT /models，PUT /quota，GET/POST /quota/adjustments，POST /quota/reset，GET /members。
- ApplicationKeyController：GET/POST /admin/applications/{id}/keys，POST /{keyId}/rotate、/status、/revoke。
- DTO 源位置：light-ai-client/src/main/java/com/lightai/client/application；前端消费位置：light-ai-admin-ui/src/api/applications.ts。后端先审查并补齐契约，明确字段类型、必填、枚举、分页、版本、幂等与错误，不将 DTO 文件存在视为已验收。

环境复核命令：node --version、java -version；Maven 可使用 D:/IntelliJ IDEA 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd。前端在 light-ai-admin-ui 下使用 npm run lint、npm run typecheck、npm test、npm run build。Maven 未在 PATH 时使用该绝对路径，不据此认定 Maven 未安装。默认沙箱失败时走产品审批机制；审批被拒绝才记录具体受阻命令。

## 7. BE-P20 审查差异（2026-09-12，后端执行模型 codex-be-0912）

以下问题提出方均为后端执行模型，状态均为待确认；不以现有实现作为新版产品结论。

| 编号 | 任务 | 差异与影响 | 待确认处理 |
|---|---|---|---|
| BE-P20-001 | BE-201 | code 重复当前返回 FIELD_VALIDATION_FAILED/400，计划要求 409 但未定义重复应用错误码；列表缺部门/预算筛选及 24h 摘要，查询存在逐应用子查询；归档未检查运行请求 | 架构确认错误码、筛选/摘要 DTO 与归档准入互斥契约，DB-201 提供批量查询及运行请求检查端口；不私自新增字段/错误码 |
| BE-P20-002 | BE-202 | DTO 使用 key_value/masked_value，计划要求 secret/key_prefix/grace_expires_at；rotate 原地覆盖摘要，没有新记录、幂等键与宽限持久化 | 架构与 DB-202 确认新记录轮换、幂等结果持久化及精确 DTO；本次仅按已确认 CONTRACT-V2-001 统一 data 包装，不提供双结构兼容 |
| BE-P20-003 | BE-203 | 当前仅校验虚拟模型存在与 enabled，没有验证已发布可路由及候选能力交集；DTO stream_allowed 与计划 allow_stream 不一致 | 架构确认能力与活动快照查询端口、字段收口及应用负责人授权上限；本次补 GET /models 读取现有授权视图，不宣称其为运行可用目录 |
| BE-P20-004 | BE-204 | 当前拒绝降低到已用+预占以下，违反 PRD 4.5；仅当前额度行，PUT 未生成调整单，缺周期历史/自然周期滚动/预约与时区参数，缺 remaining/reset_at | 架构与 DB-203/204 确认策略历史、调整账本及周期 DTO；禁止修改已发布迁移，本次补 GET /quota，不宣称额度治理完成 |
| BE-P20-005 | BE-205 | 企业身份源未确认；角色权限主要由固定角色映射，创建/成员维护显式权限与跨应用拒绝审计尚需闭环 | 沿用 C-V2-002/003，保持成员只读，以测试身份验证既有四角色；不自行选择企业身份协议 |

本次已明确可执行范围：应用/密钥成功响应 data 包装、GET models/quota、请求体与 ID 的 400 校验、解析错误脱敏，以及对应真实 service + H2 + MockMvc 测试。H2 与测试身份不替代真实数据库或企业身份验收。全部 BE-201～205 完整任务在依赖未确认与未验证前保持未勾选。
### BE-P20 部分交付与暂停记录

- 提出方：后端执行模型。状态：执行中（已交付子项，任务包阻塞待确认）。
- 分支 feature/backend-p20-codex-be-0912；领取 ea68d2e；实现 bd95691（fix(backend): align BE-201-BE-204 application api envelopes）。已在独立集成目录本地 dev 合入并普通推送，随后 fetch 验证 origin/dev 包含 bd95691。
- 自检：仅后端 4 个生产文件、1 个新增 API 测试与计划/沟通文档；无前端、DDL 或数据库迁移修改。data 包装、GET 子资源和解析安全修复符合 CONTRACT-V2-001；整体 V2 契约仍待本节 BE-P20-001～005 确认。
- 测试：Java 17.0.19；目标测试 16/16；功能目录与独立 dev 集成目录分别执行 mvn -B verify，14 模块成功，441 项中 425 通过、16 环境跳过，0 失败/错误。跳过原因及命令见 BACKEND_PLAN.md 本次执行记录；无独立后端 lint 配置，git diff --check 通过。
- 未验收：BE-201～205 均未勾选。真实 MySQL/PostgreSQL/Redis、Provider、企业身份、前后端首调 E2E 与性能未执行。不得以本次 H2/MockMvc 结果宣称生产链路成功。
- 影响/回滚：密钥调用方应读取统一 data 包装，字段仍为当前 DTO，未提供双结构；必要回退 bd95691，数据库无迁移回滚需求。前端文件由 FE-P20 负责人维护。
- 占用：BE-P20 改为阻塞，保留原负责人、暂停后续实现，未标记完成或解除占用。需架构确认 BE-P20-001～005 后继续，不重复领取或接管。
## BE-P21 执行审查（2026-09-12，codex-be-0912）

提出方：后端执行模型。任务：BE-211～BE-215，领取提交 45ce9c9。按 BACKEND_PLAN 已明确路径推进渠道、嵌套凭证、上游模型、虚拟模型及嵌套路由；不保留旧 HTTP 路径双入口。内部类名可复用，不据此反推产品范围。

| 编号 | 状态 | 差异及需确认的契约 |
|---|---|---|
| BE-P21-001 | 待确认 | Channel DTO 仍用 type/proxy_url/connect_timeout_ms/read_timeout_ms/default_headers/enabled，计划用 provider_type/proxy/timeouts/headers/status/health/priority/weight；需冻结精确请求和响应，不擅自添加双字段。紧急启停与草稿状态的独立命令、版本和运行广播契约尚未提供。 |
| BE-P21-002 | 待确认 | 渠道 Key DTO 缺可编辑 priority，存在 rpm_limit/tpm_limit 命名；冷却/即时启停、最后可用 Key 影响与跨实例同步需确认；不以草稿写成功表示运行切换。 |
| BE-P21-003 | 待确认 | 现有模型导入无同步预览/提交 token、请求幂等键与 model_sync_job/item，UpstreamModelRecord 无 locked_fields；需 DB-213 与架构确定预览快照、锁定字段及幂等事务契约，禁止通过临时内存状态冒充完成。 |
| BE-P21-004 | 待确认 | 虚拟模型仍为 alias/display_name/routing_strategy/enabled，缺持久化安全能力交集、显式收紧和应用影响 DTO；等待 DB-214/215 与 BE-P20 已提出的运行可用性端口收口。 |
| BE-P21-005 | 待确认 | route runtime_status 仅依据静态配置且 active_credential_count 当前写死 0，未反映固定快照/健康/容量；发布校验与运行状态响应需明确，不能以管理草稿视图声明运行成功。 |
| BE-P21-006 | 待确认 | 实际迁移的 batch_check_job 缺仓储要求的 operator_id/command 等字段，批量检测无法持久化；需 DB-213 确认采用现有批量表还是统一 model_sync_job/item。已修复请求解析、输入归属、事务和连接释放；合法请求在当前 schema 明确返回 CONFIG_DATA_UNAVAILABLE/503，未验收成功批量检测。 |

本次无需重新设计即可执行：按计划改为 /admin/channels、/admin/upstream-models、/admin/virtual-models、嵌套 credentials/routes；服务层校验 parent-child 归属；统一 ID 与参数错误；按 BE-215 允许零权重并验证更新不可暗换路径；补充真实 service/JDBC/H2/MockMvc 回归、事务和权限测试。跨包契约未确认项单独保留未验收。

补充：沿用现有 ErrorCode，OBJECT_REFERENCE_INVALID 与 PROVIDER_ADAPTER_NOT_FOUND 为 HTTP 422，参数格式错误为 400，不能未确认擅改全局错误码。轮换审计使用现有配置动词 UPDATE 加敏感字段变更摘要，不新增审计枚举。凭证删除占用查询失败现拒绝操作；真实共享占用与删除互斥尚需运行端口验收。

前端联调影响：本次 HTTP 路径切换为 channels/upstream-models/virtual-models 和嵌套 credentials/routes，原 providers/provider-models/model-aliases/route-candidates 不保留兼容映射；FE-P21 与应用模型选择器需由前端负责人同步。DTO 仅交付已存在且本次测试明确的字段，不宣称上述待确认 V2 字段齐备。
