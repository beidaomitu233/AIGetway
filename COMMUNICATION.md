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
## 8. FE-P20 部分交付与契约依赖（2026-09-12）

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
| -- | --- | ---- | ------ | ---- | --------- | --------- | ------ | -- | ---- |
| FE-P20-001 | 前端执行模型 | 列表/权限契约 | FE-201 缺部门/预算筛选字段定义及 24h/峰值响应；应用页面仍基于 /ui 前缀；401 企业登录跳转与细粒度创建权限尚未冻结 | 关联 BE-P20-001/005，确认精确 DTO、页面根与身份入口；已完成现有字段的 URL 筛选、排序、错误和身份清理，不伪造统计 | ApplicationListPage、ApplicationFormPage、应用路由/会话 | ApplicationService、bootstrap/auth | application、usage_aggregate、member | 待确认 | 已实现可执行子项；FE-201/202 完整验收未通过。长整型 Token 超出 JS 安全范围的传输口径也需契约明确 |
| FE-P20-002 | 前端执行模型 | 密钥轮换 | 后端 bd95691 已修复 data 包装，但当前原地轮换无宽限/新记录/幂等；key_value/masked_value 与计划 secret/key_prefix 未收口 | 关联 BE-P20-002，由架构/DB 定义精确请求、结果与宽限策略；不加双响应兼容。前端已做现有响应的一次显示、清理、复制失败与范围校验 | ApplicationKeyPanel、ApplicationKeySecretDialog、api/applications | ApplicationKeyController/Service | application_key、audit_log | 待确认 | 当前立即轮换行为保留为既有接口，未宣称 V2 宽限轮换完成；FE-204 不勾选 |
| FE-P20-003 | 前端执行模型 | 模型/审计/影响接口 | 当前 model-aliases enabled 不代表已发布可路由，缺授权/状态影响结果与应用审计 GET；新 GET models 仍为授权配置视图 | 关联 BE-P20-001/003，确认活动目录、能力交集、受影响密钥/近期调用与应用审计；不能用全局审计模糊筛选替代完整应用审计 | ApplicationDetailPage、ApplicationFormPage | application/alias/audit services | model_permission、key、trace、audit_log | 待确认 | 已保留局部错误和权限控制；现有审计链接仅导航，不代表应用审计闭环；FE-202/203/205 未勾选 |
| FE-P20-004 | 前端执行模型 | 额度规则冲突 | 前端按 PRD 预览并提示降低后停止新请求，但后端仍拒绝低于已用+预占；缺周期历史/统一时区/remaining/reset_at 最终契约，PUT 调整账本尚不完整 | 关联 BE-P20-004；后端完成原子策略/账本后联调。前端剩余值按返回的 limit-used-reserved 定点计算；period_end 仅称周期结束，不冒充重置时间 | ApplicationDetailPage、ApplicationQuotaSummary、applicationValues | ApplicationService、quota port | application_quota_policy、quota_adjustment、usage_ledger | 待确认 | 前端校验/预览/失败处理已验证，真实结算与降低额度成功未验证；FE-205 未勾选 |

### 本次执行与测试记录

