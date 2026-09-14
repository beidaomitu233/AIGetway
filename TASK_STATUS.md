# 联调任务状态

| 任务包 | 负责人 | 领取时间 | 前端范围 | 后端范围 | 数据库/基础设施范围 | 状态 | 更新时间 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P0：恢复可用性与菜单收口 | 代码审查与修复模型/root | 2026-09-15 | 应用创建、菜单 | 应用创建契约 | V9 迁移 | 已验证 | 2026-09-15 |
| P1：应用模型映射数据与后端 | 代码审查与修复模型/root | 2026-09-15 | — | 应用映射、目录、运行时路由 | V10 迁移 | 领取中 | 2026-09-15 |
| P2：应用工作台与渠道页面 | 代码审查与修复模型/root | 2026-09-15 | 应用详情、渠道详情 | 页面契约 | — | 领取中 | 2026-09-15 |
| P5-A：启动与应用接入链路联调 | 代码审查与修复模型/root | 2026-09-15 | — | 应用密钥、映射、V1 调用 | H2/Redis | 待复验 | 2026-09-15 |
| P5-B：流式调用与终态结算联调 | 代码审查与修复模型/root | 2026-09-15 | V1 流式响应验证 | ChatPipeline 流式/取消/失败终态 | Trace、Attempt、额度与用量账本 | 待复验 | 2026-09-15 |
| P4：删除旧模块 | 代码审查与修复模型/root | 2026-09-15 | 旧菜单/路由与页面清理 | 旧模块服务、控制器、装配与权限清理 | 旧表迁移清理及审计/调用/成本快照回归 | 进行中 | 2026-09-15 |
| P4-BE-001：运行时权限与配置读取迁移 | 代码审查与修复模型/root | 2026-09-15 | — | 应用密钥模型范围、应用映射运行时读取、默认运行配置 | 运行时读取回归与旧依赖扫描 | 已验证 | 2026-09-15 |
| P4-BE-006-A：密钥模型范围与候选权限读取迁移 | 代码审查与修复模型/root | 2026-09-15 | — | 密钥模型范围、候选权限优先读取应用映射并兼容存量授权 | 旧目录表下线后的密钥与候选目录回归 | 已验证 | 2026-09-15 |
| P4-BE-006-B：应用详情模型读取迁移 | 代码审查与修复模型/root | 2026-09-15 | 应用详情模型摘要 | 详情与模型权限读取不依赖 virtual_model | 删除旧目录表后的详情/API 回归 | 领取中 | 2026-09-15 |
| P4-BE-006-C：管理旧模型授权写路径与发布装配迁移 | — | — | — | 旧模型授权写入、草稿/发布装配和依赖检查 | 管理 API、发布与迁移回归 | 待领取 | — |
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
- P4-BE-001 已验证：运行时鉴权改从应用映射读取，Standalone 不再查询旧 `runtime_config`；H2 删除 `virtual_model`、`route_candidate` 后鉴权仍通过。`/v1/models`、`/v1/chat/completions` 和调用/Trace 查询已在隔离 H2/Redis/本地桩环境通过。`application_model_permission` 仍仅用于读取历史约束。`JdbcApplicationRepository` 的管理历史权限写路径、草稿/发布服务及观测历史快照依赖仍待后续迁移，P4 顶层保持进行中。
- P4-BE-006-A 已验证：应用密钥模型范围和非可信身份候选集合优先读取 ACTIVE 应用映射；旧目录表删除后 `ApplicationKeyServiceTest` 与应用服务回归仍通过，未迁移存量应用保留明确兼容回退。

### P4-BE-001 领取记录（2026-09-15）

- 已由代码审查与修复模型/root 领取，范围限定为应用密钥鉴权与密钥模型范围校验、运行时应用映射读取、默认运行配置读取；不删除尚未迁移的管理草稿/发布历史接口。
- 领取标记与后续修复在本分支提交，其他协作者不得重复领取该子包；完成前保持“领取中/待复验”，不得仅凭代码修改标记为已验证。
### P4-BE-006-A / P4-BE-006-B 领取记录（2026-09-15）

- 已由代码审查与修复模型/root 领取，范围限定为管理 API 的历史模型授权写路径、草稿/发布装配与依赖检查；运行时应用映射链路已由 P4-BE-001 覆盖。
- 先完成静态依赖核对和可执行的最小迁移，再更新状态；不删除审计、调用与成本快照所需历史字段。
