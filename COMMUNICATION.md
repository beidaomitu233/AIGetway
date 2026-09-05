# 协作沟通与审查记录

## 1. 使用规则

本文件名固定COMMUNICATION.md，所有Plan引用同一文件，不另建大小写不同的Communication.md。前端、后端、数据库、审查模型均在此登记接口/字段/需求/实现冲突。文档规划可按合理假设继续；待确认项涉及生产行为的任务可做契约夹具和独立工作，合并前由责任人确认所列具体口径。

状态固定：待确认、已确认、执行中、已完成、驳回。待确认→已确认后可执行；执行中→已完成需测试证据和审查结论。驳回必须说明原因；重新提出使用新序号关联原记录。不得把“提出建议”标已确认，也不得把文档已经写出等同于功能已完成。

提出方填写现象/复现输入/预期输出和具体冲突位置；处理方给明确字段/API/表调整及受影响任务；三方更新文档后审查模型复核。确认记录只保留最终结论与证据，讨论过程留Git评审。无冲突任务继续执行，禁止私改接口名、加用户系统或扩大范围。

## 2. 需求与契约待确认台账

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
|---|---|---|---|---|---|---|---|---|---|
| C-001 | 架构规划 | 身份接入 | Standalone管理身份提供方和登录入口未指定，PRD没有账户密码实体 | 采用部署认证适配/AuthContext与默认拒绝匿名；测试用四角色夹具 | admin-ui/auth、FE-002 | admin/AuthContext、BE-002 | 不新增用户/角色密码表 | 待确认 | 产品/部署负责人指定身份来源、会话与退出入口；不阻断其他模块规划 |
| C-002 | 架构规划 | 技术选型 | 无代码基线；PRD未指定数据库方言和构建工具 | 默认PostgreSQL独立schema、JDBC、Redis单主高可用、Maven、Vue3/TS；Java17与Boot矩阵遵循PRD | FE-001 | BE-001/003/055 | 全部39表 | 待确认 | 确认宿主数据库与部署版本后锁定依赖；不默认建设多库兼容层 |
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
| C-024 | 前端执行模型 | 字段假设 | 4.2.2.2要求详情页展示最近10条检测记录，4.2.9.1未提供检测记录查询接口，ProviderDetail未含该字段 | 假设ProviderDetail响应包含recent_check_records（最近10条ProviderCheckRecord，按检查时间倒序） | FE-009、src/api/providers.ts | BE-009 | provider_check_record | 待确认 | 后端如以独立接口提供检测记录，前端同步调整取数逻辑 |

## 3. 任务包交接格式

| 序号 | 提出方 | 问题类型 | 功能问题描述 | 优化说明 | 涉及前端文件/模块 | 涉及后端文件/模块 | 涉及数据库表 | 状态 | 处理结论 |
|---|---|---|---|---|---|---|---|---|---|
| 模板：新增编号 | 前端/后端/数据库/审查 | 需求/接口/字段/状态/安全/性能/测试/Git | 填PRD章节、任务ID、复现输入、预期与实际 | 填具体接口/字段及兼容影响 | 填实际文件与FE任务 | 填实际文件与BE任务 | 填表与DB任务 | 待确认 | 填责任人、采用口径、分支、commit、测试命令/结果、审查人及日期；没有证据不得标已完成 |
| H-001 | 前端执行模型 | Git/任务领取 | 领取前端基础包FE-P01全部6项（FE-001工程入口与路径、FE-002身份权限缓存、FE-003请求与错误处理、FE-004列表状态与URL筛选、FE-005表单与危险操作组件、FE-006敏感信息与嵌入样式），领取即锁定，他人请勿重复领取 | 分支feature/frontend-foundation（基于dev ae533d7）；交付后在此登记commit、测试命令与结果 | light-ai-admin-ui全部基础模块、FRONTEND_PLAN FE-P01 | BE-001/BE-002（bootstrap与公共DTO契约） | 无 | 执行中 | 2026-09-05领取并锁定；已交付commit d0a7d49（feat(frontend): complete foundation package FE-001..FE-006）；npm run lint、typecheck、test（53例通过）、build全部通过；空根与/light-ai双根深链浏览器刷新验证通过；真实接口联调待BE-P01完成后执行，不以Mock宣称联调验收 |
| H-002 | 前端执行模型 | Git/并发冲突 | H-001锁定后检测到另一协作者于15:57起在同一分支工作区并行编写FE-P01实现（src/api、src/app、src/stores等），其权限码方案与本方C-022首版登记不一致，且本方两个孤儿文件（src/api/types.ts、src/config/runtime.ts）与其实现重复 | 冲突解决：以工作区中已成体系、被router/http/store实际引用的实现为唯一基础；本方删除自建孤儿文件避免重复维护；C-022统一为实际落地的点分权限码；等待写入稳定后审计契约符合度、补齐缺口（mocks插件、tests）、统一运行四项门禁后单次交付 | light-ai-admin-ui/src（api、app、stores、composables、components、layout、pages、styles、main.ts、App.vue） | BE-001/BE-002 | 无 | 执行中 | 2026-09-05：已删除本方孤儿文件并修正C-022；后续协作者领取任务前应先核对COMMUNICATION.md领取记录，避免同包重复开发。同日缺口（mocks契约夹具、53例测试）已由并行实现补齐，四项门禁通过并单次交付，见H-001处理结论 |

