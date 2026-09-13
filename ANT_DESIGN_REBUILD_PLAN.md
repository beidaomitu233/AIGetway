# 轻享 AI 管理后台 Ant Design 全量重构计划

## 1. 目标与边界

本计划用于将 `light-ai-admin-ui` 从现有自研样式与基础组件体系，整体重构为基于 Vue 3 与 Ant Design Vue 的企业管理后台。重构目标不是替换按钮、输入框或主题色，而是重新建立应用壳层、设计令牌、页面模板、数据展示、表单反馈、业务工作台和交互状态。

业务范围、接口语义和数据模型继续以 `轻享AI-企业AI中台-产品需求说明书-PRD-V2.0.md` 为准。已完成的前后端联调结果属于必须保护的资产；本轮默认不修改管理 API、后端实现和数据库迁移。

### 1.1 必须实现

- 采用 `ant-design-vue` 作为基础组件库，图标使用按需导入的 `@ant-design/icons-vue`。
- 使用 Ant Design `ConfigProvider` 建立统一浅色蓝色主题，形成卡片与半平面结合的视觉体系。
- 重新实现全局布局、导航、页面头、筛选区、表格、表单、详情、抽屉、弹窗、状态反馈和业务页面。
- 保留现有路由 URL、权限判断、接口路径、请求字段、错误码处理、乐观锁、幂等键和一次性密钥安全规则。
- 使用真实接口或现有契约夹具验证页面；不得以静态假数据代替已联调功能。
- 所有主要页面支持 1024、1280、1440、1920 像素桌面宽度，重点验收 1280 与 1920。

### 1.2 不接受的实现

- 在旧页面外层套 `a-card`，内部继续保留旧 `.lai-*` 表格、表单和弹窗。
- 只替换颜色、圆角或按钮，页面信息架构与交互仍沿用旧实现。
- 在业务页面中直接覆盖 Ant Design 内部类名，或依赖 `.ant-*` DOM 结构完成业务逻辑。
- 为适配新 UI 擅自修改接口字段、金额/Token 类型、权限语义或后端错误码。
- 把密钥、认证头、请求正文或完整错误对象写入 URL、浏览器持久化或前端日志。
- 一次性导入全部图标、全部页面或完整图表库，造成无必要的首屏体积增长。

## 2. 技术与设计基线

### 2.1 技术选择

| 项目 | 决策 |
| --- | --- |
| 应用框架 | 保留 Vue 3、TypeScript、Vue Router、Pinia、Vite |
| UI 组件 | 新增 `ant-design-vue`，由 UI-R301 固定兼容版本并写入锁文件 |
| 图标 | `@ant-design/icons-vue` 按组件导入，禁止整包注册 |
| 国际化 | Ant Design 使用 `zh_CN` locale；业务文案继续使用中文 |
| 日期时间 | 统一通过现有 API 字符串和平台时区格式化，不在组件内猜测时区 |
| 图表 | UI-R306 先做体积与可访问性验证，再决定使用 `@ant-design/charts` 或保留轻量 SVG 封装 |
| 测试 | 保留 Vitest、Vue Test Utils；新增关键页面 Playwright 视觉与真实链路回归 |
| API 层 | `src/api/**` 默认冻结，仅允许修复经真实契约确认的问题 |
| 状态与权限 | 保留 `stores/bootstrap.ts`、`routerGuards.ts`、`permissions.ts` 的业务语义 |

### 2.2 视觉方向

| 令牌 | 建议值 | 使用规则 |
| --- | --- | --- |
| 主色 | `#2563EB` | 主按钮、链接、菜单选中、关键数据强调 |
| 主色悬停 | `#1D4ED8` | 交互悬停，不用于大面积背景 |
| 页面背景 | `#F4F7FB` | 内容区底色 |
| 容器背景 | `#FFFFFF` | 卡片、表格、表单、抽屉 |
| 一级文字 | `#172033` | 标题、主要数值 |
| 二级文字 | `#667085` | 辅助说明、时间、次要字段 |
| 边框 | `#E4EAF2` | 卡片、表格和分隔线 |
| 圆角 | `8px` | 普通卡片与控件；避免多套圆角 |
| 阴影 | `0 1px 2px rgba(16,24,40,.04)` | 只用于浮层或重点卡片，保持半平面感 |
| 内容间距 | `24px` | 页面级间距；卡片内部使用 16/20/24 阶梯 |

全局只提供浅色主题。左侧导航使用白色或极浅蓝灰背景，选中项使用浅蓝底和主色文字。统计卡一行最多四张，只展示能驱动判断的指标；业务明细使用表格和描述列表，不把每个字段做成独立卡片。不使用渐变、玻璃拟态、霓虹色、大面积插画或与业务状态无关的彩色 Tag。

### 2.3 Ant Design 组件落位

