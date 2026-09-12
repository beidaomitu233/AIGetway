# 轻享 AI V2.0 前端执行计划

## 1. 执行边界

开发基线统一为 origin/dev。FE-P20 已由 codex-0912 领取，不得重复领取；当前占用以 TASK_STATUS.md 为准。CONTRACT-V2-001 的响应包装与 GET 子资源已部分交付，剩余字段见本文 FE-P20 阻塞依赖处理及 COMMUNICATION.md 第 8 节。原负责人可推进页面、状态及同契约夹具测试，真实联调须等待对应后端契约收口，不以模拟结果勾选完成。

任务包从 P20 编号，用于避免与既有提交记录中的编号冲突。技术栈沿用 Vue 3、TypeScript、Vue Router、Pinia、Vite、fetch/AbortController、Vitest/Vue Test Utils；首期只交付集中式浅色管理后台，目标桌面宽度 1024—1920，重点验收 1280 与 1920。

## 2. 路由、状态和调用规则

- 一级导航：总览、应用中心、AI 资源、运行观测、开发接入、系统管理。AI 资源包含渠道、上游模型、虚拟模型与路由、配置发布；运行观测包含调用记录、用量与成本、额度流水。
- 应用路由：`/applications`、`/applications/new`、`/applications/:id`、`/applications/:id/settings`、`/applications/:id/integration`。
- 资源路由：`/channels/**`、`/models/upstream`、`/models/virtual/**`、`/config/releases`；观测路由：`/calls/**`、`/usage`、`/usage/adjustments`。
- 列表筛选写入 URL；快速切换取消旧请求并用请求序号防止旧响应覆盖。首次 loading、保留旧数据的 refresh、empty、filter-empty、error、403 分开处理。
- 表单使用服务端返回的 `version` 或 ETag；409 保留用户输入并展示差异。高风险动作先查询影响，再二次确认。
- 一次性密钥弹窗禁止自动关闭；密钥不进入 URL、localStorage、日志、埋点和示例源码。
- 权限由 bootstrap 返回的角色、permissions 和 application scopes 控制入口与按钮；页面隐藏仅改善体验，API 仍必须鉴权。

## FE-P20 应用接入工作台（5 项）

- [ ] 任务编号：FE-201
  模块：应用列表
  目标：完成应用搜索、状态/部门/负责人/环境/预算筛选、排序、分页和行跳转。
  实现说明：展示应用、负责人/部门、状态、可用模型、活动密钥、Token、金额、RPM/TPM、24 小时运行摘要；筛选进入 URL。
  依赖接口：`GET /admin/applications`。依赖表：application、application_key、application_quota_policy、usage_aggregate。
  状态处理：首次加载、刷新、无应用、筛选无结果、接口失败、403。
  验收标准：应用负责人只看到授权应用；禁用/归档入口符合权限；返回列表保留筛选。
  测试要求：覆盖范围裁剪、分页、旧请求取消、空态和 403。

- [ ] 任务编号：FE-202
  模块：应用创建与编辑
  目标：完成名称、code、部门、负责人、环境、说明、初始模型、Token/金额/币种/周期、RPM/TPM 和状态表单。
  实现说明：code 创建后不可编辑；无限制选项明确风险；金额用 decimal 字符串；保存前展示摘要。
  依赖接口：`POST /admin/applications`、`PUT /admin/applications/{id}`、虚拟模型选择接口。
  状态处理：本地校验、code 冲突、版本冲突、权限失败、保存中和成功跳转。
  验收标准：非法周期、负数额度、越权模型不可提交；409 保留输入。
  测试要求：覆盖创建成功、code 重复、编辑冲突和无限制警告。

- [ ] 任务编号：FE-203
  模块：应用详情与页签
  目标：形成概览、接入密钥、可用模型、额度与速率、调用、用量、成员与审计工作台。
  实现说明：页头只保留名称、code、状态、负责人和必要操作；未首调显示接入清单，首调后显示运行摘要。
  依赖接口：`GET /admin/applications/{id}` 及各页签子资源。
  状态处理：应用不存在、跨应用 403、禁用、归档、局部页签失败。
  验收标准：页签可直达并保留应用上下文；局部失败不清空其他页签。
  测试要求：覆盖权限、页签路由、禁用/归档影响提示。