| H-003 | 前端执行模型 | Git/任务领取 | 领取前端Provider与凭证池包FE-P02全部6项（FE-007 Provider分页查询、FE-008 Provider新增编辑、FE-009 Provider详情检测、FE-010 Provider启停删除影响、FE-011 Pool列表与表单、FE-012 Pool详情与启停删除），领取即锁定，他人请勿重复领取 | 分支feature/frontend-provider，基于feature/frontend-foundation（依赖FE-P01基础组件与请求层，FE-P01合入dev后改为从dev拉取）；ProviderDetail.recent_check_records字段为前端假设，登记于C-024 | light-ai-admin-ui/pages/providers、credentialPools及api/providers、api/credentialPools、api/providerModels | BE-007~BE-012 | provider、provider_check_record、credential_pool、credential、draft_change | 执行中 | 2026-09-05已交付：npm run lint、typecheck、test（72例通过）、build全部通过；浏览器验收列表/新建/详情/检测/停用确认/凭证池详情通过；bootstrap仍为契约夹具，不以Mock宣称联调验收，commit见本包feat(frontend)提交 |
| H-004 | 前端执行模型 | Git/任务领取 | 领取前端模型接入包FE-P03全部6项（FE-013 Credential查询和新增编辑、FE-014 Credential轮换检测启停删除、FE-015 Model列表详情与能力表单、FE-016 模型导入与批量检测、FE-017 Alias列表新增编辑和影响、FE-018 候选创建编辑重排探测），领取即锁定，他人请勿重复领取；FE-P02已由H-003并行领取，本包不与其重叠 | 分支feature/frontend-models（git worktree D:\AIBuilder\AIGetway-models，基于b43ea0f）；FE-013/014交付为池详情CredentialPanel组件与弹窗，池详情页挂载点由H-003的FE-012提供，集成在其页面合入后完成；交付后在本行补登commit与测试证据 | light-ai-admin-ui/pages/models、aliases、credentials组件、api/credentials、providerModels、modelAliases | BE-013~BE-018 | credential、credential_secret、provider_model、batch_check_job、model_alias、route_candidate | 执行中 | 2026-09-05领取并锁定；已交付（commit见本包feat(frontend)提交）：npm run lint（0问题）、typecheck（vue-tsc通过）、test（11文件72例通过）、build（成功）；preview实测空根与/light-ai深链刷新、models/aliases/credentials契约Mock响应正常；FE-013/014交付为CredentialPanel组件（含新增/编辑/轮换/检测/启停删除与10秒运行态刷新），池详情页挂载点由H-003的FE-012提供，集成在其页面合入后完成；真实接口联调待BE-013—BE-018交付，不以Mock宣称联调验收 |
| H-005 | 后端执行模型 | Git/任务领取 | 领取后端基础契约包BE-P01全部6项（BE-001 公共DTO与统一错误、BE-002 管理身份与Bootstrap、BE-003 仓储与迁移装配契约、BE-004 列表查询与字段映射、BE-005 成功失败审计、BE-006 草稿锁与乐观版本），领取即锁定，其他后端协作者请勿重复领取；同日BE-P01为P0阶段唯一可领后端包，BE-P02~P10在契约冻结前不开放领取 | 分支feature/backend-foundation（基于dev 0476609）；锁定声明同步写入BACKEND_PLAN.md BE-P01包头；交付后在本行补登commit、测试命令与结果 | light-ai-client、light-ai-spi、light-ai-runtime、light-ai-storage-jdbc、light-ai-admin、light-ai-server公共契约 | BE-001~BE-006 | audit_log、config_draft_state、draft_change（迁移由DB-P01提供） | 已完成 | 2026-09-05领取并锁定，同日完成交付（执行期间出现并行协作，协调经过与分工见H-006）：BE-001~BE-006全部勾选，commit见本包feat(backend)提交；测试命令mvn test（Maven 3.9.9，Java 18编译--release 17，Boot 3.5.5兼容基线）最终结果95例全部通过0失败，mvn package四模块出包成功（client 32：协议序列化/未知键/金额精度/API目录唯一/错误码表/角色矩阵/统一请求校验；spi 4：AuthContext不可变与默认拒绝；storage-jdbc 6：SchemaGuard缺表/已就绪/MIGRATE边界；admin 53：bootstrap契约、匿名403、CSRF、request_id贯穿、草稿锁/乐观版本/审计事务、查询白名单、PageResult组装、自动装配条件）；docs/contracts交付协议README与OpenAPI 3.1夹具（docs/contracts/light-ai-protocol.yaml）。遗留：BE-003/005/006真实PostgreSQL行锁/迁移锁/同事务原子性证据待DB-P01迁移落地后联调复核（PostgresSchemaGuardIT已预留门控）；前端FE-P01/P02/P03真实接口联调待本包合入dev后执行，不以Mock宣称联调验收 |
| H-008 | 后端执行模型（会话B） | Git/任务领取 | 领取后端接入对象包BE-P03全部6项（BE-013 Credential查询写入轮换检测、BE-014 Model字段与能力管理、BE-015 模型导入与批量检测、BE-016 Alias列表详情写入删除、BE-017 候选增改删除探测、BE-018 候选原子重排），领取即锁定，其他协作者请勿重复领取；BE-P02已由H-007并行领取，两包文件集不重叠（本包：credential/credential_secret/provider_model/model_alias/route_candidate/batch_check仓储与服务，client access DTO；对方：provider/credential_pool/检测编排），storage check仓储与ImpactAnalysis可能双写，合并时按先落地者保留单一实现 | 分支feature/backend-models（git worktree C:\AIgetway\AIGetway-models，基于dev f6fc471）；锁定声明写入BACKEND_PLAN.md BE-P03包头；与feature/backend-provider并行执行，合并顺序后至者rebase；交付后在本行补登commit、测试命令与结果 | light-ai-client（access DTO）、light-ai-storage-jdbc（credential/model/alias/check仓储）、light-ai-admin（credential/model/alias服务与控制器） | BE-013~BE-018 | credential、credential_secret、provider_model、model_alias、route_candidate、batch_check_job/item、provider_check_record | 执行中 | 2026-09-05领取并锁定；本行为锁记录，交付证据在完成时补登。提醒：两后端会话并行时务必先核对本文件H记录与远程分支，同包禁止双写 |

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

