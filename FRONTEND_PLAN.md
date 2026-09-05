# 前端执行计划

## 1. 技术栈与实现边界

使用 Vue 3、TypeScript、Vue Router、Pinia，单文件组件与 Composition API；以 Vite 构建一份 Standalone/Embedded 共用的静态资源。Vue 的组件化和官方路由/状态库见 [Vue 官方指南](https://vuejs.org/guide/introduction.html)。选型为规划决定，具体依赖版本在 FE-001 锁定并与目标浏览器验证。不复制通用后台模板，不建立通用低代码表单引擎。

HTTP 使用浏览器 fetch 和 AbortController；POST SSE 使用 fetch 流读取，不能使用只支持 GET 的 EventSource。表格、表单和抽屉使用项目内小型组件，图表优先按需求采用 SVG 实现基础折线与时间桶交互；复杂图表库需有实际收益再登记依赖。测试使用 Vitest/Vue Test Utils（执行阶段核对版本），关键浏览器流程可用 Playwright；只覆盖本计划列出的行为，不逐个测试纯展示文案。

布局为侧栏、顶部运行模式/快照/草稿入口/用户、主内容区。用间距、字号、表格和状态文本建立层级。业务必要的待发布/运行状态保留，其余不添加 Tag、Badge 或填充描述。

## 2. 路由、页面与组件

以下路径相对应用挂载根。Standalone 根为空；Embedded 默认 /light-ai，可注入自定义根。页面前缀 /ui 与 API /admin 分离。

| 页面路由 | 页面模块/主要组件 | 使用者 | 后端模块/主要表 |
|---|---|---|---|
| /ui/overview | OverviewPage、SummaryGrid、TrendChart、ExceptionList | 四角色，开发仅应用范围 | overview；trace、usage_aggregate、circuit_state |
| /ui/providers；/new；/:id；/:id/edit | ProviderList、ProviderForm、ProviderDetail、ImpactDialog、CheckDialog | 管理编辑；运维检测；其他摘要 | providers；provider、provider_check_record |
| /ui/credential-pools；/new；/:id；/:id/edit | PoolList、PoolForm、CredentialTable、SecretCreateDialog、RotateDialog | 管理编辑；运维脱敏查看；其余池摘要 | credentials；credential_pool、credential、credential_secret |
| /ui/provider-models；/new；/:id；/:id/edit；/import | ModelList、ModelForm、ImportWizard、BatchCheckPanel | 管理编辑/导入；运维检测；其余查看 | models；provider_model、batch_check_job |
| /ui/model-aliases；/new；/:id；/:id/edit | AliasList、AliasForm、CandidateTable、CandidateForm | 四角色；开发仅已发布授权Alias | aliases；model_alias、route_candidate |
| /ui/limit-policies；/new；/:id；/:id/edit | LimitList、LimitForm、CapacityPanel、QueueTable | 四角色策略摘要；管理/运维运行细节 | capacity；limit_policy、queue_entry |
| /ui/reliability-policies；/new；/:id；/:id/edit | ReliabilityForm、DefaultPolicyPanel、RecoveryList | 管理写，其他摘要，管理/运维恢复明细 | reliability；reliability_policy、recovery_decision |
| /ui/circuits；/:id | CircuitList、CircuitDetail、CircuitActionDialog | 管理/运维操作；其他按范围查看 | circuit；circuit_state、circuit_event |
| /ui/traces；/:traceId | TraceList、TraceTimeline、AttemptDrawer、UsageBreakdown | 四角色按范围；诊断/导出仅管理/运维 | observation；trace及关联明细 |
| /ui/usage | UsageSummary、TrendChart、UsageGroupTable | 四角色按范围；管理/运维导出 | usage；usage_aggregate |
| /ui/config/drafts | DraftGroups、FieldDiff、RevertDialog | 四角色查看；管理撤销 | config；config_draft_state、draft_change |
| /ui/config/publish；/records/:id | ValidationIssues、WarningConfirm、InstanceProgress、PublishHistory | 管理发布；其余允许的历史摘要 | publish；validation、snapshot、publish_record |
| /ui/runtime-config | RuntimeConfigForm、RetentionImpactDialog | 管理写；其他查看 | config；runtime_config |
| /ui/access-credentials；/:id | AccessList、AccessForm、TokenOnceDialog | 仅Standalone；管理写/运维脱敏查看 | access；access_credential、access_credential_alias |
| /ui/audit-logs；/:id | AuditList、AuditDetail、FieldDiff | 管理/运维 | audit；audit_log |
| /ui/developer-access | ModeTabs、CodeSample、ChatTestPanel | 按Alias范围；只读不能测试 | developer；trace，已发布快照 |
| /ui/forbidden；/ui/not-found | AccessDenied、NotFound | 所有角色 | 不读取受保护对象 |

组件输出事件携带对象 ID 和明确命令，不直接修改 Pinia 中服务端对象。页面负责 API 编排，模块 api 文件负责 DTO 和 URL，共用请求层只做响应解包、错误转换、取消和请求关联 ID。

## 3. 状态、表单与接口规则

Pinia 只保存 bootstrap 身份/权限/运行模式/API根/显示时区/活动快照/草稿修订；页面查询数据按模块保存在 composable。筛选/排序/页码同步 URL，使用浏览器后退可还原。页面离开取消查询与轮询；筛选变化递增请求序号，只接受最新响应。后台刷新不覆盖编辑中表单；保存返回最新 version 后重新加载详情。

首次 loading 用骨架；后续刷新保留已有内容并展示更新时间。empty 区分未配置、无匹配和无权限（后者跳403），不能把服务异常展示为0。error 显示安全 code/message 和重试入口。保存按钮禁用防连点；超时先核对服务器对象，不自动重发。未知枚举按服务端值安全显示并禁止未识别状态下的写操作。

所有表单的具体字段、默认值、范围和只读规则见附录4.2—4.6。前端与后端共同使用明确字段字典；JSON 中 null 表示无上限，0 不代替空；输入金额保持字符串。禁用参数能力时不发送其范围/默认字段。百分比只在显示/提交边界转换。版本冲突保留用户输入，可对比重新加载值，不自动覆盖新版本。导入未知能力留空并保持 disabled。

权限来自 GET /admin/bootstrap（规划补充接口 C-011）；认证失败由部署认证入口处理，前端不实现密码登录。导航守卫和按钮根据权限/模式控制，服务端仍最终鉴权。权限缺失时不发送敏感接口，不用 CSS 隐藏已拿到的 Credential 或诊断数据。切换身份清空所有缓存。开发 application_scope 不能从筛选移除。

密钥/Token仅组件内存保存，提交/关闭/离开清除；不进入 Pinia、URL、localStorage、sessionStorage、日志或遥测。Token一次显示弹窗要求“已安全保存”；API no-store。诊断样本按需请求显式 include_diagnostics=true（补充契约），仅获权后渲染，禁止插入 HTML。测试正文只在当前页内存；例子里的 Token永远是占位符。

## 4. 任务执行规则

每包6项。各任务“实现说明”同时给出输入和输出；验收必须包含任务列出的结果与异常，不能仅验证页面可打开。依赖 DB 表只用于理解契约，前端不直连数据库。FE-P01 完成后可用冻结 DTO 夹具开发后续模块；勾选完成需要真实接口或协议测试证据。每包遵循总文档的测试、自检、勾选、Git提交和审查规则。

## FE-P01 基础（6项）

> 领取锁定：前端执行模型（beidao）2026-09-05 领取 FE-001—FE-006 全部 6 项，分支 feature/frontend-foundation，执行期间请勿重复领取；完成记录与测试证据见 COMMUNICATION.md。

- [x] 任务编号：FE-001
  模块：基础；目标：工程入口与路径。
  实现说明：运行根、模式输入；配置UI和API相对基路径，产出可复用静态包及依赖锁。
  依赖接口：GET /admin/bootstrap。
  前后端/数据库依赖：BE-001、BE-002；无表。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：空根和/light-ai两种部署均能直接刷新深层页面。
  测试要求：构建、类型检查、深链路资源加载；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-002
  模块：基础；目标：身份和权限缓存。
  实现说明：读取用户、permissions、application_scope；建立路由/动作守卫及403页。
  依赖接口：GET /admin/bootstrap。
  前后端/数据库依赖：BE-002；无用户表。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：四角色导航正确，失效身份清空缓存，未授权不取敏感接口。
  测试要求：四角色与会话失效组件测试；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-003
  模块：基础；目标：请求与错误处理。
  实现说明：封装data解包、字段errors、409冲突、AbortController和request_id展示。
  依赖接口：管理公共DTO。
  前后端/数据库依赖：BE-001；无表。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：400落字段，409保留编辑值，503可重试，取消无错误弹窗。
  测试要求：模拟400/409/503与取消；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-004
  模块：基础；目标：列表状态和URL筛选。
  实现说明：输入分页排序筛选，输出可还原查询状态及loading/empty/error。
  依赖接口：PageResult。
  前后端/数据库依赖：BE-001；各列表表。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：后退恢复筛选，快速切换无旧请求覆盖，错误不变成空列表。
  测试要求：请求竞态、刷新及空集测试；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-005
  模块：基础；目标：表单和危险操作组件。
  实现说明：输入字段错误/version/影响对象；输出明确保存或取消命令。
  依赖接口：ManagementOperationResult、ImpactAnalysis。
  前后端/数据库依赖：BE-001；audit_log。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：离开脏表单确认，写入防连点，确认取消不调用API。
  测试要求：脏表单、重复提交、版本冲突；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-006
  模块：基础；目标：敏感信息和嵌入样式。
  实现说明：建立Secret输入和内存清理，隔离宿主样式，按角色过滤渲染。
  依赖接口：bootstrap权限与模式。
  前后端/数据库依赖：BE-002；credential_secret。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：真实Token不进URL/存储/日志，宿主样式不破坏主导航。
  测试要求：敏感值扫描与嵌入页面截图检查；通过后勾选并在本包提交中附测试证据。


## FE-P02 Provider与凭证池（6项）

> 领取锁定：前端执行模型 2026-09-05 领取 FE-007—FE-012 全部 6 项，分支 feature/frontend-provider（基于 feature/frontend-foundation，FE-P01 审查合入后改基 dev），执行期间请勿重复领取；完成记录与测试证据见 COMMUNICATION.md H-003。

- [x] 任务编号：FE-007
  模块：Provider与凭证池；目标：Provider分页查询。
  实现说明：输入keyword/type/enabled/connection_status；输出名称、配置和检测状态及分页。
  依赖接口：GET /admin/providers。
  前后端/数据库依赖：BE-007；provider、object_runtime_state。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：筛选持久，权限内动作可用，三个状态各自展示。
  测试要求：组合筛选、空页、请求失败；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-008
  模块：Provider与凭证池；目标：Provider新增与编辑。
  实现说明：按4.2.2字段提交；type创建后只读，禁认证头并校验超时组合。
  依赖接口：POST /admin/providers；PUT /admin/providers/{id}。
  前后端/数据库依赖：BE-008；provider。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：合法保存取得version和草稿数，认证头/超时错误不提交。
  测试要求：字段边界与409；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-009
  模块：Provider与凭证池；目标：Provider详情和检测。
  实现说明：读取详情，输入检测模型/凭证/超时，展示CheckRecord及失败码。
  依赖接口：GET /admin/providers/{id}；POST /admin/providers/{id}/check。
  前后端/数据库依赖：BE-009；provider_check_record。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：检测结果不误报已发布，耗时与错误可定位。
  测试要求：检测成功、401映射、超时；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-010
  模块：Provider与凭证池；目标：Provider启停删除影响。
  实现说明：先读取引用影响，再携带version和confirmed_impact_version确认。
  依赖接口：/admin/providers/{id}/impact、enable、disable；DELETE详情。
  前后端/数据库依赖：BE-010；provider、draft_change。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：引用变化重新确认，OBJECT_IN_USE列明阻塞，取消无变更。
  测试要求：影响过期、引用删除被拒；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-011
  模块：Provider与凭证池；目标：Pool列表与表单。
  实现说明：输入Provider/name/strategy/enabled，输出池详情；Provider不可改。
  依赖接口：GET/POST /admin/credential-pools；GET/PUT /admin/credential-pools/{id}。
  前后端/数据库依赖：BE-011；credential_pool。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：三种策略可选，非管理员只读，跨Provider编辑不存在。
  测试要求：名称重复、策略切换、权限；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-012
  模块：Provider与凭证池；目标：Pool详情与启停删除。
  实现说明：显示池状态、计数和Credential区域；先确认影响后停用/删除。
  依赖接口：/admin/credential-pools/{id}/impact、enable、disable；DELETE详情。
  前后端/数据库依赖：BE-012；credential_pool、credential。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：开发/只读不加载Credential列表；池含Credential阻止删除。
  测试要求：四角色字段、引用约束；通过后勾选并在本包提交中附测试证据。


## FE-P03 模型接入（6项）

> 领取锁定：前端执行模型（beidao）2026-09-05 领取 FE-013—FE-018 全部 6 项，分支 feature/frontend-models（worktree D:\AIBuilder\AIGetway-models，基于 b43ea0f），执行期间请勿重复领取；FE-P02（feature/frontend-provider）由另一协作者并行执行，池详情页挂载点由其提供，完成记录见 COMMUNICATION.md H-004。

- [x] 任务编号：FE-013
  模块：模型接入；目标：Credential查询和新增编辑。
  实现说明：在池详情提交名称、密钥来源、限额；两次密钥一致，引用编辑不回传掩码。
  依赖接口：GET/POST /admin/credential-pools/{poolId}/credentials；GET/PUT /admin/credentials/{id}。
  前后端/数据库依赖：BE-013；credential、credential_secret。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：来源不可切换，空限额保留null，关闭后清理秘密。
  测试要求：互斥来源、超限、掩码保留；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-014
  模块：模型接入；目标：Credential轮换检测启停删除。
  实现说明：独立轮换弹窗与检测命令，显式展示轮换即时生效结果。
  依赖接口：/admin/credentials/{id}/rotate、check、enable、disable；DELETE详情。
  前后端/数据库依赖：BE-013；credential、provider_check_record。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：新密钥只写入，旧值不回显；CAPACITY_IN_USE时保留对象。
  测试要求：轮换失败、运行占用、取消；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-015
  模块：模型接入；目标：Model列表详情与能力表单。
  实现说明：按4.2.6录入能力/范围/默认值/价格，读取引用和检测状态。
  依赖接口：GET/POST /admin/provider-models；GET/PUT /admin/provider-models/{id}。
  前后端/数据库依赖：BE-014；provider_model。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：正上下文、价格精度、支持开关联动准确，缺能力禁启用。
  测试要求：上下文边界、价格精度、409；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-016
  模块：模型接入；目标：模型导入与批量检测。
  实现说明：输入Provider/来源/模型选择；显示created/skipped/failed和逐项检测进度。
  依赖接口：/admin/providers/{id}/available-models；/admin/provider-models/import、batch-check；/admin/batch-check-jobs/{id}。
  前后端/数据库依赖：BE-015；provider_model、batch_check_job、batch_check_item。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：未知能力待补充且disabled；取消停止未开始项；离页停轮询。
  测试要求：不支持目录、部分失败、任务取消；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-017
  模块：模型接入；目标：Alias列表新增编辑和影响。
  实现说明：输入alias/display_name/description；展示发布能力摘要并执行引用确认。
  依赖接口：/admin/model-aliases；/admin/model-aliases/{id}及impact/enable/disable。
  前后端/数据库依赖：BE-016；model_alias。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：alias创建后只读，开发仅授权已发布Alias，删除引用保护。
  测试要求：非法Alias、越权、影响过期；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-018
  模块：模型接入；目标：候选创建编辑重排探测。
  实现说明：在Alias详情选择同Provider模型/池，输入priority/weight；重排带每项version。
  依赖接口：/admin/model-aliases/{id}/candidates、candidates/reorder；/admin/route-candidates/{id}、check。
  前后端/数据库依赖：BE-017、BE-018；route_candidate。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：跨Provider不可选，冲突整批不变，运行状态与草稿启停独立。
  测试要求：重复组合、重排409、探测限流；通过后勾选并在本包提交中附测试证据。


## FE-P04 治理（6项）

- [ ] 任务编号：FE-019
  模块：治理；目标：限流列表与可编辑策略。
  实现说明：按作用类型选择对象，输入RPM/TPM/并发和QUEUE参数。
  依赖接口：/admin/limit-policies及详情/enable/disable。
  前后端/数据库依赖：BE-021；limit_policy。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：null无上限，至少一项限额，REJECT不发队列字段。
  测试要求：全空、重复策略、队列边界；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-020
  模块：治理；目标：实时容量和FIFO队列。
  实现说明：显示当前窗口已用/预占/结算/并发与等待记录，仅只读。
  依赖接口：GET /admin/limit-policies/{id}/usage、queue。
  前后端/数据库依赖：BE-021；capacity_reservation、queue_entry。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：无取消他人按钮，状态存储503保留旧值及更新时间。
  测试要求：窗口变化、503、敏感字段权限；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-021
  模块：治理；目标：可靠性策略与默认值。
  实现说明：分区录入超时、三类预算、退避、熔断；转换失败率比例。
  依赖接口：/admin/reliability-policies；/default；详情/enable/disable。
  前后端/数据库依赖：BE-022；reliability_policy。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：首token超时小于总超时，fallback关时预算0，默认只读。
  测试要求：组合边界、百分比往返；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-022
  模块：治理；目标：恢复决策列表与Trace跳转。
  实现说明：按Alias和动作展示来源Attempt、等待和预算，带trace_id跳转。
  依赖接口：GET /admin/reliability-policies/{id}/recovery-decisions。
  前后端/数据库依赖：BE-022；recovery_decision。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：无正文，来源与目标路径一致，无数据为空状态。
  测试要求：动作筛选、权限、链接；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-023
  模块：治理；目标：熔断列表详情与事件。
  实现说明：输入状态/Provider过滤，展示窗口、探测数和近50事件。
  依赖接口：GET /admin/circuits；/{id}；/{id}/events。
  前后端/数据库依赖：BE-023；circuit_state、circuit_event。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：OPEN排序靠前，开发仅授权Alias，敏感凭证不下发。
  测试要求：状态排序、事件空集、越权；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-024
  模块：治理；目标：熔断人工操作与冲突。
  实现说明：输入原因/时限及state_version，提交open/recover/probe。
  依赖接口：POST /admin/circuits/{id}/open、recover、probe。
  前后端/数据库依赖：BE-023；circuit_command、audit_log。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：状态冲突刷新后重新确认；命令待收敛不显示已完成。
  测试要求：CAS冲突、探测失败、应用延迟；通过后勾选并在本包提交中附测试证据。


## FE-P05 Trace（6项）

> 领取锁定：前端执行模型 2026-09-05 领取 FE-025—FE-030 全部 6 项，分支 feature/frontend-trace（基于 dev），执行期间请勿重复领取；完成记录与测试证据见 COMMUNICATION.md H-006。

- [x] 任务编号：FE-025
  模块：Trace；目标：Trace组合和精确查询。
  实现说明：按4.4.1筛选，trace_id精确模式停用其他业务条件，保留范围控制。
  依赖接口：GET /admin/traces。
  前后端/数据库依赖：BE-031；trace、attempt。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：精确查询最多1条，无权ID空列表，普通时间≤31天。
  测试要求：ID越权、31天边界、筛选恢复；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-026
  模块：Trace；目标：Trace列表列与导出。
  实现说明：展示最终状态、路径、用量/成本；按当前条件导出CSV。
  依赖接口：GET /admin/traces/export。
  前后端/数据库依赖：BE-031、BE-036；trace。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：未获权无导出，超10万行错误可读，下载取消释放请求。
  测试要求：导出422、文件名、断开；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-027
  模块：Trace；目标：Trace摘要与受控诊断。
  实现说明：按需请求详情和include_diagnostics，展示数量摘要与可用样本。
  依赖接口：GET /admin/traces/{traceId}。
  前后端/数据库依赖：BE-032；trace、trace_content_sample。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：诊断只有有权且AVAILABLE显示，403无子明细，过期不可取。
  测试要求：四角色、EXPIRED、404/403；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-028
  模块：Trace；目标：统一时间线与Attempt抽屉。
  实现说明：按服务端timeline顺序渲染，展开耗时、错误、预占和价格快照。
  依赖接口：GET /admin/traces/{traceId}。
  前后端/数据库依赖：BE-032；attempt、route_decision、capacity_reservation。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：节点对应源ID，不前端重排，同时间顺序稳定。
  测试要求：相同时间、无Attempt失败、长列表；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-029
  模块：Trace；目标：恢复关联与终态表现。
  实现说明：点击Recovery高亮来源和目标；运行Trace轮询至终态。
  依赖接口：GET /admin/traces/{traceId}。
  前后端/数据库依赖：BE-032；recovery_decision、circuit_event。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：FAIL无目标，终态停轮询，已结束不显示运行计时。
  测试要求：FAIL、取消、流中断；通过后勾选并在本包提交中附测试证据。

- [x] 任务编号：FE-030
  模块：Trace；目标：响应Usage与总消耗对账。
  实现说明：分开展示response_*和全部Attempt Token/成本、ACTUAL/ESTIMATED/MIXED。
  依赖接口：GET /admin/traces/{traceId}。
  前后端/数据库依赖：BE-033；trace、attempt。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：失败尝试仍计入总量，金额不经浮点汇总，缺用量显示估算。
  测试要求：重试+Fallback、混合Usage、零价格；通过后勾选并在本包提交中附测试证据。


## FE-P06 概览与Usage（6项）

- [ ] 任务编号：FE-031
  模块：概览与Usage；目标：概览筛选与摘要。
  实现说明：读取授权筛选和摘要，统一时间/application/Alias/Provider。
  依赖接口：GET /admin/overview/filters、summary。
  前后端/数据库依赖：BE-034；trace、usage_aggregate。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：请求数与Trace total一致；成功率分母正确，缺数据不显示0。
  测试要求：包含取消/运行/失败夹具；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-032
  模块：概览与Usage；目标：趋势与时间桶钻取。
  实现说明：按响应桶画请求/成功率/Token/分币种成本，点击带范围跳转。
  依赖接口：GET /admin/overview/trends。
  前后端/数据库依赖：BE-034；usage_aggregate。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：连续无重复桶，bucket_end不重复包含，取消旧请求。
  测试要求：跨日、空桶、两币种；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-033
  模块：概览与Usage；目标：异常定位与刷新。
  实现说明：展示OPEN/无候选/凭证异常/失败Trace，按类型进入有权详情。
  依赖接口：GET /admin/overview/exceptions。
  前后端/数据库依赖：BE-034；circuit_state、object_runtime_state、trace。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：开发不接收Credential异常，刷新失败保留上次数据。
  测试要求：角色对比、对象删除、503；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-034
  模块：概览与Usage；目标：Usage筛选和摘要趋势。
  实现说明：同参数请求summary/trends，核对fingerprint并取最早更新时间。
  依赖接口：GET /admin/usage/summary、trends。
  前后端/数据库依赖：BE-035；usage_aggregate。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：指纹不同不混呈现，actual与estimated分别显示，费用分币种。
  测试要求：响应竞态、多币种、延迟；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-035
  模块：概览与Usage；目标：Usage分组排序与钻取。
  实现说明：输入group_by/sort，按维度及currency拆行并带条件进入Trace。
  依赖接口：GET /admin/usage/groups。
  前后端/数据库依赖：BE-035；usage_aggregate。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：多币种禁总费用排序，过Trace留存禁钻取，开发范围不可清除。
  测试要求：金额排序400、历史过期、权限；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-036
  模块：概览与Usage；目标：Usage导出与保留状态。
  实现说明：按当前聚合筛选导出，展示桶/币种和安全错误，取消请求可停止。
  依赖接口：GET /admin/usage/export。
  前后端/数据库依赖：BE-036；usage_aggregate。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：下载结果与分组同口径，10万行上限，403无下载。
  测试要求：导出边界、取消、时区桶；通过后勾选并在本包提交中附测试证据。


## FE-P07 草稿发布（6项）

- [ ] 任务编号：FE-037
  模块：草稿发布；目标：草稿摘要和分组差异。
  实现说明：读取draft-state/summary/changes，按实体分组展示脱敏字段差异。
  依赖接口：GET /admin/config/draft-state、draft-changes/summary、draft-changes。
  前后端/数据库依赖：BE-037；config_draft_state、draft_change。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：摘要数量一致，同对象一行，密钥无前后值。
  测试要求：多次编辑、删除新建抵消；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-038
  模块：草稿发布；目标：单项撤销与全部撤销。
  实现说明：输入原因/确认文本/revision/version，显示revert_blockers。
  依赖接口：POST /admin/config/draft-changes/{entityType}/{entityId}/revert、revert-all。
  前后端/数据库依赖：BE-038；draft_change、audit_log。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：有依赖禁单撤销，冲突不清页面，全部撤销确认文字有效。
  测试要求：阻塞、旧revision、取消；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-039
  模块：草稿发布；目标：固定修订配置校验。
  实现说明：提交revision，展示ERROR/WARNING定位对象字段。
  依赖接口：POST /admin/config/validate。
  前后端/数据库依赖：BE-039；config_validation、config_validation_issue。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：ERROR阻止下一步，revision变化与过期使结果失效。
  测试要求：校验失败、过期、并发编辑；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-040
  模块：草稿发布；目标：警告确认与发布提交。
  实现说明：逐项确认warning IDs与publish_note，提交validation_id及revision。
  依赖接口：POST /admin/config/publish。
  前后端/数据库依赖：BE-040；publish_record、config_snapshot。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：缺警告禁提交，重复点击仅一次，网络超时读取记录核对。
  测试要求：遗漏警告、锁冲突、超时；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-041
  模块：草稿发布；目标：实例准备激活与收敛进度。
  实现说明：轮询发布详情展示READY/LOADED和失败摘要、首轮/收敛时间。
  依赖接口：GET /admin/config/publish-records/{id}。
  前后端/数据库依赖：BE-040、BE-041；publish_instance_result。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：准备失败旧快照仍活动，PARTIAL_FAILED保持可收敛展示。
  测试要求：失败/超时/恢复三实例夹具；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-042
  模块：草稿发布；目标：发布历史和快照摘要。
  实现说明：输入时间/状态/发布人查询历史，详情展示前后版本与实例能力。
  依赖接口：GET /admin/config/publish-records；/admin/config/snapshots/{snapshotNo}/summary；/admin/runtime-instances。
  前后端/数据库依赖：BE-042；publish_record、runtime_instance。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：不请求content；无权实例信息不展示；关闭进度不取消发布。
  测试要求：历史分页、权限、页面重开；通过后勾选并在本包提交中附测试证据。


## FE-P08 运行配置与访问（6项）

- [ ] 任务编号：FE-043
  模块：运行配置与访问；目标：运行参数编辑。
  实现说明：按4.5.3录入留存/刷新/请求上限/采样/代理/发布时限。
  依赖接口：GET/PUT /admin/runtime-config。
  前后端/数据库依赖：BE-043；runtime_config。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：timezone_locked只读，关闭采样率0，修改保存为草稿。
  测试要求：组合约束、锁定409、版本冲突；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-044
  模块：运行配置与访问；目标：保留期缩短影响确认。
  实现说明：提交目标天数，展示删除估算，携带impact_version保存。
  依赖接口：POST /admin/runtime-config/retention-impact。
  前后端/数据库依赖：BE-043；retention_impact。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：参数变化或10分钟过期需重新估算，取消不保存。
  测试要求：过期、修改参数、影响为0；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-045
  模块：运行配置与访问；目标：Access列表详情与表单。
  实现说明：Standalone模式读取脱敏凭证；录入application/Alias/IP/有效期。
  依赖接口：/admin/access-credentials及/{id}。
  前后端/数据库依赖：BE-044；access_credential、access_credential_alias。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：Embedded无入口；空Alias允许全部；IP/CIDR错误阻止提交。
  测试要求：模式限制、过期、IP边界；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-046
  模块：运行配置与访问；目标：Token签发轮换一次显示。
  实现说明：创建/rotate结果仅存在弹窗，保存勾选后清空secret。
  依赖接口：POST /admin/access-credentials；/{id}/rotate。
  前后端/数据库依赖：BE-044；access_credential。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：关闭后无法恢复明文，轮换失败不弹假Token，no-store。
  测试要求：一次显示、刷新、失败；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-047
  模块：运行配置与访问；目标：Access即时启停删除。
  实现说明：携带version/reason操作，刷新状态/最新Trace和审计摘要。
  依赖接口：/{id}/enable、disable；DELETE /admin/access-credentials/{id}。
  前后端/数据库依赖：BE-044；access_credential、audit_log。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：立即状态不显示待发布，EXPIRED不能直接启用。
  测试要求：过期、冲突、删除后404；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-048
  模块：运行配置与访问；目标：审计列表详情与导出。
  实现说明：按操作人/动作/对象/时间/结果筛选，查看脱敏diff和request_id。
  依赖接口：GET /admin/audit-logs；/{id}；/export。
  前后端/数据库依赖：BE-045；audit_log。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：仅管理/运维可取，失败记录可关联请求，密钥无值。
  测试要求：失败审计、敏感扫描、导出上限；通过后勾选并在本包提交中附测试证据。


## FE-P09 开发接入与交付（6项）

- [ ] 任务编号：FE-049
  模块：开发接入与交付；目标：接入模式和授权Alias。
  实现说明：读取context并按模式显示地址、SDK/Maven/Spring配置区域。
  依赖接口：GET /admin/developer-access/context。
  前后端/数据库依赖：BE-046；config_snapshot。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：开发仅授权Alias，无已发布模型显示空态，模式切换清旧输出。
  测试要求：空目录、失效Alias、三模式；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-050
  模块：开发接入与交付；目标：安全代码示例复制。
  实现说明：按语言/模式/Alias请求code-sample，保留换行和占位Token。
  依赖接口：GET /admin/developer-access/code-sample。
  前后端/数据库依赖：BE-046；无新增表。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：示例含正确Alias和base_url，无真实Secret；复制内容一致。
  测试要求：占位符扫描与剪贴内容；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-051
  模块：开发接入与交付；目标：同步在线测试。
  实现说明：输入model/system/user/temperature/top_p/max_tokens并提交chat。
  依赖接口：POST /admin/developer-access/test/chat。
  前后端/数据库依赖：BE-047；trace、attempt。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：只读禁测试，参数错误定位，成功response与trace_id一致。
  测试要求：空user、权限、错误码；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-052
  模块：开发接入与交付；目标：流式在线测试解析。
  实现说明：POST stream读取UTF-8/SSE跨块分帧，按sequence追加并显示Usage。
  依赖接口：POST /admin/developer-access/test/chat/stream。
  前后端/数据库依赖：BE-047、BE-028；trace、attempt。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：角色/文本/Usage正常，提交后error保留已收文本且不报成功。
  测试要求：中文跨字节、分帧、流中断；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-053
  模块：开发接入与交付；目标：取消测试和离页清理。
  实现说明：Abort当前请求并终止输出，取消后允许新建独立调用。
  依赖接口：/admin/developer-access/test/chat、/admin/developer-access/test/chat/stream取消连接。
  前后端/数据库依赖：BE-029；trace、capacity_reservation。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：取消后无追加；不缓存正文；Trace=CANCELLED且容量释放。
  测试要求：取消与完成竞态、离页；通过后勾选并在本包提交中附测试证据。

- [ ] 任务编号：FE-054
  模块：开发接入与交付；目标：关键路径集成验收。
  实现说明：串联配置接入→发布→测试→Trace/Usage核对及两种挂载路径。
  依赖接口：全部已实现管理接口。
  前后端/数据库依赖：BE-P10、DB-P05；核心表。
  状态处理：首次加载、无数据、接口失败、无权限；写操作增加提交中、成功、版本冲突；流式按任务专属终态处理。
  验收标准：四角色、首次接入、发布冲突、流中断和Token轮换流程通过。
  测试要求：执行端到端与生产构建并保存证据；通过后勾选并在本包提交中附测试证据。


## 需求契约附录：页面字段、操作与状态完整基线

以下内容取自PRD第4.0—4.6.1节。原编号保留供检索；采用口径以PROJECT_DOCUMENT第4节为准。附录中的操作定义是上述任务的验收补充，不能省略字段或异常。

## 4.0 通用页面规则

管理端固定包含左侧一级导航、顶部产品名称、当前运行模式、当前配置快照、待发布变更入口和当前用户。二级页面在一级导航展开后按 2.3 的功能模块顺序显示。页面刷新不丢失筛选条件，筛选条件同步到 URL；返回列表后恢复页码、排序和筛选。

列表默认按 updated_at 或业务时间倒序，支持 page、page_size 和明确列的排序。首次加载展示骨架状态，无数据展示空状态，加载失败展示可重试错误。创建和编辑使用独立页面或右侧抽屉，离开存在未保存内容的表单时需要确认。

保存只写入草稿并生成 AuditLog，不直接改变运行快照。页面对 draft_changed 对象显示统一变更状态，顶部待发布数量实时更新。删除、停用、发布、密钥轮换、人工熔断和恢复属于高风险操作，需要确认；人工熔断与恢复必须填写原因。

管理接口统一返回 data 或 error。创建成功返回对象详情，更新成功返回最新 version，删除成功返回被删除 ID。字段校验失败返回 FIELD_VALIDATION_FAILED 和字段级 errors；权限失败返回 ACCESS_DENIED；版本冲突返回 CONFIG_VERSION_CONFLICT，并提示用户刷新后重新编辑。

## 4.1 运行概览

运行概览是管理端默认首页，按照“运行摘要—趋势分析—异常定位”的阅读顺序组织。三个区域共享同一 OverviewQuery；用户修改时间、应用、Alias 或 Provider 后，页面以同一查询版本并行刷新摘要、趋势和异常数据，任一请求返回旧查询版本时丢弃结果，避免筛选切换后出现区域数据混用。

### 4.1.1 运行摘要页

#### 4.1.1.1 页面进入与信息顺序

用户登录管理端后默认进入运行概览，也可以从左侧“运行概览—运行摘要”进入。页面从上到下依次展示公共筛选区、核心指标卡、恢复动作摘要、趋势分析和异常定位。公共筛选区从左到右为时间范围、application、Model Alias、Provider、费用币种、自动刷新与手动刷新；默认时间范围为当前页面时区最近 24 小时，application、Alias、Provider 和 currency 均为空。

时间范围支持最近 1 小时、24 小时、7 天、30 天和自定义范围。自定义范围必须位于 Trace 或 Usage 可查询保留期内，起止时间相等、开始时间晚于结束时间或跨度超过 365 天时禁止查询。最近 1 小时和 24 小时默认 granularity=HOUR，7 天、30 天及更长范围默认 granularity=DAY；用户可以在趋势区域切换服务端允许的粒度。

#### 4.1.1.2 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 公共筛选 | start_at、end_at | 时间范围选择器 | OverviewQuery | 快捷范围或自定义范围转换为 UTC 请求；页面按 RuntimeConfig.timezone 展示。 |
| 公共筛选 | application | 可搜索单选 | 当前角色 application_scope | 系统管理员、运维和只读人员可选授权范围；开发人员固定为本应用时只展示不可编辑值。 |
| 公共筛选 | alias_id | 可搜索单选 | 已发布 Model Alias | 只返回当前角色可见 Alias；选择后 Provider 选项缩小为该 Alias 候选使用的 Provider。 |
| 公共筛选 | provider_id | 可搜索单选 | 已发布 Provider | 只向具有 Provider 查看权限的角色展示；不返回 Credential 维度。 |
| 公共筛选 | currency | 单选 | 查询范围内 UsageAggregate.currency | 空值表示各币种分别展示，不计算跨币种总额。 |
| 公共筛选 | auto_refresh | 开关 | 页面状态、RuntimeConfig.dashboard_refresh_seconds | 默认开启；浏览器隐藏、离开页面或权限失效时停止轮询。 |
| 公共筛选 | refresh | 按钮 | 页面操作 | 使用当前 OverviewQuery 重新加载三个区域；加载中不可重复触发。 |
| 核心指标 | request_count | 数值卡 | OverviewSummary | 显示查询范围内 Trace 数；点击进入 Trace 列表并带入相同筛选。 |
| 核心指标 | success_rate | 百分比卡 | OverviewSummary | 保留两位小数；分母为 0 时显示“—”。 |
| 核心指标 | average_total_ms | 时长卡 | OverviewSummary | 小于 1000ms 显示毫秒，其他情况转换为秒并保留两位。 |
| 核心指标 | p95_first_token_ms | 时长卡 | OverviewSummary | 查询范围没有首 Token 样本时显示“—”。 |
| 核心指标 | total_tokens | 数值卡 | OverviewSummary | 展示总 Token，并在展开信息中分别显示 actual_tokens 与 estimated_tokens。 |
| 核心指标 | costs | 金额卡组 | OverviewSummary.costs | 每个 currency 独立显示；选定 currency 时只显示一个金额卡。 |
| 状态指标 | success_count、failure_count、stream_interrupted_count、cancelled_count、active_count | 状态摘要 | OverviewSummary | 点击任一数量进入 Trace 列表并追加对应 status 条件。 |
| 恢复动作 | retry_count、credential_failover_count、fallback_count | 数值摘要 | OverviewSummary | 点击进入 Trace 列表并追加 recovery_action 条件。 |
| 运行状态 | open_circuit_count、unavailable_candidate_count | 数值摘要 | OverviewSummary | 点击滚动到异常定位区域并应用对应 item_type。 |
| 数据状态 | data_updated_at | 时间与延迟状态 | OverviewSummary | 与当前时间相差超过两个 dashboard_refresh_seconds 时显示“数据聚合延迟”。 |

#### 4.1.1.3 查询、加载与异常流程

页面进入后先读取运行配置和可用筛选项，生成 query_version，再并行请求 summary、trends 和 exceptions。三个请求分别展示骨架状态；部分接口失败时保留已成功区域并在失败区域提供重试，不能用 0 代替失败数据。用户修改任何公共筛选项后，页面把页内 drilldown 状态清空，递增 query_version，并重新请求三个接口；旧版本响应不得覆盖新结果。

自动刷新按 dashboard_refresh_seconds 执行。每轮使用相同 OverviewQuery 替换当前结果，不把新趋势点累加到旧数组。浏览器网络恢复后立即执行一次刷新；连续三次失败后暂停自动刷新并显示最近一次成功时间，用户手动刷新成功后恢复。接口返回 ACCESS_DENIED 时清空全部概览数据并返回无权限页；返回 CONFIG_DATA_UNAVAILABLE 或 OBSERVATION_DATA_UNAVAILABLE 时显示服务不可用状态并保留筛选条件。

请求量、状态数量和恢复动作钻取到 4.4.1 Trace 列表，携带 start_at、end_at、application、alias_id、provider_id、status 或 recovery_action。费用卡钻取到 4.4.3 Usage 与 Cost 页，携带相同范围、维度和 currency。所有钻取参数都重新经过目标页面权限校验，概览页面不把隐藏 Provider 或 Credential 条件写入 URL。

#### 4.1.1.4 指标口径与完成标准

request_count、状态、success_rate、耗时和恢复动作直接查询 Trace、Attempt 与 RecoveryDecision，保证新结束调用可以在下一次刷新出现；Token 与 Cost 读取 UsageAggregate，允许存在第 5.1 节规定的聚合延迟。request_count 以 Trace.started_at 落在查询范围内为准。success_rate 的分母为 SUCCEEDED、FAILED 和 STREAM_INTERRUPTED，CANCELLED、RUNNING 与 QUEUED 不进入分母。average_total_ms 使用相同三类最终状态，p95_first_token_ms 只统计存在 first_token_at 的流式 Trace。total_tokens 与 costs 汇总全部可计费 Attempt；状态计数汇总 Trace 最终状态，因此一次包含 Retry 或 Fallback 的请求只计一个 request_count，但可以产生多个恢复动作和多笔 Attempt Cost。

相同 OverviewQuery 下，request_count 必须与 Trace 列表 total 一致，Token 与 Cost 必须与 Usage 汇总一致。页面不显示完整 client_ip、Credential ID、secret_ref、Provider 原始错误或消息正文。手动与自动刷新不能重复累计数据，筛选切换后不能出现不同 query_version 的区域组合。

### 4.1.2 趋势分析

#### 4.1.2.1 区域布局与字段

趋势分析位于运行摘要下方，默认展示请求量与成功率。区域顶部从左到右为 metric、granularity 和图表显示方式；下方为主图、图例和数据更新时间。metric 支持 REQUEST_COUNT、SUCCESS_RATE、AVERAGE_TOTAL_MS、P95_FIRST_TOKEN_MS、TOTAL_TOKENS、COST、RETRY_COUNT 和 FALLBACK_COUNT。COST 在未选择 currency 时按币种绘制多条序列，不提供跨币种求和。

| 页面区域 | 字段或控件 | 数据来源 | 展示与交互规则 |
|---|---|---|---|
| 趋势控制 | metric | 页面枚举 | 默认 REQUEST_COUNT；切换指标复用当前 points，不重复请求已包含的字段。 |
| 趋势控制 | granularity | OverviewQuery.granularity | 可选项由时间跨度决定；跨度不支持所选粒度时服务端返回 FIELD_VALIDATION_FAILED。 |
| 趋势控制 | chart_type | 页面状态 | COUNT、TOKEN、COST 使用折线或柱状图；比例与耗时使用折线图。 |
| 图表横轴 | bucket_start、bucket_end | OverviewTrendPoint | 按页面时区显示，时间桶采用左闭右开。 |
| 图表纵轴 | metric 对应值 | OverviewTrendPoint | 计数和 Token 从 0 起；比例范围 0—100%；耗时以 ms 为基础值。 |
| 图例 | currency 或状态系列 | OverviewTrendPoint | 多币种 Cost 按 currency 分系列；其他指标不拆分 Provider 或 Alias。 |
| 提示框 | 时间桶、请求量、成功数、失败数、指标值 | OverviewTrendPoint | 悬停或键盘聚焦显示；无数据补零点标记为“无调用”。 |
| 数据状态 | data_updated_at | OverviewTrendResult | 展示最近聚合时间和延迟状态。 |

#### 4.1.2.2 交互与数据流

用户切换 granularity 时，页面使用当前 OverviewQuery 重新调用趋势接口；只切换 metric 或 chart_type 时使用已返回 points 在前端重绘。点击某个时间桶时，根据当前 metric 进入 Trace 或 Usage 页面：请求、成功率、耗时、重试和 Fallback 进入 Trace，Token 和 Cost 进入 Usage；目标范围使用该点 bucket_start 与 bucket_end，不使用整页时间范围。

服务端从 HOUR 或 DAY UsageAggregate 与 Trace 状态聚合读取数据，生成完整连续桶序列。没有数据的时间桶补零；成功率分母为 0、没有流式样本的首 Token P95 和不存在对应币种的 Cost 返回 null 或 0 的规则必须与字段语义一致，前端不能把 null 显示为真实 0。当前时间尚未结束的时间桶可以返回临时值，后续刷新以相同 bucket_start 替换。

#### 4.1.2.3 完成标准

相同粒度下全部 points 按 bucket_start 升序且没有重复时间桶；各桶 request_count 之和与摘要 request_count 的差异只允许来自查询末端尚未聚合的数据，并在 data_updated_at 中体现。多币种费用始终按币种分线，点击任一数据点能够带入精确时间桶和公共筛选条件。

### 4.1.3 异常定位

#### 4.1.3.1 区域布局与字段

异常定位位于趋势分析下方，按照“当前运行状态—近期失败调用”展示。顶部摘要依次为 OPEN 熔断、HALF_OPEN 熔断、不可用候选、无效 Credential 和近期失败 Trace；列表默认最多展示 20 项，排序优先级为 OPEN 熔断、HALF_OPEN 熔断、不可用候选、无效 Credential、失败 Trace，同级按 occurrence_count desc、latest_at desc。

| 页面区域 | 字段或控件 | 数据来源 | 展示与交互规则 |
|---|---|---|---|
| 异常摘要 | open_circuit_count | OverviewExceptionSummary | 点击后列表只显示 item_type=CIRCUIT 且 status=OPEN。 |
| 异常摘要 | half_open_circuit_count | OverviewExceptionSummary | 点击后列表只显示 HALF_OPEN。 |
| 异常摘要 | unavailable_candidate_count | OverviewExceptionSummary | 点击后显示 UNAVAILABLE 或 CIRCUIT_OPEN 候选。 |
| 异常摘要 | invalid_credential_count | OverviewExceptionSummary | 只向系统管理员和运维人员展示数值；其他角色不返回该字段明细。 |
| 异常摘要 | recent_failure_trace_count | OverviewExceptionSummary | 点击后显示 FAILED 与 STREAM_INTERRUPTED Trace。 |
| 异常列表 | item_type、object_name | OverviewExceptionItem | 展示对象类型和名称；Credential 只显示配置名称。 |
| 异常列表 | status | OverviewExceptionItem | 使用状态文本，不添加额外说明标签。 |
| 异常列表 | error_code、error_summary | OverviewExceptionItem | 错误摘要脱敏并截断为 500 字符；无错误时显示状态变化原因。 |
| 异常列表 | occurrence_count、latest_at | OverviewExceptionItem | 展示范围内次数和最近发生时间。 |
| 异常列表 | related Provider、Model、Alias | OverviewExceptionItem | 仅展示当前角色有权限的关联名称，点击进入对应详情。 |
| 异常列表 | 查看详情 | 页面操作 | CIRCUIT 进入 4.3.3，候选进入 4.2.8，Credential 进入 4.2.4，TRACE 进入 4.4.2。 |

#### 4.1.3.2 操作、权限与数据流

exceptions 接口先按当前 application_scope、Alias 和 Provider 条件筛选 Trace，再读取相关 CircuitState、RouteCandidate 和 Credential 健康状态。系统管理员和运维人员可以查看 Credential 配置名称与脱敏值；开发人员只接收本应用 Trace 和可见 Alias，不返回 Credential 类型的 item；只读人员可以查看 Provider、模型、候选、熔断和 Trace 异常，不返回 Credential 类型的 item，且不能从异常项执行检测、探测、人工熔断或恢复。

异常区域只提供定位入口。连接检测在 Provider 或 Credential 页面执行，候选探测和熔断操作在熔断详情执行；用户完成操作返回概览后，页面重新请求 exceptions，不在前端直接修改状态。object_id 指向已删除历史对象时，列表使用 Trace 或 CircuitEvent 保存的名称快照并只允许进入 Trace 详情。

#### 4.1.3.3 异常与完成标准

当前状态类异常不受 start_at 限制，但必须符合 application、Alias 与 Provider 范围；recent_failure_trace_count 和 occurrence_count 受完整时间范围限制。异常对象恢复后在下一刷新周期从当前状态列表移除，历史失败 Trace 仍按时间范围保留。接口不可用时页面保留摘要与趋势，异常区域显示独立重试状态。

每一项必须至少提供 object_id、status、latest_at 和可用的定位路径；错误与对象信息不得包含 Credential 原文、完整 secret_ref、Authorization、client_ip 或消息正文。点击项后目标页面能够定位相同对象或 Trace，权限不足时目标页面返回 ACCESS_DENIED 且不泄露对象是否存在。

### 4.1.4 运行概览接口契约

| 方法与路径 | 请求 | 响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/overview/filters | 可选 alias_id | OverviewFilterOptions | 可查看角色 | ACCESS_DENIED、CONFIG_DATA_UNAVAILABLE、OBJECT_REFERENCE_INVALID |
| GET /admin/overview/summary | OverviewQuery，不使用 granularity | OverviewSummary | 可查看角色；按 application_scope 过滤 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/overview/trends | OverviewQuery，包含 granularity | OverviewTrendResult | 可查看角色；按 application_scope 过滤 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/overview/exceptions | OverviewQuery，不使用 currency 与 granularity | OverviewExceptionResult | 可查看角色；Credential 明细按角色裁剪 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |

三个数据接口使用同一 start_at、end_at、application、alias_id 和 provider_id 语义，并在响应头返回 X-Data-Updated-At。列表或聚合数据源暂时不可用时返回 OBSERVATION_DATA_UNAVAILABLE，不返回成功状态和空集合。请求范围无业务数据时返回字段完整的零值或空 items；权限裁剪发生在聚合前，响应数量不能包含调用方无权查看的数据。

## 4.2 模型接入

模型接入按照实际依赖顺序组织页面：先在 Provider 列表创建并检测外部连接，再进入 Credential Pool 配置凭证与容量，随后维护 Provider Model 的上下文、输出、能力、默认参数和价格，最后创建 Model Alias 并配置 Route Candidate。每个详情页提供前后步骤入口；所有配置保存为草稿，完成整个链路后从待发布变更进入配置校验与发布。

### 4.2.1 Provider 列表页

页面结构：列表按名称、类型、连接状态和启用状态筛选，表格依次显示 name、type、base_url、connection_status、关联模型数、关联凭证池数、last_check_at、enabled、draft_changed 和操作。页面主操作为“新建 Provider”，行操作为查看、编辑、检测、启用、停用和删除。

#### 4.2.1.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 name 和 base_url，输入 2—64 字符后查询，清空恢复全部。 |
| 筛选区 | type | 多选下拉 | Provider Adapter 注册表 | 选项为当前实例已加载的 Provider 类型。 |
| 筛选区 | connection_status | 多选下拉 | Provider.connection_status | 支持 UNKNOWN、AVAILABLE、UNAVAILABLE。 |
| 筛选区 | enabled | 单选下拉 | Provider.enabled | 全部、启用、停用。 |
| 筛选区 | draft_changed | 单选下拉 | Provider.draft_changed | 全部、存在未发布变更、已发布一致。 |
| 列表 | name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | type | 文本 | Provider.type | 显示 Adapter 的产品名称，悬停显示枚举值。 |
| 列表 | base_url | 省略文本 | Provider.base_url | 最多展示一行，悬停显示完整地址。 |
| 列表 | connection_status | 状态文本 | Provider.connection_status | UNKNOWN、AVAILABLE、UNAVAILABLE 使用固定文本，不用颜色代替状态文字。 |
| 列表 | provider_model_count | 数字链接 | ProviderModel 聚合 | 点击进入 Provider Model 列表并带入 provider_id。 |
| 列表 | credential_pool_count | 数字链接 | CredentialPool 聚合 | 点击进入 Credential Pool 列表并带入 provider_id。 |
| 列表 | last_check_at | 时间 | Provider.last_check_at | 为空显示“未检测”，按系统时区展示。 |
| 列表 | enabled | 开关状态 | Provider.enabled | 列表只展示，变更必须使用启停操作。 |
| 列表 | draft_changed | 变更状态 | Provider.draft_changed | true 显示“待发布”，点击进入待发布变更页。 |
| 列表 | actions | 操作菜单 | 权限与对象状态计算 | 只展示当前用户有权限且对象状态允许的操作。 |

#### 4.2.1.2 操作定义

| 操作 | 权限 | 前置条件 | 数据变化 | 成功结果 | 失败处理 |
|---|---|---|---|---|---|
| 新建 Provider | 系统管理员 | Adapter 注册表非空 | 创建 Provider 草稿和 AuditLog | 进入新对象详情，draft_changed=true | 字段错误定位到表单；类型未加载返回 PROVIDER_ADAPTER_NOT_FOUND。 |
| 查看 | 全部可查看角色 | 对象存在 | 无 | 打开详情 | 对象删除后返回 OBJECT_NOT_FOUND 并刷新列表。 |
| 编辑 | 系统管理员 | 对象存在且 version 最新 | 更新草稿、version 和 AuditLog | 返回最新详情 | 版本冲突保留本地输入并提示刷新比较。 |
| 检测 | 系统管理员、运维人员 | 存在可用 Provider Model 与 Credential | 新建 ProviderCheckRecord，更新最近检测字段 | 展示状态、耗时、Provider Request ID | 检测失败不修改 enabled，不写入配置草稿。 |
| 启用 | 系统管理员 | 当前 enabled=false | enabled=true，draft_changed=true，写 AuditLog | 列表显示待发布 | 引用不完整时允许保存，发布校验拦截。 |
| 停用 | 系统管理员 | 当前 enabled=true，已完成影响分析 | enabled=false，draft_changed=true，写 AuditLog | 受影响对象进入待发布变更 | 影响分析失败时禁止确认。 |
| 删除 | 系统管理员 | 无 Credential Pool、Provider Model 和历史生效引用 | 删除未发布对象或标记删除草稿，写 AuditLog | 返回列表 | 存在引用返回 OBJECT_IN_USE 和 blockers。 |

数据流转：列表加载时，管理接口查询 Provider 主表并分别聚合 ProviderModel 与 CredentialPool 数量，连接状态读取最近检测结果，草稿状态通过当前对象与活动快照比较得到。用户执行停用或删除时，页面先调用影响分析接口取得 ImpactAnalysis，再提交带 version 的变更命令；服务端在同一事务中修改草稿和写入 AuditLog，运行快照保持不变，直到配置发布完成。

功能流程：检测操作要求选择一个已配置模型和一个可用 Credential，系统发送最小请求并生成 ProviderCheckRecord。检测期间当前行进入检测中状态，完成后更新 connection_status、耗时和错误。停用 Provider 时页面展示受影响的 Provider Model、Credential Pool 和 Model Alias 候选数量，确认后将其排除于下一运行快照。

规则与异常：同名 Provider 不允许重复。被模型或凭证池引用的 Provider 禁止删除。connection_status 只表示最近检测结果，运行路由仍以 enabled、凭证健康、候选状态和实时容量共同判断。检测超时、鉴权失败和模型不存在需要返回不同错误码。

完成标准：可以创建四种内置 Provider 和 CUSTOM_SPI；检测记录可追溯；停用并发布后不再产生该 Provider 的新 Attempt。

接口：GET/POST /admin/providers，GET/PUT/DELETE /admin/providers/{id}，POST /admin/providers/{id}/check，POST /admin/providers/{id}/enable，POST /admin/providers/{id}/disable。

Provider 列表查询请求包含 keyword、type、connection_status、enabled、draft_changed、page、page_size 和 sort。响应为 PageResult<ProviderListItem>，ProviderListItem 由 Provider 字段加 provider_model_count、credential_pool_count 和 actions 组成。GET /admin/providers/{id}/impact 返回 ImpactAnalysis，POST /admin/providers/{id}/check 接收 ProviderCheckCommand。

### 4.2.2 Provider 新建、编辑与详情页

页面结构：表单字段顺序为 name、type、base_url、proxy_url、connect_timeout_ms、read_timeout_ms、default_headers、enabled。type 在创建后只读。详情页顶部显示连接状态和检测按钮，下方显示基础配置、关联凭证池、关联模型和最近十次检测记录。

#### 4.2.2.1 表单字段

| 字段 | 控件形式 | 新建默认值 | 编辑规则 | 提交规则 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 可编辑 | 去除首尾空格后提交，2—64 字符，全局唯一。 |
| type | 可搜索单选 | 无 | 创建后只读 | 必须来自 Adapter 注册表。 |
| base_url | URL 文本框 | Adapter 默认地址 | 可编辑 | 规范化尾部斜杠；HTTPS 为默认要求。 |
| proxy_url | URL 文本框 | 空 | 可编辑 | 空值直连；填写时校验 http 或 https 协议。 |
| connect_timeout_ms | 数字框 | 3000 | 可编辑 | 整数 100—60000。 |
| read_timeout_ms | 数字框 | 120000 | 可编辑 | 整数 1000—600000，且不小于 connect_timeout_ms。 |
| default_headers | 键值编辑器 | 空对象 | 可编辑 | 键不区分大小写判重；禁止认证和 Cookie 类请求头；最多 20 项。 |
| enabled | 开关 | true | 可编辑 | false 只改变草稿，发布后影响路由。 |
| version | 隐藏字段 | 创建时为空 | 只读 | 更新必须提交当前 version。 |

#### 4.2.2.2 详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 状态摘要 | connection_status | Provider.connection_status | 展示最近检测状态、last_check_at、last_check_latency_ms 和 last_error_code。 |
| 基础信息 | Provider 全部非敏感字段 | Provider | default_headers 展开为键值列表。 |
| 关联凭证池 | id、name、status、credential_available | CredentialPool | 默认展示前 10 条，可进入完整列表。 |
| 关联模型 | id、display_name、model_id、connection_status | ProviderModel | 默认展示前 10 条，可进入完整列表。 |
| 检测记录 | 检查时间、结果、耗时、失败摘要 | Provider Check Record | 按检查时间倒序展示最近 10 条。 |
| 审计信息 | created_by、created_at、updated_by、updated_at、version | Provider | 时间按系统时区展示。 |

#### 4.2.2.3 表单操作与数据流

| 操作 | 请求 | 服务端处理 | 页面结果 |
|---|---|---|---|
| 保存新建 | POST /admin/providers | 校验 Adapter、唯一性和字段范围；创建草稿与 AuditLog | 跳转详情并显示待发布状态。 |
| 保存编辑 | PUT /admin/providers/{id} | 校验 version；计算 FieldChange；更新草稿与 AuditLog | 用响应覆盖表单基线，清除未保存状态。 |
| 取消 | 无 | 无服务端请求 | 返回来源页；存在修改时二次确认。 |
| 检测 | POST /admin/providers/{id}/check | 解析 ProviderCheckCommand，选择指定模型和凭证，调用 Adapter，保存检测记录 | 展示检测结果并刷新状态摘要。 |
| 查看待发布差异 | GET /admin/config/draft-changes | 返回该 Provider 的 FieldChange | 打开差异抽屉，敏感值不展示。 |

数据流转：新建页面先从 Adapter 注册表接口读取 type、默认 base_url、受支持协议和参数范围。提交后，Provider 草稿写入配置存储，活动 ConfigSnapshot 不变。详情检测时，服务端按 credential_id 解析密钥，Adapter 构建最小请求并调用外部系统；响应转换为 ProviderCheckRecord，只更新运行检测字段，不改变草稿版本。发布成功后，新 Trace 才读取更新后的 Provider 配置。

功能流程：用户填写 type 后，页面加载对应 Provider Adapter 的默认 base_url、允许参数范围和保留请求头。保存前先执行前端格式校验，服务端再次校验地址、请求头和超时。保存成功返回详情并标记 draft_changed。编辑 base_url、代理或请求头后，页面提示该变更需要重新检测，但不强制检测后才能保存。

规则与异常：default_headers 的键不区分大小写判重；Authorization、x-api-key、Cookie 等认证字段禁止写入该字段。base_url 必须为合法绝对地址。CUSTOM_SPI 只有在运行环境加载对应 type 实现后才能发布。

完成标准：字段校验与 2.6.3 一致；详情关联数准确；保存、检测、启停均生成审计记录。

### 4.2.3 Credential Pool 列表页

页面结构：列表按 Provider、状态和启用状态筛选，显示 name、Provider、selection_strategy、credential_total、credential_available、current_concurrency、rpm_used、tpm_used、status、draft_changed 和操作。主操作为“新建凭证池”。

#### 4.2.3.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 CredentialPool.name。 |
| 筛选区 | provider_id | 可搜索多选 | Provider | 显示 name，值为 id；默认只加载未删除 Provider。 |
| 筛选区 | status | 多选下拉 | CredentialPool.status | AVAILABLE、PARTIAL_AVAILABLE、UNAVAILABLE、DISABLED。 |
| 筛选区 | enabled | 单选下拉 | CredentialPool.enabled | 全部、启用、停用。 |
| 列表 | name | 链接文本 | CredentialPool.name | 点击进入凭证池详情。 |
| 列表 | provider_name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | selection_strategy | 文本 | CredentialPool.selection_strategy | 展示策略中文名称与枚举值。 |
| 列表 | credential_total | 数字 | 聚合字段 | 包含所有未删除 Credential。 |
| 列表 | credential_available | 数字 | 运行状态聚合 | 只统计当前可参与选择的 Credential。 |
| 列表 | current_concurrency | 数字 | 容量组件 | 自动刷新，不进入草稿。 |
| 列表 | rpm_used | 数字 | 容量组件 | 显示当前分钟已预占请求数。 |
| 列表 | tpm_used | 数字 | 容量组件 | 显示当前分钟 Token 使用与预占量。 |
| 列表 | status | 状态文本 | CredentialPool.status | 状态文字旁展示不可用原因入口。 |
| 列表 | draft_changed | 变更状态 | CredentialPool.draft_changed | true 可跳转待发布差异。 |
| 列表 | actions | 操作菜单 | 权限与引用状态 | 查看、编辑、启用、停用、删除。 |

#### 4.2.3.2 新建与编辑字段

| 字段 | 控件形式 | 新建默认值 | 编辑规则 | 校验 |
|---|---|---|---|---|
| provider_id | 可搜索单选 | 无 | 创建后只读 | 必须指向未删除 Provider。 |
| name | 单行文本 | 空 | 可编辑 | 2—64 字符，同一 Provider 下唯一。 |
| selection_strategy | 单选卡片 | LEAST_CONCURRENT | 可编辑 | 只能选择 LEAST_CONCURRENT、ROUND_ROBIN、WEIGHTED_RANDOM。 |
| enabled | 开关 | true | 可编辑 | 停用前必须完成影响分析。 |
| version | 隐藏字段 | 空 | 只读 | 更新时必填。 |

#### 4.2.3.3 操作与数据流

| 操作 | 前置条件 | 数据变化 | 结果与异常 |
|---|---|---|---|
| 新建 | 系统管理员已选择 Provider | 创建 CredentialPool 草稿和 AuditLog | 成功进入详情；Provider 不存在返回 OBJECT_REFERENCE_INVALID。 |
| 编辑 | version 最新 | 更新字段和 version，写 FieldChange | 策略变化发布后只影响新 Credential 选择。 |
| 启用 | enabled=false | 写 enabled=true 草稿 | 池内无 Credential 时允许保存，发布时校验引用它的启用候选。 |
| 停用 | enabled=true，影响分析成功 | 写 enabled=false 草稿 | 显示受影响 Route Candidate 和 Model Alias。 |
| 删除 | 池内无 Credential 且无候选引用 | 删除草稿或写删除变更 | 存在引用返回 OBJECT_IN_USE。 |

数据流转：列表请求同时读取配置存储中的 CredentialPool、Provider 名称和容量组件中的实时统计。实时字段不参与 version 和 draft_changed。新建、编辑、启停和删除只修改配置草稿；发布构建 Route Candidate 时重新验证 provider_id 关系，运行实例加载快照后才改变凭证选择范围。

功能流程：创建时先选择 Provider，再填写名称和 selection_strategy。点击池名称进入详情页。停用池前展示引用该池的 Route Candidate；确认保存并发布后，这些候选显示 UNAVAILABLE，路由器不会选择池内 Credential。

规则与异常：一个 Provider 可以包含多个池；池不能跨 Provider 使用。存在 Credential 或候选引用时禁止删除。凭证池状态由 enabled 与池内凭证可用数量计算，不允许人工直接修改。

完成标准：池容量汇总与池内 Credential 数据一致；选择策略发布后，新请求使用新策略，正在执行的 Attempt 不迁移。

接口：GET/POST /admin/credential-pools，GET/PUT/DELETE /admin/credential-pools/{id}，POST /admin/credential-pools/{id}/enable，POST /admin/credential-pools/{id}/disable。

列表接口请求包含 keyword、provider_id、status、enabled、page、page_size 和 sort，响应为 PageResult<CredentialPoolListItem>。GET /admin/credential-pools/{id}/impact 返回 ImpactAnalysis。

### 4.2.4 Credential Pool 详情与 Credential 管理页

页面结构：详情顶部展示凭证池基础信息和容量摘要，下方 Credential 表格依次显示 name、masked_value、secret_source、weight、RPM、TPM、并发上限、current_concurrency、health_status、rate_limit_reset_at、last_success_at、last_check_at、draft_changed 和操作。主操作为“新增 Credential”，行操作为编辑、轮换密钥、检测、启用、停用和删除。

#### 4.2.4.1 凭证池详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 基础信息 | id、name、provider_id、selection_strategy、enabled、version | CredentialPool | provider_id 显示 Provider 名称并可跳转。 |
| 容量摘要 | credential_total、credential_available、current_concurrency、rpm_used、tpm_used、status | 配置聚合与容量组件 | 每 10 秒刷新；刷新不改变表单。 |
| 引用关系 | route_candidate_count、model_alias_count | RouteCandidate 聚合 | 点击进入候选或 Alias 列表。 |
| 审计信息 | created_by、created_at、updated_by、updated_at、draft_changed | CredentialPool | draft_changed 可查看 FieldChange。 |

#### 4.2.4.2 Credential 列表字段

| 字段 | 数据来源 | 展示规则 | 可排序 |
|---|---|---|---|
| name | Credential.name | 点击打开编辑抽屉。 | 是 |
| masked_value | Credential.masked_value | 固定脱敏；EXTERNAL_REF 同时显示 secret_ref 的非敏感标识。 | 否 |
| secret_source | Credential.secret_source | 显示“加密存储”或“外部引用”。 | 是 |
| weight | Credential.weight | 仅 WEIGHTED_RANDOM 时突出显示。 | 是 |
| rpm_limit | Credential.rpm_limit | 空值显示“不限制”。 | 是 |
| tpm_limit | Credential.tpm_limit | 空值显示“不限制”。 | 是 |
| concurrent_limit | Credential.concurrent_limit | 空值显示“不限制”。 | 是 |
| current_concurrency | 容量组件 | 当前执行中 Attempt 数，每 10 秒刷新。 | 是 |
| health_status | Credential.health_status | 展示状态和最近错误入口。 | 是 |
| rate_limit_reset_at | Credential.rate_limit_reset_at | 非 RATE_LIMITED 时显示空。 | 是 |
| last_success_at | Credential.last_success_at | 无成功记录显示“暂无”。 | 是 |
| last_check_at | Credential.last_check_at | 无检测记录显示“未检测”。 | 是 |
| enabled | Credential.enabled | 只读展示，启停通过操作提交。 | 是 |
| draft_changed | Credential.draft_changed | true 显示待发布。 | 是 |
| actions | 权限和状态计算 | 编辑、轮换、检测、启用、停用、删除。 | 否 |

#### 4.2.4.3 Credential 表单字段

| 字段 | 控件形式 | 新建默认值 | 可见与编辑规则 | 校验与持久化 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 新建、编辑可见 | 2—64 字符，同一 pool_id 下唯一。 |
| secret_source | 单选 | INLINE_ENCRYPTED | 新建可选，创建后只读 | 决定 secret_value 或 secret_ref 的必填规则。 |
| secret_value | 密码输入框 | 空 | 仅新建或轮换可见 | 1—4096 字符，加密后保存；响应不返回。 |
| secret_value_confirm | 密码输入框 | 空 | 仅 INLINE_ENCRYPTED 新建或轮换可见 | 必须与 secret_value 完全一致，不持久化。 |
| secret_ref | 单行文本 | 空 | EXTERNAL_REF 时可见；编辑可修改 | 1—512 字符；只保存引用，不解析后回显值。 |
| masked_value | 只读文本 | 保存后生成 | 详情和列表可见 | 服务端计算，不接受提交。 |
| weight | 数字框 | 1 | 始终可编辑 | 1—100。 |
| rpm_limit | 数字框 | 空 | 始终可编辑 | 空或正整数。 |
| tpm_limit | 数字框 | 空 | 始终可编辑 | 空或正整数。 |
| concurrent_limit | 数字框 | 空 | 始终可编辑 | 空或 1—100000。 |
| enabled | 开关 | true | 始终可编辑 | false 发布后停止选择。 |
| version | 隐藏字段 | 空 | 编辑时只读 | 更新、轮换、启停必须提交。 |

#### 4.2.4.4 Credential 操作定义

| 操作 | 权限与前置条件 | 服务端数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新增 | 系统管理员；凭证池存在 | 创建 Credential 草稿；加密 secret_value 或保存 secret_ref；写 AuditLog | 返回不含密钥的 Credential。 |
| 编辑 | 系统管理员；version 最新 | 更新名称、引用、权重和限额；不接受 secret_value | 刷新行数据；密钥变更必须使用轮换。 |
| 轮换密钥 | 系统管理员；INLINE_ENCRYPTED | 加密新值、更新 masked_value 与 version、清除相关短时缓存、写 AuditLog | 只显示轮换成功，不返回新密钥。 |
| 检测 | 系统管理员、运维人员；已选择 Provider Model | 解析密钥并调用 Adapter；新建 ProviderCheckRecord；更新 health_status | 展示耗时、请求 ID 和错误；不生成草稿。 |
| 启用 | 系统管理员；enabled=false | enabled=true，health_status=UNKNOWN，draft_changed=true | 提示发布后参与选择。 |
| 停用 | 系统管理员；enabled=true | enabled=false，draft_changed=true | 发布后停止新选择；运行中 Attempt 继续。 |
| 删除 | 系统管理员；无运行中 Attempt，且池中仍满足候选可用性或候选已停用 | 删除未发布对象或记录删除草稿 | 条件不满足返回 OBJECT_IN_USE 或 CAPACITY_IN_USE。 |

数据流转：创建 INLINE_ENCRYPTED Credential 时，前端只通过 HTTPS 提交一次 secret_value，服务端加密后写入密钥字段并生成 masked_value，事务完成后清除请求对象中的明文引用。EXTERNAL_REF 只保存 secret_ref。运行时从已发布快照取得 credential_id，在发起 Provider 请求前解析密钥并写入内存请求头；检测和业务调用都不把密钥写入 ProviderCheckRecord、Trace、Attempt、AuditLog 或日志。

检测数据流：用户先选择 provider_model_id，页面提交 ProviderCheckCommand。服务端验证模型与凭证属于同一 Provider，预占检测并发，解析 Credential，调用 Adapter 最小请求，分类结果并保存 ProviderCheckRecord，随后更新 Credential.health_status、last_check_at 和 last_error_code。检测不计入业务 Usage 和 Cost，但产生独立运行指标。

功能流程：新增 Credential 时选择 secret_source。INLINE_ENCRYPTED 要求输入 secret_value 并二次确认；EXTERNAL_REF 要求输入 secret_ref。保存后页面只显示 masked_value。轮换密钥打开独立表单，新密钥保存成功后旧密钥立即失效，运行中的 Attempt 保持其已取得的内存值。检测时选择池所属 Provider 的一个模型，执行最小调用并更新 health_status。

规则与异常：secret_value 和 secret_ref 互斥。读取接口永远不返回 secret_value、token_hash 或已解析的外部 Secret。INVALID 由明确鉴权失败产生；RATE_LIMITED 在复位时间前不参与选择；UNKNOWN 可以参与选择，但优先级低于 HEALTHY；DISABLED 永不参与选择。运行请求产生明确成功结果后可以把 UNKNOWN 或暂时不可用状态恢复为 HEALTHY。

选择规则：LEAST_CONCURRENT 选择当前并发占上限比例最低的 Credential；ROUND_ROBIN 在可用 Credential 间轮转；WEIGHTED_RANDOM 根据 weight 随机。任一策略在选择前都过滤停用、INVALID、未复位的 RATE_LIMITED、并发已满和分钟容量不足的 Credential。

完成标准：密钥明文不出现在详情、网络读取响应、日志、Trace 和 AuditLog；停用或判定无效的 Credential 不产生新 Attempt；并发计数在成功、失败、取消和超时后均正确归还。

接口：GET/POST /admin/credential-pools/{poolId}/credentials，GET/PUT/DELETE /admin/credentials/{id}，POST /admin/credentials/{id}/rotate，POST /admin/credentials/{id}/check，POST /admin/credentials/{id}/enable，POST /admin/credentials/{id}/disable。

### 4.2.5 Provider Model 列表页

页面结构：列表按 Provider、模型标识、连接状态、流式能力和启用状态筛选，显示 display_name、model_id、Provider、context_window、max_output_tokens、support_stream、input_price、output_price、currency、connection_status、draft_changed 和操作。主操作为“新建模型”。

#### 4.2.5.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 display_name 和 model_id。 |
| 筛选区 | provider_id | 可搜索多选 | Provider | 显示 Provider.name。 |
| 筛选区 | connection_status | 多选下拉 | ProviderModel.connection_status | UNKNOWN、AVAILABLE、UNAVAILABLE。 |
| 筛选区 | support_stream | 单选下拉 | ProviderModel.support_stream | 全部、支持、不支持。 |
| 筛选区 | enabled | 单选下拉 | ProviderModel.enabled | 全部、启用、停用。 |
| 列表 | display_name | 链接文本 | ProviderModel.display_name | 点击进入模型详情。 |
| 列表 | model_id | 等宽文本 | ProviderModel.model_id | 保持大小写原值，可复制。 |
| 列表 | provider_name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | context_window | 数字 | ProviderModel.context_window | 使用千分位，不缩写。 |
| 列表 | max_output_tokens | 数字 | ProviderModel.max_output_tokens | 使用千分位。 |
| 列表 | support_stream | 是/否文本 | ProviderModel.support_stream | 显示明确文本。 |
| 列表 | input_price | 金额 | ProviderModel.input_price | 与 price_unit、currency 组合显示。 |
| 列表 | output_price | 金额 | ProviderModel.output_price | 与 price_unit、currency 组合显示。 |
| 列表 | connection_status | 状态文本 | ProviderModel.connection_status | 同时显示 last_check_at。 |
| 列表 | route_candidate_count | 数字链接 | RouteCandidate 聚合 | 点击查看引用该模型的候选。 |
| 列表 | enabled | 状态文本 | ProviderModel.enabled | 启停通过操作提交。 |
| 列表 | draft_changed | 变更状态 | ProviderModel.draft_changed | true 显示待发布。 |
| 列表 | actions | 操作菜单 | 权限和引用状态 | 查看、编辑、检测、启停、删除。 |

#### 4.2.5.2 模型导入字段与流程

| 字段或控件 | 控件形式 | 来源 | 规则 |
|---|---|---|---|
| provider_id | 单选下拉 | Provider | 必须选择一个启用或草稿启用的 Provider。 |
| source | 单选 | ProviderModelImportCommand.source | 默认 PROVIDER_API；Adapter 不支持模型列表时只允许 ADAPTER_PRESET。 |
| credential_id | 单选下拉 | Credential | PROVIDER_API 时必填，只显示同 Provider 的非停用 Credential。 |
| keyword | 文本框 | 页面状态 | 在候选 model_id 和 display_name 中过滤。 |
| candidate_table | 多选表格 | ProviderModelImportCandidate | 展示 model_id、来源、已存在、tokenizer、上下文、最大输出、流式、system、temperature、top_p 与 stop 能力默认值；未知字段明确显示“待补充”。 |
| apply_known_defaults | 开关 | ModelImportCommand | 默认 true；false 时能力字段留空并要求逐个补全。 |
| enabled | 开关 | ModelImportCommand | 默认 false，避免未经核验直接进入启用草稿。 |

数据流转：用户选择 Provider 和来源后，页面调用 GET /admin/providers/{id}/available-models。PROVIDER_API 模式由服务端解析 credential_id 并通过 Adapter 调用外部模型列表接口；ADAPTER_PRESET 模式读取当前 Adapter 版本内置目录。服务端把外部结果转换为 ProviderModelImportCandidate，并通过 provider_id + model_id 标记 existing。用户提交选中 model_ids 后，服务端在一个事务中跳过已存在项、创建其余 ProviderModel 草稿、写入逐对象 AuditLog，并返回 created、skipped 和 failed 明细。

当 Provider API 只返回 model_id 时，context_window、max_output_tokens 和能力字段保持空值，导入结果不能发布为 enabled=true，管理员必须在编辑页补齐。Adapter 预置默认值用于减少录入，不代表 Provider 实时承诺，页面需要显示默认值来源。

#### 4.2.5.3 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 结果与异常 |
|---|---|---|---|
| 新建模型 | 系统管理员；存在 Provider | 创建单个 ProviderModel 草稿 | 进入编辑详情。 |
| 导入模型 | 系统管理员；Provider 与来源可用 | 批量创建 ProviderModel 草稿和 AuditLog | 返回逐项结果，重复项跳过。 |
| 查看 | 可查看角色；对象存在 | 无 | 打开详情。 |
| 编辑 | 系统管理员；version 最新 | 更新模型草稿 | 参数与价格变更只影响发布后的新 Attempt。 |
| 检测 | 系统管理员、运维人员；选择同 Provider Credential | 新建 ProviderCheckRecord，更新检测字段 | 展示耗时、Usage 和 Provider Request ID。 |
| 批量检测 | 系统管理员、运维人员；选中 1—20 个同 Provider 模型并指定 Credential | 以最大并发 3 逐个检测，每个模型独立写记录 | 展示成功、失败和未执行数量；单项失败不终止其余项。 |
| 启用或停用 | 系统管理员；完成影响分析 | 更新 enabled 草稿 | 发布后改变候选过滤结果。 |
| 删除 | 系统管理员；无 Route Candidate 引用 | 删除草稿或记录删除变更 | 有引用返回 OBJECT_IN_USE。 |

功能流程：用户先选择 Provider，再填写模型基本能力、默认参数和价格。检测操作要求选择该 Provider 下的可用 Credential，发送最小消息并记录响应模型、耗时、Usage 和错误。停用模型时展示引用它的 Route Candidate 数量。

规则与异常：context_window 必须大于 max_output_tokens。default_max_tokens 不得超过 max_output_tokens。temperature 和 top_p 的值需要同时满足统一规则与 Adapter 能力。价格修改只影响修改后完成的 Attempt，历史费用保持原值。模型检测成功不自动覆盖管理员填写的上下文和价格。

完成标准：模型页面完整呈现 2.6.5 的上下文、能力、参数和计费字段；保存后的运行请求应用默认参数；停用并发布后不产生该模型的新 Attempt。

接口：GET/POST /admin/provider-models，GET/PUT/DELETE /admin/provider-models/{id}，POST /admin/provider-models/{id}/check，POST /admin/provider-models/{id}/enable，POST /admin/provider-models/{id}/disable。

模型导入增加 GET /admin/providers/{id}/available-models、POST /admin/provider-models/import 和 POST /admin/provider-models/batch-check。导入接口接收 ProviderModelImportCommand，返回 created_items、skipped_items 和 failed_items；批量检测接收 provider_model_ids、credential_id、mode 和 timeout_ms。

### 4.2.6 Provider Model 新建、编辑与详情页

页面结构：表单分为基础信息、能力、默认生成参数和价格四个连续区块。字段顺序为 provider_id、display_name、model_id、model_type、tokenizer_family、context_window、max_output_tokens、support_stream、support_system_message、default_temperature、default_top_p、default_max_tokens、default_stop、input_price、output_price、price_unit、currency、enabled。详情页增加关联 Model Alias 和最近检测记录。

#### 4.2.6.1 表单字段

| 区域 | 字段 | 控件形式 | 默认值 | 编辑与校验规则 |
|---|---|---|---|---|
| 基础信息 | provider_id | 可搜索单选 | 从入口带入或空 | 创建后只读；必须指向有效 Provider。 |
| 基础信息 | display_name | 单行文本 | 导入名称或空 | 2—64 字符，同一 Provider 下唯一。 |
| 基础信息 | model_id | 单行文本 | 导入标识或空 | 1—128 字符，保持大小写；同一 Provider 下唯一。 |
| 基础信息 | model_type | 只读单选 | CHAT_TEXT | V1.0 固定为 CHAT_TEXT。 |
| 能力 | tokenizer_family | Adapter 选项单选 | Adapter 默认值 | 必填；只能选择当前 Provider Adapter 声明的 TokenEstimator。 |
| 能力 | context_window | 数字框 | Adapter 已知值或空 | 必填正整数；必须大于 max_output_tokens。 |
| 能力 | max_output_tokens | 数字框 | Adapter 已知值或空 | 必填，范围 1—context_window。 |
| 能力 | support_stream | 开关 | Adapter 已知值或 true | 必填；关闭后流式请求过滤该模型。 |
| 能力 | support_system_message | 开关 | Adapter 已知值或 true | 必填；关闭后 system 请求过滤该模型。 |
| 参数能力 | support_temperature | 开关 | Adapter 已知值 | Adapter 不支持时固定为 false；false 时隐藏 temperature 范围与默认值。 |
| 参数能力 | temperature_min | 小数框 | Adapter 下限 | support_temperature=true 时必填，不得超出 Adapter 范围。 |
| 参数能力 | temperature_max | 小数框 | Adapter 上限 | support_temperature=true 时必填，必须大于等于 temperature_min。 |
| 参数能力 | support_top_p | 开关 | Adapter 已知值 | Adapter 不支持时固定为 false；false 时隐藏 top_p 范围与默认值。 |
| 参数能力 | top_p_min | 小数框 | Adapter 下限 | support_top_p=true 时必填，范围 0—1。 |
| 参数能力 | top_p_max | 小数框 | Adapter 上限 | support_top_p=true 时必填，范围 0—1 且大于等于 top_p_min。 |
| 参数能力 | support_stop | 开关 | Adapter 已知值 | Adapter 不支持时固定为 false；false 时隐藏 stop 上限与默认值。 |
| 参数能力 | max_stop_sequences | 数字框 | Adapter 上限与 4 的较小值 | support_stop=true 时必填，范围 1—4。 |
| 参数能力 | max_stop_length | 数字框 | Adapter 上限与 128 的较小值 | support_stop=true 时必填，范围 1—128。 |
| 默认参数 | default_temperature | 小数框 | 空 | 空或模型 temperature_min—temperature_max；请求值优先。 |
| 默认参数 | default_top_p | 小数框 | 空 | 空或模型 top_p_min—top_p_max；请求值优先。 |
| 默认参数 | default_max_tokens | 数字框 | 空 | 空或 1—max_output_tokens。 |
| 默认参数 | default_stop | 可增删文本列表 | 空 | 项数不超过 max_stop_sequences，每项不超过 max_stop_length，去重后提交。 |
| 价格 | input_price | 金额框 | 0 | decimal(20,8)，不得小于 0。 |
| 价格 | output_price | 金额框 | 0 | decimal(20,8)，不得小于 0。 |
| 价格 | price_unit | 单选 | 1000000 | 只能为 1000 或 1000000。 |
| 价格 | currency | 可搜索单选 | USD | ISO 4217 三位代码。 |
| 状态 | enabled | 开关 | 新建 true、导入 false | 启用模型必须补齐所有必填能力字段。 |
| 系统 | version | 隐藏字段 | 空 | 更新时提交当前 version。 |

#### 4.2.6.2 参数合并顺序

一次请求的生成参数按照“UnifiedChatRequest 显式值、Provider Model 默认值、Provider 默认行为”的顺序确定。temperature、top_p、max_tokens 和 stop 分别独立合并，调用方未填写某字段时才读取模型默认值。调用方显式值和模型默认值都必须落在 Provider Model 声明的支持状态与范围内；Adapter 能力是模型范围的上界。provider_options 只接受 Adapter 声明的白名单字段，不能覆盖 model、messages、stream 和认证请求头。

temperature 和 top_p 在各自 support 字段为 true 时按模型 min、max 校验。stop 只有在 support_stop=true 时可用，列表项数与单项长度分别受 max_stop_sequences 和 max_stop_length 限制。max_tokens 合并完成后，运行时先校验不超过 max_output_tokens，再与估算 input_tokens 相加校验 context_window。候选模型的参数范围不一致时，路由器过滤不兼容候选；所有候选均不兼容时返回 MODEL_CAPABILITY_NOT_SUPPORTED 或 CONTEXT_WINDOW_EXCEEDED。

#### 4.2.6.3 详情字段与操作

| 区域或操作 | 字段或输入 | 数据流与结果 |
|---|---|---|
| 状态摘要 | connection_status、last_check_at、last_error_code | 来源于最近 ProviderCheckRecord，不进入配置草稿。 |
| 关联 Alias | alias、priority、weight、credential_pool、candidate_status | 来源于 RouteCandidate，点击进入 Alias 候选行。 |
| 检测记录 | 检查时间、结果、耗时、失败摘要 | 按检查时间倒序展示最近 20 条。 |
| 保存 | ProviderModel 可编辑字段、version | 服务端校验组合约束，写草稿、FieldChange 和 AuditLog。 |
| 检测 | credential_id、mode、timeout_ms | Adapter 发起最小请求，记录响应模型、Token、耗时和错误。 |
| 查看用量 | provider_model_id、时间范围 | 跳转 Usage 页面并带入模型筛选。 |
| 查看调用 | provider_model_id、时间范围 | 跳转 Trace 页面并带入模型筛选。 |

数据流转：保存模型时，配置存储记录管理员确认的上下文、能力、默认参数和价格。配置发布把这些字段写入不可变 ConfigSnapshot。运行请求建立 Trace 后固定 snapshot_no，路由阶段读取模型能力过滤候选，参数合并后由 Adapter 转换外部请求；Provider 返回 Usage 后，Attempt 使用快照中的 input_price、output_price、price_unit 和 currency 固化 cost，因此后续价格编辑不会回算历史记录。

功能流程：选择 Provider 后页面加载 Adapter 参数能力。用户保存时，服务端对组合约束进行校验。详情页点击某个关联 Alias 可进入 Alias 详情，并定位到引用该模型的候选行。

规则与异常：模型标识区分大小写并按原值传给 Provider；同一 Provider 下禁止重复。support_stream 为 false 时，包含该模型的候选不能处理 stream=true 请求。support_system_message 为 false 时，含 system 消息的请求过滤该候选；没有其他候选时返回 MODEL_CAPABILITY_NOT_SUPPORTED。

完成标准：模型字段和运行能力过滤一致；编辑模型标识、上下文和能力后必须通过发布才生效；历史 Trace 保留当时的 provider_model_id 和模型标识快照。

### 4.2.7 Model Alias 列表页

页面结构：列表按 alias、启用状态和运行可用性筛选，显示 alias、display_name、route_strategy、candidate_count、available_candidate_count、support_stream、enabled、draft_changed、updated_at 和操作。主操作为“新建 Model Alias”。

#### 4.2.7.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 alias、display_name 和 description。 |
| 筛选区 | enabled | 单选下拉 | ModelAlias.enabled | 全部、启用、停用。 |
| 筛选区 | runtime_availability | 单选下拉 | available_candidate_count 计算 | 全部、可调用、无可用候选。 |
| 筛选区 | support_stream | 单选下拉 | ModelAlias.support_stream | 全部、支持、不支持。 |
| 列表 | alias | 链接与复制控件 | ModelAlias.alias | 点击进入详情，复制只复制原始字符串。 |
| 列表 | display_name | 文本 | ModelAlias.display_name | 单行展示。 |
| 列表 | route_strategy | 文本 | ModelAlias.route_strategy | V1.0 显示 PRIORITY_WEIGHTED。 |
| 列表 | candidate_count | 数字 | ModelAlias.candidate_count | 点击进入候选区域。 |
| 列表 | available_candidate_count | 数字 | ModelAlias.available_candidate_count | 实时刷新；为 0 时显示不可调用原因入口。 |
| 列表 | support_stream | 状态文本 | ModelAlias.support_stream | stream_candidate_count 小于 candidate_count 时显示“部分支持”。 |
| 列表 | request_count_24h | 数字链接 | Trace 聚合 | 点击进入最近 24 小时 Trace。 |
| 列表 | enabled | 状态文本 | ModelAlias.enabled | 启停通过操作执行。 |
| 列表 | draft_changed | 变更状态 | ModelAlias.draft_changed | true 显示待发布。 |
| 列表 | updated_at | 时间 | ModelAlias.updated_at | 按系统时区展示。 |
| 列表 | actions | 操作菜单 | 权限与引用状态 | 查看、编辑、启用、停用、删除。 |

#### 4.2.7.2 新建与编辑字段

| 字段 | 控件形式 | 默认值 | 编辑规则 | 校验 |
|---|---|---|---|---|
| alias | 单行等宽文本 | 空 | 创建后只读 | 2—64 字符，只允许字母、数字、点、短横线和下划线，全局唯一。 |
| display_name | 单行文本 | 空 | 可编辑 | 2—64 字符。 |
| description | 多行文本 | 空 | 可编辑 | 最多 500 字符，不用于模型 Prompt。 |
| route_strategy | 只读单选 | PRIORITY_WEIGHTED | V1.0 只读 | 固定优先级内按权重选择。 |
| enabled | 开关 | true | 可编辑 | enabled=true 的 Alias 发布时必须至少有一个启用且引用完整的候选。 |
| version | 隐藏字段 | 空 | 只读 | 更新时必填。 |

#### 4.2.7.3 操作定义与数据流

| 操作 | 前置条件 | 数据变化 | 成功结果 | 失败处理 |
|---|---|---|---|---|
| 新建 | 系统管理员；alias 唯一 | 创建 ModelAlias 草稿和 AuditLog | 进入详情并引导新增候选 | 重名返回 FIELD_VALIDATION_FAILED。 |
| 编辑 | version 最新 | 更新 display_name、description、enabled 和 version | 返回最新详情 | alias 字段即使提交也被拒绝。 |
| 启用 | 当前停用 | enabled=true 草稿 | 提示配置候选并发布 | 无候选允许保存，发布校验失败。 |
| 停用 | 当前启用，影响分析完成 | enabled=false 草稿 | 展示最近调用与 Access Credential 影响 | 影响分析失败禁止提交。 |
| 删除 | 无治理策略和 Access Credential 引用，活动快照未启用 | 删除草稿或记录删除变更 | 返回列表 | blockers 非空返回 OBJECT_IN_USE。 |
| 查看调用 | 对象存在 | 无 | 跳转 Trace 并带入 alias 和时间范围 | 无数据展示空状态。 |

数据流转：Alias 保存后只存在于配置草稿，新增候选后形成 Alias → RouteCandidate → ProviderModel/CredentialPool 的完整引用。发布校验把 Alias 与全部候选作为一个聚合检查，并写入同一 ConfigSnapshot。业务请求的 model 字段只解析已发布 Alias；Alias 的展示名称和描述不进入外部 Provider 请求。

功能流程：创建时填写 alias、display_name、description 和 enabled，保存后进入详情继续配置候选。停用时展示最近 24 小时调用量和使用该 Alias 的 Standalone Access Credential 数量，确认并发布后调用返回 MODEL_ALIAS_DISABLED。

规则与异常：alias 创建后不可修改，避免业务调用入口变化；如需更名，应创建新 Alias 并迁移接入方。没有候选的 Alias 可以保存草稿，但不能发布为启用状态。删除前必须确认没有 Access Credential 和治理策略引用，且最近发布快照中未启用。

完成标准：已发布 Alias 可以通过 GET /v1/models 查询；停用或不存在的 Alias 返回明确错误；列表可直接判断候选是否具备运行可用性。

接口：GET/POST /admin/model-aliases，GET/PUT/DELETE /admin/model-aliases/{id}，POST /admin/model-aliases/{id}/enable，POST /admin/model-aliases/{id}/disable。

列表查询请求包含 keyword、enabled、runtime_availability、support_stream、page、page_size 和 sort。GET /admin/model-aliases/{id}/impact 返回 ImpactAnalysis，结果增加引用它的 ReliabilityPolicy、LimitPolicy 和 StandaloneAccessCredential 标识。

### 4.2.8 Model Alias 详情与候选路由页

页面结构：详情顶部展示 Alias 基础信息、当前发布快照和运行可用状态。候选表格按 priority 升序排列，同优先级按权重展示，字段为 Provider、Provider Model、Credential Pool、priority、weight、能力、current_concurrency、runtime_status、excluded_reason、draft_changed 和操作。页面支持新增、编辑、启停、删除候选和执行探测。

#### 4.2.8.1 Alias 详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 基础信息 | id、alias、display_name、description、route_strategy、enabled、version | ModelAlias | alias 可复制，route_strategy 只读。 |
| 能力摘要 | support_stream、stream_candidate_count、candidate_count | ModelAlias 聚合 | 展示支持流式的候选比例。 |
| 运行摘要 | available_candidate_count、request_count_24h、success_rate_24h、p95_total_ms_24h | 候选实时状态与 Trace 聚合 | 每 30 秒刷新，不写入草稿。 |
| 配置状态 | current_snapshot_no、draft_changed、updated_by、updated_at | RuntimeConfig 与 ModelAlias | 可进入待发布差异。 |
| 治理策略 | LimitPolicy、ReliabilityPolicy 摘要 | 治理实体 | 未配置时显示系统默认可靠性参数入口。 |

#### 4.2.8.2 Route Candidate 列表与表单字段

| 字段 | 控件或展示形式 | 新建默认值 | 数据来源与规则 |
|---|---|---|---|
| provider_model_id | 分组搜索单选 | 空 | 表单按 Provider 分组显示启用或草稿启用模型；选中后只读展示模型能力。 |
| credential_pool_id | 单选 | 空 | 只显示与 provider_model_id 同 Provider 的凭证池。 |
| provider_name | 只读文本 | 由模型计算 | 来源于 Provider.name。 |
| provider_model_name | 链接文本 | 由模型计算 | 显示 display_name 与 model_id，可进入模型详情。 |
| credential_pool_name | 链接文本 | 由池计算 | 显示名称、可用凭证数和池状态。 |
| priority | 数字框 | 10 | 1—100，数值越小越优先。 |
| weight | 数字框 | 1 | 1—100，只在同 priority 可用集合内计算。 |
| enabled | 开关 | true | false 发布后排除。 |
| support_stream | 只读状态 | 由模型计算 | 来源于 ProviderModel.support_stream。 |
| support_system_message | 只读状态 | 由模型计算 | 来源于 ProviderModel.support_system_message。 |
| context_window | 只读数字 | 由模型计算 | 来源于 ProviderModel.context_window。 |
| current_concurrency | 只读数字 | 0 | 来自运行容量组件，定时刷新。 |
| runtime_status | 只读状态 | UNAVAILABLE | 由启停、引用、凭证、熔断和容量共同计算。 |
| excluded_reason | 只读文本 | 空 | 只在 runtime_status 非 AVAILABLE 时展示。 |
| version | 隐藏字段 | 空 | 更新时提交。 |
| draft_changed | 只读状态 | true | 与活动快照比较得到。 |

#### 4.2.8.3 候选操作定义

| 操作 | 权限与前置条件 | 数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新增候选 | 系统管理员；已选择同 Provider 模型与池 | 创建 RouteCandidate 草稿和 AuditLog | 插入对应 priority 分组。 |
| 编辑候选 | 系统管理员；version 最新 | 更新 pool、priority、weight、enabled 和 version | 重新排序并显示待发布。 |
| 调整顺序 | 系统管理员；选中一个或多个候选 | 批量更新 priority 和 version | 全部成功后重排；任一版本冲突则整批不提交。 |
| 启用或停用 | 系统管理员 | 更新 enabled 草稿 | 发布后改变候选集合。 |
| 删除 | 系统管理员；对象存在 | 删除草稿或记录删除变更 | Alias 启用且删除后无候选时允许保存，发布校验拦截。 |
| 探测 | 系统管理员、运维人员；候选引用完整且池内有可用 Credential | 选择一个 Credential，执行 ProviderCheckCommand，写 ProviderCheckRecord | 显示实际 Credential 脱敏值、耗时、Token 和错误。 |
| 查看 Trace | 可查看角色 | 无 | 跳转 Trace 并带入 route_candidate_id；无数据时展示空状态。 |

#### 4.2.8.4 候选配置数据流

新增或编辑候选时，前端先根据 provider_model_id 请求可选凭证池，服务端再次校验 ProviderModel.provider_id 与 CredentialPool.provider_id 相同，并校验相同 alias_id、provider_model_id、credential_pool_id 组合不存在。保存操作只写 RouteCandidate 草稿和 AuditLog。

批量调整顺序提交 items，每项包含 id、priority 和 version。服务端在单个事务中校验所有版本与范围，任一项失败时全部回滚。权重不通过拖动隐式修改，只有显式编辑 weight 才产生 FieldChange。

发布时，服务端从 Alias 开始装配候选图，检查 Provider、Provider Model、Credential Pool、至少一个 Credential、治理策略和候选价格币种；同一 Alias 的全部启用候选必须使用同一 currency，保证一次 Trace 在重试和 Fallback 后仍可形成单一费用总额。校验成功后把完整关系写入 ConfigSnapshot。运行时在一次 Trace 内读取固定快照，实时叠加 Credential 健康、容量和 CircuitState，产生 RouteDecision；选择成功后才创建 Attempt。

功能流程：新增候选时先选择 Provider Model，Credential Pool 下拉框只显示同 Provider 的池。保存后页面即时检查模型与池关系、重复组合和能力差异。拖动只用于调整优先级，释放后明确保存；同优先级权重通过数字字段修改。探测候选时，系统选择池内一个可用 Credential 执行最小调用并记录 ProviderCheckRecord。

路由规则：运行时按 priority 从小到大分组。先过滤停用、Provider 或模型停用、池不可用、能力不匹配、熔断 OPEN 和容量不足的候选，再在当前最低可用 priority 内按 weight 选择。选中的凭证在 Credential Pool 内按 selection_strategy 产生。当前优先级全部失败且 fallback_enabled=true 时进入下一优先级。

规则与异常：同一 Alias 下 provider_model_id 与 credential_pool_id 组合不得重复。至少一个启用候选满足模型、凭证池、Credential 和能力条件时，启用 Alias 才能发布。启用候选 currency 不一致时，ConfigValidationResult.status=FAILED，并生成 code=PRICE_CONFIGURATION_INVALID 的字段级问题，逐项指出候选和币种。候选实时 runtime_status 不写入草稿，也不触发发布。

完成标准：页面顺序与运行路由顺序一致；发布校验能定位无凭证、跨 Provider、能力冲突和重复候选；Trace 能显示最终选择和被尝试的每个候选。

接口：GET /admin/model-aliases/{id}/candidates，POST /admin/model-aliases/{id}/candidates，PUT/DELETE /admin/route-candidates/{id}，POST /admin/route-candidates/{id}/check。

候选接口增加 GET /admin/provider-models/{id}/credential-pools 和 PUT /admin/model-aliases/{id}/candidates/reorder。reorder 请求为 items 数组，每项包含 id、priority、version；响应返回更新后的全部候选和 Alias 聚合字段。

### 4.2.9 模型接入管理接口契约

模型接入管理接口统一使用 JSON。列表查询返回 PageResult，详情查询返回完整非敏感实体，写操作返回 ManagementOperationResult。更新、启停、轮换和删除必须提交 version；服务端先校验权限和 version，再校验引用与字段，在同一事务中写业务草稿和 AuditLog。检测记录属于运行数据，检测接口不修改配置 version 和 draft_changed。

#### 4.2.9.1 Provider 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/providers | keyword、type、connection_status、enabled、draft_changed、page、page_size、sort | PageResult<ProviderListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/providers | name、type、base_url、proxy_url、connect_timeout_ms、read_timeout_ms、default_headers、enabled | ManagementOperationResult | 系统管理员 | PROVIDER_ADAPTER_NOT_FOUND、FIELD_VALIDATION_FAILED |
| GET /admin/providers/{id} | id | ProviderDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/providers/{id} | Provider 可编辑字段、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| GET /admin/providers/{id}/impact | id、operation | ImpactAnalysis | 系统管理员 | OBJECT_NOT_FOUND |
| POST /admin/providers/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、PROVIDER_AUTH_FAILED、TOTAL_TIMEOUT |
| POST /admin/providers/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/providers/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED、CONFIG_VERSION_CONFLICT |
| DELETE /admin/providers/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE、IMPACT_ANALYSIS_EXPIRED |

#### 4.2.9.2 Credential Pool 与 Credential 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/credential-pools | keyword、provider_id、status、enabled、page、page_size、sort | PageResult<CredentialPoolListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/credential-pools | provider_id、name、selection_strategy、enabled | ManagementOperationResult | 系统管理员 | OBJECT_REFERENCE_INVALID、FIELD_VALIDATION_FAILED |
| GET /admin/credential-pools/{id} | id | CredentialPoolDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/credential-pools/{id} | name、selection_strategy、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| GET /admin/credential-pools/{id}/impact | id、operation | ImpactAnalysis | 系统管理员 | OBJECT_NOT_FOUND |
| POST /admin/credential-pools/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/credential-pools/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED |
| DELETE /admin/credential-pools/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE |
| GET /admin/credential-pools/{poolId}/credentials | health_status、enabled、page、page_size、sort | PageResult<CredentialListItem> | 系统管理员、运维人员 | OBJECT_NOT_FOUND |
| POST /admin/credential-pools/{poolId}/credentials | name、secret_source、secret_value 或 secret_ref、weight、rpm_limit、tpm_limit、concurrent_limit、enabled | ManagementOperationResult | 系统管理员 | SECRET_RESOLUTION_FAILED、FIELD_VALIDATION_FAILED |
| GET /admin/credentials/{id} | id | CredentialDetail | 系统管理员、运维人员 | OBJECT_NOT_FOUND；响应不含 secret_value 和 token_hash |
| PUT /admin/credentials/{id} | name、secret_ref、weight、rpm_limit、tpm_limit、concurrent_limit、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| POST /admin/credentials/{id}/rotate | secret_value、secret_value_confirm、version | ManagementOperationResult | 系统管理员 | SECRET_CONFIRM_MISMATCH、CONFIG_VERSION_CONFLICT |
| POST /admin/credentials/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、PROVIDER_AUTH_FAILED |
| POST /admin/credentials/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/credentials/{id}/disable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/credentials/{id} | version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE、CAPACITY_IN_USE |

#### 4.2.9.3 Provider Model 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/provider-models | keyword、provider_id、connection_status、support_stream、enabled、page、page_size、sort | PageResult<ProviderModelListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/provider-models | ProviderModel 可编辑字段 | ManagementOperationResult | 系统管理员 | OBJECT_REFERENCE_INVALID、FIELD_VALIDATION_FAILED |
| GET /admin/provider-models/{id} | id | ProviderModelDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/provider-models/{id} | ProviderModel 可编辑字段、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| GET /admin/providers/{id}/available-models | source、credential_id、keyword | array<ProviderModelImportCandidate> | 系统管理员 | MODEL_LIST_NOT_SUPPORTED、SECRET_RESOLUTION_FAILED |
| POST /admin/provider-models/import | ProviderModelImportCommand | ImportResult | 系统管理员 | FIELD_VALIDATION_FAILED、IMPORT_SOURCE_UNAVAILABLE |
| POST /admin/provider-models/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、TOTAL_TIMEOUT |
| POST /admin/provider-models/batch-check | provider_model_ids、credential_id、mode、timeout_ms | BatchCheckJob | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、CHECK_TARGET_INVALID |
| GET /admin/batch-check-jobs/{id} | id | BatchCheckJob 与 array<BatchCheckItem> | 系统管理员、运维人员 | OBJECT_NOT_FOUND |
| POST /admin/batch-check-jobs/{id}/cancel | id | BatchCheckJob | 系统管理员、运维人员 | JOB_ALREADY_FINISHED |
| POST /admin/provider-models/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/provider-models/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED |
| DELETE /admin/provider-models/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE |

#### 4.2.9.4 Model Alias 与候选接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/model-aliases | keyword、enabled、runtime_availability、support_stream、page、page_size、sort | PageResult<ModelAliasListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/model-aliases | alias、display_name、description、enabled | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED |
| GET /admin/model-aliases/{id} | id | ModelAliasDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/model-aliases/{id} | display_name、description、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| GET /admin/model-aliases/{id}/impact | id、operation | ImpactAnalysis | 系统管理员 | OBJECT_NOT_FOUND |
| POST /admin/model-aliases/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/model-aliases/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED |
| DELETE /admin/model-aliases/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE |
| GET /admin/model-aliases/{id}/candidates | id | array<RouteCandidateDetail> | 可查看角色 | OBJECT_NOT_FOUND |
| POST /admin/model-aliases/{id}/candidates | provider_model_id、credential_pool_id、priority、weight、enabled | ManagementOperationResult | 系统管理员 | OBJECT_REFERENCE_INVALID、DUPLICATE_ROUTE_CANDIDATE |
| PUT /admin/route-candidates/{id} | credential_pool_id、priority、weight、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_REFERENCE_INVALID |
| PUT /admin/model-aliases/{id}/candidates/reorder | items[].id、items[].priority、items[].version | array<RouteCandidateDetail> | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| POST /admin/route-candidates/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、CAPACITY_LIMITED |
| DELETE /admin/route-candidates/{id} | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |

#### 4.2.9.5 接口数据与事务规则

impact 接口返回 impact_version，值由当前引用关系摘要计算。停用和删除命令必须回传 confirmed_impact_version；引用关系变化导致版本不一致时返回 IMPACT_ANALYSIS_EXPIRED，页面重新展示影响内容，避免用户确认后对象关系已经改变。

创建、编辑、启停、删除、轮换和重排在单一数据库事务中完成配置草稿、version 和 AuditLog 写入。审计写入失败时业务变更回滚。模型导入以每个 ProviderModel 为独立结果，但同一成功对象的实体与审计仍保持原子性。批量检测任务不修改草稿，取消只阻止尚未开始的 BatchCheckItem，正在执行的 Provider 请求传播取消信号。

所有详情与列表响应在序列化前执行敏感字段过滤。Credential.secret_value、已解析 Secret、Standalone token_value、token_hash、Authorization 和自定义认证请求头不得进入 ManagementOperationResult、错误详情、导出或 AuditLog。

## 4.3 运行治理

运行治理按照“限流策略—可靠性策略—熔断状态—运行路由执行”展开。管理员先为 Alias、Provider Model 或 Credential 配置容量上限，再为 Alias 配置超时、重试、Fallback 和熔断参数；运维人员通过熔断状态页处理运行异常；路由执行章节定义这些页面配置在一次请求中的固定执行顺序。策略保存后进入草稿，熔断人工操作与运行探测即时生效并单独审计。

### 4.3.1 限流策略列表与编辑页

页面结构：列表按 scope_type、作用对象和启用状态筛选，显示 name、scope_type、对象名称、rpm_limit、tpm_limit、concurrent_limit、overflow_strategy、queue_timeout_ms、enabled、draft_changed 和操作。创建页按顺序填写名称、范围类型、范围对象、三个上限、溢出策略、排队超时和启用状态。

#### 4.3.1.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 LimitPolicy.name 和作用对象名称。 |
| 筛选区 | scope_type | 多选下拉 | LimitPolicy.scope_type | MODEL_ALIAS、PROVIDER_MODEL、CREDENTIAL。 |
| 筛选区 | scope_id | 远程搜索下拉 | 作用对象实体 | 选择 scope_type 后加载；查询时传对象 ID。 |
| 筛选区 | overflow_strategy | 单选下拉 | LimitPolicy.overflow_strategy | 全部、REJECT、QUEUE。 |
| 筛选区 | enabled | 单选下拉 | LimitPolicy.enabled | 全部、启用、停用。 |
| 列表 | name | 链接文本 | LimitPolicy.name | 点击进入编辑与运行使用抽屉。 |
| 列表 | scope_type | 文本 | LimitPolicy.scope_type | 显示范围中文名称和枚举值。 |
| 列表 | scope_name | 链接文本 | scope_id 关联实体 | 点击进入 Alias、模型或 Credential 详情。 |
| 列表 | rpm_limit | 数字 | LimitPolicy.rpm_limit | 空值显示“不限制”。 |
| 列表 | rpm_used | 数字与百分比 | LimitUsageSnapshot.rpm_used | 有上限时显示 used/limit 与百分比。 |
| 列表 | tpm_limit | 数字 | LimitPolicy.tpm_limit | 空值显示“不限制”。 |
| 列表 | tpm_used | 数字与百分比 | tpm_reserved + tpm_confirmed | 分开展示已确认和预占，百分比按总和计算。 |
| 列表 | concurrent_limit | 数字 | LimitPolicy.concurrent_limit | 空值显示“不限制”。 |
| 列表 | concurrency_used | 数字与百分比 | LimitUsageSnapshot.concurrency_used | 每 5 秒刷新。 |
| 列表 | overflow_strategy | 文本 | LimitPolicy.overflow_strategy | QUEUE 同时展示 queue_length 与 queue_max_size。 |
| 列表 | window_end | 倒计时 | LimitUsageSnapshot.window_end | 仅 RPM 或 TPM 有限制时显示。 |
| 列表 | counter_store_status | 状态文本 | LimitUsageSnapshot.counter_store_status | DEGRADED 或 UNAVAILABLE 时提供运行告警入口。 |
| 列表 | enabled | 状态文本 | LimitPolicy.enabled | 变更使用启停操作。 |
| 列表 | draft_changed | 变更状态 | LimitPolicy.draft_changed | true 显示待发布。 |
| 列表 | actions | 操作菜单 | 权限与对象状态 | 查看、编辑、启用、停用、删除、查看排队。 |

#### 4.3.1.2 表单字段

| 字段 | 控件形式 | 默认值 | 显示与编辑规则 | 校验 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 新建、编辑可见 | 2—64 字符，全局唯一。 |
| scope_type | 单选卡片 | MODEL_ALIAS | 创建后只读 | 只能为 MODEL_ALIAS、PROVIDER_MODEL、CREDENTIAL。 |
| scope_id | 远程搜索单选 | 空 | 创建后只读；选项随 scope_type 变化 | 必须指向未删除对象；同一对象最多一份启用策略。 |
| rpm_limit | 数字框 | 空 | 可编辑 | 空或正整数，最大 1000000000。 |
| tpm_limit | 数字框 | 空 | 可编辑 | 空或正整数，最大 long 安全范围。 |
| concurrent_limit | 数字框 | 空 | 可编辑 | 空或 1—100000。 |
| overflow_strategy | 单选 | REJECT | 可编辑 | REJECT 或 QUEUE。 |
| queue_timeout_ms | 数字框 | 5000 | 仅 QUEUE 时显示并必填 | 1—60000；运行时与当前 Trace 剩余 total_timeout_ms 取较小值。 |
| queue_max_size | 数字框 | 1000 | 仅 QUEUE 时显示并必填 | 1—100000。 |
| window_seconds | 只读数字 | 60 | V1.0 不可修改 | 固定分钟窗口。 |
| enabled | 开关 | true | 可编辑 | 至少一个 rpm_limit、tpm_limit、concurrent_limit 非空时才允许启用。 |
| version | 隐藏字段 | 空 | 更新时只读 | 更新、启停和删除时必填。 |

#### 4.3.1.3 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新建策略 | 系统管理员；作用对象不存在其他启用策略 | 创建 LimitPolicy 草稿与 AuditLog | 返回详情并显示待发布。 |
| 编辑策略 | 系统管理员；version 最新 | 更新限额、溢出参数和 version | 运行使用数据不清零，发布后使用新上限重新判断。 |
| 启用 | 系统管理员；至少配置一个上限 | enabled=true 草稿 | 发布校验对象引用和队列超时。 |
| 停用 | 系统管理员；当前 enabled=true | enabled=false 草稿 | 发布后新请求不再应用该策略，已有 QueueEntry 立即重新评估。 |
| 删除 | 系统管理员；策略未被活动快照引用或已先停用 | 删除草稿或记录删除变更 | 发布后删除；存在草稿冲突返回 OBJECT_IN_USE。 |
| 查看实时使用 | 系统管理员、运维人员、只读人员 | 无配置变化 | 展示 LimitUsageSnapshot 和等待队列摘要。 |
| 取消排队请求 | 不提供管理端操作 | 无 | V1.0 只允许调用方取消自身 Trace，避免管理员破坏请求顺序。 |

#### 4.3.1.4 容量计数与数据流

RPM 与 TPM 使用按 Unix 时间对齐的 60 秒固定窗口，window_start 等于 floor(epoch_seconds / 60) × 60。每次准备发起外部 Provider 请求时，系统同时收集 Alias、Provider Model 和 Credential 三层启用策略，并在共享容量存储中执行一次原子校验与预占；任一策略不足时，全部策略均不得产生部分计数。

RPM 每个实际外部 Attempt 预占 1。TPM 预占 estimated_input_tokens + effective_max_tokens，其中 effective_max_tokens 是请求值、模型默认值或 max_output_tokens 合并后的结果。Attempt 结束后使用 Provider 实际 Usage 结算；没有 Usage 时使用 TokenEstimator，差额从 tpm_reserved 转入或退回。Provider 已收到请求但响应失败时仍按实际或估算消耗结算，避免低估成本与容量。

并发在创建 Attempt 前增加，在成功、失败、超时和取消的统一清理阶段释放。CapacityReservation.expires_at 等于 Trace 总超时加 30 秒清理宽限，Watchdog 只释放超过 expires_at 且没有 RUNNING Attempt 的异常预占，并记录 WATCHDOG_EXPIRED 指标。

当所有候选均受容量限制且至少一个阻塞策略为 QUEUE 时，系统创建一个 QueueEntry，Trace 进入 QUEUED。队列按 alias_id 分区并按 sequence 先入先出；容量释放或窗口切换时，从队首重新执行完整候选、凭证和三层策略判断，队列不绑定首次不足的候选。队列长度达到所有阻塞策略中最小 queue_max_size 时返回 QUEUE_FULL。deadline_at 取 queue_timeout_ms 和 total_timeout_ms 剩余时间的较早值。

功能流程：选择 scope_type 后，对象选择器只加载对应实体。保存时检查同一对象是否已有启用策略。发布后，新请求同时受到 Alias、Provider Model 和 Credential 层策略约束，任一层无可用额度即视为当前路径容量不足。

计数规则：RPM 在 Attempt 准备调用 Provider 前预占，每次 Retry 和 Fallback 都分别计数；未实际发出外部请求的候选过滤不计 RPM。TPM 在调用前按输入估算 Token 加 max_tokens 预占，完成后以实际或最终估算 Token 结算差额。并发从 Attempt 开始前增加，在成功、失败、取消和超时的 finally 阶段归还。

溢出规则：REJECT 立即返回 CAPACITY_LIMITED 或继续寻找其他候选。QUEUE 只在至少存在一个未来可释放容量的候选时排队，采用先入先出；等待超过 queue_timeout_ms 或 total_timeout_ms 剩余时间时结束。客户端断开连接后取消队列等待。

完成标准：三类限额可以独立设置；并发不泄漏；分钟窗口复位准确；排队时间记录到 Trace.queued_ms；列表实时用量与运行计数一致。

接口：GET/POST /admin/limit-policies，GET/PUT/DELETE /admin/limit-policies/{id}，POST /admin/limit-policies/{id}/enable，POST /admin/limit-policies/{id}/disable。

### 4.3.2 可靠性策略列表与编辑页

页面结构：列表显示名称、Model Alias、总超时、最大重试次数、是否允许 Fallback、熔断窗口、失败率阈值、OPEN 时长、状态、草稿变更和操作。编辑页按调用时间、重试退避、Fallback 和 Circuit Breaker 四个区块连续展示，核心策略信息见 2.6.7，具体字段与默认值按本节表执行。

#### 4.3.2.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 ReliabilityPolicy.name 与 Alias。 |
| 筛选区 | alias_id | 远程搜索多选 | ModelAlias | 显示 alias 和 display_name。 |
| 筛选区 | fallback_enabled | 单选下拉 | ReliabilityPolicy.fallback_enabled | 全部、启用、关闭。 |
| 筛选区 | enabled | 单选下拉 | ReliabilityPolicy.enabled | 全部、启用、停用。 |
| 列表 | name | 链接文本 | ReliabilityPolicy.name | 点击进入编辑。 |
| 列表 | alias | 链接文本 | ModelAlias.alias | 点击进入 Alias 详情。 |
| 列表 | connect_timeout_ms | 毫秒 | ReliabilityPolicy.connect_timeout_ms | 大于 1000 时同时显示秒值。 |
| 列表 | first_token_timeout_ms | 毫秒 | ReliabilityPolicy.first_token_timeout_ms | 只影响流式请求。 |
| 列表 | total_timeout_ms | 毫秒 | ReliabilityPolicy.total_timeout_ms | 包含排队、退避和全部 Attempt。 |
| 列表 | max_retries | 数字 | ReliabilityPolicy.max_retries | 表示同候选瞬时错误重试次数。 |
| 列表 | max_credential_failovers | 数字 | ReliabilityPolicy.max_credential_failovers | 表示同候选换密钥次数。 |
| 列表 | max_fallbacks | 数字 | ReliabilityPolicy.max_fallbacks | fallback_enabled=false 时显示 0。 |
| 列表 | circuit_failure_rate | 百分比 | ReliabilityPolicy.circuit_failure_rate | 与 circuit_min_requests 组合展示。 |
| 列表 | circuit_open_seconds | 秒 | ReliabilityPolicy.circuit_open_seconds | 显示自动 OPEN 时长。 |
| 列表 | enabled | 状态文本 | ReliabilityPolicy.enabled | 停用后 Alias 使用内置默认策略。 |
| 列表 | draft_changed | 变更状态 | ReliabilityPolicy.draft_changed | true 显示待发布。 |
| 列表 | actions | 操作菜单 | 权限与状态 | 查看、编辑、启用、停用、删除。 |

#### 4.3.2.2 表单字段

| 区域 | 字段 | 控件形式 | 默认值 | 校验与业务规则 |
|---|---|---|---|---|
| 基础 | name | 单行文本 | 空 | 2—64 字符，全局唯一。 |
| 基础 | alias_id | 远程搜索单选 | 空 | 创建后只读；同一 Alias 最多一份启用策略。 |
| 超时 | connect_timeout_ms | 数字框 | 3000 | 100—60000。 |
| 超时 | first_token_timeout_ms | 数字框 | 30000 | 1000—300000，必须小于 total_timeout_ms。 |
| 超时 | total_timeout_ms | 数字框 | 120000 | 1000—600000，覆盖排队、退避和全部 Attempt。 |
| 重试 | max_retries | 数字框 | 1 | 0—5。 |
| 重试 | max_credential_failovers | 数字框 | 1 | 0—10。 |
| 重试 | initial_backoff_ms | 数字框 | 200 | 0—10000。 |
| 重试 | backoff_multiplier | 小数框 | 2.00 | 1.00—5.00。 |
| 重试 | jitter_percent | 数字框 | 20 | 0—100。 |
| 重试 | respect_retry_after | 开关 | true | 只处理 Provider 可解析的 Retry-After。 |
| 重试 | max_retry_after_ms | 数字框 | 5000 | 0—60000；respect_retry_after=false 时禁用。 |
| Fallback | fallback_enabled | 开关 | true | false 时 max_fallbacks 强制为 0。 |
| Fallback | max_fallbacks | 数字框 | 2 | 0—10；只统计切换 Route Candidate。 |
| 熔断 | circuit_window_seconds | 数字框 | 60 | 10—600。 |
| 熔断 | circuit_min_requests | 数字框 | 20 | 1—10000。 |
| 熔断 | circuit_failure_rate | 百分比框 | 50.00 | 1.00—100.00。 |
| 熔断 | circuit_open_seconds | 数字框 | 30 | 1—3600。 |
| 熔断 | circuit_half_open_probes | 数字框 | 3 | 1—100。 |
| 熔断 | circuit_half_open_successes | 数字框 | 2 | 1—circuit_half_open_probes。 |
| 状态 | enabled | 开关 | true | 停用后使用不可编辑的内置默认策略。 |
| 系统 | version | 隐藏字段 | 空 | 更新、启停和删除时提交。 |

#### 4.3.2.3 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新建策略 | 系统管理员；Alias 没有其他启用策略 | 创建 ReliabilityPolicy 草稿与 AuditLog | 返回详情并显示待发布。 |
| 编辑策略 | 系统管理员；version 最新 | 更新策略草稿和 version | 发布后只影响新建 Trace。 |
| 启用 | 系统管理员；参数组合有效 | enabled=true 草稿 | 发布校验 Alias 唯一策略。 |
| 停用 | 系统管理员；当前启用 | enabled=false 草稿 | 发布后 Alias 使用内置默认值，已有 Trace 继续使用原快照。 |
| 删除 | 系统管理员；当前策略已停用或只存在未发布草稿 | 删除草稿或记录删除变更 | 活动快照引用时要求先停用并发布。 |
| 查看默认策略 | 所有可查看角色 | 无 | 展示本节定义的默认值，不允许编辑。 |
| 查看近期恢复决策 | 系统管理员、运维人员 | 无 | 按 Alias 展示 RecoveryDecision 和关联 Attempt。 |

#### 4.3.2.4 尝试预算与数据流

Trace 创建时从 ConfigSnapshot 取得 Alias 的 ReliabilityPolicy；没有启用策略时装配内置默认值。策略在整个 Trace 生命周期内保持不变。理论外部请求上限为 1 + max_retries + max_credential_failovers + max_fallbacks，但每类动作只消耗自身预算，路由候选或 Credential 数量不足时不会补足预算。

RETRY 表示同一 Route Candidate 上因网络错误、连接超时、首 Token 超时或可重试 5xx 再次调用；运行时优先排除刚失败的网络连接，Credential 是否复用由错误类型决定。CREDENTIAL_FAILOVER 表示 Provider 429、鉴权失败或单密钥不可用后，在同一 Credential Pool 中更换 Credential，Attempt.attempt_type 记录为 CREDENTIAL_FAILOVER。FALLBACK 表示切换到另一个 Route Candidate，并受 fallback_enabled 与 max_fallbacks 约束。

每个失败 Attempt 完成 Token 结算与并发释放后生成 RecoveryDecision。决策器依次检查客户端是否取消、是否已经输出流式 delta、剩余 total_timeout_ms、错误分类、各类预算、熔断状态、候选与 Credential 可用性和容量。只有全部条件满足才执行下一次外部请求；下一次 Attempt 重新进行 RPM、TPM 与并发预占。

退避时间为 initial_backoff_ms × backoff_multiplier^retries_used，再按 jitter_percent 施加正负随机抖动。Provider 返回 Retry-After 且 respect_retry_after=true 时，取 Provider 等待值与 max_retry_after_ms 的较小值；等待后剩余时间不足完成最小连接预算时，跳过等待并尝试 Credential Failover 或 Fallback。所有等待都计入 total_timeout_ms。

功能流程：创建时选择尚未绑定启用策略的 Alias。保存执行范围和组合校验；发布后策略按 Trace 创建时取得的快照执行，运行中的 Trace 不切换策略。禁用策略后使用本节定义的系统默认可靠性策略。

重试规则：NETWORK_ERROR、CONNECT_TIMEOUT、FIRST_TOKEN_TIMEOUT 和标记可重试的 PROVIDER_SERVER_ERROR 允许 RETRY。PROVIDER_RATE_LIMITED 先执行 CREDENTIAL_FAILOVER 或 FALLBACK；没有替代路径且 Retry-After 在 max_retry_after_ms 与剩余总超时内时，才允许对原路径执行一次受 max_retries 约束的延迟 RETRY。请求参数错误、模型不存在、鉴权失败、内容拒绝和客户端取消不执行 RETRY。max_retries 只统计初始 Attempt 之后保持同一 Route Candidate 的瞬时错误重试。

流式规则：首个 SSE 业务块输出前可以重试或 Fallback。首块输出后若外部流中断，结束当前请求并发送包含 UnifiedError 的 SSE data，禁止拼接另一个模型的输出。first_token_timeout_ms 只作用于流式首 Token，total_timeout_ms 作用于排队、全部 Attempt、退避和流式输出全过程。

完成标准：错误分类与重试决策可从 Attempt 查看；全部 Attempt 总时长不超过策略总超时加允许的服务端清理时间；配置组合错误在发布前被拦截。

接口：GET/POST /admin/reliability-policies，GET/PUT/DELETE /admin/reliability-policies/{id}，POST /admin/reliability-policies/{id}/enable，POST /admin/reliability-policies/{id}/disable。

### 4.3.3 熔断状态页

页面结构：页面按 state、Provider、Provider Model 和 Credential 筛选，显示模型、Credential 脱敏名称、state、sample_count、failure_count、failure_rate、opened_at、next_probe_at、last_error_code 和操作。点击行打开详情，展示最近状态变化和计入统计的 Trace。

#### 4.3.3.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | state | 多选下拉 | CircuitState.state | CLOSED、OPEN、HALF_OPEN。 |
| 筛选区 | provider_id | 远程搜索多选 | Provider | 先筛 Provider，再联动模型与 Credential。 |
| 筛选区 | provider_model_id | 远程搜索多选 | ProviderModel | 显示 display_name 和 model_id。 |
| 筛选区 | credential_id | 远程搜索多选 | Credential | 仅系统管理员和运维人员展示，只显示 name 与 masked_value。 |
| 筛选区 | open_source | 单选下拉 | CircuitState.open_source | 全部、AUTO、MANUAL。 |
| 筛选区 | has_recent_failure | 开关 | CircuitState.last_error_code | 开启后只显示当前窗口有失败的状态。 |
| 列表 | provider_name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | provider_model_name | 链接文本 | ProviderModel.display_name | 点击进入模型详情。 |
| 列表 | credential_name | 文本 | Credential.name、masked_value | 仅系统管理员和运维人员展示；其他可查看角色显示“受限凭证”，响应不返回 credential_id。 |
| 列表 | state | 状态文本 | CircuitState.state | 明确显示 CLOSED、OPEN 或 HALF_OPEN。 |
| 列表 | open_source | 文本 | CircuitState.open_source | CLOSED 时显示空。 |
| 列表 | sample_count | 数字 | CircuitState.sample_count | 当前滚动窗口有效 Attempt 数。 |
| 列表 | failure_count | 数字 | CircuitState.failure_count | 当前窗口计入熔断的失败数。 |
| 列表 | failure_rate | 百分比 | CircuitState.failure_rate | sample_count 未达最小值时仍展示，但标记“样本不足”。 |
| 列表 | half_open_in_flight | 数字 | CircuitState.half_open_in_flight | 仅 HALF_OPEN 显示。 |
| 列表 | half_open_success_count | 数字 | CircuitState.half_open_success_count | 仅 HALF_OPEN 显示。 |
| 列表 | opened_at | 时间 | CircuitState.opened_at | CLOSED 时显示空。 |
| 列表 | next_probe_at | 时间与倒计时 | CircuitState.next_probe_at | OPEN 时展示。 |
| 列表 | last_error_code | 文本链接 | CircuitState.last_error_code | 点击筛选相关 Trace。 |
| 列表 | updated_at | 时间 | CircuitState.updated_at | 按系统时区展示。 |
| 列表 | actions | 操作菜单 | 权限、state、state_version | 人工打开、恢复、立即探测、查看详情。 |

#### 4.3.3.2 详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 当前状态 | 状态、窗口请求数、失败率、打开时间、下次探测时间、状态版本 | Circuit State | 每 5 秒刷新；刷新后人工操作必须使用最新状态版本。 |
| 生效阈值 | circuit_window_seconds、circuit_min_requests、circuit_failure_rate、circuit_open_seconds、circuit_half_open_probes、circuit_half_open_successes | Alias 的 ReliabilityPolicy | 同时显示 policy_id 和 snapshot_no。 |
| 状态事件 | 发生时间、变更前后状态、原因、触发来源 | Circuit Event | 按发生时间倒序，默认最近 50 条。 |
| 失败样本 | trace_id、attempt_id、ended_at、error_code、total_ms | Attempt | 只展示当前窗口计入熔断的 Attempt。 |
| 近期探测 | ProviderCheckRecord 或 HALF_OPEN_PROBE Attempt | 检测与调用记录 | 区分人工探测和业务流量探测。 |
| 人工信息 | open_source、manual_reason、manual_open_until、operator | CircuitState 与最近 CircuitEvent | 只在人工打开时展示。 |

#### 4.3.3.3 操作定义

| 操作 | 权限与前置条件 | 请求字段 | 状态与数据变化 | 结果与异常 |
|---|---|---|---|---|
| 人工打开 | 系统管理员、运维人员；state 非 OPEN 或允许延长人工 OPEN | action=MANUAL_OPEN、reason、open_seconds、state_version | state=OPEN，open_source=MANUAL，重算 next_probe_at，写 CircuitEvent 与 AuditLog | 版本变化返回 CIRCUIT_STATE_CONFLICT。 |
| 人工恢复 | 系统管理员、运维人员；state 为 OPEN 或 HALF_OPEN | action=MANUAL_RECOVER、reason、state_version | state=CLOSED，清空窗口与探测计数，写事件与审计 | 已 CLOSED 返回 CIRCUIT_STATE_CONFLICT。 |
| 立即探测 | 系统管理员、运维人员；state=OPEN 且没有进行中人工探测 | action=PROBE_NOW、state_version | 临时取得一个 HALF_OPEN 探测名额，执行最小调用并写检测记录 | Credential 不可用返回 CHECK_TARGET_INVALID。 |
| 查看失败 Trace | 可查看角色 | 时间范围、error_code | 无状态变化 | 跳转 Trace 列表。 |

#### 4.3.3.4 状态机与数据流

CLOSED 使用 circuit_window_seconds 的滚动窗口。每个计入熔断的 Attempt 结束后写入按秒分桶的成功或失败计数，计算时只汇总 now - window_seconds 之后的分桶。sample_count 达到 circuit_min_requests 且 failure_rate 大于等于 circuit_failure_rate 时，以原子状态更新进入 OPEN，设置 open_source=AUTO、opened_at=now、next_probe_at=now+circuit_open_seconds，并生成 CircuitEvent。

OPEN 状态不接收普通业务 Attempt。到达 next_probe_at 后，第一个取得探测名额的业务请求以 HALF_OPEN_PROBE 类型执行，其余请求继续绕过该模型与 Credential 组合。人工 PROBE_NOW 可以在时间未到时执行一个最小检测，但仍受探测并发限制。

HALF_OPEN 最多同时存在 circuit_half_open_probes 个探测。任一计入熔断的失败立即重新进入 OPEN并重置 next_probe_at；连续成功达到 circuit_half_open_successes 后进入 CLOSED并清空旧窗口。未达到成功数时保持 HALF_OPEN，释放探测名额后允许后续探测。

人工打开同样设置 next_probe_at，open_seconds 到期后按 HALF_OPEN 规则探测。人工恢复直接进入 CLOSED并清空统计。所有自动和人工迁移都使用 state_version 原子更新，并写 CircuitEvent；人工操作额外写 AuditLog。CircuitState 属于运行状态，不进入配置草稿，也不需要发布。

功能流程：CLOSED 状态按策略窗口统计有效 Attempt。达到 circuit_min_requests 且失败率达到阈值时进入 OPEN。到达 next_probe_at 后，第一个获得探测名额的请求使状态进入 HALF_OPEN；成功探测达到配置数量后恢复 CLOSED，任一探测失败则重新 OPEN。人工打开和恢复立即改变运行状态，不需要配置发布，并写入 AuditLog。

规则与异常：计入失败率的错误包括 Provider 网络错误、连接超时、首 Token 超时、Provider 服务错误、无法解析的响应和首个 SSE 业务块输出后的流式中断。Provider 429、鉴权失败、模型不存在、Secret 解析失败、业务参数错误、内容拒绝、客户端取消、本系统限流与排队超时均不计入失败率。人工恢复清空当前窗口统计。人工打开必须填写原因和预计恢复时间；未填写预计时间时使用策略 open_seconds。

完成标准：OPEN 候选不接收常规请求；HALF_OPEN 并发不超过 half_open_probes；状态变化在页面和指标系统中及时可见；人工操作可追溯。

接口：GET /admin/circuits，GET /admin/circuits/{id}，POST /admin/circuits/{id}/open，POST /admin/circuits/{id}/recover，POST /admin/circuits/{id}/probe。

### 4.3.4 运行路由执行

运行路由没有独立编辑页面，由 Model Alias、Route Candidate、Credential Pool、Limit Policy 和 Reliability Policy 的已发布快照共同驱动。每个 Trace 固定使用创建时的 snapshot_no，保证一次调用内规则一致。

执行顺序固定为请求校验、候选能力过滤、候选启停与引用校验、熔断过滤、容量预判、优先级与权重选择、Credential 选择、容量预占、Attempt 创建、Provider 调用、结果结算。任何阶段失败都写入可解释的 route decision；未产生外部请求的过滤结果可以保存原因，但不创建 Attempt。

当同一优先级存在多个候选时，权重只在当前可用集合内重新归一化。候选因容量不足被过滤后可以继续选择其他候选；所有候选均容量不足且存在 QUEUE 策略时进入队列。路由结果必须支持通过 Trace 详情重现当时的候选顺序、过滤原因和实际选择。

#### 4.3.4.1 路由输入与输出

| 数据 | 字段 | 来源 | 用途 |
|---|---|---|---|
| RouteExecutionContext | trace_id、snapshot_no、alias_id | Trace 与 ConfigSnapshot | 固定一次调用的追踪和配置版本。 |
| RouteExecutionContext | stream、has_system_message | UnifiedChatRequest | 过滤不支持流式或 system 的模型。 |
| RouteExecutionContext | estimated_input_tokens、effective_max_tokens | TokenEstimator 与参数合并 | 上下文校验和 TPM 预占。 |
| RouteExecutionContext | deadline_at | ReliabilityPolicy.total_timeout_ms | 约束排队、退避和全部 Attempt。 |
| RouteExecutionContext | 三类已使用预算 | RecoveryDecision 聚合 | 防止重试、换密钥和 Fallback 超限。 |
| RouteExecutionContext | excluded_candidate_ids、excluded_credential_ids | 前序失败结果 | 避免在同一 Trace 中重复选择已确认无效对象。 |
| RouteExecutionResult | route_candidate_id、credential_id | 路由与凭证选择 | 创建 Attempt 并调用 Adapter。 |
| RouteExecutionResult | capacity_reservation_id | 容量组件 | 结算 Token 和释放并发。 |
| RouteExecutionResult | attempt_type、decision_sequence | 恢复动作与 RouteDecision | 解释本次 Attempt 的来源。 |

#### 4.3.4.2 执行阶段

| 顺序 | 阶段 | 处理规则 | 产生数据 | 终止错误 |
|---:|---|---|---|---|
| 1 | 请求与身份校验 | 校验访问凭证、IP、Alias、消息和基础参数 | Trace、RouteExecutionContext | ACCESS_TOKEN_INVALID、ACCESS_IP_DENIED、FIELD_VALIDATION_FAILED |
| 2 | 配置快照装配 | 获取 Alias、候选、模型、凭证池和治理策略 | snapshot_no、候选初始集合 | MODEL_ALIAS_NOT_FOUND、MODEL_ALIAS_DISABLED |
| 3 | 静态候选过滤 | 排除停用、引用无效、Adapter 未加载的候选 | FILTERED RouteDecision | OBJECT_REFERENCE_INVALID |
| 4 | 能力与上下文过滤 | 校验 stream、system、参数范围和 context_window | FILTERED RouteDecision、estimated_input_tokens | MODEL_CAPABILITY_NOT_SUPPORTED、CONTEXT_WINDOW_EXCEEDED |
| 5 | 熔断过滤 | 排除 OPEN，控制 HALF_OPEN 探测名额 | FILTERED 或 SELECTED_FOR_PROBE RouteDecision | CIRCUIT_OPEN |
| 6 | 优先级与权重排序 | priority 从小到大；同级按 weight 无放回生成尝试顺序 | 候选有序集合 | ALL_CANDIDATES_FAILED |
| 7 | Credential 选择 | 按池策略过滤健康、限额、并发和本 Trace 排除项 | credential_id、RouteDecision | CREDENTIAL_NOT_AVAILABLE |
| 8 | 原子容量预占 | 同时校验 Alias、模型、Credential 三层策略并预占 | CapacityReservation | CAPACITY_LIMITED、QUEUE_FULL、CAPACITY_STATE_UNAVAILABLE |
| 9 | 排队 | 仅所有候选容量不足且存在 QUEUE 时创建 FIFO 条目 | QueueEntry、Trace.status=QUEUED | QUEUE_TIMEOUT、CLIENT_CANCELLED |
| 10 | Attempt 执行 | 创建 Attempt，Adapter 转换并调用 Provider | Attempt、Provider Request ID、流式事件 | Provider 分类错误 |
| 11 | 结算与健康更新 | 结算 Token、费用、容量、Credential 健康和 CircuitState | Usage、Cost、CircuitEvent | INTERNAL_ERROR |
| 12 | 恢复决策 | 根据错误矩阵和预算生成下一动作 | RecoveryDecision | ALL_CANDIDATES_FAILED 或原始最终错误 |

同一优先级的加权选择按当前可用候选重新归一化，每轮生成无放回顺序，单个候选在同一选择轮中只出现一次。静态过滤和容量过滤不会消耗 max_fallbacks；只有某个候选已经产生失败 Attempt，随后切换到另一个 Route Candidate 时才增加 fallbacks_used。

#### 4.3.4.3 错误分类与恢复决策矩阵

| 错误或状态 | Credential/模型状态变化 | RETRY | CREDENTIAL_FAILOVER | FALLBACK | 计入熔断 | 最终处理 |
|---|---|---|---|---|---|---|
| 请求字段或上下文错误 | 无 | 否 | 否 | 否 | 否 | 直接返回 400 或 422。 |
| PROVIDER_AUTH_FAILED | Credential=INVALID | 否 | 有其他 Credential 时执行 | 允许 | 否 | 无替代路径时返回鉴权失败。 |
| SECRET_RESOLUTION_FAILED | Credential=UNAVAILABLE | 否 | 有其他 Credential 时执行 | 允许 | 否 | 外部 Secret 全部失败时返回 502。 |
| PROVIDER_RATE_LIMITED | Credential=RATE_LIMITED，并记录 reset_at | 仅无替代路径且 Retry-After 在预算内时延迟执行 | 优先执行 | 允许 | 否 | 无恢复路径时返回 503 与 retry_after_ms。 |
| PROVIDER_MODEL_NOT_FOUND | ProviderModel=UNAVAILABLE，记录检测错误 | 否 | 否 | 允许 | 否 | 无其他候选时返回 502。 |
| PROVIDER_REQUEST_REJECTED | 保持配置状态并触发参数转换告警 | 否 | 否 | 否 | 否 | 返回 502，保留脱敏 Provider Request ID。 |
| NETWORK_ERROR | 保持当前健康状态，连续失败由熔断处理 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 503。 |
| CONNECT_TIMEOUT | 保持当前健康状态 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 504。 |
| FIRST_TOKEN_TIMEOUT | 保持当前健康状态 | 尚未输出 delta 时允许 | 重试耗尽后可换密钥 | 尚未输出 delta 时允许 | 是 | 已输出内容时按 STREAM_INTERRUPTED。 |
| PROVIDER_SERVER_ERROR | 保持当前健康状态 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 503。 |
| PROVIDER_BAD_RESPONSE | 保持当前健康状态 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 502。 |
| CONTENT_FILTER | 无 | 否 | 否 | 否 | 否 | 作为正常 finish_reason 返回。 |
| STREAM_INTERRUPTED | 保持当前健康状态 | 否 | 否 | 否 | 是 | 发送流式 UnifiedError 并结束 Trace。 |
| TOTAL_TIMEOUT | 无 | 否 | 否 | 否 | 否 | 立即取消进行中请求并返回 504。 |
| CLIENT_CANCELLED | 无 | 否 | 否 | 否 | 否 | 传播取消，Trace=CANCELLED。 |
| CAPACITY_LIMITED | 无 | 否 | 尝试其他 Credential | 尝试其他候选 | 否 | 存在 QUEUE 时排队，否则返回 429。 |

Provider 429 表示当前 Credential 或上游账户容量受限，优先通过多密钥与其他候选恢复，不进入 CircuitState 失败率。Provider 鉴权失败只影响实际使用的 Credential。模型不存在属于 Provider Model 配置或可用性问题，运行时排除该模型并触发运维告警，不使用熔断掩盖配置错误。

#### 4.3.4.4 恢复动作顺序

失败后先完成当前 Attempt 的 Usage、Cost 和 CapacityReservation 结算，再进行下一步。错误明确绑定 Credential 时，优先 CREDENTIAL_FAILOVER；错误属于瞬时网络或 Provider 服务错误时，在 max_retries 内 RETRY；当前候选重试或换密钥预算耗尽后，在 max_fallbacks 内 FALLBACK。任何动作执行前都重新检查 deadline_at、客户端连接、候选状态、Credential 健康、CircuitState 和容量。

RETRY 保持 route_candidate_id，正常情况下保持 credential_id；错误导致 Credential 状态变化时改用 CREDENTIAL_FAILOVER。CREDENTIAL_FAILOVER 保持 route_candidate_id 并更换 credential_id。FALLBACK 必须更换 route_candidate_id，并根据新候选的 Credential Pool 重新选择凭证。每个动作生成 RecoveryDecision，下一次实际调用生成新的 Attempt 和 CapacityReservation。

#### 4.3.4.5 流式边界与追踪

流式请求在第一个 SSE 业务块发给客户端前可以执行所有允许的恢复动作。首块输出后设置 response_committed=true；后续连接中断、解析错误或超时只允许结束当前流，禁止把另一个 Provider 的输出拼接到已经返回的内容。系统发送包含 UnifiedError 的 SSE data，Trace.status=STREAM_INTERRUPTED，并释放容量。

Trace 详情需要按时间顺序合并 RouteDecision、QueueEntry、CapacityReservation、Attempt、RecoveryDecision 和 CircuitEvent。开发人员可以从一条失败 Trace 看到候选为什么被过滤、是否排队、选择了哪个 Credential、消耗了哪类恢复预算、等待了多久以及最终错误从何产生。

### 4.3.5 运行治理管理接口契约

#### 4.3.5.1 限流策略接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/limit-policies | keyword、scope_type、scope_id、overflow_action、enabled、draft_changed、page、page_size、sort | PageResult<LimitPolicyListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/limit-policies | LimitPolicy 可编辑字段 | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、LIMIT_POLICY_CONFLICT、OBJECT_REFERENCE_INVALID |
| GET /admin/limit-policies/{id} | id | LimitPolicyDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/limit-policies/{id} | LimitPolicy 可编辑字段、version | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_VERSION_CONFLICT、LIMIT_POLICY_CONFLICT |
| POST /admin/limit-policies/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、LIMIT_POLICY_CONFLICT |
| POST /admin/limit-policies/{id}/disable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/limit-policies/{id} | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_IN_USE |
| GET /admin/limit-policies/{id}/usage | id | LimitUsageSnapshot | 系统管理员、运维人员 | OBJECT_NOT_FOUND、CAPACITY_STATE_UNAVAILABLE |
| GET /admin/limit-policies/{id}/queue | status、started_from、started_to、page、page_size | PageResult<QueueEntry> | 系统管理员、运维人员 | OBJECT_NOT_FOUND、FIELD_VALIDATION_FAILED |

限流列表在策略主要信息之外展示作用对象、当前 RPM、TPM 预占与结算、并发数、队列长度和容量存储状态。详情页继续展示当前窗口用量、等待数量、活动配置版本和可执行操作。用量查询只读取当前窗口与并发状态，不创建业务计数；分布式计数存储不可用时返回 CAPACITY_STATE_UNAVAILABLE，页面保留上次成功数据并标明更新时间。

队列接口只提供运行排查所需的只读分页数据。V1.0 不提供从管理页面删除单个 QueueEntry 的操作；业务客户端断开、等待超时、容量取得和运行实例关闭由运行时按状态机结束排队，避免人工删除造成 Trace 与容量状态不一致。

#### 4.3.5.2 可靠性策略接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/reliability-policies | keyword、alias_id、fallback_enabled、enabled、draft_changed、page、page_size、sort | PageResult<ReliabilityPolicyListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/reliability-policies | ReliabilityPolicy 可编辑字段 | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、RELIABILITY_POLICY_CONFLICT、OBJECT_REFERENCE_INVALID |
| GET /admin/reliability-policies/default | 无 | ReliabilityPolicyDefault | 可查看角色 | 无 |
| GET /admin/reliability-policies/{id} | id | ReliabilityPolicyDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/reliability-policies/{id} | ReliabilityPolicy 可编辑字段、version | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_VERSION_CONFLICT、RELIABILITY_POLICY_CONFLICT |
| POST /admin/reliability-policies/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、RELIABILITY_POLICY_CONFLICT |
| POST /admin/reliability-policies/{id}/disable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/reliability-policies/{id} | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_IN_USE |
| GET /admin/reliability-policies/{id}/recovery-decisions | trace_id、action、reason_code、started_from、started_to、page、page_size | PageResult<RecoveryDecision> | 系统管理员、运维人员 | OBJECT_NOT_FOUND、FIELD_VALIDATION_FAILED |

可靠性策略列表展示策略主要信息、关联 Alias、活动配置版本和可执行操作。详情页增加最近发布时间以及近一小时按重试、Fallback、终止聚合的恢复决策数量。系统默认策略使用与新建表单相同的业务配置，页面标识来源为 SYSTEM_DEFAULT，且不允许直接编辑。

恢复决策查询只返回 RecoveryDecision 与来源 Attempt 的 id、sequence、attempt_type、error_code、started_at、ended_at，不返回请求消息、响应正文和 Provider 原始错误正文。页面从决策记录跳转 Trace 详情时，以 trace_id 定位完整时间线。

#### 4.3.5.3 熔断状态接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/circuits | state、provider_id、provider_model_id、credential_id、open_source、has_recent_failure、page、page_size、sort | PageResult<CircuitStateListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| GET /admin/circuits/{id} | id | CircuitStateDetail | 可查看角色 | OBJECT_NOT_FOUND |
| GET /admin/circuits/{id}/events | trigger_type、started_from、started_to、page、page_size | PageResult<CircuitEvent> | 可查看角色 | OBJECT_NOT_FOUND、FIELD_VALIDATION_FAILED |
| POST /admin/circuits/{id}/open | action=MANUAL_OPEN、reason、open_seconds、state_version | CircuitStateDetail | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、CIRCUIT_STATE_CONFLICT |
| POST /admin/circuits/{id}/recover | action=MANUAL_RECOVER、reason、state_version | CircuitStateDetail | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、CIRCUIT_STATE_CONFLICT |
| POST /admin/circuits/{id}/probe | action=PROBE_NOW、state_version、timeout_ms | ProviderCheckRecord | 系统管理员、运维人员 | CIRCUIT_STATE_CONFLICT、CHECK_TARGET_INVALID、CAPACITY_LIMITED |

熔断列表展示状态主要信息，并补充 Provider、Provider Model、Credential 掩码、策略、活动配置版本和可执行操作。详情页展示关联对象的非敏感摘要、生效阈值、最近 50 条状态事件、当前窗口失败样本摘要和可执行操作。

人工 open 与 recover 接口必须提交页面最后读取的 state_version。服务端以 compare-and-set 更新 CircuitState；状态或版本已经改变时返回 CIRCUIT_STATE_CONFLICT 和 current_state_version，页面刷新详情后由操作者重新确认。probe 接口取得一个探测名额后生成 ProviderCheckRecord，target_type=CIRCUIT_STATE；实际外部调用同时生成 HALF_OPEN_PROBE Attempt，检测成功与失败均按 4.3.3.4 更新状态。

#### 4.3.5.4 写入、发布与一致性规则

LimitPolicy 和 ReliabilityPolicy 属于配置实体。新建、编辑、启用、停用和删除在同一数据库事务内写入草稿、递增 version、计算 draft_changed 并生成 AuditLog；运行时继续使用活动 ConfigSnapshot。配置发布成功后，新建 Trace 读取新策略，已经创建的 Trace 继续使用原 snapshot_no 和策略值。

同一 scope_type 与 scope_id 最多存在一条启用的 LimitPolicy；同一 alias_id 最多存在一条启用的 ReliabilityPolicy。唯一约束在保存和发布两个阶段执行。保存阶段发生冲突时返回对应策略冲突错误并给出 conflicting_policy_id；发布校验发现引用状态变化时生成 LIMIT_POLICY_INVALID 或 RELIABILITY_POLICY_INVALID 问题，ConfigValidationResult.status=FAILED，活动快照保持不变。

CircuitState、CircuitEvent 和 CircuitCommand 属于运行态数据，不进入配置草稿。人工状态操作在一个原子事务或等价原子写中更新 CircuitState、追加 CircuitEvent 并生成 AuditLog；任一步失败都不提交状态变化。自动熔断迁移追加 CircuitEvent，不生成管理员 AuditLog，运行指标记录触发原因。

容量预占、分钟窗口计数、队列取得和熔断探测名额必须使用支持原子比较与增减的共享状态存储。Standalone 单实例可以使用进程内实现；集群模式使用共享 Redis 实现。共享状态存储不可用时，系统停止创建新的 CapacityReservation，并返回 CAPACITY_STATE_UNAVAILABLE；已经运行的 Attempt 继续完成本地清理和 Trace 记录，防止绕过全局限额。

#### 4.3.5.5 分页、排序与敏感字段规则

运行治理列表统一使用 page 从 1 开始、page_size 默认 20 且范围 1—100。未指定 sort 时，策略列表按 updated_at desc，熔断列表按 state 优先级 OPEN、HALF_OPEN、CLOSED 后再按 updated_at desc，队列按 sequence asc，事件和恢复决策按 created_at desc。非法字段、超出范围的分页参数或不支持的排序字段返回 FIELD_VALIDATION_FAILED。

系统管理员和运维人员取得的 Credential 字段只包含 name 和 masked_value；开发人员与只读人员的 circuit、limit usage、queue 和 recovery 响应移除 credential_id、credential_name 与 masked_value。所有响应均不得包含 secret_value、完整 secret_ref、Authorization、业务消息正文和 Provider 原始响应正文。错误摘要沿用统一脱敏规则，并始终提供 trace_id、attempt_id 或操作审计 id 中至少一个可追踪标识。

## 4.4 调用观测

调用观测按照“Trace 列表检索—Trace 详情定位—Usage 与 Cost 汇总”的页面顺序组织。用户可以从概览或 Usage 数据点带入筛选进入 Trace 列表，再从单个 Trace 查看路由、Attempt、恢复动作和时间线；需要分析整体消耗时进入 Usage 与 Cost，并可在明细保留期内钻取回 Trace。

### 4.4.1 Trace 列表页

调用观测沿用同类网关按时间、模型、渠道和调用凭证查询日志，并在顶部展示统计汇总和消耗趋势的使用方式。轻享 AI 在此基础上增加 Trace、Attempt、路由判定、恢复动作和费用快照，保证一次业务调用可以完整追溯。参考依据为 [New API 日志与统计](https://docs.newapi.pro/zh/docs/guide/feature-guide/admin/log) 和 [New API 日志管理接口](https://github.com/QuantumNous/new-api-docs/blob/main/docs/en/api/fei-log.md)。

#### 4.4.1.1 页面进入与布局

用户从左侧“调用观测—Trace”进入列表。页面从上到下依次为基础筛选区、高级筛选折叠区、查询操作区、结果表格和分页区。首次进入默认查询当前页面时区最近 1 小时的数据，状态与其他条件为空，按 started_at desc 排序，每页 20 条。页面 URL 保存非敏感查询参数，刷新或从详情返回时恢复原筛选、页码和排序。

基础筛选区放置时间范围、trace_id、application、Model Alias、Provider、Provider Model 和 status。高级筛选区放置 project、tenant、标签、source_mode、Standalone Access Credential、request_user、client_ip、Provider Credential、Attempt 类型、错误、流式、Usage 来源、恢复动作和耗时范围。两类 Credential 选项与 client_ip 只向系统管理员和运维人员开放；只读人员可以看到 Provider 和模型，无法取得 Credential ID 和来源 IP。

#### 4.4.1.2 查询字段

| 顺序 | 字段 | 控件形式 | 默认值 | 查询与校验规则 |
|---:|---|---|---|---|
| 1 | start_at、end_at | 日期时间范围 | 最近 1 小时 | 按 RuntimeConfig.timezone 解释，左闭右开；普通组合查询必填，跨度最大 31 天。 |
| 2 | trace_id | 单行文本 | 空 | 精确匹配，1—128 字符；填写后停用时间以外的业务筛选并忽略分页，仍执行权限校验。 |
| 3 | application | 可输入多选 | 空 | 精确匹配 Trace.application，最多 20 项。 |
| 4 | alias_id | 远程搜索多选 | 空 | 显示 Alias 当前名称；查询使用 ID，历史结果显示 Trace.alias 快照。 |
| 5 | provider_id | 远程搜索多选 | 空 | 匹配 Trace.final_provider_id，最多 20 项。 |
| 6 | provider_model_id | 联动远程搜索多选 | 空 | Provider 已选择时只加载其模型；匹配 final_provider_model_id。 |
| 7 | status | 多选下拉 | 空 | QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED、STREAM_INTERRUPTED。 |
| 8 | project | 可输入多选 | 空 | 精确匹配 Trace.project，最多 20 项。 |
| 9 | tenant | 可输入多选 | 空 | 精确匹配 Trace.tenant，最多 20 项。 |
| 10 | tag_key | 单行文本 | 空 | 与 tag_value 成对使用，键最长 64 字符。 |
| 11 | tag_value | 单行文本 | 空 | 对指定 tag_key 精确匹配，值最长 256 字符。 |
| 12 | source_mode | 多选下拉 | 空 | SDK、EMBEDDED、STANDALONE。 |
| 13 | access_credential_id | 远程搜索多选 | 空 | 匹配 Standalone Access Credential；系统管理员和运维人员可用。 |
| 14 | request_user | 单行文本 | 空 | 精确匹配非敏感业务用户标识，最长 128 字符。 |
| 15 | client_ip | 单行文本 | 空 | 仅敏感诊断权限可用；支持单 IP 精确匹配，未开启 IP 记录时停用。 |
| 16 | credential_id | 远程搜索多选 | 空 | 受控字段，匹配 Trace.final_credential_id；选项显示 name 和 masked_value。 |
| 17 | attempt_type | 多选下拉 | 空 | 查询存在指定 Attempt 类型的 Trace。 |
| 18 | error_code | 可输入多选 | 空 | 精确匹配 Trace.error_code，最多 20 项。 |
| 19 | requested_stream | 单选下拉 | 全部 | 全部、流式、同步。 |
| 20 | usage_source | 多选下拉 | 空 | ACTUAL、ESTIMATED、MIXED。 |
| 21 | has_retry | 三态开关 | 全部 | true 匹配 retry_count>0，false 匹配 retry_count=0。 |
| 22 | has_credential_failover | 三态开关 | 全部 | 按 credential_failover_count 是否大于 0 查询。 |
| 23 | has_fallback | 三态开关 | 全部 | 按 fallback_count 是否大于 0 查询。 |
| 24 | min_total_ms | 非负整数 | 空 | 与 max_total_ms 组合过滤已结束 Trace；范围 0—600000。 |
| 25 | max_total_ms | 非负整数 | 空 | 必须大于等于 min_total_ms，最大 600000。 |
| 26 | anomalous_running | 三态开关 | 全部 | true 仅查询超过策略总超时加清理宽限的运行中 Trace。 |
| 27 | sort | 单选下拉 | started_at desc | 支持 started_at、total_ms、total_tokens、total_cost；trace_id 精确查询时忽略。 |
| 28 | page、page_size | 分页控件 | 1、20 | page 从 1 开始；page_size 支持 20、50、100。 |

#### 4.4.1.3 列表字段

| 顺序 | 列字段 | 数据来源 | 展示与交互规则 |
|---:|---|---|---|
| 1 | started_at | TraceListItem.started_at | 按页面时区展示到毫秒。 |
| 2 | trace_id | TraceListItem.trace_id | 等宽字体，点击进入详情，复制操作不进入详情。 |
| 3 | source_mode | TraceListItem.source_mode、access_credential_name | 显示产品形态；Standalone 在同一单元格展示访问凭证名称。 |
| 4 | application | TraceListItem.application | project 或 tenant 存在时在同一单元格分行展示。 |
| 5 | alias | TraceListItem.alias | 展示历史快照名称；关联对象存在时可进入 Alias 详情。 |
| 6 | final_provider_name | TraceListItem.final_provider_name | 未创建 Attempt 时显示空。 |
| 7 | final_provider_model_name | TraceListItem.final_provider_model_name | 点击进入 Provider Model 详情；对象已删除时只展示快照。 |
| 8 | requested_stream | TraceListItem.requested_stream | 显示“流式”或“同步”。 |
| 9 | status | TraceListItem.status | 使用状态文本；anomalous_running=true 时显示“运行异常”。 |
| 10 | attempt_count | TraceListItem.attempt_count | 大于 1 时可展开显示 retry、credential failover、fallback 数量。 |
| 11 | queued_ms | TraceListItem.queued_ms | 无排队显示空；有值显示毫秒和换算后的秒值。 |
| 12 | first_token_ms | TraceListItem.first_token_ms | 仅流式且已输出首 Token 时显示。 |
| 13 | total_ms | TraceListItem.total_ms | 运行中使用临时计算值并明确标识；最终值只取持久化字段。 |
| 14 | total_tokens | TraceListItem.total_tokens | 展示全部 Attempt Token，usage_source 同时显示。 |
| 15 | total_cost | TraceListItem.total_cost、currency | 固定八位小数；无计费用量时显示空。 |
| 16 | error_code | TraceListItem.error_code | 点击后把当前错误码加入筛选；成功时为空。 |
| 17 | actions | TraceListItem.actions | 查看详情、复制 Trace ID、查看 Alias、查看模型、筛选同类错误。 |

#### 4.4.1.4 操作定义

| 操作 | 权限与前置条件 | 系统处理 | 页面结果与异常 |
|---|---|---|---|
| 查询 | 可查看角色；字段校验通过 | 按统一查询对象读取 Trace 列表和 total | 回到第 1 页；失败保留输入并定位错误字段。 |
| 重置 | 可查看角色 | 清除筛选并恢复最近 1 小时 | 立即重新查询。 |
| 手动刷新 | 可查看角色 | 保持条件和页码重新查询 | 更新 RUNNING、QUEUED 和 anomalous_running。 |
| 自动刷新 | 可查看角色；当前结果存在运行中 Trace | 按 dashboard_refresh_seconds 轮询当前页 | 离开页面、浏览器隐藏或无运行中数据时停止。 |
| 查看详情 | 可查看角色；Trace 存在 | 查询完整 TraceDetail | 在当前列表路由下进入详情，返回时恢复列表状态。 |
| 复制 Trace ID | 可查看角色 | 复制 trace_id | 成功后短暂显示复制结果，不写审计。 |
| 导出当前结果 | 系统管理员、运维人员；必须填写时间范围 | 使用与列表一致的筛选生成 CSV | 超出条数限制返回 EXPORT_TOO_LARGE，页面要求缩小范围。 |

#### 4.4.1.5 查询与数据流

普通组合查询先按 started_at、权限数据范围和筛选条件定位 Trace，再通过 final_attempt_id 读取最终 Provider、模型和 Credential 快照。Attempt 类型筛选使用存在性查询，不改变列表一行一个 Trace 的结构。服务端必须在一次分页查询或批量装配中返回 TraceListItem，禁止逐行查询 Attempt。

trace_id 精确查询不要求时间范围，可以在 trace_retention_days 内直接定位；仍需验证当前角色的数据范围。找不到时返回空列表，不泄露该 ID 是否曾由其他应用产生。RUNNING 和 QUEUED 的 total_ms 只在响应层按当前时间计算，不回写数据库；达到最终状态后使用 ended_at-started_at 固化。

列表 total 表示满足筛选的 Trace 数量。排序只允许白名单字段，total_cost 排序要求查询指定单一 currency；未指定币种且结果包含多个币种时，页面停用费用排序。查询条件、列表 total 和导出必须复用同一查询规范。

完成标准：精确 trace_id 一次查询定位；组合筛选、分页和排序结果稳定；Attempt 存在性筛选不产生重复 Trace；列表、概览钻取和导出使用相同数据范围；页面与接口不出现 Credential 原文、Token、Authorization 和消息正文。

### 4.4.2 Trace 详情页

#### 4.4.2.1 页面顺序与加载

详情页按照排查问题的实际阅读顺序展示 Trace 摘要、请求摘要、统一时间线、Attempt 明细、Usage 与 Cost、最终错误六个区域。页面顶部保留“返回列表”和“复制 Trace ID”。Trace 处于 QUEUED 或 RUNNING 时，每 5 秒刷新详情；进入最终状态后停止刷新。刷新保持已展开的 Attempt 和当前滚动位置。

详情接口一次返回 TraceDetail。服务端先读取 Trace，再按 trace_id 批量读取 RouteDecision、QueueEntry、CapacityReservation、Attempt、RecoveryDecision 和 CircuitEvent，最后装配 Timeline、UsageCostSummary 和 ErrorDetail。任何下级记录读取失败都返回 OBSERVATION_DATA_UNAVAILABLE，页面不展示可能互相矛盾的局部详情。

#### 4.4.2.2 区域字段

| 页面区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| Trace 摘要 | trace_id、status、started_at、ended_at、total_ms | Trace | status 与最终时间放在首行；运行中 total_ms 动态计算。 |
| Trace 摘要 | application、project、tenant、tags | Trace | tags 按键排序；敏感键在写入阶段已经拒绝。 |
| Trace 摘要 | alias、config_snapshot_no、source_mode | Trace | Alias 使用快照名称，snapshot_no 可复制。 |
| Trace 摘要 | requested_stream、response_committed、finish_reason | Trace | finish_reason 只在成功时展示。 |
| Trace 摘要 | attempt_count、retry_count、credential_failover_count、fallback_count、queued_ms、first_token_ms | Trace | 作为本次调用执行概况。 |
| 请求摘要 | source_mode、access_credential_name、request_user、client_ip、user_agent、config_snapshot_no | TraceRequestSummary | access credential 只显示名称；client_ip 仅敏感诊断权限可见。 |
| 请求摘要 | message_count、system_message_count、user_message_count、assistant_message_count、input_char_count | TraceRequestSummary | 只展示数量。 |
| 请求摘要 | requested_stream、temperature、top_p、max_tokens、stop_count、provider_option_keys | TraceRequestSummary | 展示运行时解析后的实际值。 |
| 请求摘要 | content_sample_status、sampled_messages | TraceRequestSummary | 默认 DISABLED；只有敏感诊断权限与 AVAILABLE 同时满足时展示脱敏样本。 |
| 路由判定 | sequence、route_candidate_id、decision、reason_code、reason_detail、observed_status、created_at | RouteDecision | 按 sequence 展示候选过滤、选择、排队和失败原因。 |
| 排队记录 | blocking_policy_ids、estimated_tokens、sequence、status、enqueued_at、deadline_at、acquired_at、ended_at、wake_reason、error_code | QueueEntry | 未排队时隐藏该节点；策略 ID 可进入限流策略详情。 |
| 容量预占 | id、attempt_id、policy_ids、reserved_tokens、actual_tokens、status、release_reason、created_at、settled_at | CapacityReservation | 归属于对应 Attempt；异常 EXPIRED 明确标记。 |
| Attempt 概览 | sequence、attempt_type、status、provider_name_snapshot、provider_model_name_snapshot、model_id_snapshot、credential_name_snapshot | Attempt | Credential 只显示名称和当前 masked_value；当前对象删除时使用名称快照。 |
| Attempt 时间 | started_at、provider_started_at、response_headers_at、first_token_at、ended_at、dispatch_ms、response_header_ms、first_token_ms、total_ms | Attempt | 时间轴和毫秒数同时展示，缺失阶段为空。 |
| Attempt 外部响应 | endpoint_host、http_status、provider_request_id、response_committed、finish_reason | Attempt | endpoint_host 不含路径参数；Provider Request ID 可复制。 |
| Attempt 错误 | error_category、error_stage、error_code、error_summary、retryable、retry_after_ms | Attempt | 只展示脱敏摘要。 |
| 恢复决策 | sequence、source_attempt_id、action、reason_code、scheduled_delay_ms、target_route_candidate_id、target_credential_id、三类 used、remaining_timeout_ms、created_at | RecoveryDecision | 放在来源 Attempt 结束和目标 Attempt 开始之间。 |
| 熔断事件 | circuit_id、from_state、to_state、trigger_type、error_code、reason、created_at | CircuitEvent | 只显示 trigger_trace_id 等于本 Trace 的事件。 |
| Usage 与 Cost | 输入、输出、总 Token、费用、币种、是否估算 | Trace、Attempt、价格快照 | 总计在上，各 Attempt 按序号在下。 |
| 最终错误 | 错误码、分类、可重试标志、失败 Attempt、简要原因 | Trace 与最终 Attempt | Trace 成功时整个区域隐藏。 |
| 保留信息 | detail_expires_at、content_sample_status | TraceDetail | 告知明细和诊断样本预计保留状态。 |

#### 4.4.2.3 统一时间线规则

时间线由 TraceTimelineItem 组成。首先放置 TRACE_CREATED；QueueEntry 产生时放置 QUEUE_ENTERED，成功取得容量时放置 QUEUE_ACQUIRED，TIMEOUT、CANCELLED 或 REJECTED 时放置 QUEUE_ENDED；每条 RouteDecision 放置 ROUTE_DECISION；每个 Attempt 至少放置 ATTEMPT_STARTED 与 ATTEMPT_ENDED，存在 first_token_at 时增加 ATTEMPT_FIRST_TOKEN；失败后放置 RECOVERY_DECIDED；触发熔断迁移时放置 CIRCUIT_CHANGED；最后放置 TRACE_ENDED。

所有事件按 occurred_at 升序排列。时间相同时依次使用 Trace、Queue、RouteDecision、Attempt Started、Attempt First Token、Attempt Ended、RecoveryDecision、CircuitEvent、Trace Ended 的固定优先级，再使用来源 sequence。该排序规则由服务端实现，前端不得重新推断。时间线中的 reason_code 可以定位原始 RouteDecision、RecoveryDecision 或 Attempt。

点击 Attempt 时间线节点打开明细抽屉。抽屉顶部展示路径和状态，中部展示阶段耗时，底部依次展示外部响应、Usage/Cost、容量预占和错误。点击 RecoveryDecision 节点同时高亮来源 Attempt 与目标 Attempt；action=FAIL 时只高亮来源 Attempt。

#### 4.4.2.4 Usage、Cost 与错误口径

Trace.input_tokens、output_tokens、total_tokens 和 total_cost 汇总全部产生用量或费用的 Attempt，包括失败后的 RETRY、CREDENTIAL_FAILOVER 和 FALLBACK。response_*_tokens 只来自最终成功 Attempt，用于说明调用方收到的 Usage。最终成功但存在前序失败时，Trace.total_tokens 可以大于 response_total_tokens。

每个 Attempt 的 input_price、output_price、price_unit 和 currency 从 config_snapshot_no 固化。input_cost 等于 input_tokens ÷ price_unit × input_price，output_cost 使用相同公式；cost 等于两项之和并四舍五入到八位小数。Trace.total_cost 等于同币种 Attempt.cost 之和。同一 Alias 的启用候选发布时已经保证 currency 一致。

Provider 返回 Usage 时标记 ACTUAL；响应未提供 Usage 或失败后无法读取完整 Usage 时由 Adapter TokenEstimator 生成 ESTIMATED。Trace 同时包含两类 Attempt 时标记 MIXED。结算异常不得清空已取得的 Usage，错误阶段记录为 SETTLEMENT并触发内部告警。

ErrorDetail 使用 Trace.error_code 和 error_summary 作为最终口径，同时展示 final Attempt 的 http_status、provider_request_id、retryable 和 retry_after_ms。前序失败通过 failed_attempt_ids 进入相应 Attempt 查看。Provider 原始错误正文不直接返回管理页面。

#### 4.4.2.5 操作与权限

| 操作 | 权限与前置条件 | 处理结果 | 限制 |
|---|---|---|---|
| 返回列表 | 可查看角色 | 恢复原查询和滚动位置 | 无。 |
| 复制 Trace ID | 可查看角色 | 复制 trace_id | 不写审计。 |
| 复制 Provider Request ID | 系统管理员、运维人员；字段存在 | 复制选中 Attempt 的标识 | 只读人员不可见完整值。 |
| 查看配置快照 | 系统管理员、运维人员 | 打开 snapshot_no 对应发布记录 | 快照已超出保留范围时只显示编号。 |
| 查看关联对象 | 具有对应页面权限 | 打开 Alias、Provider、模型、凭证池或策略详情 | 对象已删除时保持历史快照展示。 |
| 查看诊断样本 | 具有敏感诊断权限；采样启用且样本未过期 | 展开 sampled_messages | 每次查看写 AuditLog，导出仍不包含样本。 |
| 筛选同类错误 | 可查看角色；error_code 存在 | 返回 Trace 列表并带入相同时间范围和错误码 | 保留当前数据权限。 |

完成标准：Attempt 数量与 Trace.attempt_count 一致；时间线能够按顺序解释过滤、排队、外部调用、恢复和最终状态；每笔 Token 与费用可以追溯到 Attempt；响应 Usage 与运行总消耗得到区分；运行中刷新不重复节点；任何页面区域、接口和复制操作均不泄露认证信息与默认消息正文。

### 4.4.3 Usage 与 Cost 页

#### 4.4.3.1 页面布局与筛选字段

页面从上到下依次为筛选区、UsageSummary 摘要区、趋势区和分组区。首次进入查询最近 7 个完整自然日加当前日，granularity=DAY，group_by=ALIAS，trend_metric=REQUEST_COUNT。筛选条件由摘要、趋势和分组三个接口共用，任一条件变化后同时刷新三个区域；页面以同一个 query_fingerprint 标识本次查询，避免区域显示不同条件的数据。

| 顺序 | 字段 | 控件形式 | 默认值 | 查询与校验规则 |
|---:|---|---|---|---|
| 1 | start_at、end_at | 日期时间范围 | 最近 7 天 | 左闭右开；不得早于 usage_retention_days 可查询范围。 |
| 2 | granularity | 单选 | DAY | HOUR、DAY；HOUR 最大跨度 31 天，DAY 最大跨度 3650 天。 |
| 3 | application | 可输入多选 | 空 | 最多 20 项，精确匹配。 |
| 4 | project | 可输入多选 | 空 | 最多 20 项，精确匹配。 |
| 5 | tenant | 可输入多选 | 空 | 最多 20 项，精确匹配。 |
| 6 | alias_id | 远程搜索多选 | 空 | 最多 20 项。 |
| 7 | provider_id | 远程搜索多选 | 空 | 最多 20 项。 |
| 8 | provider_model_id | 联动远程搜索多选 | 空 | 最多 20 项。 |
| 9 | credential_pool_id | 远程搜索多选 | 空 | 系统管理员和运维人员可用。 |
| 10 | credential_id | 联动远程搜索多选 | 空 | 只显示 name、masked_value；最多 20 项。 |
| 11 | trace_status | 多选下拉 | 空 | 按 Trace 最终状态过滤。 |
| 12 | error_code | 可输入多选 | 空 | 最多 20 项。 |
| 13 | usage_source | 多选下拉 | 空 | ACTUAL、ESTIMATED；MIXED 由 Trace 级汇总计算。 |
| 14 | requested_stream | 单选下拉 | 全部 | 全部、流式、同步。 |
| 15 | currency | 可搜索单选 | 全部 | 全部时费用分币种展示；指定后显示单一总费用。 |
| 16 | trend_metric | 单选 | REQUEST_COUNT | REQUEST_COUNT、SUCCESS_RATE、ATTEMPT_COUNT、TOKEN、COST、RETRY、CREDENTIAL_FAILOVER、FALLBACK。 |
| 17 | group_by | 单选 | ALIAS | 取 UsageGroupRow.dimension_type 支持值。 |
| 18 | group_sort | 单选 | TOTAL_COST desc | 支持 REQUEST_COUNT、ATTEMPT_COUNT、TOTAL_TOKENS、TOTAL_COST 和 DIMENSION_NAME。 |
| 19 | group_page、group_page_size | 分页控件 | 1、20 | page_size 支持 20、50、100。 |

credential_pool_id、credential_id 以及 group_by=CREDENTIAL_POOL 或 CREDENTIAL 只向系统管理员和运维人员开放。开发人员的 application 条件由访问范围强制注入且不可清除；只读人员按宿主数据范围查询。无权使用的筛选字段返回 ACCESS_DENIED，不静默忽略。

#### 4.4.3.2 摘要区字段

摘要第一行按顺序展示 request_count、success_rate、attempt_count、total_tokens 和费用；第二行展示 success_count、failure_count、cancelled_count、queued_count、stream_count、stream_interrupted_count、initial_count、retry_count、credential_failover_count、fallback_count 和 half_open_probe_count；Token 展开项显示 input_tokens、output_tokens、actual_tokens、estimated_tokens 和 actual_token_rate。

费用指定单一 currency 时展示 input_cost、output_cost 和 total_cost。查询包含多个币种时，total_cost 主卡不执行换算，按 UsageSummary.costs 逐币种显示金额；input_cost 和 output_cost 同样按币种在展开区分组。所有金额保持八位小数，页面不使用浮点数进行二次相加。

success_rate 的分母为 SUCCEEDED、FAILED 和 STREAM_INTERRUPTED，QUEUED、RUNNING 与 CANCELLED 不进入分母。摘要数据来自 summary 接口，不通过趋势点或分组行相加获得。data_updated_at 与当前时间相差超过两个 dashboard_refresh_seconds 时，页面显示数据延迟状态。

#### 4.4.3.3 趋势区字段与交互

趋势横轴使用 UsageTrendPoint.bucket_start，提示信息展示 bucket_start、bucket_end 和当前指标值。REQUEST_COUNT 展示请求总数并分成功、失败；SUCCESS_RATE 使用每个桶自己的成功率；ATTEMPT_COUNT 分 INITIAL、RETRY、CREDENTIAL_FAILOVER、FALLBACK、HALF_OPEN_PROBE；TOKEN 分 actual_tokens 与 estimated_tokens；COST 按 currency 生成独立序列；其余恢复指标展示对应 Attempt 数。

HOUR 使用页面时区自然小时，DAY 使用页面时区自然日。缺失时间桶由服务端补零；费用多币种时每个币种单独补零。点击数据点进入 Trace 列表，带入该桶时间、当前可映射筛选条件和指标对应条件。目标时间超出 trace_retention_days 时停用钻取，并保留 Usage 聚合查看能力。

#### 4.4.3.4 分组区字段与交互

分组表依次显示 dimension_name、request_count、success_count、failure_count、success_rate、attempt_count、initial_count、retry_count、credential_failover_count、fallback_count、half_open_probe_count、actual_tokens、estimated_tokens、total_tokens、input_cost、output_cost、total_cost、request_share、token_share 和 cost_share。对象维度使用当前对象名称；对象已删除时使用聚合时保存的历史名称。空 project、tenant 或 error_code 使用固定值“未设置”，不得与字符串值相同。

APPLICATION、PROJECT、TENANT 和 ALIAS 按 Trace 归属计算请求指标，并把该 Trace 的全部 Attempt、Token 和费用归入同一业务维度。PROVIDER、PROVIDER_MODEL、CREDENTIAL_POOL 和 CREDENTIAL 的 request_count 只归入 final_attempt_id 对应路径；attempt_count、Token 和费用按每个实际 Attempt 路径归入，因此前序失败渠道可能出现 request_count=0 且 cost>0。TRACE_STATUS 把 Trace 的全部 Attempt 归入最终状态。ERROR_CODE 的 request_count 使用 Trace 最终 error_code，Attempt 与费用使用各 Attempt.error_code；无错误使用“未设置”。USAGE_SOURCE 的 Token 与费用按 Attempt.usage_source 归入，request_count 使用 final_attempt_id 的 usage_source；没有 Attempt 或没有 Usage 时归入“未设置”。

所有 group_by 维度的 request_count 均按上述最终归属保持互斥，因此 request_share 合计为 100%。token_share 和 cost_share 按实际 Attempt 归属计算；没有 Token 或费用的查询中对应占比为空。

点击分组行进入 Trace 列表时，业务维度、最终路径和 Trace 状态可以直接映射。按 Attempt 错误或 Usage 来源分组时，跳转附加 attempt_type 或存在性条件，由 Trace 列表返回包含相应 Attempt 的 Trace。时间超出 Trace 明细保留期时停用跳转。

#### 4.4.3.5 操作定义

| 操作 | 权限与前置条件 | 系统处理 | 页面结果与异常 |
|---|---|---|---|
| 查询 | 可查看角色；时间与字段合法 | 使用同一筛选生成 query_fingerprint，加载摘要、趋势和分组 | 任一接口失败时该区域显示失败，保留其他已成功区域及统一条件。 |
| 重置 | 可查看角色 | 恢复最近 7 天、DAY、ALIAS | 同时刷新全部区域。 |
| 切换趋势指标 | 可查看角色 | 只重新查询 trends | 摘要和分组保持不变。 |
| 切换分组维度 | 可查看角色 | group_page 回到 1，只重新查询 groups | 保留筛选与趋势。 |
| 查看 Trace | 可查看角色；时间仍在 Trace 保留期 | 跳转 Trace 列表并映射条件 | 无法映射的聚合维度不生成错误筛选。 |
| 导出 | 系统管理员、运维人员；时间范围必填 | 按当前 granularity、group_by 和筛选流式生成 CSV | 超过 100000 行返回 EXPORT_TOO_LARGE。 |

#### 4.4.3.6 聚合计算与数据流

一次 Trace 进入最终状态后，服务端在保存最终 Trace、最后 Attempt 和 Usage/Cost 结算的同一业务事务中写入唯一 UsageAggregationEvent。聚合处理器按事件 id 幂等消费，使用 Trace.started_at 作为该 Trace 及其全部 Attempt 的时间归属，分别更新 HOUR 和 DAY 两种 UsageAggregate。采用 Trace 开始时间可以保证一次调用的请求、恢复动作、Token 和费用处于同一时间桶。

request_count 每个 Trace 只增加 1；attempt_count 每个 Attempt 增加 1；initial_count、retry_count、credential_failover_count、fallback_count 和 half_open_probe_count 分别按 attempt_type 增加。Token 与费用只统计已经完成结算的 Attempt。Provider 没有返回 Usage 时使用 TokenEstimator，并把 Token 计入 estimated_*；实际与估算不得重复累计。Trace 最终事务提交后，Trace、Attempt、Token 和价格快照保持不可变；事件处理失败只重试同一事件，不修改源数据。

聚合处理器为每个 Trace 生成一条请求贡献和每个 Attempt 一条执行贡献。请求贡献的 request_count、成功、失败、取消、排队和流式指标按 Trace 取值，路径维度使用 final_attempt_id；未创建 Attempt 时路径与 Usage 来源为空，currency 使用 Trace.currency。请求贡献的 Attempt、Token 和费用指标全部为 0。执行贡献的 request_count 等 Trace 指标为 0，Attempt、恢复动作、Token 和费用按实际 Attempt 取值。两类贡献按相同 dimension_key 合并，因此任意查询层级都不会重复统计 request_count，同时保留前序失败路径产生的 Token 和费用。

UsageAggregate 唯一键由 granularity、bucket_start、维度组合和 currency 构成。聚合更新使用数据库原子增量或等价事务，updated_at 在提交后更新。summary、trends 和 groups 使用相同的聚合查询构造器；summary 直接对符合维度的聚合行求和，groups 按 group_by 重新归并，trends 按 bucket_start 补齐时间桶。

聚合处理器每次原子取得一条 PENDING，或 next_retry_at 已到达的 FAILED 事件，写入 locked_by、locked_at 并进入 PROCESSING。处理实例超过 120 秒未完成时，其他实例可以接管。HOUR、DAY 聚合更新与事件 SUCCEEDED 状态在同一数据库事务提交；失败时回滚所有聚合增量，事件记录错误并按 1、2、4、8、16 分钟退避，之后固定每 30 分钟重试。连续失败 10 次产生运行告警，仍保留事件继续重试。

聚合允许存在不超过两个 dashboard_refresh_seconds 的延迟。Trace 明细清理前，清理任务必须确认对应 UsageAggregationEvent.status=SUCCEEDED；未消费记录跳过清理并告警。Usage 保留期到达后按 bucket_end 分批删除 HOUR 和 DAY 聚合行。

完成标准：同一筛选下摘要、趋势和分组共享查询条件；请求、Attempt、Token 和费用口径可以从保留期内 Trace/Attempt 复算；重放聚合事件不重复累计；实际与估算用量分别可见；价格修改不改变历史费用；多币种不做汇率换算；钻取条件与原聚合维度一致。

### 4.4.4 调用观测管理接口契约

#### 4.4.4.1 Trace 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/traces | TraceListQuery | PageResult<TraceListItem> | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/traces/{traceId} | traceId | TraceDetail | 可查看角色 | OBJECT_NOT_FOUND、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/traces/export | TraceListQuery，忽略 page、page_size | text/csv 流 | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、EXPORT_TOO_LARGE、OBSERVATION_DATA_UNAVAILABLE |

PageResult<TraceListItem> 返回 items、total、page、page_size、sort、query_started_at 和 data_updated_at。精确 trace_id 查询成功时 items 最多一条，page 固定 1。TraceDetail 中 attempts 数量必须等于 trace.attempt_count；route_decisions、recovery_decisions 和 timeline 已按各自顺序排序。

详情数据权限先按 Trace.application 和宿主数据范围判断，再读取下级数据。没有权限统一返回 ACCESS_DENIED，不通过子实体接口绕过 Trace 权限。Credential ID、Provider Request ID 和诊断样本根据字段权限在序列化阶段移除，服务端日志不得记录移除前的响应对象。

#### 4.4.4.2 Usage 与 Cost 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/usage/summary | UsageQuery，忽略 trend_metric 与分组分页字段 | UsageSummaryResult | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/usage/trends | UsageQuery，使用 trend_metric | UsageTrendResult | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/usage/groups | UsageQuery，使用 group_by、group_sort 与分组分页字段 | UsageGroupResult | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/usage/export | UsageQuery，忽略 group_page、group_page_size | text/csv 流 | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、EXPORT_TOO_LARGE、OBSERVATION_DATA_UNAVAILABLE |

四个接口对同一组筛选字段生成相同 query_fingerprint，并在响应中返回。调用方同时请求三个页面接口时，应以相同 query_fingerprint 和服务端解析后的 start_at、end_at、timezone、currency_filters 为一致性依据。data_updated_at 允许在接口执行期间产生轻微差异，页面取三个结果中最早时间作为整页数据更新时间。

查询未指定 currency 时，summary.costs 按币种返回，trends 的同一 bucket 可以包含多个 currency 点，groups 按 dimension 与 currency 拆行。请求 total_cost 排序或 cost_share 时必须指定单一 currency；否则返回 FIELD_VALIDATION_FAILED 并指出 currency 字段。

### 4.4.5 导出、保留与清理规则

Trace 导出文件名为 traces-{start_at}-{end_at}.csv，字段顺序为 started_at、trace_id、source_mode、access_credential_name、application、project、tenant、alias、requested_stream、final_provider_name、final_provider_model_name、status、attempt_count、retry_count、credential_failover_count、fallback_count、queued_ms、first_token_ms、total_ms、usage_source、input_tokens、output_tokens、total_tokens、total_cost、currency、error_code。每行对应一个 Trace，不展开 Attempt、client_ip、user_agent 和诊断样本。

Usage 导出文件名为 usage-{granularity}-{group_by}-{start_at}-{end_at}.csv，字段顺序为 bucket_start、bucket_end、dimension_type、dimension_id、dimension_name、request_count、success_count、failure_count、success_rate、attempt_count、initial_count、retry_count、credential_failover_count、fallback_count、half_open_probe_count、actual_tokens、estimated_tokens、total_tokens、input_cost、output_cost、total_cost、currency。每行对应一个时间桶、分组值和币种组合。

导出使用 UTF-8 BOM 和 RFC 4180 CSV 转义。文本字段以 =、+、- 或 @ 开头时在值前增加单引号，防止电子表格公式执行。服务端边查询边写响应，不把完整文件保存在数据库或本地目录；最大 100000 行、最长执行 60 秒。超过行数在发送响应头前返回 EXPORT_TOO_LARGE；执行中连接断开时取消数据库游标并停止生成。

Trace、Attempt、RouteDecision、QueueEntry、CapacityReservation、RecoveryDecision、CircuitEvent 关联明细按 trace_retention_days 同批清理。诊断样本按 diagnostic_sample_retention_days 单独提前清理。UsageAggregate 按 usage_retention_days 保留，清理 Trace 前必须确认聚合完成。清理每批最多 1000 个 Trace，批次之间提交事务，失败批次记录起止 ID 和错误并等待下一次任务重试。

保留期缩短只影响新配置发布后的清理任务，不恢复已删除数据。页面时间选择器根据可查询起点限制 Trace 和 Usage 范围；从 Usage 钻取到已超出 Trace 保留期的时间时停用入口。导出与页面读取遵循相同保留期和权限，不提供绕过清理策略的历史恢复接口。

## 4.5 运行配置

运行配置按照“查看待发布变更—执行校验与发布—维护运行参数—管理 Standalone Access Credential—查询审计日志”的顺序组织。模型接入和治理配置先进入待发布变更，运行参数同样经快照发布生效；访问凭证属于安全即时配置，不进入模型运行快照；所有成功与失败管理操作最终可以在审计日志中查询。

### 4.5.1 待发布变更页

#### 4.5.1.1 页面进入与信息顺序

用户从左侧“运行配置—待发布变更”进入页面。页面从上到下依次展示草稿状态、变更摘要、筛选区、分组变更列表和底部固定操作区。草稿状态展示 base_snapshot_no、draft_revision、change_count、status、first_modified_at 和 last_modified_at。摘要按照新增、修改、启用、停用、删除以及对象类型显示 DraftChangeSummary。

变更列表默认按 entity_type 的依赖顺序分组：Provider、Credential Pool、Credential、Provider Model、Model Alias、Route Candidate、Limit Policy、Reliability Policy、Runtime Config；组内按 modified_at desc。运行状态、Trace、Usage、CircuitState、ProviderCheckRecord、Standalone Access Credential 和 AuditLog 不进入草稿列表。

#### 4.5.1.2 筛选与列表字段

| 页面区域 | 字段 | 控件或展示形式 | 数据来源 | 规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 单行文本 | 查询参数 | 匹配 entity_name 或 entity_id。 |
| 筛选区 | entity_type | 多选下拉 | DraftChange.entity_type | 默认全部配置实体。 |
| 筛选区 | change_type | 多选下拉 | DraftChange.change_type | CREATE、UPDATE、ENABLE、DISABLE、DELETE。 |
| 筛选区 | modified_by | 远程搜索多选 | DraftChange.modified_by | 显示 modified_by_name。 |
| 筛选区 | modified_from、modified_to | 日期时间范围 | DraftChange.modified_at | 可为空，使用页面时区。 |
| 草稿状态 | base_snapshot_no | 数字链接 | ConfigDraftState | 点击进入当前活动快照摘要。 |
| 草稿状态 | draft_revision | 数字 | ConfigDraftState | 任一草稿写操作后变化。 |
| 草稿状态 | status | 文本 | ConfigDraftState.status | PUBLISHING 时停用配置编辑和撤销。 |
| 摘要 | total_count 与各 change count | 数字 | DraftChangeSummary | 点击某类数量写入 change_type 筛选。 |
| 列表 | entity_type | 分组标题 | DraftChange.entity_type | 按固定依赖顺序展示。 |
| 列表 | change_type | 文本 | DraftChange.change_type | 直接表达新增、修改、启用、停用或删除。 |
| 列表 | entity_name、entity_id | 链接与等宽文本 | DraftChange | 点击进入对象详情；删除草稿打开只读差异。 |
| 列表 | changed_fields | 差异表 | array<FieldChange> | 每个字段展示 before_value、after_value；sensitive=true 时只显示“敏感字段已变更”。 |
| 列表 | dependency_summary | 数量链接 | DraftChange | 展开查看关联草稿对象。 |
| 列表 | modified_by_name | 文本 | DraftChange | 同时保留 modified_by 用于查询。 |
| 列表 | modified_at | 时间 | DraftChange | 按页面时区展示。 |
| 列表 | revertable | 操作状态 | DraftChange | false 时展示 revert_blockers。 |
| 列表 | entity_version | 隐藏操作参数 | DraftChange | 单项撤销时提交。 |

#### 4.5.1.3 操作定义

| 操作 | 权限与前置条件 | 请求数据 | 服务端变化 | 页面结果与异常 |
|---|---|---|---|---|
| 查询 | 可查看角色 | 筛选、page、page_size | 无 | 返回当前 draft_revision 下的 PageResult<DraftChange>。 |
| 查看对象 | 具有对象查看权限 | entity_type、entity_id | 无 | 打开对象草稿详情。 |
| 查看字段差异 | 可查看角色 | DraftChange.id | 无 | 展开完整脱敏 FieldChange。 |
| 撤销单项 | 系统管理员；status=EDITABLE；revertable=true | RevertDraftCommand | 恢复活动快照值或删除未发布新建对象，递增 draft_revision，写 AuditLog | 成功后刷新摘要和当前组；版本变化返回 CONFIG_DRAFT_CHANGED。 |
| 撤销全部 | 系统管理员；change_count>0；status=EDITABLE | RevertAllDraftCommand | 在单一事务中恢复 base_snapshot_no 的全部配置，清空 DraftChange，递增 draft_revision，写逐对象审计和汇总审计 | 任一对象恢复失败则全部回滚。 |
| 校验并发布 | 系统管理员；change_count>0；status=EDITABLE | 当前 draft_revision | 无直接变更 | 进入 4.5.2 校验步骤。 |

#### 4.5.1.4 草稿修订与差异数据流

任一配置实体的新建、编辑、启用、停用、删除或 RuntimeConfig 修改，在同一事务内锁定 ConfigDraftState，校验实体 version，写入草稿实体和 AuditLog，再把 draft_revision 增加 1。事务失败时实体、审计和 draft_revision 一并回滚。草稿对象保留自身 version，用于同一对象并发编辑；draft_revision 用于保护跨对象校验、撤销全部和发布。

DraftChange 由当前草稿与 base_snapshot_no 对应 ConfigSnapshot 比较生成。活动快照中不存在而草稿存在的对象为 CREATE；两边存在且 enabled 从 false 变 true 为 ENABLE，从 true 变 false 为 DISABLE；草稿标记删除为 DELETE；其他字段变化为 UPDATE。一个对象只生成一条 DraftChange，changed_fields 包含全部差异。

Credential 的 secret_value、Secret 解析值和 Standalone Token 不进入 ConfigSnapshot。Credential 密钥轮换立即更新密钥存储，配置字段未变化时不产生 DraftChange；Credential 新建、池关系、限额、权重和启停属于配置草稿。Standalone Access Credential 全部操作立即生效，不参与 draft_revision。

#### 4.5.1.5 撤销规则

撤销 CREATE 会删除未发布草稿对象；撤销 UPDATE、ENABLE 或 DISABLE 会从活动快照复制完整对象值覆盖草稿；撤销 DELETE 会清除删除标记并恢复活动值。单项撤销不得级联修改其他草稿对象。其他新建草稿引用该对象时，revertable=false，并在 revert_blockers 返回引用对象 ID；管理员先处理引用对象。

撤销全部以 base_snapshot_no 为唯一目标，忽略各对象当前 change_type。页面要求输入固定确认文本并填写原因。成功后 ConfigDraftState.base_snapshot_no 保持不变、change_count=0、status=EDITABLE；草稿实体版本重新生成，防止旧编辑页面继续提交。

完成标准：所有配置草稿对象与 DraftChange 一一对应；摘要、筛选和分组数量一致；敏感字段无前后值；实体 version 防止同对象覆盖，draft_revision 防止跨对象旧校验和旧撤销；撤销失败不产生部分恢复。

### 4.5.2 配置发布页

#### 4.5.2.1 页面步骤

配置发布页面包含“校验”“确认”“实例准备与激活”“发布结果”四个连续步骤，并在下方提供发布历史。进入页面时读取 ConfigDraftState；change_count=0 时只能查看历史。校验步骤锁定页面读取到的 draft_revision，但不阻止其他管理员继续编辑；一旦草稿发生变化，原 ConfigValidationResult 立即 EXPIRED。

确认步骤展示 validation_id、base_snapshot_no、target_snapshot_no、draft_revision、change_summary、affected_alias_ids、全部 WARNING、在线实例和 DRAINING/STALE/OFFLINE 实例。管理员逐条确认 WARNING，填写 publish_note 后提交 acknowledged_warning_ids。缺少任一警告确认时页面禁止提交，服务端仍会校验其完整性。发布提交成功后页面进入只读进度状态，轮询 PublishRecordDetail；关闭页面不影响服务端流程。

#### 4.5.2.2 校验结果字段与页面行为

| 页面区域 | 字段 | 数据来源 | 展示与操作 |
|---|---|---|---|
| 校验摘要 | status、error_count、warning_count | ConfigValidationResult | error_count>0 时禁止进入确认；WARNING 需要展开阅读。 |
| 版本信息 | base_snapshot_no、draft_revision、target_snapshot_no | ConfigValidationResult | 与当前 ConfigDraftState 对比；不一致时显示已过期。 |
| 内容摘要 | content_checksum、change_summary | ConfigValidationResult | checksum 可复制，用于发布一致性排查。 |
| 影响范围 | affected_alias_ids | ConfigValidationResult | 展示 Alias 名称和近 24 小时请求量。 |
| 问题列表 | severity、code、entity_type、entity_name、field_path、message、suggestion | ConfigValidationIssue | ERROR 在前，按 entity_type 和 field_path 排序；点击进入对象编辑页。 |
| 关联对象 | related_entity_ids | ConfigValidationIssue | 展开查看导致引用或冲突的对象。 |
| 有效期 | validated_at、expires_at | ConfigValidationResult | 到期或草稿变化后只允许重新校验。 |

#### 4.5.2.3 发布校验矩阵

| 校验类别 | 具体规则 | 严重度 | ConfigValidationIssue.code |
|---|---|---|---|
| 基础字段 | 全部必填字段、长度、数值范围、枚举和组合约束有效 | ERROR | FIELD_INVALID |
| 唯一约束 | Provider、池、Credential、模型、Alias 和策略名称及业务唯一键无冲突 | ERROR | UNIQUE_CONFLICT |
| 引用完整性 | 草稿引用对象存在、未删除且关系可达 | ERROR | REFERENCE_INVALID |
| Adapter | 每个启用 Provider 的 type 已注册，schema_version 与运行实例兼容 | ERROR | ADAPTER_UNAVAILABLE |
| Provider 关系 | ProviderModel 与 CredentialPool 属于 RouteCandidate 指定的同一 Provider | ERROR | PROVIDER_RELATION_INVALID |
| Alias 候选 | 启用 Alias 至少有一个启用候选，候选链包含 Provider、模型、池和至少一个启用 Credential | ERROR | ALIAS_NO_AVAILABLE_CANDIDATE |
| 能力与上下文 | 模型 tokenizer_family、context_window、max_output_tokens、stream、system、temperature、top_p、stop 支持状态、模型级范围、默认参数与 Adapter 能力上界组合合法 | ERROR | MODEL_CAPABILITY_INVALID |
| 价格 | 启用模型具有 input_price、output_price、price_unit、currency；同一 Alias 候选 currency 一致 | ERROR | PRICE_CONFIGURATION_INVALID |
| Credential | 启用池至少有一个启用且非 INVALID Credential；EXTERNAL_REF 具有非空 secret_ref | ERROR | CREDENTIAL_CONFIGURATION_INVALID |
| Credential 检测 | 启用 Credential 或模型最近 24 小时无成功检测记录 | WARNING | CONNECTION_CHECK_STALE |
| 限流策略 | 同一作用对象最多一份启用策略，至少一个限额存在，QUEUE 参数有效 | ERROR | LIMIT_POLICY_INVALID |
| 可靠性策略 | 同一 Alias 最多一份启用策略，超时、重试、Fallback 和熔断参数组合有效 | ERROR | RELIABILITY_POLICY_INVALID |
| RuntimeConfig | 保留期、采样、消息长度、代理和实例时限字段有效；timezone 锁定规则满足 | ERROR | RUNTIME_CONFIG_INVALID |
| 运行实例版本 | ONLINE 目标实例支持 ConfigSnapshot.schema_version 和全部启用 Adapter | ERROR | INSTANCE_VERSION_INCOMPATIBLE |
| 非在线实例 | 存在 DRAINING、STALE 或 OFFLINE 实例；DRAINING 结束或实例恢复心跳后加载最新活动快照 | WARNING | INSTANCE_NOT_ONLINE |

校验只读取配置和运行实例能力，不向外部 Provider 发起模型调用，也不解析外部 Secret。连接健康通过最近 ProviderCheckRecord 形成 WARNING；发布成功不等同于外部 Provider 实时可用。

#### 4.5.2.4 发布事务与两阶段加载

提交 ConfigPublishCommand 后，服务端在事务中校验 validation_id 为 PASSED、未过期、未被使用，current draft_revision 和 content_checksum 与校验结果一致，acknowledged_warning_ids 完整，并将 ConfigDraftState 从 EDITABLE 改为 PUBLISHING。警告确认不完整返回 FIELD_VALIDATION_FAILED；版本条件不满足返回 CONFIG_VALIDATION_EXPIRED 或 CONFIG_DRAFT_CHANGED，均不创建 PublishRecord。

服务端取得单调递增 snapshot_no，把规范化配置序列化为 ConfigSnapshot.content，计算 SHA-256 content_checksum，确认不包含 Credential secret_value、完整 secret_ref、Standalone Token、运行状态和审计数据，保存 status=CREATED 的快照，并创建 status=PREPARING 的 PublishRecord。目标实例集合固定为发布开始时 status=ONLINE 的 RuntimeInstance；STALE 和 OFFLINE 只进入警告列表，不阻止发布。ABORTED 快照编号不回收，后续成功快照允许出现编号间隔。

准备阶段向每个目标实例下发 InstancePrepareCommand。实例下载 CREATED 快照，验证 schema_version 与 content_checksum，在独立内存区域解析全部对象、装配 Adapter、构建路由图并执行本地完整性检查；期间继续使用 from_snapshot_no。成功上报 READY，失败上报 FAILED。任一在线目标实例在 publish_instance_timeout_seconds 内未 READY，结果标记 TIMED_OUT，PublishRecord=FAILED，ConfigSnapshot=ABORTED，草稿解除锁定并保持全部变更，活动快照不变。

全部在线目标实例 READY 后，发布服务先把 PublishRecord 改为 ACTIVATING，再在一个数据库事务中把上一 ACTIVE 快照改为 SUPERSEDED、目标快照改为 ACTIVE、更新 RuntimeConfig.current_snapshot_no、清空已发布草稿并把 ConfigDraftState 恢复为 EDITABLE。随后向实例下发 InstanceActivationCommand；实例以单次引用替换切换已准备内存配置并上报 LOADED。新 Trace 读取新 snapshot_no，切换前已经创建的 Trace 保持原快照对象引用直到结束。全部实例在首轮时限内 LOADED 时记录 status=SUCCEEDED、completed_at=converged_at=当前时间。

激活后未在时限内确认的实例进入 TIMED_OUT，明确失败的实例进入 FAILED，PublishRecord=PARTIAL_FAILED，并写 completed_at；已确认实例继续使用新快照。实例后续心跳发现 active_snapshot_no 落后时重新下载当前 ACTIVE 快照并切换，原 PublishInstanceResult 更新为 LOADED。全部目标实例收敛后 PublishRecord 从 PARTIAL_FAILED 更新为 SUCCEEDED 并写 converged_at，completed_at 与 duration_ms 保留首轮结果。发布服务不自动把已加载实例恢复到旧快照。

#### 4.5.2.5 实例状态与异常

RuntimeInstance 每 15 秒发送心跳。有效心跳且 accepting_requests=true 时为 ONLINE；accepting_requests=false 时为 DRAINING。last_heartbeat_at 超过 instance_stale_seconds 时为 STALE，超过三倍该值时为 OFFLINE。实例启动或恢复心跳时，如果 active_snapshot_no 小于全局活动快照，先下载和验证最新快照，成功后进入 ONLINE；加载失败保留本地上一完整快照，status=STALE 并上报错误。DRAINING、STALE 和 OFFLINE 均不进入新发布的目标实例集合。

PublishInstanceResult 状态顺序为 PENDING、PREPARING、READY、ACTIVATING、LOADED。FAILED 和 TIMED_OUT 可以在后台重新加载后转为 LOADED。页面显示 instance_id、runtime_mode、runtime_version、supported_schema_versions、loaded_adapter_types、from_snapshot_no、target_snapshot_no、status、retry_count、load_duration_ms、error_code、error_summary 和 updated_at；能力集合来自 RuntimeInstance 最近一次有效心跳。

目标实例数量为 0 时禁止发布并返回 NO_ONLINE_RUNTIME_INSTANCE。单实例 Embedded 部署把当前进程注册为唯一 RuntimeInstance；Standalone 集群中的每个服务实例分别注册。本地 SDK 使用进程内 LocalRuntimeDefinition，不参与管理端发布。运行实例只通过内部认证接口取得 ConfigSnapshot.content。

#### 4.5.2.6 发布历史

历史筛选依次为时间范围、配置版本、发布状态、发布人和关键词。列表展示发布时间、前后版本、结果、发布人和变更摘要，默认按发布时间倒序。点击行进入详情，页面从上到下展示发布摘要、校验问题、配置快照摘要、各运行实例的准备或激活结果和当前状态。

FAILED 记录只允许查看；管理员修复或保持原草稿后重新执行校验。PARTIAL_FAILED 记录显示后台收敛状态，不提供人工强制覆盖实例。ConfigSnapshot.content 不通过管理页面返回，页面只展示 schema_version、checksum、大小、实体数量和状态。

完成标准：旧 draft_revision 无法发布；校验问题可以定位对象和字段；准备失败不改变活动快照；激活事务与草稿清理保持原子；实例切换不影响运行中 Trace；发布详情可以解释每个实例当前和目标快照；敏感值不进入快照、校验问题和加载错误。

### 4.5.3 运行参数页

#### 4.5.3.1 页面布局

运行参数页顶部展示 current_snapshot_no、published_at、draft_changed、draft_revision 和最近修改人。表单依次分为时间与保留、请求限制、诊断采样、来源 IP、发布协调五个区块。每个字段同时显示当前活动值和草稿输入值；存在差异时可查看 FieldChange。页面只编辑 RuntimeConfig，Standalone Access Credential 使用独立的 4.5.4 页面。

#### 4.5.3.2 表单字段

| 区域 | 字段 | 控件形式 | 默认值 | 校验与生效规则 |
|---|---|---|---|---|
| 时间与保留 | timezone | IANA 时区搜索 | Asia/Shanghai | 已存在 UsageAggregate 后 timezone_locked=true，字段只读。 |
| 时间与保留 | trace_retention_days | 数字框 | 30 | 1—365；缩短时必须确认 RetentionImpact。 |
| 时间与保留 | usage_retention_days | 数字框 | 365 | 30—3650，必须大于等于 trace_retention_days。 |
| 时间与保留 | audit_retention_days | 数字框 | 365 | 365—3650。 |
| 页面 | dashboard_refresh_seconds | 数字框 | 30 | 10—300。 |
| 请求限制 | max_message_chars | 数字框 | 100000 | 1000—1000000。 |
| 请求限制 | max_request_chars | 数字框 | 500000 | max_message_chars—5000000。 |
| 诊断采样 | diagnostic_sampling_enabled | 开关 | false | 开启时显示并启用后三个采样字段。 |
| 诊断采样 | diagnostic_sample_rate | 小数框 | 0 | 0—1，最多四位小数；关闭时强制为 0。 |
| 诊断采样 | diagnostic_sample_retention_days | 数字框 | 7 | 1—30，且不超过 trace_retention_days。 |
| 诊断采样 | diagnostic_sample_max_chars | 数字框 | 1000 | 100—10000。 |
| 来源 IP | client_ip_recording_enabled | 开关 | false | 只影响新 Trace；历史 IP 按 Trace 保留期清理。 |
| 来源 IP | trusted_proxy_cidrs | 可增删列表 | 空 | 最多 100 项，每项为 IPv4、IPv6 或 CIDR；仅 Standalone 使用。 |
| 发布协调 | publish_instance_timeout_seconds | 数字框 | 60 | 10—300；从下一次发布开始使用。 |
| 发布协调 | instance_stale_seconds | 数字框 | 60 | 30—600，必须大于两倍心跳间隔。 |
| 系统字段 | current_snapshot_no、published_at | 只读 | 系统值 | 从活动快照读取。 |
| 系统字段 | version | 隐藏 | 系统值 | 保存 RuntimeConfigUpdateCommand 时提交。 |

#### 4.5.3.3 操作与数据流

| 操作 | 权限与前置条件 | 服务端处理 | 页面结果与异常 |
|---|---|---|---|
| 查看 | 可查看角色 | 返回 RuntimeConfigDetail | 页面展示活动值、草稿值和保留影响。 |
| 估算保留影响 | 系统管理员；修改任一保留天数 | 只读统计预计删除数量和 target_cutoff_at | 展示 RetentionImpact，不产生草稿。 |
| 保存草稿 | 系统管理员；version 最新；字段合法 | 更新 RuntimeConfig 草稿、实体 version、draft_revision 和 AuditLog | 成功后显示待发布；版本冲突保留输入。 |
| 重置未保存输入 | 系统管理员 | 无服务端请求 | 恢复最近读取的 draft_config。 |
| 查看待发布差异 | 可查看角色；draft_changed=true | 跳转 4.5.1 并筛选 Runtime Config | 无。 |

RuntimeConfig 保存只写草稿，发布激活后新请求、页面查询、清理任务和下一次发布协调使用新值。已经运行的 Trace 保持创建时取得的超时与快照；dashboard_refresh_seconds 在管理页面下一次读取配置后生效。

保留期缩短时，服务端根据当前记录估算删除数量，保存请求必须携带页面确认过的 impact_version；估算后数据范围变化返回 RETENTION_IMPACT_EXPIRED。发布成功后清理任务按新 cutoff 分批执行，不恢复已清理数据。usage_retention_days 不得小于 trace_retention_days，保证 Trace 存在期间可查询对应 Usage。

开启诊断采样后只对新请求按 sample_rate 随机决定，并先脱敏再持久化。关闭采样停止新建样本，并在 10 分钟内清理现存 TraceContentSample，将关联 Trace.content_sample_status 更新为 EXPIRED。降低 sample retention 产生同样的提前清理影响。

Standalone 仅在请求的直连来源位于 trusted_proxy_cidrs 时读取 X-Forwarded-For，并从右向左移除可信代理得到真实来源；其他请求使用连接地址。client_ip_recording_enabled=false 时仍可使用解析后的 IP 执行 Access Credential 白名单校验，但不写入 Trace 和 last_used_ip。

完成标准：所有字段具有确定范围和组合校验；活动值与草稿值可区分；timezone 锁定后无法形成混合时间桶；保留期缩短有影响确认；采样开关、来源 IP 和发布协调参数只在发布后影响对应新操作。

### 4.5.4 Standalone Access Credential 页

New API 的公开令牌页面支持有效期、模型范围、IP 白名单和完整 Key 一次性显示，验证了网关访问凭证的基础管理方式。轻享 AI 保留这些与运行安全直接相关的能力，不引入充值、配额余额和商业分组。参考依据为 [New API 令牌管理](https://docs.newapi.pro/zh/docs/guide/feature-guide/user/token)。

#### 4.5.4.1 可见范围与列表页面

该页面只在 Standalone Mode 展示。SDK 和 Embedded Mode 使用宿主调用上下文，不创建 Standalone Access Credential。页面顶部依次为 keyword、application、status、allowed_alias_id、expires_before 和 has_recent_use 筛选，右侧主操作为“创建访问凭证”。

| 顺序 | 列字段 | 数据来源 | 展示与交互规则 |
|---:|---|---|---|
| 1 | name | AccessCredentialListItem.name | 点击进入详情。 |
| 2 | masked_token | AccessCredentialListItem.masked_token | 只允许复制脱敏值。 |
| 3 | application | AccessCredentialListItem.application | 点击筛选同应用 Trace。 |
| 4 | allowed_alias_count | AccessCredentialListItem | 空数组显示“全部已发布 Alias”。 |
| 5 | ip_rule_count | AccessCredentialListItem | 空数组显示“不限制”。 |
| 6 | status | AccessCredentialListItem.status | ACTIVE、DISABLED、EXPIRED；DELETED 默认不显示。 |
| 7 | expires_at | AccessCredentialListItem.expires_at | 7 天内到期时显示剩余时间。 |
| 8 | rotation_generation | AccessCredentialListItem.rotation_generation | 用于确认当前 Token 代次。 |
| 9 | last_used_at | AccessCredentialListItem.last_used_at | 未使用显示空。 |
| 10 | last_used_ip | AccessCredentialListItem.last_used_ip | 仅敏感诊断权限可见。 |
| 11 | trace_count_24h | AccessCredentialListItem.trace_count_24h | 点击进入对应 Trace。 |
| 12 | updated_at | AccessCredentialListItem.updated_at | 最近安全配置变更时间。 |
| 13 | actions | AccessCredentialListItem.actions | 查看、编辑、轮换、启停、删除、查看 Trace。 |

#### 4.5.4.2 创建与编辑字段

| 字段 | 控件形式 | 创建默认值 | 编辑规则 | 校验 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 可编辑 | 2—64 字符，全局唯一。 |
| application | 单行文本 | 空 | 可编辑，立即影响新 Trace | 1—64 字符。 |
| allowed_alias_ids | 远程搜索多选 | 空 | 可编辑 | 必须指向当前已发布 Alias；空数组允许全部。 |
| ip_allowlist | 可增删列表 | 空 | 可编辑 | 最多 100 项，IPv4、IPv6 或 CIDR；空数组不限制。 |
| expires_at | 日期时间 | 空 | 可延长或缩短 | 创建时为空或晚于当前时间至少 5 分钟；编辑为过去时间后立即 EXPIRED。 |
| enabled | 开关 | true | 编辑页通过独立启停操作修改 | 创建时可选。 |
| version | 隐藏 | 空 | 编辑、启停、轮换和删除提交 | 必须为最新版本。 |

详情页从上到下展示非敏感 credential 字段、allowed_aliases、最近 24 小时调用统计、最近 10 条 Trace 和 AuditLog 摘要。token_hash、token_hash_version 和完整 token_value 永不出现在详情响应。

#### 4.5.4.3 Token 签发与一次性展示

创建和轮换时，服务端使用密码学安全随机源生成 32 字节随机值，编码为无填充 Base64URL，并添加 lai_ 前缀。token_prefix 取用于索引的前 8 个字符，token_hash 使用服务端 pepper 执行 HMAC-SHA256，比较时使用恒定时间函数。原始 token_value 只存在于当前请求内存和 AccessCredentialSecretResult，响应完成后清除引用。

页面以阻断弹窗显示 token_value、issued_at 和 rotation_generation，要求用户勾选“已安全保存”后关闭。关闭后任何读取接口都无法再次取得原文；遗失时执行轮换。页面禁止把 Token 写入 URL、浏览器持久缓存、分析事件和前端日志。

#### 4.5.4.4 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 成功结果与失败处理 |
|---|---|---|---|
| 创建 | 系统管理员；Standalone Mode | 创建摘要记录和 generation=1 Token，写 AuditLog | 返回 AccessCredentialSecretResult；字段错误不生成 Token。 |
| 查看 | 系统管理员、运维人员 | 无 | 返回 AccessCredentialDetail，不含摘要与原文。 |
| 编辑 | 系统管理员；version 最新；非 DELETED | 更新名称、应用、Alias 范围、IP 和到期时间，递增 version，写审计 | 对新鉴权立即生效；版本冲突不覆盖。 |
| 轮换 | 系统管理员；非 DELETED；version 最新；填写原因 | 在单一事务替换 token_prefix、token_hash、hash version，generation+1，写 rotated_at 和审计 | 返回一次性新 Token；旧 Token 在事务提交后立即无效。 |
| 停用 | 系统管理员；status=ACTIVE；version 最新 | enabled=false、status=DISABLED、disabled_at=now，写审计 | 新请求立即返回 ACCESS_TOKEN_INVALID。 |
| 启用 | 系统管理员；status=DISABLED；尚未过期 | enabled=true、status=ACTIVE，写审计 | 已过期返回 ACCESS_CREDENTIAL_EXPIRED。 |
| 删除 | 系统管理员；非 DELETED；填写原因 | 软删除、enabled=false、清除 token_hash、status=DELETED，写审计 | 无法恢复；历史 Trace 使用名称快照。 |
| 查看 Trace | 系统管理员、运维人员 | 无 | 跳转 Trace 列表并带入 access_credential_id。 |

#### 4.5.4.5 Standalone 鉴权流程

Standalone Server 从 Authorization: Bearer 读取 Token，先校验前缀、长度和字符集，再使用 token_prefix 定位候选记录，按 token_hash_version 计算 HMAC 并恒定时间比较。匹配后依次校验 deleted_at、enabled、expires_at、来源 IP 和 requested alias_id。任一身份条件失败统一返回 ACCESS_TOKEN_INVALID；来源 IP 不允许时返回 ACCESS_IP_DENIED；Alias 不在 allowed_alias_ids 时返回 ACCESS_DENIED。

鉴权成功后把 credential_id、application 和权限范围写入 UnifiedRequestContext，创建 Trace 时保存 access_credential_id 和名称快照。last_used_at、last_used_ip 和 last_used_trace_id 在 Trace 创建成功后更新；该更新失败不阻断模型调用，但产生内部告警。鉴权失败不创建业务 Trace，安全日志记录 request_id、token_prefix、失败类别和来源 IP，不记录 Token 原文。

编辑、停用、轮换和删除只影响事务提交后的新请求。已经完成鉴权并进入运行时的 Trace 继续执行；客户端重试会重新鉴权。Standalone Access Credential 不进入 ConfigDraftState、ConfigSnapshot 和发布流程，所有写操作立即生效并生成 AuditLog。

完成标准：Token 只在创建和轮换响应出现一次；旧代次在轮换提交后不可鉴权；Alias 与 IP 范围按请求执行；停用、过期和删除状态准确；任何页面、读取接口、Trace、AuditLog 和日志均无 token_value、token_hash 和 pepper。

### 4.5.5 审计日志页

#### 4.5.5.1 页面筛选与列表

审计页面只读。默认查询最近 24 小时，按 created_at desc，每页 20 条；普通组合查询时间跨度最大 31 天，request_id 或 AuditLog.id 精确查询可以跨 audit_retention_days 内的全部数据。

| 页面区域 | 字段 | 控件或展示形式 | 查询与展示规则 |
|---|---|---|---|
| 筛选区 | start_at、end_at | 日期时间范围 | 普通查询必填，左闭右开。 |
| 筛选区 | audit_id | 单行文本 | 精确匹配，填写后忽略分页和其他业务筛选。 |
| 筛选区 | request_id | 单行文本 | 精确匹配同一管理操作链路。 |
| 筛选区 | operator | 远程搜索多选 | 匹配 operator_id 或 operator_name。 |
| 筛选区 | operator_role | 多选下拉 | 使用操作发生时角色快照。 |
| 筛选区 | operation | 多选下拉 | 取 AuditLog.operation。 |
| 筛选区 | entity_type | 多选下拉 | 配置、运行、安全对象类型。 |
| 筛选区 | entity_keyword | 单行文本 | 匹配 entity_id 或 entity_name。 |
| 筛选区 | source_mode | 多选下拉 | ADMIN_UI、MANAGEMENT_API、SYSTEM_TASK。 |
| 筛选区 | result | 多选下拉 | SUCCEEDED、FAILED。 |
| 筛选区 | error_code | 可输入多选 | 精确匹配。 |
| 筛选区 | client_ip | 单行文本 | 仅系统管理员可用。 |
| 列表 | created_at | 时间 | 页面时区，精确到毫秒。 |
| 列表 | operator_name、operator_role | 文本 | 系统任务显示固定系统身份。 |
| 列表 | operation | 文本 | 高风险操作同时显示已填写原因的存在状态。 |
| 列表 | entity_type、entity_name | 文本链接 | 对象存在且有权限时进入详情。 |
| 列表 | change_summary | 文本 | 只列字段名，不展示值。 |
| 列表 | source_mode | 文本 | 标识页面、API 或系统任务。 |
| 列表 | result、error_code | 文本 | 失败时可按 error_code 继续筛选。 |
| 列表 | duration_ms | 数字 | 服务端操作耗时。 |

#### 4.5.5.2 详情字段与操作

点击行打开 AuditLogDetail。详情从上到下展示 request_id、操作人、角色、source_mode、client_ip、user_agent、operation、operation_reason、对象、before_version、after_version、changed_fields、result、error_code、error_summary、duration_ms 和 created_at。changed_fields 按 field_name 排序，普通字段展示 before_value 与 after_value，敏感字段固定展示 sensitive=true 和空值。

页面操作包括查询、重置、查看详情、复制 request_id、按 operator 或 error_code 继续筛选、进入关联对象。页面不提供编辑、删除、批量清理和包含字段值的导出。系统管理员与运维人员可以查看审计；开发人员和只读人员无入口。

#### 4.5.5.3 写入与事务规则

成功的配置、安全和运行写操作在同一数据库事务中写业务变化与 AuditLog；审计写入失败时业务变化回滚。配置校验、连接检测、发布、草稿撤销、Credential 轮换、熔断人工操作和诊断样本查看都生成审计。一次跨对象操作使用同一 request_id，为每个对象写一条明细审计，并额外写一条汇总对象审计。

业务操作因字段、权限、版本、引用或外部调用失败时，先回滚业务事务，再在独立审计事务中记录 result=FAILED、error_code 和脱敏 error_summary。失败审计事务再次失败时，管理接口保持原业务错误，系统产生高优先级内部告警并记录不含敏感值的安全日志。

CREATE、UPDATE、ENABLE、DISABLE 和 DELETE 记录实际变化字段；CHECK 记录目标、检测模式、结果和 Provider Request ID 是否存在，不记录请求正文；PUBLISH 记录 validation_id、draft_revision、snapshot_no 和实例统计；VIEW_DIAGNOSTIC_SAMPLE 记录 Trace ID 和样本 ID，不复制样本内容。

secret_value、secret_ref 完整值、token_value、token_hash、Authorization、自定义认证头、Provider 原始错误和消息正文不得进入 before_value、after_value、operation_reason、error_summary 和元数据。operation_reason 在写入前执行同一敏感规则扫描。

AuditLog 按 RuntimeConfig.audit_retention_days 保留，最低 365 天。清理任务按 created_at 每批最多 1000 条删除，无法通过页面提前删除。保留期修改按配置发布生效。

完成标准：每个成功管理写操作都有同事务审计；失败操作可通过 request_id 定位；跨对象操作明细完整；敏感字段没有值；审计页面权限、筛选、分页和保留期一致。

### 4.5.6 运行配置管理接口契约

#### 4.5.6.1 草稿、校验与发布接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/config/draft-state | 无 | ConfigDraftState | 可查看角色 | CONFIG_DATA_UNAVAILABLE |
| GET /admin/config/draft-changes/summary | 无 | DraftChangeSummary | 可查看角色 | CONFIG_DATA_UNAVAILABLE |
| GET /admin/config/draft-changes | keyword、entity_type、change_type、modified_by、modified_from、modified_to、page、page_size | PageResult<DraftChange> | 可查看角色 | FIELD_VALIDATION_FAILED、CONFIG_DATA_UNAVAILABLE |
| POST /admin/config/draft-changes/{entityType}/{entityId}/revert | RevertDraftCommand | ConfigDraftState | 系统管理员 | CONFIG_DRAFT_CHANGED、DRAFT_REVERT_BLOCKED、CONFIG_VERSION_CONFLICT |
| POST /admin/config/draft-changes/revert-all | RevertAllDraftCommand | ConfigDraftState | 系统管理员 | CONFIG_DRAFT_CHANGED、CONFIRMATION_TEXT_MISMATCH |
| POST /admin/config/validate | ConfigValidateCommand | ConfigValidationResult 与 array<ConfigValidationIssue> | 系统管理员 | CONFIG_DRAFT_CHANGED、CONFIG_DATA_UNAVAILABLE |
| POST /admin/config/publish | ConfigPublishCommand | PublishRecord | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_VALIDATION_EXPIRED、CONFIG_DRAFT_CHANGED、CONFIG_PUBLISH_IN_PROGRESS、NO_ONLINE_RUNTIME_INSTANCE |
| GET /admin/config/publish-records | start_at、end_at、snapshot_no、status、published_by、keyword、page、page_size | PageResult<PublishRecordListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| GET /admin/config/publish-records/{id} | id | PublishRecordDetail | 可查看角色 | OBJECT_NOT_FOUND |
| GET /admin/config/snapshots/{snapshotNo}/summary | snapshotNo | 不含 content 的 ConfigSnapshot | 系统管理员、运维人员 | OBJECT_NOT_FOUND |
| GET /admin/runtime-instances | status、runtime_mode、application、version、page、page_size | PageResult<RuntimeInstance> | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED |

#### 4.5.6.2 RuntimeConfig 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/runtime-config | 无 | RuntimeConfigDetail | 可查看角色 | CONFIG_DATA_UNAVAILABLE |
| POST /admin/runtime-config/retention-impact | trace_retention_days、usage_retention_days、audit_retention_days、diagnostic_sample_retention_days | RetentionImpactResult | 系统管理员 | FIELD_VALIDATION_FAILED |
| PUT /admin/runtime-config | RuntimeConfigUpdateCommand | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_FIELD_IMMUTABLE、RETENTION_IMPACT_EXPIRED、CONFIG_VERSION_CONFLICT |

#### 4.5.6.3 Standalone Access Credential 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/access-credentials | keyword、application、status、allowed_alias_id、expires_before、has_recent_use、page、page_size、sort | PageResult<AccessCredentialListItem> | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、MODE_NOT_SUPPORTED |
| POST /admin/access-credentials | AccessCredentialCreateCommand | AccessCredentialSecretResult | 系统管理员 | FIELD_VALIDATION_FAILED、OBJECT_REFERENCE_INVALID、MODE_NOT_SUPPORTED |
| GET /admin/access-credentials/{id} | id | AccessCredentialDetail | 系统管理员、运维人员 | OBJECT_NOT_FOUND、MODE_NOT_SUPPORTED |
| PUT /admin/access-credentials/{id} | AccessCredentialUpdateCommand | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、OBJECT_REFERENCE_INVALID、CONFIG_VERSION_CONFLICT |
| POST /admin/access-credentials/{id}/rotate | AccessCredentialRotateCommand | AccessCredentialSecretResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_NOT_FOUND |
| POST /admin/access-credentials/{id}/enable | version | ManagementOperationResult | 系统管理员 | ACCESS_CREDENTIAL_EXPIRED、CONFIG_VERSION_CONFLICT |
| POST /admin/access-credentials/{id}/disable | version、reason | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/access-credentials/{id} | version、reason | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_NOT_FOUND |

#### 4.5.6.4 审计接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/audit-logs | 4.5.5.1 全部筛选、page、page_size、sort | PageResult<AuditLogListItem> | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、AUDIT_DATA_UNAVAILABLE |
| GET /admin/audit-logs/{id} | id | AuditLogDetail | 系统管理员、运维人员 | OBJECT_NOT_FOUND、AUDIT_DATA_UNAVAILABLE |

#### 4.5.6.5 运行实例内部接口

| 方法与路径 | 请求字段 | 成功响应 | 调用方 | 主要错误码 |
|---|---|---|---|---|
| POST /internal/runtime-instances/heartbeat | RuntimeInstanceHeartbeat | RuntimeHeartbeatResponse | 已认证运行实例 | INSTANCE_AUTH_FAILED、INSTANCE_VERSION_INCOMPATIBLE |
| GET /internal/config-snapshots/{snapshotNo} | snapshotNo、expected_checksum | ConfigSnapshot.content 与元数据 | 已认证运行实例 | OBJECT_NOT_FOUND、SNAPSHOT_CHECKSUM_MISMATCH、INSTANCE_VERSION_INCOMPATIBLE |
| POST /internal/publish-records/{publishId}/instances/{instanceId}/reports | InstanceLoadReport | PublishInstanceResult | 已认证运行实例 | OBJECT_NOT_FOUND、INSTANCE_REPORT_CONFLICT |

内部接口使用部署提供的实例服务身份或双向 TLS，与 Standalone Access Credential 隔离。实例只能读取被 Prepare 或当前 ACTIVE 指令引用的快照。RuntimeHeartbeatResponse 的 prepare_command 与 activation_command 不会同时返回；实例优先完成准备和 READY 上报，目标快照进入 ACTIVE 后才接收激活指令。心跳和上报按 instance_id 幂等，reported_at 早于当前结果 updated_at 的旧报告返回 INSTANCE_REPORT_CONFLICT。

#### 4.5.6.6 跨模块事务与安全边界

配置实体写入、DraftChange、ConfigDraftState 修订和成功 AuditLog 保持一个事务。发布激活事务同时更新 ConfigSnapshot 状态、RuntimeConfig.current_snapshot_no、配置草稿和 ConfigDraftState。Standalone Access Credential 使用独立即时事务，不等待发布锁；轮换、停用和删除必须同时提交安全状态与审计。

所有配置读取响应在序列化前移除 ConfigSnapshot.content、Credential 密钥、完整 secret_ref、Standalone token_hash 和 token_hash_version。只有内部实例接口可以读取 ConfigSnapshot.content，内容本身仍不得包含密钥和完整 secret_ref。实例需要模型密钥时，使用快照中的 credential_id 调用进程内 CredentialSecretResolver，从受保护的凭证存储取得数据库密文解密结果或完整 secret_ref，再按 3.6 的 Secret SPI 解析；解析值只存在于受控内存。AccessCredentialSecretResult 设置 Cache-Control: no-store，管理页面禁止再次请求原文。

## 4.6 开发接入

开发接入按照使用者的接入路径组织：先在接入说明页确认运行形态、Alias、依赖和认证，再使用在线测试验证协议与 Trace；随后分别定义 Java SDK、Spring Boot Starter、Embedded Admin UI 和 Standalone Server 的开发契约。统一 HTTP API 与 Provider SPI 的底层协议在 4.7 独立展开，供客户端、服务端和 Adapter 实现共同使用。

### 4.6.1 接入说明页

New API 通过统一 base_url、Bearer Token、`GET /v1/models` 和 `POST /v1/chat/completions` 降低现有客户端的接入成本。轻享 AI V1.0 采用相同的基础入口，并在响应中增加 light_ai 追踪字段；接入页围绕“确认连接方式—选择 Alias—复制示例—测试调用—查看 Trace”的页面顺序组织。参考依据为 [New API 使用 API](https://docs.newapi.pro/zh/docs/guide/feature-guide/user/api) 和 [New API 模型列表](https://docs.newapi.pro/zh/docs/api/ai-model/models/list/listmodels)。

#### 4.6.1.1 页面顺序与进入规则

用户从左侧“开发接入—接入说明”进入页面。页面从上到下依次展示运行形态与连接信息、Model Alias 选择器、依赖与配置示例、同步/异步/流式调用示例、请求字段说明、在线测试面板、测试结果和常见错误。首次进入默认选择按 alias asc 排序的第一个可用 Alias；URL 中携带 alias_id 时优先选择该 Alias，无权限或已停用时回退到第一个可用项并给出字段级提示。

系统管理员和运维人员可以查看全部已发布 Alias；开发人员只查看 application_scope 允许的 Alias；只读人员可以查看协议字段和脱敏示例，但不能执行测试调用。Standalone 页面永不读取访问凭证原文，所有 Token 位置固定显示 `lai_your_token`。系统管理员需要真实 Token 时进入 4.5.4 创建或轮换页面。

#### 4.6.1.2 页面字段

| 页面区域 | 字段 | 数据来源 | 展示与交互规则 |
|---|---|---|---|
| 连接信息 | runtime_mode | DeveloperAccessContext | SDK、Embedded 或 Standalone。 |
| 连接信息 | api_base_url | DeveloperAccessContext | Standalone 可复制；Embedded 显示“进程内调用”；SDK 显示“本地 Runtime”。 |
| 连接信息 | authentication_type | DeveloperAccessContext | NONE、HOST_CONTEXT 或 BEARER_TOKEN，并链接到对应配置说明。 |
| 版本信息 | sdk_version、server_version | DeveloperAccessContext | 用于生成依赖版本和检查主版本兼容。 |
| 配置信息 | current_snapshot_no | DeveloperAccessContext | Embedded 与 Standalone 可复制并进入发布记录。 |
| 模型选择 | selected_alias_id | DeveloperAccessContext | 远程搜索 available_models；切换后刷新全部示例。 |
| 模型摘要 | display_name、support_stream、support_system_message、context_window、max_output_tokens | UnifiedModelItem.light_ai | 直接来自当前活动快照，不展示 Provider 和 Credential。 |
| 示例条件 | sample_type | CodeSampleRequest | 依赖、配置、同步、异步、流式或 HTTP。 |
| 示例条件 | build_tool | CodeSampleRequest | Maven 或 Gradle，仅依赖示例显示。 |
| 示例内容 | language、content、placeholders | CodeSampleResult | 代码只读，可一键复制；占位符在代码下方列出。 |
| 请求说明 | 统一模型请求的主要字段 | 2.6.10 | 展示页面调用涉及的类型、必填、范围和默认来源。 |
| 错误说明 | code、HTTP 状态、retryable、说明 | 4.7.3 | 支持按 code 搜索，不展示内部堆栈。 |

#### 4.6.1.3 示例生成规则

依赖示例根据 build_tool 返回 `light-ai-client`、`light-ai-runtime` 或 `light-ai-spring-boot-starter` 的当前稳定版本。SDK 形态生成 LocalRuntimeDefinition 构建入口和同步、异步、流式 Java 调用；Embedded 形态生成 Spring 配置与注入 LightAiClient 的示例；Standalone 形态生成 Spring Standalone Client 配置、原生 Java Client 和 cURL 示例。

示例中的 model 使用用户当前选择的 Alias。base_url 可以使用部署公开地址，access_token、数据库密码、Provider Credential 和 secret_ref 始终使用占位符。示例不写入用户剪贴板，只有点击复制后由浏览器执行复制；复制操作不产生 AuditLog，但记录不含示例内容的页面行为指标。

#### 4.6.1.4 在线测试面板

测试面板字段依次为 model、可选 system_message、user_message、stream、temperature、top_p 和 max_tokens。默认 stream=true；输出区按 StreamEvent.sequence 追加内容，并同时展示 trace_id、Provider、Provider Model、finish_reason、Usage、Cost 和总耗时。正文只保存在当前浏览器页面状态，刷新或关闭页面后清除。

测试请求通过当前管理身份建立 UnifiedRequestContext。开发人员使用宿主或产品权限中的 application_scope；系统管理员和运维人员的测试 application 记录为 `ADMIN_CONSOLE`，并在 Trace.tags 写入 `light_ai_test=true` 和 operator_id。身份完成后进入与业务调用相同的 Alias 解析、路由、容量、可靠性、Provider、Usage 和 Trace 链路。测试接口不使用 Standalone Access Credential，因此不能用于验证某个 Token 的 Alias 或 IP 限制。

| 操作 | 权限与前置条件 | 服务端处理 | 页面结果与异常 |
|---|---|---|---|
| 切换 Alias | 当前角色可见 | 读取 DeveloperAccessContext 并重新生成示例 | Alias 失效时清空测试输入之外的上下文。 |
| 复制连接地址 | api_base_url 有值 | 无服务端变化 | 复制规范化 HTTPS 地址。 |
| 复制示例 | 示例已生成 | 无服务端变化 | 保留原有换行和缩进。 |
| 发起非流式测试 | 有测试权限；字段合法 | 创建 Trace 并执行统一调用 | 返回 ApiTestResult.response；错误展示 code、message 和 trace_id。 |
| 发起流式测试 | 有测试权限；字段合法 | 建立 SSE 并执行统一调用 | 逐块渲染；结束后显示 Usage 与 Trace 链接。 |
| 取消测试 | 当前测试未结束 | 关闭 SSE 或取消调用上下文 | Trace=CANCELLED，停止追加内容。 |
| 查看 Trace | 已创建 trace_id 且有权限 | 跳转 Trace 详情 | 身份校验前失败时无入口。 |

完成标准：页面顺序可以指导新接入方完成一次调用；每个请求字段均可查到类型与限制；所有示例可直接编译或在替换占位符后执行；测试调用与 Trace 数据一致；页面和前端日志无 Token、Provider Credential 与测试消息正文。

### 4.6.2 Java SDK


## 在线测试接口的输入与流类型

FE-050使用 GET /admin/developer-access/code-sample。FE-051提交ApiTestCommand（model、system_message、user_message、stream=false及可空采样参数），FE-052使用 POST /admin/developer-access/test/chat/stream并令stream=true。管理测试读取StreamEvent SSE，按event与sequence处理START/DELTA/USAGE/DONE；错误为UnifiedErrorEnvelope，不能用/v1的choices解析器。准确字段与例外见BACKEND_PLAN“开发接入DTO的精确契约”。

## 检测记录展示

FE-009/014/016和Trace详情允许PROVIDER_CHECK来源无Alias、无Route Candidate，以“模型检测”展示来源；仍显示真实模型、用量和有权可见的凭证掩码。业务请求的Alias缺失仍按错误处理。Provider类型和表单能力选项读取bootstrap.adapters，不自行猜测Adapter范围。
