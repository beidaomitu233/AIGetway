# 轻享 AI 全栈审查修复最终报告

日期：2026-09-08

分支：`fix/fullstack-review-optimization`

当前代码基线：`e4bb44a`

## 1. 结论

本轮完成 CR-001～CR-019 的代码、契约、存储、测试和 Git 复核。CR-001～CR-014、CR-017～CR-019 已完成；CR-015、CR-016 已完成当前仓库和本机可验证部分，保留外部环境门禁。

当前基线具备可执行 Standalone Server、统一同步/异步/流式客户端、版本化数据库迁移、Redis 共享容量/熔断/FIFO、配置草稿发布、权限与敏感信息保护、观测与留存清理。当前修复在 Java 17 完整验证通过，Java 21 上一基线完整验证通过；Java 17/21 × Spring Boot 3.3.8/3.4.13/3.5.5 的 14 模块构建矩阵均通过。

暂不建议签署生产放行。当前机器没有 Docker、PostgreSQL、MySQL 5.7 或 MySQL 8.0，无法完成真实数据库升级与回滚矩阵；WebFlux 管理 API/Admin UI、物理双 Server、200 条业务 HTTP 流、10 分钟稳态及真实 DB/Redis 故障演练也未完成。

## 2. CR-001～CR-019 状态

| 编号 | 状态 | 当前结论与证据 |
| --- | --- | --- |
| CR-001 | 已完成 | Server 主类、Boot repackage、生产装配和可执行 JAR 已交付；当前 JAR 健康、UI 与鉴权边界冒烟通过。 |
| CR-002 | 已完成 | Embedded 使用活动快照端口，移除硬编码模型定义。 |
| CR-003 | 已完成 | RuntimeConfig、Access Credential、开发接入和留存清理进入默认管理装配。 |
| CR-004 | 已完成 | 新增 `light-ai-storage-redis`：Lua 原子校验 Alias、Provider Model、Credential 的 RPM/TPM/并发；共享熔断与全局 FIFO；结算释放幂等、租约回收和 fail-closed。11 项真实 Redis 集成测试以两个独立客户端连接验证竞争、取消、恢复和回收。 |
| CR-005 | 已完成 | 业务 Token 鉴权执行 IPv4/IPv6 CIDR 白名单并使用实际来源 IP。 |
| CR-006 | 已完成 | `applicationScope` 与 `aliasScope` 分离；开发人员空 Alias 范围默认拒绝。 |
| CR-007 | 已完成 | 内部实例使用逐实例口令，认证身份必须与请求头、路径、正文 UUID 一致。 |
| CR-008 | 已完成 | Provider URL 规范化并在连接边界复核 DNS/IP，覆盖回环、私网和链路本地地址。 |
| CR-009 | 已完成 | RuntimeConfig 写入草稿、递增 revision，并进入快照与恢复。 |
| CR-010 | 已完成 | 事务连接生命周期交由 Spring 管理，提交与回滚测试通过。 |
| CR-011 | 已完成 | 管理 SSE 使用 START/DELTA/USAGE/DONE；业务 SSE 使用 JSON chunk 与唯一 `[DONE]`；错误与成功终态互斥。 |
| CR-012 | 已完成 | 流式前端请求复用受保护请求头，携带 CSRF Token 与 request id。 |
| CR-013 | 已完成 | 留存影响使用独立 DTO、嵌套目标/计数和真实估算。 |
| CR-014 | 已完成 | JDBC 删除端口和调度已装配；Trace、Usage、审计和样本分别按留存规则清理。 |
| CR-015 | 部分完成 | MySQL/PostgreSQL V1 迁移、历史表、SHA-256 校验、全局锁、事务、字段/索引守卫已交付；H2 空库、重复迁移、checksum 篡改测试通过。5 项真实数据库用例因环境缺失跳过。 |
| CR-016 | 部分完成 | Java/Boot 构建矩阵已通过；当前修复 Java 17 完整测试与上一基线 Java 21 完整测试通过。Reactive Standalone、宿主端口 Embedded Chat、无 Web 空快照，以及 Reactive + H2 管理存储、快照、凭证和 ChatPipeline 自动接线已验证；WebFlux 管理 API/Admin UI、物理双实例、200 流、预热/稳态及真实故障演练未完成。 |
| CR-017 | 已完成 | 管理请求超时覆盖响应体读取，定时器与取消监听在 `finally` 清理。 |
| CR-018 | 已完成 | `draft_change` 按 R 类物理删除语义移除不存在的 `deleted_at` 条件。 |
| CR-019 | 已完成 | RuntimeConfig 在事务内使用 `WHERE version=?` CAS 更新并检查影响行数。 |

## 3. 本轮新增的关键实现

数据库提交 `f39a521` 将生产 DDL 迁入 MySQL/PostgreSQL 的 `V1__baseline.sql`。`DefaultSchemaMigrator` 维护版本、checksum、迁移锁和事务；`SchemaGuard` 从仅核表名提升为校验迁移历史、全部必需列和索引。迁移重复执行保持幂等，文件被修改时启动拒绝继续。

客户端提交 `8aa2b54` 修复订阅建立前到达的流事件丢失。流 Publisher 先缓存 START/DELTA/USAGE/DONE 或错误，订阅与需求到达后按顺序投递，并保留背压、取消和唯一终态语义。

运行时提交 `4e917dd` 新增 Redis 共享状态模块并显式装配 Standalone。`ChatPipeline` 每个请求固定同一快照，使用 UUID 范围键，按输出上限计算 TPM，容量溢出进入带作用域的 FIFO，取消和超时能清理排队；半开探测在容量预占后执行。内存实现也收紧为完整原子操作，保持 Embedded 单实例语义一致。