- [ ] RV-051：SecretProvider 选择与失效
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：两个 SecretProvider 同时匹配同一引用，并准备一项可轮换引用
  操作：分别解析冲突引用和轮换正常引用
  输出与验收：冲突返回 SECRET_PROVIDER_CONFLICT 且不读取密钥；正常轮换调用 invalidate，后续请求不再使用旧缓存。
  依赖任务：FE-P09（有管理页面时）；BE-P09/P10及BE-025/026；DB-P03/P04（Local无DB）；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。

- [ ] RV-052：TraceExporter 失败隔离
  使用者/位置：按PRD对应页面权限或业务应用入口。
  前置与输入：TraceExporter 连续返回可重试失败
  操作：完成模型调用并等待三次导出重试
  输出与验收：业务响应与内部 Trace 成功完成；批次 batch_id 保持不变；最终输出 exporter_failure 指标，日志无正文与密钥。
  依赖任务：FE-P05/P06；BE-P06/P08；DB-P04/P05；各包内任务给出API与表。
  测试证据：执行后填写测试名称、环境、结果与commit；不使用真实秘密作夹具。


## 6. 文档验收结果

已完成文档结构与交叉引用审查：前端54任务/9包、后端60任务/10包、数据库30任务/5包，每包6项；39张表均有逐字段必填/类型/默认/约束/索引及页面接口映射；PRD6.6全部52场景均有验收追踪。任务ID唯一、任务引用有效，开发接入路径和管理/业务流类型已核对。

文档分支：docs/architecture-plan。提交说明：docs: update architecture plan。提交范围仅PROJECT_DOCUMENT.md、FRONTEND_PLAN.md、BACKEND_PLAN.md、DATABASE_PLAN.md、COMMUNICATION.md。当前交付为规划文档；功能任务与产品验收场景保持未勾选，未执行业务代码、数据库迁移或运行性能测试。
