# 轻享 AI V2.0 后端执行计划

## 1. 执行边界

开发基线统一为 origin/dev。BE-P20 可从 TASK_STATUS.md 领取；BE-201/202 首先处理 COMMUNICATION.md 的 CONTRACT-V2-001，交付应用和密钥的精确字段、响应包装及接口测试供前端使用。身份源等未决项仅限制对应任务；执行环境故障按 ENV-V2-001 验证审批通道。

任务包从 P20 编号，用于避免与既有提交记录中的编号冲突。技术栈沿用 Java 17 字节码、Spring Boot Standalone、模块化 Maven、JDBC、PostgreSQL/MySQL、Redis 原子脚本和 JDK HttpClient。V2 首期权威配置位于集中式服务，公共 client/spi/runtime 不依赖 Spring，Adapter 只承担单次调用与协议映射。

## 2. API 与错误基线

- 业务 API：`GET /v1/models`、`POST /v1/chat/completions`；Bearer 为应用密钥，响应包含 `X-Request-Id`。
- 管理 API：`/admin/applications`、`/admin/channels`、`/admin/upstream-models`、`/admin/virtual-models`、`/admin/config-releases`、`/admin/calls`、`/admin/usage`、`/admin/audit-logs`、`/admin/settings`。
- 错误：`error={code,message,request_id,retryable,details?}`；400 校验、401 业务密钥认证、403 权限、404 对象、409 版本/状态、429 额度/速率、502/503 上游、504 总超时。
- 写接口携带 `version`/ETag；调整、重置、轮换、发布携带幂等键。日志只记录错误分类和 request_id，不记录密钥、认证头、正文、堆栈或上游敏感响应。

## BE-P20 应用、密钥与权限（5 项）

- [ ] 任务编号：BE-201
  模块：应用管理
  目标：复核并完成应用列表、创建、详情、编辑、启停和归档 API。
  接口：`GET/POST /admin/applications`、`GET/PUT /admin/applications/{id}`、状态命令。
  请求/响应：code、name、department、owner、environment、description、status、version 及运行摘要。
  业务流程：校验数据范围 → 唯一 code → 乐观锁写入 → 审计；归档要求已禁用且无运行请求。
  异常处理：重复 code 409、越权 403、版本冲突 409、活动请求阻止归档。
  数据表：application、application_member、audit_log。
  验收/测试：覆盖四角色、跨应用、并发编辑、禁用即时拒绝和归档前置。

- [ ] 任务编号：BE-202
  模块：应用密钥
  目标：完成高熵签发、摘要鉴权、轮换、宽限、禁用、启用、撤销和 IP/CIDR 校验。
  接口：`/admin/applications/{id}/keys/**`；业务鉴权 Bearer。
  响应字段：一次性 secret、key_prefix、status、expires_at、grace_expires_at、version。
  业务流程：生成 → 单向摘要 → 保存 → 一次返回；轮换生成新记录，撤销永久终止。
  异常处理：重复幂等键返回原业务结果但不再暴露 secret；策略越界 400；状态冲突 409。
  数据表：application_key、audit_log。
  验收/测试：数据库无原文；过期/撤销/IP 错误在上游前拒绝；日志脱敏。

- [ ] 任务编号：BE-203
  模块：应用模型权限
  目标：授权已发布且可路由的虚拟模型，并支持应用/密钥级参数收紧。
  接口：`GET/PUT /admin/applications/{id}/models`、`GET /v1/models`。
  请求/响应：virtual_model_id、enabled、max_output_tokens、allow_stream、version。
  业务流程：验证模型活动与路由 → 验证能力边界 → 保存权限 → 新请求即时生效。
  异常处理：无路由、能力越界、应用/密钥范围放宽、版本冲突。
  数据表：application_model_permission、application_key_model_scope、virtual_model。
  验收/测试：`/v1/models` 仅返回授权可用模型；取消授权不终止已准入请求。

- [ ] 任务编号：BE-204
  模块：应用额度与调整
  目标：完成 Token/金额/RPM/TPM、周期、续期、增减和人工重置。
  接口：`GET/PUT /quota`、`POST /quota/adjustments`、`POST /quota/reset`。
  请求/响应：limit、used、reserved、remaining、currency、period、reset_at、version、reason。
  业务流程：校验 → 幂等调整单 → 原子更新当前策略 → 审计；历史账本不改写。
  异常处理：币种冲突、非法周期、减少至已用以下、重复请求、版本冲突。
  数据表：application_quota_policy、quota_adjustment、usage_ledger。
  验收/测试：重复提交不重复加额；降低后立即拒绝新请求；自然周期按统一时区滚动。

