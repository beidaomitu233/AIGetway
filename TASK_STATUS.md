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
| BE-P20 | BE-201～BE-205 | 后端执行模型 codex-be-0912 / feature/backend-p20-codex-be-0912 | light-ai-admin 应用/API/权限与测试、light-ai-client/application、必要 server/runtime 应用鉴权测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 阻塞 | 2026-09-12：bd95691 已合入远程 dev；data 包装/GET 子资源/校验与 API 测试已交付；BE-P20-001～004 技术处理已确认，005 权限边界已明确、企业身份选型待确认；DB-P20 迁移/真实存储、真实 Provider、企业身份及首调 E2E 未验收；五项未勾选，保留原负责人及占用，整体维持阻塞；仅原负责人可推进无依赖子项，见 COMMUNICATION 第 8 节 |
| BE-P21 | BE-211～BE-215 | 后端执行模型 codex-be-0912 / feature/backend-p21-codex-be-0912 | light-ai-admin/channel、upstream、alias、check、同步与权限映射；相关 client DTO、runtime/route 零权重排除、storage-jdbc/JdbcChannelCredentialRepository SQL 修复（无迁移）、server 装配与后端测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 阻塞 | 2026-09-12：c08d625 已随 cc6b73a 合入远程 dev；渠道/嵌套 Key/上游/虚拟模型/路由路径、安全与 JDBC 修复已交付；两目录 verify 均 437 通过、16 环境跳过；BE-P21-001～006 契约/数据库及真实环境待确认验收，五项未勾选，保留负责人，暂停依赖实现，未解除占用 |
| BE-P22 | BE-221～BE-225 | 后端执行模型 codex-be-0912 / feature/backend-p22-codex-be-0912 | client/admin/runtime/server/spi：/v1/models 与 Chat 协议、原子准入与 Reservation、路由与 Key 选择、恢复与流式边界、结算与账本及后端测试；范围修订：storage-jdbc 的 JdbcApplicationQuotaPort（账本渠道归属/额度维度错误）、JdbcRuntimeStateWriter（限流冷却 reset_at 写入，无迁移）、JdbcChannelCredentialSecretPort（同级权重选 Key）；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 阻塞 | 2026-09-12：a0d15f5 实现与 ca4459b 文档已随 2b51220 合入远程 dev；统一协议/原子准入/Attempt 身份与 Key 权重/恢复顺序/崩溃收敛子项交付，新增 14 项测试，14 模块 verify 451 项通过；真实 Redis/双数据库/Provider/首调 E2E 未验收，BE-P22-001～006 登记 COMMUNICATION；五项未勾选，保留负责人，未解除占用 |
| BE-P23 | BE-231～BE-235 | 后端执行模型 zcode-be-0912 / feature/backend-p23-zcode-be-0912 | light-ai-admin 调用/用量/发布/设置与审计接口及 DTO、light-ai-client 相关契约对象、必要 server 装配与后端测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端与已发布数据库迁移，新增迁移先与 DB 包协调 | 进行中 | 2026-09-12 领取；基于已合入 dev 的 BE-P22 Trace/Attempt/账本资产实现观测、发布与设置；独立工作目录 .worktrees/backend-p23-zcode-be-0912；迁移与 DB 协调 |
| DB-P20 | DB-201～DB-205 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| DB-P21 | DB-211～DB-215 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| DB-P22 | DB-221～DB-225 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| DB-P23 | DB-231～DB-235 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| RV-P20 | RV-201～RV-206 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P21 | RV-211～RV-216 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P22 | RV-221～RV-226 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P23 | RV-231～RV-236 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
