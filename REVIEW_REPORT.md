# 应用接入域代码审查与修复报告

## 1. 审查范围与基线

- 审查分支：`fix-review-rvp20-rvagent-0912`
- 基线提交：`bbc1340`，审查前已 `git fetch origin --prune`，当时 `origin/dev` 与本地基线一致。
- 范围：FE-201～FE-205、BE-201～BE-205 与 DB-P20 已交付部分，重点覆盖应用列表、创建/编辑、模型授权、额度与密钥页面/API 契约；不接管 FE/BE/DB-P20 的主任务占用。
- 依据：`PROJECT_DOCUMENT.md`、`FRONTEND_PLAN.md`、`BACKEND_PLAN.md`、`DATABASE_PLAN.md`、`COMMUNICATION.md`、`TASK_STATUS.md` 及当前代码、测试和迁移。
- 工作区已有并行审查改动（配置校验兼容、应用列表 SQL 排序限定）已保留，未覆盖或回退。

## 2. 结论与问题统计

当前结论：**不通过（代码层已确认的 P0/P1 已清零；H2+Redis 联调已通过，但真实 PostgreSQL/MySQL/Provider、企业身份和完整生产门禁仍未完成）**。

| 类别 | 数量 | 编号 |
| --- | ---: | --- |
| 初次发现 P1 | 1 | RV-P20-001 |
| 初次发现 P2 | 4 | RV-P20-002～RV-P20-005 |
| 已修复 | 5 | RV-P20-001～RV-P20-005 |
| 并行联调已修复 | 2 | FS-RV-P20-001、FS-RV-P20-002 |
| 当前确认未解决 P0/P1 | 0 | — |
| 待验证/待确认 | 3 类 | 真实 PostgreSQL/MySQL/Redis/Provider 链路；密钥列表 N+1；`ip_allowlist` 域名解析策略 |

## 3. 问题记录与修复证据

| 编号 | 级别 | 问题与依据 | 涉及文件/接口/表 | 根因与修复 | 验证结果 | 负责人 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RV-P20-001 | P1 | 应用创建/编辑授权候选仍读取配置视图 `/virtual-models`，可能展示未发布或不可路由模型，违反 BE-P20-003 的 model-options 口径。 | `applications.ts`、`ApplicationFormPage.vue`、`ApplicationDetailPage.vue`；`GET /admin/applications/{id}/model-options`、创建候选端点 | 切换到 `/applications/model-options` 与 `/{id}/model-options`，消费 `virtual_model_id/code/max_output_tokens/allow_stream/snapshot_no`，保留加载失败与空态。 | 应用页面测试断言不再访问 `/admin/virtual-models`；前端 248 项全量测试、typecheck、build 通过。 | 代码审查与修复模型/rvagent-0912 | 已验证 |
| RV-P20-002 | P2 | 列表 24 小时摘要字段由 Jackson `SNAKE_CASE` 对数字后缀生成 `requests24h/success_rate24h`，与计划和前端契约的 `requests_24h/success_rate_24h` 不一致。 | `ApplicationListItem.java`、`ApplicationApiContractTest`；`GET /admin/applications` | 对两个字段显式 `@JsonProperty`，补充 JSON 合约断言；前端同步类型并展示调用次数/成功率。 | 后端合约断言已补；前端页面断言 `42 次`、`87.5%`；Maven 在本环境不可用，未重复运行后端测试。 | 代码审查与修复模型/rvagent-0912 | 已验证（前端）；后端待环境复验 |
| RV-P20-003 | P2 | 应用列表缺少计划要求的部门精确筛选和预算状态筛选，URL 与请求未保留该状态。 | `ApplicationListPage.vue`、`applications.ts`；`GET /admin/applications?department=&budget_status=` | 增加筛选字段、URL 状态、选项和请求回归测试；后端筛选已存在于基线实现。 | Vitest 覆盖 URL、编码参数和返回列表；并行 H2+Redis HTTP 链路已验证 `budget_status` 列表 200。 | 代码审查与修复模型/rvagent-0912 | 已验证 |
| RV-P20-004 | P2 | 资源下线后历史授权模型从 model-options 消失，但仍留在提交数组；保存会再次提交失效模型并被服务端拒绝，无法完成授权收口。 | `ApplicationDetailPage.vue`、`applicationP20.test.ts`；模型授权 PUT | 候选加载后识别历史失效授权，提示管理员并从提交集合移除，使保存可以取消这些授权；切换应用时清理状态。 | 新增失效模型收口回归测试，断言 PUT 仅提交可用模型；应用域测试通过。 | 代码审查与修复模型/rvagent-0912 | 已验证 |
| RV-P20-005 | P2 | 后端已返回 `budget_status`、`requests_24h`、`success_rate_24h`，列表接口类型和页面未消费，FE-201 的预算/24 小时运行摘要验收缺口。 | `applications.ts`、`ApplicationListPage.vue`、`applicationValues.ts`、列表夹具 | 补齐字段类型、预算状态标签、64 位请求计数格式化和成功率展示；保留异常数据提示。 | 页面回归断言预算状态、调用次数和成功率；全量前端测试 28 文件/248 项通过，typecheck/build 通过。 | 代码审查与修复模型/rvagent-0912 | 已验证 |
| FS-RV-P20-001 | P1 | V2 发布校验只读取 `channels/channel_credentials`，旧 V1 草稿夹具读取失败并退化为 `REFERENCE_INVALID`。 | 并行改动 `ConfigValidationService.java` 与相关测试 | 增加 V2 优先、V1 键回退和旧关系解析，保留 V2 快照路径。 | 并行线程报告 `ConfigValidationServiceTest` 8 项、`ConfigPublishServiceTest` 16 项通过；详见 `COMMUNICATION.md`。 | 联调模型/当前任务 | 已验证（并行提交） |
| FS-RV-P20-002 | P2 | 预算状态 JOIN quota 后按 `updated_at desc` 排序，未限定列名会触发歧义。 | 并行改动 `JdbcApplicationRepository.java`、`JdbcApplicationRepositoryTest` | 排序表达式统一限定 `application` 表，新增预算筛选+更新时间排序回归。 | 并行线程报告 H2 仓储回归和真实 HTTP 200；详见 `COMMUNICATION.md`。 | 联调模型/当前任务 | 已验证（并行提交） |