- [ ] 任务编号：BE-205
  模块：企业身份与应用成员
  目标：接入确认的企业身份源，建立管理会话、RBAC 和应用成员数据范围。
  接口：登录/回调/退出/bootstrap、`/admin/applications/{id}/members`。
  业务流程：验证身份 → 映射角色/组 → 合并权限与范围 → 请求层和查询层双重执行。
  异常处理：身份源超时、回调非法、无角色、会话过期、跨应用访问。
  数据表：application_member、audit_log；外部身份只存稳定 subject id。
  验收/测试：四角色矩阵、403 审计、应用密钥与管理身份隔离。

## BE-P21 渠道、模型与路由（5 项）

- [ ] 任务编号：BE-211
  模块：渠道管理
  目标：收口 Provider→Channel 资源域，完成渠道 CRUD、状态、影响和检测。
  接口：`/admin/channels/**`。字段：provider_type、base_url、proxy、timeouts、headers、priority、weight、status、health、version。
  业务流程：Provider 能力校验 → URL 安全校验 → 草稿写入或即时状态命令 → 审计。
  异常处理：危险目标/重定向、未知 Provider、活动引用、版本冲突。
  数据表：channel、channel_check_record、draft_change。
  验收/测试：SSRF/DNS 重绑定/TLS、状态与健康分离、禁用影响。

- [ ] 任务编号：BE-212
  模块：渠道多 Key
  目标：完成加密写入、轮换、启停、检测、优先级/权重、Key 级 RPM/TPM、冷却和健康。
  接口：`/admin/channels/{id}/credentials/**`。
  业务流程：受保护写入 → 加密/Secret 引用 → 单 Key 检测 → 调度选择；认证失败只摘除当前 Key。
  异常处理：KMS 不可用 fail-closed、429 冷却、重复名称、最后可用 Key 影响。
  数据表：channel_credential、channel_check_record、audit_log。
  验收/测试：原文不可读出；同级权重分布；单 Key 故障切换。

- [ ] 任务编号：BE-213
  模块：上游模型
  目标：完成同步预览/提交、人工维护、能力与价格校验、人工锁定字段。
  接口：`/admin/upstream-models/**`、渠道同步接口。
  请求/响应：channel_id、model_id、capabilities、context_window、max_output、prices、currency、source、locked_fields、status。
  业务流程：Adapter 获取 → diff → 预览 → 幂等提交；启用前验证完整价格和能力。
  异常处理：渠道不可用、同步冲突、模型下线、价格缺失。
  数据表：upstream_model、model_sync_job/item。
  验收/测试：不自动覆盖锁定字段；同渠道 model_id 唯一。

- [ ] 任务编号：BE-214
  模块：虚拟模型
  目标：提供稳定 code、能力安全交集、应用授权影响和状态管理。
  接口：`/admin/virtual-models/**`。
  业务流程：计算候选能力交集 → 应用显式收紧 → 发布校验 → 对外模型目录。
  异常处理：code 重复、候选能力不相容、无活动路由、被应用引用。
  数据表：virtual_model、application_model_permission、route_candidate。
  验收/测试：不支持显式参数在路由前拒绝；code 不随上游迁移改变。

- [ ] 任务编号：BE-215
  模块：路由候选
  目标：按优先级和同级权重配置候选，完成能力、Key、价格和应用影响校验。
  接口：`/admin/virtual-models/{id}/routes/**`。
  业务流程：重复/匹配校验 → 草稿保存 → 发布校验 → 快照生效。
  异常处理：重复候选、渠道模型不匹配、无 Key、价格缺失、版本冲突。
  数据表：route_candidate、draft_change、config_snapshot。
  验收/测试：零权重不接正常流量；权重不跨优先级；切换建立新 Attempt。

## BE-P22 网关准入、恢复与结算（5 项）

- [ ] 任务编号：BE-221
  模块：统一业务协议
  目标：稳定实现 `GET /v1/models` 与同步/SSE Chat，校验虚拟模型和显式参数。
  请求/响应：OpenAI 兼容字段、Usage、finish_reason、单一 `[DONE]`、`X-Request-Id`。
  业务流程：请求校验 → ApplicationContext → 准入 → Runtime → 统一序列化。
  异常处理：未知字段策略、非法 request_id、客户端取消、流内错误。
  数据表：无直接管理写入；形成 Trace。
  验收/测试：协议兼容、chunk 顺序、单终态、取消传播。

