# 轻享 AI V2.0 任务协作状态

唯一开发起点：同步后的 origin/dev。本表只登记占用和依赖；Plan 勾选表示实际验收完成。用户已授权前后端开始开发，基线统一不代表产品全量验收。

## 领取与交付

1. git fetch origin，确认本地包含最新 origin/dev；在干净的独立 checkout/worktree 从 origin/dev 建立 feature/frontend-*、feature/backend-*、feature/database-* 分支。
2. 领取前核对最新本表。同包只允许一名负责人，填写负责人、分支、具体文件范围和时间，经普通提交及推送到协作 dev 后才开始编码。推送非快进时先重新读取远端，保留他人记录；不得强推或覆盖占用。
3. 占用登记只改本表；完成/阻塞记录写 COMMUNICATION.md。若依赖仅影响部分任务，登记对应任务并继续无冲突项。
4. 前端负责页面/API 消费和组件测试；后端负责 API/DTO/服务和接口测试；数据库负责版本迁移/索引。后端需要迁移时先协调 DB 包，不交叉改同一迁移。
5. 功能开发在各自分支；验证、自检、勾选 Plan、提交并审查合入 dev 后释放占用。不会自动向其他任务派发消息。

## 任务包

| 任务包 | 编号 | 负责人/分支 | 修改范围 | 状态 | 依赖与入口 |
|---|---|---|---|---|---|
| FE-P20 | FE-201～FE-205 | 前端执行模型 codex-0912 / feature/frontend-p20-codex-0912 | 应用页面/API/测试；FRONTEND_PLAN.md、COMMUNICATION.md；暂停后续写入 | 阻塞 | 2026-09-12：418218d、ef8a972 前端子项及 56347c7 文档已合入远程 dev（5b0b55d）；typecheck/build 通过，199 测试通过，lint 0 error/81 历史 warning。FE-P20-001～004/BE-P20-001～005 待契约与真实联调；五项未勾选，保留负责人，未解除占用 |
| FE-P21 | FE-211～FE-215 | 前端执行模型 codex-0912 / feature/frontend-p21-codex-0912 | light-ai-admin-ui 渠道、上游模型、虚拟模型与路由及相关 API/组件/导航/测试；FRONTEND_PLAN.md、COMMUNICATION.md | 阻塞 | 2026-09-12：def540e/b27689d/fd9014a 已完成契约接入与前端回归，226 项通过；等待 BE-P21-001～006 和真实联调，五项未勾选；8e96984 已合入远程 dev 并普通推送成功，保留负责人、未解除占用 |
| FE-P22 | FE-221～FE-225 | 前端执行模型 zcode-0912 / feature/frontend-p22-zcode-0912 | light-ai-admin-ui 调用记录、用量成本、配置发布、开发接入与总览页面及其 API、相关组件、测试；FRONTEND_PLAN.md、COMMUNICATION.md | 阻塞 | 2026-09-12：fc0e955 实现与 eaa32e3 文档已随 eb2843a 合入远程 dev；过渡端点加固完成，28 文件 244 测试、typecheck/lint/build 通过；V2 路径/字段差异登记 COMMUNICATION FE-P22-001～005；五项未勾选，保留负责人，未解除占用，待 BE-P22/P23 契约与真实联调 |
| FE-P23 | FE-231～FE-235 | 前端执行模型 zcode-0912 / feature/frontend-p23-zcode-0912 | light-ai-admin-ui 身份入口、系统设置与审计、通用交互组件、前端安全与门禁相关页面/组件/测试；FRONTEND_PLAN.md、COMMUNICATION.md | 进行中 | 2026-09-12 领取；现有过渡端点上按 FRONTEND_PLAN 加固，V2 路径/字段差异登记 COMMUNICATION；独立工作目录 .worktrees/frontend-p23-zcode-0912；身份源确认与 BE-P23 联调后验收 |
| BE-P20 | BE-201～BE-205 | 后端执行模型 zcode-be-0912b / feature/backend-p20-zcode-be-0912b | light-ai-admin 应用/API/权限与测试、light-ai-client/application、必要 server/runtime 应用鉴权测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 阻塞 | 2026-09-12 经用户确认原领取（codex-be-0912）会话中断，由本负责人接管；c1bb917 实现与 7315d68 文档已随 e3d9082 合入远程 dev，合并后受影响测试复验通过；本轮交付 BE-201 冲突码/筛选/24h 摘要/批量聚合/归档占用、BE-203 model-options 双端点与收紧规则、BE-204 降额与 PUT 幂等、BE-202 secret 契约、impact 端点及 64 位十进制字符串契约；admin 212 项通过，全仓 verify 477 项 0 失败 16 项环境跳过；轮换代际/周期快照/企业身份等登记 COMMUNICATION BE-P20-107～111，依赖 DB-P20 迁移与 BP 决策；五项未勾选，占用保留待迁移后接续 |
| BE-P21 | BE-211～BE-215 | 后端执行模型 zcode-be-0912c / feature/backend-p21-zcode-be-0912c | light-ai-admin/channel、upstream、alias、check、同步与权限映射；相关 client DTO、runtime/route、storage-jdbc 存储支撑（无迁移）、server 装配与后端测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 进行中 | 2026-09-12 经用户确认原领取（codex-be-0912）会话中断、剩余子项未实际执行，由本负责人接管推进；基线含 c08d625 已交付内容（路径收口、安全与 JDBC 修复，437 项测试通过）；按 BACKEND_PLAN BE-211～215 执行无迁移依赖子项（BE-211 V2 DTO、BE-212 Key priority/冷却、BE-215 运行计数真实化），依赖 DB-P21 迁移与跨包契约的子项如实登记阻塞；五项保持未勾选；独立工作目录 .worktrees/backend-p21-zcode-be-0912c |
| BE-P22 | BE-221～BE-225 | 后端执行模型 codex-be-0912 / feature/backend-p22-codex-be-0912 | client/admin/runtime/server/spi：/v1/models 与 Chat 协议、原子准入与 Reservation、路由与 Key 选择、恢复与流式边界、结算与账本及后端测试；范围修订：storage-jdbc 的 JdbcApplicationQuotaPort（账本渠道归属/额度维度错误）、JdbcRuntimeStateWriter（限流冷却 reset_at 写入，无迁移）、JdbcChannelCredentialSecretPort（同级权重选 Key）；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 阻塞 | 2026-09-12：a0d15f5 实现与 ca4459b 文档已随 2b51220 合入远程 dev；统一协议/原子准入/Attempt 身份与 Key 权重/恢复顺序/崩溃收敛子项交付，新增 14 项测试，14 模块 verify 451 项通过；真实 Redis/双数据库/Provider/首调 E2E 未验收，BE-P22-001～006 登记 COMMUNICATION；五项未勾选，保留负责人，未解除占用 |
| BE-P23 | BE-231～BE-235 | 后端执行模型 zcode-be-0912 / feature/backend-p23-zcode-be-0912 | light-ai-admin 调用/用量/发布/设置与审计接口及 DTO、light-ai-client 相关契约对象、必要 server 装配与后端测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端与已发布数据库迁移，新增迁移先与 DB 包协调 | 进行中 | 2026-09-12 领取；基于已合入 dev 的 BE-P22 Trace/Attempt/账本资产实现观测、发布与设置；独立工作目录 .worktrees/backend-p23-zcode-be-0912；迁移与 DB 协调 |
| DB-P20 | DB-201～DB-205 | 数据库执行模型 zcode-0912 / feature/database-p20-zcode-0912 | storage-jdbc 双方言版本迁移与存储支撑、storage-redis 复核；DATABASE_PLAN.md、COMMUNICATION.md；不修改前端与后端服务代码 | 进行中 | 2026-09-12 领取；先复核现有 V2/V3/V4 迁移，按 DATABASE_PLAN「BE-P20 数据库交付契约」新增应用域迁移，不重复建表、不修改已发布迁移；迁移号领取时按远端最大版本分配 |
| DB-P21 | DB-211～DB-215 | 数据库执行模型 zcode-db-0912c / feature/database-p21-zcode-db-0912c | storage-jdbc 双方言版本迁移与存储支撑、storage-redis 复核；DATABASE_PLAN.md、COMMUNICATION.md、TASK_STATUS.md；不修改前端与 admin/client/runtime/server 服务代码 | 进行中 | 2026-09-12 经用户指定由本负责人接管（原领取 zcode-db-0912b 会话中断、无交付、无远程分支）；先复核现有 V2/V3/V4 迁移与 BE-P21-001～006 数据缺口，按远端当时最大版本号（V4）自 V5 起新增渠道/模型/路由域迁移，不重复建表、不修改已发布迁移；迁移号 V5 登记为 DB-P21 占用，DB-P20 应用域建议自 V6 起，与 DB-P20 并发协调；独立工作目录 .worktrees/database-p21-zcode-db-0912c |
| DB-P22 | DB-221～DB-225 | 数据库执行模型 zcode-db-0912b / feature/database-p22-zcode-db-0912b | storage-jdbc 双方言版本迁移与存储支撑、storage-redis 复核；DATABASE_PLAN.md、COMMUNICATION.md、TASK_STATUS.md；不修改前端与 admin/client/runtime/server 服务代码 | 进行中 | 2026-09-12 领取（用户指定本会话承接剩余数据库包）；排队于 DB-P21 之后顺序执行；迁移号领取时按远端最大版本分配；真实 PostgreSQL/MySQL/Redis 环境缺失时相应验收项保持未勾选 |
| DB-P23 | DB-231～DB-235 | 数据库执行模型 zcode-db-0912b / feature/database-p23-zcode-db-0912b | storage-jdbc 双方言版本迁移与存储支撑；DATABASE_PLAN.md、COMMUNICATION.md、TASK_STATUS.md；不修改前端与 admin/client/runtime/server 服务代码 | 进行中 | 2026-09-12 领取（用户指定本会话承接剩余数据库包）；排队于 DB-P22 之后顺序执行；含 V1→V2 迁移兼容与数据库交付门禁；未运行环境保持未勾选 |
| RV-P20 | RV-201～RV-206 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P21 | RV-211～RV-216 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P22 | RV-221～RV-226 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P23 | RV-231～RV-236 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
