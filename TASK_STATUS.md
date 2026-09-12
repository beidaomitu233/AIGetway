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
| FE-P21 | FE-211～FE-215 | 前端执行模型 codex-0912 / feature/frontend-p21-codex-0912 | light-ai-admin-ui 渠道、上游模型、虚拟模型与路由页面及 API、相关组件、导航权限、测试；FRONTEND_PLAN.md、COMMUNICATION.md | 进行中 | 2026-09-12 领取；独立工作目录 .worktrees/frontend-p20-baseline；按已确认契约实现，与 BE-P21 联调后验收 |
| FE-P22 | FE-221～FE-225 | 未领取 | light-ai-admin-ui | 待领取 | 可领取；对应 BE/DB 契约联调后验收 |
| FE-P23 | FE-231～FE-235 | 未领取 | light-ai-admin-ui | 待领取 | 可领取；对应 BE/DB 契约联调后验收 |
| BE-P20 | BE-201～BE-205 | 后端执行模型 codex-be-0912 / feature/backend-p20-codex-be-0912 | light-ai-admin 应用/API/权限与测试、light-ai-client/application、必要 server/runtime 应用鉴权测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 阻塞 | 2026-09-12：bd95691 已合入远程 dev；data 包装/GET 子资源/校验与 API 测试已交付；BE-P20-001～005 待架构/DB 确认，五项未勾选；保留负责人，暂停后续实现，未解除占用 |
| BE-P21 | BE-211～BE-215 | 后端执行模型 codex-be-0912 / feature/backend-p21-codex-be-0912 | light-ai-admin/channel、upstream、alias、check、同步与权限映射；相关 client DTO、runtime/route 零权重排除、server 装配与后端测试；BACKEND_PLAN.md、COMMUNICATION.md；不修改前端和数据库迁移 | 进行中 | 2026-09-12 领取；渠道/上游/虚拟模型/嵌套路由契约与服务回归；独立 worktree .worktrees/backend-p21-codex-0912 |
| BE-P22 | BE-221～BE-225 | 未领取 | client/admin/runtime/server/spi | 待领取 | 可领取；迁移与 DB 协调 |
| BE-P23 | BE-231～BE-235 | 未领取 | client/admin/runtime/server/spi | 待领取 | 可领取；迁移与 DB 协调 |
| DB-P20 | DB-201～DB-205 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| DB-P21 | DB-211～DB-215 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| DB-P22 | DB-221～DB-225 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| DB-P23 | DB-231～DB-235 | 未领取 | storage-jdbc/storage-redis、版本迁移 | 待领取 | 可领取；先复核现有 V2/V3/V4 迁移，不重复建表 |
| RV-P20 | RV-201～RV-206 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P21 | RV-211～RV-216 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P22 | RV-221～RV-226 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
| RV-P23 | RV-231～RV-236 | 未领取 | 验收记录；无产品代码占用 | 待依赖 | 待相应 FE/BE/DB 交付；先准备验收场景 |