- [ ] 任务编号：BE-222
  模块：原子准入
  目标：同时执行应用/密钥模型、Token、金额、RPM 和 TPM 的原子预占。
  业务流程：保守估算 → Redis 原子窗口/容量 → 数据库 Reservation → 成功放行或完整回滚。
  异常处理：共享状态不可用、预算边界、并发超额、预占写失败。
  数据表：budget_reservation；Redis 应用和密钥限额键。
  验收/测试：治理绕过为 0；其他应用不受影响；429 返回维度和 retry_after。

- [ ] 任务编号：BE-223
  模块：路由与 Key 选择
  目标：过滤状态、健康、权限、能力、容量和价格后，先候选再 Key。
  业务流程：锁定快照 → 候选优先级/权重 → Key 优先级/权重 → 建立 Attempt。
  异常处理：无候选、无 Key、容量不足、快照不一致。
  数据表：config_snapshot、request_attempt；共享健康/容量状态。
  验收/测试：同 request 的每个 Attempt 只有单渠道/模型/Key；选择结果可复现。

- [ ] 任务编号：BE-224
  模块：恢复与流式边界
  目标：统一同 Key 重试、换 Key、同级候选和下一优先级预算。
  业务流程：错误分类 → 检查独立次数与总时限 → 新 Attempt → 成功或终止。
  异常处理：上游认证、429、超时、5xx、流首块后错误。
  数据表：request_attempt、recovery_decision、circuit_state。
  验收/测试：首块后不切换；总时限覆盖所有尝试；健康更新不覆盖人工状态。

- [ ] 任务编号：BE-225
  模块：结算与恢复
  目标：按实际 Usage 和价格快照结算，幂等释放剩余预占并写账本。
  业务流程：锁定价格 → 实际/估算 Usage → 原子补扣/释放 → ledger → Trace 终态。
  异常处理：Usage 缺失、实际超预占、账本写失败、进程崩溃、重复回调。
  数据表：usage_ledger、budget_reservation、request_trace、price_snapshot。
  验收/测试：成功/失败/取消/超时唯一终态；重复扣减 0；恢复任务可收敛。

## BE-P23 观测、发布与交付（5 项）

- [ ] 任务编号：BE-231
  模块：调用与时间线
  目标：按应用范围查询 Trace，并组合准入、路由、Attempt、恢复、流提交、结算和终态。
  接口：`GET /admin/calls`、`GET /admin/calls/{requestId}`、受控导出。
  异常处理：运行中数据、观测延迟、过期、越权。数据表：request_trace、request_attempt、recovery_decision。
  验收/测试：request_id 贯穿；默认无正文；导出字段与权限一致。

- [ ] 任务编号：BE-232
  模块：用量成本
  目标：以 usage_ledger 为事实源，按应用、密钥、模型、渠道和时间聚合。
  接口：`/admin/usage/summary|trend|breakdown|adjustments|export`。
  异常处理：多币种、估算 Usage、聚合延迟、重放事件。
  数据表：usage_ledger、usage_aggregate、quota_adjustment。
  验收/测试：聚合可从账本重算；币种不混加；ACTUAL/ESTIMATED 可区分。

- [ ] 任务编号：BE-233
  模块：配置发布与回滚
  目标：将 Channel/Upstream/Virtual/Route 形成不可变完整快照并使实例收敛。
  接口：`/admin/config-releases/**` 与内部实例拉取/确认接口。
  业务流程：草稿 → 校验 → READY → 激活 → 实例确认；失败保留旧活动版本；回滚生成新记录。
  异常处理：引用、能力、价格、无 Key、实例不兼容、部分加载失败。
  数据表：config_snapshot、publish_record、publish_instance_result、runtime_instance。
  验收/测试：新请求新快照、运行请求旧快照、失败无部分激活。

- [ ] 任务编号：BE-234
  模块：系统设置、审计与安全
  目标：管理时区、留存、诊断、网络策略、告警和运行默认值，并覆盖关键操作审计。
  异常处理：越权、版本冲突、非法安全降级、审计写失败。
  数据表：runtime_setting、audit_log、retention_policy。
  验收/测试：关键操作审计 100%；安全设置默认拒绝；日志/导出扫描无敏感值。

- [ ] 任务编号：BE-235
  模块：后端交付门禁
  目标：完成单测、管理 API、业务协议、权限、事务、并发、流式、迁移、Redis、真实 Provider 和 E2E。
  实现说明：分别报告 PostgreSQL、MySQL、Redis 和 Provider 环境；Mock 不替代真实结果。
  验收标准：应用创建到用量结算闭环；附加延迟、吞吐和流并发只报告实测值。
  测试要求：无未解释跳过；失败项写入 COMMUNICATION 并保持任务未勾选。
