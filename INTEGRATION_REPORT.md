# P5-A 接入链路联调报告

## 范围与结论

- 任务包：P5-A 启动与应用接入链路联调
- 负责人：代码审查与修复模型/root
- 分支：`fix/fullstack-integration-P5-root-claim`
- 基线：领取提交 `1298519 docs(fullstack): claim P5-A 接入链路联调`
- 验证环境：H2 内存数据库、Redis `127.0.0.1:6379`、管理端与 V1 服务端口 `18082`、本地假上游端口 `19090`
- 当前结论：范围内可执行检查通过；真实供应商请求未执行，任务状态保留“待复验”。

## 初次发现与修复

| 编号 | 级别 | 问题与依据 | 根因 | 修复与验证 |
| --- | --- | --- | --- | --- |
| P5-A-01 | P1 | 应用保存手工模型映射后，`GET /v1/models` 返回空列表，调用无法找到模型。 | Runtime 只读取全局发布快照；应用映射仓储使用内连接过滤掉 `virtual_model_id` 为空的手工目标。 | 增加按业务 Principal 读取激活应用映射的快照适配；映射仓储改为支持手工目标；H2 快照测试和隔离服务联调返回 `assistant`。 |
| P5-A-02 | P1 | 新建应用未设置金额预算时，首次聊天在额度预占阶段返回 `CONFIG_DATA_UNAVAILABLE`/503。 | 额度策略币种可为空，但预算预占表币种字段非空，预占直接写入空值触发完整性约束。 | 预占快照优先使用策略币种，否则使用价格估算币种；新增无预算额度回归测试；隔离服务聊天调用成功。 |

## 实际链路证据

按以下顺序执行并重新读取结果：

1. `POST /admin/applications` 创建应用并生成默认额度策略。
2. `POST /admin/applications/{id}/keys` 签发应用密钥；明文只在创建响应中使用，报告不记录密钥。
3. `POST /admin/channels` 创建 OpenAI 兼容渠道，配置为本地假上游。
4. `POST /admin/channels/{id}/credentials` 写入受保护渠道凭证。
5. `PUT /admin/applications/{id}/mappings` 保存 `assistant -> qwen-max` 手工映射。
6. `GET /v1/models` 返回 1 个应用模型 `assistant`。
7. `POST /v1/chat/completions` 返回 200，假上游响应内容为 `P5 upstream stub ok`，回显 `provider_model=qwen-max`、实际 Token 用量和 USD 成本快照。

真实外部供应商未调用；自动审查阻止了向外部地址发送凭证和聊天内容，本次仅使用本机假上游验证协议和跨层读写。

## 测试与构建

- `mvn -q -pl light-ai-storage-jdbc -am -Dtest=JdbcApplicationQuotaPortTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过。
- `mvn -q -pl light-ai-admin,light-ai-runtime,light-ai-storage-jdbc -am -Dtest=JdbcApplicationQuotaPortTest,JdbcConfigSnapshotPortAdapterTest,ApplicationKeyServiceTest,ApplicationMappingServiceTest,ApplicationRuntimeSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`：通过。
- `mvn -q -pl light-ai-server -am compile`：通过。
- 隔离服务启动、H2/Redis 联调和本地假上游聊天调用：通过。

未验证项：真实供应商同步、真实供应商流式响应、生产数据库和多实例共享容量故障恢复。

## 文档与提交

- `PROJECT_DOCUMENT.md` 已登记 P5-A 领取记录和当前交付证据，状态为“待复验”。
- 本报告为 P5-A 联调证据记录。
- 代码提交：`e85b11e fix(fullstack): P5-A 接通应用映射运行时路由`。
- 测试提交：`9e3b984 test(fullstack): P5-A 覆盖应用映射与调用链`。
- 文档提交：随本报告提交交付，当前提交链包含 `d007781 docs(fullstack): P5-A 记录接入链路联调`。

## 后续条件

在具备授权的真实供应商测试环境后，补做至少一次真实同步或流式调用、失败切换和共享 Redis 故障复验；完成后将 P5-A 状态更新为“已验证”。