## 4. 主要修改

前端应用 API 与页面改用已发布且可路由的 model-options 契约；创建表单在候选快照不可用时保留基本信息录入并显示局部错误；详情授权处理失效历史授权；应用列表支持部门/预算筛选并展示预算状态和 24 小时摘要。后端应用列表 24 小时字段显式固定 JSON 名，相关契约测试同步补齐。数据库迁移未新增或修改。

并行联调另修复配置校验 V1/V2 兼容读取和预算筛选排序 SQL 歧义，修改不属于本次前端提交，已在 `COMMUNICATION.md` 登记。

## 5. 实际验证

已执行：

- `npm run typecheck`：通过。
- `npm test -- --run`：28 个文件、248 项通过；保留既有 Vue Router/RouterLink 测试警告，无失败项。
- `npm run build`：通过。
- 应用域定向测试：`tests/applicationP20.test.ts` 与 `tests/applicationPages.test.ts` 共 43 项通过。
- `git diff --check`：代码改动无空白错误；文档并行改动的 EOF 空行提示已记录，未影响构建。

未执行或未能在本环境复验：

- 本会话默认 PATH 没有 `mvn` 或 `mvnw`，但并行联调线程通过 IntelliJ Maven 运行了发布校验 24 项、应用合约 7 项、仓储回归 1 项及全仓 `verify`（14 模块构建成功）；这些结果作为并行证据引用，不冒称为本会话重复执行。
- 真实 PostgreSQL/MySQL、Provider 首次成功调用、企业身份四角色、浏览器点击级写操作和完整数据库升级/回滚/并发场景；H2+Redis、真实 HTTP 和 Chromium DOM 回放已由并行联调线程完成。

## 6. 文档、提交与后续

- `COMMUNICATION.md` 已登记 FS-RV-P20-001/002 与 RV-P20-001～005；`INTEGRATION_REPORT.md` 已记录 H2+Redis、真实 HTTP 和页面回放证据。本报告补充统一问题统计和复验结论。
- `FRONTEND_PLAN.md`、`BACKEND_PLAN.md`、`DATABASE_PLAN.md` 的主任务勾选未擅自改为完成，因真实环境门禁和包级验收仍阻塞。
- `TASK_STATUS.md` 的 RV-P20 占用保留至本分支提交及远程回读完成。
- 前端修复提交：`9a9454a`（RV-P20-001/003/004/005）；JSON 契约提交：`a320d8f`（RV-P20-002）；并行后端修复：`41e6635`；并行联调文档：`b7265bc`。审查报告提交：`fdbe546`；PR：[#1](https://github.com/beidaomitu233/AIGetway/pull/1)，已推送审查分支并完成远程哈希回读，待独立评审合并。

## 7. 合并建议

PR #1 已提交前端应用域修复供独立评审；合并前需确认并行后端修复已进入目标分支，并补齐 Maven、真实数据库/Redis/Provider 及首调链路验收。完成这些门禁后再将 RV-P20 和 FE/BE/DB-P20 主任务从阻塞状态释放。