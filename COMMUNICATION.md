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