| 现有能力 | 重构方式 |
| --- | --- |
| `AppLayout` | `a-layout`、`a-layout-sider`、`a-menu`、`a-layout-header`、`a-breadcrumb` |
| `DataTable`、`Pagination`、`ListPager` | `a-table` 的服务端排序/分页能力与 `a-pagination` |
| `FormField`、`KeyValueEditor` | `a-form`、`a-form-item`、`a-row/a-col`、`a-form-list` |
| `AppMultiSelect` | `a-select mode="multiple"`，支持搜索、清空和已选项汇总 |
| `ConfirmDialog`、业务弹窗 | `a-modal`；复杂查看和编辑使用 `a-drawer` |
| `PageState` | `a-skeleton`、`a-empty`、`a-result`、`a-alert`、局部 `a-spin` |
| `StatusText` | 统一状态映射后的 `a-tag` 或 Badge，普通类型仍使用文本 |
| `VersionConflictBanner` | `a-alert` + `a-descriptions`，保留输入和差异操作 |
| 一次性密钥 | 受控 `a-modal` + `a-typography-paragraph copyable`，禁止遮罩/ESC 误关 |
| 批量检测/发布 | `a-steps`、`a-progress`、`a-result`、逐项状态表格 |
| Trace 时间线 | `a-timeline` + 详情 `a-drawer`，运行中刷新保持节点顺序 |

### 2.4 UI-R300 页面与 API 映射

| 页面域 | 保留路由 | 主要读取接口 | 主要写入/动作接口 | 重构任务 |
| --- | --- | --- | --- | --- |
| 总览 | `/ui/overview` | `/admin/overview/**` | 无 | UI-R332 |
| 应用列表 | `/ui/applications` | `/admin/applications` | 归档/状态动作 | UI-R310 |
| 应用创建/编辑 | `/ui/applications/new`、`/:id/settings` | `/admin/applications/{id}/model-options` | `POST/PUT /admin/applications` | UI-R311 |
| 应用详情 | `/ui/applications/:id` | 应用详情及 `/models`、`/quota`、`/members`、`/audit` | 页签动作由 UI-R313/314 负责 | UI-R312～314 |
| 开发接入 | `/ui/applications/:id/integration` | 应用接入、模型和限制 | 受控在线测试 | UI-R315 |
| 渠道 | `/ui/channels`、`/new`、`/:id`、`/:id/edit` | `/admin/channels`、详情与检测记录 | 渠道 CRUD、启停、检测 | UI-R320～321 |
| 上游模型 | `/ui/models/upstream/**` | `/admin/upstream-models/**`、渠道选项 | 同步、导入、启停、编辑 | UI-R322 |
| 虚拟模型/路由 | `/ui/models/virtual/**` | `/admin/virtual-models/**`、路由候选 | 草稿编辑、候选启停和排序 | UI-R323 |
| 限流/可靠性/熔断 | `/ui/limit-policies`、`/ui/reliability-policies`、`/ui/circuits` | 对应 `/admin/**` 策略和运行态接口 | 策略保存、人工摘除/恢复 | UI-R325 |
| 发布 | `/ui/config/drafts`、`/ui/config/publish` | 草稿、校验、影响、实例和历史 | 发布、回滚 | UI-R324 |
| 调用观测 | `/ui/traces/**` | `/admin/traces` | 受控导出 | UI-R330 |
| 用量成本 | `/ui/usage/**` | `/admin/usage/**` | 额度调整 | UI-R331 |
| 系统管理 | `/ui/audit-logs/**`、`/ui/access-credentials/**`、`/ui/runtime-config` | 审计、访问凭证、运行参数 | 高权限设置、受控导出 | UI-R333 |
| 开发接入 | `/ui/developer-access` | 可用应用、模型和运行配置 | 受控在线测试 | UI-R334 |
| 登录/错误 | `/ui/forbidden`、`/ui/not-found`、登录回调 | bootstrap、身份状态 | 登录、退出、重试 | UI-R307 |

页面只负责调用既有 API 模块和展示服务端结果；若映射中发现接口字段缺失，先登记 `UI-ANT-CONTRACT-*`，交 UI-R341 处理，不由业务页面自行改变请求形状。

### 2.5 UI-R300 旧组件去留表

