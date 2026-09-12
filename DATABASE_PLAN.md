# 轻享 AI V2.0 数据库执行计划

## 1. 执行边界

开发基线统一为 origin/dev，占用按 TASK_STATUS.md 登记。DB-P20 先复核已有 V2/V3/V4 迁移与 BE-P20 字段差异，只为确有缺口新增迁移；COMMUNICATION.md 的未决业务选项不得被解释为可自行改变数据口径。

任务包从 P20 编号，用于避免与既有迁移及提交记录中的编号冲突。V2 延续 PostgreSQL、MySQL 8.0/MySQL 5.7 双方言与版本化迁移，已发布迁移不得修改。金额使用定点 decimal，时间存 UTC，密钥原文禁止落库。所有主表具有 created_at、updated_at 和 version；历史/流水表只追加并使用 created_at/occurred_at。有历史引用的主对象采用禁用或归档，禁止直接物理删除。

## 2. 核心表契约

| 表 | 用途与关键字段 | 必填/默认 | 索引与约束 | 页面/API |
|---|---|---|---|---|
| application | id、code、name、department、owner_id、environment、status、version | code/name/environment/status 必填；ACTIVE 默认 | code 全局唯一；status 索引 | 应用列表/详情，applications API |
| application_member | application_id、subject_id、role、created_at | 全部必填 | (application_id,subject_id) 唯一；subject_id 索引 | 成员与数据范围 |
| application_key | application_id、name、key_prefix、key_digest、expires_at、ip_allowlist、rpm、tpm、status、grace_expires_at、last_used_at | digest/prefix/status 必填；ACTIVE 默认 | digest 唯一；名称为标签不设唯一；状态/到期索引 | 应用密钥、业务鉴权 |
| application_quota_policy | application_id、current_period_id、policy_version、token_limit、amount_limit、currency、rpm、tpm、period_type/start/end、used/reserved、version | application_id/period/version 必填；used/reserved=0 | application_id 唯一；历史拆 period/history 见文末契约 | 额度页、准入 |
| quota_adjustment | idempotency_key、application_id、dimension、before_value、delta、after_value、reason、effective_at、operator | 维度/原因/时间/操作者必填；POLICY 数值列可空，change_json 必填 | (application_id,idempotency_key) 唯一；应用+时间索引 | 调整/续期/重置 |
| application_model_permission | application_id、virtual_model_id、enabled、constraints_json、version | enabled=true | (application_id,virtual_model_id) 唯一 | 可用模型、/v1/models |
| channel | id、provider_type、name、base_url、proxy、timeouts、headers、priority、weight、status、health、version | name/type/url/status 必填 | name 唯一；type/status/health 索引 | 渠道列表/详情 |
| channel_credential | channel_id、name、secret_ciphertext/secret_ref、key_id、priority、weight、rpm、tpm、status、health、cooldown_until | 二选一 secret 存储；状态必填 | (channel_id,name) 唯一；channel+状态+优先级索引 | 渠道 Key、运行选择 |
| upstream_model | channel_id、model_id、capabilities、context_window、max_output、prices、currency、source、locked_fields、status、version | channel/model/status 必填 | (channel_id,model_id) 唯一 | 上游模型 |
| virtual_model | code、display_name、capabilities、status、version | code/status 必填 | code 全局唯一 | 虚拟模型、业务 model |
| route_candidate | virtual_model_id、channel_id、upstream_model_id、priority、weight、status、conditions、version | 关联与优先级必填 | 三元组唯一；virtual+status+priority 索引 | 路由编辑/运行 |
| budget_reservation | request_id、application_id、application_key_id、reserved_tokens、reserved_amount、currency、expires_at、status、version | request/status 必填 | request_id 唯一；status+expires 索引 | 网关准入与恢复 |
| request_trace | request_id、application/key/virtual_model、snapshot_no、policy_version、status、timings、usage、cost、currency、usage_source | request/status 必填 | request_id 唯一；应用/时间、状态/时间索引 | 调用记录 |
| request_attempt | trace_id、sequence_no、channel/upstream_model/credential、status、error_code、timings、usage | trace/sequence/status 必填 | (trace_id,sequence_no) 唯一 | Attempt 时间线 |
| usage_ledger | event_key、request/application/key/model/channel、token_delta、amount_delta、currency、price_snapshot、event_type、occurred_at | event_key/type/time 必填 | event_key 唯一；应用/时间、模型/时间、渠道/时间索引 | 用量、成本、额度流水 |
| config_snapshot | snapshot_no、content_hash、content、status、created_by、activated_at | no/hash/content/status 必填 | snapshot_no/hash 唯一；单一 ACTIVE 由事务维护 | 配置发布 |
| audit_log | operator、action、target_type/id、before_digest、after_digest、result、request_id、created_at | action/target/result/time 必填 | operator/时间、target/时间、request_id 索引 | 审计 |

