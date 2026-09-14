# P5-A 接入链路联调报告

## 范围与结论

- 任务包：P5-A 启动与应用接入链路联调
- 负责人：代码审查与修复模型/root
- 分支：`fix/fullstack-integration-P5-root-claim`
- 基线：领取提交 `1298519 docs(fullstack): claim P5-A 接入链路联调`
- 验证环境：`light-ai-server` Standalone 端口 `18081`、H2 内存数据库（MIGRATE）、Redis `127.0.0.1:6379`（命名空间 `p5-e2e`）、本地 OpenAI 协议假上游 `127.0.0.1:19090/v1`
- 当前结论：本地替身环境的应用接入主链路通过；真实供应商和浏览器页面未执行，任务状态保留“待复验”。

## 问题与修复

| 编号 | 问题与复现步骤 | 根因与修复 | 验证结果 | 状态 |
| --- | --- | --- | --- | --- |
| P5-A-01 | 应用保存手工模型映射后，`GET /v1/models` 为空，聊天请求无法路由。 | Runtime 只读取全局发布快照；应用映射仓储过滤掉 `virtual_model_id` 为空的手工目标。增加按业务 Principal 读取应用 active mapping 的快照适配，允许手工上游模型名。 | H2 快照测试通过；隔离服务发布后 `/v1/models` 返回 `p5-chat`，聊天经渠道路由到 `gpt-4o-mini`。 | 已验证 |
| P5-A-02 | 未设置金额预算的应用在首次聊天额度预占阶段可能失败。 | 额度策略币种为空时预占表仍要求非空币种。预占快照优先使用策略币种，否则使用本次价格估算币种；金额仍按策略为零。 | `JdbcApplicationQuotaPortTest` 新增无预算币种回归通过。 | 已验证 |
| P5-A-03 | 使用旧 plain jar 启动时，非法管理请求客户端超时，错误日志出现 Logback `ThrowableProxy` 类加载失败。 | 联调进程误用了旧构建产物；重新执行 Maven package 并从当前源码 fat jar 启动。 | 同一非法请求稳定返回 400 `FIELD_VALIDATION_FAILED`，发布和正常请求均返回完整 JSON。 | 已验证 |

## 实际链路证据

1. `GET /health/live`、`GET /health/ready` 返回 200/UP。
2. 通过真实管理 API 创建渠道、受保护凭证、上游模型、虚拟模型和路由候选，草稿 revision 5；创建企业应用、应用密钥和模型授权，并保存应用级 `p5-chat -> gpt-4o-mini` 映射。
3. `POST /admin/config/validate` 返回 `PASSED`；确认 `CONNECTION_CHECK_STALE` 警告后发布，发布记录从 `PREPARING` 收敛为 `SUCCEEDED`，在线实例加载 active snapshot 1。
4. 使用应用密钥调用 `GET /v1/models`，返回一个 `p5-chat` 模型。
5. 使用同一密钥调用 `POST /v1/chat/completions`（`stream=false`），假上游返回 `P5 upstream stub ok`；响应回显实际输入 4、输出 5、总计 9 tokens。
6. 重新读取管理端 `/admin/calls`、`/admin/traces` 和 `/admin/usage/summary`：调用与 Trace 各 1 条、状态 `SUCCEEDED`、`usage_source=ACTUAL`、4/5/9 tokens、成功率 100%。
7. 缺少 Bearer 凭证返回 401 `ACCESS_TOKEN_INVALID`；未授权模型返回 403 `ACCESS_DENIED`；非法管理请求返回 400，均包含 request id 且不回显输入内容。

测试数据为虚构值；应用密钥原文仅在测试进程变量中使用，不写入仓库、日志或报告。

## 测试与构建

```text
mvn -B -pl light-ai-admin,light-ai-storage-jdbc -am -Dtest=ApplicationRuntimeSnapshotTest,JdbcApplicationQuotaPortTest -Dsurefire.failIfNoSpecifiedTests=false test
BUILD SUCCESS；ApplicationRuntimeSnapshotTest 1/1，JdbcApplicationQuotaPortTest 7/7

mvn -B -pl light-ai-server -am -DskipTests package
BUILD SUCCESS；Spring Boot fat jar repackage succeeded
```

## 未验证项与资料缺口

真实供应商同步/流式调用、生产数据库、多实例共享容量故障恢复和浏览器管理页面未在当前环境执行。仓库未提供 `FRONTEND_PLAN.md`、`BACKEND_PLAN.md`、`DATABASE_PLAN.md`、`COMMUNICATION.md` 或 `REVIEW_REPORT.md`，本报告仅记录缺口，不从缺失资料推断需求。