| 现有文件/能力 | 处理决定 | 责任任务 | 保护点 |
| --- | --- | --- | --- |
| `AppLayout.vue`、`base.css` | 完全重写并最终移除旧布局样式 | UI-R302、UI-R342 | 路由、权限和快照上下文不变 |
| `PageState.vue` | 重写为 Ant Skeleton/Empty/Result/Alert 组合 | UI-R303 | loading、empty、error、403 分离 |
| `DataTable.vue`、`Pagination.vue`、`ListPager.vue` | 以 Table/Pagination 新实现替换 | UI-R304 | URL 筛选、取消旧请求、服务端排序 |
| `FormField.vue`、`KeyValueEditor.vue` | 以 Form/Form.List 新实现替换 | UI-R305 | 字段错误、动态行、敏感值不落盘 |
| `ConfirmDialog.vue`、`CheckDialog.vue`、`CheckCommandDialog.vue` | 按风险动作分别迁移 Modal/Progress/Result | UI-R305、UI-R321 | 影响确认、部分失败和最终状态 |
| `SecretInput.vue`、`TokenOnceDialog.vue` | 保留安全语义，重写为受控 Modal/Input | UI-R305、UI-R313 | 原文仅创建成功展示一次 |
| `VersionConflictBanner.vue` | 重写为 Alert/Descriptions | UI-R303 | 409 保留输入并显示最新版本 |
| `StatusText.vue` | 重写为状态映射 + Tag/Badge | UI-R303 | 只给真实状态着色 |
| `AppMultiSelect.vue` | 替换为 Select multiple/TreeSelect | UI-R304 | 多选搜索、清空和已选计数 |
| `TrendChart.vue` | 迁移至 UI-R306 图表封装 | UI-R306 | 真实数据、筛选口径和空态 |
| `credentials/*` | 重写渠道凭证表格与一次性输入 | UI-R321 | 上游 Key 只可掩码读取 |
| `ModulePlaceholder.vue` | 仅在路由尚未交付期间保留，最终删除或限于明确未实现页 | UI-R307、UI-R342 | 不把业务缺失渲染成成功 |
| `useListQuery`、`useFormSubmit`、`useDirtyGuard`、`useLifecycleActions` | 保留行为层，按需改为 Ant 事件适配 | 对应业务包 | 竞态、脏数据和幂等语义不变 |
| `bootstrap`、`permissions`、`routerGuards`、`api/http.ts` | 保留为业务基础，不做视觉复制 | UI-R301、UI-R302、UI-R307 | 鉴权、数据范围和错误解析不变 |

### 2.6 UI-R300 评审结论与待决事项

- 结论：同意以 Ant Design Vue 作为唯一新视图组件体系，采用蓝色浅色、卡片与半平面视觉方向；所有业务页从新页面树实现。
- 结论：保留现有 Vue/Router/Pinia/Vite 和 API/权限/数据契约；不以 UI 重构为理由新增后端模块或迁移。
- 结论：共享层先合入，业务域按 UI-R310～UI-R334 并行；UI-R340～UI-R343 负责跨页视觉、契约和清理。
- 待决：Ant Design Vue 的具体兼容版本由 UI-R301 在当前 Node/Vite 锁定后确定；图表依赖由 UI-R306 做体积和可访问性验证后确定。
- 待决：企业登录真实身份源和 Provider/真实数据库门禁仍沿用既有阻塞记录，不因页面重构标记为通过。
- 执行状态：UI-R300 已完成文档、映射和领取矩阵，提交 `1000902`，等待独立评审；UI-R301～UI-R343 尚未领取。

## 3. 已联调结果保护清单

以下行为在新 UI 中必须原样保留，并由 UI-R341 建立契约保护测试：

1. 应用创建、详情、额度、成员、模型候选和密钥签发继续使用现有 `/admin/applications/**` 契约。
2. 一次性应用密钥读取字段为 `secret`；弹窗关闭后不得从页面状态或浏览器存储恢复原文。
3. 模型授权约束使用 `allow_stream`；创建和编辑候选来自 `/admin/applications/model-options` 与 `/{id}/model-options`。
4. Token、额度、请求计数和版本等 64 位值按十进制字符串处理；金额继续使用 decimal 字符串，禁止转为浮点数参与业务判断。
5. 应用列表保留部门、预算状态等 URL 筛选，并展示 `budget_status`、`requests_24h`、`success_rate_24h`。
6. 渠道继续使用 V2 字段 `provider_type/proxy/timeouts/headers/priority/weight/status/health/version`；启停走独立命令。
7. 渠道详情写操作必须携带服务端返回的 `version`，409 时保留用户输入并展示最新版本。
8. 上游模型渠道选项使用 `provider_type/status`；模型详情保留 `last_check_at/last_error_code/route_candidate_count`。
9. 虚拟模型 24 小时摘要使用带 `_24h` 的稳定 JSON 名；路由权重只在同优先级候选中解释。
10. 列表筛选写入 URL，快速切换取消旧请求并防止旧响应覆盖；首次加载、刷新、业务空态、筛选空态、错误和 403 分开呈现。
11. H2 + 单机 Redis + Vite + Chromium 已验证的应用与渠道链路不得因 UI 重构退化；真实 PostgreSQL/MySQL/Provider 和企业身份仍是后续生产门禁，不能写成已验证。

UI 业务包开始前，执行分支必须先包含 RV-P20 PR #1 或其等价合并结果，不允许复制粘贴旧页面绕过依赖。

## 4. 并行开发规则