Starter 提交 `6a1b1ba` 补齐默认 Embedded ChatPipeline、Servlet JDBC 凭证适配、Reactive/无 Web 运行内核，并将可复用容量与凭证端口从可执行 Server 模块下沉到 Runtime 和 Storage JDBC。提交 `e4bb44a` 将管理 JDBC 仓储与业务服务从 Servlet 条件中拆出，在 Reactive 上下文复用；Servlet 控制器、拦截器和过滤器继续按 Web 栈隔离。

## 4. 数据库与回滚

生产迁移文件：

- `light-ai-storage-jdbc/src/main/resources/db/migration/mysql/V1__baseline.sql`
- `light-ai-storage-jdbc/src/main/resources/db/migration/postgres/V1__baseline.sql`

本轮没有连接生产数据库，没有执行生产数据删除、表删除或逆向 DDL。代码回退可按提交反向回退；回退 `f39a521` 前必须确认目标环境尚未依赖迁移历史表。真实数据库验收需要分别提供 PostgreSQL、MySQL 5.7、MySQL 8.0 连接，执行空库安装、已有数据升级、故障恢复、checksum 保护、回滚和全仓仓储/事务用例。

## 5. 验证结果

| 验证 | 实际结果 |
| --- | --- |
| Java 17：`mvn verify`，启用本地 Redis IT | 14 模块成功；409 项总计，404 项执行、5 个外部数据库用例跳过；0 failure、0 error；Redis 11 项全部执行。 |
| Java 21 上一基线：`mvn verify`，启用本地 Redis IT | 14 模块成功；404 项总计，399 项执行、5 项跳过；0 failure、0 error；当前新增 Reactive JDBC 拆分尚未在 Java 21 重跑。 |
| Java 17/21 × Boot 3.3.8/3.4.13/3.5.5 | 六组 `package` 全部成功，均完成 14 模块编译、测试编译和制品打包。 |
| Reactive 自动装配 | Standalone Client、宿主端口 Embedded 实际 Chat、无 Web/无存储空快照、Reactive + H2 管理 JDBC 存储与运行端口均通过；Servlet 管理控制器不会进入 Reactive 上下文。 |
| 可执行 JAR 当前基线 | `/health/live`、`/health/ready`、`/ui/`、`/ui/index.html` 返回 200；未认证 `/v1/models` 返回 401。测试进程已停止。 |
| 前端 | lint 0 error、37 warning；typecheck 通过；22 个文件 160/160 测试通过；Vite 生产构建通过。 |
| 历史全链路 JAR 冒烟 | H2 空库、配置写入/发布、Access Token、models、同步 Chat、业务 SSE、管理 SSE 通过；本轮核心修改后另执行当前 JAR 启动与边界冒烟。 |

本机有真实 Redis 7.4.2，因此 Redis 用例属于实际服务验证。MySQL/PostgreSQL 用例按环境条件跳过，不能计为通过。进程内管线基准在 Java 21 下为 P50 0.06ms、P95 0.13ms，仅用于回归观察，不作为生产容量结论。

## 6. 文档与任务状态

- `FRONTEND_PLAN.md`：FE-001～FE-054 保持完成，记录当前门禁。
- `BACKEND_PLAN.md`：更新 Redis、迁移、Java/Boot 矩阵及 Reactive JDBC 接线证据；BE-059、BE-060 因 WebFlux 管理端与真实集群/压测缺口保持未勾选。
- `DATABASE_PLAN.md`：记录版本化迁移与 H2 验证；DB-001～DB-030 等待真实数据库逐项验收，保持未勾选。
- `PROJECT_DOCUMENT.md`：更新集群共享状态现状与最终生产门禁。
- `COMMUNICATION.md`：CR-004 改为已完成，CR-015/016 改为部分完成并写入实际证据。

## 7. Git 交付

当前分支新增的分类提交：

- `f39a521` `fix(database): add versioned schema validation`
- `8aa2b54` `fix(client): preserve pre-subscription stream events`
- `4e917dd` `fix(runtime): add shared redis capacity and circuit state`
- `3452567` `docs(fullstack): finalize review validation report`
- `6a1b1ba` `fix(starter): wire embedded runtime across web stacks`
- `50dd229` `docs(fullstack): update reactive validation evidence`
- `e4bb44a` `fix(starter): enable reactive embedded jdbc core`

本报告和计划文档使用独立文档提交。当前分支未推送、未合并到 `dev` 或 `main`，未创建 Tag，未发布制品。

## 8. 剩余生产门禁

1. 为 PostgreSQL、MySQL 5.7、MySQL 8.0 提供隔离数据库，完成 CR-015 的升级、失败恢复、回滚和仓储矩阵。
2. 补齐 WebFlux 管理 API、Admin UI 资源与认证/CSRF 适配，并执行 Spring Boot 3.3/3.4/3.5 Reactive 运行验证；公共 JDBC 存储、快照、凭证和 Embedded ChatPipeline 已完成 Reactive 上下文接线。
3. 在两台物理 Server、共享 Redis 和真实数据库上执行 200 条业务 HTTP 流、2 分钟预热、10 分钟稳态、取消/超时/背压、Redis/DB 断连、实例重启与对账。
4. 全部门禁通过后，从 `dev` 创建 `release/<version>`，完成发布验证，再合并 `main` 与 `dev` 并在 `main` 创建版本 Tag。

当前建议进入代码审查和外部环境验收，暂缓合并 `main` 与生产发布。
