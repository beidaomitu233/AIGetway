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

# P5-C 失败切换与观测一致性联调报告

## 范围与结论

- 任务包：P5-C 失败切换与观测一致性联调
- 负责人：代码审查与修复模型/root
- 分支：`fix/fullstack-integration-P5-C-root-claim`
- 基线：`fix/fullstack-integration-P5-B-root`（已占用 P5-B 的流式取消与终态结算成果）
- 验证环境：`light-ai-server` Standalone 端口 `18084`、H2 内存数据库（MIGRATE）、Redis `127.0.0.1:6379`（命名空间 `p5b2-e2e`）、本地 OpenAI 协议替身 `127.0.0.1:19090`（健康）与 `127.0.0.1:19091`（持续 500 失败）。
- 当前结论：失败切换的 Attempt 类型、Trace 计数、Usage 聚合和 SSE `[DONE]` 终止在真实 HTTP + H2 + Redis 隔离环境通过；真实供应商、生产共享状态和浏览器页面仍需授权环境复验，任务状态为“待复验”。

## 问题与修复

| 编号 | 问题与复现步骤 | 根因与修复 | 验证结果 | 状态 |
| --- | --- | --- | --- | --- |
| P5-C-01 | 上游在发送 `data: [DONE]` 后保持 HTTP 连接，适配器继续等待 EOF，流式请求无法及时收敛。 | 新增 `SseLineParser.readUntilDone`，消费到协议终止帧立即返回并关闭响应体；OpenAI 兼容适配器改用该路径。 | 同一 SSE 连接在约 715ms 内返回 `[DONE]`，curl 正常退出，不读取终止帧之后的数据。 | 已验证 |
| P5-C-02 | 重试、换 Key、fallback 均使用默认序号推导 Attempt 类型，Trace 的恢复计数保持 0，调用观测无法对账。 | `TraceStore` 增加带类型的兼容契约；ChatPipeline 在同步/流式恢复前写入 `RETRY`、`CREDENTIAL_FAILOVER`、`FALLBACK`；JDBC 最终化按 Attempt 类型聚合三类计数。 | H2 回归及端到端 Trace 均得到 `attempt_count=4`、`retry_count=1`、`credential_failover_count=1`、`fallback_count=1`。 | 已验证 |
| P5-C-03 | Provider 异步流在首个业务块提交前失败时，原实现把异常抛到异步回调线程，恢复循环无法接管，Trace 保持 RUNNING。 | 新增 StreamSession 持有同一 Trace 的候选、凭证和恢复预算；首块前错误通过回调释放当前 Attempt 并继续重试、换 Key 或 fallback，提交后仍固定终态。 | 异步回调回归通过；真实 HTTP + H2 + Redis 复验最终切换健康渠道，Trace/Usage 恢复计数与 Attempt 类型一致。 | 已验证 |
| P5-C-04 | Provider 在完成或失败后重复/迟到回调时，原实现可能重复结算、重复通知，或由旧 Attempt 的迟到 `onComplete` 改写恢复中的 Trace。 | StreamAccumulator 为每个 Attempt 增加终态 CAS，并串行化 `onNext`、`onError`、`onComplete` 与取消回调；首个终态之后的回调直接丢弃。 | `duplicateStreamTerminalCallbacksAreIgnoredPerAttempt` 与 `lateCompletionAfterPreCommitFailureCannotCommitOldAttempt` 回归通过：重复终态回调未产生第二次结算或通知，失败 Attempt 的迟到完成也未改写 fallback Trace。 | 已验证 |
| P5-C-05 | Provider 在建立流式 Publisher 或 `subscribe` 阶段直接抛出运行时异常时，原实现未进入恢复，容量和 Attempt 可能遗留为 RUNNING。 | StreamSession 捕获适配器流式订阅阶段的运行时异常，转换为统一 Provider 错误并复用失败 Attempt 清理与 fallback 决策。 | `streamPublisherSetupFailureUsesFallbackAndClosesAttempt` 回归通过：首候选 Publisher 建立失败后第二候选成功，Trace 两个 Attempt 分别为 FAILED/SUCCEEDED，最终仅一次成功终态。 | 已验证 |
| P5-C-06 | HTTP 流超时被当作客户端取消，迟到的 `onComplete` 可能把已超时 Attempt/Trace 误收敛为成功，或保留错误终态。 | V1 超时回调使用 `CancellationSignal.timeout`；StreamAccumulator 在 `onNext`、`onError`、`onComplete` 前统一检查取消/超时，并按提交状态与终止错误收敛 `FAILED`/`STREAM_INTERRUPTED`，容量与应用额度仍走一次性释放。 | `streamTimeoutBeforeCommitFinalizesFailedAttempt` 回归通过：超时后迟到内容和完成回调均被丢弃，Attempt=FAILED、error_code=TOTAL_TIMEOUT、Trace=FAILED，容量只释放一次。 | 已验证 |

## 实际链路证据