1. 每名开发使用独立 worktree 和唯一负责人标识，从最新 `origin/dev` 创建 `feature/frontend-ant-*` 分支。
2. 领取任务前在 `TASK_STATUS.md` 登记任务编号、负责人、独占文件、状态和时间；同一任务包只允许一个负责人。
3. UI-R301～UI-R306 属共享基础层。依赖它们的业务包只能在对应基础提交进入 `dev` 后开始，不允许各业务分支复制一套局部组件。
4. 业务包默认只能修改其表格中列出的页面、同域测试与同域 Mock。修改 `main.ts`、`App.vue`、`AppLayout.vue`、全局主题、路由、导航或 `src/ui/**` 必须由对应基础包负责人完成。
5. `src/api/**`、`stores/bootstrap.ts`、权限枚举和路由路径是保护区。发现契约问题时先登记 `UI-ANT-CONTRACT-*`，由 UI-R341 统一处理。
6. 页面重写使用新组件树，不要求保留旧 DOM 或 `.lai-*` 类名；必须保留业务行为和可观察结果。
7. 每个业务包至少提交一个实现 Commit 和一个测试/复验 Commit；提交前运行定向测试、typecheck、lint 和 build。
8. 合并顺序遵循依赖波次。跨包冲突时由基础层负责人处理共享文件，业务包不得相互覆盖。

## 5. 任务包与依赖波次

### Wave 0：设计契约与迁移护栏

#### UI-R300 设计与契约基线

- 独占范围：`ANT_DESIGN_REBUILD_PLAN.md`、设计评审记录、页面清单；不修改产品代码。
- 工作内容：冻结信息架构、主题令牌、页面模板、状态矩阵、保护契约和任务依赖；为主要页面定义桌面线框与验收截图清单。
- 交付物：设计评审结论、旧组件去留表、路由/页面/接口映射、并行任务领取表。
- 验收：产品、前端和联调负责人确认“重写 UI、不重写业务契约”的边界；所有页面均有归属，无两个包拥有同一文件。

#### UI-R301 Ant Design 工程基座

- 独占范围：`package.json`、`package-lock.json`、`src/main.ts`、`src/App.vue`、`src/design/**`、`src/styles/theme.css`、`src/styles/reset.css`。
- 依赖：UI-R300。
- 工作内容：安装 Ant Design Vue 与图标；配置 `ConfigProvider`、`zh_CN`、主题 token、CSS reset、全局字体、focus 样式和消息容器；验证 Vite 按需打包。
- 验收：示例页能渲染按钮、表单、表格、弹窗和中文分页；无旧全局 `.lai-btn/.lai-input/.lai-table` 依赖；主题色、圆角、字号、间距在单一配置源维护。
- 测试：入口挂载、locale、theme token、production build、依赖体积报告。

### Wave 1：共享框架，可并行执行

#### UI-R302 应用壳层与导航

- 独占范围：`src/layout/AppLayout.vue`、`src/ui/layout/**`、`src/app/router.ts`、`src/app/navConfig.ts`、`tests/layout.test.ts`。
- 依赖：UI-R301。
- 工作内容：用 Ant Layout/Menu/Breadcrumb 重写固定侧栏、顶部上下文栏、折叠菜单、应用上下文、快照/草稿提示和用户区；保持权限过滤和现有路由。
- 验收：应用中心位于 AI 资源之前；菜单选中与展开随路由正确；1024 宽度可折叠且内容无横向溢出；无权限菜单不渲染。

#### UI-R303 页面框架与状态反馈

- 独占范围：`src/ui/page/**`、重写 `src/components/PageState.vue`、`VersionConflictBanner.vue`、新增 `tests/antPageState.test.ts`。
- 依赖：UI-R301。
- 工作内容：建立 `PageHeader`、`PageCard`、`PageSection`、`AsyncState`、`RequestError`、`ConflictAlert`；统一标题、操作区、刷新状态、request_id 和 403/404/500 呈现。
- 验收：首次 loading 不遮挡壳层；刷新保留旧数据；空态和筛选无结果不同；错误显示 request_id 与重试；409 保留表单输入。

#### UI-R304 列表、筛选与表格基座

- 独占范围：`src/ui/data/**`、重写 `DataTable.vue`、`ListPager.vue`、`Pagination.vue`、`AppMultiSelect.vue`、`tests/useListQuery.test.ts`。
- 依赖：UI-R301。
- 工作内容：建立 Ant Table 的服务端分页、排序、固定操作列、行跳转；建立 `FilterBar`、高级筛选 Drawer、列设置和刷新按钮；继续使用现有 `useListQuery` 竞态保护。
- 验收：筛选与排序写入 URL；返回列表保留状态；旧请求不会覆盖新请求；横向滚动只发生在表格容器；键盘可到达行操作。

#### UI-R305 表单、弹窗与敏感操作基座

- 独占范围：`src/ui/form/**`、`src/ui/feedback/**`、重写 `FormField.vue`、`KeyValueEditor.vue`、`ConfirmDialog.vue`、`SecretInput.vue`、`TokenOnceDialog.vue`、新增 `tests/antFormFeedback.test.ts`。
- 依赖：UI-R301。
- 工作内容：统一 Ant Form 校验、两列布局、动态键值、脏数据离开保护、影响确认、危险操作、复制反馈和一次性密钥弹窗。
- 验收：保存只在变化且校验通过时可用；服务端字段错误落到对应表单项；危险操作展示影响；密钥弹窗禁止遮罩/ESC 误关并在关闭后清除原文。