- [ ] 任务编号：FE-204
  模块：应用密钥
  目标：创建、轮换、禁用、启用和撤销应用密钥。
  实现说明：表单包含名称、有效期、IP/CIDR、密钥级 RPM/TPM 和模型子集；完整值只在成功弹窗出现一次。
  依赖接口：`/admin/applications/{id}/keys/**`。依赖表：application_key、audit_log。
  状态处理：策略越界、名称非法、轮换宽限非法、撤销失败、复制失败；名称仅为显示标签，轮换代际允许同名。
  验收标准：关闭弹窗后不能恢复原文；密钥级配置不能放宽应用策略；撤销二次确认。
  测试要求：覆盖一次显示、宽限/立即撤销、掩码列表和敏感存储检查。

- [ ] 任务编号：FE-205
  模块：应用模型、额度与成员
  目标：完成模型授权/取消、参数上限、额度预览与调整流水、成员与应用审计。
  实现说明：额度集中显示已用、预占、剩余和重置时间；降低上限到已用以下提示“保存后立即停止新请求”，不终止已准入的流；所有调整要求原因。
  依赖接口：`/models`、`/quota`、`/quota/adjustments`、`/members`、`/audit`。
  状态处理：不可路由模型、并发版本冲突、调整重复、成员源不可用。
  验收标准：取消授权显示影响密钥与近期调用；调整结果与流水一致。
  测试要求：覆盖授权、额度增减/重置、版本冲突和应用范围。

## FE-P21 渠道、上游模型与路由（5 项）

- [ ] 任务编号：FE-211
  模块：渠道列表与表单
  目标：完成渠道搜索、Provider/配置状态/健康/模型筛选和创建编辑。
  实现说明：展示 Base URL 主机、活动 Key 数、模型数、优先级、权重、最近检测/成功/错误；表单含超时、代理和非敏感头。
  依赖接口：`GET/POST/PUT /admin/channels`。
  状态处理：危险 URL、重复名称、版本冲突、无权限。
  验收标准：前端只做即时校验，服务端 SSRF 结论为准；禁用前展示影响。
  测试要求：覆盖筛选、URL 错误、409 和权限按钮。

- [ ] 任务编号：FE-212
  模块：渠道详情与 Key 池
  目标：完成概览、渠道 Key、上游模型、健康记录和调用记录页签。
  实现说明：Key 只展示名称、掩码、配置/健康、优先级、权重、RPM/TPM 和最近结果；添加/轮换采用一次性输入。
  依赖接口：`/admin/channels/{id}/credentials/**`、检测与影响接口。
  状态处理：单 Key 检测失败、批量部分失败、最后可用 Key 禁用警告。
  验收标准：批量检测展示进度和逐项终态；不泄露上游正文与凭证。
  测试要求：覆盖一次输入、部分失败、影响确认和状态分离。

- [ ] 任务编号：FE-213
  模块：上游模型目录
  目标：完成同步预览、导入、人工创建编辑、启停和价格能力维护。
  实现说明：显示真实 model_id、渠道、能力、上下文、最大输出、价格、币种、来源和锁定字段。
  依赖接口：`/admin/upstream-models/**`、渠道同步预览/提交。
  状态处理：价格缺失、能力冲突、同步下线、人工字段冲突。
  验收标准：预览明确新增/变化/下线/冲突；提交不覆盖人工锁定字段。
  测试要求：覆盖预览提交、禁用保存、启用校验。

- [ ] 任务编号：FE-214
  模块：虚拟模型与应用影响
  目标：完成虚拟模型列表、详情、能力限制、已授权应用和用量摘要。
  实现说明：code 创建后只读；能力展示候选安全交集和管理员收紧值；状态改变先查询应用影响。
  依赖接口：`/admin/virtual-models/**`。
  状态处理：code 冲突、能力越界、无候选、被应用引用。
  验收标准：应用只看到已发布可用模型；禁用操作展示受影响应用。
  测试要求：覆盖能力交集、引用影响和状态操作。