1. 通过真实管理 API 创建失败渠道（OPENAI）和健康渠道（DEEPSEEK）、受保护 Key、企业应用、应用密钥与 `p5b-chat` 映射；校验并发布配置。
2. 使用应用密钥调用 `POST /v1/chat/completions`（`stream=true`），请求先命中失败渠道并依次执行重试、换 Key、优先级 fallback，最终切换健康渠道；SSE 返回角色块、内容块、usage、finish 和 `data: [DONE]`。
3. 重新读取 `/admin/calls`、`/admin/traces` 和 `/admin/usage/summary`：request_id `e4db7dcb-f18c-4350-9cb0-d1c96d00a8a1`，最终渠道为 DEEPSEEK，状态 `SUCCEEDED`，`requested_stream=true`，Attempt 4 条，恢复计数 1/1/1，实际 Token 6/2/8，Usage 来源 `ACTUAL`。Trace 明细的 Attempt 类型依次为 `INITIAL`、`RETRY`、`CREDENTIAL_FAILOVER`、`FALLBACK`，首个业务块提交后未再切换。
4. `/admin/usage/summary?requested_stream=true` 返回 `request_count=1`、`stream_count=1`、`attempt_count=4`，与调用和 Trace 数据一致。
5. 测试数据为虚构值；应用密钥原文仅在测试进程变量中使用，未写入代码、日志或报告。

## 复验补充（2026-09-15）

- 重启当前分支构建的 `light-ai-server`（端口 `18084`，H2 MIGRATE，Redis 命名空间 `p5c-e2e-rerun`），通过 V2 管理 API 创建渠道、受保护凭证、企业应用、应用密钥和 `p5c-chat` 手工映射；校验 `PASSED`，发布记录收敛为 `SUCCEEDED`。
- 应用密钥调用 `/v1/models` 返回 `p5c-chat`；同步 `/v1/chat/completions` 返回 `LIGHT_AI_SYNC_OK` 和实际 4/5/9 Token。
- 流式调用返回角色块、内容块、finish 和唯一 `[DONE]`，客户端读取到终止帧耗时 188ms；Trace `83294358-449e-4c5b-8320-d58cc6364e39` 为 `SUCCEEDED`、`requested_stream=true`、`response_committed=true`、`usage_source=ACTUAL`、6/2/8 Token；`/admin/usage/summary?requested_stream=true` 返回 `request_count=1`、`stream_count=1`、`attempt_count=1`。

## 测试与构建

```text
mvn -B -pl light-ai-provider-common,light-ai-server -am -Dtest=OpenAiCompatibleAdapterTest,JdbcTraceStoreTest,V1ControllerStreamTest,ChatPipelineTest,ChatPipelineRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
BUILD SUCCESS；OpenAiCompatibleAdapterTest 6/6、JdbcTraceStoreTest 6/6、V1ControllerStreamTest 1/1、ChatPipelineTest 18/18、ChatPipelineRecoveryTest 12/12

mvn -B -pl light-ai-server -am -DskipTests package
BUILD SUCCESS；Spring Boot fat jar repackage succeeded
```

## 异步失败恢复补充（2026-09-15）

- 复现问题：Provider 异步流在首个业务块提交前返回 500 时，旧实现将异常抛到异步回调线程，恢复循环无法接管，Trace 保持 RUNNING。
- 修复结果：ChatPipeline 新增 StreamSession，首块前错误通过同一 Trace 的回调释放 Attempt 并继续 RETRY、CREDENTIAL_FAILOVER 或 FALLBACK；提交后仍固定 STREAM_INTERRUPTED 终态。
- 异步回归测试通过：ChatPipelineRecoveryTest 新增异步 onError 场景，首个候选失败后第二候选成功，同一 Trace 只产生一次最终回调。
- 终态幂等回归通过：每个 Attempt 的重复 `onComplete`、迟到 `onError`、失败后迟到 `onComplete` 和取消竞争均由一次性门控收敛，容量结算与监听器终态通知不重复。
- 流式订阅建立异常回归通过：适配器在 `streamChat` 阶段抛出运行时异常时，失败 Attempt 已释放并按既有预算切换候选。
- 最新真实 HTTP + H2 + Redis 复验（端口 18085，Redis 命名空间 p5c-failover-rerun4）：request/trace 38898ec2-37de-41a3-b6cf-79f4f474e393 在 659ms 内 SUCCEEDED，最终渠道 DEEPSEEK，Attempt 类型 INITIAL、RETRY、CREDENTIAL_FAILOVER、FALLBACK，计数 4/1/1/1，实际 Token 6/2/8；Usage summary 与调用记录一致，客户端收到唯一 [DONE] 后结束。
- 该修复仍需真实供应商、生产共享状态和浏览器页面复验，任务状态保持“待复验”。

## 未验证项与后续条件

真实供应商 SSE 终止行为、生产数据库、跨实例共享容量和浏览器页面未执行；需要在授权环境复验后，才可将 P5-C 从“待复验”更新为“已验证”。