#### UI-R306 指标、图表与诊断展示基座

- 独占范围：`src/ui/metrics/**`、重写 `TrendChart.vue`、图表测试与体积记录。
- 依赖：UI-R301。
- 工作内容：建立指标卡、趋势图、图例、空态、Tooltip 和降级表格；验证图表库体积后再固定实现。
- 验收：一行最多四个指标；颜色只表达业务状态；同一筛选口径驱动摘要、趋势和分组；无数据时不绘制虚假零值。

#### UI-R307 权限与系统状态页

- 独占范围：`src/pages/forbidden/**`、`src/pages/notFound/**`、新增登录/会话状态页面、`tests/routerGuards.test.ts`、`tests/bootstrapStore.test.ts`。
- 依赖：UI-R302、UI-R303。
- 工作内容：重写启动加载、初始化失败、403、404、无角色、会话过期和身份源失败页面；保持原目标恢复和权限守卫。
- 验收：不同故障不显示成空白页；无权限不跳到错误首页；页面不展示调试堆栈或敏感响应。

### Wave 2：业务域页面，可按任务包并行

#### UI-R310 应用列表

- 独占范围：`src/pages/applications/ApplicationListPage.vue`、`applicationValues.ts` 的展示映射、`tests/antApplicationList.test.ts` 与专用夹具。
- 依赖：UI-R303、UI-R304、RV-P20 合并结果。
- 工作内容：重写应用筛选、核心指标摘要、主表、状态操作和空态；使用 Ant Table/Select/Tag/Progress。
- 验收：部门、负责人、环境、预算状态等筛选进入 URL；展示 Token/金额/RPM/TPM、预算状态和 24h 摘要；64 位计数不丢精度。

#### UI-R311 应用创建与编辑

- 独占范围：`ApplicationFormPage.vue`、`tests/antApplicationForm.test.ts`。
- 依赖：UI-R303、UI-R305、RV-P20 合并结果。
- 工作内容：按基本信息、模型授权、额度预算、速率限制分区重写表单；高级项折叠并显示已配置数量；提供提交摘要。
- 验收：候选使用创建 `model-options`；code 编辑时只读；decimal/64 位字符串不转浮点；409 保留输入；无限制配置有明确风险说明。

#### UI-R312 应用详情工作台与概览

- 独占范围：`ApplicationDetailPage.vue`、`src/pages/applications/detail/ApplicationOverviewTab.vue`、其他页签占位接口、`tests/antApplicationWorkbench.test.ts`；不得修改密钥/额度子组件内部。
- 依赖：UI-R302、UI-R303、UI-R306。
- 工作内容：建立应用切换器、环境与状态头、概览/密钥/模型/额度/调用/用量/成员/审计页签和局部加载边界。
- 验收：页签可直达并保留应用上下文；局部失败不清空其他页签；无首调显示接入清单，已有调用显示运行摘要。

#### UI-R313 应用密钥

- 独占范围：`ApplicationKeyPanel.vue`、`ApplicationKeySecretDialog.vue`、`tests/antApplicationKeys.test.ts`。
- 依赖：UI-R305、UI-R312。
- 工作内容：用 Ant Table/Modal/Form 重写创建、轮换、启停、撤销、有效期、IP/CIDR、模型子集和密钥级限制。
- 验收：完整 `secret` 只出现一次；掩码列表不泄露；轮换宽限和撤销影响清晰；复制失败有反馈。

#### UI-R314 应用模型、额度、成员与应用审计

- 独占范围：`ApplicationQuotaSummary.vue`、`src/pages/applications/detail/ApplicationModelsTab.vue`、`ApplicationQuotaTab.vue`、`ApplicationMembersTab.vue`、`ApplicationAuditTab.vue`、`tests/antApplicationGovernance.test.ts`；不修改 `ApplicationDetailPage.vue`。
- 依赖：UI-R305、UI-R306、UI-R312、RV-P20 合并结果。
- 工作内容：将详情页大块逻辑拆成模型授权、额度速率、调整流水、成员和审计子组件；使用 Ant Transfer/Table/Progress/Descriptions。
- 验收：失效历史授权可收口；降低额度展示即时影响；已用/预占/剩余一致；调整要求原因和幂等保护；应用范围权限不退化。

#### UI-R315 应用开发接入

- 独占范围：`ApplicationIntegrationPage.vue`、新增 `src/ui/developer/CodeSamplePanel.vue`、`ChatTestPanel.vue`、`tests/antApplicationIntegration.test.ts`。
- 依赖：UI-R303、UI-R305、UI-R306。
- 工作内容：重写接入清单、Base URL、可用模型、限制、代码示例、在线测试结果和 request_id 跳转。
- 验收：示例只含占位密钥；在线测试使用已授权模型和真实准入链路；成功后展示 Usage/耗时/request_id，失败显示可操作建议。