- [ ] 任务编号：FE-215
  模块：路由编辑器
  目标：管理候选渠道/上游模型、优先级、权重、状态和条件。
  实现说明：即时展示重复、渠道模型不匹配、无 Key、能力不一致、价格缺失和应用影响；保存仅形成草稿。
  依赖接口：`/admin/virtual-models/{id}/routes`、`/admin/config-releases/impact`。
  状态处理：拖动排序冲突、候选失效、草稿版本冲突。
  验收标准：权重只在同优先级解释；零权重候选有明确含义；未发布不得显示运行成功。
  测试要求：覆盖新增、排序、校验、409 和发布前后状态。

## FE-P22 观测、发布与接入（5 项）

- [ ] 任务编号：FE-221
  模块：调用记录
  目标：按时间、应用、密钥、虚拟/上游模型、渠道、状态、错误和 request_id 查询并查看详情。
  实现说明：详情时间线依次展示准入、路由、Attempt、恢复、流提交、结算和终态；敏感字段只显示别名或掩码。
  依赖接口：`GET /admin/calls`、`GET /admin/calls/{requestId}`。
  状态处理：Trace 运行中、部分观测延迟、过期、403、导出失败。
  验收标准：应用负责人仅见所属应用；刷新不打乱时间线；默认无消息正文。
  测试要求：覆盖筛选、运行中刷新、范围、脱敏和导出。

- [ ] 任务编号：FE-222
  模块：用量成本与额度流水
  目标：展示请求、成功率、输入/输出 Token、金额、预算使用率、趋势、分组和流水。
  实现说明：应用、密钥、虚拟/上游模型、渠道和时间共用筛选；多币种分组；ESTIMATED 与 ACTUAL 可区分。
  依赖接口：`/admin/usage/**`、`/admin/applications/{id}/quota/adjustments`。
  状态处理：聚合延迟、无数据、不同币种、导出失败。
  验收标准：所有卡片/图表/表格口径一致；无可信汇率时无总金额。
  测试要求：覆盖筛选联动、多币种、估算标记和零值。

- [ ] 任务编号：FE-223
  模块：配置发布
  目标：展示草稿差异、校验、应用影响、发布进度、实例结果、历史和回滚。
  实现说明：VALIDATING/ACTIVATING 使用真实轮询；失败保留上一活动版本；即时安全操作不混入草稿。
  依赖接口：`/admin/config-releases/**`。
  状态处理：校验错误/警告、实例部分失败、轮询中断、版本冲突。
  验收标准：任务运行时不提示成功；回滚展示影响并生成新记录。
  测试要求：覆盖全状态、旧响应保护、失败保护和回滚。

- [ ] 任务编号：FE-224
  模块：开发接入与在线测试
  目标：从应用详情提供 Base URL、认证、模型、限制、Java/Python/JS/cURL 示例和真实链路测试。
  实现说明：示例只用占位符；测试选择已授权模型，展示响应、Usage、耗时和 request_id。
  依赖接口：`GET /admin/applications/{id}/integration`、受控测试接口。
  状态处理：无密钥、无模型、预算耗尽、限流、流式失败。
  验收标准：测试走正式准入/路由/结算并标记来源；失败展示统一错误。
  测试要求：覆盖无前置、同步、流式、取消和敏感数据检查。

- [ ] 任务编号：FE-225
  模块：总览
  目标：按角色范围展示风险、请求/Token/金额/RPM/TPM/P95、趋势、应用排行和渠道健康。
  实现说明：时间范围进入 URL，所有区域共享口径和更新时间；点击风险进入预设筛选列表。
  依赖接口：`/admin/overview/**`。
  状态处理：字段完整零值、聚合失败、部分区域失败、权限裁剪。
  验收标准：应用负责人只见所属应用摘要；聚合失败不伪装为空集合。
  测试要求：覆盖范围、钻取、零值、局部失败和角色差异。

## FE-P23 身份、系统与交付（5 项）

- [ ] 任务编号：FE-231
  模块：企业身份入口
  目标：完成登录跳转、回调、无角色受限、会话过期、退出和原目标恢复。
  实现说明：单身份源只展示企业账号登录；不提供注册、找回密码、短信或社交登录。
  依赖接口：企业身份适配与 bootstrap。
  状态处理：身份源超时、回调非法、无角色、会话过期。
  验收标准：退出清除敏感页面状态；错误页面提供可执行重试。
  测试要求：覆盖登录、回调、无角色、过期和退出。