- 任务：FE-P20（FE-201～FE-205），领取 7e6c6dc，分支 feature/frontend-p20-codex-0912。前端实现提交 418218d、ef8a972；同步后端 bd95691 后完成门禁。未修改后端、数据库、迁移或依赖锁文件。
- 修改文件：light-ai-admin-ui/src/api/applications.ts；src/pages/applications 下 ApplicationListPage.vue、ApplicationFormPage.vue、ApplicationDetailPage.vue、ApplicationKeyPanel.vue；新增 ApplicationKeySecretDialog.vue、ApplicationQuotaSummary.vue、applicationValues.ts；测试 applicationPages.test.ts、applicationP20.test.ts、fixtures/application.ts。文件前缀均为 light-ai-admin-ui。
- 组件：既有列表/表单/详情/密钥组件改造；新增一次性密钥弹窗与额度明细组件；新增页面域内定点金额、整数/周期/IP 校验。不引入状态库或新测试框架。
- 接口消费：现有 GET/POST applications、GET/PUT application detail、POST status、GET/POST keys、POST key rotate/status/revoke、PUT models/quota、GET/POST quota/adjustments、POST quota/reset、GET members、GET model-aliases。统一经现有 data/error 请求层；新 GET models/quota 本包未改为重复读取，因为详情已有相同子对象。
- 状态：loading、保留数据的刷新、未创建/筛选空态、error/403、权限裁剪、表单非法值、409 保留输入与对比、提交中、撤销失败、局部查询失败、复制失败、未知枚举、身份/应用切换、过期读取与迟到签发结果。写入成功仅以 API 成功结果为准。
- 新增 31 项回归；应用相关 39 项通过。最终全量 24 文件/199 项通过，0 失败/0 跳过；typecheck/build 通过；lint 0 error/81 个未修改文件的 warning；git diff --check 通过。命令与浏览器检查详见 FRONTEND_PLAN.md 附录。
- 原始日志/截图位于独立工作目录 output/playwright，未提交；浏览器为明确标记测试夹具。未执行真实企业身份、DB/Redis/Provider/首调 E2E、完整预算/宽限/归档及性能验证。
- 任务主勾选：FE-201～FE-205 全部保持未勾选；已完成的前端子项允许审查合入，完整任务包等待上述契约与真实联调，状态为阻塞。远程合入与占用最终状态由后续 TASK_STATUS.md 记录确认。

### FE-P20 远程交付确认

2026-09-12：在独立 clone 的本地 dev 合入功能分支，再同步最新 origin/dev（含他人的 45ce9c9 任务登记），普通推送成功。fetch 验证远程 5b0b55d 包含 418218d、ef8a972、56347c7；合并后的 light-ai-admin-ui 文件树与全部门禁通过的 ef8a972 完全一致，git diff --check 通过。未推送功能分支，未强推，未修改其他负责人的任务记录。FE-P20 登记为阻塞，保留负责人，未解除占用；FE-201～FE-205 保持未勾选，等待 FE-P20-001～004/BE-P20-001～005 和真实联调完成后继续。


## FE-P21 契约核对（2026-09-12，前端执行模型）

以 origin/dev 344c398 为核对点，BE-P21 进行中。已确认路径目标不等于现有运行契约；待后端交付后再对齐，不构造未定义响应。

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
| -- | --- | ---- | ------ | ---- | --------- | --------- | ------ | -- | ---- |
| FE-P21-001 | 前端执行模型 | 接口字段与路径 | Channel Controller 仍为 providers；列表无活动 Key 数、优先级/权重和最近成功；Key 仍挂 credential-pools，缺渠道嵌套影响与批量检测契约 | 请 BE-P21 公布完整路径、DTO、权限和批量终态契约，禁止把池数当 Key 数 | providers、credentials、导航/API | channel、check | channel、channel_credential | 待确认 | 先修复确定的输入安全与交互，不虚构数据 |
| FE-P21-002 | 前端执行模型 | 同步契约缺失 | 上游模型仍用 provider-models 路径，但 Java DTO 已改 channel_id；缺同步新增/变化/下线/冲突及 locked_fields 预览提交契约 | 明确安全同步和人工字段锁定的服务端保证；前端不得用旧导入假装同步 | models、providerModels API | upstream | upstream_model、model_sync_job/item | 待确认 | 等待 BE-213 收口 |
| FE-P21-003 | 前端执行模型 | 虚拟模型与路由冲突 | 旧候选前端提交 provider_model_id/credential_pool_id，Java 已为 upstream_model_id/channel_id；weight 最小 1 与计划允许 0 冲突；缺能力交集、授权应用影响及发布影响契约 | 需 BE-214/215 明确 code、嵌套路由、零权重及影响 DTO 后接入，避免擅自改变运行语义 | aliases、modelAliases API | alias、config-release、runtime | virtual_model、route_candidate | 待确认 | FE-214/215 完整验收待服务端契约与联调 |