#### UI-R320 渠道列表与表单

- 独占范围：`src/pages/providers/ProviderListPage.vue`、`ProviderFormPage.vue`、`tests/antProviderPages.test.ts`。
- 依赖：UI-R303、UI-R304、UI-R305。
- 工作内容：按渠道 V2 字段重写列表、筛选、创建编辑、代理/超时/非敏感头配置和状态操作。
- 验收：使用 `provider_type/status/health/priority/weight/version`；Base URL 主机清晰；危险 URL 以服务端结论为准；409 保留输入。

#### UI-R321 渠道详情、Key 与检测

- 独占范围：`ProviderDetailPage.vue`、`src/components/credentials/**`、`CheckDialog.vue`、`CheckCommandDialog.vue`、`tests/antProviderDetail.test.ts`。
- 依赖：UI-R303、UI-R305、UI-R320。
- 工作内容：重写概览、凭证、上游模型、健康、调用页签；实现单项/批量检测进度和逐项终态。
- 验收：写操作携带 `version`；上游 Key 只显示掩码；最后可用 Key 操作有影响提示；部分失败不显示整体成功。

#### UI-R322 上游模型目录

- 独占范围：`src/pages/models/**`、`tests/antUpstreamModel.test.ts`。
- 依赖：UI-R303、UI-R304、UI-R305、UI-R321。
- 工作内容：重写列表、详情、表单、同步预览、导入和批量检测；渠道选择使用 V2 选项。
- 验收：显示真实 model_id、能力、价格、锁定字段、最近检测和候选数；同步预览区分新增/变化/下线/冲突；不覆盖人工锁定字段。

#### UI-R323 虚拟模型与路由编辑器

- 独占范围：`src/pages/aliases/**`、`tests/antVirtualRoute.test.ts`。
- 依赖：UI-R303、UI-R304、UI-R305、UI-R322。
- 工作内容：重写虚拟模型列表/详情/表单、能力交集、应用影响和路由候选编辑；复杂编辑使用 Table + Drawer，不依赖拖拽作为唯一排序方式。
- 验收：24h 字段正确展示；路由明确优先级与同级权重；重复、无 Key、能力不一致和价格缺失即时提示；保存只形成草稿。

#### UI-R324 配置草稿、发布与回滚

- 独占范围：`src/pages/config/**`、`tests/antConfigRelease.test.ts`。
- 依赖：UI-R303、UI-R304、UI-R305。
- 工作内容：用 Steps/Timeline/Table 重写草稿分组、差异、校验、影响、发布进度、实例结果、历史和回滚。
- 验收：异步状态与最终结果分开；警告确认有明确对象；失败实例可定位；运行中不提示发布成功；回滚展示影响并要求高权限。

#### UI-R325 可靠性、限流与熔断

- 独占范围：`src/pages/limits/**`、`reliabilities/**`、`circuits/**`、`tests/antReliability.test.ts`。
- 依赖：UI-R303、UI-R304、UI-R305。
- 工作内容：重写策略列表/表单、默认策略、用量抽屉、恢复决策、熔断详情与人工动作。
- 验收：继承关系、单位和生效范围清晰；运行状态与配置状态分离；人工摘除/恢复展示版本与影响；危险默认值不得静默提交。

#### UI-R330 调用记录与详情

- 独占范围：`src/pages/traces/**`、`tests/antTracePages.test.ts`。
- 依赖：UI-R303、UI-R304、UI-R306。
- 工作内容：重写筛选表格和 Trace 详情时间线；高级筛选进入 Drawer；Attempt、恢复、流提交、结算按时间顺序展示。
- 验收：运行中刷新不打乱时间线；默认不显示消息正文；密钥只显示掩码/别名；应用负责人只能看到所属应用；导出失败准确反馈。

#### UI-R331 用量、成本与额度流水

- 独占范围：`src/pages/usage/**`、`tests/antUsagePages.test.ts`。
- 依赖：UI-R304、UI-R306。
- 工作内容：重写摘要、趋势、分组、预算使用率、单位请求成本和调整流水；筛选在所有视图使用同一口径。
- 验收：金额按币种分组，不混算；图表和表格数据一致；64 位 Token 不丢精度；空数据与真实零值区分。

#### UI-R332 运行总览

- 独占范围：`src/pages/overview/**`、`tests/antOverviewPage.test.ts`。
- 依赖：UI-R306、UI-R331。
- 工作内容：重写风险提醒、核心指标、趋势、应用排行、渠道健康和快捷入口；按角色数据范围展示。
- 验收：指标卡不超过四列；异常可跳到带筛选的目标页；刷新保留数据；无权限模块不留空洞占位。

#### UI-R333 审计、访问凭证与系统设置