- [ ] 任务编号：FE-232
  模块：系统设置与审计
  目标：管理身份适配、时区、留存、诊断采样、网络策略、预算告警和运行默认值，并查询审计。
  实现说明：高风险设置展示影响并要求权限；审计展示前后摘要但不显示密钥原文。
  依赖接口：`/admin/settings`、`/admin/audit-logs`。
  状态处理：只读角色、版本冲突、设置校验、导出失败。
  验收标准：页面不出现支付/订阅配置；关键设置改动形成审计。
  测试要求：覆盖权限、409、审计脱敏和导出。

- [ ] 任务编号：FE-233
  模块：通用交互与可访问性
  目标：统一分页、表单、确认、一次性密钥、冲突、页面状态和 request_id 错误组件。
  实现说明：键盘焦点、对话框焦点圈、表格溢出、数字列和状态色满足桌面管理场景。
  依赖接口：统一错误契约。
  状态处理：网络断开、取消、重复提交、未知错误。
  验收标准：1280/1920 无非预期横向溢出；错误可复制 request_id。
  测试要求：组件测试与关键页面视觉检查。

- [ ] 任务编号：FE-234
  模块：前端安全
  目标：验证密钥、认证头、消息正文和连接信息不进入持久化、URL、日志和埋点。
  实现说明：只在内存持有一次性密钥；页面离开清理请求、轮询和敏感状态。
  依赖接口：敏感字段裁剪后的 DTO。
  状态处理：复制失败、下载取消、浏览器刷新。
  验收标准：浏览器存储、控制台和网络错误展示无敏感值。
  测试要求：自动扫描加人工 DevTools 检查。

- [ ] 任务编号：FE-235
  模块：前端门禁
  目标：完成 lint、typecheck、组件/页面测试、production build 和关键 E2E。
  实现说明：使用真实后端契约；Mock 只能作为开发夹具，不能作为发布成功证据。
  依赖接口：BE-P20—P23 稳定契约。
  状态处理：缺测试环境时登记 COMMUNICATION 并保留未勾选。
  验收标准：命令、版本、通过数和失败数可复核；无未解释跳过项。
  测试要求：应用首调、渠道多 Key、发布、调用详情和四角色 E2E。

## FE-P20 阻塞依赖处理（2026-09-12）

原负责人 codex-0912 与原文件占用保持不变。后台契约的目标定义见 BACKEND_PLAN.md「BE-P20 架构处理结论」；下面是待实现接口，不能当作当前服务已返回的字段。

- FE-201/202：新增 department、budget_status 筛选；分页沿用 page_size；DUPLICATE_APPLICATION_CODE/409 在 code 处提示并保留输入。requests_24h 为十进制整数字符串，success_rate_24h 为 0～1 字符串，无请求为 null；预算不足不与聚合加载失败混淆。列表峰值暂不展示，见 BACKEND_PLAN 补充契约。
- FE-204：目标原文字段统一 secret，普通读取仅 key_prefix/masked_value。创建/轮换传同一逻辑提交的 idempotency_key；用户改变表单后生成新键，网络重试复用原键。secret_available=false 时只显示操作已生效但原文无法重取，不能显示复制按钮。宽限上限未配置时只开放立即轮换。
- FE-205：allow_stream 替代 stream_allowed，null 表示继承；GET /model-options 用于授权候选，GET /models 用于已配置权限（可能失效），不得混用。取消授权仍需展示影响。
- 额度展示 tokens_remaining、amount_remaining、period_id、policy_version、timezone、reset_at；null 上限显示无限制。降低至已用/预占以下允许提交，必须确认保存后停止新准入。SCHEDULED 只提示已预约，APPLIED 才提示已生效。
- 额度命令和预约状态使用 BACKEND_PLAN 定义的统一结果；原负责人通过 adjustments 的 operation_id 查询预约结果。人工重置要求输入应用 code，明确所重置维度；人工重置/续期遇到 OBJECT_IN_USE 保留表单并提示禁用后等待运行请求排空，不自动重试高风险操作。
- 企业身份、真实 Provider 和首次真实调用 E2E 保持未验收。同步契约改动必须由当前负责人合入，待前后端共同切换后移除旧字段使用，不增加长期双字段兼容。

