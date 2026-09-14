# P4 旧模块下线审查记录

- 审查分支：`fix/fullstack-integration-P4-root`
- 基线：`origin/dev`，并合入已完成的 P1-P5-B 集成基线
- 负责人：代码审查与修复模型/root
- 日期：2026-09-15
- 结论：进行中（旧访问凭证与运行时权限读取已完成迁移，剩余管理历史写路径、发布装配与观测查询依赖待迁移）

## 本批已完成

| 编号 | 级别 | 问题与依据 | 修复与验证 | 状态 |
| --- | --- | --- | --- | --- |
| P4-FE-001 | P1 | 旧模型、虚拟模型、限流/可靠性/熔断、草稿发布、运行参数、旧访问凭证和独立开发接入仍出现在主导航或可访问路由 | 删除主路由，历史路径重定向到应用/渠道工作台；移除顶部待发布入口、渠道页旧跳转和旧访问凭证页面/API/Mock；`deprecatedRoutes.test.ts` 9/9、运行/审计测试 14/14、`npm run typecheck`/`npm run build` 通过 | 已验证 |
| P4-BE-002 | P1 | 旧访问凭证仍有管理控制器、服务、JDBC 仓储和客户端契约，默认鉴权装配依赖已退役表 | 删除旧控制器/服务/仓储/DTO 与 API 清单入口，`AccessTokenAuthService` 仅使用应用密钥；`ApplicationKeyServiceTest`、`LightAiAdminAutoConfigurationTest` 和全仓编译通过 | 已验证 |
| P4-DB-001 | P1 | 旧访问凭证表仍在基线结构清单中，升级后会继续被 SchemaGuard 视为必需 | 新增 V12 MySQL/PostgreSQL 迁移删除两张表，SchemaContract/ExpectedSchema 同步更新；迁移、SchemaGuard 测试 10/10 通过 | 已验证 |
| P4-BE-003 | P1 | 应用映射运行时快照回查 `virtual_model`/`upstream_model`，删除旧目录会阻断应用调用 | 快照读取仅使用 V2 映射目标名称和渠道连接，保存不再依赖旧目录回查；H2 删除旧表后快照/运行映射测试通过 | 已验证 |
| P4-BE-004 | P1 | 运行参数、上游模型、虚拟模型/候选路由、治理策略、草稿/发布等旧控制器仍由自动配置暴露 | 移除对应 HTTP 控制器 Bean，保留内部发布服务和实例接口；`LightAiAdminAutoConfigurationTest` 6/6 通过 | 已验证 |
| P4-BE-005 | P1 | 无实时目录能力的渠道仍回退查询已退役 `upstream_model`，删除旧表会阻断应用映射批量草案 | 改为返回 `manual_input_allowed` 并由应用内手工输入；删除仓储 `catalog/activeModel` 旧查询，目录/映射/快照/应用密钥回归 7/7 通过 | 已验证 |
| P4-BE-001 | P1 | 运行时应用密钥鉴权仍通过 `JdbcApplicationRepository.listModelPermissions` 连接旧 `virtual_model`，Standalone 默认模型仍读取 `runtime_config` | 鉴权按应用映射读取公开模型并按密钥范围过滤，约束只读 `application_model_permission` 元数据；单一应用映射可作为默认模型，Standalone 默认配置端口不再访问旧表。应用密钥回归 4/4、默认模型回归 1/1、全仓编译通过；隔离 H2/Redis/本地桩完成应用→映射→渠道→上游→调用/Trace 实链路 | 已验证 |

## 剩余问题

| 编号 | 级别 | 位置与依据 | 影响 | 状态 |
| --- | --- | --- | --- | --- |
| P4-BE-006 | P1 | `JdbcApplicationRepository` 的管理历史模型权限写路径仍保留旧 `virtual_model`/`route_candidate` 依赖；草稿/发布服务仍有装配依赖 | 需迁移管理写路径、全局发布协调和观测历史查询后再删除剩余旧服务与表；不影响本次已验证的应用映射运行时鉴权 | 进行中 |

## 验证

- `npm run typecheck`：通过。
- `npm run build`：通过（仅有既有大 chunk 警告）。
- `npm run test -- --run tests/runtimeAccess.test.ts tests/deprecatedRoutes.test.ts`：通过，14/14。
- `npx eslint mocks/adminMockPlugin.ts mocks/runtimeAccessMock.ts tests/runtimeAccess.test.ts tests/deprecatedRoutes.test.ts`：通过；全量 lint 仍有 5 个既有错误（ApplicationDetailPage 3 项、developerPage.test 2 项）。
- `mvn -pl light-ai-storage-jdbc -am -Dtest=DefaultSchemaMigratorTest,SchemaGuardTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，10/10。
- `mvn -pl light-ai-admin -am -Dtest=ApplicationKeyServiceTest,LightAiAdminAutoConfigurationTest,ApplicationRuntimeSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，10/10。
- `mvn -pl light-ai-admin -am -Dtest=LightAiAdminAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，6/6。
- `mvn -pl light-ai-admin -am -Dtest=ChannelModelCatalogServiceTest,ApplicationMappingServiceTest,ApplicationRuntimeSnapshotTest,ApplicationKeyServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，7/7。
- `mvn -pl light-ai-admin -am -Dtest=ApplicationKeyServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，4/4。`mvn -pl light-ai-runtime -am -Dtest=AccessTokenPortTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，1/1。`mvn -pl light-ai-server -am -Dtest=V1ChatRequestParsingTest,V1ErrorContractTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过，7/7。`mvn -DskipTests compile`：通过，全仓 14 模块。
- 实链路使用隔离 H2、Redis `127.0.0.1:6379` 命名空间和本地 OpenAI 协议桩（端口 19090），通过 `http://127.0.0.1:18081` 验证；未执行真实供应商调用、PostgreSQL/MySQL 实例上的全新库迁移和剩余旧管理服务删除后的回归。

## Commit / 远程状态

- `d70cafd` `fix(fullstack): P4 下线旧管理页面入口`
- `bc3d24d` `test(fullstack): P4 覆盖旧路由重定向`
- `c80bcf1` `docs(fullstack): P4 记录旧模块下线审查`
- `fec5405` `fix(fullstack): P4 下线旧访问凭证运行面`
- `5ecb928` `fix(fullstack): P4 解耦应用映射运行时旧表`
- `839d651` `fix(fullstack): P4 删除旧访问凭证前端入口`
- `689e076` `fix(fullstack): P4 下线旧管理控制器装配`
- `56068fd` `fix(fullstack): P4 移除旧模型目录查询`
- `8af31b3` `docs(fullstack): P4 记录模型目录解耦复验`
- `9f06b08` `docs(fullstack): claim P4-BE-001 runtime migration`
- `2442ac3` `fix(fullstack): P4-BE-001 migrate runtime permission reads`
- `987de9c` `test(fullstack): P4-BE-001 runtime auth regressions`
- 文档提交后推送到 `origin/fix/fullstack-integration-P4-root`；未直接合并 `dev`，等待后续运行链路迁移和独立评审。

## 合并建议

完成 P4-BE-006 的管理历史写路径、发布协调和观测查询迁移，并通过 PostgreSQL/MySQL/全新库回归后，再提交 P4 顶层完成复验。