## 提交

- `e85b11e fix(fullstack): P5-A 接通应用映射运行时路由`
- `9e3b984 test(fullstack): P5-A 覆盖应用映射与调用链`
- `d007781 docs(fullstack): P5-A 记录接入链路联调`
- `5ebd491 docs(fullstack): P5-A 补充交付提交信息`
- PR：[#7](https://github.com/beidaomitu233/AIGetway/pull/7)，目标分支 `dev`，当前 OPEN，待真实环境复验后合并。


# P5-B 流式调用与终态结算联调报告

## 范围与结论

- 任务包：P5-B 流式调用与终态结算联调
- 负责人：代码审查与修复模型/root
- 分支：`fix/fullstack-integration-P5-B-root`
- 基线：`2c0c02e merge: include P5-A baseline for P5-B`（基于最新 P5-A 远程成果）
- 验证环境：`light-ai-server` Standalone 端口 `18083`、H2 内存数据库（MIGRATE）、Redis `127.0.0.1:6379`（命名空间 `p5b-e2e4`）、本地 OpenAI 协议假上游 `127.0.0.1:19190` 和延迟取消假上游 `127.0.0.1:19191`。
- 当前结论：正常流式和客户端断开终态已在隔离环境通过；真实供应商和生产共享状态未执行，任务状态为“待复验”。

## 问题与修复

| 编号 | 问题与依据 | 根因与修复 | 验证结果 | 状态 |
| --- | --- | --- | --- | --- |
| P5-B-01 | 流式请求实际返回 SSE，但 Trace 的 `requested_stream` 固定为 `false`，无法准确区分同步/流式请求。 | `TraceStore.create` 的 JDBC 实现将字段硬编码为 `false`；增加带 `requestedStream` 的兼容创建契约，ChatPipeline 按请求真实值传递并由 JDBC 持久化。 | `JdbcTraceStoreTest.persistsRequestedStreamFlag` 通过；Trace `f677d32f-0b2b-4014-bc22-9a9c0cf38c7f` 查询到 `requested_stream=true`。 | 已验证 |
| P5-B-02 | 客户端提前关闭 SSE 后，服务端仍阻塞等待上游并可能最终记为成功；延迟假上游复现耗时约 5 秒。 | V1 SSE 改为异步执行并增加 500ms 保活探测；CancellationSignal 终止监听联动上游订阅，OpenAI 兼容 Adapter 取消时关闭响应体和工作任务。 | Trace `7818c368-1455-463a-b073-2de13081768a` 在约 1007ms 内收敛为 `CANCELLED`，Attempt 为 `CANCELLED`、`CLIENT_CANCELLED`，未提交响应且无用量结算。 | 已验证 |

## 实际链路证据

1. 通过管理 API 创建渠道、受保护 Key、企业应用、应用密钥和 `assistant -> qwen-max` 应用映射。
2. 正常流式调用返回 HTTP 200，SSE 包含角色块、内容块、finish 和 `[DONE]`；Trace `f677d32f-0b2b-4014-bc22-9a9c0cf38c7f` 为 `SUCCEEDED`、`requested_stream=true`、`response_committed=true`、`usage_source=ACTUAL`、输入/输出/总 Token 为 4/2/6。
3. 使用 HTTP 客户端读取 SSE 首字节后主动关闭连接；保活写入检测到断开，Trace `7818c368-1455-463a-b073-2de13081768a` 在约 1007ms 内为 `CANCELLED`，Attempt 记录 `CLIENT_CANCELLED`，`response_committed=false`。
4. 测试数据均为虚构值；应用密钥仅在测试进程变量中使用，未写入代码、日志或报告。

## 测试与构建

```text
mvn -q -pl light-ai-server,light-ai-provider-common,light-ai-runtime -am -Dtest=V1ControllerStreamTest,OpenAiCompatibleAdapterTest,JdbcTraceStoreTest,ChatPipelineTest,ChatPipelineRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
BUILD SUCCESS；相关测试报告：V1ControllerStreamTest 1/1、OpenAiCompatibleAdapterTest 6/6、JdbcTraceStoreTest 5/5、ChatPipelineTest 18/18、ChatPipelineRecoveryTest 7/7

mvn -q -pl light-ai-provider-common,light-ai-provider-openai,light-ai-runtime,light-ai-server -am -DskipTests install
BUILD SUCCESS；以当前源码启动隔离服务并完成上述 SSE 链路复验
```

## 未验证项与后续条件

真实供应商流式协议、生产数据库、共享容量故障恢复、多实例取消传播和浏览器页面未执行；需在授权环境复验后再将 P5-B 由“待复验”更新为“已验证”。
