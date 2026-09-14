# 联调任务状态

| 任务包 | 负责人 | 领取时间 | 前端范围 | 后端范围 | 数据库/基础设施范围 | 状态 | 更新时间 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P0：恢复可用性与菜单收口 | 代码审查与修复模型/root | 2026-09-15 | 应用创建、菜单 | 应用创建契约 | V9 迁移 | 已验证 | 2026-09-15 |
| P1：应用模型映射数据与后端 | 代码审查与修复模型/root | 2026-09-15 | — | 应用映射、目录、运行时路由 | V10 迁移 | 领取中 | 2026-09-15 |
| P2：应用工作台与渠道页面 | 代码审查与修复模型/root | 2026-09-15 | 应用详情、渠道详情 | 页面契约 | — | 领取中 | 2026-09-15 |
| P5-A：启动与应用接入链路联调 | 代码审查与修复模型/root | 2026-09-15 | — | 应用密钥、映射、V1 调用 | H2/Redis | 待复验 | 2026-09-15 |
| P5-B：流式调用与终态结算联调 | 代码审查与修复模型/root | 2026-09-15 | V1 流式响应验证 | ChatPipeline 流式/取消/失败终态 | Trace、Attempt、额度与用量账本 | 待复验 | 2026-09-15 |
| P4：删除旧模块 | 代码审查与修复模型/root | 2026-09-15 | 旧菜单/路由与页面清理 | 旧模块服务、控制器、装配与权限清理 | 旧表迁移清理及审计/调用/成本快照回归 | 进行中 | 2026-09-15 |
> 本表记录联调任务领取与状态；同一任务包只允许一个负责人继续修改，状态更新需附验证证据。


## P4 当前进展（2026-09-15）

- 领取记录已推送到 `origin/claim/fullstack-integration-P4-root`，修复分支为 `fix/fullstack-integration-P4-root`。
- P4-A 已完成旧管理入口的前端下线：路由重定向、顶部入口清理、渠道页旧草稿/模型跳转清理。
- 验证：`light-ai-admin-ui` 的 `npm run typecheck`、`npm run build` 通过；`npm run test -- --run tests/runtimeAccess.test.ts tests/deprecatedRoutes.test.ts` 通过（14/14）；目标文件 lint 通过。全量 lint 仍有基线错误：`ApplicationDetailPage.vue` 3 项、`developerPage.test.ts` 2 项。
- P4-BE-002 已完成旧访问凭证运行面清理：移除管理控制器/服务、客户端 DTO、JDBC 仓储和自动装配，只保留应用密钥鉴权；`ApiCatalog` 删除旧接口，访问凭证表由 V12 迁移删除。
- P4-BE-003 已完成应用映射运行时解耦：激活映射快照和运行映射不再回查 `virtual_model`，应用目标以保存的模型名称为准；H2 测试删除 `virtual_model`、`upstream_model` 后仍可读取映射快照。
- P4-BE-004 已完成旧管理 HTTP 控制器下线：运行参数、上游模型、虚拟模型/候选路由、治理策略、草稿/发布控制器不再由自动配置暴露；内部发布服务和实例接口暂保留，等待运行时迁移。自动配置测试 6/6 通过。
- P4-BE-005 已完成渠道目录旧表解耦：无实时 ProviderAdapter 时只返回 `manual_input_allowed`，不再查询 `upstream_model`；映射仓储删除旧目录查询，H2 删除旧表后的批量映射测试通过。目录、映射、快照和应用密钥回归 7/7 通过。
- 验证：`mvn -pl light-ai-storage-jdbc -am -Dtest=DefaultSchemaMigratorTest,SchemaGuardTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过（10/10）；`mvn -pl light-ai-admin -am -Dtest=ApplicationKeyServiceTest,LightAiAdminAutoConfigurationTest,ApplicationRuntimeSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过（10/10）；全仓 `mvn -DskipTests compile` 通过。
- 待处理：运行时及存储层仍直接读取 `virtual_model`、`route_candidate`、`runtime_config` 和 `config_snapshot`，草稿/发布服务仍有装配依赖。需继续完成运行链路迁移，再删除剩余旧服务和表；当前不宣称 P4 完成。