原负责人可按契约准备组件与接口夹具；只有真实接口及对应验收通过才能勾选 FE-201～205。

数值、路径与新增读取接口的执行说明：

- 64 位 Token/计数/版本按 BACKEND_PLAN 使用字符串；前端用 BigInt 或既有精确整数工具运算，提交仍为字符串，禁止先 Number 再转回。验证大于 JS 安全整数的值、最大 long、负数/越界和小数。
- PRD 中 /applications 等仍是 V2 目标页面路径，当前实现 /ui/applications 是过渡现状。P20 不自行切换全站根路径；根路径/登录入口统一由 FE-P23/BE-205 交付并验收深链刷新、静态资源和返回地址。401 当前只清理身份/敏感状态并显示会话失效，身份源入口未确认前不跳转猜测的登录地址；路径和登录闭环继续列为待验收。
- 创建使用无应用 ID 的 GET /admin/applications/model-options；已有应用用 /{id}/model-options。状态/取消授权前调 /{id}/impact 展示影响和阻止原因，提示预览时间；不得用预览替代最终 409 处理。应用审计使用 /{id}/audit，legacy_partial 要明确显示历史覆盖不完整。依赖端点未交付时仅准备夹具，不能称真实成功。

## FE-P20 前端部分交付（2026-09-12）

负责人：前端执行模型 codex-0912；分支 feature/frontend-p20-codex-0912。领取 7e6c6dc；前端实现 418218d、ef8a972，已同步后端 bd95691。以下仅为已实现并经前端测试验证的子项；FE-201～FE-205 的主勾选框全部保持未勾选，完整验收依赖 COMMUNICATION.md 的 BE-P20-001～005 和 FE-P20-001～004。

| 任务 | 本次前端结果 | 待验收/阻塞 |
|---|---|---|
| FE-201 | 负责人筛选、最近调用默认排序、URL 还原、刷新/空态/403、身份切换清理；金额定点运算 | 部门/预算筛选、24h/峰值字段和范围真实联调，见 BE-P20-001 |
| FE-202 | 显式填写额度、整数/decimal/币种/周期校验、无限制警告、保存摘要、编辑加载隔离、409 对比后保留输入 | code 冲突最终错误码、已发布可路由模型目录、企业身份权限与真实保存联调 |
| FE-203 | 页签 URL 同步与权限、切换应用中止旧请求、局部错误重试、保留详情刷新、未知状态禁止写入 | 应用审计/影响接口、归档运行请求检查、最终页面路径与会话失效入口 |
| FE-204 | 密钥级 RPM/TPM/模型子集/IP/CIDR/有效期校验；防重提交；一次性弹窗拆分、复制失败提示、离开清理与切换确认 | 新记录轮换/宽限/幂等与最终 secret/key_prefix 字段，见 BE-P20-002；现有立即轮换不等于 V2 全量验收 |
| FE-205 | 已用/预占/剩余额度明细、精确金额、调整预览、低于用量警告、成员与流水局部错误、写权限检查 | 授权影响与能力交集、应用审计、降低额度后端仍拒绝、周期/重置字段与真实账本一致性 |

验证环境：Windows、Node 20.19.6、npm 10.8.2，独立 worktree；npm ci 使用既有锁文件，无新增依赖。最终代码 ef8a972：npm run typecheck 通过；npm run lint（JSON 报告）0 error/81 warning，警告仅来自未修改的 ApplicationIntegrationPage.vue（44）、AuditDetailPage.vue（36）、AuditListPage.vue（1）；npm test 24 个文件/199 项通过、0 失败/0 跳过（本包新增 31 项，应用相关合计 39 项）；npm run build 通过；git diff --check 通过。

浏览器验证：Playwright CLI + 本机 Vite + 明确标记的网络夹具，应用列表/详情/表单各在 1280×1080、1920×1080 检查；document 与 main 无额外横向溢出，表格容器内滚动。已查看三张 1280 截图，修复列表标题间距并复测几何。截图与原始日志仅存本地 output/playwright，不提交临时产物。此项不是实际后端或真实业务首调验收。