- 独占范围：`src/pages/audit/**`、`src/pages/access/**`、`src/pages/runtimeConfig/**`、`tests/antSystemAdmin.test.ts`。
- 依赖：UI-R303、UI-R304、UI-R305。
- 工作内容：重写审计列表/详情、旧访问凭证只读提示和系统设置；安全或成本设置展示影响。
- 验收：变更前后摘要可读且脱敏；导出遵守当前筛选与权限；首期不出现充值、支付、订阅等 C 端配置。

#### UI-R334 全局开发接入与在线测试

- 独占范围：`src/pages/developer/DeveloperAccessPage.vue`、`tests/antDeveloperAccess.test.ts`；复用 UI-R315 交付的 `src/ui/developer/**`。
- 依赖：UI-R303、UI-R305、UI-R306、UI-R315。
- 工作内容：重写全局接入说明、应用选择、代码示例、SSE 流式展示、取消和结果详情。
- 验收：示例不写入真实密钥；流式内容单路追加；取消后状态明确；错误事件后不显示成功终态；request_id 可跳转 Trace。

### Wave 3：迁移收口与门禁

#### UI-R340 响应式、可访问性与视觉回归

- 独占范围：Playwright 配置、视觉基线、可访问性测试；原则上不直接修改业务页，缺陷回派原负责人。
- 依赖：Wave 2 全部目标页面。
- 工作内容：在 1024/1280/1440/1920 检查布局、表格滚动、抽屉、弹窗、焦点、键盘、对比度和文本截断；建立主要页面截图基线。
- 验收：1280 与 1920 无页面级横向溢出；键盘可完成导航、筛选、提交和关闭；高风险操作焦点不丢失；截图差异经设计评审确认。

#### UI-R341 契约与跨端回归

- 独占范围：`src/api/**` 的必要契约修复、契约保护测试、真实链路 E2E；任何 API 修改必须先登记。
- 依赖：Wave 2 全部业务包。
- 工作内容：逐项复验第 3 节保护清单；真实启动后端、隔离数据库、Redis 与 Vite，覆盖应用、渠道、模型、发布、调用和用量链路。
- 验收：已联调通过的链路无回归；写后重读一致；失败场景无部分写入；未具备真实 Provider/数据库时明确记录，不使用 Mock 冒充。

#### UI-R342 旧组件与样式清理

- 独占范围：旧 `src/components/**`、`src/styles/base.css`、无引用 Mock/测试；删除前先用 `rg` 证明无引用。
- 依赖：UI-R340、UI-R341。
- 工作内容：删除被 Ant Design 体系完全替代的旧组件和 `.lai-*` 样式，保留仍承载业务安全规则的封装；更新组件清单。
- 验收：无死代码和双套组件；无业务页依赖旧按钮、输入、表格、弹窗 CSS；全量测试、typecheck、lint、build 通过。

#### UI-R343 最终集成与发布评审

- 独占范围：计划状态、交付报告、最终冲突处理；只处理集成缺陷，不新增功能。
- 依赖：UI-R340～UI-R342。
- 工作内容：合入最新 `dev`、解决任务包冲突、运行完整门禁、汇总视觉评审与未验证项、确认回滚点。
- 验收：前端 lint/typecheck/test/build 全通过；关键页面视觉评审通过；真实链路证据完整；未解决 P0/P1 时结论必须为不通过。

## 6. 任务领取矩阵

| 任务 | 建议分支 | 初始状态 | 独占范围摘要 |
| --- | --- | --- | --- |
| UI-R300 | `docs/ant-design-admin-redesign-plan` | 待评审 | 设计、契约与任务拆分文档 |
| UI-R301 | `feature/frontend-ant-foundation` | 待领取 | 依赖、入口、主题与设计令牌 |
| UI-R302 | `feature/frontend-ant-shell` | 待领取 | 应用壳层、导航与布局 |
| UI-R303 | `feature/frontend-ant-page-state` | 待领取 | 页面框架、异步状态、冲突反馈 |
| UI-R304 | `feature/frontend-ant-data` | 待领取 | 筛选、表格、分页与多选 |
| UI-R305 | `feature/frontend-ant-form` | 待领取 | 表单、弹窗、敏感与高风险操作 |
| UI-R306 | `feature/frontend-ant-metrics` | 待领取 | 指标卡与图表基座 |
| UI-R307 | `feature/frontend-ant-access-state` | 待领取 | 权限、登录和系统状态页 |
| UI-R310 | `feature/frontend-ant-app-list` | 待领取 | 应用列表 |
| UI-R311 | `feature/frontend-ant-app-form` | 待领取 | 应用创建与编辑 |
| UI-R312 | `feature/frontend-ant-app-workbench` | 待领取 | 应用详情工作台与概览 |
| UI-R313 | `feature/frontend-ant-app-keys` | 待领取 | 应用密钥 |
| UI-R314 | `feature/frontend-ant-app-governance` | 待领取 | 应用模型、额度、成员、审计 |
| UI-R315 | `feature/frontend-ant-app-integration` | 待领取 | 应用开发接入 |
| UI-R320 | `feature/frontend-ant-channel` | 待领取 | 渠道列表与表单 |
| UI-R321 | `feature/frontend-ant-channel-detail` | 待领取 | 渠道详情、Key 与检测 |
| UI-R322 | `feature/frontend-ant-upstream-model` | 待领取 | 上游模型目录 |
| UI-R323 | `feature/frontend-ant-virtual-route` | 待领取 | 虚拟模型与路由编辑器 |
| UI-R324 | `feature/frontend-ant-release` | 待领取 | 草稿、发布与回滚 |
| UI-R325 | `feature/frontend-ant-reliability` | 待领取 | 可靠性、限流与熔断 |
| UI-R330 | `feature/frontend-ant-trace` | 待领取 | 调用记录与详情 |
| UI-R331 | `feature/frontend-ant-usage` | 待领取 | 用量、成本与额度流水 |
| UI-R332 | `feature/frontend-ant-overview` | 待领取 | 运行总览 |
| UI-R333 | `feature/frontend-ant-admin` | 待领取 | 审计、访问凭证和系统设置 |
| UI-R334 | `feature/frontend-ant-developer` | 待领取 | 全局开发接入与在线测试 |
| UI-R340 | `test/frontend-ant-visual` | 待领取 | 响应式、可访问性、视觉回归 |
| UI-R341 | `test/frontend-ant-contract` | 待领取 | 契约与跨端回归 |
| UI-R342 | `refactor/frontend-ant-cleanup` | 待领取 | 旧组件和样式清理 |
| UI-R343 | `release/frontend-ant-integration` | 待领取 | 最终集成和发布评审 |

