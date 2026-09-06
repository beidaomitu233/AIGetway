# 后端执行计划

## 1. 技术栈与模块边界

Java 17字节码、Maven模块，验证Java17/21。Server以Boot3.5兼容基线实施；Starter必须验证PRD指定Boot3.3/3.4/3.5。公共client/spi/runtime不依赖Spring。DTO不可变，Java Flow公开流接口，传输复用JDK HttpClient连接池，不能每次请求新建客户端。[JDK17 HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html) 提供同步/异步请求能力；背压及取消语义由本项目契约验证。

持久化使用JDBC、数据库事务和显式查询；Standalone默认PostgreSQL；迁移用版本化工具（建议Flyway，执行时锁定兼容版）。集群Redis使用原子脚本，连接由宿主或Server配置；脚本同时校验多层计数，不能先读后分别加。[Redis原子脚本说明](https://redis.io/docs/latest/develop/programmability/eval-intro/) 是原子状态操作的技术依据。Redis Cluster若采用分片，相关键必须同slot；V1先使用单主高可用Redis容量命名空间，不新增跨slot分布式事务。

Boot使用独立light-ai命名空间、AutoConfiguration.imports、ConditionalOnMissingBean。[Spring官方自动装配](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html) 作为实现参考。测试采用JUnit5、可控时钟/随机源、HTTP Stub和真实数据库/Redis集成环境；版本由执行包锁定。四个Adapter通过相同契约套件，不依赖线上密钥执行日常测试。

服务分层：Controller鉴权/DTO → ApplicationService事务与编排 → Domain/Runtime状态与业务规则 → Repository/CapacityStore/ProviderAdapter。Controller不直接拼SQL；Adapter不实施重试/路由；数据库迁移由数据库模型独占。目录与发布物依赖见PROJECT_DOCUMENT第2节。

## 2. 协议补充字典

原PRD明确的API方法、路径、请求、响应、权限和错误见本文附录。以下补齐原文只给DTO名称的序列化规则。所有字段snake_case；所有对象不接受未知可写字段。实体字段类型与必填采用DATABASE_PLAN；页面字段/默认值采用FRONTEND_PLAN附录；敏感库字段永不直接序列化。

| DTO | 请求字段与校验 | 响应/结果 |
|---|---|---|
| ProviderCheckCommand | model_id或provider_model_id必须解析同Provider；credential_id可空（由目标池选），mode=MINIMAL_CHAT或CONNECTION_ONLY（能力允许时），timeout_ms默认10000范围100—60000；target由路径决定；不接收真实Secret | ProviderCheckRecord：id、target_type/id、status、started_at/ended_at、total_ms、trace_id、attempt_id、usage、error_code/summary；Provider Request ID仅有权返回 |
| ProviderModelImportCommand | provider_id、source=PROVIDER_API/ADAPTER_PRESET、credential_id（前者必填）、model_ids非空去重数组≤100、apply_known_defaults=true、enabled=false；未知能力不可enabled | ImportResult：created[{model_id,id,version}]、skipped[{model_id,reason}]、failed[{model_id,error}]；逐对象提交 |
| ImpactAnalysis | 查询operation=DISABLE/DELETE；以当前草稿与活动快照依赖关系产生版本 | impact_version、entity_type/id、references[{entity_type,id,name,relation}]、affected_alias_ids、can_delete、blockers；敏感字段剥离 |
| ConfigValidateCommand | draft_revision正整数或0 | validation_id、status、base_snapshot_no、target_snapshot_no、draft_revision、content_checksum、validated_at、expires_at、issues、change_summary、affected_alias_ids、target_instances |
| ConfigPublishCommand | validation_id、draft_revision、acknowledged_warning_ids数组、publish_note≤500；当前用户为发布人，不收operator_id | PublishRecord；首次合法提交202；同validation重复请求若已有记录返回该记录，不能创建第二份；条件失效409 |
| RevertDraftCommand | version、draft_revision、reason 1—500 | ConfigDraftState |
| RevertAllDraftCommand | draft_revision、confirmation_text固定REVERT ALL、reason 1—500 | ConfigDraftState；全文还原原子完成 |
| RuntimeConfigUpdateCommand | 全部可编辑运行参数、version；缩短留存时confirmed_impact_version必填 | ManagementOperationResult；impact期限10分钟且绑定目标值及当前revision |
| AccessCredentialCreateCommand | name、application、allowed_alias_ids=[]、ip_allowlist=[]、expires_at可空、enabled=true | AccessCredentialSecretResult：credential（非敏感详情）、token_value、issued_at、rotation_generation；只本次返回 |
| AccessCredentialUpdateCommand | name、application、allowed_alias_ids、ip_allowlist、expires_at、version | ManagementOperationResult，即时生效；enabled独立操作 |
| AccessCredentialRotateCommand | version、reason 1—500 | AccessCredentialSecretResult；enabled不变，generation+1，旧Token立即拒绝 |
| RuntimeInstanceHeartbeat | instance_id、runtime_mode、runtime_version、application、zone可空、supported_schema_versions[]、loaded_adapter_types[]、active_snapshot_no、accepting_requests、reported_at | server_time、active_snapshot_no、prepare_command或activation_command（互斥，可均空） |
| InstancePrepareCommand | 服务端生成publish_id、snapshot_no、content_checksum、schema_version、deadline_at | 实例构建独立内存配置并上报READY/FAILED |
| InstanceActivationCommand | publish_id、snapshot_no、content_checksum | 实例验证ACTIVE后引用切换并上报LOADED |
| InstanceLoadReport | target_snapshot_no、status、reported_at、retry_count、load_duration_ms、error_code/summary可空 | PublishInstanceResult；身份instance_id必须匹配路径，旧报告409 |
| TraceDetail | path traceId；include_diagnostics默认false（补充C-011） | trace、request_summary、attempts、route_decisions、queue_entries、capacity_reservations、recovery_decisions、circuit_events、timeline、detail_expires_at；无权诊断参数403 |
| TraceTimelineItem | 只读服务端派生 | id、type、occurred_at、source_id、sequence、attempt_id可空、reason_code可空；按PRD确定序 |
| RetentionImpactResult | 目标4项天数 | impact_version、estimated_at、expires_at、target_values、counts（trace/usage/audit/sample）、earliest_remaining_at |
| ApiTestCommand / ApiTestResult | 当前管理身份；model、system_message可空、user_message必填、stream、temperature/top_p/max_tokens可空 | response（UnifiedChatResponse）、trace_id、total_ms；流测试返回StreamEvent SSE |

ListItem包含对应页面列表字段；Detail包含页面详情区域字段。enabled、version属于配置；runtime_status/connection_status/health_status、draft_changed、关联数量与能力摘要由组合查询生成，禁止放进可写DTO。列表允许哪些筛选、排序和分页，以附录每个接口行及页面字段表为准。Bootstrap外所有管理读取均执行相同身份规则。

### 2.1 补充API（C-011）

| 方法/路径 | 请求 | 响应data | 权限/错误 |
|---|---|---|---|
| GET /admin/bootstrap | 无 | user{id,display_name}、roles[]、permissions[]、application_scope[]、allowed_alias_ids[]、runtime_mode、ui_base_path、admin_api_base_path、timezone、current_snapshot_no、draft_revision、draft_change_count、csrf_token（仅会话需要时） | 已认证管理身份；ACCESS_DENIED；无秘密 |
| GET /admin/provider-models/{id}/impact | operation | ImpactAnalysis | 管理员；OBJECT_NOT_FOUND |
| GET /admin/audit-logs/export | 同审计筛选；忽略分页 | UTF-8 BOM CSV流，列created_at/request_id/operator_id/action/entity_type/entity_id/result/error_code | 管理/运维；EXPORT_TOO_LARGE、AUDIT_DATA_UNAVAILABLE；10万行/60秒 |

### 2.2 统一调用字段与序列化

| 字段 | 类型/默认/边界 |
|---|---|
| model | string可缺省，使用已配置default_alias_id；无默认时报字段错误 |
| messages | 非空array；每项仅role与content；role=system/user/assistant；content非空string；至少1个user，system≤1且首项；单条/总字符按RuntimeConfig |
| stream | boolean，默认false |
| temperature、top_p | 可空decimal；按各候选模型及Adapter范围校验 |
| max_tokens | 可空正整数；显式超限过滤，缺省按C-006 |
| stop | 可空string数组，去重，最多4项且各≤128；受模型更小上限约束；V1不接收单字符串（待确认C-015） |
| trace_id | 可空string，1—128，字母数字点短横线下划线；header与body同时提供必须一致 |
| metadata | 可选object，仅application、project、tenant、user、tags；application只能等于身份推导值；project/tenant≤64，user≤128；tags≤20，key≤64/value≤256字符串，敏感键拒绝；不透传Provider |
| provider_options | 可选object；key只能来自当前Adapter ProviderOptionSpec，按type/range/enum校验，不能覆盖统一字段或认证 |
| stream_options | 可空object，仅include_usage boolean默认false；非stream=true时拒绝提供 |

以上metadata长度/键及trace_id字符集是补充规划假设C-015；严格未知字段与业务数据范围始终生效。application由Access Credential/宿主决定，客户端不能伪造。

UnifiedChatResponse：id=trace_id、object=chat.completion、created=开始时间Unix秒、model=Alias、choices=[{index:0,message:{role:assistant,content:string},finish_reason}]、usage={prompt_tokens,completion_tokens,total_tokens}、light_ai={trace_id,provider,provider_model,usage_source,cost:{amount,currency,estimated},snapshot_no}。Token按公共数值规则，SDK转long；响应不返回Credential ID。finish_reason=stop/length/content_filter；供应商其他终态必须在Adapter映射登记，不能静默当成功。

UnifiedChatChunk：id/object=chat.completion.chunk/created/model、choices[{index:0,delta:{role?,content?},finish_reason?}]、light_ai{trace_id,sequence,provider,provider_model}；可选Usage块choices=[]并带usage与light_ai.cost。role块sequence=0，所有JSON成功块连续递增；最后[DONE]为分隔结束标识，不是JSON事件、不递增序号。发生error使用UnifiedErrorEnvelope并关闭，无finish和DONE。Java映射START/DELTA/USAGE/DONE，错误onError、取消无终结事件。

### 2.3 HTTP、权限与幂等

管理成功默认200，创建201，合法发布/批量检测异步受理202；删除仍200含data.id。所有写入经权限/version/字段/引用验证。未认证管理身份默认403 ACCESS_DENIED并交部署登录；业务Token缺失401。SDK本地错误不用伪造HTTP状态。Trace ID唯一冲突409，不提供业务幂等重放。固定静态路由/export、/default在/{id}前匹配。

AuthContext包含authenticated/user_id/display_name/roles/application_scope，建立一次不可变请求上下文。实例内部接口单独mTLS/服务身份，不认可业务Token和管理Cookie。Standalone管理认证具体身份提供方待C-001确认；默认不提供匿名管理。服务端数据权限先限制查询范围，再读子实体，序列化时剥离敏感字段。鉴权拒绝不产生Provider调用。

审计及日志脱敏发生在写入前；禁止打印DTO、HTTP body、Authorization、完整secret_ref。失败日志仅request_id/trace_id/attempt_id、分类、耗时、目标ID和安全摘要。未知内部错误返回INTERNAL_ERROR并产生告警。

## 3. 事务、运行态与异常实现约束

配置事务：锁draft_state → version比较 → 字段/引用 → 草稿实体与差异 → revision+1 → Audit SUCCEEDED → 提交。发布期间PUBLISHING拒绝草稿写。Credential纯轮换和Access独立事务。唯一约束冲突必须转换为确定业务错误。Repository不在异常日志打印绑定Secret。

运行请求：HTTP身份及容量存储可用性前置检查 → Alias → 唯一Trace → 固定快照 → 按候选估算缓存 → 路由/容量 → 外部Attempt → Usage/费用 → 统一终止。不同tokenizer_family只估算一次。预占成功到请求实际发出之间失败必须RELEASED，并退还未发送RPM/TPM；发出后失败按实际/估算结算。取消和超时争抢一次终态CAS，不双结算。

Redis与数据库采用不同权威：容量实时真相在CapacityStore，SQL为可追踪结算事实；reservation_id作为重复释放/结算幂等键。每个Attempt结束保存结算意图，存储恢复后重放，状态仍未知期间拒绝新预占。Circuit人工操作先写PENDING命令与受理审计事务，再原子执行Redis CAS并记录applied_command_id/event；数据库落最终事件/结果后标SUCCEEDED。中途失败可以按command_id核对补记，不重复应用；不能声称SQL与Redis共同回滚。HTTP未收敛返回202命令引用，前端读取CircuitDetail.pending_command直到终态。此方案C-013须确认，自动状态迁移用相同事件标识可靠补记。

流式已经发送文本但最终Trace事务失败：发送INTERNAL_ERROR流式错误，不发送成功DONE，标记待恢复告警；不能撤回已发内容。写入失败时持久化故障恢复记录到部署可用的可靠设施是待确认C-016，禁止无依据承诺故障中仍可完整保留所有事实；默认readiness DOWN且拒绝新调用。

金额计算：每Attempt以调用开始价格快照计算input_tokens×input_price/price_unit及output同式；NUMERIC精度，金额8位小数HALF_UP，在Attempt组件上各舍入一次再求和，Trace/聚合只加已存金额。不使用double、不汇率换算；舍入规则为C-015补充假设。

聚合：处理器原子取得事件并用租约fencing避免过期worker提交；请求/执行贡献分离；HOUR/DAY与event成功同事务。P95不得平均各桶P95；采用可合并毫秒直方图，见数据库计划。overview请求状态来自Trace同范围快照，Usage已终态数据带watermark；数据持续变化时采用共同query_started_at和fingerprint核对，不承诺两个不同查询时刻无差异。

## 4. 后端任务包

每包6项。所有接口任务同时依赖本文附录请求/响应/错误和页面字段，不得只实现任务标题。DB-P01先确定公共字段/约束；后续包按表完成迁移。每包要求单元/接口测试、自检、勾选、单一目的提交，并提供测试命令、结果和关联PRD场景。

## BE-P01 基础契约（6项）

> 领取锁定：后端执行模型（beidao）2026-09-05 领取 BE-P01 全部 6 项（BE-001—BE-006），分支 feature/backend-foundation（基于 dev 0476609），执行期间请勿重复领取或并行修改同包任务；完成记录与测试证据见 COMMUNICATION.md。
> 交付说明（2026-09-05）：BE-001—BE-006 已实现并通过 mvn test（95 例：client 32 / spi 4 / storage-jdbc 6 / admin 53，0 失败）与 mvn package；docs/contracts 提供协议 README 与 OpenAPI 3.1 夹具；light-ai-admin 含 AutoConfiguration.imports 装配（BE-055 做全量 Starter 条件装配时扩展）。其中 BE-003/005/006 的真实 PostgreSQL 行锁、迁移锁与同事务原子性证据依赖 DB-P01 迁移落地后补充联调复核，当前以契约实现与事务语义单元验证为完成基线。运行中发现协作并行（H-006），实现为双方互补合并结果。

- [x] 任务编号：BE-001
  模块：基础契约；目标：冻结公共DTO与错误。
  接口/服务：公共管理/业务/内部协议。
  请求参数与响应字段：本文2节及附录请求→data或error、统一HTTP与字段类型；类型、必填及错误HTTP见协议字典和附录。
  业务流程：确定未知字段、金额精度、分页和版本；提供OpenAPI/契约夹具。
  异常处理：字段错误/协议冲突不可静默兼容。
  数据表与协作依赖：无业务表；DB-P01、FE-P01。
  验收标准：每API有唯一method/path/schema，序列化不丢精度。
  测试要求：字段缺失、未知键、bigint与金额往返；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-002
  模块：基础契约；目标：管理身份与Bootstrap。
  接口/服务：GET /admin/bootstrap及所有管理入口。
  请求参数与响应字段：宿主/部署身份→roles/permissions/application_scope/模式；类型、必填及错误HTTP见协议字典和附录。
  业务流程：逐请求建立AuthContext；同时限制对象范围和字段。
  异常处理：未认证/无权限ACCESS_DENIED。
  数据表与协作依赖：audit_log；DB-P01、FE-002。
  验收标准：四角色无越权；无用户注册密码接口。
  测试要求：四角色矩阵、伪造scope、Embedded匿名；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-003
  模块：基础契约；目标：仓储与迁移装配契约。
  接口/服务：启动schema-mode。
  请求参数与响应字段：DataSource/schema version→仓储就绪或结构错误；类型、必填及错误HTTP见协议字典和附录。
  业务流程：执行VALIDATE/MIGRATE边界，数据库迁移由DB方提供。
  异常处理：缺表/错误版本阻止就绪。
  数据表与协作依赖：全部表；DB-P01。
  验收标准：本地SDK与远程client无数据库连接。
  测试要求：缺表、已有schema、迁移锁；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-004
  模块：基础契约；目标：列表查询与字段映射。
  接口/服务：各GET列表和详情。
  请求参数与响应字段：Query→PageResult/Detail；类型、必填及错误HTTP见协议字典和附录。
  业务流程：参数白名单和稳定排序；DTO显式映射防泄漏。
  异常处理：错误排序/页码400，存储503。
  数据表与协作依赖：配置/观测表；DB-P01。
  验收标准：分页不漏重，敏感列永不序列化。
  测试要求：排序注入、空集、权限范围；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-005
  模块：基础契约；目标：成功和失败审计。
  接口/服务：全部管理写操作。
  请求参数与响应字段：request_id/操作者/命令→AuditLog；类型、必填及错误HTTP见协议字典和附录。
  业务流程：成功同事务；业务失败回滚后独立失败审计；先脱敏。
  异常处理：审计不可写回滚业务并告警。
  数据表与协作依赖：audit_log；DB-P01。
  验收标准：同request_id能定位成功或失败，无密钥值。
  测试要求：故意审计失败、version冲突；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-006
  模块：基础契约；目标：草稿锁与乐观版本。
  接口/服务：配置写服务。
  请求参数与响应字段：version/draft_revision→最新版本与变更数；类型、必填及错误HTTP见协议字典和附录。
  业务流程：锁ConfigDraftState，检查PUBLISHING，原子更新版本。
  异常处理：CONFIG_VERSION_CONFLICT、CONFIG_PUBLISH_IN_PROGRESS。
  数据表与协作依赖：config_draft_state、draft_change；DB-P01。
  验收标准：两管理员旧版本不覆盖，失败revision不增。
  测试要求：并发写、事务回滚；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P02 Provider与池（6项）

> 领取锁定：后端执行模型（beidao）2026-09-05 领取 BE-P02 全部 6 项（BE-007—BE-012），分支 feature/backend-provider（基于 dev f6fc471，BE-P01 已合入），执行期间请勿重复领取或并行修改同包任务；完成记录与测试证据见 COMMUNICATION.md H-007。
> 交付说明（2026-09-05）：BE-007—BE-012 已实现并通过 mvn test（全仓 113 例：client 32 / spi 4 / storage-jdbc 6 / admin 71，0 失败）与 mvn package。检测编排通过 ProviderCheckExecutor SPI 对接 Adapter（BE-P05 交付前无执行器时返回 PROVIDER_ADAPTER_NOT_FOUND，不伪造记录）；Pool 运行指标 current_concurrency/rpm_used/tpm_used 由容量运行时（BE-P04）提供，当前为 0；provider/credential_pool 详情 created_by/updated_by 暂取 draft_change 操作者，专用列登记 C-025 待 DB-P02 确认。真实 PostgreSQL 下的 SQL 与事务证据待 DB-P02 迁移落地后联调复核。

- [x] 任务编号：BE-007
  模块：Provider与池；目标：Provider列表详情。
  接口/服务：GET /admin/providers；GET /admin/providers/{id}。
  请求参数与响应字段：筛选/id→ProviderListItem/Detail；类型、必填及错误HTTP见协议字典和附录。
  业务流程：组合草稿与运行状态、引用计数；隐藏密钥。
  异常处理：404/字段非法/存储失败。
  数据表与协作依赖：provider、object_runtime_state；DB-P02、FE-007。
  验收标准：筛选命中且运行状态不进入草稿。
  测试要求：过滤分页与角色字段；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-008
  模块：Provider与池；目标：Provider创建编辑。
  接口/服务：POST /admin/providers；PUT /admin/providers/{id}。
  请求参数与响应字段：4.2.2字段/version→ManagementOperationResult；类型、必填及错误HTTP见协议字典和附录。
  业务流程：验证Adapter注册、URL/超时/headers，再事务保存。
  异常处理：类型不可变/名称冲突/认证头拒绝。
  数据表与协作依赖：provider；DB-P02、FE-008。
  验收标准：禁止认证头和不允许目标地址，保存不改变ACTIVE。
  测试要求：超时边界、SSRF目的、409；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-009
  模块：Provider与池；目标：Provider检测与记录。
  接口/服务：POST /admin/providers/{id}/check。
  请求参数与响应字段：ProviderCheckCommand→ProviderCheckRecord；类型、必填及错误HTTP见协议字典和附录。
  业务流程：验证模型/凭证同Provider；一次Adapter调用；最小内容使用后清理。
  异常处理：CHECK_TARGET_INVALID、认证/超时错误。
  数据表与协作依赖：provider_check_record、trace、attempt；DB-P02/P03、FE-009。
  验收标准：检测不改version，真实调用留Attempt与费用。
  测试要求：失败/成功/取消与密钥扫描；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-010
  模块：Provider与池；目标：Provider影响启停删除。
  接口/服务：GET impact；POST enable/disable；DELETE /admin/providers/{id}。
  请求参数与响应字段：version/confirmed_impact_version→操作结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：核对草稿+活动引用，拒绝被引用删除，差异与审计事务。
  异常处理：OBJECT_IN_USE、IMPACT_ANALYSIS_EXPIRED。
  数据表与协作依赖：provider、credential_pool、provider_model；DB-P02、FE-010。
  验收标准：影响变更必须重确认，启停仅发布生效。
  测试要求：确认后新增引用竞态；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-011
  模块：Provider与池；目标：Pool查询创建编辑。
  接口/服务：GET/POST /admin/credential-pools；GET/PUT /{id}。
  请求参数与响应字段：name/provider_id/strategy/enabled/version→详情/操作结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：Provider内名称唯一，所属Provider不可修改，运行状态派生。
  异常处理：引用错误、名称/版本冲突。
  数据表与协作依赖：credential_pool；DB-P02、FE-011。
  验收标准：三策略保存正确，读不会暴露Secret。
  测试要求：跨Provider写、重复名称；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-012
  模块：Provider与池；目标：Pool影响与移除。
  接口/服务：GET /{id}/impact；POST enable/disable；DELETE池。
  请求参数与响应字段：version/影响→操作结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：池内Credential或候选引用时拒绝删除，发布后启停生效。
  异常处理：OBJECT_IN_USE、影响过期。
  数据表与协作依赖：credential_pool、credential、route_candidate；DB-P02、FE-012。
  验收标准：有子凭证不可删；无部分差异写入。
  测试要求：引用与回滚；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P03 接入对象（6项）

> 领取锁定：后端执行模型（beidao）2026-09-05 领取 BE-P03 全部 6 项（BE-013—BE-018），分支 feature/backend-model-access（基于 dev 163f869，BE-P02 已合入），执行期间请勿重复领取或并行修改同包任务；完成记录与测试证据见 COMMUNICATION.md H-008。
> 交付说明（2026-09-05）：BE-013—BE-018 已实现并通过 mvn test（全仓 131 例：client 43 / spi 4 / storage-jdbc 13 / admin 71，0 失败）与 mvn package。秘密与配置分离：secret_value 经 AES-256-GCM 加密落 credential_secret（主密钥来自部署配置，缺失时拒绝启动），掩码/引用展示不含明文；轮换走独立即时事务（executeStandalone）递增 secret_version，两次输入不一致 SECRET_CONFIRM_MISMATCH；来源不可切换；删除被容量占用时 CAPACITY_IN_USE（占用判定待 BE-P04 容量存储）。模型启用强制能力完整且 context>max_output（C-014）；导入逐对象事务、重复 skipped、强制停用导入；批量检测不写草稿、取消仅阻止未开始项。候选同 Provider 约束保存与发布两阶段拦截；重排要求完整集合+逐项 version 原子写入。available-models 与检测执行依赖 Adapter SPI（BE-P05），未加载时返回 MODEL_LIST_NOT_SUPPORTED / PROVIDER_ADAPTER_NOT_FOUND，不伪造结果。真实 PostgreSQL 下 SQL/事务证据待 DB-P02 迁移落地后联调复核。

- [x] 任务编号：BE-013
  模块：接入对象；目标：Credential查询写入轮换和检测。
  接口/服务：池下credentials；/admin/credentials/{id}及rotate/check/enable/disable。
  请求参数与响应字段：4.2.4字段及version→脱敏详情/CheckRecord；类型、必填及错误HTTP见协议字典和附录。
  业务流程：分离配置与秘密，INLINE加密、EXTERNAL引用；轮换即时invalidate。
  异常处理：SECRET_CONFIRM_MISMATCH、CAPACITY_IN_USE、SECRET_PROVIDER_CONFLICT。
  数据表与协作依赖：credential、credential_secret；DB-P02、FE-013/014。
  验收标准：来源不可切换；旧Token不回读；运行已取Secret不被破坏。
  测试要求：密钥泄漏扫描、轮换竞态、占用删除；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-014
  模块：接入对象；目标：Model字段与能力管理。
  接口/服务：/admin/provider-models及/{id}/impact、enable、disable、check。
  请求参数与响应字段：4.2.6完整字段→详情/操作/检测；类型、必填及错误HTTP见协议字典和附录。
  业务流程：验证Adapter能力上界、价格、上下文、默认值和只读关系。
  异常处理：FIELD_VALIDATION_FAILED、OBJECT_IN_USE。
  数据表与协作依赖：provider_model；DB-P02、FE-015。
  验收标准：停用导入可缺能力，启用必完整，历史价格不变。
  测试要求：能力边界、默认值、价格精度；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-015
  模块：接入对象；目标：模型导入与批量检测。
  接口/服务：available-models；import；batch-check；jobs/{id}/cancel。
  请求参数与响应字段：ImportCommand或模型IDs→逐项结果/任务；类型、必填及错误HTTP见协议字典和附录。
  业务流程：逐对象导入事务；最多100项检测排队；取消传播至运行项。
  异常处理：MODEL_LIST_NOT_SUPPORTED、JOB_ALREADY_FINISHED。
  数据表与协作依赖：batch_check_job/item、provider_check_record；DB-P02、FE-016。
  验收标准：重复导入skipped，部分失败保留成功，检测不写草稿。
  测试要求：重复、部分失败、取消；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-016
  模块：接入对象；目标：Alias列表详情写入与删除。
  接口/服务：/admin/model-aliases及/{id}/impact、enable、disable。
  请求参数与响应字段：alias/display_name/description/version→详情/结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：别名唯一且创建后只读，开发只看授权已发布，引用删除检查。
  异常处理：OBJECT_IN_USE、FIELD_VALIDATION_FAILED。
  数据表与协作依赖：model_alias、route_candidate；DB-P02、FE-017。
  验收标准：无候选草稿可保存，发布时严格拦截。
  测试要求：空候选、权限、唯一冲突；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-017
  模块：接入对象；目标：候选增改删除和探测。
  接口/服务：/admin/model-aliases/{id}/candidates；/admin/route-candidates/{id}/check。
  请求参数与响应字段：model/pool/priority/weight/enabled/version→详情/结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：同Provider约束，重复三元组拒绝，更新不换model。
  异常处理：DUPLICATE_ROUTE_CANDIDATE、OBJECT_REFERENCE_INVALID。
  数据表与协作依赖：route_candidate；DB-P02、FE-018。
  验收标准：跨Provider在保存和发布两阶段拒绝。
  测试要求：重复组合、错误引用、探测限流；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-018
  模块：接入对象；目标：候选原子重排与状态摘要。
  接口/服务：PUT /admin/model-aliases/{id}/candidates/reorder。
  请求参数与响应字段：items[id,priority,version]→候选数组；类型、必填及错误HTTP见协议字典和附录。
  业务流程：要求完整候选集合无重复；所有version核对后统一写入。
  异常处理：CONFIG_VERSION_CONFLICT、字段错误。
  数据表与协作依赖：route_candidate、draft_change；DB-P02、FE-018。
  验收标准：任一冲突整批回滚，权重保持原值。
  测试要求：缺ID、重复ID、并发编辑；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P04 路由与治理（6项）

> 领取锁定：后端执行模型（beidao）2026-09-05 领取 BE-P04 全部 6 项（BE-019—BE-024），分支 feature/backend-routing-governance（基于 dev a41fb8b，BE-P03 已合入），执行期间请勿重复领取或并行修改同包任务；完成记录与测试证据见 COMMUNICATION.md H-010。
> 交付说明（2026-09-05）：BE-019—BE-024 已实现并通过 mvn test（全仓 161 例：client 43 / spi 4 / storage-jdbc 13 / runtime 30 / admin 71，0 失败）与 mvn package。新建 light-ai-runtime 模块（无 Spring/无管理库依赖）：路由能力/上下文/熔断过滤与同级权重无放回（可控随机源可复算，过滤不消耗恢复预算）；凭证三策略选择（HEALTHY 优先、限流复位边界、禁用/无效排除）；恢复引擎固定矩阵（认证/参数终态不重试、429 先换凭证再 Fallback 后 Retry-After 截断、指数退避+抖动）与 Trace 级线性预算（1+retries+failovers+fallbacks 不乘法膨胀）；进程内原子容量存储（60 秒固定窗口、三层同次预占部分失败全回退、原窗口结算、终态互斥单次释放、未发送退还 RPM）+按 Alias FIFO 队列（满 QUEUE_FULL/取消/超时）+Watchdog 租约清扫端口；熔断引擎（429 不计失败、阈值自动 OPEN、到期惰性 HALF_OPEN、探测名额不超额、state_version CAS 人工命令）。管理面：限流/可靠性策略 CRUD（保存与启用两阶段唯一冲突，CONFLICT 携带 conflicting_policy_id）、启用需至少一限额、系统默认策略端点、熔断列表/详情/事件/人工 open-recover（C-013：PENDING 命令+受理审计→CAS→事件+终态同事务，未应用不报成功）。共享状态存储为端口+进程内实现（Embedded 合法），集群 Redis 实现按计划归属 BE-P05 storage-redis；容量/队列/恢复决策的 SQL 持久化在 P05/P09 服务装配时接入（capacity_reservation/queue_entry/recovery_decision 表结构已按 DATABASE_PLAN 预留）。真实 PostgreSQL 下 SQL 证据待 DB-P03 迁移。

- [x] 任务编号：BE-019
  模块：路由与治理；目标：候选能力过滤与加权顺序。
  接口/服务：RuntimeCore路由服务。
  请求参数与响应字段：请求/固定快照→RouteDecision/候选顺序；类型、必填及错误HTTP见协议字典和附录。
  业务流程：每tokenizer缓存估算；静态/能力/熔断过滤，同级权重无放回。
  异常处理：MODEL_CAPABILITY_NOT_SUPPORTED、CONTEXT_WINDOW_EXCEEDED。
  数据表与协作依赖：route_decision、trace；DB-P03。
  验收标准：过滤不创建Attempt，不消耗Fallback预算。
  测试要求：多能力/上下文混合、确定随机源；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-020
  模块：路由与治理；目标：凭证选择与Secret取得。
  接口/服务：CredentialSelector/Resolver。
  请求参数与响应字段：池策略/健康/容量/deadline→凭证及短期句柄；类型、必填及错误HTTP见协议字典和附录。
  业务流程：HEALTHY优先UNKNOWN，过滤无效和未复位限流；三种选择策略。
  异常处理：CREDENTIAL_NOT_AVAILABLE、SECRET_RESOLUTION_FAILED。
  数据表与协作依赖：credential_secret、object_runtime_state；DB-P02/P03。
  验收标准：不选禁用密钥，使用后清句柄，无明文缓存超期。
  测试要求：三策略、reset边界、解析失败；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-021
  模块：路由与治理；目标：限流策略与运行查询。
  接口/服务：/admin/limit-policies及usage/queue/enable/disable。
  请求参数与响应字段：4.3.1字段→策略/LimitUsageSnapshot/队列页；类型、必填及错误HTTP见协议字典和附录。
  业务流程：作用对象唯一启用；三层限额与Credential限制取最小；运行只读。
  异常处理：LIMIT_POLICY_CONFLICT、CAPACITY_STATE_UNAVAILABLE。
  数据表与协作依赖：limit_policy、capacity_reservation、queue_entry；DB-P03、FE-019/020。
  验收标准：无管理取消队列端点，空值不变0。
  测试要求：唯一冲突、QUEUE组合、角色；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-022
  模块：路由与治理；目标：可靠性策略和恢复判定。
  接口/服务：/admin/reliability-policies及default/recovery-decisions。
  请求参数与响应字段：4.3.2字段/错误分类→策略/RecoveryDecision；类型、必填及错误HTTP见协议字典和附录。
  业务流程：固定Trace总预算；429先换密钥再候选；瞬时错误重试退避。
  异常处理：RELIABILITY_POLICY_CONFLICT、TOTAL_TIMEOUT。
  数据表与协作依赖：reliability_policy、recovery_decision；DB-P03、FE-021/022。
  验收标准：动作计数全Trace累计，不发生预算乘法膨胀。
  测试要求：错误矩阵、Retry-After、总超时；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-023
  模块：路由与治理；目标：熔断窗口与人工命令。
  接口/服务：/admin/circuits及events/open/recover/probe。
  请求参数与响应字段：state_version/reason/open_seconds→CircuitDetail或pending命令；类型、必填及错误HTTP见协议字典和附录。
  业务流程：共享探测名额；错误计数按矩阵；按C-013可靠应用命令。
  异常处理：CIRCUIT_STATE_CONFLICT、CAPACITY_LIMITED。
  数据表与协作依赖：circuit_state/event/command；DB-P03、FE-023/024。
  验收标准：429不计失败，HALF_OPEN不超额；未应用不报成功。
  测试要求：CAS竞态、故障注入、窗口/探测；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-024
  模块：路由与治理；目标：容量预占结算FIFO与Watchdog。
  接口/服务：CapacityStore及QueueService。
  请求参数与响应字段：三层策略/estimated/max_tokens→reservation或queue；类型、必填及错误HTTP见协议字典和附录。
  业务流程：原子预占，原窗口结算，一次释放，队首重新选路，到期回收。
  异常处理：QUEUE_FULL、QUEUE_TIMEOUT、CAPACITY_STATE_UNAVAILABLE。
  数据表与协作依赖：capacity_reservation/item、queue_entry；DB-P03。
  验收标准：两实例合计不超上限；失败无部分计数。
  测试要求：并发、窗口切换、失联、取消竞态；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P05 协议与Provider（6项）

> 领取锁定：后端执行模型（beidao，会话B）2026-09-06 领取 BE-P05 全部 6 项（BE-025—BE-030），分支 feature/backend-protocol（基于 dev 2c3fdf9，独立 worktree C:\AIgetway\AIGetway-models），执行期间请勿重复领取或并行修改同包任务；BE-P04（feature/backend-routing-governance）已由 H-007 会话并行领取，本包通过 RoutingPort/CapacityPort/TraceStore 端口解耦，其实现由 BE-P04 交付后接线；完成记录与测试证据见 COMMUNICATION.md。

> 交付说明（2026-09-06）：BE-025—BE-030 已实现并通过 mvn test（全工程 162 例，本包新增 15 例：ChatPipeline 同步/流式/取消 10 例、UsageSettlement 3 例、SseEncoder 2 例）加上四 Adapter 线协议夹具（provider-common 5、anthropic 5、gemini 5）。遗留：真实 Provider HTTP 联调依赖部署方测试凭证（不使用真实密钥的夹具已覆盖请求构建/响应解析/SSE/错误分类）；/v1 HTTP 绑定（light-ai-server）端到端验证随 BE-P09/BE-055 执行；RoutingPort/CapacityPort/TraceStore 的生产实现由 BE-P04/BE-P06 交付后接线。

- [ ] 任务编号：BE-025
  模块：协议与Provider；目标：Provider SPI及受控参数。
  接口/服务：ProviderAdapter各方法。
  请求参数与响应字段：Capabilities/OptionSpec/CallContext→统一结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：纯校验与错误分类；每次外部调用一次，无内置重试；支持取消。
  异常处理：Adapter重复type/不支持参数。
  数据表与协作依赖：无新增配置表；BE-019/020、DB-P03。
  验收标准：四Adapter同接口，秘密不进CallContext日志。
  测试要求：SPI统一契约、重复注册、未知option；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-026
  模块：协议与Provider；目标：四内置Adapter转换。
  接口/服务：OPENAI/ANTHROPIC/GEMINI/DEEPSEEK。
  请求参数与响应字段：统一文本请求→供应商请求→统一响应；类型、必填及错误HTTP见协议字典和附录。
  业务流程：分别转换system、max_tokens、usage、finish与流协议；更新官方协议夹具。
  异常处理：401/429/5xx/解析错误按附录映射。
  数据表与协作依赖：attempt、provider_check_record；BE-025。
  验收标准：每Adapter同步流式取消和计价字段可核对。
  测试要求：每家六类HTTP/流夹具，不用真实密钥；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-027
  模块：协议与Provider；目标：模型目录与同步HTTP。
  接口/服务：GET /v1/models；POST /v1/chat/completions stream=false。
  请求参数与响应字段：Token/IP/UnifiedChatRequest→目录/Response；类型、必填及错误HTTP见协议字典和附录。
  业务流程：按4.7顺序，Alias前失败无Trace；唯一Trace后全路径最终化。
  异常处理：完整统一错误表。
  数据表与协作依赖：trace、attempt、config_snapshot；DB-P03/P04。
  验收标准：目录不计Trace，暂时熔断不移除；响应Usage最终Attempt。
  测试要求：身份/体积/未知字段/重复ID/Fallback；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-028
  模块：协议与Provider；目标：SSE提交与错误边界。
  接口/服务：POST /v1/chat/completions stream=true。
  请求参数与响应字段：UnifiedChatRequest→Chunk SSE/error；类型、必填及错误HTTP见协议字典和附录。
  业务流程：首内容或正常空结束才提交，sequence0连续；提交后禁止换路径。
  异常处理：STREAM_INTERRUPTED、CLIENT_CANCELLED。
  数据表与协作依赖：trace、attempt；DB-P03。
  验收标准：提交前恢复只有最终路径块；中断无DONE。
  测试要求：中文分帧、首块前后错误、零内容；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-029
  模块：协议与Provider；目标：统一取消总超时与清理。
  接口/服务：CancellationSignal/HTTP断开/SDK取消。
  请求参数与响应字段：首个终止信号→Trace与Attempt终态；类型、必填及错误HTTP见协议字典和附录。
  业务流程：终止CAS，关闭Provider/队列，结算或释放一次；迟到结果不覆盖。
  异常处理：TOTAL_TIMEOUT、CLIENT_CANCELLED。
  数据表与协作依赖：trace、attempt、capacity_reservation；DB-P03。
  验收标准：并发归还一次，取消无后续输出。
  测试要求：成功/超时/取消三方竞态；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-030
  模块：协议与Provider；目标：Usage和价格快照计算。
  接口/服务：Attempt结算服务。
  请求参数与响应字段：实际Usage或估算、价格快照→Token/cost；类型、必填及错误HTTP见协议字典和附录。
  业务流程：失败请求也结算；decimal运算，保留ACTUAL/ESTIMATED和价格。
  异常处理：缺Usage估算、非法上游Usage分类。
  数据表与协作依赖：attempt；DB-P04。
  验收标准：Trace总成本为Attempt和，改价不影响历史。
  测试要求：无Usage、失败估算、舍入边界；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P06 调用观测（6项）

> 领取锁定：后端执行模型（beidao）2026-09-06 领取 BE-P06 全部 6 项（BE-031—BE-036），分支 feature/backend-observability（基于 dev d943afd，独立 worktree D:\AIBuilder\AIGetway-observability），执行期间请勿重复领取或并行修改同包任务；BE-P05（feature/backend-protocol）并行执行中，本包不修改 /v1 管道与 Adapter 文件，TraceFinalizer 以服务+事务边界交付，/v1 管道终端化接线在 BE-P05 合入后协调；完成记录与测试证据见 COMMUNICATION.md H-019。

- [x] 任务编号：BE-031
  模块：调用观测；目标：Trace列表筛选导出查询底座。
  接口/服务：GET /admin/traces。
  请求参数与响应字段：TraceListQuery→PageResult；类型、必填及错误HTTP见协议字典和附录。
  业务流程：精确ID与普通31天过滤分流，权限先注入，稳定排序。
  异常处理：OBSERVATION_DATA_UNAVAILABLE。
  数据表与协作依赖：trace、attempt；DB-P04、FE-025/026。
  验收标准：越权精确ID空集，final路径过滤与Attempt类型exists正确。
  测试要求：组合查询、边界时间、执行计划；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-032
  模块：调用观测；目标：Trace详情和时间线。
  接口/服务：GET /admin/traces/{traceId}。
  请求参数与响应字段：traceId/include_diagnostics→TraceDetail；类型、必填及错误HTTP见协议字典和附录。
  业务流程：先权限后子表，按固定顺序合并；诊断读取审计。
  异常处理：ACCESS_DENIED、OBJECT_NOT_FOUND。
  数据表与协作依赖：trace关联表、trace_content_sample；DB-P04、FE-027/028/029。
  验收标准：attempt数一致，无权诊断字段不序列化。
  测试要求：同时间序、无Attempt、过期样本；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-033
  模块：调用观测；目标：最终化与幂等聚合。
  接口/服务：TraceFinalizer/UsageAggregator。
  请求参数与响应字段：终态Trace+Attempt→唯一event→HOUR/DAY；类型、必填及错误HTTP见协议字典和附录。
  业务流程：同事务最终化；请求与执行贡献分离，事件fencing消费。
  异常处理：事务失败不返回同步成功，聚合失败退避。
  数据表与协作依赖：usage_aggregation_event、usage_aggregate；DB-P04、FE-030。
  验收标准：同event重放零增量；失败Provider仍有Token、无请求数。
  测试要求：重放/接管/双粒度回滚/路径归因；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-034
  模块：调用观测；目标：概览摘要趋势异常。
  接口/服务：GET /admin/overview/filters、summary、trends、exceptions。
  请求参数与响应字段：公共筛选→摘要/连续桶/异常；类型、必填及错误HTTP见协议字典和附录。
  业务流程：按权限范围注入；请求状态来自Trace；成本分币种，异常对象可钻取。
  异常处理：OBSERVATION_DATA_UNAVAILABLE。
  数据表与协作依赖：trace、usage_aggregate、circuit_state；DB-P04、FE-031/032/033。
  验收标准：请求数与同范围Trace一致，开发无Credential异常。
  测试要求：终态混合、空桶、多币种、四角色；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-035
  模块：调用观测；目标：Usage统一查询。
  接口/服务：GET /admin/usage/summary、trends、groups。
  请求参数与响应字段：UsageQuery→三类Result/fingerprint；类型、必填及错误HTTP见协议字典和附录。
  业务流程：共用解析器、维度条件和桶归属；P95合并直方图。
  异常处理：多币种成本排序400、越权403。
  数据表与协作依赖：usage_aggregate；DB-P04、FE-034/035。
  验收标准：三个结果同fingerprint，不能平均桶P95。
  测试要求：多维复算、时区/DST、排序；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-036
  模块：调用观测；目标：安全流式导出。
  接口/服务：GET /admin/traces/export、/admin/usage/export。
  请求参数与响应字段：同筛选→CSV；类型、必填及错误HTTP见协议字典和附录。
  业务流程：先检查10万行，再游标输出；公式转义/UTF8 BOM；60秒与取消。
  异常处理：EXPORT_TOO_LARGE、存储失败。
  数据表与协作依赖：trace、usage_aggregate；DB-P04、FE-026/036。
  验收标准：无敏感列，不把完整文件放内存/磁盘。
  测试要求：100000/100001、公式字符、断开；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P07 草稿发布（6项）

> 领取锁定：后端执行模型（beidao）2026-09-06 领取 BE-P07 全部 6 项（BE-037—BE-042），分支 feature/backend-config-publish（git worktree D:\AIBuilder\AIGetway-backend，基于 dev 4f23ed7；该分支早前基于 dev d943afd 的未推送领取与半成品由本会话接续并合入最新 dev），执行期间请勿重复领取或并行修改同包任务；BE-P05/P08 已合入 dev；完成记录与测试证据见 COMMUNICATION.md H-022。
> 交付说明（2026-09-06）：BE-037—BE-042 已实现并通过 mvn test（全模块 0 失败，admin 模块 164 例含本包 49 例新增：草稿状态/差异脱敏展示与撤销阻塞、单项与全量撤销事务回滚、校验矩阵与凭据过期、发布准备/原子激活/幂等重提交、心跳命令、上报冲突与超时收敛、内部实例默认拒绝鉴权、四角色权限与统一错误信封）与 mvn package。发布校验矩阵交付可实现子集（REFERENCE_INVALID、PROVIDER_RELATION_INVALID、ALIAS_NO_AVAILABLE_CANDIDATE、MODEL_CAPABILITY_INVALID、PRICE_CONFIGURATION_INVALID、CREDENTIAL_CONFIGURATION_INVALID、LIMIT/RELIABILITY_POLICY_INVALID、ADAPTER_UNAVAILABLE、INSTANCE_VERSION_INCOMPATIBLE，WARNING：CONNECTION_CHECK_STALE、INSTANCE_NOT_ONLINE），唯一约束主要在写入期拦截；revert-all 恢复 runtime_config 发布参数待 BE-043 RuntimeConfig 全量落地后补全；真实 PostgreSQL 下事务/行锁/部分唯一索引证据沿用门控 IT 口径待 DB-P05 迁移落地后复核。

- [x] 任务编号：BE-037
  模块：草稿发布；目标：草稿状态与差异查询。
  接口/服务：GET /admin/config/draft-state、draft-changes/summary、draft-changes。
  请求参数与响应字段：筛选→state/summary/page；类型、必填及错误HTTP见协议字典和附录。
  业务流程：与base快照比较，同实体一行，秘密字段无前后值。
  异常处理：CONFIG_DATA_UNAVAILABLE。
  数据表与协作依赖：config_draft_state、draft_change；DB-P05、FE-037。
  验收标准：新增再删除抵消，草稿数量一致。
  测试要求：多次编辑、字段脱敏；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-038
  模块：草稿发布；目标：单项和全量撤销。
  接口/服务：POST 单项revert与revert-all。
  请求参数与响应字段：version/revision/confirmation/reason→state；类型、必填及错误HTTP见协议字典和附录。
  业务流程：引用阻止单项；全部以base快照恢复并生成新对象version。
  异常处理：DRAFT_REVERT_BLOCKED、CONFIG_DRAFT_CHANGED。
  数据表与协作依赖：配置实体、draft_change、audit_log；DB-P05、FE-038。
  验收标准：任一步失败全回滚，秘密轮换不被撤销。
  测试要求：新对象依赖、旧编辑页、回滚；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-039
  模块：草稿发布；目标：固定修订校验。
  接口/服务：POST /admin/config/validate。
  请求参数与响应字段：draft_revision→Validation及Issue；类型、必填及错误HTTP见协议字典和附录。
  业务流程：规范化checksum，完整校验矩阵；不访问Provider/解析Secret。
  异常处理：CONFIG_DRAFT_CHANGED、版本不兼容。
  数据表与协作依赖：config_validation/issue、runtime_instance；DB-P05、FE-039。
  验收标准：字段可定位、warning可确认、revision变化失效。
  测试要求：无候选、币种冲突、实例能力；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-040
  模块：草稿发布；目标：准备与原子激活。
  接口/服务：POST /admin/config/publish。
  请求参数与响应字段：validation/revision/warnings→PublishRecord；类型、必填及错误HTTP见协议字典和附录。
  业务流程：锁草稿、固定ONLINE集、CREATED快照；全READY才活动事务；零实例拒绝。
  异常处理：CONFIG_VALIDATION_EXPIRED、NO_ONLINE_RUNTIME_INSTANCE。
  数据表与协作依赖：config_snapshot、publish_record、publish_instance_result；DB-P05、FE-040/041。
  验收标准：准备失败保草稿；激活只有一个ACTIVE，重复提交不重建。
  测试要求：两实例失败、重复发布、事务崩溃；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-041
  模块：草稿发布；目标：内部心跳报告和收敛。
  接口/服务：POST heartbeat；GET snapshot；POST instance reports。
  请求参数与响应字段：mTLS身份/heartbeat/report→命令/实例结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：15秒心跳，校验checksum/schema和报告时序，落后实例先加载再接入。
  异常处理：INSTANCE_AUTH_FAILED、INSTANCE_REPORT_CONFLICT。
  数据表与协作依赖：runtime_instance、publish_instance_result；DB-P05、FE-041。
  验收标准：内部内容只授权实例可取；PARTIAL_FAILED可收敛。
  测试要求：旧报告、伪造instance、加载中断；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-042
  模块：草稿发布；目标：发布查询与恢复协调。
  接口/服务：GET publish-records/详情、snapshot summary、runtime-instances。
  请求参数与响应字段：筛选/id→历史/实例/摘要；类型、必填及错误HTTP见协议字典和附录。
  业务流程：读不含content；协调器重启按持久阶段恢复，未激活超时abort。
  异常处理：CONFIG_DATA_UNAVAILABLE、OBJECT_NOT_FOUND。
  数据表与协作依赖：publish_record、config_snapshot、runtime_instance；DB-P05、FE-042。
  验收标准：重启不双激活，completed与converged语义分开。
  测试要求：阶段崩溃、历史分页、权限；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P08 运行与安全管理（6项）

> 领取锁定：后端执行模型（beidao，会话B）2026-09-06 领取 BE-P08 全部 6 项（BE-043—BE-048），分支 feature/backend-security（基于 dev 0c46813，独立 worktree C:\AIGetway\AIGetway-models），执行期间请勿重复领取或并行修改同包任务；其 BE-P06 领取已释放改领本包（dev 续号 H-020/H-021，见 COMMUNICATION.md）；完成记录与测试证据见 COMMUNICATION.md。

> 交付说明（2026-09-06）：BE-043—BE-048 已实现并通过 mvn test（全工程 175 例，本包新增 13 例：AccessTokenService 2 例、AccessCredentialService 6 例等）。遗留：真实 PostgreSQL 约束与同事务原子性证据待 DB-P05 联调；/v1 鉴权端到端（两实例轮换、IP/IPv6 代理链）随部署验收执行；清理任务 DeletionPort 生产实现待 BE-P48 数据迁移落地。

- [x] 任务编号：BE-043
  模块：运行与安全管理；目标：Runtime参数及保留影响。
  接口/服务：GET/PUT runtime-config；POST retention-impact。
  请求参数与响应字段：参数/version/impact→详情/结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：校验采样/留存/时区；impact绑定参数/revision且10分钟过期。
  异常处理：CONFIG_FIELD_IMMUTABLE、RETENTION_IMPACT_EXPIRED。
  数据表与协作依赖：runtime_config、retention_impact；DB-P05、FE-043/044。
  验收标准：首次聚合锁时区，保留缩短只发布后生效。
  测试要求：过期/锁定/组合边界；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-044
  模块：运行与安全管理；目标：Access凭证全生命周期与鉴权。
  接口/服务：/admin/access-credentials及rotate/enable/disable；业务过滤器。
  请求参数与响应字段：命令/version或Bearer→一次Token/权限上下文；类型、必填及错误HTTP见协议字典和附录。
  业务流程：HMAC摘要，代次即时轮换；Alias/IP检查，可信代理链，过期计算。
  异常处理：ACCESS_TOKEN_INVALID、ACCESS_IP_DENIED、MODE_NOT_SUPPORTED。
  数据表与协作依赖：access_credential、access_credential_alias；DB-P05、FE-045/046/047。
  验收标准：旧Token立即失效，application由凭证，Token读取无原文。
  测试要求：两实例轮换、IP/IPv6代理、过期；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-045
  模块：运行与安全管理；目标：审计查询与导出。
  接口/服务：GET /admin/audit-logs、/{id}、/export。
  请求参数与响应字段：审计筛选→页/详情/CSV；类型、必填及错误HTTP见协议字典和附录。
  业务流程：权限固定管理/运维，字段脱敏，失败事务独立记录。
  异常处理：AUDIT_DATA_UNAVAILABLE、EXPORT_TOO_LARGE。
  数据表与协作依赖：audit_log；DB-P01/P05、FE-048。
  验收标准：request_id可关联成功失败，无Secret摘要前值。
  测试要求：筛选、权限、CSV注入；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-046
  模块：运行与安全管理；目标：开发上下文与示例。
  接口/服务：GET /admin/developer-access/context、code-sample。
  请求参数与响应字段：mode/alias/language→context/samples；类型、必填及错误HTTP见协议字典和附录。
  业务流程：只从公开配置和授权已发布Alias生成，秘密为占位符。
  异常处理：Alias失效、权限拒绝。
  数据表与协作依赖：config_snapshot；DB-P05、FE-049/050。
  验收标准：示例字段与SDK一致，复制无真实密钥。
  测试要求：三模式示例编译/占位检查；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-047
  模块：运行与安全管理；目标：在线测试入口。
  接口/服务：POST /admin/developer-access/test/chat、/admin/developer-access/test/chat/stream。
  请求参数与响应字段：管理身份/ApiTestCommand→ApiTestResult/StreamEvent SSE；类型、必填及错误HTTP见协议字典和附录。
  业务流程：复用运行链；管理运维application=ADMIN_CONSOLE，开发受scope。
  异常处理：参数/权限/Provider错误。
  数据表与协作依赖：trace、attempt；DB-P03/P04、FE-051/052。
  验收标准：source可识别且不使用业务Token冒充测试。
  测试要求：同步/流式/取消/只读拒绝；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-048
  模块：运行与安全管理；目标：清理和保留任务。
  接口/服务：内部调度服务。
  请求参数与响应字段：当前ACTIVE留存策略→分批清理结果；类型、必填及错误HTTP见协议字典和附录。
  业务流程：每批≤1000Trace，只有聚合成功可删；样本独立；快照保护引用。
  异常处理：未消费跳过告警，不删活动快照。
  数据表与协作依赖：trace关联表、usage_aggregate、config_snapshot；DB-P05。
  验收标准：删明细不删未过期Usage，不遗留孤儿数据。
  测试要求：事件未完成、阈值、批次失败；业务事务增加失败回滚断言，读取增加权限断言。


## BE-P09 SDK与扩展（6项）

> 交付完成：后端执行模型（beidao）已完成 BE-P09 全部 6 项任务（BE-049 ~ BE-054）。完成成果包括 LightAiClient/ChatRequest/ChatResponse/StreamEvent/ModelInfo 统一 SDK 客户端、LocalRuntimeDefinition/LocalRuntimeValidator/LocalLightAiClient 本地运行内核、StandaloneLightAiClient 远程 HTTP 客户端（JDK 17 HttpClient 复用、容忍未知字段、协议错误安全摘要）、FlowStreamPublisher（背压控制、单订阅、取消语义）、SecretManager（多 Provider 冲突检测、短期缓存、主动失效、内存清零）与 TraceExportCoordinator（有界队列 10000、1s/5s/30s 幂等重试、异常隔离）。全仓 12 模块 mvn clean test 全部通过（304+ 测试，0 失败），详细测试证据见 COMMUNICATION.md H-023。

- [x] 任务编号：BE-049
  模块：SDK与扩展；目标：客户端公开对象与生命周期。
  接口/服务：LightAiClient builder/models/chat/chatAsync/stream/close。
  请求参数与响应字段：不可变请求/config→公开响应/异常；类型、必填及错误HTTP见协议字典和附录。
  业务流程：复制集合、线程安全、close幂等、关闭拒绝新请求。
  异常处理：CLIENT_CLOSED、参数错误。
  数据表与协作依赖：Local内存；BE-027/028。
  验收标准：公开API无Provider或Spring类型，版本依赖统一。
  测试要求：并发、集合修改、重复close；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-050
  模块：SDK与扩展；目标：Local Runtime构建与执行。
  接口/服务：LOCAL_RUNTIME builder。
  请求参数与响应字段：LocalRuntimeDefinition/secret suppliers→RuntimeClient；类型、必填及错误HTTP见协议字典和附录。
  业务流程：本地无网络校验生成snapshot1，无热更新/DB/Admin；有界Trace。
  异常处理：引用/能力失败阻止创建。
  数据表与协作依赖：进程内；BE-019/024/025。
  验收标准：构建不访问Provider或DB，规则与Server相同。
  测试要求：无候选、Secret边界、同步流；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-051
  模块：SDK与扩展；目标：远程Client请求与异常。
  接口/服务：STANDALONE_CLIENT。
  请求参数与响应字段：TokenSupplier/请求→HTTP响应/统一异常；类型、必填及错误HTTP见协议字典和附录。
  业务流程：build不探测，调用读取Token；仅确认未写请求体才可transport重试。
  异常处理：SERVER_PROTOCOL_ERROR、HTTP统一错误。
  数据表与协作依赖：不访问DB；BE-027/028。
  验收标准：不自动重放已发模型调用，响应未知字段忽略。
  测试要求：建连失败、半发送、未知响应；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-052
  模块：SDK与扩展；目标：Future取消和Flow背压。
  接口/服务：chatAsync/Flow.Subscription。
  请求参数与响应字段：cancel/request(n)→单次终止/事件；类型、必填及错误HTTP见协议字典和附录。
  业务流程：每Publisher单Subscriber；缓冲≤32；n≤0 error；cancel不DONE。
  异常处理：CancellationException、IllegalArgumentException。
  数据表与协作依赖：进程内或远程断开；BE-029。
  验收标准：按需求下发，无丢块，无onError后onComplete。
  测试要求：慢消费者、32边界、取消竞态；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-053
  模块：SDK与扩展；目标：Secret SPI选择失效与缓存。
  接口/服务：supports/resolve/invalidate。
  请求参数与响应字段：引用/deadline/cancel→短期ResolvedSecret；类型、必填及错误HTTP见协议字典和附录。
  业务流程：唯一supports匹配；到期不缓存；rotation主动invalidate。
  异常处理：SECRET_PROVIDER_CONFLICT、SECRET_RESOLUTION_FAILED。
  数据表与协作依赖：credential_secret或本地supplier；BE-020。
  验收标准：不解析多实现冲突，Secret不进入快照/异常。
  测试要求：过期、冲突、解析取消；业务事务增加失败回滚断言，读取增加权限断言。

- [x] 任务编号：BE-054
  模块：SDK与扩展；目标：TraceExporter隔离。
  接口/服务：TraceExporter.export。
  请求参数与响应字段：脱敏batch_id→ExportResult；类型、必填及错误HTTP见协议字典和附录。
  业务流程：事务后异步；1/5/30秒三次重试同batch；Local队列≤10000。
  异常处理：最终失败只指标/安全日志。
  数据表与协作依赖：Trace或Local队列；BE-033。
  验收标准：导出失败不修改业务成功，无正文和密钥。
  测试要求：三次重试、队列满、重复batch；业务事务增加失败回滚断言，读取增加权限断言。



## BE-P10 交付与验收（6项）

- [ ] 任务编号：BE-055
  模块：交付与验收；目标：Starter两模式装配。
  接口/服务：AutoConfiguration与Embedded Admin。
  请求参数与响应字段：light-ai属性/宿主Bean→条件组件；类型、必填及错误HTTP见协议字典和附录。
  业务流程：MissingBean、Servlet/Reactive路由、path冲突、schema/Redis验证。
  异常处理：ADMIN_PATH_CONFLICT、Bean冲突、启动参数错误。
  数据表与协作依赖：Embedded仓储；BE-003/049、FE-P09。
  验收标准：client模式无Runtime/DB/Admin，无Web仍有Client。
  测试要求：Boot3.3/3.4/3.5矩阵与Bean覆盖；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-056
  模块：交付与验收；目标：Server启动就绪摘流。
  接口/服务：/health/live、/health/ready与进程关闭。
  请求参数与响应字段：部署参数/存储/快照→运行状态；类型、必填及错误HTTP见协议字典和附录。
  业务流程：启动检查，DOWN拒绝新调用；先DRAINING再等待/取消存量。
  异常处理：必需存储不可用、快照无效。
  数据表与协作依赖：runtime_instance、核心仓储；DB-P05。
  验收标准：Redis中断全局fail-closed，恢复先收敛快照。
  测试要求：进程重启、Redis故障、优雅关闭；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-057
  模块：交付与验收；目标：Metrics和安全日志。
  接口/服务：内部指标/日志入口。
  请求参数与响应字段：Trace/Attempt/容量事件→低基数指标；类型、必填及错误HTTP见协议字典和附录。
  业务流程：按PRD5.5指标，trace/attempt/credential ID禁标签，密钥过滤。
  异常处理：Exporter失败有告警，日志失败不泄漏。
  数据表与协作依赖：无新增业务表；BE-P06。
  验收标准：指标包含聚合延迟/恢复/熔断，日志可按trace定位。
  测试要求：标签基数与敏感内容扫描；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-058
  模块：交付与验收；目标：关键跨模块回归。
  接口/服务：运行/管理全链路。
  请求参数与响应字段：PRD6.6夹具→验收证据；类型、必填及错误HTTP见协议字典和附录。
  业务流程：首次接入、两实例发布、容量/恢复、Token/权限、Usage复算。
  异常处理：任何关键失败登记COMMUNICATION。
  数据表与协作依赖：全部核心表；FE-P09、DB-P05。
  验收标准：每个PRD验收场景有测试或手工证据。
  测试要求：执行本文附录6.6场景矩阵；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-059
  模块：交付与验收；目标：SDK与Starter制品兼容验证。
  接口/服务：Maven制品与样例。
  请求参数与响应字段：Java17/21及Boot矩阵→兼容报告；类型、必填及错误HTTP见协议字典和附录。
  业务流程：普通Java无Spring依赖，客户端同主版本忽略响应新增字段。
  异常处理：主版本差异告警、不静默改请求。
  数据表与协作依赖：无新增表；BE-049/055。
  验收标准：同步/异步/流式样例可编译运行，模式隔离。
  测试要求：两Java×三Boot×两Web类型关键组合；业务事务增加失败回滚断言，读取增加权限断言。

- [ ] 任务编号：BE-060
  模块：交付与验收；目标：性能与故障恢复验收。
  接口/服务：压测与双实例故障演练。
  请求参数与响应字段：总文档基线负载→P95/并发/延迟报告；类型、必填及错误HTTP见协议字典和附录。
  业务流程：200流、32缓冲、20ms附加P95、恢复/队列/保留验证。
  异常处理：未达标记录具体瓶颈和阻断项。
  数据表与协作依赖：全运行存储；FE-054、DB-P05。
  验收标准：量化指标达标或明确未通过，不以单元测试代替压测。
  测试要求：稳态压测、断Redis/DB、重启与对账；业务事务增加失败回滚断言，读取增加权限断言。


## 需求契约附录：API、运行流程和完整验收基线

以下为PRD原编号。采用口径以PROJECT_DOCUMENT第4节和本文补充字典为准。页面DTO完整字段见FRONTEND_PLAN需求契约附录，物理字段见DATABASE_PLAN。


| 方法与路径 | 请求 | 响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/overview/filters | 可选 alias_id | OverviewFilterOptions | 可查看角色 | ACCESS_DENIED、CONFIG_DATA_UNAVAILABLE、OBJECT_REFERENCE_INVALID |
| GET /admin/overview/summary | OverviewQuery，不使用 granularity | OverviewSummary | 可查看角色；按 application_scope 过滤 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/overview/trends | OverviewQuery，包含 granularity | OverviewTrendResult | 可查看角色；按 application_scope 过滤 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/overview/exceptions | OverviewQuery，不使用 currency 与 granularity | OverviewExceptionResult | 可查看角色；Credential 明细按角色裁剪 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |

三个数据接口使用同一 start_at、end_at、application、alias_id 和 provider_id 语义，并在响应头返回 X-Data-Updated-At。列表或聚合数据源暂时不可用时返回 OBSERVATION_DATA_UNAVAILABLE，不返回成功状态和空集合。请求范围无业务数据时返回字段完整的零值或空 items；权限裁剪发生在聚合前，响应数量不能包含调用方无权查看的数据。

## 4.2 模型接入

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
#### 4.6.2.1 发布物与职责

| 发布物 | 包含能力 | 不包含能力 |
|---|---|---|
| light-ai-client | 统一协议对象、Standalone HTTP 客户端、同步/异步/流式 API、错误转换 | Provider Adapter、路由、管理页面和存储实现。 |
| light-ai-runtime | light-ai-client、进程内 Runtime Core、Provider SPI、路由、容量、可靠性、Trace 与 LocalRuntimeDefinition | 管理页面、配置草稿和数据库发布流程。 |
| light-ai-spi | ProviderAdapter、SecretProvider、AuthContextProvider、TraceExporter 等扩展接口 | 默认实现与网络客户端。 |
| light-ai-spring-boot-starter | 自动装配、Embedded Runtime、Standalone Client、宿主资源复用和 Embedded Admin UI | Standalone Server 可执行程序。 |

Java SDK 的公开对象包括客户端、请求、消息、响应、用量、模型信息、流式事件、客户端配置和统一调用异常。请求、响应与用量映射 2.6.10 的统一模型调用信息；公开 API 不出现任何具体 Provider SDK 类型。

V1.0 发布物使用 Java 17 字节码并验证 Java 17 与 Java 21。公共 API 不依赖 Spring；`light-ai-runtime` 与 `light-ai-client` 可以在普通 Java 应用中单独使用。依赖版本在同一 V1.x 系列内统一管理，接入方不得混用不同版本的 client、runtime 与 spi。

#### 4.6.2.2 LightAiClient 操作

| 方法 | 输入 | 返回 | 执行规则 |
|---|---|---|---|
| builder | LightAiClientConfig 各字段 | LightAiClient.Builder | build 时执行模式必填、URL、超时和本地定义校验。 |
| models | 无 | List<ModelInfo> | LOCAL_RUNTIME 从本地快照读取；STANDALONE_CLIENT 调用 GET /v1/models。 |
| chat | ChatRequest | ChatResponse | 当前线程等待完成；失败抛出统一调用异常。 |
| chatAsync | ChatRequest | CompletableFuture<ChatResponse> | 与 chat 共用执行链，完成、失败和取消只发生一次。 |
| stream | ChatRequest | Flow.Publisher<StreamEvent> | SDK 强制 stream=true；每个 Publisher 只允许一个 Subscriber。 |
| close | 无 | void | 拒绝新调用，按 close_timeout_ms 等待现有调用并释放传输与本地 Runtime 资源。 |

LightAiClient 构造完成后线程安全，可以作为应用单例复用。ChatRequest 和响应对象不可变，Builder 在 build 时复制集合，调用期间不读取接入方后续修改。models 返回不可变列表。调用 close 后执行任何方法均抛出 CLIENT_CLOSED；重复 close 幂等。

#### 4.6.2.3 LOCAL_RUNTIME 数据流

LOCAL_RUNTIME 构建时读取本地运行定义，执行与配置发布相同的字段、引用、能力、价格、限流和可靠性校验，但不执行运行实例兼容检查，也不请求外部 Provider。校验结果一次性返回能够确定的对象、字段和原因；任一错误阻止客户端创建。

校验成功后在进程内生成 snapshot_no=1 的不可变 ConfigSnapshot，content_checksum 由规范化定义计算。V1.0 Local Runtime 不提供运行中热更新；需要变更时由宿主构造新的 LightAiClient 并完成引用切换。凭证通过 credential_secret_suppliers 按 credential_id 延迟取得，明文不进入 LocalRuntimeDefinition、快照、异常和日志。

一次调用按 4.3.4 运行路由执行。容量、队列和熔断状态保存在当前进程；Trace 默认保存在有界内存缓冲并输出 Metrics，注册 TraceExporter 后异步发送脱敏 Trace。Local Runtime 不注册 RuntimeInstance，不参与 4.5 配置发布，也不提供 Admin UI。

#### 4.6.2.4 STANDALONE_CLIENT 数据流

STANDALONE_CLIENT 在 build 时规范化 base_url、验证 HTTPS 和 Token Supplier，但不执行网络探测。models、chat 和 stream 调用时读取当前 Token，构造 Authorization: Bearer 头，并分别访问 `/v1/models` 或 `/v1/chat/completions`。application 由服务端 Access Credential 决定，客户端不能通过请求头覆盖。

SDK 把调用方 trace_id 写入请求体；调用方未提供时不预先生成，由服务端响应取得。HTTP 非 2xx 响应解析统一失败结果并转换为 SDK 统一调用异常；无法解析的响应转换为 SERVER_PROTOCOL_ERROR，并保存状态码与最多 1000 字符的脱敏摘要。响应体、SSE 与异常解析都不记录 Authorization 和消息正文。

transport_retry_count 默认 0。启用后只对 DNS、TCP 或 TLS 建连阶段且确认请求体尚未写入的失败执行退避重试；请求体可能已经发送、收到任意响应头、取得 trace_id 或开始 SSE 后均不重试。服务端 retryable 只提供给业务代码判断，SDK不自动重新发起模型调用。

#### 4.6.2.5 异步、流式与取消

chatAsync 返回的 CompletableFuture 取消时，SDK 同时触发底层调用 CancellationSignal。LOCAL_RUNTIME 取消待执行任务或向当前 Adapter 传播取消；STANDALONE_CLIENT 取消 HTTP 请求。future 以 CancellationException 结束，服务端已经创建 Trace 时记录 CANCELLED。同步 chat 所在线程被中断时执行同样的取消流程并恢复线程中断标记。

stream 返回 Java Flow.Publisher。Publisher 遵循 request(n) 背压：n 小于等于 0 时调用 onError(IllegalArgumentException)；只有存在正需求时才发送事件。每个订阅最多缓冲 32 个 StreamEvent，达到上限后暂停读取 Provider 或 HTTP 响应，不丢弃事件。Subscriber.cancel 立即停止下游事件、关闭远程连接或触发本地 CancellationSignal，不发送 DONE 和 onComplete。

正常顺序为 START、零到多个 DELTA、可选 USAGE、DONE、onComplete。Provider 或服务端错误通过 onError 携带统一调用异常并终止；onError 后不得调用 onComplete。stream_idle_timeout_ms 只计算相邻网络块等待时间，服务端 ReliabilityPolicy.total_timeout_ms 继续限制完整调用。

完成标准：两种客户端模式使用同一公开对象；同步、异步和流式结果与 HTTP 协议字段一一映射；异步和流式取消能够释放容量；背压不丢块；客户端不会自动造成重复模型调用；所有读取接口和异常均不暴露 Token 或 Provider Credential。

### 4.6.3 Spring Boot Starter 与 Embedded Admin UI

Spring Boot 官方自定义自动装配规则要求使用独立配置命名空间、条件装配和 MissingBean 回退，使宿主能够覆盖默认 Bean。轻享 AI 使用 `light-ai` 命名空间，自动装配类通过 AutoConfiguration.imports 注册，不扫描宿主业务包。参考依据为 [Spring Boot 创建自动装配](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)。

V1.0 Starter 支持 Spring Boot 3.3、3.4 与 3.5，覆盖 Servlet Web 应用和 Reactive Web 应用。宿主没有 Web 栈时仍可装配 LightAiClient 与 Embedded Runtime，但不创建 Admin UI。

#### 4.6.3.1 自动装配条件与顺序

light-ai.enabled=false 时不创建任何 Light AI Bean。mode=STANDALONE_CLIENT 时只创建 SpringLightAiProperties、HTTP Transport、LightAiClient 和健康检查，不加载 Runtime Core、数据库、Redis、管理页面与 Provider Adapter。mode=EMBEDDED 时先绑定并校验配置，再解析宿主 DataSource 和可选 RedisConnectionFactory，验证数据结构，注册 Adapter，加载活动快照，创建 Runtime Core 与 LightAiClient，最后挂载 Admin UI 并注册 RuntimeInstance。

默认 Bean 均使用 ConditionalOnMissingBean。宿主提供 LightAiClient 时 Starter 不再创建客户端；提供 ProviderAdapter、SecretProvider、AuthContextProvider 或 TraceExporter 时加入对应有序集合。两个 ProviderAdapter 返回相同 provider_type 时启动失败并列出冲突 Bean 名称，不按装配顺序静默覆盖。

#### 4.6.3.2 配置项分组

| 分组 | 字段 | 作用与模式规则 |
|---|---|---|
| 基础 | light-ai.enabled、light-ai.mode、light-ai.application | 控制装配模式；Embedded 必须具有 application。 |
| Standalone Client | base-url、access-token、connect-timeout、request-timeout、stream-idle-timeout、transport-retries | 只在 STANDALONE_CLIENT 生效；Token 属性值在 Actuator 与配置日志中脱敏。 |
| Admin UI | enabled、path、local-access-enabled、trusted-network-cidrs | 只在 EMBEDDED 生效；path 冲突或不合法时启动失败。 |
| Runtime Instance | instance-id、zone、shutdown-timeout | 决定心跳身份、区域和优雅关闭时间。 |
| Storage | data-source-bean-name、redis-connection-factory-bean-name、redis-required、schema-mode | 指定宿主 Bean、集群容量状态和数据结构处理方式。 |

核心接入信息见 2.6.11，各模式实际使用的配置项以上表和部署配置说明为准。Spring 配置元数据必须包含字段说明，使 IDE 可以补全。access-token 不允许出现在 `/actuator/configprops` 明文响应；Bean Validation 失败时启动异常指出属性路径和规则，不输出当前 Secret 值。

#### 4.6.3.3 自动装配 Bean

| Bean 或资源 | EMBEDDED | STANDALONE_CLIENT | 宿主覆盖规则 |
|---|---|---|---|
| LightAiClient | 创建 | 创建 | 宿主同类型 Bean 优先。 |
| RuntimeCore | 创建 | 不创建 | 宿主可提供完整实现。 |
| ConfigRepository、TraceRepository、UsageRepository | 创建 | 不创建 | 使用指定 DataSource。 |
| CapacityStore | Redis 或进程内 | 不创建 | 宿主可提供自定义实现。 |
| ProviderAdapterRegistry | 创建 | 不创建 | 收集全部 ProviderAdapter Bean。 |
| SecretProviderRegistry | 创建 | 不创建 | 收集 SecretProvider Bean。 |
| AuthContextProvider | 可选 | 不创建 | 宿主实现决定管理身份与数据范围。 |
| TraceExporter | 可选集合 | 不创建 | 失败不阻断业务响应并产生内部告警。 |
| Embedded Admin UI | 条件创建 | 不创建 | 需要 Web 应用且 admin.enabled=true。 |
| LightAiHealthContributor | 创建 | 创建 | Actuator 存在时注册。 |

#### 4.6.3.4 Embedded Admin UI 路径与认证

Admin UI 的静态资源、页面路由和管理 API 统一挂载在 light-ai.admin.path 下；业务模型调用仍通过注入的 LightAiClient，不增加宿主公开模型端点。启动时检查 Admin 根路径是否与 Spring MVC、WebFlux、Actuator 和静态资源映射冲突，冲突返回 ADMIN_PATH_CONFLICT。

存在 AuthContextProvider 时，每个页面和管理请求从宿主安全上下文取得 user_id、display_name、roles、application_scope，并按 2.4 校验。没有 AuthContextProvider 时 Admin UI 默认不可访问；只有 local-access-enabled=true 且直连来源为 loopback 或 trusted-network-cidrs 时，才建立 operator_id=LOCAL_ADMIN 的系统管理员上下文。代理头只在来源属于 RuntimeConfig.trusted_proxy_cidrs 时解析。拒绝访问返回 ACCESS_DENIED 并记录安全日志。

#### 4.6.3.5 启动、就绪与关闭

schema-mode=VALIDATE 时只验证所需表和版本；不一致阻止 Embedded Runtime 就绪。MIGRATE 时在数据库锁内执行产品自带的顺序迁移，失败回滚当前迁移并阻止启动。redis-required=true 且 Redis 不可用时不创建 RuntimeCore；false 时使用进程内容量状态，并在健康信息中标明仅适合单实例。

存在有效 ACTIVE 快照时加载后进入就绪并开始心跳；首次安装使用 snapshot_no=0。数据库暂时中断时继续使用内存快照处理调用，但配置管理、Trace 持久化一致性或容量安全条件不满足时按对应错误码拒绝新请求。关闭时先发送 accepting_requests=false 心跳并使健康状态 DOWN，再拒绝新调用；等待 shutdown-timeout 后取消剩余调用、结算或释放 CapacityReservation、刷新关键 Trace，最后关闭线程池。

#### 4.6.3.6 宿主与外部扩展 SPI

| SPI 方法 | 输入 | 返回 | 线程与失败规则 |
|---|---|---|---|
| SecretProvider.supports | secret_ref | boolean | 纯函数；多个实现同时匹配时返回 SECRET_PROVIDER_CONFLICT。 |
| SecretProvider.resolve | SecretResolveRequest | CompletionStage<ResolvedSecret> | 遵守 deadline 与 cancellation；失败映射 SECRET_RESOLUTION_FAILED。 |
| SecretProvider.invalidate | secret_ref、version | void | Credential 轮换或管理员显式失效时调用；重复调用幂等。 |
| AuthContextProvider.currentContext | 当前宿主管理请求 | AuthContext | 每个请求调用一次；异常按未认证处理并记录安全日志。 |
| TraceExporter.export | TraceExportBatch | CompletionStage<TraceExportResult> | 异步执行，不阻断模型响应；实现必须按 batch_id 幂等。 |

SecretProviderRegistry 按 supports 选择唯一实现。没有实现匹配、多个实现匹配、解析超时和返回空密钥均使当前 Credential 不可用；原始异常脱敏后进入 Attempt 或 ProviderCheckRecord。Runtime 可以缓存 ResolvedSecret，但缓存到期不得晚于 expires_at，Credential 轮换和 invalidate 必须主动清除。

AuthContextProvider 只用于 Embedded Admin UI 和管理 API，不参与业务 LightAiClient 的 application 判定。authenticated=false、roles 为空或 application_scope 不满足时拒绝请求。宿主 Cookie、Authorization 和完整 Principal 不复制到 AuthContext 与 AuditLog。

Trace 导出职责见 2.6.11。数据库形态在 Trace 最终事务后异步生成脱敏批次，失败按 1s、5s、30s 最多重试三次；Local Runtime 使用最多 10000 条的有界内存队列。最终失败产生 exporter_failure 指标和脱敏日志，不修改已经完成的 Trace 与业务响应。

完成标准：属性绑定、条件装配与 Bean 覆盖结果确定；Embedded 和 Standalone Client 不加载对方组件；管理路径与宿主认证安全；Secret、认证和 Trace 导出 SPI 的输入输出与失败边界确定；单实例与集群存储要求可验证；启动失败能定位属性、Bean 或数据结构；关闭后无活动容量预占和运行线程。

### 4.6.4 Standalone Server

#### 4.6.4.1 部署配置与启动顺序

Standalone Server 从环境变量、受保护配置文件或部署平台 Secret 注入 StandaloneServerConfig。普通配置可以在启动摘要中输出，database_password、redis_password、TLS 私钥和内部服务身份始终脱敏。public_base_url 必须是 HTTPS；tls_mode=TERMINATE 时进程加载证书，BEHIND_PROXY 时只信任配置的反向代理来源并由外层终止 TLS。

启动顺序固定为：校验部署配置；验证或迁移数据库结构；连接数据库；连接 Redis 并执行原子能力检查；注册内置与自定义 Provider Adapter；加载 ACTIVE ConfigSnapshot；初始化路由、容量、可靠性、Trace 和 Usage 组件；注册 RuntimeInstance 并发送首次心跳；启动业务、管理与内部 HTTP 路由；全部就绪检查通过后把 readiness 改为 UP。任一步失败均不得短暂进入 UP。

migration_mode=VALIDATE 发现版本不匹配时退出进程；MIGRATE 在全局数据库迁移锁内执行，其他实例等待并在完成后重新验证。单实例允许 redis_url 为空并使用进程内状态；检测到相同数据库存在其他 ONLINE Standalone 实例且未配置 Redis 时 readiness=DOWN，防止形成分裂的容量状态。

#### 4.6.4.2 对外与内部入口

| 方法与路径 | 认证 | 成功响应 | 主要用途 |
|---|---|---|---|
| GET /health/live | 无 | HealthResponse | 仅确认进程与 HTTP 线程可响应。 |
| GET /health/ready | 无 | ReadinessResponse 的 status 与 time | 供负载均衡摘挂实例，不暴露内部连接信息。 |
| GET /v1/models | Bearer Access Token | UnifiedModelList | 返回凭证范围内 Alias。 |
| POST /v1/chat/completions | Bearer Access Token | UnifiedChatResponse 或 SSE | 执行统一模型调用。 |
| GET /admin | 管理身份 | Admin UI | 进入管理端。 |
| /admin/** | 管理身份与角色 | 管理 API | 执行 4.1—4.6 管理能力。 |
| /internal/** | 实例服务身份或双向 TLS | 内部对象 | 心跳、快照和发布实例上报。 |

业务 API、管理 API 和内部 API 使用独立认证过滤链。Bearer Access Token 只能访问 `/v1/**`，管理会话不能作为业务 Token，实例身份只能访问 `/internal/**`。路由未命中统一返回 404，不把管理或内部路由是否存在暴露给无权限身份。

#### 4.6.4.3 健康与就绪规则

/health/live 只在进程无法继续运行时失败，不检查外部 Provider、数据库和 Redis。/health/ready 同时要求 DATABASE、CAPACITY_STORE、CONFIG_SNAPSHOT 和 ADAPTER_REGISTRY 为 UP，并要求 accepting_requests=true。外部 Provider 暂时不可用不影响 Server readiness，由候选运行状态、Fallback 和统一错误处理。

公开 readiness 响应只包含 status 与 time。系统管理员通过运行实例详情查看完整 ReadinessCheck，包括 active_snapshot_no、检查项、机器码与 last_success_at。数据库或共享容量状态失效时实例立即停止接受新请求并进入 DRAINING 或 STALE；已建立流按当前本地快照继续到完成或 ReliabilityPolicy 超时。

#### 4.6.4.4 集群一致性

集群实例共享数据库中的配置、访问凭证摘要、Trace、Usage、发布和审计数据，并共享 Redis 中 RPM、TPM、并发、队列和熔断状态。业务请求不要求会话粘滞；一个 SSE 调用建立后由接收实例持续处理。每个实例使用唯一 instance_id 心跳，并按 4.5.2 完成快照准备与激活。

Access Credential 编辑、轮换、停用和删除提交后，新请求在全部实例上立即读取相同持久状态；允许最多一个 dashboard_refresh_seconds 的本地只读缓存，轮换、停用和删除通过失效通知主动清除缓存。失效通知不可用时，安全状态读取绕过缓存，避免旧 Token 继续鉴权。

#### 4.6.4.5 优雅关闭与安全边界

实例收到正常终止信号后立即把 accepting_requests 改为 false、发送 DRAINING 心跳并使 readiness=DOWN。负载均衡停止分配新连接后，实例等待当前同步与流式调用；到达 shutdown_timeout_seconds 后向 Provider 传播取消，最终化 Trace，释放或结算 CapacityReservation，并关闭数据库与 Redis 连接。异常退出遗留的容量预占由 Watchdog 按 expires_at 回收。

请求正文上限使用 RuntimeConfig.max_request_chars 与 HTTP 解码上限中的较小值。Authorization、Cookie、数据库与 Redis 密码、Provider Credential、完整 secret_ref 和消息正文不得进入访问日志。SSE 响应禁用代理缓冲和内容压缩，定期数据由真实 Provider 事件驱动，不额外发送会被 SDK 当作模型内容的心跳块。

完成标准：部署字段有确定来源、默认值与条件校验；启动和迁移不会产生假就绪；三类入口身份隔离；健康检查可以安全驱动负载均衡；多实例容量、凭证状态与配置一致；优雅关闭不接收新请求并最终释放运行资源。

### 4.6.5 开发接入管理接口契约

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/developer-access/context | alias_id | DeveloperAccessContext | 可查看角色 | MODEL_ALIAS_NOT_FOUND、ACCESS_DENIED |
| GET /admin/developer-access/code-sample | CodeSampleRequest 查询字段 | CodeSampleResult | 可查看角色 | FIELD_VALIDATION_FAILED、MODEL_ALIAS_NOT_FOUND |
| POST /admin/developer-access/test/chat | ApiTestCommand | ApiTestResult | 系统管理员、运维人员、开发人员 | FIELD_VALIDATION_FAILED、MODEL_ALIAS_NOT_FOUND、MODEL_CAPABILITY_NOT_SUPPORTED、CAPACITY_LIMITED |
| POST /admin/developer-access/test/chat/stream | ApiTestCommand，stream 固定 true | text/event-stream，数据映射 StreamEvent | 系统管理员、运维人员、开发人员 | FIELD_VALIDATION_FAILED、MODEL_ALIAS_NOT_FOUND、MODEL_CAPABILITY_NOT_SUPPORTED、CAPACITY_LIMITED |

开发测试接口使用管理身份与 application_scope，不接受 Authorization Token 字段。普通错误响应使用统一失败结果；流建立后的错误按 2.6.10 的流式规则结束。测试输入转换为统一模型请求时，system_message 位于 messages 首项，user_message 位于末项，metadata.tags 自动增加 light_ai_test 和 operator_id；页面不能覆盖这些系统标签。

## 4.7 统一模型调用

### 4.7.1 HTTP 接口

#### 4.7.1.1 协议边界与公共请求规则

Standalone Server 对外提供 `GET /v1/models` 和 `POST /v1/chat/completions`。请求与响应使用 UTF-8 JSON；流式响应使用 UTF-8 SSE。V1.0 不提供文本补全、Embedding、图片、音频、Realtime、Responses、Tool Calling 与多模态端点，这些路径返回 404。

| 请求项 | 规则 | 失败处理 |
|---|---|---|
| Authorization | 固定 `Bearer <Standalone Access Token>` | 缺失、格式错误、摘要不匹配、停用或过期返回 ACCESS_TOKEN_INVALID。 |
| Content-Type | POST 固定 application/json，可带 charset=utf-8 | 其他类型返回 UNSUPPORTED_CONTENT_TYPE。 |
| Accept | 非流式允许 application/json 或 */*；流式允许 text/event-stream 或 */* | 不兼容返回 FIELD_VALIDATION_FAILED。 |
| Content-Encoding | V1.0 只接受 identity | 压缩请求返回 UNSUPPORTED_CONTENT_ENCODING。 |
| X-Trace-Id | 可选，规则与请求体 trace_id 相同 | 与请求体不一致或格式错误返回 FIELD_VALIDATION_FAILED。 |
| User-Agent | 可选，最多 512 字符 | 超长截断后仅写 Trace.user_agent，不影响调用。 |
| 请求体大小 | JSON 解码前限制为 `min(部署 HTTP 上限, max_request_chars × 4 + 65536)` 字节 | 超限返回 REQUEST_TOO_LARGE，且不解析正文。 |
| 未知字段 | 顶层、Message、metadata、stream_options 中未知字段均拒绝 | 返回 FIELD_VALIDATION_FAILED 并在 error.param 指出字段。 |

服务端响应始终设置 X-Light-AI-Version，并在存在 Trace 时设置 X-Trace-Id；身份、IP、请求体解码或 Alias 解析前失败时不返回 X-Trace-Id。错误响应设置 Cache-Control: no-store。任何响应头不得包含 Access Credential、Provider Credential、完整 secret_ref 和消息正文。

#### 4.7.1.2 GET /v1/models

服务端完成 Token、状态、有效期和来源 IP 鉴权后，从当前 ACTIVE ConfigSnapshot 读取 ModelAlias，只保留 enabled=true、具有至少一个可用配置候选且位于 allowed_alias_ids 范围内的对象。空 allowed_alias_ids 表示全部已发布 Alias。实时容量耗尽和临时熔断不从列表移除模型，避免模型目录随瞬时状态抖动。

成功返回 UnifiedModelList，object 固定 list，data 按 id asc，不分页。每项包含 OpenAI 风格的 id、object、created、owned_by，以及 light_ai 下的显示名称、流式、system、temperature、top_p、stop 能力与范围、最大上下文、最大输出和更新时间。Alias 能力表示至少一个已发布候选可以处理对应参数，具体调用仍按候选模型范围过滤。列表为空仍返回 HTTP 200 与空数组。该接口不创建业务 Trace，鉴权失败进入安全日志，成功调用只记录低基数接口指标。

#### 4.7.1.3 非流式 Chat Completions

stream=false 或未提供时执行非流式流程。服务端在读取当前活动快照后按固定顺序处理：校验访问身份和来源 IP；解析 JSON 与字段结构；解析有权限的 Model Alias；检查 trace_id 唯一；生成 Trace；校验 Message、参数和 Provider Option；使用候选 TokenEstimator 估算输入 Token；过滤能力、上下文、停用、熔断和容量不满足的候选；进入 4.3.4 路由、凭证、可靠性和 Provider 调用；结算 Usage 与 Cost；最终化 Trace；构造 UnifiedChatResponse。

Alias 解析前失败不创建 Trace。Alias 解析成功后，无论能力过滤、容量、Provider、结算或内部执行成功与否都必须最终化同一 Trace，并在错误响应返回 trace_id。服务端内部 RETRY、CREDENTIAL_FAILOVER 和 FALLBACK 仍属于同一 Trace，不改变外部响应 ID。

同步成功返回 HTTP 200 和 UnifiedChatResponse。choices 只有一项，model 保留请求 Alias；Provider 实际模型只出现在 light_ai 扩展。响应 Usage 表示最终成功 Attempt，light_ai.cost 表示该 Trace 全部可计费 Attempt 成本。Trace 最终事务提交失败时不返回成功模型结果，返回 INTERNAL_ERROR 并产生高优先级告警，避免响应成功却无法追踪。

#### 4.7.1.4 流式 Chat Completions

stream=true 时，服务端先完成身份、请求、Alias、能力、上下文、容量和路由处理，并连接首个 Provider。Provider 在首个可输出内容前失败时，运行时仍可按策略 RETRY、CREDENTIAL_FAILOVER 或 FALLBACK；外部 SSE 响应尚未提交。取得首个内容或无内容的正常结束后，服务端确定最终输出路径，提交 HTTP 200 与 SSE 响应头，依次发送首个 role 块、内容块、结束选择块、可选 Usage 块和 `[DONE]`。

第一个 SSE 业务块写出时设置 Trace.response_committed=true。此后 Provider 失败、超时或协议中断不得切换候选，服务端发送 `data: {"error":<UnifiedError>}`，关闭连接，并把 Trace 标记 STREAM_INTERRUPTED。已经产生的 Provider Usage 可以结算，缺失部分按 TokenEstimator 估算；不发送成功 finish_reason 与 `[DONE]`。

| 流式阶段 | HTTP/SSE 行为 | Trace 与运行行为 |
|---|---|---|
| 响应未提交，校验失败 | 返回普通 JSON 错误与对应 HTTP 状态 | Alias 解析后错误有 Trace；可重试规则按错误分类。 |
| 响应未提交，路径失败 | Runtime 内部重试或切换候选 | 每次外部调用形成 Attempt。 |
| 首次提交 | 发送响应头、role 块及首个内容或结束块 | response_committed=true，固定最终输出路径。 |
| 内容输出 | 按到达顺序发送 UnifiedChatChunk | sequence 连续递增，不合并跨 Provider 内容。 |
| 正常结束 | 发送 finish 块、可选 Usage 块、`data: [DONE]` | Trace=SUCCEEDED，归还容量并生成聚合事件。 |
| 提交后失败 | 发送错误对象并关闭连接 | Trace=STREAM_INTERRUPTED，不执行 Fallback。 |
| 客户端断开 | 停止写出并传播取消 | Trace=CANCELLED，释放容量；不再发送任何块。 |

SSE 响应设置 Content-Type: text/event-stream、Cache-Control: no-cache、Connection: keep-alive 和 X-Accel-Buffering: no。sequence 从 0 开始连续递增。include_usage=false 时不向调用方发送 Usage 块，但 Trace 仍记录用量与成本。连接写入阻塞计入 total_timeout；客户端消费过慢导致写超时时按 CLIENT_CANCELLED 处理。

#### 4.7.1.5 字段、能力与上下文校验

基础字段校验一次性收集能够安全确定的全部字段问题，error.param 返回第一项，error.message 可以包含其余字段路径摘要。messages 至少一条 user，system 最多一条且位于首项；空正文、超长正文、总字符超限、无效枚举和未知字段均在调用 Provider 前拦截。

每个候选独立解析 temperature、top_p、max_tokens、stop、stream、system 和 provider_options 支持情况。请求未给出采样参数时使用候选 ProviderModel 默认值，因此不同候选可以具有不同最终值。temperature、top_p 与 stop 必须同时满足 ProviderModel 支持状态、模型级范围和 ProviderCapabilities 上界；请求显式给出某参数时，不支持该参数或取值超出范围的候选被过滤。max_tokens 未提供时取候选 default_max_tokens，并限制为 max_output_tokens 与 context_window - estimated_input_tokens 的较小值；结果小于 1 时过滤候选。

TokenEstimator 对每个不同 tokenizer_family 至多执行一次估算并缓存于当前 RouteExecutionContext。候选满足 `estimated_input_tokens + resolved_max_tokens <= context_window` 才能保留。所有候选因能力被过滤返回 MODEL_CAPABILITY_NOT_SUPPORTED；均因上下文被过滤返回 CONTEXT_WINDOW_EXCEEDED；两类原因同时存在时，如果至少一个候选能力满足但上下文不足，返回 CONTEXT_WINDOW_EXCEEDED。

#### 4.7.1.6 Trace ID、重试与取消边界

trace_id 只用于关联，不提供结果复用。接入方提供的 trace_id 已存在时返回 TRACE_ID_CONFLICT，不返回已有结果，也不启动新调用。V1.0 不处理 Idempotency-Key；业务方重试完整模型调用时必须接受可能产生新内容和新费用，并使用新的 trace_id 或不提供该字段。

retryable=true 表示相同业务请求在外部条件恢复后可以重新发起，不表示 SDK 或 Server 会在 Trace 结束后自动重放。Server 内部重试只发生在同一 Trace 的 ReliabilityPolicy 预算内。HTTP 客户端关闭、Java CancellationSignal、总超时与实例关闭共用取消路径；只有第一个终止信号生效，其余操作幂等。

#### 4.7.1.7 业务接口契约

| 方法与路径 | 请求 | 成功响应 | 主要错误码 |
|---|---|---|---|
| GET /v1/models | Authorization、来源 IP | UnifiedModelList | ACCESS_TOKEN_INVALID、ACCESS_IP_DENIED、INTERNAL_ERROR |
| POST /v1/chat/completions，stream=false | UnifiedChatRequest | UnifiedChatResponse | FIELD_VALIDATION_FAILED、TRACE_ID_CONFLICT、MODEL_ALIAS_NOT_FOUND、ACCESS_DENIED、MODEL_CAPABILITY_NOT_SUPPORTED、CONTEXT_WINDOW_EXCEEDED、CAPACITY_LIMITED、ALL_CANDIDATES_FAILED |
| POST /v1/chat/completions，stream=true，提交前 | UnifiedChatRequest | SSE 响应或普通错误 | 与非流式相同。 |
| POST /v1/chat/completions，stream=true，提交后 | 已建立 SSE | UnifiedChatChunk、流式错误或连接结束 | STREAM_INTERRUPTED、CLIENT_CANCELLED。 |

### 4.7.2 Provider SPI

#### 4.7.2.1 ProviderAdapter 方法契约

| 方法 | 输入 | 输出 | 规则 |
|---|---|---|---|
| providerType | 无 | string | 进程内唯一，必须与 Provider.type 完全匹配。 |
| capabilities | 无 | ProviderCapabilities | 启动后不可变化，供表单、发布和运行过滤使用。 |
| validateConfig | ProviderConfigView | ProviderConfigValidation | 只执行本地结构校验，不访问网络和 Secret。 |
| listModels | ProviderOperationContext | array<ProviderModelDescriptor> | supports_model_list=true 时实现；只用于管理员显式导入。 |
| checkConnection | ProviderOperationContext 与检测模式 | ProviderCheckRecord 数据 | 只在管理员显式检测时访问 Provider。 |
| estimateTokens | ProviderChatRequest、model_id | long | 返回大于等于 0 的输入 Token 估算；不得访问 Provider 网络。 |
| chat | ProviderCallContext | ProviderChatResponse | 执行一次非流式外部调用，不在 Adapter 内重试。 |
| streamChat | ProviderCallContext | Flow.Publisher<ProviderStreamChunk> | 执行一次流式外部调用，支持背压和取消。 |
| classifyError | ProviderFailure | ErrorClassification | 纯函数，不访问网络与持久化状态。 |

内置 OPENAI、ANTHROPIC、GEMINI 和 DEEPSEEK Adapter 与 CUSTOM_SPI 使用同一接口。ProviderAdapter 作为单例注册并必须线程安全；方法不能把可变调用状态保存在实例字段。capabilities 和 validateConfig 可以在管理请求线程执行，chat、streamChat、listModels 与 checkConnection 必须遵守 ProviderCallContext.deadline_at 和 cancellation。

#### 4.7.2.2 配置、注册与发布数据流

应用启动时 ProviderAdapterRegistry 收集所有 Adapter，读取 providerType 和 capabilities，检查唯一性与字段完整性。缺少已配置 Provider.type 对应 Adapter 时，运行实例心跳的 loaded_adapter_types 不包含该类型，发布校验生成 ADAPTER_UNAVAILABLE。重复 providerType 阻止实例就绪。

Provider 编辑保存时调用 validateConfig，对 base_url、代理、超时和 Adapter 专属静态规则生成字段问题。配置发布再次针对固定草稿执行同一校验，并检查所有 ONLINE 目标实例 loaded_adapter_types。validateConfig 不读取 Credential、不请求外部 Provider；连接可用性由 ProviderCheckRecord 形成独立 WARNING。

ProviderOptionSpec 是 provider_options 的唯一白名单来源。内置 Adapter 的 option_specs 随运行版本固定；CUSTOM_SPI 在 capabilities 中声明。请求 option key 必须使用 provider_type.option_name，路由只保留 provider_type 与全部 option key 匹配的候选，Adapter 收到的 ProviderChatRequest 已移除其他类型选项。

#### 4.7.2.3 单次 Provider 调用数据流

Runtime 先选定 RouteCandidate 和 Credential，创建 CapacityReservation、RouteDecision 与 Attempt，再通过 CredentialSecretResolver 得到 secret handle，并构造 ProviderCallContext。Adapter 根据 ProviderConfigView、outbound_headers、model_id 和 secret handle 生成外部请求；Runtime 不向 Adapter 提供其他 Credential 或候选列表。

Adapter 负责协议转换、认证格式、HTTP 发送、响应解析、Provider Usage、Provider 响应 ID 与请求 ID 提取。返回 ProviderChatResponse 后，Runtime 校验 content、finish_reason 和 Usage，缺少 Usage 时调用 estimateTokens 生成 ESTIMATED；随后结算容量、成本和 Attempt。Adapter 不计算业务费用，不写 Trace，不修改 Credential 健康，不更新熔断状态。

secret handle 只能在构造认证材料的最小作用域内读取。Adapter 不得缓存 char[]、String 密钥或完整认证头；调用结束在 finally 中清除可修改缓冲。outbound_headers 已排除认证字段，Adapter 对默认头和自身认证头进行合并，Credential 认证值具有最高优先级且不允许被 Provider.default_headers 覆盖。

#### 4.7.2.4 流式 Adapter 规则

streamChat Publisher 每个订阅对应一次外部 HTTP 调用且只允许一个 Subscriber。Provider 内容按原顺序转换为 CONTENT，Usage 转换为 USAGE，正常结束转换为唯一 FINISH；Provider 缺少明确结束事件时，只有协议允许通过正常连接关闭判断结束，才能生成 FINISH。取消 Subscription 必须关闭外部响应体并触发 cancellation。

Adapter 不把 Provider 注释、心跳、空数据行和协议控制帧转换成 CONTENT。单个 Unicode 字符被底层网络分片时，解码器等待完整 UTF-8 序列。Provider 返回多个文本 content block 时按到达顺序转换为连续 CONTENT。V1.0 遇到工具、多模态或无法转换的 block 时以 PROVIDER_BAD_RESPONSE 失败，不输出部分结构化对象。

Runtime 在向业务调用方提交首个块前可以取消当前 Adapter 并依据 ErrorClassification 选择恢复动作；提交后 Adapter 错误直接结束当前流。ProviderStreamChunk 不包含原始响应体，诊断日志只能保存事件类型、字节数、序号和脱敏 Provider Request ID。

#### 4.7.2.5 错误分类基线

| Provider 条件 | unified_code | retryable | Credential Failover | Fallback | 计入熔断 |
|---|---|---:|---:|---:|---:|
| DNS、连接重置、可重试网络错误 | NETWORK_ERROR | true | true | true | true |
| 建连超时 | CONNECT_TIMEOUT | true | true | true | true |
| Provider 401 或明确密钥无效 | PROVIDER_AUTH_FAILED | false | true | true | false |
| Provider 403 且属于密钥权限 | PROVIDER_AUTH_FAILED | false | true | true | false |
| Provider 429 | PROVIDER_RATE_LIMITED | true | true | true | false |
| Provider 明确模型不存在 | PROVIDER_MODEL_NOT_FOUND | false | false | true | false |
| Provider 拒绝已经过本系统校验的请求参数 | PROVIDER_REQUEST_REJECTED | false | false | false | false |
| Provider 500、502、503、504 | PROVIDER_SERVER_ERROR | true | true | true | true |
| 响应 JSON、SSE 或 Usage 无法解析 | PROVIDER_BAD_RESPONSE | true | true | true | true |
| 首 Token 超时 | FIRST_TOKEN_TIMEOUT | true | true | true | true |
| 当前 Trace 到达总期限 | TOTAL_TIMEOUT | false | false | false | false |
| Provider 正常内容过滤结束 | 无错误，finish_reason=CONTENT_FILTER | false | false | false | false |

Adapter 可以根据 Provider 明确语义细化分类，但不能把鉴权、权限、请求参数和正常内容过滤标记为普通可重试 5xx。classifyError 返回的 unified_code 必须存在于 4.7.3。Runtime 根据 retryable、credential_failover_allowed、fallback_allowed、counts_toward_circuit 和 ReliabilityPolicy 生成 RecoveryDecision；Adapter 自身不执行恢复动作。

#### 4.7.2.6 SPI 完成标准

每个内置 Adapter 必须通过统一契约测试：能力声明与实际方法一致；字段转换正确；同步与流式 Usage 可提取或估算；取消关闭外部连接；同一 Adapter 支持并发调用；错误分类符合基线；密钥和消息不进入日志；Adapter 内没有 Retry、Credential Failover、Fallback、费用、Trace 与熔断业务逻辑。CUSTOM_SPI 在注册时接受相同检查，缺失必需能力时不进入就绪状态。

### 4.7.3 统一错误码

| 错误码 | HTTP 状态 | retryable | 说明 |
|---|---:|---|---|
| FIELD_VALIDATION_FAILED | 400 | false | 请求或管理字段不合法。 |
| SECRET_CONFIRM_MISMATCH | 400 | false | 两次输入的密钥不一致。 |
| CONFIRMATION_TEXT_MISMATCH | 400 | false | 高风险操作的固定确认文本不匹配。 |
| REQUEST_TOO_LARGE | 413 | false | HTTP 请求体超过运行配置或部署上限。 |
| UNSUPPORTED_CONTENT_TYPE | 415 | false | 请求 Content-Type 不是 application/json。 |
| UNSUPPORTED_CONTENT_ENCODING | 415 | false | V1.0 不接受压缩请求体。 |
| ACCESS_TOKEN_INVALID | 401 | false | Standalone Access Token 无效、过期或停用。 |
| INSTANCE_AUTH_FAILED | 401 | false | 运行实例内部接口身份认证失败。 |
| ACCESS_IP_DENIED | 403 | false | 调用来源 IP 不在访问凭证允许范围。 |
| ACCESS_DENIED | 403 | false | 无页面、对象或 Alias 权限。 |
| OBJECT_NOT_FOUND | 404 | false | 管理对象不存在或已删除。 |
| MODEL_ALIAS_NOT_FOUND | 404 | false | Alias 不存在或未发布。 |
| OBJECT_IN_USE | 409 | false | 管理对象仍被其他配置引用，不能删除。 |
| CAPACITY_IN_USE | 409 | true | Credential 仍有运行中 Attempt，不能删除。 |
| IMPACT_ANALYSIS_EXPIRED | 409 | false | 确认后引用关系已经变化，需要重新确认影响。 |
| DUPLICATE_ROUTE_CANDIDATE | 409 | false | Alias 下已存在相同模型与凭证池组合。 |
| JOB_ALREADY_FINISHED | 409 | false | 批量检测任务已结束，不能取消。 |
| MODEL_ALIAS_DISABLED | 409 | false | Alias 已停用。 |
| LIMIT_POLICY_CONFLICT | 409 | false | 同一作用对象已存在另一条启用的限流策略。 |
| RELIABILITY_POLICY_CONFLICT | 409 | false | 同一 Alias 已存在另一条启用的可靠性策略。 |
| CIRCUIT_STATE_CONFLICT | 409 | true | 熔断状态或 state_version 已变化，需要刷新后重新操作。 |
| TRACE_ID_CONFLICT | 409 | false | 接入方提供的 trace_id 已被使用。 |
| ADMIN_PATH_CONFLICT | 409 | false | Embedded Admin UI 路径与宿主路由冲突。 |
| SECRET_PROVIDER_CONFLICT | 409 | false | 多个 SecretProvider 同时匹配同一 secret_ref。 |
| CONFIG_DRAFT_CHANGED | 409 | false | 全局草稿修订号已变化，当前校验、撤销或发布请求失效。 |
| DRAFT_REVERT_BLOCKED | 409 | false | 其他草稿对象仍引用目标对象，不能单独撤销。 |
| CONFIG_VALIDATION_EXPIRED | 409 | false | 配置校验已过期、已使用或不再对应当前草稿。 |
| CONFIG_PUBLISH_IN_PROGRESS | 409 | true | 已有发布占用全局草稿锁。 |
| CONFIG_FIELD_IMMUTABLE | 409 | false | 当前数据状态下字段已经锁定，不能修改。 |
| RETENTION_IMPACT_EXPIRED | 409 | false | 保留期影响估算已过期或目标参数发生变化。 |
| ACCESS_CREDENTIAL_EXPIRED | 409 | false | 已过期的访问凭证不能重新启用。 |
| SNAPSHOT_CHECKSUM_MISMATCH | 409 | true | 实例下载的快照内容摘要与发布指令不一致。 |
| INSTANCE_REPORT_CONFLICT | 409 | true | 实例上报早于已保存状态或与当前发布阶段冲突。 |
| PROVIDER_ADAPTER_NOT_FOUND | 422 | false | Provider 类型对应的 Adapter 未加载。 |
| OBJECT_REFERENCE_INVALID | 422 | false | 配置引用不存在、已删除或 Provider 关系不一致。 |
| CHECK_TARGET_INVALID | 422 | false | 检测命令中的模型、凭证或目标关系不合法。 |
| MODEL_LIST_NOT_SUPPORTED | 422 | false | Provider Adapter 不支持读取外部模型列表。 |
| MODEL_CAPABILITY_NOT_SUPPORTED | 422 | false | 没有候选满足流式、system 或上下文能力。 |
| CONTEXT_WINDOW_EXCEEDED | 422 | false | 输入与最大输出超过全部候选上下文。 |
| EXPORT_TOO_LARGE | 422 | false | 当前筛选预计导出超过 100000 行，需要缩小时间或业务范围。 |
| MODE_NOT_SUPPORTED | 422 | false | 当前运行模式不支持该管理能力。 |
| INSTANCE_VERSION_INCOMPATIBLE | 422 | false | 运行实例版本不支持目标快照结构或启用的 Adapter。 |
| CAPACITY_LIMITED | 429 | true | RPM、TPM 或并发容量不足。 |
| QUEUE_FULL | 429 | true | 当前 Alias 队列达到生效策略允许的最小上限。 |
| QUEUE_TIMEOUT | 429 | true | 排队等待超过限制。 |
| PROVIDER_AUTH_FAILED | 502 | false | 外部 Provider 鉴权失败。 |
| PROVIDER_MODEL_NOT_FOUND | 502 | false | Provider 明确返回目标模型不存在或不可用。 |
| PROVIDER_REQUEST_REJECTED | 502 | false | Provider 拒绝已经过本系统校验的请求参数。 |
| PROVIDER_RATE_LIMITED | 503 | true | Provider 返回限流且无可用 Fallback。 |
| PROVIDER_BAD_RESPONSE | 502 | true | Provider 响应无法解析或协议不完整。 |
| SERVER_PROTOCOL_ERROR | — | false | Java SDK 收到无法解析的 Standalone 响应，调用结果可能已经产生。 |
| PROVIDER_SERVER_ERROR | 503 | true | Provider 返回可重试服务端错误。 |
| SECRET_RESOLUTION_FAILED | 502 | true | 外部 Secret 无法读取或返回无效密钥。 |
| IMPORT_SOURCE_UNAVAILABLE | 503 | true | 模型导入来源当前不可用。 |
| NETWORK_ERROR | 503 | true | 与 Provider 通信时发生可重试网络错误。 |
| CREDENTIAL_NOT_AVAILABLE | 503 | true | 当前候选的凭证池没有可用 Credential。 |
| CIRCUIT_OPEN | 503 | true | 当前模型与 Credential 路径处于 OPEN，且没有其他可用路径。 |
| CAPACITY_STATE_UNAVAILABLE | 503 | true | 全局容量状态存储不可用，无法安全创建新预占。 |
| OBSERVATION_DATA_UNAVAILABLE | 503 | true | Trace 关联明细或 Usage 聚合当前无法完整读取。 |
| CONFIG_DATA_UNAVAILABLE | 503 | true | 配置草稿、快照或发布数据当前无法完整读取。 |
| AUDIT_DATA_UNAVAILABLE | 503 | true | 审计数据当前无法完整读取。 |
| NO_ONLINE_RUNTIME_INSTANCE | 503 | true | 当前没有可参与配置发布的在线运行实例。 |
| CONNECT_TIMEOUT | 504 | true | 连接 Provider 超时。 |
| FIRST_TOKEN_TIMEOUT | 504 | true | 流式首 Token 超时。 |
| TOTAL_TIMEOUT | 504 | true | 调用总超时。 |
| ALL_CANDIDATES_FAILED | 503 | true | 所有候选尝试均失败。 |
| STREAM_INTERRUPTED | 502 | true | 已输出内容后外部流中断。 |
| CLIENT_CANCELLED | 499 | false | 客户端在排队或调用过程中主动断开。 |
| CLIENT_CLOSED | — | false | Java LightAiClient 已关闭，不能创建新调用。 |
| CONFIG_VERSION_CONFLICT | 409 | false | 编辑对象版本已变化。 |
| INTERNAL_ERROR | 500 | false | 未分类内部错误。 |

同步 HTTP 错误使用 UnifiedErrorEnvelope，error 至少包含 code、type、message 和 retryable；字段错误返回 param；已经创建 Trace 时返回 trace_id；可以重试且能确定等待时间时返回 retry_after_ms。流提交后的错误把相同 UnifiedError 放入 SSE data。message 不包含 Credential、Authorization、消息正文和 Provider 原始敏感信息。表中 HTTP 状态为“—”的错误只在 Java SDK 本地产生。

## 4.8 删除、停用与引用规则

配置对象优先停用。Provider 被 Credential Pool 或 Provider Model 引用时禁止删除；Credential Pool 包含 Credential 或被 Route Candidate 引用时禁止删除；Provider Model 被 Route Candidate 引用时禁止删除；Model Alias 被 Limit Policy、Reliability Policy 或 Standalone Access Credential 引用时禁止删除。Route Candidate 可以在草稿中删除，发布后从运行快照移除。

删除无引用草稿对象需要二次确认并写审计。已进入历史 Trace、Attempt、PublishRecord 或 AuditLog 的 ID 保留为历史引用，关联对象删除后页面以历史快照名称展示。

# 5. 非功能需求

## 5.1 性能与容量

在 Provider 响应耗时之外，路由、凭证选择、容量判断和 Trace 初始化的服务端附加 P95 延迟应不高于 20ms。同步响应与流式转发不缓存完整输出后再返回。Standalone 单实例目标支持至少 200 个并发流式连接，具体发布容量通过压测基线确认。

Trace 写入不得阻塞 Provider 内容转发；关键状态先可靠记录，非关键聚合异步执行。Usage 聚合允许最多两个 dashboard_refresh_seconds 的展示延迟。

Java LightAiClient 必须线程安全并复用 HTTP 连接池。流式路径每个订阅最多缓冲 32 个 StreamEvent，达到上限后使用背压暂停上游读取，不以扩大内存缓冲处理慢消费者。输入正文受 max_request_chars 保护，模型输出受 Provider Model.max_output_tokens 保护；服务端不得在返回前复制多份完整模型文本。

## 5.2 可用性与一致性

Standalone 目标月可用性为 99.9%，外部 Provider 故障时间不计入服务自身可用性，但应正确执行 Fallback。配置快照原子加载，一次 Trace 固定使用一个 snapshot_no。集群容量计数使用共享原子存储；共享存储不可用时停止创建新的容量预占，返回 CAPACITY_STATE_UNAVAILABLE 并产生告警。单实例 SDK 或 Embedded Mode 使用进程内原子状态，不依赖共享存储。

数据库保存配置、Trace、Usage、审计和发布记录。Redis 或兼容共享缓存用于集群 RPM、TPM、并发、队列和熔断状态；单实例 Embedded Mode 可以使用进程内实现。数据库和 Redis 属于部署依赖，其选型与连接参数由工程设计确定。

Standalone 与 Embedded Runtime 只有在配置快照、Adapter Registry 和必需存储均可用时进入就绪。优雅关闭先停止接收新调用，再处理存量调用和容量归还。运行实例失去共享容量状态时停止创建新 Trace，避免在集群中突破全局限制。

## 5.3 安全

外部调用和管理接口默认使用 HTTPS。Credential secret_value 采用受保护密钥加密后存储，主加密密钥不与业务数据保存在同一位置。所有密钥字段在页面、API、日志、指标、Trace 和审计中脱敏。管理接口执行角色权限校验，Standalone Access Token 只保存单向摘要。

消息正文默认不持久化。诊断采样必须由部署方显式启用，并配置采样比例、脱敏规则和保留期。导出、日志和外部 Trace Exporter 不包含消息正文与认证信息。

SDK、Starter 和 Standalone 的配置日志、异常、Actuator、健康响应与接入示例不得输出 access_token、数据库与 Redis 密码、TLS 私钥、Provider Credential 和完整 secret_ref。业务 API 只接受预定义 JSON 字段和 identity 编码，请求体超限时在解析正文前拒绝。

## 5.4 可扩展性与兼容性

新增 Provider 通过 Provider SPI 实现，不修改路由和治理核心。Adapter 能力声明用于管理端字段校验和运行时候选过滤。Java SDK 与 Server 在同一主版本内保持请求、响应和错误码兼容；新增可选字段不改变已有字段含义。

V1.x Server 可以在响应中增加可选字段，V1.x Java Client 必须忽略未知响应字段；Server 对未知请求字段保持严格拒绝，避免调用方误以为参数已经生效。SDK 与 Server 主版本不一致时，客户端在首次成功响应读取版本头后产生兼容性告警；协议解析仍以实际字段为准。ProviderAdapter.adapter_version 与产品版本独立，但 provider_type 和既有 ProviderOptionSpec.key 的含义在 V1.0 内保持稳定。

## 5.5 可观测性

系统输出请求量、成功率、错误率、耗时分位数、首 Token 耗时、Token、实际与估算用量比例、费用、重试、Credential Failover、Fallback、限流、队列、并发、熔断、聚合待处理数量和聚合延迟指标。高基数 trace_id、attempt_id 和 credential_id 不作为 Metrics 标签。日志使用 trace_id 关联，Provider Request ID 只写入受控诊断字段。

## 5.6 数据保留与清理

Trace 默认保留 30 天，Usage 默认保留 365 天，审计至少保留 365 天。清理按时间分批执行，避免长事务；清理 Trace 时保留 UsageAggregate。配置快照和发布记录至少保留最近 100 个成功版本及全部近 365 天发布记录。

# 6. 验收要求

## 6.1 模型接入验收

系统管理员可以完成 Provider、Credential Pool、Credential、Provider Model、Model Alias 和 Route Candidate 的完整配置。Provider Model 页面包含上下文、输出、流式、system、默认采样和价格字段。配置引用错误在发布前被准确拦截，发布后业务应用可以使用 Alias 完成同步和流式调用。

## 6.2 路由与容量验收

同一 Alias 配置多个优先级和权重后，运行结果符合优先级内权重选择规则。多 Credential 按池策略选择。RPM、TPM 和并发分别达到上限时能够拒绝、排队或选择其他候选；所有结束路径正确归还并发，Token 预占与结算可核对。

## 6.3 可靠性验收

可重试网络错误、连接超时和可重试 5xx 按次数与退避执行；Provider 429 优先切换 Credential 或候选，没有替代路径且等待时间在策略预算内时允许延迟重试。参数、鉴权和内容拒绝不执行 RETRY。当前候选失败后可以 Fallback。达到失败窗口阈值后熔断 OPEN，等待期后 HALF_OPEN 探测并按结果恢复或重新打开。流式首段输出后发生中断时不切换模型。

## 6.4 观测与安全验收

每次模型调用生成 Trace，每次实际外部调用生成 Attempt。Trace 详情可以说明候选、Credential 脱敏值、重试、Fallback、Token、费用、耗时和错误。Credential 原文、访问 Token、认证请求头和默认消息正文不出现在读取接口、页面、日志、Trace、审计与导出中。

## 6.5 交付形态验收

Java SDK 同时支持 Local Runtime 和 Standalone Client，提供线程安全的同步、异步、Java Flow 流式、背压、取消、统一异常和生命周期管理。Spring Boot Starter 按 EMBEDDED 与 STANDALONE_CLIENT 条件装配，配置属性具有完整元数据，宿主 Bean 可以覆盖默认实现，Embedded Admin UI 受宿主认证或显式本地访问规则保护。Standalone Server 提供模型目录、同步与 SSE 调用、管理与内部实例入口、存活与就绪检查、集群共享状态和优雅关闭。Provider SPI 的配置校验、模型列表、Token 估算、同步、流式、取消和错误分类通过统一契约测试。

## 6.6 关键验收场景

| 场景 | 前置条件 | 操作 | 预期结果 |
|---|---|---|---|
| 概览指标一致性 | 查询范围内同时存在成功、失败、流式中断、取消和运行中 Trace，并包含 Retry 与 Fallback | 使用同一 application、Alias、Provider 和时间范围查询运行摘要、Trace 列表及 Usage 汇总 | request_count 与 Trace total 一致；success_rate 排除 CANCELLED、RUNNING、QUEUED；Token 与分币种 Cost 和 Usage 一致；恢复动作按 RecoveryDecision 计数。 |
| 概览趋势与钻取 | 最近 24 小时存在多个时间桶和两种费用币种 | 切换请求量、成功率、Token、Cost 指标并点击一个时间桶 | 时间桶连续且无重复；Cost 分币种展示；钻取页面带入 bucket_start、bucket_end 和公共筛选，结果范围与数据点一致。 |
| 概览异常权限与定位 | 存在 OPEN 熔断、不可用候选、INVALID Credential 和失败 Trace，并准备开发人员与运维人员身份 | 两种身份分别查询异常区域并点击异常项 | 运维人员可见 Credential 脱敏信息并进入对应详情；开发人员不接收 Credential 项，只看到本应用 Trace 与授权 Alias；目标详情与异常对象一致。 |
| 首次接入模型 | Provider、模型和 Credential 均未配置 | 按模型接入主流程配置并发布 | 发布成功，Alias 可查询并完成调用。 |
| 模型上下文过滤 | 两个候选 context_window 不同 | 发送只满足较大上下文候选的请求 | 小上下文候选被过滤，请求由大上下文候选完成。 |
| 模型参数能力过滤 | 同一 Alias 的两个候选具有不同 temperature 范围，其中一个不支持 stop | 发送只落在一个候选 temperature 范围内且包含 stop 的请求 | 不兼容候选在调用 Provider 前被过滤；兼容候选收到解析后的参数；全部候选不兼容时返回 MODEL_CAPABILITY_NOT_SUPPORTED。 |
| 多密钥调度 | 一个池内有三个 HEALTHY Credential | 连续发起并发请求 | 按 selection_strategy 分配，且不超过各自并发上限。 |
| Provider 限流 | 首选 Credential 返回 429，池内存在其他 Credential 或备用候选 | 发起调用 | 优先执行 Credential Failover，再执行 Fallback；Provider 429 不增加熔断失败数，Trace 保存全部 Attempt 与 RecoveryDecision。 |
| 熔断恢复 | 候选错误率达到阈值 | 等待 OPEN 期并发起探测 | 状态按 CLOSED、OPEN、HALF_OPEN 规则迁移。 |
| 流式中断 | 客户端已收到内容块 | Provider 连接中断 | 返回流式 UnifiedError，状态 STREAM_INTERRUPTED，不拼接备用模型输出。 |
| 配置冲突 | 两名管理员编辑同一对象 | 后保存旧 version | 返回 CONFIG_VERSION_CONFLICT，不覆盖最新值。 |
| 发布失败 | 启用 Alias 没有可用候选 | 执行发布 | 返回对象与字段错误，当前运行快照保持不变。 |
| 密钥安全 | 创建并轮换 Credential | 查询详情、日志、Trace、审计和导出 | 所有位置均无法获取密钥原文。 |
| 用量复算 | 一次请求包含重试和 Fallback | 对比 Trace、Attempt 和 Usage | Trace 成本等于各 Attempt 成本之和，聚合数据可复算。 |
| Trace 精确定位 | 已存在本角色范围内 Trace，并准备一个无权限 Trace ID | 分别按两个 trace_id 查询 | 有权限 Trace 一次返回，越权 ID 返回空列表，不能推断其他应用数据。 |
| Trace 时间线 | 请求经历排队、失败、Credential Failover 和成功 | 打开 Trace 详情 | 时间线按队列、路由、Attempt、恢复决策和结束顺序展示，节点数量与源实体一致。 |
| 响应用量与总消耗 | 首次 Attempt 失败并产生估算 Token，Fallback 成功并返回实际 Usage | 查看 Usage/Cost 详情 | response_total_tokens 等于成功 Attempt，Trace.total_tokens 包含两次 Attempt，usage_source=MIXED。 |
| 路径费用归因 | Provider A 失败后 Provider B 成功 | Usage 按 Provider 分组 | Provider A request_count=0 且保留失败 Attempt 的 Token 与费用，Provider B 获得 request_count=1。 |
| 聚合幂等 | 同一 UsageAggregationEvent 被处理器重复取得 | 重放事件并查询 Usage | HOUR、DAY 聚合各只增加一次，事件最终为 SUCCEEDED。 |
| 多币种汇总 | 查询范围包含不同 currency 的多个 Alias | 不指定 currency 查看摘要和趋势 | 费用按币种分别返回和绘制，不产生跨币种总额。 |
| 诊断样本权限 | 已开启采样并产生 AVAILABLE 样本 | 运维人员与只读人员分别查看详情 | 运维人员查看脱敏截断内容并产生审计，只读人员无法取得 sampled_messages 和 client_ip。 |
| 导出安全与上限 | Trace 字段包含以等号开头的文本，并准备超过 100000 行结果 | 分别导出小范围和大范围 | 小范围 CSV 对公式字符转义且不含敏感字段；大范围返回 EXPORT_TOO_LARGE。 |
| 明细与聚合保留 | Trace 超过 trace_retention_days，Usage 尚在 usage_retention_days 内 | 查询 Trace 和 Usage | Trace 明细不可查询，Usage 汇总仍存在，页面停用对应 Trace 钻取。 |
| 草稿修订失效 | 管理员甲已完成校验，管理员乙随后保存另一项配置 | 管理员甲使用原 validation_id 发布 | 返回 CONFIG_DRAFT_CHANGED 或 CONFIG_VALIDATION_EXPIRED，不创建快照和发布记录。 |
| 单项撤销依赖 | 新建 Route Candidate 引用了同一草稿中新建的 Provider Model | 直接撤销该 Provider Model | DraftChange.revertable=false，返回 DRAFT_REVERT_BLOCKED，两个草稿对象均保持原值。 |
| 实例准备失败 | 两个 ONLINE 实例参与发布，其中一个实例无法解析目标快照 | 执行校验和发布 | 目标快照进入 ABORTED，PublishRecord=FAILED，活动快照、草稿内容和运行中请求保持不变。 |
| 配置原子激活 | 两个 ONLINE 实例均已对目标快照上报 READY | 发布服务提交激活并下发 InstanceActivationCommand | 数据库只存在一个 ACTIVE 快照；两个实例切换到相同 snapshot_no；旧 Trace 保持旧快照，新 Trace 使用新快照。 |
| 激活后实例收敛 | 目标快照已 ACTIVE，一个实例未在时限内上报 LOADED | 查看发布进度并等待该实例恢复心跳 | PublishRecord 先为 PARTIAL_FAILED；实例加载当前 ACTIVE 快照后结果转为 LOADED，记录最终转为 SUCCEEDED。 |
| 保留影响过期 | 管理员已取得保留期缩短的 impact_version，估算超过 10 分钟或目标参数改变 | 保存 RuntimeConfig 草稿 | 返回 RETENTION_IMPACT_EXPIRED，不写草稿和审计成功记录；重新估算后可以提交。 |
| 时区锁定 | 已存在 UsageAggregate，RuntimeConfig.timezone_locked=true | 尝试修改 timezone | 返回 CONFIG_FIELD_IMMUTABLE，不产生新的时间桶口径。 |
| 访问 Token 一次显示与轮换 | Standalone Mode 已创建访问凭证并安全保存初始 Token | 关闭创建弹窗后查询详情，再执行轮换并分别使用新旧 Token 调用 | 详情无法取得原文；轮换响应只显示一次新 Token；旧 Token 立即返回 ACCESS_TOKEN_INVALID，新 Token 可鉴权。 |
| 访问范围控制 | 访问凭证限定一个 Alias 和一个 IP 网段 | 分别使用允许与未允许的 Alias、来源 IP 调用 | 允许组合进入统一运行链路；越界 Alias 返回 ACCESS_DENIED，越界 IP 返回 ACCESS_IP_DENIED。 |
| 审计事务一致性 | 准备一项可成功的配置修改和一项会触发版本冲突的修改 | 依次提交两项操作 | 成功修改与 SUCCEEDED AuditLog 同事务提交；冲突修改回滚，并以相同 request_id 生成脱敏 FAILED AuditLog。 |
| 配置快照敏感值检查 | 草稿包含数据库密钥凭证、外部 Secret 引用和 Standalone Access Credential | 校验发布并检查快照、校验问题、实例错误和审计 | ConfigSnapshot 不含 secret_value、Token、运行状态与审计；secret_ref、错误和差异按敏感规则脱敏。 |
| 接入示例安全 | Standalone 已配置 Alias 和访问凭证 | 打开接入说明并复制 Maven、Spring、Java 与 cURL 示例 | Alias 与公开 base_url 正确；Token 和所有模型密钥均为占位符；页面、剪贴行为指标和前端日志无真实 Secret。 |
| 模型目录访问范围 | 两个 Access Credential 分别允许不同 Alias | 分别调用 GET /v1/models | 每个响应只含其范围内已发布 Alias；临时容量耗尽不移除模型；接口不创建业务 Trace。 |
| Trace ID 冲突 | 已存在调用方指定 trace_id 的 Trace | 使用相同 trace_id 再次调用 | 返回 TRACE_ID_CONFLICT，不复用旧响应、不创建 Attempt、不产生新费用。 |
| 同步响应一致性 | Alias 经一次失败 Attempt 后 Fallback 成功 | 调用 stream=false 并对比响应与 Trace | choices 来自最终成功 Attempt；response Usage 对应最终 Attempt；light_ai.cost 等于全部可计费 Attempt 成本；X-Trace-Id 与响应 trace_id 一致。 |
| 流提交前恢复 | 首选 Provider 在首个内容块前连接失败，备用候选可用 | 发起 stream=true 调用 | 外部流只出现备用候选的一组连续块；Trace 保留失败 Attempt 与 Fallback；sequence 从 0 连续递增。 |
| 流提交后中断 | 客户端已经收到首个内容块 | Provider SSE 解析失败 | 不切换候选；发送 UnifiedError 后关闭连接；Trace=STREAM_INTERRUPTED；Java Publisher 调用 onError 且不调用 DONE、onComplete。 |
| Java 异步取消 | chatAsync 已创建 Trace 且 Provider 尚未结束 | 调用 CompletableFuture.cancel | 底层 CancellationSignal 触发，future 以 CancellationException 结束，Trace=CANCELLED，并发容量释放一次。 |
| Java 流式背压 | Subscriber 初始只 request(1)，Provider 连续产生多个块 | 分批增加 request 数并最终 cancel | 每次只收到已请求数量，事件顺序不变且不丢失；取消后停止事件并释放连接。 |
| Local Runtime 配置校验 | 本地运行定义存在无引用模型或 Alias 无候选 | 构建 LOCAL_RUNTIME 客户端 | 返回包含对象、字段和原因的配置校验结果，客户端未创建，不调用 Provider。 |
| Local Runtime 密钥边界 | 本地定义通过 credential_secret_suppliers 提供密钥 | 完成一次调用后检查定义、快照、日志、异常与 TraceExporter | 所有位置无密钥原文；secret handle 使用结束后清理。 |
| Starter 模式隔离 | 分别启动 EMBEDDED 与 STANDALONE_CLIENT 应用上下文 | 检查 Bean、存储和页面路由 | Embedded 创建 Runtime、仓库与可选 Admin；Standalone Client 只创建 HTTP Client 与健康组件，不连接业务数据库和 Redis。 |
| Starter Bean 覆盖 | 宿主声明自定义 LightAiClient 与 ProviderAdapter | 启动 EMBEDDED 应用 | 默认 LightAiClient 回退；自定义 Adapter 加入 Registry；重复 provider_type 阻止就绪并指出冲突 Bean。 |
| Embedded Admin 安全 | 未提供 AuthContextProvider，local-access-enabled=false | 从本机和外部地址访问 Admin 路径 | 两者均拒绝；显式开启后只有 loopback 或可信网段建立 LOCAL_ADMIN 上下文。 |
| Standalone 就绪与摘流 | 数据库、Redis、快照和 Adapter 初始正常 | 分别中断 Redis、恢复后发送正常终止信号 | Redis 必需时 readiness=DOWN；恢复后 UP；终止时先 DRAINING 和拒绝新请求，再等待或取消存量调用。 |
| Standalone 集群容量 | 两个实例共享数据库和 Redis | 并发请求达到 Alias 全局上限 | 两实例合计不超过上限；任一实例停止后 Watchdog 回收遗留预占。 |
| Provider SPI 错误分类 | 自定义 Adapter 模拟 401、429、5xx、无效 SSE 和正常内容过滤 | 执行契约测试 | 分类分别符合 4.7.2.5；Adapter 不自行重试；Runtime 按 RecoveryDecision 执行；内容过滤作为正常 finish_reason。 |
| SecretProvider 选择与失效 | 两个 SecretProvider 同时匹配同一引用，并准备一项可轮换引用 | 分别解析冲突引用和轮换正常引用 | 冲突返回 SECRET_PROVIDER_CONFLICT 且不读取密钥；正常轮换调用 invalidate，后续请求不再使用旧缓存。 |
| TraceExporter 失败隔离 | TraceExporter 连续返回可重试失败 | 完成模型调用并等待三次导出重试 | 业务响应与内部 Trace 成功完成；批次 batch_id 保持不变；最终输出 exporter_failure 指标，日志无正文与密钥。 |



## 开发接入DTO的精确契约

GET /admin/developer-access/code-sample 采用4.6.5的单数路径；POST流测试为 /admin/developer-access/test/chat/stream。CodeSampleRequest：alias_id必填UUID，mode=LOCAL_RUNTIME/EMBEDDED/STANDALONE_CLIENT，sample_type=DEPENDENCY/CONFIG/SYNC/ASYNC/STREAM/HTTP，build_tool=MAVEN/GRADLE仅DEPENDENCY使用。CodeSampleResult：language、content、filename可空、alias_id、mode、sample_type，所有密钥为占位符；查询参数不接收Token。枚举代码为C-015补充，展示名称来自4.6.1。

ApiTestCommand：model为授权已发布Alias；system_message可空string，user_message为非空string；stream默认true但chat接口要求false、chat/stream强制true；temperature、top_p、max_tokens可空。按RuntimeConfig校验字符数。后端转换为messages并由管理身份注入application与系统标签，前端不提交metadata或Token。只读身份拒绝测试。

管理测试StreamEvent SSE每个data为一个对象：event=START/DELTA/USAGE/DONE、trace_id、sequence（从0连续）、model、provider、provider_model；DELTA有delta文本，USAGE有usage和cost，DONE有finish_reason和total_ms。START在最终输出路径确定后才发送，成功最后一个事件为DONE然后关闭；不发送HTTP业务协议的[DONE]字面串。错误仍为{error:UnifiedError}，无DONE；取消不再发事件。FE-052按此解析，BE-047负责从Runtime事件映射；/v1/chat/completions继续使用UnifiedChatChunk。

## 检测与适配器元数据补充

Bootstrap追加adapters数组：provider_type、adapter_version、default_base_url、tokenizer_families、capabilities、provider_option_specs；只输出已加载Adapter的非敏感不可变声明。ProviderOptionSpec固定key/type/required/default/min/max/enum_values/description，type=STRING/INTEGER/DECIMAL/BOOLEAN，未声明key拒绝。前端不硬编码模型能力范围。此字段纳入C-011确认。

独立Provider/Model/Credential检测不要求先有Alias。MINIMAL_CHAT建立invocation_source=PROVIDER_CHECK、application=ADMIN_CONSOLE且alias_id可空的Trace，并以明确选择的Model/Pool/Credential建立route_candidate_id可空的Attempt；真实用量和价格正常结算。CONNECTION_ONLY不发模型推理时仅生成CheckRecord。具体条件约束见DATABASE_PLAN最后一节；不得绕过凭证/容量/取消保护。C-021记录该关系补充。


人工熔断未收敛响应data为CircuitStateDetail，增加pending_command={id,status,error_code可空}，HTTP202；已完成返回HTTP200且pending_command=null。客户端通过既有GET /admin/circuits/{id}读取最新命令和状态，避免新增任务查询系统。状态FAILED必须给安全错误码且不报人工操作成功。
