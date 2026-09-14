# P4 旧模块下线审查记录

- 审查分支：`fix/fullstack-integration-P4-root`
- 基线：`origin/dev`，并合入已完成的 P1-P5-B 集成基线
- 负责人：代码审查与修复模型/root
- 日期：2026-09-15
- 结论：进行中（前端入口已下线，后端和数据库删除待运行链路迁移）

## 本批已完成

| 编号 | 级别 | 问题与依据 | 修复与验证 | 状态 |
| --- | --- | --- | --- | --- |
| P4-FE-001 | P1 | 旧模型、虚拟模型、限流/可靠性/熔断、草稿发布、运行参数、旧访问凭证和独立开发接入仍出现在主导航或可访问路由 | 删除主路由，历史路径重定向到应用/渠道工作台；移除顶部待发布入口和渠道页旧跳转；`deprecatedRoutes.test.ts` 9/9、`npm run typecheck` 通过 | 已验证 |

## 剩余问题

| 编号 | 级别 | 位置与依据 | 影响 | 状态 |
| --- | --- | --- | --- | --- |
| P4-BE-001 | P1 | `JdbcApplicationModelMappingRepository`、`JdbcApplicationRepository` 仍读取 `virtual_model`/`route_candidate`；`ServerApplication` 仍提供 `runtime_config`/`ConfigSnapshotPort`；`LightAiAdminAutoConfiguration` 仍注册旧服务 | 未完成运行时迁移前删除旧服务或表会破坏应用映射解析、路由选择、调用历史关联和配置版本读取 | 进行中 |

## 验证

- `npm run typecheck`：通过。
- `npm run test -- --run tests/deprecatedRoutes.test.ts`：通过，9/9。
- `mvn -pl light-ai-admin,light-ai-storage-jdbc -am -DskipTests compile`：通过。
- 未执行真实供应商调用、PostgreSQL/MySQL 全新库迁移和后端删除后的整链路回归；这些依赖 P4-BE-001 完成及授权环境。

## 合并建议

当前仅建议合入前端入口下线提交；P4 包保持进行中。完成运行时配置版本迁移、历史快照引用处理、后端控制器/服务/仓储删除和 PostgreSQL/MySQL/全新库回归后，再提交 P4 完成复验。
