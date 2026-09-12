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
| FE-P20 | FE-201～FE-205 | 未领取 | light-ai-admin-ui | 待领取 | 可领取；BE-201/202 先收口应用契约，页面与状态可同步开发 |
| FE-P21 | FE-211～FE-215 | 未领取 | light-ai-admin-ui | 待领取 | 可领取；对应 BE/DB 契约联调后验收 |
| FE-P22 | FE-221～FE-225 | 未领取 | light-ai-admin-ui | 待领取 | 可领取；对应 BE/DB 契约联调后验收 |
| FE-P23 | FE-231～FE-235 | 未领取 | light-ai-admin-ui | 待领取 | 可领取；对应 BE/DB 契约联调后验收 |
| BE-P20 | BE-201～BE-205 | 未领取 | client/admin/runtime/server/spi | 待领取 | 可领取；优先应用请求/响应契约及权限差异 |
| BE-P21 | BE-211～BE-215 | 未领取 | client/admin/runtime/server/spi | 待领取 | 可领取；迁移与 DB 协调 |
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