JSON 字段必须给出稳定 schema、最大长度和脱敏要求。MySQL 5.7 无法表达的部分唯一条件由事务查询、锁和可验证逻辑约束补足，不以普通“先查后写”代替并发控制。

## DB-P20 应用域迁移与复核（5 项）

- [ ] 任务编号：DB-201
  模块：应用与成员
  目标：复核/新增 application、application_member 的双数据库迁移。
  输入输出：应用基础字段、稳定企业 subject、角色和版本。依赖：BE-201/205。
  异常：code 重复、无效角色、孤儿成员、并发更新。
  验收：唯一/外键/状态/版本一致；按 subject 查询应用范围有索引。
  测试：PostgreSQL、MySQL 5.7/8.0 迁移与约束测试。

- [ ] 任务编号：DB-202
  模块：应用密钥
  目标：复核 application_key 摘要、前缀、轮换宽限、IP、模型和速率收紧字段。
  输入输出：只保存 digest/prefix，不保存完整 secret。依赖：BE-202/203。
  异常：摘要碰撞、非法终态恢复、过期扫描；轮换继承名称不产生名称冲突。
  验收：digest 唯一；REVOKED 不可恢复；高频鉴权索引可用。
  测试：唯一冲突、到期、轮换查询和敏感字段扫描。

- [ ] 任务编号：DB-203
  模块：额度策略
  目标：建立当前/历史 application_quota_policy，支持生命周期、日、月和自定义周期。
  输入输出：limit/used/reserved、currency、rpm/tpm、周期与版本。依赖：BE-204/222。
  异常：金额无币种、周期重叠、负数、版本冲突。
  验收：当前策略唯一；关闭周期不可改写；decimal 精度一致。
  测试：周期边界、时区、并发版本和多币种。

- [ ] 任务编号：DB-204
  模块：额度调整
  目标：quota_adjustment 只追加记录增减、续期和重置。
  输入输出：幂等键、前值、变化、新值、原因、操作者、生效时间。依赖：BE-204。
  异常：重复幂等键、币种/维度不匹配、预约冲突。
  验收：重复请求不产生第二条业务事件；历史不可更新/删除。
  测试：幂等并发、预约生效和审计关联。

- [ ] 任务编号：DB-205
  模块：应用模型权限
  目标：建立应用和密钥模型范围及 constraints JSON 约束。
  输入输出：virtual_model_id、enabled、最大输出、allow_stream、version。依赖：BE-203/214。
  异常：孤儿模型、重复授权、能力字段漂移。
  验收：组合唯一；查询 /v1/models 与运行授权使用同一索引路径。
  测试：授权/取消、级联限制和迁移回滚。

## DB-P21 渠道与模型资源域（5 项）

- [ ] 任务编号：DB-211
  模块：渠道
  目标：完成 V1 provider 到 channel 的可追踪迁移并保留引用。
  输入输出：Provider 类型、URL、超时、优先级、权重、配置状态和健康状态。依赖：BE-211。
  异常：重复名称、危险 URL 历史值、引用丢失。
  验收：配置/健康分列；迁移前后数量和引用对账。
  测试：双数据库升级、回滚说明和旧数据校验报告。

- [ ] 任务编号：DB-212
  模块：渠道 Key
  目标：将凭证池/凭证收口为 channel_credential 多 Key 模型。
  输入输出：密文或 secret_ref 二选一、key_id、优先级/权重、限额、健康。依赖：BE-212。
  异常：两种 secret 同时为空/同时存在、同渠道重名、孤儿凭证。
  验收：密文列不可通过管理查询返回；选择索引支持高频路由。
  测试：迁移对账、唯一约束、密文扫描和轮换。

