# 轻享 AI V2.0 项目执行总文档

## 1. 文档基线与快速检查（2026-09-12）

需求基线为仓库根目录《轻享AI-企业AI中台-产品需求说明书-PRD-V2.0.md》。代码主线为 `fix/fullstack-review-optimization`，已快进到 `origin/fix/fullstack-review-optimization` 的 `9e074b5`。此前在 `dev` 执行 `git pull --ff-only`，结果为最新；未跟踪文件 `scripts/e2e-openrouter.sh` 已保留。

## 2. 产品目标与范围

轻享 AI V2.0 是面向企业内部应用的统一 AI API Gateway 与治理中台。应用是权限、额度、速率、调用和成本归属的第一业务对象；业务系统只持有应用密钥并调用虚拟模型，平台集中管理渠道、上游 Key、真实模型、路由、恢复和结算。

首期交付集中式 Standalone Gateway、管理后台和可选 Java 客户端便利层，覆盖文本 Chat Completions。注册、充值、订阅、支付、多租户运营、Prompt、知识库、RAG、工作流、Agent Runtime、多模态和训练不在范围内；Embedded/Local Runtime 不作为首期验收门槛。

## 3. 快速差距结论

| 领域 | 远端功能分支可见资产 | 快速判断 | 剩余包 |
|---|---|---|---|
| 应用中心 | 应用/密钥/额度/成员/模型约束的后端、存储及部分页面 | 已开发待集成和系统验收 | FE-P20、BE-P20、DB-P20 |
| 渠道与上游模型 | V4 迁移、存储和后端资源域重命名 | 数据/后端基础已有，前端和运行联调待完成 | FE-P21、BE-P21、DB-P21 |
| 虚拟模型与路由 | 旧 Alias/候选资产、应用模型约束 | 需收口 V2 术语、能力交集和应用影响 | FE-P22、BE-P22、DB-P22 |
| 网关治理 | Chat、容量、恢复、流式、Redis 与应用配额端口 | 应用鉴权、预算、RPM/TPM、预占账本缺完整证据 | BE-P22、DB-P22 |
| 观测与成本 | Trace/Attempt/Usage 与应用维度 | 账本事实源、价格快照、多币种和范围验收待闭环 | FE-P22、BE-P23、DB-P22 |
| 发布与可靠性 | 草稿、发布、熔断资产 | 需对齐新资源域与即时安全操作 | FE-P22、BE-P23、DB-P23 |
| 身份与交付 | 旧四角色权限和大量单测 | 企业身份、成员数据范围、真实环境门禁未闭环 | FE-P23、BE-P23、DB-P23 |

文件存在和历史提交不代表任务完成；只有在当前任务包验收、测试和审查通过后才能勾选。

## 4. 角色与权限

| 角色 | 核心能力 | 数据范围 |
|---|---|---|
| 系统管理员 | 应用、渠道、模型、路由、发布、成员、设置、审计 | 全平台 |
| 平台运维 | 全局运行查看、渠道检测/启停、故障处置 | 全平台运行数据，不见密钥原文 |
| 应用负责人 | 所属应用的密钥、模型、额度、接入、调用和用量 | 显式应用成员关系 |
| 审计查看者 | 配置、调用、成本和审计只读 | 授权范围 |
| 业务应用 | `/v1/models` 与 Chat 调用 | 单一应用密钥绑定的应用 |

页面入口、按钮、API 和查询层同时鉴权。跨应用访问返回 403 并写审计；应用密钥不能登录后台，管理会话不能替代业务 API 密钥。

## 5. 核心业务流程

1. 应用接入：创建应用 → 授权虚拟模型 → 配置 Token/金额/RPM/TPM → 签发一次性应用密钥 → 生成接入信息 → 首次真实调用。
2. 资源接入：创建渠道 → 配置多 Key → 检测 → 同步/维护上游模型与价格 → 建立虚拟模型和候选 → 校验并发布。
3. 运行调用：应用密钥鉴权 → 状态/IP/模型校验 → 原子预算和速率预占 → 锁定配置/策略/价格快照 → 选候选和 Key → 调用与恢复 → 结算/释放 → Trace 与账本。
4. 配额调整：只追加调整单；减少到已用量以下时立即拒绝新请求；重置使用幂等键并审计，不改写历史。
5. 流式恢复：首个业务块提交前可切换；提交后禁止拼接其他路径，只发送统一流内错误并终止。

## 6. 模块、页面与数据

| 模块 | 页面/接口 | 核心实体 |
|---|---|---|
| 总览 | `/overview` | request_trace、usage_aggregate |
| 应用中心 | `/applications/**`、`/admin/applications/**` | application、member、key、quota、model_permission |
| AI 资源 | `/channels/**`、`/models/upstream`、`/models/virtual/**` | channel、credential、upstream_model、virtual_model、route_candidate |
| 网关 | `GET /v1/models`、`POST /v1/chat/completions` | reservation、trace、attempt、usage_ledger |
| 发布 | `/config/releases` | config_snapshot、publish_record、instance_result |
| 观测 | `/calls/**`、`/usage/**` | trace、attempt、ledger、adjustment |
| 系统 | `/login`、`/audit`、`/settings` | member、audit_log、runtime_setting |

## 7. 接口、状态与安全原则

- 成功响应使用 `data`；错误使用 `error={code,message,request_id,retryable,details?}`。写接口使用版本或 `If-Match`，额度、轮换、重置、发布使用幂等键。
- Application：ACTIVE/DISABLED/ARCHIVED；Application Key：ACTIVE/DISABLED/EXPIRED/REVOKED；Trace：RUNNING/SUCCEEDED/FAILED/CANCELLED；Reservation：RESERVED/SETTLED/RELEASED/EXPIRED。
- 配置状态与健康状态分离。应用、密钥撤销、紧急摘除即时生效；渠道、上游模型、虚拟模型和路由经草稿发布后生效。
- 应用密钥只保存摘要和前缀，渠道 Key 加密或保存 Secret 引用。日志、Trace、审计、导出、指标和浏览器存储禁止出现密钥、认证头、Cookie、完整 secret_ref、数据库密码和默认正文。
- Redis/共享状态不可用时拒绝新准入。关系库与 Redis 使用幂等可恢复状态机，不宣称共同事务。
- 金额使用 decimal 字符串和 ISO 4217 币种；无可信汇率时分币种展示。时间存 UTC、按平台时区展示。

## 8. 执行顺序与 Git

阶段依次为：P20 应用闭环 → P21 资源与路由 → P22 准入、观测与结算 → P23 身份、发布与交付。每包 5 项、一次独立提交；完成顺序为测试、自检、勾选 Plan、提交、登记 COMMUNICATION、审查、合入 `dev`。

分支：`main`、`dev`、`feature/frontend-模块名`、`feature/backend-模块名`、`feature/database-模块名`、`docs/architecture-plan`。提交格式：`docs: update architecture plan`、`feat(frontend): complete application page`、`feat(backend): complete application api`、`test: add gateway tests`、`fix: resolve review issue`。

## 9. 待确认项

优先处理 PRD BP-001—BP-012：企业身份方式、应用负责人建应用权限、RPM/TPM 是否强制、统一时区、轮换宽限期、密钥级预算、首批 Provider、币种、保留周期、容量目标、旧数据迁移、Embedded/Local 去留。状态与建议见 `COMMUNICATION.md`。
