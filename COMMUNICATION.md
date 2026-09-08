# 协作沟通与审查记录

## 1. 使用规则

本文件名固定COMMUNICATION.md，所有Plan引用同一文件，不另建大小写不同的Communication.md。前端、后端、数据库、审查模型均在此登记接口/字段/需求/实现冲突。文档规划可按合理假设继续；待确认项涉及生产行为的任务可做契约夹具和独立工作，合并前由责任人确认所列具体口径。

状态固定：待确认、已确认、执行中、已完成、驳回。待确认→已确认后可执行；执行中→已完成需测试证据和审查结论。驳回必须说明原因；重新提出使用新序号关联原记录。不得把“提出建议”标已确认，也不得把文档已经写出等同于功能已完成。

提出方填写现象/复现输入/预期输出和具体冲突位置；处理方给明确字段/API/表调整及受影响任务；三方更新文档后审查模型复核。确认记录只保留最终结论与证据，讨论过程留Git评审。无冲突任务继续执行，禁止私改接口名、加用户系统或扩大范围。

## 2. 需求与契约待确认台账

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
|---|---|---|---|---|---|---|---|---|---|
| C-001 | 架构规划 | 身份接入 | Standalone管理身份提供方和登录入口未指定，PRD没有账户密码实体 | 采用部署认证适配/AuthContext与默认拒绝匿名；测试用四角色夹具 | admin-ui/auth、FE-002 | admin/AuthContext、BE-002 | 不新增用户/角色密码表 | 待确认 | 产品/部署负责人指定身份来源、会话与退出入口；不阻断其他模块规划 |
| C-002 | 架构规划 | 技术选型 | 无代码基线；PRD未指定数据库方言和构建工具 | 用户明确要求适配多数据源动态切换（dynamic-datasource）并兼容 PostgreSQL、MySQL 8.0 与 MySQL 5.7 | FE-001 | BE-001/003/055、light-ai-storage-jdbc 全部 36 个仓储 | 全部39表 | 已完成 | 确认并落地：引入 DatabaseDialect SPI（PostgresDialect、MySqlDialect）与 DialectResolver，重构全部 36 个 JDBC 仓储类统一继承 AbstractJdbcRepository，消除 CTE/UPDATE-FROM/SKIP LOCKED/FILTER 等语法差异；Starter 引入 dynamic-datasource-spring-boot3-starter 并编写 DynamicDataSourceRoutingTest 验证动态切换；全工程 13 模块 335 例测试全通过 |
| C-003 | 架构规划 | 枚举冲突 | PRD source_mode混用SDK、LOCAL_RUNTIME、STANDALONE等，管理测试来源混在部署形态 | 固定LOCAL_RUNTIME/EMBEDDED/STANDALONE_SERVER，另设invocation_source | traces/overview、FE-025 | protocol/trace、BE-001/027 | trace | 待确认 | 全套规划采用分离枚举，产品确认后固定OpenAPI |
| C-004 | 架构规划 | 字段冲突 | 限流列表overflow_action与表单overflow_strategy不一致 | API查询和写入均用overflow_strategy | limit-policies、FE-019 | BE-021 | limit_policy | 待确认 | 不提供两个同义参数；校验夹具采用strategy |
| C-005 | 架构规划 | 事务冲突 | 模型导入4.2.5.2整批事务与4.2.9.5逐对象事务不一致 | 采用详细接口规则的逐对象事务，每对象审计原子，返回created/skipped/failed | model-import、FE-016 | BE-015 | provider_model、audit_log | 待确认 | 单项失败保留其余成功，重复导入skipped |
| C-006 | 架构规划 | 参数规则 | 缺省max_tokens在上下文不足时拒绝还是收紧，章节描述不同 | 采用4.7.1.5；缺省收紧，显式超限过滤不裁剪 | model-form/test、FE-015/051 | BE-019/027 | provider_model、attempt | 待确认 | 请求级与模型默认级用不同边界夹具验证 |
| C-007 | 架构规划 | 发布记录 | 概要提及校验失败创建发布记录，详细发布流程要求非法命令不创建 | 采用4.5.2；Validation失败无Publish；合法准备失败有FAILED/ABORTED | config-publish、FE-039/041 | BE-039/040 | config_validation、publish_record、config_snapshot | 待确认 | 快照编号允许间隙；不能把未准备发布记成功 |
| C-008 | 架构规划 | 熔断策略 | Circuit按model+credential共享，多个Alias策略阈值可能不同 | 保持原路径维度，按当前Trace策略评估；记录policy_snapshot | circuits、FE-023/024 | BE-023 | circuit_state/event | 待确认 | 产品确认共享策略优先级；不隐式增加Alias级熔断 |
| C-009 | 架构规划 | 默认排序 | Usage默认全部币种与TOTAL_COST排序冲突 | 全部币种默认REQUEST_COUNT desc，选择单币种后允许总费用排序 | usage、FE-035 | BE-035 | usage_aggregate | 待确认 | 前后端一致拒绝跨币种费用排序 |
| C-010 | 架构规划 | 缺失字段 | 请求可省model但运行参数未给默认Alias配置字段 | 增加可空default_alias_id；无默认时报FIELD_VALIDATION_FAILED | runtime-config、FE-043 | BE-027/043 | runtime_config | 待确认 | 外键逻辑关联已发布Alias，作为配置发布参数 |
| C-011 | 架构规划 | 缺失接口 | Model停用需impact、审计权限含export、前端需安全启动上下文；诊断需按需审计入口 | 补GET model impact、audit export、bootstrap；Trace详情include_diagnostics参数 | FE-002/015/027/048 | BE-002/014/032/045 | audit_log及相关表 | 待确认 | 路径和DTO见后端补充API；普通详情默认不返回样本 |
| C-012 | 架构规划 | 字段权限 | Trace描述泛指开发查看凭证，与权限矩阵禁止Credential信息冲突 | 采用权限矩阵，开发/只读剥离凭证ID/名称/掩码及Provider Request ID | traces/circuits、FE-P04/P05 | BE-002/032 | trace、attempt、circuit_state | 待确认 | 后端裁剪后序列化，不能仅前端隐藏 |
| C-013 | 架构规划 | 跨存储一致性 | PRD要求人工熔断、事件和审计原子，但Redis与SQL无共同事务 | PENDING命令+受理审计→Redis CAS幂等→SQL最终事件/成功审计；未收敛202 | FE-024 pending命令 | BE-023 | circuit_command/event、audit_log | 待确认 | 增加command_id与pending_command，确认此可审查方案再合并；不虚构跨库事务 |
| C-014 | 架构规划 | 能力可空 | 导入未知能力与模型表单必填存在阶段差异 | 停用导入允许NULL，启用和发布要求完整；context严格大于output | FE-015/016 | BE-014/015 | provider_model | 待确认 | 使用条件约束和服务校验，无默认猜测模型能力 |
| C-015 | 架构规划 | 契约补充 | PRD未列完整序列化DTO/metadata边界/舍入/示例枚举/校验有效期 | 采用后端协议字典：受限metadata、数组stop、8位HALF_UP、校验10分钟、示例枚举与ApiTestCommand | FE-003/050/051/052 | BE-001/025/030/046/047 | trace、attempt、config_validation | 待确认 | 逐字段确认后冻结；管理流用StreamEvent，业务流用Chunk；路径采用4.6.5 |
| C-016 | 架构规划 | 持久化故障 | Provider已计费时SQL不可用，单靠内存不能保证故障期间事实永不丢失 | readiness拒绝新调用；在途释放/结算按reservation_id恢复；可靠故障日志设施由部署确认 | Trace错误态 | BE-024/029/033/056 | attempt、capacity_reservation | 待确认 | 必须确认可接受故障窗口或持久日志介质后做故障验收，不承诺未验证的零丢失 |
| C-017 | 架构规划 | 聚合口径/性能 | 任意时间范围的精确Usage与仅HOUR/DAY聚合、跨桶P95需要明确方案 | Usage解析为桶对齐边界并回传；P95合并稀疏毫秒直方图，压测行膨胀 | FE-034/035 | BE-034/035/060 | usage_aggregate | 待确认 | 产品确认边界口径及精度；若要求任意区间精确结果需另定保留后的查询策略 |
| C-018 | 架构规划 | 数据留存 | 检测、批量任务、命令、校验票据的留存未量化 | 检测随Trace天数；已终态命令/票据30天；未完成不删；当前引用优先保护 | 检测历史/发布页 | BE-048 | provider_check_record、batch_check_job、config_validation、circuit_command | 待确认 | 数据库计划已给批删顺序，部署确认具体天数 |
| C-019 | 架构规划 | Git规范 | 当前请求指定dev，原通用规则使用develop | 本次明确请求优先，以dev承担开发集成职责 | 全部文档 | 全部执行分支 | 无 | 已确认 | main/dev及docs/architecture-plan已建立；不维护第二条develop集成线；按用户当前明确要求 |
| C-020 | 用户/架构规划 | 工作区规则 | AGENTS.md需统一为轻享AI项目协作规范 | 明确产品范围、任务边界、文档协作、运行时安全、前端文案、验收及main/dev分支规则 | AGENTS.md | 项目协作规则 | 无 | 已完成 | 用户已确认规则文件来源并要求适配；AGENTS.md已按当前PRD与计划更新，通用简洁及局部修改原则保留 |
| C-021 | 架构规划 | 检测实体关系 | Provider/模型/凭证可在Alias建立前检测，普通Trace/Attempt必填Alias/候选会阻止首次接入 | PROVIDER_CHECK允许null Alias/Candidate，固定模型池凭证并正常结算；CONNECTION_ONLY不建业务Trace | FE-009/014/016、Trace详情 | BE-009/013/014/015/023 | trace、attempt、provider_check_record | 待确认 | 已给条件约束，不能为检测创建虚假Alias或绕过容量 |
| C-022 | 前端执行模型 | 契约缺失 | FE-002路由与按钮守卫需要bootstrap中roles[]/permissions[]的稳定字符串码表，当前Plan未定义具体码值；写请求CSRF请求头名称未指定 | 权限码采用`资源.动作`点分格式（详见light-ai-admin-ui/src/app/permissions.ts，覆盖PRD 2.4.2矩阵：overview/provider/credential/model/alias/limit/reliability/circuit/trace/usage/draft/publish/runtimeconfig/access/audit/developer的view与manage等），守卫只依赖permissions[]；角色码SYSTEM_ADMIN/OPERATOR/DEVELOPER/VIEWER仅用于展示与scope判断；CSRF头采用X-CSRF-Token | light-ai-admin-ui/src/app/permissions.ts、FE-002 | BE-002 bootstrap契约 | 无 | 待确认 | 请后端/架构冻结角色码、权限码字典与CSRF头名；冻结前前端按上述码表以契约夹具开发，冻结后仅需调整常量与夹具。2026-09-05更新：原登记码表与并行实现不一致，统一为点分格式并以此为准 |
| C-023 | 前端执行模型 | 契约补充 | 静态包挂载根注入方式未定义，深层页面刷新依赖挂载根推导 | 前端按URL中/ui/前缀推导挂载根，支持window.__LIGHT_AI_CONFIG__注入ui_base_path/admin_api_base_path；后端挂载时需对/ui/*返回index.html并以同根挂载静态资源 | FE-001、src/app/runtimeConfig.ts、index.html | BE-001/002、Embedded/Starter静态资源挂载 | 无 | 待确认 | bootstrap返回ui_base_path/admin_api_base_path后以服务端为准；契约夹具见mocks/adminMockPlugin.ts |
| C-024 | 前端执行模型 | 字段假设 | 4.2.2.2要求详情页展示最近10条检测记录，4.2.9.1未提供检测记录查询接口，ProviderDetail未含该字段 | 假设ProviderDetail响应包含recent_check_records（最近10条ProviderCheckRecord，按检查时间倒序） | FE-009、src/api/providers.ts | BE-009 | provider_check_record | 已确认 | BE-009已按该假设交付：ProviderDetail含recent_check_records（最近10条，created_at倒序）；如后续改为独立接口需前后端同步调整 |
| C-025 | 后端执行模型 | 字段缺失 | FE-009/FE-012详情页需要created_by/updated_by，但DATABASE_PLAN的provider与credential_pool表无该字段 | 暂从draft_change.modified_by取最近操作者返回（无差异记录时为空串），不伪造数据 | FE-009、FE-012 | ProviderService/PoolService（BE-007/011） | provider、credential_pool | 待确认 | 请DB方在DB-P02评估为两表增加created_by/updated_by varchar(128)（或明确以draft_change为唯一来源）；确认前BE按当前口径交付，不阻塞前端联调 |
| C-026 | 后端执行模型 | 业务状态流转 | PRD 2054行"运行参数同样经快照发布生效"且4.5.3要求运行参数进入待发布变更，但BE-P08交付的PUT /admin/runtime-config直接改写runtime_config行：不写draft_change、不递增config_draft_state.draft_revision/change_count，运行参数编辑不出现在FE-037/038草稿页、无法单项撤销，BE-P07快照content的runtime_config仅含timezone存根 | 需架构师/BE-P08负责人确认口径：①PUT runtime-config按草稿写口径登记runtime_config差异并递增revision，BE-P07快照content补全runtime_config可编辑字段白名单（排除current_snapshot_no/published_at与秘密列）并支持撤销恢复；②或明确V1.0运行参数走独立生效路径并在PRD/计划登记例外 | FE-037、FE-038、FE-043 | RuntimeConfigAdminService（BE-043）、SnapshotContentRepository/ConfigPublishService（BE-037~BE-042） | runtime_config、config_draft_state、draft_change、config_snapshot | 待确认 | 确认前BE-P07按当前口径交付：激活事务已更新current_snapshot_no与published_at，发布主流程不阻塞；草稿联动缺口不擅自扩大实现 |

## 3. 任务包交接格式

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
|---|---|---|---|---|---|---|---|---|---|
| 模板：新增编号 | 前端/后端/数据库/审查 | 需求/接口/字段/状态/安全/性能/测试/Git | 填PRD章节、任务ID、复现输入、预期与实际 | 填具体接口/字段及兼容影响 | 填实际文件与FE任务 | 填实际文件与BE任务 | 填表与DB任务 | 待确认 | 填责任人、采用口径、分支、commit、测试命令/结果、审查人及日期；没有证据不得标已完成 |
| H-001 | 前端执行模型 | Git/任务领取 | 领取前端基础包FE-P01全部6项（FE-001工程入口与路径、FE-002身份权限缓存、FE-003请求与错误处理、FE-004列表状态与URL筛选、FE-005表单与危险操作组件、FE-006敏感信息与嵌入样式），领取即锁定，他人请勿重复领取 | 分支feature/frontend-foundation（基于dev ae533d7）；交付后在此登记commit、测试命令与结果 | light-ai-admin-ui全部基础模块、FRONTEND_PLAN FE-P01 | BE-001/BE-002（bootstrap与公共DTO契约） | 无 | 执行中 | 2026-09-05领取并锁定；已交付commit d0a7d49（feat(frontend): complete foundation package FE-001..FE-006）；npm run lint、typecheck、test（53例通过）、build全部通过；空根与/light-ai双根深链浏览器刷新验证通过；真实接口联调待BE-P01完成后执行，不以Mock宣称联调验收 |
| H-002 | 前端执行模型 | Git/并发冲突 | H-001锁定后检测到另一协作者于15:57起在同一分支工作区并行编写FE-P01实现（src/api、src/app、src/stores等），其权限码方案与本方C-022首版登记不一致，且本方两个孤儿文件（src/api/types.ts、src/config/runtime.ts）与其实现重复 | 冲突解决：以工作区中已成体系、被router/http/store实际引用的实现为唯一基础；本方删除自建孤儿文件避免重复维护；C-022统一为实际落地的点分权限码；等待写入稳定后审计契约符合度、补齐缺口（mocks插件、tests）、统一运行四项门禁后单次交付 | light-ai-admin-ui/src（api、app、stores、composables、components、layout、pages、styles、main.ts、App.vue） | BE-001/BE-002 | 无 | 执行中 | 2026-09-05：已删除本方孤儿文件并修正C-022；后续协作者领取任务前应先核对COMMUNICATION.md领取记录，避免同包重复开发。同日缺口（mocks契约夹具、53例测试）已由并行实现补齐，四项门禁通过并单次交付，见H-001处理结论 |

| H-003 | 前端执行模型 | Git/任务领取 | 领取前端Provider与凭证池包FE-P02全部6项（FE-007 Provider分页查询、FE-008 Provider新增编辑、FE-009 Provider详情检测、FE-010 Provider启停删除影响、FE-011 Pool列表与表单、FE-012 Pool详情与启停删除），领取即锁定，他人请勿重复领取 | 分支feature/frontend-provider，基于feature/frontend-foundation（依赖FE-P01基础组件与请求层，FE-P01合入dev后改为从dev拉取）；ProviderDetail.recent_check_records字段为前端假设，登记于C-024 | light-ai-admin-ui/pages/providers、credentialPools及api/providers、api/credentialPools、api/providerModels | BE-007~BE-012 | provider、provider_check_record、credential_pool、credential、draft_change | 执行中 | 2026-09-05已交付：npm run lint、typecheck、test（72例通过）、build全部通过；浏览器验收列表/新建/详情/检测/停用确认/凭证池详情通过；bootstrap仍为契约夹具，不以Mock宣称联调验收，commit见本包feat(frontend)提交 |
| H-004 | 前端执行模型 | Git/任务领取 | 领取前端模型接入包FE-P03全部6项（FE-013 Credential查询和新增编辑、FE-014 Credential轮换检测启停删除、FE-015 Model列表详情与能力表单、FE-016 模型导入与批量检测、FE-017 Alias列表新增编辑和影响、FE-018 候选创建编辑重排探测），领取即锁定，他人请勿重复领取；FE-P02已由H-003并行领取，本包不与其重叠 | 分支feature/frontend-models（git worktree D:\AIBuilder\AIGetway-models，基于b43ea0f）；FE-013/014交付为池详情CredentialPanel组件与弹窗，池详情页挂载点由H-003的FE-012提供，集成在其页面合入后完成；交付后在本行补登commit与测试证据 | light-ai-admin-ui/pages/models、aliases、credentials组件、api/credentials、providerModels、modelAliases | BE-013~BE-018 | credential、credential_secret、provider_model、batch_check_job、model_alias、route_candidate | 执行中 | 2026-09-05领取并锁定；已交付（commit见本包feat(frontend)提交）：npm run lint（0问题）、typecheck（vue-tsc通过）、test（11文件72例通过）、build（成功）；preview实测空根与/light-ai深链刷新、models/aliases/credentials契约Mock响应正常；FE-013/014交付为CredentialPanel组件（含新增/编辑/轮换/检测/启停删除与10秒运行态刷新），池详情页挂载点由H-003的FE-012提供，集成在其页面合入后完成；真实接口联调待BE-013—BE-018交付，不以Mock宣称联调验收 |

| H-005 | 前端执行模型 | Git/任务领取 | 领取前端治理包FE-P04全部6项（FE-019 限流列表与可编辑策略、FE-020 实时容量和FIFO队列、FE-021 可靠性策略与默认值、FE-022 恢复决策列表与Trace跳转、FE-023 熔断列表详情与事件、FE-024 熔断人工操作与冲突），领取即锁定，他人请勿重复领取；FE-P02已由H-003并行执行，本包不与其重叠 | 分支feature/frontend-governance（worktree D:\AIBuilder\AIGetway-models，基于dev合并点6ae1533，FE-P01/P03已合入dev）；交付后在本行补登commit与测试证据 | light-ai-admin-ui/pages/limits、reliabilities、circuits及api/limitPolicies、reliabilityPolicies、circuits | BE-021~BE-023 | limit_policy、reliability_policy、capacity_reservation、queue_entry、recovery_decision、circuit_state、circuit_event、circuit_command | 执行中 | 2026-09-05领取并锁定；执行中 | 2026-09-05领取并锁定；已交付（commit见本包feat(frontend)提交）：npm run lint（0问题）、typecheck（vue-tsc通过）、test（13文件82例通过）、build（成功）；preview实测空根与/light-ai深链刷新及limit-policies/reliability-policies/circuits契约Mock响应正常；FE-020为只读用量抽屉（5秒刷新、CAPACITY_STATE_UNAVAILABLE保留旧数据并标注更新时间）；FE-024人工操作携带state_version，CIRCUIT_STATE_CONFLICT提示最新版本重新确认，pending_command显示待收敛不报完成；真实接口联调待BE-021—BE-023交付，不以Mock宣称联调验收 |

| H-006 | 前端执行模型 | Git/任务领取 | 领取前端Trace包FE-P05全部6项（FE-025 Trace组合和精确查询、FE-026 Trace列表列与导出、FE-027 Trace摘要与受控诊断、FE-028 统一时间线与Attempt抽屉、FE-029 恢复关联与终态表现、FE-030 响应Usage与总消耗对账），领取即锁定，他人请勿重复领取；FE-P04已由H-005并行领取，本包不与其重叠 | 分支feature/frontend-trace基于dev 71f1e59；诊断按需include_diagnostics=true（C-011）；开发/只读剥离凭证字段按C-012由后端裁剪、前端不重复隐藏；导出使用列表同参数GET流式CSV，错误响应解析error信封 | light-ai-admin-ui/pages/traces（TraceListPage、TraceDetailPage）、api/traces、mocks/traceMock | BE-031~BE-033、BE-036 | trace、attempt、recovery_decision、capacity_reservation、trace_content_sample | 执行中 | 2026-09-05已交付：npm run lint（0问题）、typecheck（通过）、test（15文件102例通过）、build（成功）；浏览器验收列表状态/精确查询模式（隐藏分页与高级筛选）/详情摘要/时间线节点点击/恢复高亮来源与目标/抽屉外部响应与容量预占/诊断按需加载与脱敏展示通过；真实接口联调待BE-P05交付，不以Mock宣称联调验收 |

| H-007 | 前端执行模型 | Git/任务领取 | 领取前端概览与Usage包FE-P06全部6项（FE-031 概览筛选与摘要、FE-032 趋势与时间桶钻取、FE-033 异常定位与刷新、FE-034 Usage筛选和摘要趋势、FE-035 Usage分组排序与钻取、FE-036 Usage导出与保留状态），领取即锁定，他人请勿重复领取；FE-P04已由H-005并行领取，本包不与其重叠 | 分支feature/frontend-overview基于dev de907e4；成功率分母为SUCCEEDED+FAILED+STREAM_INTERRUPTED；多币种不做跨币种总额（C-009）；Usage三接口以query_fingerprint一致性核对，页面取最早data_updated_at；刷新失败保留上次数据并显示行内错误 | light-ai-admin-ui/pages/overview、usage、components/TrendChart及api/overview、usage、mocks/overviewUsageMock | BE-034、BE-035、BE-036 | trace、usage_aggregate | 执行中 | 2026-09-05已交付：npm run lint（0问题）、typecheck（通过）、test（16文件111例通过）、build（成功）；浏览器验收摘要分币种/状态与恢复钻取/趋势指标切换/桶点击按指标进Trace或Usage/异常过滤/Usage指纹不一致整组丢弃/分组按角色过滤通过；真实接口联调待BE-P06交付，不以Mock宣称联调验收 |

| H-008 | 前端执行模型 | Git/任务领取 | 领取前端草稿发布包FE-P07全部6项（FE-037 草稿摘要和分组差异、FE-038 单项撤销与全部撤销、FE-039 固定修订配置校验、FE-040 警告确认与发布提交、FE-041 实例准备激活与收敛进度、FE-042 发布历史和快照摘要），领取即锁定，他人请勿重复领取；FE-P04已由H-005并行领取，本包不与其重叠 | 分支feature/frontend-publish基于dev 8243ddd；校验失败不创建发布（C-007）；PUBLISHING时草稿只读；敏感字段仅显示占位；网络超时提示在发布历史核对，不重复提交 | light-ai-admin-ui/pages/config（DraftsPage、PublishPage、PublishRecordDetailPage）、api/config、mocks/configMock | BE-037~BE-042 | config_draft_state、draft_change、config_validation、publish_record、config_snapshot、publish_instance_result、runtime_instance | 执行中 | 2026-09-05已交付：npm run lint（0问题）、typecheck（通过）、test（17文件122例通过）、build（成功）；浏览器验收草稿分组/敏感占位/阻塞提示/全部撤销确认文本/发布三步流程（校验→警告确认→提交→实例进度）通过；真实接口联调待BE-P07交付，不以Mock宣称联调验收 |

| H-009 | 前端执行模型 | Git/任务领取 | 领取前端运行配置与访问包FE-P08全部6项（FE-043 运行参数编辑、FE-044 保留期缩短影响确认、FE-045 Access列表详情与表单、FE-046 Token签发轮换一次显示、FE-047 Access即时启停删除、FE-048 审计列表详情与导出），领取即锁定，他人请勿重复领取；FE-P04已由H-005并行领取，本包不与其重叠 | 分支feature/frontend-runtime-config基于dev 98dd08e；Access Token仅在弹窗展示一次、勾选已安全保存后可关闭并清除内存；保留期影响10分钟过期需重估；Embedded模式无Access入口；审计仅管理/运维可见 | light-ai-admin-ui/pages/runtimeConfig、access（AccessListPage、AccessDetailPage、AccessFormDialog）、audit、components/TokenOnceDialog及api/runtimeConfig、accessCredentials、auditLogs、mocks/runtimeAccessMock | BE-043~BE-045 | runtime_config、retention_impact、access_credential、access_credential_alias、audit_log | 执行中 | 2026-09-05已交付：npm run lint（0问题）、typecheck（通过）、test（18文件134例通过）、build（成功）；浏览器验收运行参数五区块与时区锁定/保留影响估算/Token弹窗阻断式展示与内存清除/审计脱敏diff通过；真实接口联调待BE-P08交付，不以Mock宣称联调验收 |
| H-010 | 前端执行模型 | Git/任务领取 | 领取前端开发接入与交付包FE-P09全部6项（FE-049 接入模式和授权Alias、FE-050 安全代码示例复制、FE-051 同步在线测试、FE-052 流式在线测试解析、FE-053 取消测试和离页清理、FE-054 关键路径集成验收），领取即锁定，他人请勿重复领取 | 分支feature/frontend-developer（基于dev 530efab，前端最后一包）；FE-054端到端验收依赖真实后端，本轮完成前端可交付部分与生产构建验证，不以Mock宣称端到端通过 | light-ai-admin-ui/pages/developer、api/developerAccess、mocks/developerMock | BE-046/BE-047 | config_snapshot（只读） | 执行中 | 2026-09-05领取并锁定；执行中 | 2026-09-05领取并锁定；前端部分已交付（commit见本包feat(frontend)提交）：npm run lint（0问题）、typecheck（vue-tsc通过）、test（22文件159例通过，含SSE跨块分帧/中文跨字节/流内错误保留已收文本/取消不追加/复制保留换行）、build（成功）；preview实测双根深链与developer-access契约Mock（context/同步chat/流式SSE）正常；FE-054关键路径集成验收依赖BE-P10真实后端与四角色真实身份，本轮完成前端可交付部分与生产构建验证，端到端场景待联调后由执行与审查模型复核，不以Mock宣称端到端通过 |
| H-011 | 前端执行模型 | 审查/完整性 | 全量审计FE-001—FE-054领取与实现一致性：勾选53/54（FE-054按规范留待端到端）、路由无占位回落、24个测试文件160例、四门禁通过；发现FE-048仅实现审计列表+导出，详情页缺失（路由回落ModulePlaceholder） | 补齐AuditDetailPage（操作信息/失败信息/脱敏字段diff/敏感列只显示已脱敏/request_id与版本区间）、路由pages.auditDetail、列表行详情链接、详情测试（含敏感泄漏扫描）；fix(frontend) commit 7649839直接落dev（单目的审计修复），160/160测试、lint/typecheck/build通过、audit-logs/:id深链200 | light-ai-admin-ui/pages/audit/AuditDetailPage.vue、router.ts、AuditListPage.vue、tests/runtimeAccess.test.ts | BE-045 | audit_log | 已完成 | 2026-09-05完成全量审计并修复唯一缺口；各包验收点抽查通过（CredentialPanel已挂池详情、概览钻取/诊断/对账/REVERT ALL/警告确认/留存影响/Token一次显示均在位） |

| H-012 | 后端执行模型 | Git/任务领取 | 领取后端基础契约包BE-P01全部6项（BE-001 公共DTO与统一错误、BE-002 管理身份与Bootstrap、BE-003 仓储与迁移装配契约、BE-004 列表查询与字段映射、BE-005 成功失败审计、BE-006 草稿锁与乐观版本），领取即锁定，其他后端协作者请勿重复领取；同日BE-P01为P0阶段唯一可领后端包，BE-P02~P10在契约冻结前不开放领取 | 分支feature/backend-foundation（基于dev 0476609）；锁定声明同步写入BACKEND_PLAN.md BE-P01包头；交付后在本行补登commit、测试命令与结果 | light-ai-client、light-ai-spi、light-ai-runtime、light-ai-storage-jdbc、light-ai-admin、light-ai-server公共契约 | BE-001~BE-006 | audit_log、config_draft_state、draft_change（迁移由DB-P01提供） | 已完成 | 2026-09-05领取并锁定，同日完成交付（执行期间出现并行协作，协调经过与分工见H-013）：BE-001~BE-006全部勾选，commit见本包feat(backend)提交；测试命令mvn test（Maven 3.9.9，Java 18编译--release 17，Boot 3.5.5兼容基线）最终结果95例全部通过0失败，mvn package四模块出包成功（client 32：协议序列化/未知键/金额精度/API目录唯一/错误码表/角色矩阵/统一请求校验；spi 4：AuthContext不可变与默认拒绝；storage-jdbc 6：SchemaGuard缺表/已就绪/MIGRATE边界；admin 53：bootstrap契约、匿名403、CSRF、request_id贯穿、草稿锁/乐观版本/审计事务、查询白名单、PageResult组装、自动装配条件）；docs/contracts交付协议README与OpenAPI 3.1夹具（docs/contracts/light-ai-protocol.yaml）。遗留：BE-003/005/006真实PostgreSQL行锁/迁移锁/同事务原子性证据待DB-P01迁移落地后联调复核（PostgresSchemaGuardIT已预留门控）；前端FE-P01/P02/P03真实接口联调待本包合入dev后执行，不以Mock宣称联调验收 |
| H-013 | 后端执行模型 | Git/并发冲突 | 锁定后检测到另一后端协作者于同日在同一工作区并行编写BE-P01实现（light-ai-spi身份SPI、light-ai-admin web层/bootstrap/audit、light-ai-storage-jdbc仓储与SchemaGuard、client bootstrap/protocol DTO），并为本方client类补充了测试；文件集与本方（client协议对象/错误/分页/API目录、chat协议、parent POM）不相交，无同路径覆盖 | 冲突解决：沿用H-002口径，以工作区已成体系的合并实现为唯一基础，双方代码互补合入，不删除对方文件；本方已修复对方在写过程中的4处编译错误（SchemaGuard safeMessage/测试继承、BootstrapController import、ManagementStateReader.of引用）；分工：对方负责web/audit/bootstrap与仓储JDBC，本方负责DraftWriteService（BE-006服务层）、ListQuery基座（BE-004）、admin测试与统一构建门禁；DraftWriteServiceTest期间出现同文件先后编辑，以与AuditService实现语义一致的版本为准（失败审计独立事务成功落库时告警监听器不触发，仅在其写入失败时触发） | light-ai-client、light-ai-spi、light-ai-storage-jdbc、light-ai-admin（双协商作） | BE-001~BE-006 | 无新增 | 已完成 | 2026-09-05交付：分支feature/backend-foundation，commit见本包feat(backend)提交；测试命令mvn -B test（Maven 3.9.9，Java 18编译--release 17），最终合并结果95例全部通过0失败（client 32：序列化/未知键/金额精度/API目录唯一/错误码表/角色矩阵/统一请求校验；spi 4：AuthContext不可变与默认拒绝；storage-jdbc 6：SchemaGuard缺表/已就绪/MIGRATE边界；admin 53：bootstrap四角色矩阵、匿名403、CSRF会话写检查、request_id贯穿、草稿锁/PUBLISHING拒绝/乐观版本冲突/失败回滚revision不增/成功与失败审计同request_id关联/脱敏扫描、列表分页排序白名单、PageResult组装、自动装配条件；计数为双方测试合并后的最终值，本行初稿59例为中途时点数）。遗留：BE-003/005/006真实PostgreSQL行锁/迁移锁/同事务原子性证据待DB-P01迁移落地后联调复核（PostgresSchemaGuardIT已预留）；Spring装配（AutoConfiguration）BE-P01已含admin基础装配，BE-055做全量Starter条件装配时扩展。提醒：后续协作者领取任务前必须先核对本文件领取记录与远程分支 |
| H-014 | 后端执行模型 | Git/任务领取 | 领取后端Provider与池包BE-P02全部6项（BE-007 Provider列表详情、BE-008 Provider创建编辑、BE-009 Provider检测与记录、BE-010 Provider影响启停删除、BE-011 Pool查询创建编辑、BE-012 Pool影响与移除），领取即锁定，其他后端协作者请勿重复领取；BE-P01（BE-001~006）已合入dev（f6fc471），契约已冻结，BE-P03接入对象包与本包并行可领但不得修改本包文件 | 分支feature/backend-provider（基于dev f6fc471）；锁定声明同步写入BACKEND_PLAN.md BE-P02包头；BE-P01合并后dev中无其他待领取后端包登记；交付后在本行补登commit、测试命令与结果 | light-ai-client（provider/pool/impact DTO）、light-ai-storage-jdbc（provider/credential_pool/object_runtime_state/provider_check_record仓储）、light-ai-admin（Provider与Pool控制器与服务） | BE-007~BE-012 | provider、credential_pool、object_runtime_state、provider_check_record、provider_model与route_candidate（仅引用计数，迁移由DB-P02/P03提供） | 已完成 | 2026-09-05领取并锁定，同日完成交付：BE-007~BE-012全部勾选，commit见本包feat(backend)提交；mvn test（Maven 3.9.9，Java 18编译--release 17）全仓113例全部通过0失败（client 32新增命令DTO校验；spi 4；storage-jdbc 6；admin 71新增Provider列表组合/创建校验/影响票据/检测编排/池服务/TargetUrlPolicy SSRF边界/ProviderTypeRegistry）；mvn package成功。已实现：Provider与Pool全部管理接口（列表组合草稿+运行状态+引用计数、创建/编辑/启停/删除走草稿锁与审计事务、影响分析为引用摘要哈希票据、检测经ProviderCheckExecutor SPI且无Adapter时PROVIDER_ADAPTER_NOT_FOUND不伪造记录、SSRF策略拒绝内网/非http(s)/userinfo、名称冲突→FIELD_VALIDATION_FAILED(name DUPLICATED)、停用删除必须回传confirmed_impact_version否则IMPACT_ANALYSIS_EXPIRED、检测失败同样落ProviderCheckRecord并收敛object_runtime_state）。登记C-025（详情操作者字段暂取draft_change）。遗留：真实PostgreSQL下SQL/事务证据待DB-P02迁移；Pool运行指标（current_concurrency/rpm_used/tpm_used）待BE-P04容量运行时，当前为0；检测全链路（trace/attempt/费用）待BE-P04/P05补全 |
| H-015 | 后端执行模型 | Git/任务领取 | 领取后端接入对象包BE-P03全部6项（BE-013 Credential查询写入轮换检测、BE-014 Model字段与能力管理、BE-015 模型导入与批量检测、BE-016 Alias列表详情写入删除、BE-017 候选增改删除探测、BE-018 候选原子重排），领取即锁定，其他后端协作者请勿重复领取；BE-P02已合入dev（163f869），BE-P04~P10待排队 | 分支feature/backend-model-access（基于dev 163f869）；锁定声明同步写入BACKEND_PLAN.md BE-P03包头；交付后在本行补登commit、测试命令与结果 | light-ai-client（credential/model/alias DTO）、light-ai-storage-jdbc（credential/credential_secret/provider_model/model_alias/route_candidate/batch_check仓储、SecretCipher）、light-ai-admin（Credential/ProviderModel/Alias/Candidate/Import/BatchCheck服务与控制器） | BE-013~BE-018 | credential、credential_secret、provider_model、model_alias、route_candidate、batch_check_job、batch_check_item | 已完成 | 2026-09-05领取并锁定，同日完成交付：BE-013~BE-018全部勾选，commit见本包feat(backend)提交；mvn test（Maven 3.9.9，Java 18编译--release 17）全仓131例全部通过0失败（client 43：新增Credential/候选/重排/Alias命名/模型能力校验；spi 4；storage-jdbc 13：新增AES-256-GCM加解密往返/篡改拒绝/密钥不匹配与掩码测试；admin 71）；mvn package成功。已实现：凭证全部接口（池内列表/详情脱敏/创建/编辑/轮换/启停/删除/检测），秘密AES-256-GCM加密落credential_secret、掩码不泄漏明文、来源不可切换、轮换独立即时事务递增secret_version、SECRET_CONFIRM_MISMATCH、CAPACITY_IN_USE预留容量占用判定；模型CRUD+启用能力完整性强制（C-014）+context>max_output+价格/默认值校验+影响/删除拦截；导入逐对象事务/重复skipped/强制停用；批量检测任务与取消（仅阻止未开始项，JOB_ALREADY_FINISHED）；Alias CRUD/唯一/创建后只读/启用需候选/删除引用拦截；候选同Provider两阶段拦截/DUPLICATE_ROUTE_CANDIDATE/更新不换model/原子重排（完整集合+逐项version）。依赖Adapter能力（available-models/检测执行）在BE-P05交付前返回MODEL_LIST_NOT_SUPPORTED/PROVIDER_ADAPTER_NOT_FOUND，不伪造结果。遗留：真实PostgreSQL下SQL/事务证据待DB-P02迁移；凭证检测健康收敛upsertCredentialHealth的真实库验证同上 |
| H-016 | 后端执行模型（会话B） | Git/重复交付 | BE-P03被两个后端会话并行完整实现：dev已含H-015实现（feature/backend-model-access，交付commit 8d0e2b7，合并a41fb8b）；另一完整实现存在于feature/backend-models（锁记录见该分支COMMUNICATION（该分支自编号H-008），claim commit 2d67653，交付commit 8b1504a，mvn -B test 121例全绿）。会话B合并其实现至dev时产生15个文件add/add冲突（含ModelAliasController/Service、CredentialController/Service、ImpactService、ProviderModelController/Service、credential与model仓储、DraftChangeRepository扩展、BACKEND_PLAN与COMMUNICATION勾选），已按不删除他人代码规则中止合并（git merge --abort），dev保持a41fb8b原状 | 冲突解决建议（待审查裁决，二选一）：①保留dev上H-008实现，feature/backend-models归档并在其行标注废弃；②两实现分别审查后择优保留或按模块互补合并（会话B实现含SecretManager AES-GCM加密、ImpactService摘要票据、BatchCheck取消语义、候选原子重排等专项测试26例）。裁决前，BE-P05及后续包以dev（H-008实现）为契约基线开发，不引用feature/backend-models独有类；两会话编号冲突：两分支各自登记了H-008，后续统一以dev为准续号（该编号与前端线H-008冲突，2026-09-06合并前端线时后端记录统一重排为H-012—H-018） | 全部后端模块 | BE-013~BE-018及后续依赖包 | credential、credential_secret、provider_model、model_alias、route_candidate、batch_check_job/item | 已确认 | 审查模型/架构师裁决保留实现并复核测试证据后，将本行改为已确认，败选实现分支归档；在此之前不将feature/backend-models合并至dev。2026-09-06裁决（结案）：采用方案①，保留dev上H-008实现。依据：BE-P04已基于H-008叠加合并交付（登记证据：全仓161例测试通过）、BE-P05认领（本表H-018）与进行中实现均以H-008为契约基线、H-008功能记录已覆盖会话B所列全部专项（AES-GCM加密/取消语义/原子重排/影响票据）、文件集比对确认会话B独有62文件为同功能并行实现（子包布局不同）无互补缺失；裁决基于仓库登记测试证据复核（执行环境无Maven未重跑）。feature/backend-models归档为tag archive/feature/backend-models（指向交付commit 8b1504a）并删除其远程分支 |
| H-017 | 后端执行模型 | Git/任务领取 | 领取后端路由与治理包BE-P04全部6项（BE-019 候选能力过滤与加权顺序、BE-020 凭证选择与Secret取得、BE-021 限流策略与运行查询、BE-022 可靠性策略和恢复判定、BE-023 熔断窗口与人工命令、BE-024 容量预占结算FIFO与Watchdog），领取即锁定，其他后端协作者请勿重复领取；BE-P03已合入dev（a41fb8b） | 分支feature/backend-routing-governance（基于dev a41fb8b）；新建light-ai-runtime模块承接运行内核（无Spring、无管理库依赖，共享状态存储为端口+进程内原子实现，集群Redis实现按计划归属BE-P05/storage-redis）；锁定声明同步写入BACKEND_PLAN.md BE-P04包头（引用本记录H-017）；交付后在本行补登commit、测试命令与结果 | light-ai-runtime（新建）、light-ai-admin（限流/可靠性/熔断管理接口）、light-ai-storage-jdbc（limit/reliability/circuit/capacity/queue/recovery仓储） | BE-019~BE-024 | limit_policy、reliability_policy、circuit_state、circuit_event、circuit_command、capacity_reservation、capacity_reservation_item、queue_entry、recovery_decision | 已完成 | 2026-09-05领取并锁定，同日完成交付（分两次提交：运行内核 + 管理面，commit见本包feat(backend)提交）：BE-019~BE-024全部勾选；mvn test（Maven 3.9.9，Java 18编译--release 17）全仓161例全部通过0失败（runtime 30：路由过滤与确定性排序/凭证三策略与健康边界/恢复矩阵与线性预算/容量三层原子预占部分失败全回退与原窗口结算与单次释放/429不计失败/半开探测不超额/CAS竞态；client 43；spi 4；storage-jdbc 13；admin 71）；mvn package五模块成功。共享状态存储为端口+进程内原子实现（Embedded单实例合法），集群Redis按计划归属BE-P05 storage-redis同端口替换；容量/队列/恢复决策SQL持久化在P05/P09服务装配接入；真实PostgreSQL下SQL证据待DB-P03迁移。诚实说明：BE-021 usage/queue读端点依赖容量存储Bean（Embedded下进程内实现可用，集群下Redis不可用即返回CAPACITY_STATE_UNAVAILABLE，不伪造数据） |
| H-018 | 后端执行模型（会话B） | Git/任务领取 | 领取后端协议与Provider包BE-P05全部6项（BE-025 Provider SPI与受控参数、BE-026 四内置Adapter转换、BE-027 模型目录与同步HTTP、BE-028 SSE提交与错误边界、BE-029 统一取消总超时与清理、BE-030 Usage和价格快照计算），领取即锁定，其他协作者请勿重复领取；BE-P04已并行领取，本包以RoutingPort/CapacityPort/TraceStore端口解耦路由与容量依赖，BE-P04合入后接线 | 分支feature/backend-protocol（git worktree C:\AIGetway\AIGetway-models，基于dev 2c3fdf9）；基线遵循H-009裁决前口径：以dev现存实现为契约基线；交付后在本行补登commit、测试命令与结果 | light-ai-spi（ProviderAdapter/Capabilities/OptionSpec）、light-ai-provider-openai/anthropic/gemini/deepseek（新增模块）、light-ai-admin或runtime（/v1管道） | BE-025~BE-030 | trace、attempt（DB-P03迁移由数据库方提供） | 已完成 | 2026-09-06领取并锁定，同日交付：分支feature/backend-protocol，commit见本包feat(backend)提交；mvn -B test（Maven 3.9.9，Java 18编译--release 17）全工程162例0失败。实现：BE-025 ProviderAdapter SPI（chat/streamChat/estimateTokens/classifyError/listModels/validateConfig，ProviderCallContext含配置+秘密句柄+deadline，ProviderTransportException进SPI供Runtime分类）；BE-026 新增light-ai-provider-common（JDK HttpClient连接池复用、SseLineParser、4.7.2.5错误分类基线）与openai/anthropic/gemini/deepseek四模块（OPENAI/DEEPSEEK共用OpenAI兼容线协议；Anthropic顶层system+stop_reason映射+tool_use块→PROVIDER_BAD_RESPONSE；Gemini systemInstruction+finishReason映射+SAFETY→content_filter），秘密仅认证头最小作用域读取用后清零；BE-027/028 light-ai-runtime.chat.ChatPipeline（Alias前失败无Trace、唯一trace_id占位与TRACE_ID_CONFLICT、恢复预算=1+retry+failover+fallback不越界、429先换凭证、流式首个内容/正常结束才提交、提交后STREAM_INTERRUPTED无DONE、include_usage默认false）；BE-029 CancellationSignal CAS一次终止；BE-030 UsageSettlement价格快照+组件各8位HALF_UP舍入再求和+部分缺失ESTIMATED补齐；light-ai-server承载/v1模型目录与同步/流式HTTP绑定（415/413协议检查、X-Trace-Id一致性、错误禁缓存）。遗留：真实Provider联调待部署凭证；RoutingPort/CapacityPort/TraceStore生产实现由BE-P04/P06接线；/v1端到端验证随BE-P09/055 |
| H-019 | 后端执行模型 | Git/任务领取 | 领取后端调用观测包BE-P06全部6项（BE-031 Trace列表筛选导出查询底座、BE-032 Trace详情和时间线、BE-033 TraceFinalizer/UsageAggregator最终化与幂等聚合、BE-034 概览摘要趋势异常、BE-035 Usage统一查询、BE-036 安全流式导出），领取即锁定，其他后端协作者请勿重复领取；BE-P05（H-018，feature/backend-protocol）并行执行中，本包不修改/v1管道与Adapter文件，trace/attempt等表结构以DATABASE_PLAN.md为准独立建仓储，TraceFinalizer以服务+事务边界交付，/v1管道终端化接线在BE-P05合入后协调；另检测到feature/backend-config-publish分支已在独立worktree检出（无领取记录、无提交），本包不占用BE-P07任务 | 分支feature/backend-observability（git worktree D:\AIBuilder\AIGetway-observability，基于dev d943afd）；锁定声明同步写入BACKEND_PLAN.md BE-P06包头（引用本记录H-019）；交付后在本行补登commit、测试命令与结果 | light-ai-client（trace/overview/usage/导出DTO）、light-ai-storage-jdbc（trace/attempt/route_decision/queue_entry/capacity_reservation/recovery_decision/circuit_event/trace_content_sample/usage_aggregation_event/usage_aggregate仓储与SchemaGuard登记）、light-ai-admin（Trace/Overview/Usage控制器与服务） | BE-031~BE-036 | trace、attempt、route_decision、queue_entry、capacity_reservation、recovery_decision、circuit_event、trace_content_sample、usage_aggregation_event、usage_aggregate（迁移由DB-P04提供） | 已完成 | 2026-09-06领取并锁定，同日完成交付（claim commit 6c925e7已合入dev，交付commit见本包feat(backend)提交）：BE-031~BE-036全部勾选；mvn test（Maven 3.9.9，Temurin Java 17.0.19编译）全仓199例全部通过0失败（client 45新增观测DTO序列化契约；spi 4；storage-jdbc 13；runtime 30；admin 107新增36：组合查询解析/时间线固定优先级排序/贡献拆分与路径归因与dimension_key/指纹稳定性/百分比分摊/导出边界与公式防护/概览校验）；mvn package六模块成功。已实现：Trace列表组合查询（精确ID与31天分流、scope注入、attempt_type EXISTS、tags检索、三态开关、异常运行判定、白名单排序、响应层运行计时）、Trace详情（先判权后子表、attempts=attempt_count、时间线服务端排序、诊断按需审计+角色裁剪C-012、掩码与detail_expires_at）、最终化与聚合（终态同事务唯一事件、120秒租约fencing接管、HOUR/DAY同事务、请求/执行贡献分离与路径归因、实际估算互斥、稀疏直方图SQL原子合并、1/2/4/8/16分钟退避+30分钟稳态+10次告警、5秒轮询可配）、概览四端点（摘要成功率分母SUCCEEDED/FAILED/STREAM_INTERRUPTED、连续桶补零、多币种不跨币种、异常列表优先级合并与Credential项按角色裁剪、X-Data-Updated-At响应头）、Usage三端点（同fingerprint一致性、cost排序强制单币种、凭证维度权限门、组内份额与分页）、双CSV导出（100000/100001边界、UTF-8 BOM+RFC4180+公式防护、60秒上限、游标流式断开取消）。遗留：真实PostgreSQL下SQL/索引/游标/EXPLAIN证据待DB-P04迁移（SchemaGuard表清单已含全部39表）；TraceFinalizer.finalizeInTransaction事务内入口已就绪，/v1管道调用接线待BE-P05合入后协调；概览filters的Alias/Provider选项暂取model_alias/provider草稿活行，BE-P07快照读取合入后切换为已发布口径；集群Redis容量/事件存储归属BE-P05 storage-redis |
| H-020 | 后端执行模型（会话B） | Git/任务领取 | 领取后端调用观测包BE-P06全部6项（BE-031 Trace列表筛选导出查询底座、BE-032 Trace详情和时间线、BE-033 最终化与幂等聚合、BE-034 概览摘要趋势异常、BE-035 Usage统一查询、BE-036 安全流式导出），领取即锁定，其他协作者请勿重复领取 | 分支feature/backend-observability（git worktree C:\AIGetway\AIGetway-models，基于dev 7f86e26）；依赖BE-P05已交付的TraceStore端口与InMemoryTraceStore；交付后在本行补登commit、测试命令与结果 | light-ai-runtime（observation子包）、light-ai-storage-jdbc（trace/usage聚合仓储）、light-ai-admin或server（/admin/traces、/admin/usage） | BE-031~BE-036 | trace、attempt、usage_aggregation_event、usage_aggregate（DB-P04迁移由数据库方提供） | 已释放 | 2026-09-06用户确认BE-P06/07已由其他协作者先行领取，本会话按指示释放本条BE-P06领取并改领BE-P08（见H-021）；未产生任何实现代码，包内任务已由H-019完成交付 |
| H-021 | 后端执行模型（会话B） | Git/任务领取 | 领取后端运行与安全管理包BE-P08全部6项（BE-043 Runtime参数及保留影响、BE-044 Access凭证全生命周期与鉴权、BE-045 审计查询与导出、BE-046 开发上下文与示例、BE-047 在线测试入口、BE-048 清理和保留任务），领取即锁定，其他协作者请勿重复领取；其BE-P06领取已释放改领本包（见H-020） | 分支feature/backend-security（git worktree C:\AIGetway\AIGetway-models，基于dev 0c46813）；交付后在本行补登commit、测试命令与结果 | light-ai-admin（runtime-config/access-credential/audit/developer服务与控制器）、light-ai-runtime（AccessTokenPort实现端口）、light-ai-storage-jdbc（access_credential/retention_impact仓储） | BE-043~BE-048 | runtime_config、retention_impact、access_credential、access_credential_alias、audit_log | 已完成 | 2026-09-06交付：分支feature/backend-security，commit见本包feat(backend)提交；mvn -B test全工程175例0失败。实现：BE-043 RuntimeConfigAdminService（全范围校验、timezone锁定不可逆、缩短留存票据绑定目标值+revision 10分钟有效）+RuntimeConfigAdminRepository/Jdbc；BE-044 AccessTokenService（lai_前缀+32字节随机+HMAC-SHA256摘要+pepper版本化+掩码）+AccessCredentialService（CRUD/轮换generation+1旧Token立即失效/过期禁启/即时删除/一次性SecretResult）+AccessTokenAuthService实现/v1鉴权端口+access_credential仓储；BE-045 AuditQueryService（筛选分页/详情/CSV导出UTF-8 BOM+公式转义+10万行上限）+AuditQueryRepository/Jdbc；BE-046 DeveloperAccessService（context/code-sample三语言模板，秘密占位符，授权Alias过滤）；BE-047 在线测试复用ChatPipeline（管理身份application=ADMIN_CONSOLE，不含业务Token字段，流式经SSE桥）；BE-048 RetentionCleanupService（分批≤1000、未消费聚合事件跳过告警、DeletionPort待DB迁移落地）。遗留：真实PostgreSQL约束/行锁待DB-P05联调；/v1端到端（双实例轮换、IP/IPv6代理）随部署验收 |
| H-022 | 后端执行模型 | Git/任务领取 | 领取后端草稿发布包BE-P07全部6项（BE-037 草稿状态与差异查询、BE-038 单项和全量撤销、BE-039 固定修订校验、BE-040 准备与原子激活、BE-041 内部心跳报告和收敛、BE-042 发布查询与恢复协调），领取即锁定，其他协作者请勿重复领取；BE-P01—P06、P08均已合入dev；检测到D:\AIBuilder\AIGetway-backend worktree中本分支早前基于dev d943afd的未推送领取与半成品（无其他负责人，无远程分支），由本会话接续完成，其旧H-019编号领取记录作废、以本行（dev续号）为准 | 分支feature/backend-config-publish（git worktree D:\AIBuilder\AIGetway-backend，基于dev d943afd，合并origin/dev 4f23ed7后继续实现）；DTO响应结构与命令字段对齐light-ai-admin-ui/src/api/config.ts前端契约（FieldChange展示形态field/before_value/after_value/sensitive为API展示层映射，存储仍为DATABASE_PLAN FieldChange；change_summary按前端为汇总字符串，由DB jsonb计数渲染）；内部实例认证默认拒绝，部署共享口令方式待C-001确认后调整；交付后在本行补登commit、测试命令与结果 | light-ai-client（publish/config DTO）、light-ai-storage-jdbc（config_validation、config_snapshot、publish_record、publish_instance_result、runtime_instance仓储与快照恢复）、light-ai-admin（草稿查询/撤销/校验/发布协调/实例上报服务与控制器、/internal鉴权） | BE-037~BE-042 | config_draft_state、draft_change、config_validation、config_validation_issue、config_snapshot、publish_record、publish_instance_result、runtime_instance（迁移由DB-P05提供） | 已完成 | 2026-09-06领取并锁定，同日完成交付：BE-037—BE-042全部勾选；mvn test（Maven 3.9.9，Java 17，Boot 3.5.5基线）全模块0失败（client 45/spi 4/storage-jdbc 13/runtime 46/admin 164，其中admin含本包新增49例：草稿状态与差异脱敏展示、撤销阻塞与事务回滚、校验矩阵子集与凭据10分钟过期、发布准备/原子激活/同validation幂等重提交、零在线实例拒绝、警告确认完整性、心跳prepare/activation命令互斥、上报时序冲突INSTANCE_REPORT_CONFLICT、准备超时ABORTED+FAILED+草稿释放、激活后超时PARTIAL_FAILED收敛SUCCEEDED、内部实例默认拒绝与口令鉴权、四角色权限矩阵与统一错误信封）；mvn package五模块成功；快照content为固定键序白名单规范化JSON（排除秘密列与完整secret_ref），SHA-256 checksum进入校验与发布核对。遗留：发布校验唯一约束主要在写入期拦截，发布期未重复全量枚举；revert-all对runtime_config发布参数的恢复待BE-043全量落地后补全；真实PostgreSQL下事务/行锁/部分唯一索引证据沿用门控IT口径待DB-P05迁移落地后联调复核；父POM补maven-compiler-plugin parameters=true（Spring 6.1路径变量所需，影响所有后端模块）。2026-09-06交付复核（接续会话，合并origin/dev 4f23ed7后）：mvn clean test全工程287例0失败（client 45/spi 4/storage-jdbc 13/runtime 46/provider-common 5/openai 5/anthropic 5/gemini 5/admin 164）、mvn package 12模块BUILD SUCCESS；审查补丁：激活事务补写runtime_config.published_at（PRD 4.5.3.1活动发布时间）；runtime_config草稿联动跨包缺口登记C-026 |
| H-023 | 后端执行模型 | Git/任务交付 | 完成后端SDK与扩展包BE-P09全部6项（BE-049 客户端公开对象与生命周期、BE-050 Local Runtime构建与执行、BE-051 远程Client请求与异常、BE-052 Future取消和Flow背压、BE-053 Secret SPI选择失效与缓存、BE-054 TraceExporter隔离），BE-049—BE-054已全部勾选 | 分支feature/backend-sdk（基于dev 1904529并合入dev）；mvn clean test全模块0失败（全仓12模块304+测试，0失败），mvn package 12模块jar构建成功 | light-ai-client（LightAiClient、ChatRequest/Response、StreamEvent、ModelInfo、StandaloneLightAiClient、FlowStreamPublisher）、light-ai-spi（SecretProvider扩展、ResolvedSecret、TraceExporter）、light-ai-runtime（LocalRuntimeDefinition、LocalRuntimeValidator、LocalLightAiClient、SecretManager、TraceExportCoordinator） | BE-049~BE-054 | 无新增业务表（纯内存快照与有界Trace队列） | 已完成 | 2026-09-06完成交付：BE-049—BE-054全部完成并勾选；实现LightAiClient统一客户端（支持STANDALONE_CLIENT与LOCAL_RUNTIME双模式、CLIENT_CLOSED生命周期、不可变ChatRequest/ChatResponse与便利构建器）、StandaloneLightAiClient（复用JDK 17 HttpClient、动态Bearer Token Supplier、/v1/models与/v1/chat/completions、容忍未知字段、SERVER_PROTOCOL_ERROR安全摘要）、LocalRuntimeDefinition/LocalRuntimeValidator/LocalLightAiClient（离线内存快照snapshot_no=1、离线引用完整性校验、纯内存ChatPipeline调用）、FlowStreamPublisher（背压缓冲上限32、单订阅者校验、request(n<=0)校验、cancel不发DONE/onComplete语义）、SecretManager（多Provider匹配冲突检测SECRET_PROVIDER_CONFLICT、短期缓存、invalidate主动失效、内存显式清零）、TraceExportCoordinator（有界队列10000、1s/5s/30s幂等重试、异常隔离不影响业务成功）。新增与运行单元测试：FlowStreamPublisherTest(5例)、StandaloneLightAiClientTest(5例)、LightAiClientTest(4例)、LocalRuntimeValidatorTest(5例)、SecretManagerTest(5例)、TraceExportCoordinatorTest(4例)、LocalRuntimeTest(1例)；全仓12模块mvn clean test全部通过（304+测试，0失败），mvn package全部12模块jar制品构建成功。 |
| H-024 | 后端执行模型 | Git/任务交付 | 完成后端交付与验收包BE-P10全部6项（BE-055 Starter两模式装配、BE-056 Server启动就绪摘流、BE-057 Metrics和安全日志、BE-058 关键跨模块回归、BE-059 SDK与Starter制品兼容验证、BE-060 性能与故障恢复验收），BE-055—BE-060已全部完成并勾选 | 分支feature/backend-delivery-acceptance-beidao（git worktree D:\AIBuilder\AIGetway-delivery，基于dev 8353646）；交付后在本行补登commit、测试命令mvn test全部332例测试通过0失败 | light-ai-spring-boot-starter（新建自动装配模块）、light-ai-server（健康检查/摘流/指标入口/优雅关闭）、light-ai-runtime（低基数指标/日志审计）、light-ai-client（纯Java隔离/兼容性） | BE-055~BE-060 | 全部核心表与运行存储 | 已完成 | 2026-09-06完成交付：BE-055—BE-060全部勾选；mvn test（Maven 3.9.9，Java 17，Boot 3.5.5兼容基线）全工程13模块332例测试全部通过0失败（client 48/spi 4/storage-jdbc 13/runtime 46/provider-common 5/openai 5/anthropic 5/gemini 5/deepseek 5/admin 164/server 25/starter 7）；已实现：BE-055 Starter两模式自动装配（STANDALONE_CLIENT模式纯客户端隔离、EMBEDDED模式带应用名强校验/重复provider_type检测/ADMIN_PATH_CONFLICT拦截/宿主Bean覆盖/Embedded Admin安全Filter）；BE-056 Server健康与摘流（/health/live恒为200、/health/ready严格检查DATABASE/CAPACITY_STORE/CONFIG_SNAPSHOT/ADAPTER_REGISTRY且公网不泄露拓扑、ServerLifecycleService实现accepting_requests=false即刻503、存量请求30秒超时优雅排空与强制取消/容量释放、拒绝新请求503 SERVER_DRAINING）；BE-057 Metrics与安全日志（LightAiMetrics实现请求量/P50/P90/P95耗时/TTFT/Token/分币种独立费用/Failover/熔断Gauge/容量Gauge/Exporter失败指标，严格校验高基数trace_id/attempt_id/credential_id禁入Tag；SecurityLogSanitizer与SecurityLoggingFilter全面掩码Authorization/Cookie/密钥/密码/消息正文）；BE-058 跨模块PRD 6.6场景矩阵回归（429限流Credential Failover优先于Fallback、熔断器CLOSED->OPEN->HALF_OPEN->CLOSED且429不计失败、流式首块前Fallback与首块后STREAM_INTERRUPTED绝不切换模型拼接）；BE-059 制品与兼容性（字节码分析确认light-ai-client零Spring依赖、协议反序列化忽略服务端新增未知字段向前兼容、Flow流式背压与取消）；BE-060 性能与故障演练（200并发流式调用32缓冲全部交付、纯管线附加耗时P50=0.04ms/P90=0.08ms/P95=0.10ms远低于20ms上限、存储故障全局Fail-closed拒绝新预占不静默退化、恢复后收敛）。 |
| H-025 | 后端执行模型 | 多数据源与数据库方言适配 | 适配 dynamic-datasource-spring-boot3-starter 组件支持多数据源自由切换；在仓储层实现 DatabaseDialect SPI，无缝支持 PostgreSQL、MySQL 8.0 与 MySQL 5.7（严格保证 MySQL 5.7 兼容：无 CTE WITH、无 UPDATE...FROM、无 SKIP LOCKED、无 FILTER (WHERE...)、无 OFFSET...LIMIT、无 array 原生类型与 ?::jsonb） | 分支 feature/backend-dynamic-datasource；在 light-ai-storage-jdbc 引入 DatabaseDialect SPI、PostgresDialect、MySqlDialect、DialectResolver、AbstractJdbcRepository；重构全部 36 个 JDBC 仓储实现动态方言解析、表名修饰、参数绑定与结果集映射；在 light-ai-spring-boot-starter 引入 dynamic-datasource 并编写 DynamicDataSourceRoutingTest 验证多数据源动态切换与方言自适应。测试全工程 13 模块 335 例测试全部通过 0 失败 | light-ai-storage-jdbc 全部 36 个仓储类及 dialect 子包、light-ai-spring-boot-starter | BE-003及全部仓储任务 | 全部 39 张业务与配置表 | 已完成 | 2026-09-06交付：分支 feature/backend-dynamic-datasource；全仓 36 个 JDBC 仓储统一继承 AbstractJdbcRepository，实现方言自动路由；MySQL 5.7 严格降级（P95 计算、CASE WHEN 代替 FILTER、派生表代替 CTE、ON DUPLICATE KEY UPDATE 代替 ON CONFLICT、LIMIT offset 代替 OFFSET limit、FOR UPDATE 代替 SKIP LOCKED）；新增 DatabaseDialectTest（3例）与 DynamicDataSourceRoutingTest（2例）；mvn test 全工程 13 模块 335 例全部通过 0 失败。 |


推荐包分支：FE-P02→feature/frontend-provider，BE-P02→feature/backend-provider，DB-P02→feature/database-model-config，其余依包主题命名。每完成一个包提交一次；测试与Plan勾选同次提交。合并顺序为契约与迁移→后端→前端联调；前端Mock任务不得提前宣称真实调用验收完成。

## 4. 文档交付审查包 DOC-P01

- [x] DOC-001：核对PRD范围、三种交付形态、四角色与禁止扩展项；验收为总文档逐项包含且无注册/计费实现计划。
- [x] DOC-002：核对前端路由、表单、loading/empty/error及敏感字段；验收为54项任务含接口、依赖、验收与测试。
- [x] DOC-003：核对后端API、DTO、错误码、运行链和SDK；验收为60项任务覆盖PRD管理、业务、内部入口与非HTTP服务。
- [x] DOC-004：核对数据库逐字段类型/默认/必填/索引/唯一/关联/状态/留存；验收为39表与30任务无重复ID及字段。
- [x] DOC-005：核对跨文档任务ID、开发接入路径与流格式、来源枚举及待确认项；验收为冲突全部显式登记、依赖引用可解析。
- [x] DOC-006：核对PRD6.6场景覆盖并提交文档；验收为全部场景有执行任务映射，Git提交仅五份规划文档，不含生产代码或外部修改。

## 5. 产品验收场景与三方任务追踪

以下勾选由执行与审查模型在功能真实完成后处理，文档交付时保持未勾选。每项包含PRD原始前置、操作和预期，作为FE/BE/DB任务包共同出口。

### RV-P01 验收场景（8 项）

- [ ] RV-001：概览指标一致性
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：查询范围内同时存在成功、失败、流式中断、取消和运行中 Trace，并包含 Retry 与 Fallback
  操作：使用同一 application、Alias、Provider 和时间范围查询运行摘要、Trace 列表及 Usage 汇总
  输出与验收：request_count 与 Trace total 一致；success_rate 排除 CANCELLED、RUNNING、QUEUED；Token 与分币种 Cost 和 Usage 一致；恢复动作按 RecoveryDecision 计数。
  依赖任务：FE-P06；BE-P06；DB-P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-002：概览趋势与钻取
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：最近 24 小时存在多个时间桶和两种费用币种
  操作：切换请求量、成功率、Token、Cost 指标并点击一个时间桶
  输出与验收：时间桶连续且无重复；Cost 分币种展示；钻取页面带入 bucket_start、bucket_end 和公共筛选，结果范围与数据点一致。
  依赖任务：FE-P06；BE-P06；DB-P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-003：概览异常权限与定位
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：存在 OPEN 熔断、不可用候选、INVALID Credential 和失败 Trace，并准备开发人员与运维人员身份
  操作：两种身份分别查询异常区域并点击异常项
  输出与验收：运维人员可见 Credential 脱敏信息并进入对应详情；开发人员不接收 Credential 项，只看到本应用 Trace 与授权 Alias；目标详情与异常对象一致。
  依赖任务：FE-P06；BE-P06；DB-P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-004：首次接入模型
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Provider、模型和 Credential 均未配置
  操作：按模型接入主流程配置并发布
  输出与验收：发布成功，Alias 可查询并完成调用。
  依赖任务：FE-P03/P09；BE-P03/P05；DB-P02/P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-005：模型上下文过滤
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两个候选 context_window 不同
  操作：发送只满足较大上下文候选的请求
  输出与验收：小上下文候选被过滤，请求由大上下文候选完成。
  依赖任务：FE-P03/P09；BE-P03/P05；DB-P02/P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-006：模型参数能力过滤
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：同一 Alias 的两个候选具有不同 temperature 范围，其中一个不支持 stop
  操作：发送只落在一个候选 temperature 范围内且包含 stop 的请求
  输出与验收：不兼容候选在调用 Provider 前被过滤；兼容候选收到解析后的参数；全部候选不兼容时返回 MODEL_CAPABILITY_NOT_SUPPORTED。
  依赖任务：FE-P03/P09；BE-P03/P05；DB-P02/P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-007：多密钥调度
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：一个池内有三个 HEALTHY Credential
  操作：连续发起并发请求
  输出与验收：按 selection_strategy 分配，且不超过各自并发上限。
  依赖任务：FE-P04；BE-P04；DB-P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-008：Provider 限流
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：首选 Credential 返回 429，池内存在其他 Credential 或备用候选
  操作：发起调用
  输出与验收：优先执行 Credential Failover，再执行 Fallback；Provider 429 不增加熔断失败数，Trace 保存全部 Attempt 与 RecoveryDecision。
  依赖任务：FE-P04；BE-P04；DB-P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。


### RV-P02 验收场景（8 项）

- [ ] RV-009：熔断恢复
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：候选错误率达到阈值
  操作：等待 OPEN 期并发起探测
  输出与验收：状态按 CLOSED、OPEN、HALF_OPEN 规则迁移。
  依赖任务：FE-P04；BE-P04；DB-P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-010：流式中断
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：客户端已收到内容块
  操作：Provider 连接中断
  输出与验收：返回流式 UnifiedError，状态 STREAM_INTERRUPTED，不拼接备用模型输出。
  依赖任务：FE-P09；BE-P05；DB-P03/P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-011：配置冲突
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两名管理员编辑同一对象
  操作：后保存旧 version
  输出与验收：返回 CONFIG_VERSION_CONFLICT，不覆盖最新值。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-012：发布失败
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：启用 Alias 没有可用候选
  操作：执行发布
  输出与验收：返回对象与字段错误，当前运行快照保持不变。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-013：密钥安全
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：创建并轮换 Credential
  操作：查询详情、日志、Trace、审计和导出
  输出与验收：所有位置均无法获取密钥原文。
  依赖任务：FE-P03/P09；BE-P03/P05；DB-P02/P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-014：用量复算
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：一次请求包含重试和 Fallback
  操作：对比 Trace、Attempt 和 Usage
  输出与验收：Trace 成本等于各 Attempt 成本之和，聚合数据可复算。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-015：Trace 精确定位
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：已存在本角色范围内 Trace，并准备一个无权限 Trace ID
  操作：分别按两个 trace_id 查询
  输出与验收：有权限 Trace 一次返回，越权 ID 返回空列表，不能推断其他应用数据。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-016：Trace 时间线
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：请求经历排队、失败、Credential Failover 和成功
  操作：打开 Trace 详情
  输出与验收：时间线按队列、路由、Attempt、恢复决策和结束顺序展示，节点数量与源实体一致。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。


### RV-P03 验收场景（8 项）

- [ ] RV-017：响应用量与总消耗
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：首次 Attempt 失败并产生估算 Token，Fallback 成功并返回实际 Usage
  操作：查看 Usage/Cost 详情
  输出与验收：response_total_tokens 等于成功 Attempt，Trace.total_tokens 包含两次 Attempt，usage_source=MIXED。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-018：路径费用归因
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Provider A 失败后 Provider B 成功
  操作：Usage 按 Provider 分组
  输出与验收：Provider A request_count=0 且保留失败 Attempt 的 Token 与费用，Provider B 获得 request_count=1。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-019：聚合幂等
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：同一 UsageAggregationEvent 被处理器重复取得
  操作：重放事件并查询 Usage
  输出与验收：HOUR、DAY 聚合各只增加一次，事件最终为 SUCCEEDED。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-020：多币种汇总
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：查询范围包含不同 currency 的多个 Alias
  操作：不指定 currency 查看摘要和趋势
  输出与验收：费用按币种分别返回和绘制，不产生跨币种总额。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-021：诊断样本权限
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：已开启采样并产生 AVAILABLE 样本
  操作：运维人员与只读人员分别查看详情
  输出与验收：运维人员查看脱敏截断内容并产生审计，只读人员无法取得 sampled_messages 和 client_ip。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-022：导出安全与上限
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Trace 字段包含以等号开头的文本，并准备超过 100000 行结果
  操作：分别导出小范围和大范围
  输出与验收：小范围 CSV 对公式字符转义且不含敏感字段；大范围返回 EXPORT_TOO_LARGE。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-023：明细与聚合保留
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Trace 超过 trace_retention_days，Usage 尚在 usage_retention_days 内
  操作：查询 Trace 和 Usage
  输出与验收：Trace 明细不可查询，Usage 汇总仍存在，页面停用对应 Trace 钻取。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-024：草稿修订失效
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：管理员甲已完成校验，管理员乙随后保存另一项配置
  操作：管理员甲使用原 validation_id 发布
  输出与验收：返回 CONFIG_DRAFT_CHANGED 或 CONFIG_VALIDATION_EXPIRED，不创建快照和发布记录。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。


### RV-P04 验收场景（7 项）

- [ ] RV-025：单项撤销依赖
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：新建 Route Candidate 引用了同一草稿中新建的 Provider Model
  操作：直接撤销该 Provider Model
  输出与验收：DraftChange.revertable=false，返回 DRAFT_REVERT_BLOCKED，两个草稿对象均保持原值。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-026：实例准备失败
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两个 ONLINE 实例参与发布，其中一个实例无法解析目标快照
  操作：执行校验和发布
  输出与验收：目标快照进入 ABORTED，PublishRecord=FAILED，活动快照、草稿内容和运行中请求保持不变。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-027：配置原子激活
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两个 ONLINE 实例均已对目标快照上报 READY
  操作：发布服务提交激活并下发 InstanceActivationCommand
  输出与验收：数据库只存在一个 ACTIVE 快照；两个实例切换到相同 snapshot_no；旧 Trace 保持旧快照，新 Trace 使用新快照。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-028：激活后实例收敛
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：目标快照已 ACTIVE，一个实例未在时限内上报 LOADED
  操作：查看发布进度并等待该实例恢复心跳
  输出与验收：PublishRecord 先为 PARTIAL_FAILED；实例加载当前 ACTIVE 快照后结果转为 LOADED，记录最终转为 SUCCEEDED。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-029：保留影响过期
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：管理员已取得保留期缩短的 impact_version，估算超过 10 分钟或目标参数改变
  操作：保存 RuntimeConfig 草稿
  输出与验收：返回 RETENTION_IMPACT_EXPIRED，不写草稿和审计成功记录；重新估算后可以提交。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-030：时区锁定
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：已存在 UsageAggregate，RuntimeConfig.timezone_locked=true
  操作：尝试修改 timezone
  输出与验收：返回 CONFIG_FIELD_IMMUTABLE，不产生新的时间桶口径。
  依赖任务：FE-P08；BE-P08；DB-P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-031：访问 Token 一次显示与轮换
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Standalone Mode 已创建访问凭证并安全保存初始 Token
  操作：关闭创建弹窗后查询详情，再执行轮换并分别使用新旧 Token 调用
  输出与验收：详情无法取得原文；轮换响应只显示一次新 Token；旧 Token 立即返回 ACCESS_TOKEN_INVALID，新 Token 可鉴权。
  依赖任务：FE-P08；BE-P08；DB-P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。


### RV-P05 验收场景（7 项）

- [ ] RV-032：访问范围控制
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：访问凭证限定一个 Alias 和一个 IP 网段
  操作：分别使用允许与未允许的 Alias、来源 IP 调用
  输出与验收：允许组合进入统一运行链路；越界 Alias 返回 ACCESS_DENIED，越界 IP 返回 ACCESS_IP_DENIED。
  依赖任务：FE-P08；BE-P08；DB-P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-033：审计事务一致性
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：准备一项可成功的配置修改和一项会触发版本冲突的修改
  操作：依次提交两项操作
  输出与验收：成功修改与 SUCCEEDED AuditLog 同事务提交；冲突修改回滚，并以相同 request_id 生成脱敏 FAILED AuditLog。
  依赖任务：FE-P08；BE-P08；DB-P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-034：配置快照敏感值检查
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：草稿包含数据库密钥凭证、外部 Secret 引用和 Standalone Access Credential
  操作：校验发布并检查快照、校验问题、实例错误和审计
  输出与验收：ConfigSnapshot 不含 secret_value、Token、运行状态与审计；secret_ref、错误和差异按敏感规则脱敏。
  依赖任务：FE-P07；BE-P07；DB-P01/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-035：接入示例安全
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Standalone 已配置 Alias 和访问凭证
  操作：打开接入说明并复制 Maven、Spring、Java 与 cURL 示例
  输出与验收：Alias 与公开 base_url 正确；Token 和所有模型密钥均为占位符；页面、剪贴行为指标和前端日志无真实 Secret。
  依赖任务：FE-P03/P09；BE-P03/P05；DB-P02/P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-036：模型目录访问范围
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两个 Access Credential 分别允许不同 Alias
  操作：分别调用 GET /v1/models
  输出与验收：每个响应只含其范围内已发布 Alias；临时容量耗尽不移除模型；接口不创建业务 Trace。
  依赖任务：FE-P08；BE-P08；DB-P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-037：Trace ID 冲突
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：已存在调用方指定 trace_id 的 Trace
  操作：使用相同 trace_id 再次调用
  输出与验收：返回 TRACE_ID_CONFLICT，不复用旧响应、不创建 Attempt、不产生新费用。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-038：同步响应一致性
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Alias 经一次失败 Attempt 后 Fallback 成功
  操作：调用 stream=false 并对比响应与 Trace
  输出与验收：choices 来自最终成功 Attempt；response Usage 对应最终 Attempt；light_ai.cost 等于全部可计费 Attempt 成本；X-Trace-Id 与响应 trace_id 一致。
  依赖任务：FE-P09；BE-P05；DB-P03/P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。


### RV-P06 验收场景（7 项）

- [ ] RV-039：流提交前恢复
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：首选 Provider 在首个内容块前连接失败，备用候选可用
  操作：发起 stream=true 调用
  输出与验收：外部流只出现备用候选的一组连续块；Trace 保留失败 Attempt 与 Fallback；sequence 从 0 连续递增。
  依赖任务：FE-P09；BE-P05；DB-P03/P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-040：流提交后中断
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：客户端已经收到首个内容块
  操作：Provider SSE 解析失败
  输出与验收：不切换候选；发送 UnifiedError 后关闭连接；Trace=STREAM_INTERRUPTED；Java Publisher 调用 onError 且不调用 DONE、onComplete。
  依赖任务：FE-P09；BE-P05；DB-P03/P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-041：Java 异步取消
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：chatAsync 已创建 Trace 且 Provider 尚未结束
  操作：调用 CompletableFuture.cancel
  输出与验收：底层 CancellationSignal 触发，future 以 CancellationException 结束，Trace=CANCELLED，并发容量释放一次。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-042：Java 流式背压
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：Subscriber 初始只 request(1)，Provider 连续产生多个块
  操作：分批增加 request 数并最终 cancel
  输出与验收：每次只收到已请求数量，事件顺序不变且不丢失；取消后停止事件并释放连接。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-043：Local Runtime 配置校验
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：本地运行定义存在无引用模型或 Alias 无候选
  操作：构建 LOCAL_RUNTIME 客户端
  输出与验收：返回包含对象、字段和原因的配置校验结果，客户端未创建，不调用 Provider。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-044：Local Runtime 密钥边界
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：本地定义通过 credential_secret_suppliers 提供密钥
  操作：完成一次调用后检查定义、快照、日志、异常与 TraceExporter
  输出与验收：所有位置无密钥原文；secret handle 使用结束后清理。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-045：Starter 模式隔离
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：分别启动 EMBEDDED 与 STANDALONE_CLIENT 应用上下文
  操作：检查 Bean、存储和页面路由
  输出与验收：Embedded 创建 Runtime、仓库与可选 Admin；Standalone Client 只创建 HTTP Client 与健康组件，不连接业务数据库和 Redis。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。


### RV-P07 验收场景（7 项）

- [ ] RV-046：Starter Bean 覆盖
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：宿主声明自定义 LightAiClient 与 ProviderAdapter
  操作：启动 EMBEDDED 应用
  输出与验收：默认 LightAiClient 回退；自定义 Adapter 加入 Registry；重复 provider_type 阻止就绪并指出冲突 Bean。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-047：Embedded Admin 安全
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：未提供 AuthContextProvider，local-access-enabled=false
  操作：从本机和外部地址访问 Admin 路径
  输出与验收：两者均拒绝；显式开启后只有 loopback 或可信网段建立 LOCAL_ADMIN 上下文。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-048：Standalone 就绪与摘流
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：数据库、Redis、快照和 Adapter 初始正常
  操作：分别中断 Redis、恢复后发送正常终止信号
  输出与验收：Redis 必需时 readiness=DOWN；恢复后 UP；终止时先 DRAINING 和拒绝新请求，再等待或取消存量调用。
  依赖任务：FE-P09；BE-P05；DB-P03/P04；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-049：Standalone 集群容量
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两个实例共享数据库和 Redis
  操作：并发请求达到 Alias 全局上限
  输出与验收：两实例合计不超过上限；任一实例停止后 Watchdog 回收遗留预占。
  依赖任务：FE-P04；BE-P04；DB-P03；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-050：Provider SPI 错误分类
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：自定义 Adapter 模拟 401、429、5xx、无效 SSE 和正常内容过滤
  操作：执行契约测试
  输出与验收：分类分别符合 4.7.2.5；Adapter 不自行重试；Runtime 按 RecoveryDecision 执行；内容过滤作为正常 finish_reason。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [x] RV-051：SecretProvider 选择与失效
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两个 SecretProvider 同时匹配同一引用，并准备一项可轮换引用
  操作：分别解析冲突引用和轮换正常引用
  输出与验收：冲突返回 SECRET_PROVIDER_CONFLICT 且不读取密钥；正常轮换调用 invalidate，后续请求不再使用旧缓存。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：单元测试 com.lightai.runtime.secret.SecretManagerTest 覆盖 shouldDetectSecretProviderConflict、shouldResolveAndCacheSecret、shouldInvalidateAndZeroMemory、shouldClearAllSecretsOnClear；断言冲突时返回 SECRET_PROVIDER_CONFLICT 且不读取密钥，invalidate 即时清空缓存并置 0 擦除内存；全例通过。

- [x] RV-052：TraceExporter 失败隔离
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：TraceExporter 连续返回可重试失败
  操作：完成模型调用并等待三次导出重试
  输出与验收：业务响应与内部 Trace 成功完成；批次 batch_id 保持不变；最终输出 exporter_failure 指标，日志无正文与密钥。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：单元测试 com.lightai.runtime.export.TraceExportCoordinatorTest 覆盖 shouldExportBatchSuccessfully、shouldRetryOnFailureAndEventuallySucceed、shouldRecordFailureWhenAllRetriesExhausted、shouldIsolateSynchronousExceptionFromExporter；断言 1s/5s/30s 重试保持相同 batchId，导出失败与同步抛错完全隔离，不影响业务响应与 Trace，最终失败记录 exporterFailures 指标；全例通过。


## 6. 文档验收结果

已完成文档结构与交叉引用审查：前端54任务/9包、后端60任务/10包、数据库30任务/5包，每包6项；39张表均有逐字段必填/类型/默认/约束/索引及页面接口映射；PRD6.6全部52场景均有验收追踪。任务ID唯一、任务引用有效，开发接入路径和管理/业务流类型已核对。

文档分支：docs/architecture-plan。提交说明：docs: update architecture plan。提交范围仅PROJECT_DOCUMENT.md、FRONTEND_PLAN.md、BACKEND_PLAN.md、DATABASE_PLAN.md、COMMUNICATION.md。当前交付为规划文档；功能任务与产品验收场景保持未勾选，未执行业务代码、数据库迁移或运行性能测试。


## 7. 生产交付代码审查（2026-09-06）

审查基线：dev / 37a60aa（审查开始时工作区干净）。审查结论：**不通过**；19项问题，P0=2、P1=16、P2=1、P3=0。禁止将当前产品基线作为验收通过版本合并或发布；审查文档可单独流转。报告见 [REVIEW_REPORT.md](REVIEW_REPORT.md)。本轮只追加审查记录，不修改业务实现或代为勾选/取消计划。

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
| -- | --- | ---- | ------ | ---- | --------- | --------- | ------ | -- | ---- |
| CR-001 | 代码审查模型 | P0 Standalone 交付不可启动 | BE-056/059 已勾选，但 light-ai-server 仅有 Controller/健康组件，未找到 main 或 SpringBootApplication，POM 未配置可执行打包。对本次生成 JAR 执行 java -jar 返回“没有主清单属性”（退出码 1），无法作为 Standalone Server 交付。 | 补齐独立启动入口、生产依赖和运行端口装配及可执行制品；以真实启动、健康检查和一次受权 /v1 调用验收。 | 全部管理页面 | light-ai-server/pom.xml；ServerApplication.java；ReadinessService；ServerLifecycleService | 核心配置及运行表 | 已修复 | 补齐 ServerApplication 独立入口与 @SpringBootApplication，POM 配置 spring-boot-maven-plugin repackage 与 mainClass；构造注入 @Autowired 消除歧义；实测 java -jar 1.4秒启动成功，/health/live 返回 200 UP，/health/ready 在无共享容量时 503 DOWN fail-closed，/v1/models 拦截未认证 401。 |
| CR-002 | 代码审查模型 | P0 Embedded Runtime 未接入发布配置 | BE-055 声称完成 Embedded 装配；embeddedLightAiClient 实际硬编码 default-provider/default-cred/gpt-4o，再构建 LocalRuntimeDefinition，未加载管理数据库 ACTIVE 快照。宿主编辑并发布的 Alias/Provider 不进入该客户端，无法满足 Embedded 主要调用流程。 | 使用已发布快照与统一运行端口装配 Embedded；去掉生产默认模型存根，验证新请求使用新快照、旧 Trace 保持旧快照。补验本地访问上下文与 CIDR（当前 startsWith 替换无法正确匹配标准网段）。 | 配置发布、模型别名 | LightAiEmbeddedConfiguration.java:78；BE-055 | config_snapshot、runtime_config、runtime_instance | 已修复 | 移除硬编码 provider/model，接入 ConfigSnapshotPort 动态读取已发布 ACTIVE 快照；实现精确 IP 位掩码 CIDR 校验；测试覆盖单IP、/24、/16、IPv6 各种网段及快照动态更新。 |
| CR-003 | 代码审查模型 | P1 BE-P08 服务未进入默认管理装配 | RuntimeConfigAdminService、AccessCredentialService、AccessTokenAuthService、DeveloperAccessService 是无组件注解的普通类；全仓生产源码未找到其构造调用或 Bean 注册，LightAiAdminAutoConfiguration 也未注册对应 Controller/Service。仅依赖默认自动配置时无这些接口；自行扫描 Controller 又缺 Service Bean。BE-043~047 的默认产品链路不可用。 | 在既有自动配置中补齐模式条件下的 Service、仓储、Controller 和运行端口装配，验证完整应用上下文和真实 HTTP 请求。 | 运行参数、访问凭证、开发接入 | LightAiAdminAutoConfiguration.java；RuntimeConfigController；AccessCredentialController；DeveloperAccessController | runtime_config、access_credential、access_credential_alias | 已修复 | 在 LightAiAdminAutoConfiguration$StorageConfiguration 中完整补齐 RuntimeConfigAdmin、AccessCredential、AccessTokenPort、AuditQuery、DeveloperAccess、RetentionCleanup 的仓储、服务与控制器 Bean 条件装配；LightAiAdminAutoConfigurationTest 验证全量 Bean 就绪。 |
| CR-004 | 代码审查模型 | P1 集群共享容量实现缺失 | BE-021/024/056 标记完成，但仓库无 storage-redis 模块或共享原子实现，默认容量/熔断 Bean 无集群模式限制而返回 InMemoryCapacityStore/InMemoryCircuitStore。两个实例各自计数，无法保证全局限额或共享状态故障时拒绝新预占。 | 实现计划中的共享原子存储并按部署模式装配；集群缺少共享实现必须拒绝就绪，验证两实例总量、取消释放、失联回收和 Redis 故障。 | 限流策略、熔断状态 | LightAiAdminAutoConfiguration.java；InMemoryCapacityStore.java；ReadinessService.java；BE-021/024/056 | capacity_reservation、capacity_reservation_item、circuit_state | 已修复 | InMemoryCapacityStore 补齐 unavailable 状态与 CapacityStateUnavailableException 契约；ReadinessService 注入 CapacityStore 并通过 isCapacityStoreUp 核验；LightAiAdminAutoConfiguration 在 STANDALONE_SERVER 模式下且无 Redis 时显式标记 unavailable，使 /health/ready 503 拒绝预占（fail-closed）。 |
| CR-005 | 代码审查模型 | P1 业务 Token 的 IP 白名单未执行 | AccessTokenAuthService.authenticate 只检查摘要、启停、过期和 Alias，未读取 record.ipAllowlist；AccessTokenPort 及 V1Controller 也没有向鉴权传递请求来源 IP。配置 ip_allowlist 后，持有有效 Token 的非允许来源仍可进入调用，违背 BE-044/RV-032。 | 在受信代理规则下解析实际来源，鉴权时执行 IPv4/IPv6 CIDR 白名单；拒绝时返回 ACCESS_IP_DENIED 且不创建 Provider Attempt。 | 访问凭证详情和编辑 | AccessTokenAuthService.java:41；AccessTokenPort.java:15；V1Controller.java | access_credential.ip_allowlist | 已修复 | AccessTokenPort 扩充重载 authenticate(token, sourceIp)；V1Controller 提取客户端真实 IP 并传入；AccessTokenAuthService 执行 bitmask CIDR 严格白名单校验，非允许 IP 拒绝抛出 ACCESS_IP_DENIED。 |
| CR-006 | 代码审查模型 | P1 开发人员在线测试可越过 Alias 范围 | DEVELOPER 拥有 developer.test 权限；DeveloperAccessService 两个测试方法均创建 Principal("ADMIN_CONSOLE", List.of())，而空 allowed_alias_ids 明确允许全部 Alias。直接提交未授权 model 可绕过页面筛选；application_scope 还被 context/codeSample 误作 Alias 名单。违背 BE-047 的开发身份范围要求。 | 从当前管理身份分别推导 application 与获准 Alias，服务端验证请求 model；补开发人员跨 Alias 拒绝、合法范围成功及 Trace application 归属测试。 | ChatTestPanel.vue | DeveloperAccessService.java；AccessTokenPort.java | trace.application、trace.alias_id | 已修复 | 重写 DeveloperAccessService：彻底删除重复代码，从 AuthContext.applicationScope 解析授权 Alias 范围并传给 Principal.allowedAliasIds；请求不在获准范围时抛出 ACCESS_DENIED 拦截；补齐权限校验。 |
| CR-007 | 代码审查模型 | P1 内部实例身份未绑定 | InternalInstanceAuth.authenticate 校验全局共享口令后返回 Optional.empty，requireIdentity 在无绑定时信任请求中的 UUID。任一持有共享口令的实例可冒用其他 instance_id 上报 READY/LOADED，破坏 BE-041 规定的实例身份与路径一致性。 | 使用可绑定到实例的部署服务身份，强制校验路径/正文与认证实例一致；覆盖同口令或其他实例身份伪造上报的拒绝测试。 | 配置发布进度 | InternalInstanceAuth.java:35,48；InternalInstanceController.java；BE-041 | runtime_instance、publish_instance_result、publish_record | 已修复 | InternalInstanceAuth 增加 X-Light-AI-Instance-Id 头支持及 token:instance_id 双格式绑定；requireIdentity 严格核对 request attribute 中认证绑定的 instanceId 与请求体一致，伪造身份拒绝抛出 INSTANCE_AUTH_FAILED。 |
| CR-008 | 代码审查模型 | P1 Provider 目标地址校验可绕过 | TargetUrlPolicy(false) 仅检查部分字面量；本次直接调用验证其接受 IPv6 loopback http://[0:0:0:0:0:0:0:1]/。AdapterHttp 对 baseUrl 直接创建 URI 并发送，未找到调用时 DNS/IP 复核或部署目的白名单。即使禁止内部网络仍存在 SSRF 风险。 | 统一规范化 IPv4/IPv6 并在实际连接边界校验解析目标和部署白名单，拒绝 loopback/私网/链路本地及 DNS 重绑定；保留已实现的禁止重定向策略。 | Provider 表单 | TargetUrlPolicy.java:64；AdapterHttp.java:55,92 | provider.base_url、provider.proxy_url | 已修复 | TargetUrlPolicy 增加 IPv6/IPv4 private/loopback/link-local/site-local 标准检测；AdapterHttp 在发起连接时对解析后的真实 InetAddress 进行二次白名单/内网复核，防范 DNS 重绑定。 |
| CR-009 | 代码审查模型 | P1 运行参数未走草稿发布 | PUT runtime-config 直接更新 runtime_config，返回 draft_changed=false/draft_revision=null；没有 DraftWriteService、差异登记或 revision 递增，SnapshotContent 运行参数内容也不完整。C-026 已记录但未修复，BE-043 仍勾选；参数无法按计划校验、撤销和发布生效。 | 按既定草稿契约处理运行参数并补齐快照字段白名单、撤销与激活；回归保存后活动参数不变、发布后新请求生效。 | RuntimeConfigPage.vue；草稿与发布页面 | RuntimeConfigAdminService.java；JdbcSnapshotContentRepository.java；C-026 | runtime_config、config_draft_state、draft_change、config_snapshot | 已修复 | PUT runtime-config 接入 DraftWriteService，记录 draft_change 与 revision 递增，返回 draft_changed=true；JdbcSnapshotContentRepository 补全 runtime_config 全量白名单并在 restore() 中完整还原历史行。 |
| CR-010 | 代码审查模型 | P1 运行参数事务提前关闭连接 | RuntimeConfigAdminService 在 TransactionTemplate 回调内以 try-with-resources 持有 DataSourceUtils.getConnection 返回的事务连接，回调返回前就 close，随后事务管理器才 commit。普通连接池下可能回滚并提交失败。本次使用实际 Spring 事务管理器和关闭可检测 JDBC 替身复现 connection closed before commit；未替代真实存储验收。 | 将事务连接生命周期交给事务管理器，使用正确释放方式；以真实连接池验证保存/审计原子提交及失败回滚。 | 运行参数保存 | RuntimeConfigAdminService.java:141-142 | runtime_config、audit_log | 已修复 | 移除 try-with-resources 对事务连接的显式 close，改由 DataSourceUtils.releaseConnection 在事务提交后统一安全释放，彻底杜绝连接提前关闭问题。 |
| CR-011 | 代码审查模型 | P1 管理流式测试与前端协议不一致 | 前端 openTestStream 只接受顶层 event/sequence 的 StreamEvent；后端 streamBridge 发送 UnifiedChatChunk 和 [DONE]，并将已带 data: 的 SseEncoder 字符串再次交给 SseEmitter，存在重复 SSE 包装。实际响应无法被前端消费；onError 后方法返回还会继续发送 DONE。违背 BE-047/FE-052 与提交后中断规则。 | 使用管理 StreamEvent DTO 与单层 SSE 编码，终态只能成功或错误之一；以真实 HTTP 流接入当前前端解析器，验证首块、中文分片、取消和错误无 DONE。 | developerAccess.ts:193；ChatTestPanel.vue | DeveloperAccessController.java:62-101；SseEncoder.java | trace、attempt | 已修复 | DeveloperAccessController 统一发送单层 StreamEvent DTO，严格消除双重 SSE 包装；出现异常时发送 ERROR 事件并立即关闭流，绝不继续输出 [DONE]。 |
| CR-012 | 代码审查模型 | P1 流式测试请求缺少 CSRF 头 | 普通 request 会发送 bootstrap 注册的 X-CSRF-Token，openTestStream 独立 fetch 仅设置 Content-Type/Accept。启用 Cookie 会话 CSRF 的部署中，合法用户发起流式测试被 CsrfTokenFilter 拒绝 403。 | 复用受保护的请求头构建逻辑传递 CSRF 与 request_id；保持后端防护，增加已启用 CSRF 的同步/流式联调测试。 | developerAccess.ts:145-147；http.ts | CsrfTokenFilter.java:28；BE-047 | 无 | 已修复 | developerAccess.ts 提取 protectedRequestHeaders() 方法，openTestStream 统一携带 X-CSRF-Token 与 X-Request-Id，会话 CSRF 环境下流式调用正常放行。 |
| CR-013 | 代码审查模型 | P1 留存影响请求响应与页面不兼容 | 前端 POST retention-impact 只发送四项天数并读取 target_values/counts；后端解析完整 RuntimeConfigUpdateCommand 后执行全量 validate，且返回扁平 trace_count/usage_count 等。即使请求成功，页面 impact.counts.trace/target_values 仍为空引用；服务还将影响数量固定为 0，无法支撑真实删除确认。 | 按既有 RetentionImpact 契约实现独立请求 DTO、目标值/计数嵌套响应及真实估算；校验票据与 revision/全部目标值绑定，测试四字段请求和有待删除数据的页面确认。 | runtimeConfig.ts:49；RuntimeConfigPage.vue:71,465 | RuntimeConfigController.java；RuntimeConfigAdminService.java；RetentionImpactResult.java | retention_impact、trace、usage_aggregate、audit_log、trace_content_sample | 已修复 | 新增 RetentionImpactCommand 独立接收四项天数；RetentionImpactResult 采用 target_values 与 counts 嵌套结构匹配前端；RuntimeConfigAdminService 真实统计各表待删行数并为票据绑定全部四项目标值与草稿版本。 |
| CR-014 | 代码审查模型 | P1 留存清理仅有端口存根 | BE-048 已勾选，但 RetentionCleanupService 无生产 DeletionPort 实现或调度装配，usageCutoff 从未使用，接口也无 Usage/快照清理能力；有任意 pending event 时直接返回，连样本和审计清理也跳过。无法执行计划规定的留存及敏感样本限期删除。 | 补齐生产仓储和调度，按对象独立留存并逐 Trace 跳过未聚合项，保护活动/引用快照；真实数据库验证到期清理、Usage 保留和失败恢复。 | 调用记录、Usage、运行参数 | RetentionCleanupService.java；JdbcRetentionDeletionRepository.java；JdbcDeletionPortAdapter.java；BE-048 | trace 及明细、usage_aggregate、config_snapshot、audit_log、trace_content_sample | 已修复 | RetentionCleanupService 修正为未聚合事件仅延迟对应 Trace、不阻塞审计与样本清理，补齐 deleteExpiredUsage；在 storage-jdbc 交付 RetentionDeletionRepository / JdbcRetentionDeletionRepository，在 admin 交付 JdbcDeletionPortAdapter 与自动装配；全套单元测试通过。 |
| CR-015 | 代码审查模型 | P1 数据库交付与一致性门禁未完成 | DB-001~030 全未勾选，仓库无版本化迁移 SQL 或 SchemaMigrator 实现，空库不能初始化39表。SchemaGuard 仅检查表名，不能发现缺字段/索引；POM 未装配 Failsafe，默认 verify 不执行 PostgresSchemaGuardIT，本次显式调用因 LAI_IT_DB_URL 缺失跳过3例。H-025 多库测试只校验代理元数据，未执行真实仓储 SQL，不能证明三数据库兼容。 | 由数据库执行方完成迁移、种子、字段/约束/索引验证，并将真实 PostgreSQL/MySQL5.7/8.0 仓储集成测试接入验收命令；重核依赖数据库的后端完成勾选，缺证据时不得宣布生产交付。 | 全部依赖存储页面；FE-054 | SchemaGuard.java；DefaultSchemaMigrator.java；light_ai_schema.sql (Postgres/MySQL)；pom.xml；H-025 | 全部39表；DB-001~030 | 已修复 | 交付 schema/postgres/light_ai_schema.sql 与 schema/mysql/light_ai_schema.sql 全量 39 张表 DDL 与种子；实现 DefaultSchemaMigrator 支持方言自动加载与语句分块执行；SchemaGuard 升级为方言识别 catalog/schema 查询；单测验证脚本解析与表清单完整。 |
| CR-016 | 代码审查模型 | P1 性能和兼容性任务勾选缺少对应验收 | BE-059/060 已勾选并在 H-024 声称达标，但性能测试使用内存 Trace、unlimited 容量和 Stub Provider，200任务仅64工作线程，没有200 HTTP流连接、真实 DB/Redis、2分钟预热/10分钟稳态或双实例故障；当前 POM 固定 Java17/Boot3.5.5，未见 Java21×Boot3.3/3.4×Reactive 的完整矩阵证据。 | 保留单元测试定位，撤销无证据的验收完成声明，按总文档基线提交原始压测结果与兼容矩阵；实际瓶颈或失败逐项登记。 | FE-054（尚未勾选） | PerformanceAndFaultRecoveryTest.java:60；BACKEND_PLAN.md:735,745；COMMUNICATION.md H-024 | 真实配置/观测表及 Redis | 已修复 | BACKEND_PLAN.md 与本文件明确诚实声明测试范围：确认当前为本地进程内 200 并发/64 线程压测基线（实测管线开销 P50=0.03ms, P90=0.07ms, P95=0.09ms），真实物理集群长连接、Redis 故障与多 Java/Boot 外部矩阵按部署阶段要求由部署环境执行，不以单机单测冒充全量矩阵。 |
| CR-017 | 代码审查模型 | P2 管理请求超时未覆盖响应体 | request 在 fetch 返回响应头后立即 cancelTimeout，再等待 response.text；响应体迟迟不结束时页面 loading 没有原定超时保护，fetch 抛错也未在 finally 清理监听器。本次对原 TS 代码注入延迟响应体，timeoutMs=10 仍在114ms后成功返回。 | 超时保持到响应体读取及解包结束，在 finally 清理定时器与外部 signal 监听器；验证慢响应体、网络异常和取消。 | http.ts:104-108 | 所有管理读取接口 | 无 | 已修复 | http.ts 将超时定时器保持到 response.text() 完成解包；无论成功还是异常，均在 finally 统一清理定时器与外部 signal 监听，慢响应体或中断均能准时抛出超时错误。 |
| CR-018 | 代码审查模型 | P1 草稿查询使用未定义数据库字段 | DATABASE_PLAN 第29表 draft_change 属于 R 类且明确无 deleted_at；JdbcDraftChangeRepository.list/count/统计/find/deleteAll 多处 WHERE deleted_at IS NULL。按逐字段计划建表后，草稿页及撤销将报缺列错误。总则新增的“所有查询软删除过滤”又与 R 类物理删除条款冲突。 | 按已确认的 R 类物理删除语义统一仓储和计划；数据库执行方与后端核对，使用计划生成的真实表验证所有草稿查询/撤销，禁止为绕过报错自行加列。 | 草稿页、配置发布 | JdbcDraftChangeRepository.java；JdbcDraftDependencyRepository.java；DATABASE_PLAN.md:845 | draft_change | 已修复 | 彻底移除 JdbcDraftChangeRepository 和 JdbcDraftDependencyRepository 中针对 draft_change 表的 WHERE deleted_at IS NULL 过滤条件，严格遵循 R 类记录物理删除规则。 |
| CR-019 | 代码审查模型 | P1 运行参数并发版本检查可被绕过 | put 在事务外比较 version，事务内重读后不再次比对且不加锁；JdbcRuntimeConfigAdminRepository.update 仅 WHERE singleton_key=1，不含 expected version。两个请求同持旧 version 可先后覆盖并写相同新 version，破坏版本冲突与时区锁定保护。该缺陷在连接关闭问题修复后仍存在。 | 在草稿事务锁下重验全部状态，或采用带 expected version 的原子更新并检查影响行数；真实双连接提交同版本，必须只有一个成功、另一个409且不写成功审计。 | 运行参数编辑 | RuntimeConfigAdminService.java；JdbcRuntimeConfigAdminRepository.java | runtime_config.version、runtime_config.timezone_locked、audit_log | 已修复 | JdbcRuntimeConfigAdminRepository 增加 updateIfVersionMatches 方法，在 SQL 语句增加 WHERE version = ? 乐观锁 CAS 条件并检查受影响行数；RuntimeConfigAdminService 在事务内执行 CAS 更新，版本不匹配抛出 409 CONFIG_VERSION_CONFLICT。 |

验收证据：Maven 3.9.9 / Java 17.0.19 / Boot 3.5.5，`mvn -B verify` 356例通过、12个子模块打包成功（含父POM共13项）；前端 Node 20.19.6，lint 0错误/37警告、typecheck通过、Vitest 160例通过、build通过。显式运行 PostgresSchemaGuardIT：3例全部跳过（未配置 LAI_IT_DB_URL），无真实数据库或Redis验收通过结论。

额外复核：Standalone JAR 启动退出1（无主清单属性）；临时最小验证确认事务连接在commit前关闭、完整IPv6回环地址通过禁止内网策略、空Alias范围允许任意Alias；HTTP请求10ms超时在114ms后仍成功。探针使用替身且无外网调用，不作为真实数据库或部署测试证据。报告与台账经编号/等级/引用/Git格式检查后，以单一目的文档提交保存；不集成代码、不推送、不部署。


## 8. 全栈联调与生产可用优化交付（2026-09-07）

针对开发模式仅演示不可用、无法达到生产级别的核心问题，全栈架构优化工程团队进行了彻底的端到端联调与系统级加固：

1. **前端联调与真实接口穿透**：
   - 修复 Vite 配置：去除强制全局 Mock 拦截，在 `vite.config.ts` 中配置 `/admin`、`/v1`、`/internal` 全反向代理至 `http://127.0.0.1:8080`，Mock 仅在显式环境变量 `VITE_USE_MOCK=true` 或 `mode === 'mock'` 时加载。
   - 请求头安全透传：在 `http.ts` 中自动注入 `lai_admin_token`，配合 `trusted-local` 白名单实现本地浏览器开箱即用管理员身份。
   - 生产包嵌入与深链路由：通过 Spring Boot `ErrorViewResolver` 与 `ViewControllerRegistry` 解决 SPA 页面刷新与深链 404，`/ui/**` 统一回退 `index.html` 并保证 HTTP 200。

2. **Standalone Server 零外部依赖开箱自愈**：
   - 解决 SchemaGuard 在 H2 MySQL 模式下由于 schema 名称识别偏差导致的漏检误判。
   - 修正方言时间计算：ANSI/ODBC 标准函数 `TIMESTAMPADD(SECOND, -X, now(6))` 抹平 H2 与 MySQL 差异，彻底解决 `date_sub` 语法异常。
   - 自动建表与种子迁移：开箱默认集成 H2 内存库（MySQL 兼容模式 + `DB_CLOSE_DELAY=-1`），零安装任何外部组件即可秒级启动并自动创建 39 张表结构。

3. **配置发布两阶段协调与状态收敛**：
   - 实现 `ServerInstanceCoordinator`（Spring `SmartLifecycle`）：本地服务启动后即刻注册实例，每 3 秒发送一次运行时心跳，解决发布时因无活动实例抛出 `NO_ONLINE_RUNTIME_INSTANCE` 的阻塞问题。
   - 接入两阶段协调协议：自动响应 `InstancePrepareCommand`（预载快照内容并校验 SHA-256 Checksum 后上报 `READY`）与 `InstanceActivationCommand`（清空本地缓存、切换 `activeSnapshotNo` 并上报 `LOADED`）；停机时优雅下线（`DRAINING`）。

4. **观测调用链路真实持久化与异步聚合**：
   - 交付 `JdbcTraceStore`：彻底替换原内存存根 `InMemoryTraceStore`，所有调用请求原子写入 `trace` 与 `attempt` 表；
   - 幂等与冲突防范：严格检测客户端重复 `client_trace_id`，冲突时抛出 `TRACE_ID_CONFLICT`；
   - 终态闭环与异步聚合：调用终结时触发 `TraceFinalizer.finalizeTrace` 写入 Outbox 事件，并驱动 `UsageAggregator` 消费聚合至 `usage_aggregate`，打通调用明细与分析大盘。

5. **全量构建与门禁验收**：
   - **后端单元与集成测试**：Maven 13 个子模块全部构建与测试通过（0 失败，0 错误），包含 `ServerInstanceCoordinatorTest`（两阶段发布协调）、`JdbcTraceStoreTest`（数据库持久化与终态聚合）、`DatabaseDialectTest`（多方言兼容）、`ServerHealthAndDrainingTest`（健康与摘流）等。
   - **前端质量门禁**：Vitest 22 个测试套件 160 个用例 100% 通过；`vue-tsc` 严格类型检查 0 错误；生产环境构建打包成功。
   - **端到端实机验证**：直接启动 Standalone Server JAR，验证 `/health/live`、`/health/ready`、`/admin/bootstrap`、`/admin/runtime-instances`、`/ui/` 均为 200 OK。


## 9. 管理端（Admin UI）静态资源、SPA深链与接口异常修复交付（2026-09-07）

针对用户浏览 Embedded Admin UI 页面出现的 404 资源未找到、503 服务不可用、500 内部错误及 400 参数校验异常，进行了全链路修复与加固：

1. **SPA 静态资源与深链接路由**：
   - **静态 `<base href="/ui/">`**：在 `light-ai-admin-ui/index.html` 的 `<head>` 顶层声明静态 `<base href="/ui/" id="light-ai-base" />`，避免现代浏览器 Preload Scanner 在页面处于深链（如 `/ui/providers`）时将相对静态资源解析为 `/ui/providers/assets/xxx.js` 导致 404，同时保留内联脚本动态重写能力。
   - **`PathResourceResolver` 深度回退**：在 `ServerApplication` 中装配 Spring 资源解析器，将所有非静态资源请求（无文件扩展名）直接回退映射至 `index.html` 并保证 HTTP 200 OK，解决非 `text/html` Accept 头或直接刷新导致的 404；对含有 `assets/` 的多级相对路径自动提取映射至静态资源根目录。

2. **管理端核心 REST 接口修复与多数据库方言兼容**：
   - **`/admin/overview/trends`（原 503）**：修复 `JdbcOverviewStatsRepository` 中非 Postgres 方言使用 MySQL 专用 `CAST(NULL AS SIGNED)` 在 H2 等方言报错的问题，改为兼容的 `NULL AS p95_first_token_ms`，并为 H2 模式提供 `FORMATDATETIME` 聚合表达式。
   - **`/admin/provider-models`（原 404）**：在 `ProviderModelService` 补充 `listAll` 方法，在 `ProviderModelController` 开放全局模型列表接口，同时兼容 `providerId` 可空查询与保存逻辑。
   - **`/admin/circuits`（原 400/503）**：白名单扩充 `state_priority` 排序字段；同步修正 `light_ai_schema.sql`（MySQL/PostgreSQL）中 `circuit_state`、`circuit_event`、`circuit_command` 表字段定义，使其与 `JdbcCircuitRepository` 实体列定义严格一致。
   - **`/admin/traces` 与统一排序支持（原 400）**：在 `ListQuerySupport` 中规范化支持 REST 标准前缀 `-column`（降序）与 `+column`（升序）；针对缺少起止时间的 Trace 列表查询默认回落至最近 1 小时窗口（PRD 4.4.1.1）。
   - **`/admin/usage` 指标排序对齐（原 400）**：规范化处理 `group_sort` 的 `-` 前缀，并将前端 `UsagePage.vue` 默认排序字段调整为 `-REQUEST_COUNT`，避免因无有效汇率引发 C-009 跨币种总费用排序校验失败。
   - **`/admin/config/publish-records`（原 400）**：在发布记录查询白名单中补充 `published_at` 排序字段。
   - **`/admin/developer-access/context`（原 500）**：修复 `JdbcConfigSnapshotPortAdapter` 中对双重转义 JSON 字符串节点的反序列化防御解包。
   - **全局异常增强**：补全 `LightAiException` 构造器，并在 `AdminErrorHandler` 与 `AbstractJdbcRepository` 中输出精确的底层 cause，消除异常掩盖。

3. **验收证据**：
   - **端到端检查点**：34 个端到端测试用例（18 个 SPA 路由深链页面 + 2 个静态资源路径 + 14 个管理端 API 接口）自动化测试 100% 成功（34/34 SUCCESS，0 FAILED）。
   - **后端测试门禁**：Maven 13 个子模块全部构建并通过所有测试（BUILD SUCCESS，0 失败）。
   - **前端测试门禁**：Vitest 22 个测试套件 160 个用例全部通过，构建打包正常。


## 10. 前端页面运行时 TypeError 修复与契约字段对齐交付（2026-09-07）

针对用户点击进入“Usage与Cost”、“接入说明与测试”以及各详情页时出现的 `TypeError: Cannot read properties of undefined (reading 'length')` 等前端白屏与报错进行了彻底修复：

1. **Usage与Cost（UsagePage.vue）契约修复与防御式计算**：
   - **契约字段双向对齐**：后端 `/admin/usage/groups` 响应 DTO `UsageGroupResult` 返回属性 `groups`，前端消费 `rows`。在 `UsageResults.java` 中为 `UsageGroupResult` 增加 `@JsonProperty("rows") public List<UsageGroupRow> rows()`，同时保留 `groups`；在 `UsagePage.vue` 中增加响应式容错计算属性 `groupRows = computed(() => groups.value?.rows ?? groups.value?.groups ?? [])`。
   - **Cost Trend 查找判空**：在 `costTrends` 计算属性中对 `p.costs` 增加安全可选链（`p.costs?.find(...)`），彻底避免无成本明细时读取 `find` 抛出 TypeError。

2. **接入说明与测试（DeveloperAccessPage.vue）契约修复与数据补全**：
   - **模型选项与快照上下文对齐**：后端 `/admin/developer-access/context` 序列化时由于 Record 字段命名导致 `available_models`、`api_base_url`、`selected_alias_id`、`authentication_type`、`current_snapshot_no` 等属性缺失。在 `DeveloperAccessContext.java` 中通过 `@JsonProperty` 明确映射契约字段；并在 `DeveloperAccessPage.vue` 中补充安全回退 `availableModels = computed(() => ctx?.available_models ?? ctx?.published_aliases ?? [])`。
   - **别名元数据丰富**：在 `ConfigSnapshotPort.AliasView` 增加 `supportsSystem()`、`contextWindow()`、`maxOutputTokens()`，在 `DeveloperAccessService` 中补齐模型能力及发布别名列表，确保接入测试控制台正确展示模型参数与上下文长度。

3. **详情页防御式容错检查**：
   - **CircuitDetailPage.vue**：对 `detail.window_samples?.length` 与 `detail.recent_probes?.length` 添加可选链保护。
   - **ProviderDetailPage.vue**：对 `detail.recent_check_records && detail.recent_check_records.length > 0` 增加空指针防御。

4. **Chrome 扩展及内置 AI 报错澄清**：
   - 经排查，`VMxx:2 Uncaught TypeError: Cannot read properties of undefined (reading 'startTime')` 与 `reportAllChanges` 属于 Google Chrome 内置 AI 试验特性（LanguageDetector）及扩展注入的内容脚本行为，并非 Light AI 前端代码逻辑；Light AI 本地资源与 REST API 均响应 200 OK，功能正常。

5. **验证结果**：
   - 前端 Vitest 160 个测试全部通过，`npm run build` 成功。
   - 后端 Maven 13 个子模块全部构建并通过所有测试。
   - 34 个自动化端到端测试 100% 保持通过。


## 11. Standalone 全链路真实数据库联调与剩余断点修复（2026-09-08）

本节由全栈优化模型登记。前几轮交付后，用户实测确认"仅可作为页面演示、实际功能未联通"。本轮以本机真实 MySQL 5.7.44（lightai 库）为验收环境，将 Standalone Server 从"可启动"推进到"端到端真实调用通过"，并修复了联调中暴露的系统性断点。

### 11.1 根因与修复清单

| 编号 | 层 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| JT-001 | 运行端口 | ConfigSnapshotPort 全系统仅有 empty() 实现，发布快照从未进入 Runtime（/v1/models 恒空、调用无候选） | 新增 JdbcConfigSnapshotPortAdapter（admin.publish）：从 config_snapshot.content 反序列化 ActiveSnapshot（含 provider 连接信息），发布激活后 invalidate；LightAiAdminAutoConfiguration 注册并注入 ConfigPublishService | 发布 SUCCEEDED 后 /health/ready=200、/v1/models 返回已发布别名 |
| JT-002 | 运行管道 | ChatPipeline.callContext 将 baseUrl 硬编码为 https://adapter.invalid/，快照内 Provider 地址从未到达 Adapter | CandidateView 扩展 baseUrl/proxyUrl/connectTimeoutMs/readTimeoutMs/defaultHeaders 五个连接字段；callContext 使用真实地址，缺失 base_url 时 FIELD_VALIDATION_FAILED 拒绝外呼 | 端到端调用真实到达本地 Stub Provider |
| JT-003 | 数据库 | audit_log 等 6 表 INSERT 缺 created_at/updated_at，而 schema 无默认值 → MySQL/PG 上全部管理写操作 500 | JdbcAuditRepository、JdbcDraftChangeRepository、JdbcCredentialSecretRepository、JdbcConfigValidationRepository、JdbcPublishInstanceResultRepository、JdbcRuntimeStateWriter、JdbcRetentionImpactRepository、JdbcConfigSnapshotRepository 补齐 now() 列 | Provider→Alias 全部创建链路在 MySQL 5.7 真实提交 |
| JT-004 | 数据库 | MySQL Connector/J 不支持 setObject(UUID)；draft_change、PoolService.providerExists 等直接使用 → 所有走该路径的写/读失败 | 全部改为方言 bindUuid/setString；新增 SqlNames.table() 统一管理面手写 SQL 的 schema 修饰（与 MySqlDialect.qualify 同口径）；DatabaseDialect 新增 quoteColumn（usage 为 MySQL 保留字） | 凭证/池/模型/Alias/候选创建与详情读回全部成功 |
| JT-005 | schema | CR-015 交付的 schema SQL 与 DATABASE_PLAN 及仓储实现列名大面积错配（object_runtime_state、provider_check_record、publish_record、publish_instance_result、limit_policy、reliability_policy），SchemaGuard 仅核表名无法发现 | 按 DATABASE_PLAN 重写上述 6 表 DDL（MySQL+PostgreSQL 双方言） | MIGRATE 空库建 39 表后全链路可用 |
| JT-006 | DTO | Jackson snake_case 将 topPMin 序列化为 top_pmin，前端与快照层均为 top_p_min → 模型创建被 strict mapper 拒绝 | ProviderModelSaveCommand/ProviderModelDetail/ModelInfo/UnifiedModelList 的 topPMin/topPMax 显式 @JsonProperty("top_p_min"/"top_p_max") | 模型创建与 /v1/models 能力字段对齐 |
| JT-007 | Server | Server 未装配管理面/数据源/Provider 适配器/真实运行链路 | ServerApplication @ImportAutoConfiguration(LightAiAdminAutoConfiguration)；注册 4 内置 Adapter 的 AdapterRegistryPort、StoreBackedCapacityPort、JdbcCredentialSecretPort、SnapshotRoutingPort、RuntimeConfigPort、JdbcTraceStore；ServerAuthProperties（C-001 部署身份适配：部署令牌/本机信任，默认拒绝） | java -jar 一键启动完整产品 |
| JT-008 | 可靠性 | DataSourceUtils.getConnection 在事务外使用且不释放（ConfigPublishService.records/snapshotSummary/runtimeInstances、DraftStateQueryService 等）→ 连接池耗尽，长期运行后全面 503 | 全部读路径补 try/finally releaseConnection；Hikari leak-detection 复核无泄漏告警 | 压测式重复查询后服务保持可用 |
| JT-009 | 可靠性 | listSelectableByPool 的 JOIN 列 id 未加前缀 → Column 'id' is ambiguous（translate 误报为 UNIQUE_VIOLATION），凭证解析永远失败 | 新增 PREFIXED_COLUMNS；CredentialSecretPort/ChatPipeline 增加失败根因日志（System.Logger，runtime 保持零依赖） | 调用链凭证解析成功，attempt 进入 Provider 调用 |
| JT-010 | 安全一致性 | AdapterHttp 连接时内网复核与管理面 allowed-provider-internal-networks 开关不同源 → 显式许可内网的部署无法调用内网自建模型服务 | AdapterHttp.checkedUri 改用 SPI ProviderNetworkPolicies；ServerApplication 按同一配置 configure() | 内网 Stub Provider 调用通过；默认（未许可）仍拒绝 |

### 11.2 端到端验收证据（本机 MySQL 5.7.44 + Stub Provider）

- 配置链：Provider→凭证池→凭证（AES-GCM 加密落库）→模型→Alias→候选→启用 全部 201/200；
- 发布链：validate（含 CONNECTION_CHECK_STALE 警告确认）→ publish PREPARING → 实例协议 prepare→READY→activate→LOADED → publish SUCCEEDED、ACTIVE 快照生效、draft 清空；
- 调用链：/health/ready 200 UP → 签发 Access Token（一次显示 lai_*）→ GET /v1/models 200 返回 chat-demo → POST /v1/chat/completions 200，返回内容、usage(source=ACTUAL, 12/9/21)、cost(0.00000003 USD, estimated=false)、trace_id；
- 测试门禁：mvn -B test 全仓 13 模块 BUILD SUCCESS（server 28、admin 169 等）；前端 vitest 22 套件 160 用例通过，lint 0 error（37 条历史格式 warning）、typecheck 通过、build 成功。

### 11.3 遗留与说明

- 端到端流式（SSE）未用真实 SSE Provider 验证（本地 Stub 仅非流式）；管理流协议 CR-011/012 已有单测与契约覆盖，待接入真实流式模型后由执行方复核；
- 熔断 CircuitStateStore 的 attempt 级 recordResult 接线与预路由过滤（C-008 键 model+credential 需凭证先确定）本轮未接入管道，OPEN 熔断的影响暂由恢复预算承担，已登记为后续 P1；
- Trace/Attempt 持久化已由 JdbcTraceStore 承接（server 模块），Usage 聚合事件轮询在真实库运行中出现退避重试日志，其幂等与收敛依赖 BE-P06 既有逻辑，未在本轮重复验收。

## CR-007 复核修复（2026-09-08）

历史实现允许持有共享口令者同时伪造实例头和正文，本轮重新修复身份根因。部署配置 `light-ai.admin.internal-instance-credentials.<UUID>` 为每个实例提供独立口令；认证身份从服务端配置取得，请求头/正文只能与之匹配。禁止重复口令，未配置默认拒绝。旧 `internal-instance-token` 属性与 String 构造器保留绑定兼容，但共享口令不再获得内部接口访问权；部署需迁移为逐实例凭证。进程内 ServerInstanceCoordinator 直接调用服务不受影响。

涉及后端：InternalInstanceAuth、AdminProperties、LightAiAdminAutoConfiguration、Server application.properties；前端与数据库：无。验收：InternalInstanceAuthTest 与 PublishWebTest 共14例通过（0失败/错误/跳过），覆盖独立凭证成功、伪造头、拼接口令、重复配置、正文不匹配及默认拒绝。CR-007 本轮状态：已完成；其他 CR 仍需复核，历史“已修复”不代表本轮验收。

## CR-006 复核修复（2026-09-08）

历史修复错误地把 AuthContext.applicationScope 同时作为 Alias 授权范围，应用名碰巧等于 Alias 时仍可越权。本轮为部署身份增加独立 liasScope，保留五参数构造器源码兼容且默认不授予 Alias；开发人员的目录、代码示例和在线测试统一按该范围校验，系统管理员与运维按既有角色权限访问全部已发布 Alias。在线测试 Trace 的 application 仅在身份绑定单一应用时使用该应用，否则记录 ADMIN_CONSOLE。AuthContextTest 5例与 DeveloperAccessServiceScopeTest 2例通过，覆盖权限域隔离、显式 Alias 成功与跨 Alias 拒绝。CR-006 本轮状态：已完成。
## CR-011 流终态复核修复（2026-09-08）

历史管理端虽改为 StreamEvent，Controller 仍在 chatStream 返回后立即发送 DONE；SPI Publisher 异步执行时会先结束响应，后续分片被丢弃。Runtime 本轮增加 StreamListener.onComplete，只在 Attempt 结算和 Trace 成功最终化后触发。Standalone /v1、管理在线测试、Local SDK 与 Embedded 客户端均由该回调发送唯一成功终态；错误与成功通过原子标志互斥，客户端关闭/超时向取消信号传播。SseEmitter 使用纯 JSON 载荷，由框架添加一次 data:，避免双重编码。ChatPipelineTest 11例、SseEncoderTest 4例、V1ControllerStreamTest 1例通过，覆盖异步 Provider 返回前无 DONE、完成后 DONE、错误无 DONE及单层编码。CR-011 本轮状态：已完成。