领取人必须把“建议分支”追加唯一负责人后缀，例如 `feature/frontend-ant-app-list-<负责人>`。任务进入执行后，真实负责人和分支以 `TASK_STATUS.md` 为准。

## 7. 推荐并行分组

| 批次 | 可并行任务 | 开始条件 |
| --- | --- | --- |
| A | UI-R301 | UI-R300 评审通过 |
| B | UI-R302、UI-R303、UI-R304、UI-R305、UI-R306 | UI-R301 合入 dev |
| C1 | UI-R307、UI-R310、UI-R311、UI-R315、UI-R320、UI-R324、UI-R325、UI-R330、UI-R331 | 对应 Wave 1 依赖合入 dev |
| C2 | UI-R312、UI-R321、UI-R322、UI-R332、UI-R333、UI-R334 | 前置业务壳层、列表包或共享接入组件合入 dev |
| C3 | UI-R313、UI-R314、UI-R323 | 应用详情、渠道详情、上游模型前置包合入 dev |
| D | UI-R340、UI-R341 | 全部目标业务页合入集成分支 |
| E | UI-R342、UI-R343 | 视觉与契约回归通过 |

建议同一时间最多安排 4～6 个业务任务包，避免 `dev` 在共享基础层尚未稳定时积累过多长分支。应用详情、渠道详情和虚拟模型路由均有明确前置包，不应提前并行修改同一页面。

## 8. 每个任务包的统一完成定义

- 从最新 `origin/dev` 开始，并确认任务仍归当前负责人。
- 不改变保护契约；确需改变时有单独问题编号、前后端影响和评审结论。
- 使用 Ant Design 组件重写目标页面，不保留旧布局作为主体。
- 覆盖 loading、refresh、empty、filter-empty、error、403、disabled、partial-success 和 409 中适用状态。
- 定向测试覆盖成功、参数错误、无权限、资源不存在、状态冲突和依赖失败中适用场景。
- 运行 `npm run lint`、`npm run typecheck`、`npm test -- --run`、`npm run build`；结果写入交付记录。
- 在 1280 与 1920 宽度检查目标页面；含表格或 Drawer 的页面同时检查 1024。
- 不提交密钥、联调数据、截图临时文件、构建产物或无关改动。
- 更新 `COMMUNICATION.md` 与 `TASK_STATUS.md`；只有代码、测试和真实回归都满足时才能标记完成。

## 9. 总体验收场景

1. 管理员进入后台，导航、权限和页面上下文正确，加载过程不出现无样式闪烁或整页阻塞。
2. 创建应用，选择发布可用模型，配置额度和速率，签发一次性密钥，关闭后无法恢复原文。
3. 使用应用密钥完成首个调用，在应用详情、调用记录、用量和成本中看到一致结果。
4. 创建渠道并完成详情、编辑、启停和凭证管理；版本冲突保留输入，批量检测展示逐项状态。
5. 同步或创建上游模型，配置虚拟模型和路由草稿，完成校验、影响确认和发布。
6. 对 Token 耗尽、金额超限、RPM/TPM 超限、模型越权、应用停用和密钥撤销展示准确拒绝与诊断入口。
7. 四类管理角色看到正确的菜单、按钮和数据范围；前端裁剪与后端拒绝结果一致。
8. 1280 与 1920 的主要页面视觉层级统一、无横向溢出；1024 场景可用且导航可折叠。
