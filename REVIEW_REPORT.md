# P4 旧模块下线审查记录

- 审查分支：`fix/fullstack-integration-P4-root`
- 基线：`origin/dev`，并合入已完成的 P1-P5-B 集成基线
- 负责人：代码审查与修复模型/root
- 日期：2026-09-15
- 结论：进行中（旧访问凭证已完成删除，剩余全局路由/发布/运行配置依赖待迁移）

## 本批已完成

| 编号 | 级别 | 问题与依据 | 修复与验证 | 状态 |
| --- | --- | --- | --- | --- |
| P4-FE-001 | P1 | 旧模型、虚拟模型、限流/可靠性/熔断、草稿发布、运行参数、旧访问凭证和独立开发接入仍出现在主导航或可访问路由 | 删除主路由，历史路径重定向到应用/渠道工作台；移除顶部待发布入口、渠道页旧跳转和旧访问凭证页面/API/Mock；`deprecatedRoutes.test.ts` 9/9、运行/审计测试 14/14、`npm run typecheck`/`npm run build` 通过 | 已验证 |
| P4-BE-002 | P1 | 旧访问凭证仍有管理控制器、服务、JDBC 仓储和客户端契约，默认鉴权装配依赖已退役表 | 删除旧控制器/服务/仓储/DTO 与 API 清单入口，`AccessTokenAuthService` 仅使用应用密钥；`ApplicationKeyServiceTest`、`LightAiAdminAutoConfigurationTest` 和全仓编译通过 | 已验证 |
| P4-DB-001 | P1 | 旧访问凭证表仍在基线结构清单中，升级后会继续被 SchemaGuard 视为必需 | 新增 V12 MySQL/PostgreSQL 迁移删除两张表，SchemaContract/ExpectedSchema 同步更新；迁移、SchemaGuard 测试 10/10 通过 | 已验证 |
| P4-BE-003 | P1 | 应用映射运行时快照回查 `virtual_model`/`upstream_model`，删除旧目录会阻断应用调用 | 快照读取仅使用 V2 映射目标名称和渠道连接，保存不再依赖旧目录回查；H2 删除旧表后快照/运行映射测试通过 | 已验证 |
| P4-BE-004 | P1 | 运行参数、上游模型、虚拟模型/候选路由、治理策略、草稿/发布等旧控制器仍由自动配置暴露 | 移除对应 HTTP 控制器 Bean，保留内部发布服务和实例接口；`LightAiAdminAutoConfigurationTest` 6/6 通过 | 已验证 |

## 剩余问题

| 编号 | 级别 | 位置与依据 | 影响 | 状态 |
| --- | --- | --- | --- | --- |
| P4-BE-001 | P1 | `JdbcApplicationRepository` 仍读取 `virtual_model`/`route_candidate` 维护历史模型范围；`ServerApplication` 仍提供 `runtime_config`/`ConfigSnapshotPort`；草稿/发布服务仍有装配依赖 | 继续删除这些表前需迁移应用密钥模型范围、全局路由选择、运行配置版本和发布协调，避免权限或调用历史回归 | 进行中 |

## 验证

- `npm run typecheck`：通过。
- `npm run build`：通过（仅有既有大 chunk 警告）。
- `npm run test -- --run tests/runtimeAccess.test.ts tests/deprecatedRoutes.test.ts`：通过，14/14。
- `npx eslint mocks/adminMockPlugin.ts mocks/runtimeAccessMock.ts tests/runtimeAccess.test.ts tests/deprecatedRoutes.test.ts`：通过；全量 lint 仍有 5 个既有错误（ApplicationDetailPage 3 项、developerPage.test 2 项）。
- `mvn -pl light-ai-storage-jdbc -am -Dtest=DefaultSchemaMigratorTest,SchemaGuardTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，10/10。
- `mvn -pl light-ai-admin -am -Dtest=ApplicationKeyServiceTest,LightAiAdminAutoConfigurationTest,ApplicationRuntimeSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，10/10。
- `mvn -pl light-ai-admin -am -Dtest=LightAiAdminAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，6/6。
- `mvn -DskipTests compile`：通过，全仓 14 模块。
- 未执行真实供应商调用、PostgreSQL/MySQL 实例上的全新库迁移和剩余旧路由/发布服务删除后的整链路回归；这些依赖 P4-BE-001 完成及授权环境。

## Commit / 远程状态

- `d70cafd` `fix(fullstack): P4 下线旧管理页面入口`
- `bc3d24d` `test(fullstack): P4 覆盖旧路由重定向`
- `c80bcf1` `docs(fullstack): P4 记录旧模块下线审查`
- `fec5405` `fix(fullstack): P4 下线旧访问凭证运行面`
- `5ecb928` `fix(fullstack): P4 解耦应用映射运行时旧表`
- `839d651` `fix(fullstack): P4 删除旧访问凭证前端入口`
- 689e076 ix(fullstack): P4 下线旧管理控制器装配
- 文档提交后推送到 `origin/fix/fullstack-integration-P4-root`；未直接合并 `dev`，等待后续运行链路迁移和独立评审。

## 合并建议

当前可合入已验证的旧访问凭证删除、V12 迁移和应用映射快照解耦提交；P4 包保持进行中。完成应用密钥模型范围、全局路由、运行配置版本和发布协调迁移，并通过 PostgreSQL/MySQL/全新库回归后，再提交 P4 完成复验。