未执行：真实企业登录、真实数据库/Redis/上游联调、应用密钥首调 E2E、宽限轮换、真实预算结算/归档互斥与性能。后端的 H2/MockMvc 结果为其独立证据，不冒称本次前端真实 E2E 通过。


## FE-P21 本次前端交付记录（2026-09-12）

负责人：前端执行模型 codex-0912；分支 feature/frontend-p21-codex-0912；领取 344c398 已普通推送 dev 并回读确认。独立目录 .worktrees/frontend-p20-baseline，集成使用 .worktrees/frontend-p20-delivery 的本地 dev。实现提交 def540e、b27689d、fd9014a；已同步后端 c08d625 和最新远程任务记录。以下仅为已验证子项，FE-211～FE-215 主任务仍全部未勾选。

| 任务 | 已验证子项 | 未满足验收项 |
|---|---|---|
| FE-211 | channels CRUD/状态/影响/检测路径；地址只显示主机；URL 认证信息/协议/片段校验；请求头新增、重复/认证头/换行与提交阻止；409 保留输入、编辑状态不能绕过影响入口 | V2 精确聚合/优先级权重/模型筛选字段、即时影响与真实 SSRF 联调（BE-P21-001） |
| FE-212 | 渠道详情挂载 Key 面板；所有 Key API 嵌套渠道；掩码读取、一次性输入清理、提交中关闭保护；局部错误与空态分离；旧池入口重定向渠道列表 | Key priority、最后可用 Key 影响、批量检测/健康与调用页签、真实运行切换（BE-P21-002/006） |
| FE-213 | upstream-models CRUD/启停/检测与 channel_id；能力未知不默认支持，禁用草稿保留 null 价格；启用要求显式能力/价格；安全整数/精度校验、来源、错误与权限；批量渠道参数及平铺详情解析 | 同步预览/锁定/幂等未交付，入口明确提示不可同步；实际批量仍返回 503（BE-P21-003/006），不冒称完成 |
| FE-214 | virtual-models 列表/详情/表单、稳定 code 只读；响应式权限、状态修改走影响入口；应用模型选择器共用 API 同步换路径 | 安全能力交集、管理员收紧、已授权应用影响和真实运行目录（BE-P21-004） |
| FE-215 | 嵌套路由 CRUD/检测/排序；upstream_model_id+channel_id；所属渠道 Key 配置查询；零权重与同级解释；排序数字校验、409、请求竞态隔离；保存只报草稿 | 应用/发布影响与条件、完整能力/价格校验、固定快照及真实健康容量（BE-P21-005）；静态返回不显示运行成功 |

### 已完成子项检查

- [x] 对齐 BE-P21 已交付 HTTP 路径与字段，未自行添加后端接口或数据库字段。
- [x] 补充并更新资源输入、密钥生命周期、权限、异常、排序、旧入口与 API 契约测试。
- [x] 类型检查、lint、全量单测、构建和补丁检查通过；提交代码。
- [ ] FE-211～215 完整业务验收及真实环境联调；保留原负责人，等待上述契约。

### 实际验证

Windows / Node 20.19.6 / npm 10.8.2 / 既有 Vue、TypeScript、Vitest。最终命令：npm run typecheck；npm test -- --maxWorkers=2 --reporter=json --outputFile=../output/p21-delivery-tests.json；npm run lint -- --format json --output-file ../output/p21-delivery-lint.json；npm run build。全部退出码 0；26 文件 / 226 项通过，0 失败、0 跳过；lint 0 error、81 项历史 warning（ApplicationIntegrationPage 44、AuditDetailPage 36、AuditListPage 1），未改其格式。git diff --check 通过。默认并发曾因内存不足中止，旧 JSON 不作为结果；降为 2 worker 后使用新的报告文件，严格检查退出码。