- [ ] 任务编号：DB-213
  模块：上游模型
  目标：完成 upstream_model、来源和人工锁定字段。
  输入输出：能力、上下文、输出上限、价格、币种、状态。依赖：BE-213。
  异常：同渠道 model_id 重复、价格精度溢出、能力 JSON 非法。
  验收：启用记录必备字段可由约束/服务共同保证；同步查询有索引。
  测试：唯一、价格精度、JSON schema 和同步 diff。

- [ ] 任务编号：DB-214
  模块：虚拟模型与路由
  目标：将 alias/candidate 迁移为 virtual_model/route_candidate，保持稳定业务 code。
  输入输出：虚拟能力、候选三元组、优先级、权重、条件和版本。依赖：BE-214/215。
  异常：重复候选、渠道模型不匹配、孤儿引用。
  验收：三元组唯一；活动路由查询使用 virtual/status/priority 索引。
  测试：迁移对账、排序、禁用和引用阻止。

- [ ] 任务编号：DB-215
  模块：资源历史与影响
  目标：保存渠道/模型名称快照和草稿引用，保证对象改名后历史可解释。
  输入输出：snapshot name、dependency、impact digest。依赖：BE-215/233。
  异常：活动引用删除、快照缺资源、草稿悬挂。
  验收：有活动/历史引用对象不可物理删除；影响查询可复核。
  测试：删除阻止、改名历史和发布校验。

## DB-P22 准入、账本与观测（5 项）

- [ ] 任务编号：DB-221
  模块：预算预占
  目标：实现 budget_reservation 唯一状态机与过期回收索引。
  输入输出：request、应用/密钥、Token/金额、币种、到期和状态。依赖：BE-222/225。
  异常：重复 request、重复结算、回收与完成竞争。
  验收：终态不可二次转换；每个结束路径幂等。
  测试：并发结算/释放/过期和进程崩溃恢复。

- [ ] 任务编号：DB-222
  模块：Trace 与 Attempt
  目标：补齐应用、策略、快照、价格、流提交和用量来源字段。
  输入输出：request_id、应用/密钥/模型、timings、usage/cost、status。依赖：BE-221/224/231。
  异常：Attempt 序号重复、Trace 终态竞争、迟到 Usage。
  验收：一个 Attempt 只指向一个渠道/模型/Key；request_id 全局唯一。
  测试：时间线排序、取消、流错误和范围查询。

- [ ] 任务编号：DB-223
  模块：Usage Ledger
  目标：建立不可覆盖的请求预占、结算、释放、周期重置和人工调整事件。
  输入输出：event_key、归属维度、Token/金额、币种、价格快照、事件类型。依赖：BE-225/232。
  异常：事件重放、币种混用、估算转实际。
  验收：event_key 唯一；聚合可从账本重算；历史价格不被新价格改写。
  测试：重放、并发、多币种、ACTUAL/ESTIMATED。

- [ ] 任务编号：DB-224
  模块：聚合与索引
  目标：按应用、密钥、虚拟/上游模型、渠道和时间支持调用与用量查询。
  输入输出：时间桶、请求、Token、金额、成功/错误、延迟分位。依赖：FE-221/222/225。
  异常：迟到事件、重复聚合、空时间桶。
  验收：聚合幂等；查询计划命中设计索引；多币种独立分组。
  测试：账本重算对账、大时间范围和分页稳定性。

- [ ] 任务编号：DB-225
  模块：留存与清理
  目标：分别处理 Trace、正文采样、账本、审计和聚合留存。
  输入输出：retention policy、删除批次、影响报告。依赖：BP-009、BE-234。
  异常：活动 Trace、账本被误删、清理失败重试。
  验收：账本/审计按确认策略保留；批次幂等且不长时间锁表。
  测试：边界时间、失败恢复、归档和查询一致性。

## DB-P23 发布、安全与迁移门禁（5 项）

- [ ] 任务编号：DB-231
  模块：配置快照
  目标：补齐包含 Channel/Upstream/Virtual/Route 的不可变快照、发布和实例结果。
  依赖：BE-233。异常：hash 重复、部分激活、实例迟到确认。
  验收：单一活动版本；失败不覆盖旧版本；回滚生成新发布记录。
  测试：发布竞争、实例失败、回滚和内容 hash。

- [ ] 任务编号：DB-232
  模块：审计
  目标：覆盖密钥、额度、状态、模型授权、渠道、路由、发布、权限和设置变更。
  输入输出：操作者、动作、目标、摘要、结果、request_id、时间。依赖：BE-P20—P23。
  异常：审计写失败、摘要含敏感字段、重复操作。
  验收：关键操作覆盖率 100%；只追加；常用审计筛选有索引。
  测试：脱敏、失败操作、越权和导出。

- [ ] 任务编号：DB-233
  模块：迁移兼容
  目标：明确 V1→V2 数据映射、不可迁移记录、归档和回滚边界。
  依赖：BP-011。异常：脏 URL、缺价格、无密钥、重复 code。
  验收：迁移前后逐表数量、孤儿、唯一冲突和抽样业务对账；不可迁移项有报告。
  测试：全新安装、逐版本升级、失败重跑和备份恢复。

- [ ] 任务编号：DB-234
  模块：真实存储一致性
  目标：验证 PostgreSQL/MySQL 与 Redis 间预占、结算、释放和故障恢复。
  依赖：BE-222/225。异常：数据库提交后 Redis 失败、反向失败、网络分区。
  验收：可恢复状态机最终收敛；共享状态异常时不放行新请求。
  测试：故障注入、重复恢复、并发预算边界。

- [ ] 任务编号：DB-235
  模块：数据库交付门禁
  目标：执行 schema diff、迁移、约束、索引计划、并发和恢复测试。
  实现说明：PostgreSQL、MySQL 5.7/8.0 分别报告；未运行环境保持未勾选。
  验收：无修改已发布迁移；无明文密钥；无未解释孤儿和全表扫描热点。
  测试：记录数据库版本、命令、通过数、失败数和耗时。

## BE-P20 数据库交付契约（2026-09-12）

本节接收 BACKEND_PLAN.md 的 BE-P20-001～004 决策，属于待实现的数据设计。DB-P20 仍未领取，本轮不占用数据库代码。现有 V2/V3/V4 已存在 application、application_key、application_quota_policy、budget_reservation、usage_ledger；在此基础上增加后续版本迁移，不重复建立上述表、不修改已发布迁移。具体迁移号在领取时核对远端最大版本后分配。

类型约定：ID 为 PostgreSQL UUID/MySQL VARCHAR(36)，时间为 TIMESTAMPTZ/DATETIME(6) 且按 UTC 存储，金额 DECIMAL(30,8)，JSON 为 JSONB/JSON。下面未声明默认值的必填字段必须显式写入，逻辑关联不使用级联删除。