Playwright CLI 以明确标记的测试夹具拦截所有管理 API：1366 桌面检查渠道 Key 掩码/轮换输入与取消、虚拟模型零权重回填和表格；1024 路由页面 documentWidth=1024，11 表头/11 数据列；390 模型表单五项能力默认为待确认，修正 fieldset/grid 后 main 无越界元素，但公共页面壳仍有 20px 横向溢出（documentWidth=410），登记 FE-P21-004 待 FE-P23 处理。截图与原始日志在 output/playwright/p21，未提交；本人 Vite 和浏览器已关闭。未执行真实企业身份、DB/Redis、上游协议、真实发布/调用/批量成功、性能或完整移动端验收。

### 本包前端文件清单

资源页沿用既有目录名，产品入口改为 /ui/channels、/ui/models/upstream、/ui/models/virtual；/ui 挂载前缀的统一迁移仍属 FE-P23。旧池与旧导入源码保留为技术资产，旧池导航退场、旧入口转渠道列表，原池路由测试更新为迁移回归。观测/限流/可靠性文件仅改资源链接或选项查询路径，不扩展对应任务。

- light-ai-admin-ui/mocks/entities.ts
- light-ai-admin-ui/mocks/modelAccessMock.ts
- light-ai-admin-ui/src/api/credentials.ts
- light-ai-admin-ui/src/api/limitPolicies.ts
- light-ai-admin-ui/src/api/modelAliases.ts
- light-ai-admin-ui/src/api/providerModels.ts
- light-ai-admin-ui/src/api/providers.ts
- light-ai-admin-ui/src/app/navConfig.ts
- light-ai-admin-ui/src/app/router.ts
- light-ai-admin-ui/src/components/CheckCommandDialog.vue
- light-ai-admin-ui/src/components/CheckDialog.vue
- light-ai-admin-ui/src/components/KeyValueEditor.vue
- light-ai-admin-ui/src/components/credentials/CredentialFormDialog.vue
- light-ai-admin-ui/src/components/credentials/CredentialPanel.vue
- light-ai-admin-ui/src/components/credentials/CredentialRotateDialog.vue
- light-ai-admin-ui/src/pages/aliases/AliasDetailPage.vue
- light-ai-admin-ui/src/pages/aliases/AliasFormPage.vue
- light-ai-admin-ui/src/pages/aliases/AliasListPage.vue
- light-ai-admin-ui/src/pages/aliases/CandidateFormDialog.vue
- light-ai-admin-ui/src/pages/circuits/CircuitListPage.vue
- light-ai-admin-ui/src/pages/limits/LimitListPage.vue
- light-ai-admin-ui/src/pages/models/BatchCheckPanel.vue
- light-ai-admin-ui/src/pages/models/ModelDetailPage.vue
- light-ai-admin-ui/src/pages/models/ModelFormPage.vue
- light-ai-admin-ui/src/pages/models/ModelImportPage.vue
- light-ai-admin-ui/src/pages/models/ModelListPage.vue
- light-ai-admin-ui/src/pages/models/ModelSyncUnavailablePage.vue
- light-ai-admin-ui/src/pages/providers/ProviderDetailPage.vue
- light-ai-admin-ui/src/pages/providers/ProviderFormPage.vue
- light-ai-admin-ui/src/pages/providers/ProviderListPage.vue
- light-ai-admin-ui/src/pages/reliabilities/ReliabilityListPage.vue
- light-ai-admin-ui/src/utils/resourceValidation.ts
- light-ai-admin-ui/tests/aliasPages.test.ts
- light-ai-admin-ui/tests/applicationP20.test.ts
- light-ai-admin-ui/tests/applicationPages.test.ts
- light-ai-admin-ui/tests/batchCheck.test.ts
- light-ai-admin-ui/tests/credentialPanel.test.ts
- light-ai-admin-ui/tests/governanceForms.test.ts
- light-ai-admin-ui/tests/http.test.ts
- light-ai-admin-ui/tests/layout.test.ts
- light-ai-admin-ui/tests/modelFormAndImport.test.ts
- light-ai-admin-ui/tests/poolPages.test.ts
- light-ai-admin-ui/tests/providerDetail.test.ts
- light-ai-admin-ui/tests/providerPages.test.ts
- light-ai-admin-ui/tests/resourceApiP21.test.ts
- light-ai-admin-ui/tests/resourceP21.test.ts
- light-ai-admin-ui/tests/routerGuards.test.ts