| 任务/表 | 字段与类型、必填及默认 | 关联、索引与处理规则 |
|---|---|---|
| DB-201 application | 复用 code VARCHAR(64) 唯一、owner_id VARCHAR(128)、department VARCHAR(128) 可空、status/version | API owner_id 映射现有列；不新增同义 owner_subject_id。按状态/调用时间/ID 分页；应用成员范围在 SQL 限定。补索引前检查已有索引和查询计划。 |
| DB-201 budget_reservation | 复用 application_id、status、expires_at、terminal_at | 补 (application_id,status) 索引供归档占用检查。所有受管理请求包括无限额/零成本请求均需登记，以应用行锁串行化准入与归档；过期行必须先完成取消/幂等回收，不能仅按 expires_at 忽略仍运行的请求。 |
| DB-202 application_key | 新增 replaced_by_key_id ID 可空、grace_expires_at 时间可空；key_prefix、key_digest、masked_value 复用 | replaced_by_key_id 关联同应用新 Key；摘要仍唯一。名称是代际显示标签，取消本计划旧的 application_id+name 逻辑唯一建议。旧 Key 不删，历史调用仍指向原 ID。 |
| DB-202 application_key_operation（新增） | id ID PK；application_id ID、operation VARCHAR(16)、target_key VARCHAR(36)、idempotency_key VARCHAR(128)、request_hash VARCHAR(64)、result_key_id ID、result_json JSON、created_at 时间均必填 | 唯一(application_id,operation,target_key,idempotency_key)；创建 target_key 固定空串，轮换为旧 Key ID。结果只含非敏感元数据，禁止保存 secret。与 Key 写入同事务；唯一冲突后读取原结果/校验 hash。 |
| DB-203 application_quota_policy | 复用当前行和 version；新增 current_period_id ID、policy_version BIGINT，回填完成后必填 | 保持 application_id 唯一，作为当前策略入口；已用/预占可保留为当前周期投影，必须同事务更新。 |
| DB-203 application_quota_policy_history（新增） | id ID PK；application_id ID、policy_version BIGINT、period_id ID、policy_json JSON、operator_id VARCHAR(128)、reason VARCHAR(500)、created_at 时间必填 | 唯一(application_id,policy_version)；只追加，policy_json 固定存 token_limit、amount_limit、currency、rpm、tpm、period_type、timezone 和起止，不存累计用量。 |
| DB-203 application_quota_period（新增） | id ID PK；application_id ID、period_no BIGINT、period_type VARCHAR(16)、timezone VARCHAR(64)、currency CHAR(3)、status VARCHAR(16)、created_at/updated_at 时间必填；period_start/end 时间可空；previous_period_id ID 可空；opening_tokens_used、tokens_used、tokens_reserved BIGINT 及 opening_amount_used、amount_used、amount_reserved DECIMAL(30,8) 必填默认 0 | 唯一(application_id,period_no)；状态 OPEN/CLOSING/CLOSED。当前期转 CLOSING 后禁止新准入，旧预占仍可结算；全部终态后 CLOSED。period_no 在应用锁内分配。人工单维重置以 opening_* 承接未重置维度；自然滚动/续期 opening_*=0；tokens_used/amount_used 为含期初余额的总已用，展示不得再重复相加。 |
| DB-203 budget_reservation、usage_ledger | 各新增 period_id ID、policy_version BIGINT 可空（新写必须由服务和可实现的约束保证非空）；新增 context_origin VARCHAR(16) 必填，回填后新写默认 V2 | context_origin=V2/LEGACY_UNKNOWN；遗留未知行只标 LEGACY_UNKNOWN，禁止捏造历史版本。Reservation 终态处理按其原 period_id 更新用量，不按当前周期补扣；索引 (application_id,period_id) 支持对账。 |
| DB-204 quota_adjustment | 复用原表、原唯一(application_id,idempotency_key)，新增 before_policy_version/after_policy_version BIGINT 可空、change_json JSON 可空 | 已发生调整只追加，不用 UPDATE 改写过去 before/delta/after。整体策略变更 dimension=POLICY，数值列可空，change_json 存逐字段摘要；Token/金额调整维持现有数值字段。 |
| DB-204 application_quota_operation（新增） | id ID PK；application_id ID、idempotency_key VARCHAR(128)、request_hash VARCHAR(64)、command_json JSON、expected_policy_version BIGINT、effective_at 时间、status VARCHAR(16)、created_at/updated_at 时间必填；result_json JSON 可空 | 唯一(application_id,idempotency_key)，到期扫描索引(status,effective_at)。状态 SCHEDULED/APPLIED/CONFLICT/FAILED；执行结果在操作记录收敛，成功才追加 quota_adjustment 与策略历史。锁顺序 application→operation→period；扫描器并发通过条件更新/行锁保证单次执行。 |
| DB-205 application_model_permission | constraints_json 复用；max_output_tokens 与 allow_stream 为规范键 | 新迁移把 stream_allowed 转 allow_stream；同时存在且不一致、类型错误的行停止迁移并给出不含敏感值的修复报告，不丢弃约束。历史快照保持只读，未转换快照不能作为新活动版本。 |

迁移与事务交付顺序：

1. DB-201：批量列表查询、归档锁与占用查询；BE-201 完成 code 冲突映射、摘要和归档测试。
2. DB-202：Key 关联字段和幂等操作表；BE-202 在同一事务内创建新代际并返回一次性原文。
3. DB-203/204：周期/策略历史和预约操作，先回填当前状态，再切换准入/结算写入；BE-204 接入。数据库和 Redis 使用可恢复状态机，不宣称同一事务。
4. DB-205：约束 JSON 转换与原子快照发布协调；BE-203 统一候选目录、模型授权和业务准入规则。

回填仅记录可证明的当前策略/周期，不推断已丢失的历史。已有 RESERVED 请求应在升级前排空或显式映射到当前回填周期；无法对应的已结束记录保持遗留标识。数据升级失败阻止激活新服务；回滚先验证没有新格式写入，否则从受控备份恢复，禁止直接删新表伪装回退。

验收必须包括真实 PostgreSQL/MySQL 唯一冲突、归档/准入竞争、双并发轮换、响应丢失后的幂等重放、降低额度至已用以下、自然滚动与晚到结算、人工重置/续期的未终态占用拒绝、单维重置不清零另一维度、预约版本冲突及未知 JSON。未运行的环境保持未验收。
