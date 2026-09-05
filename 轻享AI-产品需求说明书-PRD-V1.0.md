# 轻享 AI 产品需求说明书（PRD）

## 文档信息

| 项目 | 内容 |
|---|---|
| 产品名称 | 轻享 AI |
| 产品英文名 | Light AI |
| 文档版本 | V1.0 |
| 产品阶段 | 第一阶段：AI Model Runtime |
| 文档状态 | 开发基线 |
| 目标读者 | 产品、交互、前端、后端、测试、运维 |

## 版本记录

| 版本 | 内容 |
|---|---|
| V1.0 | 定义 AI Model Runtime 的产品范围、页面结构、数据实体、外部系统、功能流程、接口规则和验收标准。 |

# 1. 业务介绍

## 1.1 业务说明

轻享 AI 是面向 Java 与 Spring Boot 应用的 AI 模型运行时。业务应用只需要使用统一模型别名发起对话请求，轻享 AI 负责把请求转换为不同模型服务商的协议，并在实际调用过程中完成凭证选择、模型路由、容量控制、超时、重试、降级、熔断、用量记录、费用计算和链路追踪。

V1.0 聚焦模型调用运行时。产品同时提供 Java SDK、Spring Boot Embedded Mode 和独立部署的 Standalone Server，使单体应用、微服务和共享 AI 基础设施可以采用同一套模型配置与运行治理规则。

## 1.2 业务目标

V1.0 需要解决业务代码直接依赖供应商 SDK、密钥分散在多个应用、模型切换需要修改代码、请求容量缺少统一控制以及调用故障难以定位的问题。完成后，接入方能够以统一请求模型调用多个供应商，通过管理端完成模型与密钥配置，并在调用记录中查看一次请求的完整路由和执行结果。

产品验收以五项结果为准：统一模型调用协议可以稳定运行；模型别名可以路由到一个或多个实际模型；多凭证可以根据容量与健康状态参与调度；异常请求可以按策略重试、降级和熔断；每次调用可以追踪 Token、费用、耗时、错误和各次尝试。

### 1.2.1 产品价值

对业务开发人员，轻享 AI 以 Model Alias 和 UnifiedChatRequest 隔离供应商 SDK、鉴权方式与响应结构。业务代码只依赖稳定的 LightAiClient 或统一 HTTP API；管理员调整实际模型、Credential 或候选优先级并发布后，业务应用无需修改调用代码。SDK Mode、Embedded Mode 和 Standalone Mode 使用相同的请求、响应、错误与追踪语义，使应用可以在保持业务接口稳定的前提下调整部署形态。

对平台管理员，轻享 AI 把 Provider、Credential Pool、Credential、Provider Model、Model Alias、Route Candidate、限流策略和可靠性策略拆分为可独立维护的配置对象。管理员可以在页面中完成接入、检测、引用校验、影响分析和配置发布，所有变更具有草稿、快照、版本冲突与审计记录，避免直接修改运行参数造成不可追踪的线上变化。

对运维人员，轻享 AI 把一次业务请求的路由判定、容量预占、外部 Attempt、Credential Failover、Retry、Fallback、熔断变化、Usage 和 Cost 归入同一 Trace。运维人员可以从概览异常进入具体 Trace 或运行对象，定位失败发生在哪个 Provider、模型、Credential 或恢复动作，并依据统一错误分类判断配置问题、容量问题和外部服务故障。

对应用与组织，轻享 AI 集中执行 RPM、TPM、并发、超时、重试、Fallback 和熔断规则，减少每个项目重复建设模型调用基础能力。Credential 原文不进入业务请求、页面读取、日志、Trace、审计和导出，Standalone Access Token 与 Provider Credential 采用独立安全边界，降低密钥分散和误用风险。

### 1.2.2 产品期望与成功定义

V1.0 完成后，开发人员应能从接入说明页选择已发布 Alias，复制对应 SDK、Spring Boot 或 cURL 示例，完成同步、异步或流式调用，并通过返回的 trace_id 查看调用详情。接入过程不要求开发人员理解供应商密钥、实际模型 ID、路由权重和熔断实现。

管理员应能按照 Provider、Credential Pool、Credential、Provider Model、Model Alias、Route Candidate、治理策略和配置发布的页面顺序完成首次接入。任一字段、引用、能力或运行实例兼容问题应在发布前定位到具体对象与字段；发布失败不得影响当前活动快照和正在运行的请求。

运行时应在每次请求中使用一个固定配置快照，按照已发布的优先级、权重、容量与健康状态选择路径。可恢复错误在总尝试次数和总超时预算内执行 Credential Failover、Retry 或 Fallback；不可恢复错误直接返回；已经向客户端输出流式内容后不得拼接其他模型结果。

观测与安全应达到可核对标准。每次业务调用生成一个 Trace，每次实际外部调用生成一个 Attempt，最终响应、Trace、Usage 聚合和 Cost 可以相互核对。任何读取接口、错误响应、日志、指标、Trace、审计、示例和导出均不得泄露 Provider Credential、Standalone Token、Authorization、数据库密码、Redis 密码或默认消息正文。性能、可用性、兼容性和数据保留的量化标准以第 5 章为准，具体场景以第 6 章为准。

## 1.3 业务范围

V1.0 范围包含 Provider SPI、Provider 管理、Credential Pool、Credential 管理、Provider Model、Model Alias、候选路由、RPM/TPM/并发限制、负载均衡、Timeout、Retry、Fallback、Circuit Breaker、Token/Usage/Cost、Metrics/Trace、Java SDK、Spring Boot Starter、Embedded Admin UI 和 Standalone Server。

V1.0 不包含 Prompt 管理、知识库、MCP、工作流、Agent Runtime、组织级 Control Plane、商业计费和模型训练。管理端只管理模型运行所需配置与运行数据。

V1.0 的协议范围限定为文本 Chat。统一接口支持 system、user 和 assistant 文本消息，支持同步响应和 SSE 流式响应；不接收图片、音频、视频、文件、Tool Calling、Structured Output、Embedding、Realtime 或 Responses API 请求。Provider Adapter 可以保留供应商差异，但只有在 ProviderCapabilities 与 ProviderOptionSpec 中声明并通过受控 provider_options 传入的参数可以进入外部请求。

V1.0 的调用方范围包括同进程 Java 应用、Spring Boot 宿主应用和通过 HTTPS 调用 Standalone Server 的业务应用。管理范围包括模型接入、运行治理、调用观测、配置发布、运行参数、Standalone Access Credential、审计和开发接入；不建设面向最终用户的注册、充值、订阅、余额、套餐、分销和多租户运营后台。

V1.0 内置 OpenAI、Anthropic Claude、Google Gemini 和 DeepSeek 的文本对话 Adapter，并提供 Provider SPI 供部署方扩展其他文本对话服务。外部 Secret、宿主认证与权限、指标和 Trace 系统通过可选 SPI 对接。数据库、Redis 或兼容共享状态存储属于部署依赖，其实现选型由工程设计确定，不作为对外关联系统。

## 1.4 名词术语

| 术语 | 说明 |
|---|---|
| Provider | 对接外部模型服务商的适配器及其连接配置。 |
| Provider Model | Provider 可调用的具体模型，例如某个服务商的具体模型标识。 |
| Credential | 调用 Provider 使用的密钥或外部密钥引用。 |
| Credential Pool | 同一 Provider 下参与调度的一组 Credential。 |
| Model Alias | 业务应用使用的稳定模型名称，用于屏蔽具体 Provider Model。 |
| Route Candidate | Model Alias 下可参与路由的 Provider Model 与 Credential Pool 组合。 |
| RPM | 每分钟允许发起的请求数。 |
| TPM | 每分钟允许消耗的 Token 数。 |
| Attempt | 一次模型调用在某个候选路由上的单次尝试。 |
| Trace | 一次业务模型调用及其全部 Attempt 的完整链路记录。 |
| Fallback | 当前候选不可用或调用失败后切换至后续候选。 |
| Circuit Breaker | 候选持续失败后暂时停止向其发送请求，并在等待期后进行探测。 |
| Embedded Mode | 运行时和管理界面嵌入业务 Spring Boot 应用。 |
| Standalone Mode | 运行时作为独立服务部署，业务应用通过 HTTP 或 SDK 调用。 |

## 1.5 参考产品与功能取舍

New API 的公开文档把上游渠道作为模型服务商、密钥和可用模型的管理单元，并提供渠道检测、优先级、权重、自动停用和多密钥轮询；其访问令牌支持模型范围、有效期和 IP 白名单。这些能力说明统一接口、上游高可用、调用凭证隔离和运行观测已经成为同类产品的基础能力。参考依据为 [New API 渠道管理](https://github.com/QuantumNous/new-api-docs-v1/blob/main/content/docs/en/guide/feature-guide/admin/channel.mdx)、[New API 令牌管理](https://docs.newapi.pro/zh/docs/guide/feature-guide/user/token) 和 [New API 项目介绍](https://github.com/QuantumNous/new-api-docs-v1/blob/main/content/docs/en/guide/wiki/basic-concepts/project-introduction.mdx)。

New API 的渠道设计还验证了同优先级权重分配、多密钥自动跳过不可用项和渠道失败转移的实际需求；其集群部署要求实例共享 Redis，以保持限流等运行状态一致。轻享 AI 将这些需求拆分为 Route Candidate、Credential Pool、Capacity Reservation、Recovery Decision 与 Circuit State，使配置、运行状态和每次恢复动作都能独立追踪。参考依据为 [New API 渠道管理说明](https://docs.newapi.pro/zh/docs/guide/feature-guide/admin/channel) 和 [New API 集群部署说明](https://github.com/QuantumNous/new-api-docs-v1/blob/main/content/docs/en/installation/deployment-methods/cluster-deployment.mdx)。

轻享 AI V1.0 保留与模型运行直接相关的渠道检测、多密钥调度、优先级与权重路由、故障切换、模型访问范围、IP 限制、用量和成本观测，并将上游连接、密钥、实际模型和业务别名拆分为 Provider、Credential、Provider Model 和 Model Alias。拆分后的对象可以独立复用、独立停用和独立追踪，更适合 Java SDK、Embedded Mode 与 Standalone Mode 共用同一运行内核。

V1.0 不引入用户账户余额、销售计费、充值、分销、多租户运营和多媒体生成业务。模型参数覆写只支持统一请求字段、Provider Model 默认值和受控 provider_options，不建设通用表达式规则引擎。

# 2. 产品概述

## 2.1 产品定位

轻享 AI V1.0 位于业务应用与外部模型服务商之间，对上提供稳定、统一的模型调用入口，对下通过 Provider SPI 适配不同供应商。产品以模型运行治理为中心，管理页面用于维护运行配置、观察调用结果和处理运行异常。

## 2.2 产品形态

### 2.2.1 交付形态

Java SDK 为 Java 应用提供统一客户端、Local Runtime 和 Standalone Client。Local Runtime 在调用进程内装配 Provider Adapter、模型、凭证供应器、路由与治理规则，不连接管理数据库和配置发布服务；Standalone Client 通过 HTTPS 调用独立服务，不在客户端执行路由、限流和密钥解析。

Spring Boot Starter 提供条件自动装配。EMBEDDED 模式在宿主 Spring Boot 进程内运行 Runtime Core，并可挂载 Embedded Admin UI，复用宿主数据库、Redis、认证和权限扩展；STANDALONE_CLIENT 模式只装配远程 LightAiClient、连接池和健康检查，不创建本地运行仓库与管理页面。

Standalone Server 以独立服务部署，对业务应用提供统一 HTTP API，对管理员提供管理接口，对运行实例提供配置加载内部接口。多个实例共享配置数据库与 Redis 或兼容原子状态存储，统一执行访问凭证、模型目录、路由、容量、可靠性、Trace 和配置快照。三种形态共享统一领域模型、路由规则、错误码和追踪结构。

### 2.2.2 总体逻辑架构

轻享 AI V1.0 按访问、协议编排、运行治理、Provider 适配、配置管理、观测和基础设施分层。上层只能依赖相邻层公开契约，Provider Adapter 不直接修改路由、限流、Trace 和配置快照，管理页面不直接写运行内存，业务应用不能接触 Provider Credential。

| 架构层 | 核心组件 | 主要职责 | 输入与输出 |
|---|---|---|---|
| 访问层 | Java LightAiClient、Spring Boot Bean、Standalone HTTP API、Embedded Admin UI、Standalone Admin UI | 接收业务调用或管理操作，完成请求解析、调用身份、角色权限、来源 IP 与协议响应处理。 | 输入 UnifiedChatRequest 或管理命令；输出 UnifiedChatResponse、UnifiedChatChunk、页面视图或 UnifiedError。 |
| 统一协议与调用编排层 | Unified Protocol、Request Validator、Call Orchestrator、Cancellation、Error Mapper | 校验消息、模型、参数、上下文和能力；创建 Trace；控制同步、异步、SSE、取消和最终响应。 | 输入已认证请求与配置快照；输出路由请求、StreamEvent、Usage 和统一错误。 |
| Runtime Core | Model Registry、Router、Credential Pool、Capacity Controller、Reliability Engine、Circuit Breaker | 解析 Model Alias，过滤候选，按优先级和权重选路，预占 RPM/TPM/并发，执行 Credential Failover、Retry、Fallback 和熔断。 | 输入调用上下文与候选配置；输出选定 ProviderCallContext、RecoveryDecision 和运行状态记录。 |
| Provider 适配层 | ProviderAdapter Registry、内置 Adapter、Provider SPI、Token Estimator | 声明 Provider 能力，校验 Provider 配置，列举模型，转换同步与流式协议，估算 Token，分类 Provider 错误。 | 输入单次 ProviderCallContext；输出 ProviderChatResponse、ProviderStreamChunk、ProviderUsage 或 ProviderFailure。 |
| 配置与发布层 | 配置仓库、DraftChange、Validator、ConfigSnapshot、Publish Coordinator、Runtime Instance Registry | 保存配置草稿，执行字段、引用、能力和实例兼容校验，生成不可变快照，并协调 Embedded 与 Standalone 实例原子加载。 | 输入管理命令；输出草稿修订、校验问题、发布记录和活动 snapshot_no。 |
| 观测与审计层 | Trace、Attempt、Usage Aggregator、Cost Calculator、Metrics、AuditLog、TraceExporter | 记录调用路径、恢复动作、Token、费用、耗时和错误，生成页面聚合，记录管理操作并向外部系统导出脱敏数据。 | 输入运行事件与管理操作；输出 Trace 详情、UsageAggregate、概览、指标和审计记录。 |
| 基础设施与扩展层 | 数据库、Redis 或兼容状态存储、SecretProvider、AuthContextProvider、TraceExporter | 持久化配置和运行记录，提供集群原子容量状态，并与部署方密钥、认证及观测系统连接。 | 输入存储或扩展请求；输出一致性状态、受控 Secret handle、用户上下文和导出结果。 |

业务调用从访问层进入后，先完成身份与协议校验，再创建 Trace 并固定当前 ConfigSnapshot。Runtime Core 根据 Alias、能力、容量与熔断状态生成路由判定，选定 Credential 后由 Provider Adapter 执行一次外部调用。每个真实外部请求对应一个 Attempt；失败后由 Reliability Engine 生成 RecoveryDecision，决定重试当前路径、切换 Credential、切换候选或结束。最终响应与 Trace 使用同一执行结果，Usage 和 Cost 在 Trace 最终化事务中产生聚合事件。

管理操作从 Admin UI 或管理接口进入后写入配置草稿，不直接改变业务调用。发布服务针对固定 draft_revision 执行完整校验并生成 ConfigSnapshot；Embedded 与 Standalone 实例先构建待激活内存配置，全部目标实例准备成功后活动快照原子切换。新 Trace 使用新快照，已经运行的 Trace 保持创建时快照，避免一次请求跨版本读取配置。

### 2.2.3 部署边界与模式差异

| 对比项 | SDK Local Runtime | Spring Boot Embedded | Standalone Server |
|---|---|---|---|
| 运行位置 | 业务 Java 进程内 | 宿主 Spring Boot 进程内 | 独立服务进程或集群 |
| 业务入口 | LightAiClient 进程内方法 | 注入的 LightAiClient；可选宿主业务接口 | Java Client 或 HTTPS `/v1/*` |
| 管理页面 | 无 | 可选 Embedded Admin UI | Standalone Admin UI |
| 配置来源 | LocalRuntimeDefinition | 数据库草稿与已发布 ConfigSnapshot | 数据库草稿与已发布 ConfigSnapshot |
| 配置生效 | 客户端构建时一次校验并装配 | 参加两阶段发布并原子切换 | 参加两阶段发布并在集群中收敛 |
| 容量与熔断状态 | 进程内 | 单实例可进程内；集群需共享状态 | Redis 或兼容共享原子状态存储 |
| 调用身份 | 宿主 application 与安全上下文 | 宿主 application 与 AuthContextProvider | Standalone Access Credential 与来源 IP |
| Provider Credential | Secret supplier 或 SecretProvider | 受保护凭证存储与 SecretProvider | 受保护凭证存储与 SecretProvider |
| Trace 与 Usage | 本地实现或 TraceExporter | 宿主数据库及可选 TraceExporter | 集中数据库、聚合任务及可选 TraceExporter |

Embedded Admin UI、Standalone Admin UI 和业务 `/v1/*` 接口属于轻享 AI 产品边界；外部模型 API、业务应用、宿主认证与权限系统、外部 Secret 系统以及外部指标与链路系统位于产品边界之外。数据库、Redis 和运行实例是内部部署组成，只在部署与非功能要求中定义，不列作对外关联系统。

## 2.3 功能列表

功能模块按照管理端导航和开发接入能力分为一级功能模块与二级功能模块。一级功能模块定义产品边界，二级功能模块对应可独立开发和验收的页面或运行能力。

| 一级功能模块 | 二级功能模块（主要功能点） | 功能描述 |
|---|---|---|
| 运行概览 | 运行摘要 | 展示请求量、成功率、平均耗时、首 Token 耗时、Token 用量和费用等核心指标。 |
| 运行概览 | 趋势分析 | 按时间范围展示请求、成功率、耗时、Token 和费用趋势。 |
| 运行概览 | 异常定位 | 展示异常 Provider、异常模型、熔断候选和近期失败调用，并可进入对应详情。 |
| 模型接入 | Provider 管理 | 创建、编辑、停用和检测 Provider 连接，维护服务地址、代理、请求头和超时。 |
| 模型接入 | Credential Pool 管理 | 为 Provider 建立凭证池，定义池内凭证选择策略并查看整体容量。 |
| 模型接入 | Credential 管理 | 新增密钥或外部密钥引用，配置 RPM、TPM、并发上限并执行可用性检测。 |
| 模型接入 | Provider Model 管理 | 维护实际模型标识、Token 估算器、上下文窗口、最大输出、stream/system/temperature/top_p/stop 能力与范围、默认参数和价格。 |
| 模型接入 | Provider Model 导入 | 从 Provider 模型列表接口或 Adapter 预置目录读取候选模型，选择后批量生成 Provider Model 草稿。 |
| 模型接入 | Model Alias 管理 | 为业务应用提供稳定模型别名，维护路由策略和启停状态。 |
| 模型接入 | 候选路由管理 | 为模型别名配置实际模型、凭证池、优先级、权重和运行可用状态。 |
| 运行治理 | 限流策略 | 按别名、模型或凭证配置 RPM、TPM、并发及溢出处理方式。 |
| 运行治理 | 可靠性策略 | 配置连接、首 Token、总耗时、重试退避、Fallback 和熔断参数。 |
| 运行治理 | 熔断状态 | 查看 CLOSED、OPEN、HALF_OPEN 状态，并执行人工打开、恢复和探测。 |
| 运行治理 | 路由执行 | 在运行时执行候选过滤、负载均衡、容量预占、失败重试和候选切换。 |
| 调用观测 | Trace 列表 | 按应用、模型、状态、时间和 Trace ID 检索模型调用。 |
| 调用观测 | Trace 详情 | 展示统一请求摘要、路由决策、Attempt 时间线、Token、费用、耗时和错误。 |
| 调用观测 | Usage 与 Cost | 按时间和业务维度汇总请求、Token、实际用量、估算用量和费用。 |
| 运行配置 | 待发布变更 | 汇总尚未生效的模型、路由和治理配置变更。 |
| 运行配置 | 配置发布 | 校验配置完整性，生成配置快照并使运行实例加载新快照。 |
| 运行配置 | 运行参数 | 配置时区、数据保留期、请求字符上限、诊断采样、来源 IP 解析和发布实例协调参数。 |
| 运行配置 | 访问凭证 | 管理 Standalone 调用 Token、应用标识、允许模型、来源 IP、有效期和启停状态。 |
| 运行配置 | 审计日志 | 记录配置对象、变更字段、操作人、结果和失败原因。 |
| 开发接入 | 接入说明 | 按运行形态展示连接地址、认证方式、Alias、依赖、配置、代码示例和协议字段。 |
| 开发接入 | 在线测试 | 使用当前管理身份执行同步或流式测试，并通过 trace_id 进入调用详情。 |
| 开发接入 | Java SDK | 提供本地 Runtime 与 Standalone Client 的同步、异步、流式、取消和统一异常。 |
| 开发接入 | Spring Boot Starter | 提供配置属性、条件自动装配、宿主 Bean 复用、健康检查与生命周期管理。 |
| 开发接入 | Embedded Admin UI | 在宿主路径下提供受宿主认证保护的模型管理、运行治理与观测页面。 |
| 开发接入 | Standalone Server | 提供独立部署、集群运行、访问凭证、健康检查、优雅关闭和三类认证入口。 |
| 开发接入 | 统一 HTTP API | 提供模型目录、同步与 SSE Chat Completions、统一错误和 Trace 关联。 |
| 开发接入 | Provider SPI | 定义 Provider 能力、配置校验、模型列表、Token 估算、同步、流式和错误分类契约。 |

## 2.4 角色与权限

### 2.4.1 角色列表

| 角色 | 职责 |
|---|---|
| 系统管理员 | 维护 Provider、Credential、模型、别名、治理策略、运行配置和访问凭证，并执行配置发布。 |
| 运维人员 | 查看运行概览、Trace、Usage、审计与熔断状态，执行连接检测、候选探测和熔断恢复。 |
| 开发人员 | 查看已发布的模型别名、接入文档和调用记录，使用 SDK 或 HTTP API 联调。 |
| 只读人员 | 查看运行概览、模型配置摘要、Trace 和 Usage，不执行任何变更操作。 |

### 2.4.2 功能权限

| 功能 | 系统管理员 | 运维人员 | 开发人员 | 只读人员 |
|---|---|---|---|---|
| 运行概览与趋势 | 查看 | 查看 | 查看 | 查看 |
| Provider、模型、别名编辑 | 管理 | 查看与检测 | 查看 | 查看 |
| Provider Model 导入 | 管理 | 无 | 无 | 无 |
| Provider、Credential、候选检测 | 管理 | 检测 | 无 | 无 |
| Credential 明文写入 | 管理 | 无 | 无 | 无 |
| Credential 脱敏信息 | 查看 | 查看 | 无 | 无 |
| 限流与可靠性策略 | 管理 | 查看 | 查看 | 查看 |
| 熔断状态 | 查看 | 查看 | 查看授权 Alias | 查看 |
| 熔断人工操作 | 管理 | 管理 | 无 | 无 |
| Trace 与 Usage | 查看 | 查看 | 查看本应用 | 查看 |
| Trace 受控诊断字段 | 查看并记录审计 | 查看并记录审计 | 无 | 无 |
| Trace 与 Usage 导出 | 导出 | 导出 | 无 | 无 |
| 待发布变更 | 查看、撤销 | 查看 | 查看 | 查看 |
| 配置发布 | 管理 | 无 | 无 | 无 |
| 运行参数 | 管理 | 查看 | 查看 | 查看 |
| Standalone Access Credential | 管理 | 查看脱敏信息 | 无 | 无 |
| 审计日志 | 查看 | 查看 | 无 | 无 |
| 接入说明与代码示例 | 查看 | 查看 | 查看授权 Alias | 查看脱敏示例 |
| 在线测试 | 测试 | 测试 | 测试授权 Alias | 无 |

Standalone Mode 使用产品自身权限时按上表鉴权；Embedded Mode 可以由宿主应用把已登录用户映射为上述角色。权限校验同时作用于页面、管理接口和敏感字段返回，Credential 密钥在任何角色的读取接口中均不返回原文。

## 2.5 业务流程

### 2.5.1 模型接入主流程

系统管理员首先创建 Provider 并完成连接检测，然后创建 Credential Pool 和至少一个可用 Credential。管理员在 Provider 下登记 Provider Model，配置上下文、输出、能力和价格，再创建 Model Alias 并添加一个或多个 Route Candidate。全部对象保存后进入待发布状态，管理员在配置发布页面执行完整性校验并发布。发布成功后，运行实例开始接受该 Alias 的模型调用。

任一必需对象停用、引用无效、候选没有可用凭证、模型上下文配置不合法或可靠性参数冲突时，发布校验失败。失败项需要定位到具体对象和字段，原运行快照继续提供服务。

### 2.5.2 模型调用主流程

业务应用提交统一请求。运行时校验调用身份、模型别名、消息、参数和上下文上限，生成 Trace 并获取当前已发布配置快照。路由器依次过滤停用候选、OPEN 候选、能力不匹配候选和容量不足候选，再依据优先级和权重选择 Provider Model 与 Credential Pool。凭证池选择可用 Credential，运行时预占 RPM、TPM 和并发额度，创建 Attempt，转换请求并调用外部 Provider。

调用成功后，运行时归还并发额度，记录 Provider 返回的 Token；Provider 未返回 Usage 时按统一估算规则记录估算 Token。运行时计算费用，完成 Trace，并向业务应用返回统一响应或流式结束事件。所有返回均携带 trace_id。

### 2.5.3 异常与降级流程

连接超时、可重试网络错误和可重试 5xx 可以在策略限制内对当前候选重试。Provider 429 优先在同一凭证池切换 Credential，再切换其他候选；没有替代路径且 Retry-After 未超过等待上限时，才允许对原路径延迟重试。每次恢复动作前重新判断剩余总超时时间和容量，退避等待不超过总超时。当前候选达到重试或凭证切换上限、进入熔断状态或失去可用凭证时，路由器切换到同优先级的其他候选，再切换到较低优先级候选。

参数错误、鉴权错误和明确的内容拒绝不自动重试。请求已经向客户端输出内容后，不再切换候选，流式通道发送包含 UnifiedError 的 SSE data 后结束。所有候选均失败时，运行时返回统一错误码，并在 Trace 中保留每次 Attempt 的错误。

### 2.5.4 配置发布流程

系统初始化时创建 snapshot_no=0 的 ACTIVE 空配置快照，其中只包含 RuntimeConfig 默认值；ConfigDraftState.base_snapshot_no 与运行实例 active_snapshot_no 均从 0 开始。管理页面对配置对象的新增、修改、停用和删除先写入草稿，每次成功写入使 draft_revision 单调递增。管理员先对固定 draft_revision 执行字段、引用、能力、价格、策略和运行实例兼容校验；校验通过后锁定草稿，生成不可变 ConfigSnapshot 并要求目标实例在不切换流量的情况下构建待激活内存配置。全部在线目标实例准备成功后，发布服务原子激活新快照、清空对应草稿并通知实例切换；单个实例通过引用替换原子切换，新 Trace 使用新 snapshot_no，已经运行的 Trace 保持原快照。准备阶段失败时快照标记 ABORTED，活动快照与草稿均保持不变；激活后个别实例未确认时发布记录进入 PARTIAL_FAILED，实例通过心跳继续加载活动快照并在全部收敛后更新为 SUCCEEDED。

### 2.5.5 业务状态

| 对象 | 状态 | 进入条件 | 可执行操作 |
|---|---|---|---|
| Provider | ENABLED、DISABLED | 管理员启停 | 编辑、检测、停用或启用 |
| Provider 连接 | UNKNOWN、AVAILABLE、UNAVAILABLE | 尚未检测、检测成功或检测失败 | 检测、查看最近错误 |
| Credential Pool | AVAILABLE、PARTIAL_AVAILABLE、UNAVAILABLE、DISABLED | 池启停状态与池内 Credential 实时健康状态共同计算 | 编辑、启停、查看 Credential |
| Provider Model 连接 | UNKNOWN、AVAILABLE、UNAVAILABLE | 尚未检测、模型检测成功或检测失败 | 检测、编辑、查看引用候选 |
| Credential | HEALTHY、UNKNOWN、RATE_LIMITED、INVALID、UNAVAILABLE、DISABLED | 检测结果、运行错误、限额复位、Secret 解析结果或人工启停 | 检测、编辑、轮换、停用、启用 |
| Route Candidate | AVAILABLE、CAPACITY_EXHAUSTED、CIRCUIT_OPEN、DISABLED、UNAVAILABLE | 配置与实时运行状态共同决定 | 编辑、探测、查看 Trace |
| Circuit | CLOSED、OPEN、HALF_OPEN | 失败率、等待期、探测结果或人工操作 | 人工打开、恢复、立即探测 |
| Capacity Reservation | ACTIVE、SETTLED、RELEASED、EXPIRED | 外部调用前预占，调用后结算、释放或由 Watchdog 回收 | 查看关联 Trace 与 Attempt |
| Queue Entry | WAITING、ACQUIRED、TIMEOUT、REJECTED、CANCELLED | 容量不足进入队列，取得容量、超时、队列已满或客户端取消后结束 | 查看关联 Trace 和阻塞策略 |
| Recovery Decision | RETRY、CREDENTIAL_FAILOVER、FALLBACK、FAIL | Attempt 失败后根据错误分类、剩余预算和可用路径生成 | 查看来源 Attempt 与目标路径 |
| Trace | QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED、STREAM_INTERRUPTED | 调用生命周期 | 查看详情 |
| Attempt | RUNNING、SUCCEEDED、FAILED、CANCELLED | 外部调用开始、正常完成、失败分类或取消传播 | 查看时间线与恢复动作 |
| Provider Check Record | SUCCEEDED、FAILED | 主动检测结束 | 查看检测对象、耗时、Usage、Provider Request ID 与错误 |
| Batch Check Job | PENDING、RUNNING、SUCCEEDED、PARTIAL_FAILED、FAILED、CANCELLED | 批量任务创建、执行、汇总或取消 | 查看任务明细、取消未开始项 |
| Config Draft State | EDITABLE、PUBLISHING | 正常编辑或发布取得全局草稿锁 | 编辑与撤销；PUBLISHING 时只读 |
| Config Validation Result | PASSED、FAILED、EXPIRED | 校验通过、校验失败、超时或草稿修订变化 | PASSED 且未过期时发布 |
| Config Snapshot | CREATED、ACTIVE、SUPERSEDED、ABORTED | 快照生成、原子激活、被新快照替代或准备失败 | 内部加载、查看摘要 |
| Publish Record | PREPARING、ACTIVATING、SUCCEEDED、PARTIAL_FAILED、FAILED | 实例准备、快照激活和实例确认结果 | 查看详情；失败时修复草稿后重新校验发布 |
| Publish Instance Result | PENDING、PREPARING、READY、ACTIVATING、LOADED、FAILED、TIMED_OUT | 目标实例接收指令、构建配置、等待激活、完成加载或超时失败 | 查看实例结果与错误 |
| Runtime Instance | ONLINE、DRAINING、STALE、OFFLINE | 心跳、accepting_requests、快照加载和失联时长共同决定 | 查看实例与快照；DRAINING、STALE、OFFLINE 不接收新发布目标 |
| Standalone Access Credential | ACTIVE、DISABLED、EXPIRED、DELETED | 创建、人工启停、到期或软删除 | 编辑、轮换、启停、删除、查看 Trace |
| Usage Aggregation Event | PENDING、PROCESSING、SUCCEEDED、FAILED | Trace 最终化、聚合器取得任务、幂等写入成功或重试耗尽 | 查看聚合延迟与失败告警 |

配置启停与运行健康状态相互独立。Provider、Credential Pool、Credential、Provider Model、Model Alias 和 Route Candidate 的 enabled 变更先进入草稿，发布成功后才影响新 Trace；connection_status、health_status、runtime_status 和 CircuitState 属于运行状态，由检测、外部调用、容量和熔断结果即时更新，不进入配置快照。页面必须分别显示“配置启停”“待发布变更”和“当前运行状态”，不能把最近一次检测成功等同于对象已经发布可用。

Trace 创建后处于 RUNNING；只有因容量 QUEUE 等待时使用 QUEUED，取得容量后回到 RUNNING。每次实际向 Provider 发出请求前创建 RUNNING Attempt，完成后只能进入 SUCCEEDED、FAILED 或 CANCELLED。Trace 在全部业务处理结束后进入一个最终状态并写 ended_at；已经结束的 Trace 和 Attempt 不允许回退到运行状态。RecoveryDecision 是失败后的不可变决策记录，不存在二次状态迁移。

配置发布从 EDITABLE 草稿开始。校验通过只生成 PASSED ConfigValidationResult，不修改活动配置；发布取得锁后草稿进入 PUBLISHING，创建 CREATED ConfigSnapshot 与 PREPARING PublishRecord。任一目标实例准备失败时快照进入 ABORTED、发布进入 FAILED、草稿恢复 EDITABLE；全部 READY 后快照原子进入 ACTIVE，上一快照进入 SUPERSEDED。激活后实例未全部 LOADED 时发布暂为 PARTIAL_FAILED，实例后续收敛后可以进入 SUCCEEDED，但活动快照不回退。

Runtime Instance 正常心跳且 accepting_requests=true 时为 ONLINE，关闭接入时为 DRAINING，超过失联阈值为 STALE 或 OFFLINE。Standalone Access Credential 到达 expires_at 后由读取逻辑计算为 EXPIRED；轮换不改变 ACTIVE 或 DISABLED 状态，只递增 rotation_generation 并立即使旧 Token 失效；DELETED 为不可恢复终态。

## 2.6 信息结构

### 2.6.1 信息结构说明

本节描述第一阶段各功能在页面展示、配置管理和核心运行流程中直接使用的主要业务信息。字段范围以能够明确产品行为、页面交互、接口契约和研发数据边界为准。数据库通用字段、Java DTO、内部命令对象、异常类、响应包装类和框架实现参数归入技术设计与接口定义，不在 PRD 中逐项展开。

所有可维护配置默认具有唯一标识、版本、启停状态、创建信息和更新信息；支持草稿发布的对象还具有草稿变更状态。以上通用信息遵循 2.6.2 的统一规则，后续实体不重复列出。

第一阶段的核心信息沿页面与运行链路分为六组：Provider 与凭证信息用于建立外部模型连接；Provider Model 用于描述模型能力和上下文限制；Model Alias 与 Route Candidate 用于形成业务稳定模型名和候选路由；运行治理信息用于限流、重试、降级与熔断；Trace、Attempt 与 Usage 用于调用观测；运行配置、发布记录、访问凭证和审计日志用于配置生效与系统运维。

### 2.6.2 字段通用规则

| 规则项 | 规则 |
| --- | --- |
| 标识 | 核心实体使用全局唯一 ID。页面展示名称不能代替 ID 建立关联。 |
| 时间 | 统一使用带时区时间，接口采用 ISO 8601，存储统一转换为 UTC。 |
| 枚举 | 枚举值由服务端定义，前端展示中文名称，保存和接口传输使用稳定代码。 |
| 金额 | 价格使用十进制定点数，并明确币种与计价单位。 |
| Token | 上下文、输入、输出和累计用量均使用非负整数。 |
| 比例 | 权重使用正整数；失败率和阈值使用 0 至 1 的小数。 |
| 密钥 | 密钥只允许写入和掩码展示，任何查询、日志和导出均不得返回明文。 |
| 可空值 | 可空表示业务允许缺省；零值表示明确配置为 0，两者含义分离。 |
| 删除 | 已被引用的配置不得直接删除；允许停用或解除引用后删除。 |
| 发布 | 管理端编辑保存为草稿，只有发布成功的配置快照能够被 Runtime 使用。 |

### 2.6.3 Provider 主要字段

Provider 表示一种外部模型服务接入配置。Provider 页面使用下列信息完成列表展示、连接配置、能力识别和连通性检查。

| 主要字段 | 含义与使用规则 |
| --- | --- |
| 名称 | 管理端唯一名称，用于选择和识别 Provider。 |
| 类型 | OPENAI、ANTHROPIC、GEMINI、DEEPSEEK 或 CUSTOM_SPI，决定适配器与可配置能力。 |
| Base URL | 外部服务地址；内置类型可使用默认地址，自定义地址必须显式填写。 |
| 代理地址 | 可选的 HTTP 或 HTTPS 代理地址；为空时直接访问 Provider。 |
| 连接与读取超时 | 分别限制建立连接和等待响应的时间。 |
| 附加请求头 | 发送给外部服务的非认证请求头，禁止配置 Host、Content-Length 和认证头。 |
| 状态 | 启用或停用；停用后不参与新请求选路。 |
| 最近检查结果 | 最近一次检查时间、结果、耗时和简要失败原因，仅用于页面展示。 |

### 2.6.4 Credential Pool 与 Credential 主要字段

Credential Pool 隶属于一个 Provider，用于组织可轮换的凭证。Credential 是池内的单个密钥。运行时先选定 Provider 和候选模型，再从对应凭证池中选择可用 Credential。

| 对象 | 主要字段 | 含义与使用规则 |
| --- | --- | --- |
| Credential Pool | 名称 | Provider 内唯一，用于页面识别和 Provider 关联。 |
| Credential Pool | Provider | 所属 Provider，创建后不可跨 Provider 迁移。 |
| Credential Pool | 选择策略 | LEAST_CONCURRENT、ROUND_ROBIN 或 WEIGHTED_RANDOM。 |
| Credential Pool | 状态 | 停用后池内所有 Credential 不再参与新请求。 |
| Credential | 名称 | 凭证池内唯一名称。 |
| Credential | 密钥来源 | INLINE_ENCRYPTED 或 EXTERNAL_REF，创建后不可切换。 |
| Credential | 密钥值或引用 | 创建、轮换或编辑时写入；查询只返回掩码，不返回明文或完整引用。 |
| Credential | 权重 | WEIGHTED_RANDOM 策略下的选择权重。 |
| Credential | RPM / TPM / 并发上限 | 可选的单凭证容量限制。 |
| Credential | 启停与健康状态 | 启停由管理员配置，健康状态由检测和运行结果更新。 |
| Credential | 限流复位时间 | Provider 明确返回限流恢复时间时记录，在复位前不参与选择。 |
| Credential | 最近活动 | 最近成功、检查和失败摘要，用于调度判断与问题定位。 |

### 2.6.5 Provider Model 主要字段

Provider Model 表示某个 Provider 可调用的具体模型。该信息既用于模型管理页面，也用于请求校验、Token 预算、能力过滤和用量计价。自动拉取只负责生成待确认信息，管理员确认后才能进入草稿并发布。

| 主要字段 | 含义与使用规则 |
| --- | --- |
| Provider | 模型所属 Provider。 |
| Model ID | 传给外部 Provider 的真实模型标识，在 Provider 内唯一。 |
| 展示名称 | 管理端和模型列表使用的名称。 |
| 模型类型 | 第一阶段固定为 CHAT_TEXT。 |
| Tokenizer | Token 估算器类别；必须来自当前 Provider Adapter 声明的实现。 |
| 上下文窗口 | 单次请求允许的输入与输出 Token 总上限。 |
| 最大输出 Token | 单次请求允许设置的最大输出 Token。 |
| 流式能力 | 是否支持流式输出。 |
| System Message 能力 | 是否支持 system 消息；不支持时请求校验失败。 |
| Temperature 能力 | 是否支持以及允许的最小值、最大值。 |
| Top P 能力 | 是否支持以及允许的最小值、最大值。 |
| Stop 能力 | 是否支持以及 stop 数量、单项长度限制。 |
| 默认参数 | 默认 temperature、top_p 和最大输出 Token；请求显式值优先。 |
| 输入价格 | 输入 Token 单价，与计价单位和币种共同使用。 |
| 输出价格 | 输出 Token 单价，与计价单位和币种共同使用。 |
| 计价单位与币种 | 计价单位为每千或每百万 Token，币种使用 ISO 4217 代码。 |
| 状态 | 启用或停用；停用模型不进入新请求候选集。 |
| 最近检查结果 | 最近一次模型检测的状态、时间和简要失败原因。 |

上下文校验按照“已估算输入 Token + 请求最大输出 Token 不大于上下文窗口”执行。请求未填写最大输出 Token 时使用模型默认值；仍未配置时使用模型允许的最大输出 Token。任何超限请求在调用 Provider 前失败。

### 2.6.6 Model Alias 与 Route Candidate 主要字段

Model Alias 是业务应用调用时使用的稳定模型名。Route Candidate 定义该 Alias 可落到哪些 Provider Model，以及候选之间的优先级、权重和降级顺序。

| 对象 | 主要字段 | 含义与使用规则 |
| --- | --- | --- |
| Model Alias | Alias | 业务调用使用的唯一模型名，发布后保持稳定。 |
| Model Alias | 展示名称 | 管理端和模型发现接口的可读名称。 |
| Model Alias | 描述 | 管理端说明信息，不进入外部模型请求。 |
| Model Alias | 路由策略 | 第一阶段固定为 PRIORITY_WEIGHTED。 |
| Model Alias | 能力摘要 | 根据已发布候选汇总流式能力和可用候选数量。 |
| Model Alias | 状态 | 停用后拒绝以该 Alias 发起的新请求。 |
| Route Candidate | Provider Model | 候选指向的真实 Provider Model。 |
| Route Candidate | Credential Pool | 必须选择与 Provider Model 同属一个 Provider 的凭证池。 |
| Route Candidate | 优先级 | 数值越小越先进入选择组。 |
| Route Candidate | 权重 | 同一优先级内的流量分配依据。 |
| Route Candidate | 状态 | 停用后不参与新请求选路。 |
| Route Candidate | 能力与运行状态 | 展示模型能力、当前并发、是否可调用和被排除原因，不进入配置草稿。 |

同一 Alias 至少需要一个启用候选才能发布。候选引用的 Provider、Provider Model、Credential Pool 和治理策略必须处于可发布状态，且模型能力能够覆盖 Alias 对外声明的能力。

### 2.6.7 运行治理主要字段

运行治理信息服务于运行时选路和故障恢复。页面只维护策略字段，容量占用、排队、恢复判断和熔断变化由系统生成。

| 对象 | 主要字段 | 含义与使用规则 |
| --- | --- | --- |
| Limit Policy | 名称与作用对象 | 作用于 Model Alias、Provider Model 或 Credential，同一对象最多一份启用策略。 |
| Limit Policy | RPM / TPM / 并发上限 | 分别限制请求数、Token 数和同时执行数；空值表示该维度不限制。 |
| Limit Policy | 溢出策略 | REJECT 立即拒绝，QUEUE 在存在可释放容量时进入队列。 |
| Limit Policy | 队列参数 | QUEUE 模式下的最大等待时间和最大队列长度。 |
| Limit Policy | Token 预占规则 | 按输入估算值和最大输出 Token 预占，结束后按实际用量结算。 |
| Reliability Policy | 名称与 Model Alias | 每个 Alias 最多一份启用策略，停用后使用系统默认策略。 |
| Reliability Policy | 超时 | 连接超时、流式首 Token 超时和完整 Trace 总超时。 |
| Reliability Policy | 尝试预算 | 同候选重试次数、Credential Failover 次数和 Route Candidate Fallback 次数。 |
| Reliability Policy | 退避与 Retry-After | 初始退避、倍率、抖动、是否遵循 Retry-After 及最大等待时间。 |
| Reliability Policy | Fallback 开关 | 控制是否允许切换 Route Candidate；流式首块发出后强制禁止。 |
| Reliability Policy | 熔断窗口与阈值 | 统计窗口、最小请求数、失败率阈值和 OPEN 持续时间。 |
| Reliability Policy | 半开恢复条件 | HALF_OPEN 探测数和恢复所需成功数。 |
| 运行态记录 | 容量占用 | 记录 Trace、策略维度、预占请求数、Token 和并发数。 |
| 运行态记录 | 排队信息 | 记录进入时间、截止时间和当前结果。 |
| 运行态记录 | 恢复判断 | 记录当前 Attempt 后选择重试、Fallback 或终止的原因。 |
| 运行态记录 | 熔断状态 | 记录 CLOSED、OPEN、HALF_OPEN、统计窗口和最近变化原因。 |

### 2.6.8 Trace、Attempt 与 Usage 主要字段

Trace 表示一次业务调用的完整生命周期，Attempt 表示该调用对某个 Provider Model 和 Credential 的一次实际尝试。Usage Aggregate 是从已完成 Attempt 聚合得到的管理指标。

| 对象 | 主要字段 | 含义与使用规则 |
| --- | --- | --- |
| Trace | Trace ID | 调用唯一标识；支持调用方传入，未传时由系统生成。 |
| Trace | 调用时间 | 开始时间、结束时间和总耗时。 |
| Trace | 调用来源 | LOCAL_RUNTIME、STANDALONE_SERVER 或管理端调试。 |
| Trace | Model Alias | 业务请求使用的 Alias。 |
| Trace | 请求模式 | 同步或流式。 |
| Trace | 最终状态 | 成功、失败、取消或超时。 |
| Trace | 最终路由 | 最终成功或最后失败的 Provider、Provider Model 与 Credential 标识。 |
| Trace | Attempt 数 | 本次调用实际产生的尝试次数。 |
| Trace | Token 用量 | 输入、输出和总 Token。 |
| Trace | 费用 | 按调用时价格快照计算的金额与币种。 |
| Trace | 错误摘要 | 统一错误分类、错误码和可重试标志，不保存外部响应正文。 |
| Trace | 内容采样 | 默认关闭；开启时仅保存脱敏、截断后的请求或响应样本。 |
| Attempt | Attempt 序号 | 在 Trace 内从 1 递增。 |
| Attempt | 路由信息 | 本次使用的候选、Provider、Provider Model 和 Credential。 |
| Attempt | 执行时间 | 开始、首块、结束时间与耗时。 |
| Attempt | 执行结果 | 成功、失败、取消或超时。 |
| Attempt | Token 与费用 | 本次尝试的实际或估算用量及费用。 |
| Attempt | 错误分类 | 用于重试、Fallback、熔断和页面排障的统一分类。 |
| Attempt | 恢复动作 | NONE、RETRY、FALLBACK 或 TERMINATE。 |
| Usage Aggregate | 时间粒度 | 小时或天。 |
| Usage Aggregate | 聚合维度 | Alias、Provider 或 Provider Model，可组合筛选。 |
| Usage Aggregate | 请求指标 | 请求数、成功数、失败数和成功率。 |
| Usage Aggregate | 性能指标 | 平均耗时、P95 耗时和首块耗时。 |
| Usage Aggregate | 用量指标 | 输入、输出和总 Token。 |
| Usage Aggregate | 费用指标 | 汇总费用与币种。 |

Trace 内容采样与调用主记录分开保存。主记录必须可长期保留并用于指标统计；内容样本按运行配置设置独立保留期，到期后删除且不影响 Trace、Attempt 和 Usage Aggregate。

### 2.6.9 运行配置、发布、访问凭证与审计主要字段

| 对象 | 主要字段 | 含义与使用规则 |
| --- | --- | --- |
| Runtime Config | 时区 | 决定管理页面时间和 Usage 聚合桶；产生聚合数据后锁定。 |
| Runtime Config | 数据保留 | Trace、Usage、Audit 和诊断样本的保留天数。 |
| Runtime Config | 页面刷新周期 | 控制运行概览和运行状态的默认刷新频率。 |
| Runtime Config | 请求长度限制 | 单条消息和完整请求允许的最大字符数。 |
| Runtime Config | 诊断采样 | 是否启用、采样比例、样本最大字符数和保留天数。 |
| Runtime Config | 来源 IP | 是否记录客户端 IP，以及 Standalone 信任的代理网段。 |
| Runtime Config | 发布协调 | 运行实例准备超时和实例失联判定时间。 |
| Draft Change | 变更对象 | 发生新增、修改、停用或删除的配置对象与对象 ID。 |
| Draft Change | 变更摘要 | 用于发布确认页展示的关键字段变化。 |
| Config Snapshot | 快照版本 | 每次成功发布生成的唯一版本。 |
| Config Snapshot | 配置内容摘要 | 快照包含的对象数量、校验摘要和内容校验值。 |
| Publish Record | 发布结果 | 成功或失败、操作人、时间和失败原因。 |
| Publish Record | 前后版本 | 发布前活动版本和目标版本。 |
| Standalone Access Credential | 名称 | 业务应用接入凭证的管理名称。 |
| Standalone Access Credential | Application | 写入 Trace 的业务应用标识。 |
| Standalone Access Credential | Access Token | 创建或轮换时返回一次，之后只显示掩码。 |
| Standalone Access Credential | 允许 Alias | 可选白名单；为空表示允许全部已发布 Alias。 |
| Standalone Access Credential | IP 白名单 | 可选 IPv4、IPv6 或 CIDR 列表；为空表示不限制来源 IP。 |
| Standalone Access Credential | 有效期与状态 | 控制凭证何时可以访问 Standalone Server。 |
| Standalone Access Credential | 轮换与使用摘要 | 记录当前代次、轮换时间、最近使用时间和脱敏来源。 |
| Audit Log | 操作信息 | 操作人、动作、对象类型、对象 ID 和时间。 |
| Audit Log | 结果与摘要 | 成功或失败及脱敏后的变更摘要。 |
| Audit Log | 请求来源 | 来源 IP、客户端或运行模式，用于安全审计。 |

发布时系统校验字段完整性、对象引用、模型能力、凭证可用性、治理策略和 Runtime 兼容性。校验失败只生成失败的 Publish Record，不改变活动快照；发布成功后 Runtime 原子切换到新快照，正在执行的 Trace 继续使用开始时的版本。

### 2.6.10 统一模型调用主要字段

统一模型调用是 Java SDK、Standalone Server 和 Provider Adapter 之间的稳定业务契约。本节只定义业务调用需要的主要信息；具体类名、序列化细节和内部异常对象归入接口与技术设计。

| 方向 | 主要字段 | 含义与使用规则 |
| --- | --- | --- |
| 请求 | model | Model Alias；未填写时使用 Runtime 默认 Alias。 |
| 请求 | messages | 按顺序传入的 system、user、assistant 消息及文本内容。 |
| 请求 | stream | 是否使用流式响应。 |
| 请求 | temperature / top_p | 可选采样参数，必须满足目标 Provider Model 的范围。 |
| 请求 | max_tokens | 可选输出上限，参与上下文与容量预占校验。 |
| 请求 | stop | 可选停止序列，数量和长度受模型能力限制。 |
| 请求 | trace_id | 可选调用链标识；未传时由系统生成。 |
| 请求 | metadata | 可选业务检索信息，只允许受限的字符串键值，不进入 Provider 提示词。 |
| 请求 | provider_options | 可选 Provider 专属参数，只接受 Adapter 声明的白名单字段。 |
| 请求 | stream_options | 流式调用是否向业务方返回 Usage。 |
| 响应 | trace_id | 对应完整调用 Trace。 |
| 响应 | model | 实际使用的 Model Alias。 |
| 响应 | provider / provider_model | 实际完成调用的 Provider 与模型标识。 |
| 响应 | message | 非流式返回的 assistant 文本。 |
| 响应 | finish_reason | 正常结束、长度限制、停止序列或其他统一结束原因。 |
| 响应 | usage | 输入、输出和总 Token；Provider 未返回时标记为估算。 |
| 响应 | cost | 可选费用、币种与是否估算。 |
| 流式响应 | event | START、DELTA、USAGE 或 DONE。 |
| 流式响应 | delta | DELTA 事件中的增量文本。 |
| 流式响应 | sequence | Trace 内单调递增的事件序号。 |
| 失败结果 | code / message | 稳定错误码与可向调用方展示的简要信息。 |
| 失败结果 | retryable / trace_id | 表示业务方是否可重试，并提供排障标识。 |

流式调用在第一个内容块发出前可以按照 Reliability Policy 重试或 Fallback；第一个内容块发出后，任何失败均终止当前流并返回失败结果，避免向业务方拼接来自不同模型的内容。

### 2.6.11 开发接入与扩展契约的主要信息

开发接入页面需要同时说明 LOCAL_RUNTIME、STANDALONE_SERVER 和 Java SDK 三种使用方式。LOCAL_RUNTIME 配置由业务进程直接加载，主要包括配置来源、运行定义和管理端监听信息；STANDALONE_SERVER 配置主要包括服务监听地址、活动配置来源、业务访问认证和管理端访问控制；Java SDK 配置主要包括运行模式、服务地址、访问凭证、请求超时、流式空闲超时和连接池设置。配置项的默认值、环境变量映射和启动失败条件在 4.6 中定义，本节不重复展开框架配置对象。

Provider SPI 面向自定义模型服务扩展，必须能够声明 Adapter 标识和支持能力，校验 Provider 配置，执行非流式或流式调用，并把外部结果、Token 用量和错误归一为统一模型调用信息。Runtime 负责选路、凭证选择、限流、重试、Fallback、熔断和 Trace 记录，Provider Adapter 只执行当前一次外部调用，不自行实施上述治理策略。

宿主扩展主要包括密钥解析、业务鉴权和 Trace 导出。密钥解析只根据 Credential 标识返回短生命周期的密钥句柄；业务鉴权根据访问凭证和请求 Alias 给出允许或拒绝结果；Trace 导出接收脱敏后的 Trace、Attempt 与 Usage 信息。扩展实现不得保存密钥明文、消息正文或 Provider 原始错误正文。

### 2.6.12 核心实体关系

| 关系 | 约束 |
| --- | --- |
| Provider 与 Credential Pool | 一个 Provider 可以关联多个凭证池，凭证池只能属于一个 Provider。 |
| Credential Pool 与 Credential | 一个凭证池包含多个 Credential，Credential 不能跨池使用。 |
| Provider 与 Provider Model | 一个 Provider 可以维护多个模型，同一 Provider 内 Model ID 唯一。 |
| Model Alias 与 Route Candidate | 一个 Alias 至少包含一个候选；候选只能属于一个 Alias。 |
| Route Candidate 与 Provider Model | 每个候选指向一个 Provider Model，并可覆盖该 Provider 的默认凭证池。 |
| 模型与治理策略 | Limit Policy 可关联 Alias、Provider Model 或 Credential；Reliability Policy 关联 Alias 并包含重试、Fallback 和熔断配置。 |
| Trace 与 Attempt | 一个 Trace 包含一次或多次 Attempt；Attempt 不能脱离 Trace 存在。 |
| Attempt 与配置快照 | Attempt 保存实际使用的 Provider、模型、凭证和策略标识，并关联 Trace 开始时的活动快照。 |
| Attempt 与 Usage Aggregate | 已结束 Attempt 产生用量事实，聚合任务按时间和业务维度形成 Usage Aggregate。 |
| Config Snapshot 与 Publish Record | 每次成功发布生成一个不可变快照和一条成功记录；失败发布不生成活动快照。 |
| Standalone Access Credential 与 Model Alias | 访问凭证可限制允许调用的 Alias，未配置白名单时允许全部已发布 Alias。 |
| Audit Log 与管理对象 | Provider、凭证、模型、Alias、治理策略、运行配置、发布和访问凭证的管理操作均产生审计记录。 |

上述关系用于发布校验和运行时引用解析。停用或删除上游对象前，系统必须检查活动草稿和活动快照中的引用；存在有效引用时阻止删除，并在页面中展示引用对象。

## 2.7 功能实现追踪

功能实现追踪用于把 2.3 的二级功能映射到详细需求、主要业务信息、接口入口和验收要求。开发拆分以二级功能为最小业务单元；同一行中的页面、服务端逻辑、核心信息和验收场景需要一起完成。

| 一级功能模块 | 二级功能模块 | 详细需求位置 | 主要业务信息 | 核心接口或入口 | 验收位置 |
| --- | --- | --- | --- | --- | --- |
| 运行概览 | 运行摘要 | 4.1.1 | Trace、Attempt、Usage Aggregate、Circuit State | GET /admin/overview/filters、GET /admin/overview/summary | 6.4、6.6“概览指标一致性” |
| 运行概览 | 趋势分析 | 4.1.2 | Trace、Usage Aggregate | GET /admin/overview/trends | 6.4、6.6“概览趋势与钻取” |
| 运行概览 | 异常定位 | 4.1.3 | Trace、Attempt、Circuit State | GET /admin/overview/exceptions | 6.4、6.6“概览异常权限与定位” |
| 模型接入 | Provider 管理 | 4.2.1—4.2.2 | Provider、Provider Check Record | /admin/providers、/check、/impact | 6.1、6.6“首次接入模型” |
| 模型接入 | Credential Pool 管理 | 4.2.3—4.2.4 | Credential Pool、Credential | /admin/credential-pools | 6.1、6.6“多密钥调度” |
| 模型接入 | Credential 管理 | 4.2.4 | Credential、Credential Pool | /admin/credentials、/check、/rotate | 6.1、6.4、6.6“密钥安全” |
| 模型接入 | Provider Model 管理 | 4.2.5—4.2.6 | Provider Model | /admin/provider-models | 6.1、6.6“模型上下文过滤” |
| 模型接入 | Provider Model 导入 | 4.2.5.2 | Provider Model、导入候选 | /admin/providers/{id}/available-models、/admin/provider-models/import | 6.1、4.2.5.2 完成标准 |
| 模型接入 | Model Alias 管理 | 4.2.7—4.2.8 | Model Alias、Route Candidate | /admin/model-aliases | 6.1、6.6“首次接入模型” |
| 模型接入 | 候选路由管理 | 4.2.8 | Route Candidate、Provider Model | /admin/model-aliases/{id}/candidates | 6.2、6.6“模型上下文过滤” |
| 运行治理 | 限流策略 | 4.3.1 | Limit Policy、容量占用、排队信息 | /admin/limit-policies、/usage、/queue | 6.2 |
| 运行治理 | 可靠性策略 | 4.3.2 | Reliability Policy、恢复判断 | /admin/reliability-policies | 6.3、6.6“Provider 限流” |
| 运行治理 | 熔断状态 | 4.3.3 | Circuit Policy、Circuit State | /admin/circuits、/open、/recover、/probe | 6.3、6.6“熔断恢复” |
| 运行治理 | 路由执行 | 4.3.4 | Route Candidate、容量占用、Attempt | Runtime Core 进程内调用链 | 6.2、6.3 |
| 调用观测 | Trace 列表 | 4.4.1 | Trace | GET /admin/traces | 6.4、6.6“Trace 精确定位” |
| 调用观测 | Trace 详情 | 4.4.2 | Trace、Attempt、内容采样 | GET /admin/traces/{trace_id} | 6.4、6.6“Trace 时间线” |
| 调用观测 | Usage 与 Cost | 4.4.3 | Usage Aggregate、Attempt | /admin/usage/summary、/trends、/groups | 6.4、6.6“用量复算”“多币种汇总” |
| 运行配置 | 待发布变更 | 4.5.1 | Draft Change、配置引用 | /admin/config/draft-state、/draft-changes、/revert | 6.6“草稿修订失效”“单项撤销依赖” |
| 运行配置 | 配置发布 | 4.5.2 | Config Snapshot、Publish Record | /admin/config/validate、/publish、/publish-records | 6.6“实例准备失败”“配置原子激活”“激活后实例收敛” |
| 运行配置 | 运行参数 | 4.5.3 | Runtime Config | GET/PUT /admin/runtime-config、/retention-impact | 6.6“保留影响过期”“时区锁定” |
| 运行配置 | 访问凭证 | 4.5.4 | Standalone Access Credential | /admin/access-credentials、/rotate | 6.6“访问 Token 一次显示与轮换”“访问范围控制” |
| 运行配置 | 审计日志 | 4.5.5 | Audit Log | /admin/audit-logs、/export | 6.4、6.6“审计事务一致性” |
| 开发接入 | 接入说明 | 4.6.1.1—4.6.1.3 | 接入模式、服务地址、访问凭证、示例参数 | /admin/developer-access/context、/code-samples | 6.5、6.6“接入示例安全” |
| 开发接入 | 在线测试 | 4.6.1.4 | 统一模型请求、响应与流式事件 | /admin/developer-access/test/chat、/stream | 6.5、6.6“同步响应一致性”“流提交前恢复” |
| 开发接入 | Java SDK | 4.6.2 | SDK 运行模式、连接参数、统一模型调用 | Java SDK 同步、异步、流式方法 | 6.5、6.6“Java 异步取消”“Java 流式背压”“Local Runtime 配置校验” |
| 开发接入 | Spring Boot Starter | 4.6.3 | Starter 运行模式、配置来源、管理端路径 | Spring 自动装配与 Actuator | 6.5、6.6“Starter 模式隔离”“Starter Bean 覆盖” |
| 开发接入 | Embedded Admin UI | 4.6.3.4 | 管理端路径、宿主用户与权限 | 管理端路径与宿主认证扩展 | 6.5、6.6“Embedded Admin 安全” |
| 开发接入 | Standalone Server | 4.6.4 | 服务监听、活动配置、业务认证、实例状态 | /health/live、/health/ready、/internal/runtime/* | 6.5、6.6“Standalone 就绪与摘流”“Standalone 集群容量” |
| 开发接入 | 统一 HTTP API | 4.7.1 | 模型目录、统一模型请求、响应、流式事件与失败结果 | GET /v1/models、POST /v1/chat/completions | 6.5、6.6“模型目录访问范围”“同步响应一致性”“流提交后中断” |
| 开发接入 | Provider SPI | 4.7.2 | Adapter 能力、Provider 配置、单次调用结果与错误分类 | Provider Adapter 方法契约 | 6.5、6.6“Provider SPI 错误分类” |

# 3. 关联系统

关联系统只描述轻享 AI 与部署边界之外系统的数据交换。业务数据库、缓存和运行实例属于产品内部基础设施，在非功能需求中说明，不列入关联系统。

## 3.1 外部系统清单

| 外部系统 | 对接方向 | 接口与认证 | 必需性 | 对接目的 |
|---|---|---|---|---|
| OpenAI API | 双向 | HTTPS；Bearer API Key | 内置可选 Provider | 发送统一对话请求并获取文本、Usage、错误和请求标识。 |
| Anthropic Claude API | 双向 | HTTPS；x-api-key 与 anthropic-version | 内置可选 Provider | 调用 Messages API 并转换 system、messages、content blocks 和 Usage。 |
| Google Gemini API | 双向 | HTTPS；x-goog-api-key | 内置可选 Provider | 调用 generateContent 或 streamGenerateContent 并转换候选与 UsageMetadata。 |
| DeepSeek API | 双向 | HTTPS；Bearer API Key | 内置可选 Provider | 调用 OpenAI 兼容的 Chat Completions 接口。 |
| 其他文本模型 Provider API | 双向 | Provider SPI 定义 HTTPS 协议与认证 | 自定义可选 Provider | 通过部署方 Adapter 接入其他文本 Chat 服务，并转换模型目录、同步、流式、Usage 与错误。 |
| 外部 Secret 系统 | 双向 | Secret SPI，由部署方实现认证 | 可选 | 按 secret_ref 读取模型密钥，密钥不写入轻享 AI 数据库。 |
| 宿主认证与权限系统 | 入站 | Embedded SPI 或宿主安全上下文 | Embedded 可选 | 提供登录用户、角色和数据范围。 |
| 外部指标与链路系统 | 出站 | Metrics/Trace Exporter；协议由部署配置 | 可选 | 接收运行指标和脱敏 Trace 关联信息。 |
| 业务应用与开发者客户端 | 双向 | Java SDK、进程内调用或 HTTPS Bearer Token | 必需调用方 | 提交统一模型请求并接收模型目录、同步响应、SSE、Usage、Cost、错误和 trace_id。 |

## 3.2 OpenAI API 数据交换

轻享 AI 通过 OpenAI Chat API 发送请求。适配器以 Authorization Bearer 传递所选 Credential，并保留 Provider 返回的请求标识用于故障排查。对接字段以 [OpenAI Chat API Reference](https://developers.openai.com/api/reference/resources/chat) 和 [OpenAI Authentication](https://platform.openai.com/docs/api-reference/authentication?lang=go) 为实现依据。

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 请求头 | Authorization、Content-Type、自定义组织或项目头 | Credential 鉴权与租户路由 | 轻享 AI → OpenAI；Authorization 运行时注入且不记录。 |
| 对话请求 | model、messages、stream、temperature、top_p、stop、max_completion_tokens 或接口支持的等价字段 | 统一请求协议转换 | 轻享 AI → OpenAI；统一 max_tokens 转换为 Provider 支持字段。 |
| 同步响应 | id、created、model、choices、finish_reason、usage | 生成统一响应并记录实际 Usage | OpenAI → 轻享 AI。 |
| 流式响应 | choices[].delta、finish_reason、usage、结束标识 | 生成 UnifiedChatChunk，并由 Java SDK 转换为 StreamEvent | OpenAI → 轻享 AI；连接中断转为 STREAM_INTERRUPTED。 |
| 错误与追踪 | HTTP 状态、错误码、错误消息、x-request-id | 错误分类、重试判断和 Attempt 追踪 | OpenAI → 轻享 AI；错误消息脱敏后保存。 |

## 3.3 Anthropic Claude API 数据交换

轻享 AI 使用 Anthropic Messages API。统一请求中的 system 消息转换为顶层 system 字段，其余消息转换为 messages。实现字段以 [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create) 为依据。

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 请求头 | x-api-key、anthropic-version、Content-Type | Credential 鉴权与协议版本声明 | 轻享 AI → Anthropic；密钥不记录。 |
| 对话请求 | model、system、messages、max_tokens、temperature、top_p、stop_sequences、stream | 统一消息与采样参数转换 | 轻享 AI → Anthropic；max_tokens 必填时使用请求值或模型默认值。 |
| 同步响应 | id、type、role、content、model、stop_reason、usage.input_tokens、usage.output_tokens | 统一响应、结束原因和实际 Usage | Anthropic → 轻享 AI。 |
| 流式事件 | message_start、content_block_delta、message_delta、message_stop、error | 转换统一流式事件 | Anthropic → 轻享 AI；内容块文本合并为 delta。 |
| 错误与追踪 | HTTP 状态、error.type、error.message、request-id | 错误分类、重试判断和 Attempt 追踪 | Anthropic → 轻享 AI；鉴权和参数错误不自动重试。 |

## 3.4 Google Gemini API 数据交换

轻享 AI 使用 Gemini generateContent 和 streamGenerateContent。统一 system 消息转换为 systemInstruction，其他消息转换为 contents，流式响应按 SSE 读取。实现字段以 [Gemini API generateContent](https://ai.google.dev/api/generate-content) 为依据。

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 请求地址与请求头 | models/{model}:generateContent 或 streamGenerateContent、x-goog-api-key | 选择同步或流式接口并鉴权 | 轻享 AI → Gemini。 |
| 对话请求 | systemInstruction、contents[].role、contents[].parts、generationConfig.temperature、topP、maxOutputTokens、stopSequences | 统一消息和采样参数转换 | 轻享 AI → Gemini。 |
| 响应内容 | candidates[].content.parts、finishReason、promptFeedback | 统一文本、结束原因和内容拒绝结果 | Gemini → 轻享 AI。 |
| Usage 与追踪 | usageMetadata、modelVersion、responseId | Token、模型版本和 Attempt 追踪 | Gemini → 轻享 AI。 |
| 错误 | HTTP 状态、error.code、error.message、error.status | 错误分类与重试判断 | Gemini → 轻享 AI；错误消息脱敏保存。 |

## 3.5 DeepSeek API 数据交换

轻享 AI 使用 DeepSeek Chat Completions 接口并通过 Bearer API Key 鉴权。实现字段以 [DeepSeek Chat Completion API](https://api-docs.deepseek.com/api/create-chat-completion/) 为依据。

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 请求头 | Authorization、Content-Type | Credential 鉴权 | 轻享 AI → DeepSeek。 |
| 对话请求 | model、messages、max_tokens、temperature、top_p、stop、stream | 统一请求协议转换 | 轻享 AI → DeepSeek。 |
| 同步响应 | id、created、model、choices、finish_reason、usage | 统一响应和实际 Usage | DeepSeek → 轻享 AI。 |
| 流式响应 | choices[].delta、finish_reason、usage、[DONE] | 统一流式事件 | DeepSeek → 轻享 AI。 |
| 错误 | HTTP 状态、error.code、error.message | 错误分类、重试与熔断统计 | DeepSeek → 轻享 AI。 |

## 3.6 外部 Secret 系统数据交换

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 密钥读取请求 | secret_ref、provider_type、credential_id | 首次调用、缓存到期和凭证检测时解析密钥 | 轻享 AI → Secret 系统；不得传递业务请求正文。 |
| 密钥读取响应 | secret_value、version、expires_at | 在内存中构造 Provider 鉴权头 | Secret 系统 → 轻享 AI；secret_value 不落库、不写日志。 |
| 读取错误 | error_code、message、retryable | 标记 Credential 暂时不可用或无效 | Secret 系统 → 轻享 AI；错误进入 ProviderCheckRecord 或 Attempt。 |

Secret SPI 的职责边界见 2.6.11。Runtime 可以对已解析密钥进行短时内存缓存，缓存不得超过密钥有效期；凭证轮换后立即使旧缓存失效。

## 3.7 宿主认证与权限系统数据交换

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 当前用户 | user_id、display_name、roles、application_scope | Embedded Admin UI 登录与功能鉴权 | 宿主系统 → 轻享 AI；角色映射为 2.4 定义的产品角色。 |
| 鉴权结果 | authenticated、denied_reason | 页面和管理接口访问控制 | 宿主系统 → 轻享 AI。 |
| 审计上下文 | user_id、display_name | 写入 AuditLog | 宿主系统 → 轻享 AI。 |

宿主系统未提供认证 SPI 时，Embedded Admin UI 默认关闭外部访问；部署方显式配置受信任网络和访问方式后方可启用。

## 3.8 外部指标与链路系统数据交换

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 指标 | request_count、success_count、latency、first_token_latency、tokens、cost、retry_count、fallback_count、circuit_state | 外部监控和告警 | 轻享 AI → 指标系统；标签只使用 application、alias、provider、model 等低基数维度。 |
| 链路 | trace_id、attempt_id、provider、model、status、duration、error_code | 跨服务链路关联 | 轻享 AI → 链路系统；不导出消息正文、密钥和完整 Provider 错误。 |

TraceExporter 按 TraceExportBatch 批量发送最终 Trace 摘要，外部系统以 batch_id 去重。外部接收失败不回滚模型调用；Runtime 按 4.6.3.6 重试并输出 exporter_failure 指标。

## 3.9 业务应用与开发者客户端数据交换

业务应用是轻享 AI 的上游调用方，位于产品部署边界之外。SDK Mode 在同一进程内调用 Runtime Core，Embedded Mode 通过注入的 LightAiClient 调用，Standalone Mode 通过 Java Client 或 HTTPS API 调用。三种方式共享 UnifiedChatRequest、UnifiedChatResponse、UnifiedUsage、StreamEvent、UnifiedError 与 trace_id 语义。

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| 调用身份 | 宿主 application、宿主安全上下文或 Authorization Bearer Token、来源 IP | 确定 application、Alias 范围和数据范围 | 业务应用 → 轻享 AI；Standalone 不接受客户端覆盖 Token 绑定的 application。 |
| 模型目录请求 | GET /v1/models 或 LightAiClient.models | 发现当前可调用 Alias 与能力 | 业务应用 → 轻享 AI；不创建业务 Trace。 |
| 模型目录响应 | UnifiedModelList | 选择 Model Alias | 轻享 AI → 业务应用；不返回 Provider、Credential、路由权重和价格配置。 |
| 对话请求 | model、messages、temperature、top_p、max_tokens、stop、stream、metadata、provider_options | 执行统一模型调用 | 业务应用 → 轻享 AI；主要字段规则见 2.6.10。 |
| 同步响应 | choices、UnifiedUsage、light_ai.trace_id、Provider 摘要、Cost、耗时 | 获取最终模型文本与调用摘要 | 轻享 AI → 业务应用。 |
| 流式响应 | UnifiedChatChunk 或 Java StreamEvent、UnifiedUsage、结束标识 | 增量消费模型文本 | 轻享 AI → 业务应用；取消向下传播并最终化 Trace。 |
| 错误响应 | code、message、retryable、trace_id | 业务错误处理与排障 | 轻享 AI → 业务应用；是否允许业务重试由 retryable 表达。 |

业务应用不得把 Provider Credential 作为统一请求字段传入。Standalone Access Token 只用于 Light AI 调用身份，不能透传给外部 Provider。trace_id 用于关联调用，不作为结果幂等键；业务应用重新发起请求会生成新的模型调用、Usage 和费用。

## 3.10 其他文本模型 Provider API 数据交换

部署方可以通过 Provider SPI 接入 V1.0 内置 Provider 之外的文本 Chat 服务。自定义 Adapter 必须把外部协议限制封装在 ProviderCapabilities、ProviderOptionSpec 和 ProviderModelDescriptor 中，Runtime Core 继续使用统一的路由、容量、可靠性、Trace、Usage 与 Cost 逻辑。自定义 Provider 不得要求业务应用在 UnifiedChatRequest 中传入 Provider Credential 或任意认证请求头。

| 数据对象 | 外部系统需接收或提供的字段 | 本系统使用场景 | 方向与处理规则 |
|---|---|---|---|
| Provider 配置 | base_url、非认证 default_headers、Adapter 自有公开配置 | 构造外部服务连接 | 轻享 AI → 自定义 Provider；认证字段由 Adapter 使用 Credential 单独注入。 |
| 模型目录 | model_id、display_name、tokenizer_family、context_window、max_output_tokens、stream、system、temperature、top_p、stop 能力与范围 | 管理员显式导入 Provider Model | 自定义 Provider → 轻享 AI；Provider 不支持目录时 Adapter 可以返回预置描述。 |
| 对话请求 | model_id、messages、temperature、top_p、max_tokens、stop、provider_options | 统一请求转换 | 轻享 AI → 自定义 Provider；只发送当前模型和 Adapter 声明支持的字段。 |
| 同步响应 | response_id、assistant 文本、finish_reason、Usage | 生成统一同步响应 | 自定义 Provider → 轻享 AI；缺少 Usage 时使用 TokenEstimator 估算。 |
| 流式响应 | 文本增量、Usage、finish_reason、结束信号 | 生成 ProviderStreamChunk | 自定义 Provider → 轻享 AI；Adapter 保持顺序并在异常时返回结构化 ProviderFailure。 |
| 错误与追踪 | HTTP 状态或等价状态、Provider 错误码、脱敏消息、请求标识 | ErrorClassification、Retry、Fallback、Circuit 与 Attempt 追踪 | 自定义 Provider → 轻享 AI；Adapter 只分类一次，不自行重试或切换 Credential。 |

自定义 Adapter 必须通过 4.7.2.6 的契约测试后才能进入运行实例。它声明的 provider_type 在实例内唯一，配置发布会校验所有 ONLINE 实例均已加载该类型；未加载时发布失败并返回 INSTANCE_VERSION_INCOMPATIBLE 或 ADAPTER_UNAVAILABLE 校验问题。

# 4. 详细功能需求

详细功能按照管理端左侧导航从上到下编排。每个页面采用“页面结构—功能流程—规则与异常—完成标准”的顺序描述。核心业务信息引用 2.6，页面控件、查询条件和接口参数在对应功能内说明；内部 DTO、异常类和框架实现参数由技术设计定义。

## 4.0 通用页面规则

管理端固定包含左侧一级导航、顶部产品名称、当前运行模式、当前配置快照、待发布变更入口和当前用户。二级页面在一级导航展开后按 2.3 的功能模块顺序显示。页面刷新不丢失筛选条件，筛选条件同步到 URL；返回列表后恢复页码、排序和筛选。

列表默认按 updated_at 或业务时间倒序，支持 page、page_size 和明确列的排序。首次加载展示骨架状态，无数据展示空状态，加载失败展示可重试错误。创建和编辑使用独立页面或右侧抽屉，离开存在未保存内容的表单时需要确认。

保存只写入草稿并生成 AuditLog，不直接改变运行快照。页面对 draft_changed 对象显示统一变更状态，顶部待发布数量实时更新。删除、停用、发布、密钥轮换、人工熔断和恢复属于高风险操作，需要确认；人工熔断与恢复必须填写原因。

管理接口统一返回 data 或 error。创建成功返回对象详情，更新成功返回最新 version，删除成功返回被删除 ID。字段校验失败返回 FIELD_VALIDATION_FAILED 和字段级 errors；权限失败返回 ACCESS_DENIED；版本冲突返回 CONFIG_VERSION_CONFLICT，并提示用户刷新后重新编辑。

## 4.1 运行概览

运行概览是管理端默认首页，按照“运行摘要—趋势分析—异常定位”的阅读顺序组织。三个区域共享同一 OverviewQuery；用户修改时间、应用、Alias 或 Provider 后，页面以同一查询版本并行刷新摘要、趋势和异常数据，任一请求返回旧查询版本时丢弃结果，避免筛选切换后出现区域数据混用。

### 4.1.1 运行摘要页

#### 4.1.1.1 页面进入与信息顺序

用户登录管理端后默认进入运行概览，也可以从左侧“运行概览—运行摘要”进入。页面从上到下依次展示公共筛选区、核心指标卡、恢复动作摘要、趋势分析和异常定位。公共筛选区从左到右为时间范围、application、Model Alias、Provider、费用币种、自动刷新与手动刷新；默认时间范围为当前页面时区最近 24 小时，application、Alias、Provider 和 currency 均为空。

时间范围支持最近 1 小时、24 小时、7 天、30 天和自定义范围。自定义范围必须位于 Trace 或 Usage 可查询保留期内，起止时间相等、开始时间晚于结束时间或跨度超过 365 天时禁止查询。最近 1 小时和 24 小时默认 granularity=HOUR，7 天、30 天及更长范围默认 granularity=DAY；用户可以在趋势区域切换服务端允许的粒度。

#### 4.1.1.2 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 公共筛选 | start_at、end_at | 时间范围选择器 | OverviewQuery | 快捷范围或自定义范围转换为 UTC 请求；页面按 RuntimeConfig.timezone 展示。 |
| 公共筛选 | application | 可搜索单选 | 当前角色 application_scope | 系统管理员、运维和只读人员可选授权范围；开发人员固定为本应用时只展示不可编辑值。 |
| 公共筛选 | alias_id | 可搜索单选 | 已发布 Model Alias | 只返回当前角色可见 Alias；选择后 Provider 选项缩小为该 Alias 候选使用的 Provider。 |
| 公共筛选 | provider_id | 可搜索单选 | 已发布 Provider | 只向具有 Provider 查看权限的角色展示；不返回 Credential 维度。 |
| 公共筛选 | currency | 单选 | 查询范围内 UsageAggregate.currency | 空值表示各币种分别展示，不计算跨币种总额。 |
| 公共筛选 | auto_refresh | 开关 | 页面状态、RuntimeConfig.dashboard_refresh_seconds | 默认开启；浏览器隐藏、离开页面或权限失效时停止轮询。 |
| 公共筛选 | refresh | 按钮 | 页面操作 | 使用当前 OverviewQuery 重新加载三个区域；加载中不可重复触发。 |
| 核心指标 | request_count | 数值卡 | OverviewSummary | 显示查询范围内 Trace 数；点击进入 Trace 列表并带入相同筛选。 |
| 核心指标 | success_rate | 百分比卡 | OverviewSummary | 保留两位小数；分母为 0 时显示“—”。 |
| 核心指标 | average_total_ms | 时长卡 | OverviewSummary | 小于 1000ms 显示毫秒，其他情况转换为秒并保留两位。 |
| 核心指标 | p95_first_token_ms | 时长卡 | OverviewSummary | 查询范围没有首 Token 样本时显示“—”。 |
| 核心指标 | total_tokens | 数值卡 | OverviewSummary | 展示总 Token，并在展开信息中分别显示 actual_tokens 与 estimated_tokens。 |
| 核心指标 | costs | 金额卡组 | OverviewSummary.costs | 每个 currency 独立显示；选定 currency 时只显示一个金额卡。 |
| 状态指标 | success_count、failure_count、stream_interrupted_count、cancelled_count、active_count | 状态摘要 | OverviewSummary | 点击任一数量进入 Trace 列表并追加对应 status 条件。 |
| 恢复动作 | retry_count、credential_failover_count、fallback_count | 数值摘要 | OverviewSummary | 点击进入 Trace 列表并追加 recovery_action 条件。 |
| 运行状态 | open_circuit_count、unavailable_candidate_count | 数值摘要 | OverviewSummary | 点击滚动到异常定位区域并应用对应 item_type。 |
| 数据状态 | data_updated_at | 时间与延迟状态 | OverviewSummary | 与当前时间相差超过两个 dashboard_refresh_seconds 时显示“数据聚合延迟”。 |

#### 4.1.1.3 查询、加载与异常流程

页面进入后先读取运行配置和可用筛选项，生成 query_version，再并行请求 summary、trends 和 exceptions。三个请求分别展示骨架状态；部分接口失败时保留已成功区域并在失败区域提供重试，不能用 0 代替失败数据。用户修改任何公共筛选项后，页面把页内 drilldown 状态清空，递增 query_version，并重新请求三个接口；旧版本响应不得覆盖新结果。

自动刷新按 dashboard_refresh_seconds 执行。每轮使用相同 OverviewQuery 替换当前结果，不把新趋势点累加到旧数组。浏览器网络恢复后立即执行一次刷新；连续三次失败后暂停自动刷新并显示最近一次成功时间，用户手动刷新成功后恢复。接口返回 ACCESS_DENIED 时清空全部概览数据并返回无权限页；返回 CONFIG_DATA_UNAVAILABLE 或 OBSERVATION_DATA_UNAVAILABLE 时显示服务不可用状态并保留筛选条件。

请求量、状态数量和恢复动作钻取到 4.4.1 Trace 列表，携带 start_at、end_at、application、alias_id、provider_id、status 或 recovery_action。费用卡钻取到 4.4.3 Usage 与 Cost 页，携带相同范围、维度和 currency。所有钻取参数都重新经过目标页面权限校验，概览页面不把隐藏 Provider 或 Credential 条件写入 URL。

#### 4.1.1.4 指标口径与完成标准

request_count、状态、success_rate、耗时和恢复动作直接查询 Trace、Attempt 与 RecoveryDecision，保证新结束调用可以在下一次刷新出现；Token 与 Cost 读取 UsageAggregate，允许存在第 5.1 节规定的聚合延迟。request_count 以 Trace.started_at 落在查询范围内为准。success_rate 的分母为 SUCCEEDED、FAILED 和 STREAM_INTERRUPTED，CANCELLED、RUNNING 与 QUEUED 不进入分母。average_total_ms 使用相同三类最终状态，p95_first_token_ms 只统计存在 first_token_at 的流式 Trace。total_tokens 与 costs 汇总全部可计费 Attempt；状态计数汇总 Trace 最终状态，因此一次包含 Retry 或 Fallback 的请求只计一个 request_count，但可以产生多个恢复动作和多笔 Attempt Cost。

相同 OverviewQuery 下，request_count 必须与 Trace 列表 total 一致，Token 与 Cost 必须与 Usage 汇总一致。页面不显示完整 client_ip、Credential ID、secret_ref、Provider 原始错误或消息正文。手动与自动刷新不能重复累计数据，筛选切换后不能出现不同 query_version 的区域组合。

### 4.1.2 趋势分析

#### 4.1.2.1 区域布局与字段

趋势分析位于运行摘要下方，默认展示请求量与成功率。区域顶部从左到右为 metric、granularity 和图表显示方式；下方为主图、图例和数据更新时间。metric 支持 REQUEST_COUNT、SUCCESS_RATE、AVERAGE_TOTAL_MS、P95_FIRST_TOKEN_MS、TOTAL_TOKENS、COST、RETRY_COUNT 和 FALLBACK_COUNT。COST 在未选择 currency 时按币种绘制多条序列，不提供跨币种求和。

| 页面区域 | 字段或控件 | 数据来源 | 展示与交互规则 |
|---|---|---|---|
| 趋势控制 | metric | 页面枚举 | 默认 REQUEST_COUNT；切换指标复用当前 points，不重复请求已包含的字段。 |
| 趋势控制 | granularity | OverviewQuery.granularity | 可选项由时间跨度决定；跨度不支持所选粒度时服务端返回 FIELD_VALIDATION_FAILED。 |
| 趋势控制 | chart_type | 页面状态 | COUNT、TOKEN、COST 使用折线或柱状图；比例与耗时使用折线图。 |
| 图表横轴 | bucket_start、bucket_end | OverviewTrendPoint | 按页面时区显示，时间桶采用左闭右开。 |
| 图表纵轴 | metric 对应值 | OverviewTrendPoint | 计数和 Token 从 0 起；比例范围 0—100%；耗时以 ms 为基础值。 |
| 图例 | currency 或状态系列 | OverviewTrendPoint | 多币种 Cost 按 currency 分系列；其他指标不拆分 Provider 或 Alias。 |
| 提示框 | 时间桶、请求量、成功数、失败数、指标值 | OverviewTrendPoint | 悬停或键盘聚焦显示；无数据补零点标记为“无调用”。 |
| 数据状态 | data_updated_at | OverviewTrendResult | 展示最近聚合时间和延迟状态。 |

#### 4.1.2.2 交互与数据流

用户切换 granularity 时，页面使用当前 OverviewQuery 重新调用趋势接口；只切换 metric 或 chart_type 时使用已返回 points 在前端重绘。点击某个时间桶时，根据当前 metric 进入 Trace 或 Usage 页面：请求、成功率、耗时、重试和 Fallback 进入 Trace，Token 和 Cost 进入 Usage；目标范围使用该点 bucket_start 与 bucket_end，不使用整页时间范围。

服务端从 HOUR 或 DAY UsageAggregate 与 Trace 状态聚合读取数据，生成完整连续桶序列。没有数据的时间桶补零；成功率分母为 0、没有流式样本的首 Token P95 和不存在对应币种的 Cost 返回 null 或 0 的规则必须与字段语义一致，前端不能把 null 显示为真实 0。当前时间尚未结束的时间桶可以返回临时值，后续刷新以相同 bucket_start 替换。

#### 4.1.2.3 完成标准

相同粒度下全部 points 按 bucket_start 升序且没有重复时间桶；各桶 request_count 之和与摘要 request_count 的差异只允许来自查询末端尚未聚合的数据，并在 data_updated_at 中体现。多币种费用始终按币种分线，点击任一数据点能够带入精确时间桶和公共筛选条件。

### 4.1.3 异常定位

#### 4.1.3.1 区域布局与字段

异常定位位于趋势分析下方，按照“当前运行状态—近期失败调用”展示。顶部摘要依次为 OPEN 熔断、HALF_OPEN 熔断、不可用候选、无效 Credential 和近期失败 Trace；列表默认最多展示 20 项，排序优先级为 OPEN 熔断、HALF_OPEN 熔断、不可用候选、无效 Credential、失败 Trace，同级按 occurrence_count desc、latest_at desc。

| 页面区域 | 字段或控件 | 数据来源 | 展示与交互规则 |
|---|---|---|---|
| 异常摘要 | open_circuit_count | OverviewExceptionSummary | 点击后列表只显示 item_type=CIRCUIT 且 status=OPEN。 |
| 异常摘要 | half_open_circuit_count | OverviewExceptionSummary | 点击后列表只显示 HALF_OPEN。 |
| 异常摘要 | unavailable_candidate_count | OverviewExceptionSummary | 点击后显示 UNAVAILABLE 或 CIRCUIT_OPEN 候选。 |
| 异常摘要 | invalid_credential_count | OverviewExceptionSummary | 只向系统管理员和运维人员展示数值；其他角色不返回该字段明细。 |
| 异常摘要 | recent_failure_trace_count | OverviewExceptionSummary | 点击后显示 FAILED 与 STREAM_INTERRUPTED Trace。 |
| 异常列表 | item_type、object_name | OverviewExceptionItem | 展示对象类型和名称；Credential 只显示配置名称。 |
| 异常列表 | status | OverviewExceptionItem | 使用状态文本，不添加额外说明标签。 |
| 异常列表 | error_code、error_summary | OverviewExceptionItem | 错误摘要脱敏并截断为 500 字符；无错误时显示状态变化原因。 |
| 异常列表 | occurrence_count、latest_at | OverviewExceptionItem | 展示范围内次数和最近发生时间。 |
| 异常列表 | related Provider、Model、Alias | OverviewExceptionItem | 仅展示当前角色有权限的关联名称，点击进入对应详情。 |
| 异常列表 | 查看详情 | 页面操作 | CIRCUIT 进入 4.3.3，候选进入 4.2.8，Credential 进入 4.2.4，TRACE 进入 4.4.2。 |

#### 4.1.3.2 操作、权限与数据流

exceptions 接口先按当前 application_scope、Alias 和 Provider 条件筛选 Trace，再读取相关 CircuitState、RouteCandidate 和 Credential 健康状态。系统管理员和运维人员可以查看 Credential 配置名称与脱敏值；开发人员只接收本应用 Trace 和可见 Alias，不返回 Credential 类型的 item；只读人员可以查看 Provider、模型、候选、熔断和 Trace 异常，不返回 Credential 类型的 item，且不能从异常项执行检测、探测、人工熔断或恢复。

异常区域只提供定位入口。连接检测在 Provider 或 Credential 页面执行，候选探测和熔断操作在熔断详情执行；用户完成操作返回概览后，页面重新请求 exceptions，不在前端直接修改状态。object_id 指向已删除历史对象时，列表使用 Trace 或 CircuitEvent 保存的名称快照并只允许进入 Trace 详情。

#### 4.1.3.3 异常与完成标准

当前状态类异常不受 start_at 限制，但必须符合 application、Alias 与 Provider 范围；recent_failure_trace_count 和 occurrence_count 受完整时间范围限制。异常对象恢复后在下一刷新周期从当前状态列表移除，历史失败 Trace 仍按时间范围保留。接口不可用时页面保留摘要与趋势，异常区域显示独立重试状态。

每一项必须至少提供 object_id、status、latest_at 和可用的定位路径；错误与对象信息不得包含 Credential 原文、完整 secret_ref、Authorization、client_ip 或消息正文。点击项后目标页面能够定位相同对象或 Trace，权限不足时目标页面返回 ACCESS_DENIED 且不泄露对象是否存在。

### 4.1.4 运行概览接口契约

| 方法与路径 | 请求 | 响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/overview/filters | 可选 alias_id | OverviewFilterOptions | 可查看角色 | ACCESS_DENIED、CONFIG_DATA_UNAVAILABLE、OBJECT_REFERENCE_INVALID |
| GET /admin/overview/summary | OverviewQuery，不使用 granularity | OverviewSummary | 可查看角色；按 application_scope 过滤 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/overview/trends | OverviewQuery，包含 granularity | OverviewTrendResult | 可查看角色；按 application_scope 过滤 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/overview/exceptions | OverviewQuery，不使用 currency 与 granularity | OverviewExceptionResult | 可查看角色；Credential 明细按角色裁剪 | FIELD_VALIDATION_FAILED、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |

三个数据接口使用同一 start_at、end_at、application、alias_id 和 provider_id 语义，并在响应头返回 X-Data-Updated-At。列表或聚合数据源暂时不可用时返回 OBSERVATION_DATA_UNAVAILABLE，不返回成功状态和空集合。请求范围无业务数据时返回字段完整的零值或空 items；权限裁剪发生在聚合前，响应数量不能包含调用方无权查看的数据。

## 4.2 模型接入

模型接入按照实际依赖顺序组织页面：先在 Provider 列表创建并检测外部连接，再进入 Credential Pool 配置凭证与容量，随后维护 Provider Model 的上下文、输出、能力、默认参数和价格，最后创建 Model Alias 并配置 Route Candidate。每个详情页提供前后步骤入口；所有配置保存为草稿，完成整个链路后从待发布变更进入配置校验与发布。

### 4.2.1 Provider 列表页

页面结构：列表按名称、类型、连接状态和启用状态筛选，表格依次显示 name、type、base_url、connection_status、关联模型数、关联凭证池数、last_check_at、enabled、draft_changed 和操作。页面主操作为“新建 Provider”，行操作为查看、编辑、检测、启用、停用和删除。

#### 4.2.1.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 name 和 base_url，输入 2—64 字符后查询，清空恢复全部。 |
| 筛选区 | type | 多选下拉 | Provider Adapter 注册表 | 选项为当前实例已加载的 Provider 类型。 |
| 筛选区 | connection_status | 多选下拉 | Provider.connection_status | 支持 UNKNOWN、AVAILABLE、UNAVAILABLE。 |
| 筛选区 | enabled | 单选下拉 | Provider.enabled | 全部、启用、停用。 |
| 筛选区 | draft_changed | 单选下拉 | Provider.draft_changed | 全部、存在未发布变更、已发布一致。 |
| 列表 | name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | type | 文本 | Provider.type | 显示 Adapter 的产品名称，悬停显示枚举值。 |
| 列表 | base_url | 省略文本 | Provider.base_url | 最多展示一行，悬停显示完整地址。 |
| 列表 | connection_status | 状态文本 | Provider.connection_status | UNKNOWN、AVAILABLE、UNAVAILABLE 使用固定文本，不用颜色代替状态文字。 |
| 列表 | provider_model_count | 数字链接 | ProviderModel 聚合 | 点击进入 Provider Model 列表并带入 provider_id。 |
| 列表 | credential_pool_count | 数字链接 | CredentialPool 聚合 | 点击进入 Credential Pool 列表并带入 provider_id。 |
| 列表 | last_check_at | 时间 | Provider.last_check_at | 为空显示“未检测”，按系统时区展示。 |
| 列表 | enabled | 开关状态 | Provider.enabled | 列表只展示，变更必须使用启停操作。 |
| 列表 | draft_changed | 变更状态 | Provider.draft_changed | true 显示“待发布”，点击进入待发布变更页。 |
| 列表 | actions | 操作菜单 | 权限与对象状态计算 | 只展示当前用户有权限且对象状态允许的操作。 |

#### 4.2.1.2 操作定义

| 操作 | 权限 | 前置条件 | 数据变化 | 成功结果 | 失败处理 |
|---|---|---|---|---|---|
| 新建 Provider | 系统管理员 | Adapter 注册表非空 | 创建 Provider 草稿和 AuditLog | 进入新对象详情，draft_changed=true | 字段错误定位到表单；类型未加载返回 PROVIDER_ADAPTER_NOT_FOUND。 |
| 查看 | 全部可查看角色 | 对象存在 | 无 | 打开详情 | 对象删除后返回 OBJECT_NOT_FOUND 并刷新列表。 |
| 编辑 | 系统管理员 | 对象存在且 version 最新 | 更新草稿、version 和 AuditLog | 返回最新详情 | 版本冲突保留本地输入并提示刷新比较。 |
| 检测 | 系统管理员、运维人员 | 存在可用 Provider Model 与 Credential | 新建 ProviderCheckRecord，更新最近检测字段 | 展示状态、耗时、Provider Request ID | 检测失败不修改 enabled，不写入配置草稿。 |
| 启用 | 系统管理员 | 当前 enabled=false | enabled=true，draft_changed=true，写 AuditLog | 列表显示待发布 | 引用不完整时允许保存，发布校验拦截。 |
| 停用 | 系统管理员 | 当前 enabled=true，已完成影响分析 | enabled=false，draft_changed=true，写 AuditLog | 受影响对象进入待发布变更 | 影响分析失败时禁止确认。 |
| 删除 | 系统管理员 | 无 Credential Pool、Provider Model 和历史生效引用 | 删除未发布对象或标记删除草稿，写 AuditLog | 返回列表 | 存在引用返回 OBJECT_IN_USE 和 blockers。 |

数据流转：列表加载时，管理接口查询 Provider 主表并分别聚合 ProviderModel 与 CredentialPool 数量，连接状态读取最近检测结果，草稿状态通过当前对象与活动快照比较得到。用户执行停用或删除时，页面先调用影响分析接口取得 ImpactAnalysis，再提交带 version 的变更命令；服务端在同一事务中修改草稿和写入 AuditLog，运行快照保持不变，直到配置发布完成。

功能流程：检测操作要求选择一个已配置模型和一个可用 Credential，系统发送最小请求并生成 ProviderCheckRecord。检测期间当前行进入检测中状态，完成后更新 connection_status、耗时和错误。停用 Provider 时页面展示受影响的 Provider Model、Credential Pool 和 Model Alias 候选数量，确认后将其排除于下一运行快照。

规则与异常：同名 Provider 不允许重复。被模型或凭证池引用的 Provider 禁止删除。connection_status 只表示最近检测结果，运行路由仍以 enabled、凭证健康、候选状态和实时容量共同判断。检测超时、鉴权失败和模型不存在需要返回不同错误码。

完成标准：可以创建四种内置 Provider 和 CUSTOM_SPI；检测记录可追溯；停用并发布后不再产生该 Provider 的新 Attempt。

接口：GET/POST /admin/providers，GET/PUT/DELETE /admin/providers/{id}，POST /admin/providers/{id}/check，POST /admin/providers/{id}/enable，POST /admin/providers/{id}/disable。

Provider 列表查询请求包含 keyword、type、connection_status、enabled、draft_changed、page、page_size 和 sort。响应为 PageResult<ProviderListItem>，ProviderListItem 由 Provider 字段加 provider_model_count、credential_pool_count 和 actions 组成。GET /admin/providers/{id}/impact 返回 ImpactAnalysis，POST /admin/providers/{id}/check 接收 ProviderCheckCommand。

### 4.2.2 Provider 新建、编辑与详情页

页面结构：表单字段顺序为 name、type、base_url、proxy_url、connect_timeout_ms、read_timeout_ms、default_headers、enabled。type 在创建后只读。详情页顶部显示连接状态和检测按钮，下方显示基础配置、关联凭证池、关联模型和最近十次检测记录。

#### 4.2.2.1 表单字段

| 字段 | 控件形式 | 新建默认值 | 编辑规则 | 提交规则 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 可编辑 | 去除首尾空格后提交，2—64 字符，全局唯一。 |
| type | 可搜索单选 | 无 | 创建后只读 | 必须来自 Adapter 注册表。 |
| base_url | URL 文本框 | Adapter 默认地址 | 可编辑 | 规范化尾部斜杠；HTTPS 为默认要求。 |
| proxy_url | URL 文本框 | 空 | 可编辑 | 空值直连；填写时校验 http 或 https 协议。 |
| connect_timeout_ms | 数字框 | 3000 | 可编辑 | 整数 100—60000。 |
| read_timeout_ms | 数字框 | 120000 | 可编辑 | 整数 1000—600000，且不小于 connect_timeout_ms。 |
| default_headers | 键值编辑器 | 空对象 | 可编辑 | 键不区分大小写判重；禁止认证和 Cookie 类请求头；最多 20 项。 |
| enabled | 开关 | true | 可编辑 | false 只改变草稿，发布后影响路由。 |
| version | 隐藏字段 | 创建时为空 | 只读 | 更新必须提交当前 version。 |

#### 4.2.2.2 详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 状态摘要 | connection_status | Provider.connection_status | 展示最近检测状态、last_check_at、last_check_latency_ms 和 last_error_code。 |
| 基础信息 | Provider 全部非敏感字段 | Provider | default_headers 展开为键值列表。 |
| 关联凭证池 | id、name、status、credential_available | CredentialPool | 默认展示前 10 条，可进入完整列表。 |
| 关联模型 | id、display_name、model_id、connection_status | ProviderModel | 默认展示前 10 条，可进入完整列表。 |
| 检测记录 | 检查时间、结果、耗时、失败摘要 | Provider Check Record | 按检查时间倒序展示最近 10 条。 |
| 审计信息 | created_by、created_at、updated_by、updated_at、version | Provider | 时间按系统时区展示。 |

#### 4.2.2.3 表单操作与数据流

| 操作 | 请求 | 服务端处理 | 页面结果 |
|---|---|---|---|
| 保存新建 | POST /admin/providers | 校验 Adapter、唯一性和字段范围；创建草稿与 AuditLog | 跳转详情并显示待发布状态。 |
| 保存编辑 | PUT /admin/providers/{id} | 校验 version；计算 FieldChange；更新草稿与 AuditLog | 用响应覆盖表单基线，清除未保存状态。 |
| 取消 | 无 | 无服务端请求 | 返回来源页；存在修改时二次确认。 |
| 检测 | POST /admin/providers/{id}/check | 解析 ProviderCheckCommand，选择指定模型和凭证，调用 Adapter，保存检测记录 | 展示检测结果并刷新状态摘要。 |
| 查看待发布差异 | GET /admin/config/draft-changes | 返回该 Provider 的 FieldChange | 打开差异抽屉，敏感值不展示。 |

数据流转：新建页面先从 Adapter 注册表接口读取 type、默认 base_url、受支持协议和参数范围。提交后，Provider 草稿写入配置存储，活动 ConfigSnapshot 不变。详情检测时，服务端按 credential_id 解析密钥，Adapter 构建最小请求并调用外部系统；响应转换为 ProviderCheckRecord，只更新运行检测字段，不改变草稿版本。发布成功后，新 Trace 才读取更新后的 Provider 配置。

功能流程：用户填写 type 后，页面加载对应 Provider Adapter 的默认 base_url、允许参数范围和保留请求头。保存前先执行前端格式校验，服务端再次校验地址、请求头和超时。保存成功返回详情并标记 draft_changed。编辑 base_url、代理或请求头后，页面提示该变更需要重新检测，但不强制检测后才能保存。

规则与异常：default_headers 的键不区分大小写判重；Authorization、x-api-key、Cookie 等认证字段禁止写入该字段。base_url 必须为合法绝对地址。CUSTOM_SPI 只有在运行环境加载对应 type 实现后才能发布。

完成标准：字段校验与 2.6.3 一致；详情关联数准确；保存、检测、启停均生成审计记录。

### 4.2.3 Credential Pool 列表页

页面结构：列表按 Provider、状态和启用状态筛选，显示 name、Provider、selection_strategy、credential_total、credential_available、current_concurrency、rpm_used、tpm_used、status、draft_changed 和操作。主操作为“新建凭证池”。

#### 4.2.3.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 CredentialPool.name。 |
| 筛选区 | provider_id | 可搜索多选 | Provider | 显示 name，值为 id；默认只加载未删除 Provider。 |
| 筛选区 | status | 多选下拉 | CredentialPool.status | AVAILABLE、PARTIAL_AVAILABLE、UNAVAILABLE、DISABLED。 |
| 筛选区 | enabled | 单选下拉 | CredentialPool.enabled | 全部、启用、停用。 |
| 列表 | name | 链接文本 | CredentialPool.name | 点击进入凭证池详情。 |
| 列表 | provider_name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | selection_strategy | 文本 | CredentialPool.selection_strategy | 展示策略中文名称与枚举值。 |
| 列表 | credential_total | 数字 | 聚合字段 | 包含所有未删除 Credential。 |
| 列表 | credential_available | 数字 | 运行状态聚合 | 只统计当前可参与选择的 Credential。 |
| 列表 | current_concurrency | 数字 | 容量组件 | 自动刷新，不进入草稿。 |
| 列表 | rpm_used | 数字 | 容量组件 | 显示当前分钟已预占请求数。 |
| 列表 | tpm_used | 数字 | 容量组件 | 显示当前分钟 Token 使用与预占量。 |
| 列表 | status | 状态文本 | CredentialPool.status | 状态文字旁展示不可用原因入口。 |
| 列表 | draft_changed | 变更状态 | CredentialPool.draft_changed | true 可跳转待发布差异。 |
| 列表 | actions | 操作菜单 | 权限与引用状态 | 查看、编辑、启用、停用、删除。 |

#### 4.2.3.2 新建与编辑字段

| 字段 | 控件形式 | 新建默认值 | 编辑规则 | 校验 |
|---|---|---|---|---|
| provider_id | 可搜索单选 | 无 | 创建后只读 | 必须指向未删除 Provider。 |
| name | 单行文本 | 空 | 可编辑 | 2—64 字符，同一 Provider 下唯一。 |
| selection_strategy | 单选卡片 | LEAST_CONCURRENT | 可编辑 | 只能选择 LEAST_CONCURRENT、ROUND_ROBIN、WEIGHTED_RANDOM。 |
| enabled | 开关 | true | 可编辑 | 停用前必须完成影响分析。 |
| version | 隐藏字段 | 空 | 只读 | 更新时必填。 |

#### 4.2.3.3 操作与数据流

| 操作 | 前置条件 | 数据变化 | 结果与异常 |
|---|---|---|---|
| 新建 | 系统管理员已选择 Provider | 创建 CredentialPool 草稿和 AuditLog | 成功进入详情；Provider 不存在返回 OBJECT_REFERENCE_INVALID。 |
| 编辑 | version 最新 | 更新字段和 version，写 FieldChange | 策略变化发布后只影响新 Credential 选择。 |
| 启用 | enabled=false | 写 enabled=true 草稿 | 池内无 Credential 时允许保存，发布时校验引用它的启用候选。 |
| 停用 | enabled=true，影响分析成功 | 写 enabled=false 草稿 | 显示受影响 Route Candidate 和 Model Alias。 |
| 删除 | 池内无 Credential 且无候选引用 | 删除草稿或写删除变更 | 存在引用返回 OBJECT_IN_USE。 |

数据流转：列表请求同时读取配置存储中的 CredentialPool、Provider 名称和容量组件中的实时统计。实时字段不参与 version 和 draft_changed。新建、编辑、启停和删除只修改配置草稿；发布构建 Route Candidate 时重新验证 provider_id 关系，运行实例加载快照后才改变凭证选择范围。

功能流程：创建时先选择 Provider，再填写名称和 selection_strategy。点击池名称进入详情页。停用池前展示引用该池的 Route Candidate；确认保存并发布后，这些候选显示 UNAVAILABLE，路由器不会选择池内 Credential。

规则与异常：一个 Provider 可以包含多个池；池不能跨 Provider 使用。存在 Credential 或候选引用时禁止删除。凭证池状态由 enabled 与池内凭证可用数量计算，不允许人工直接修改。

完成标准：池容量汇总与池内 Credential 数据一致；选择策略发布后，新请求使用新策略，正在执行的 Attempt 不迁移。

接口：GET/POST /admin/credential-pools，GET/PUT/DELETE /admin/credential-pools/{id}，POST /admin/credential-pools/{id}/enable，POST /admin/credential-pools/{id}/disable。

列表接口请求包含 keyword、provider_id、status、enabled、page、page_size 和 sort，响应为 PageResult<CredentialPoolListItem>。GET /admin/credential-pools/{id}/impact 返回 ImpactAnalysis。

### 4.2.4 Credential Pool 详情与 Credential 管理页

页面结构：详情顶部展示凭证池基础信息和容量摘要，下方 Credential 表格依次显示 name、masked_value、secret_source、weight、RPM、TPM、并发上限、current_concurrency、health_status、rate_limit_reset_at、last_success_at、last_check_at、draft_changed 和操作。主操作为“新增 Credential”，行操作为编辑、轮换密钥、检测、启用、停用和删除。

#### 4.2.4.1 凭证池详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 基础信息 | id、name、provider_id、selection_strategy、enabled、version | CredentialPool | provider_id 显示 Provider 名称并可跳转。 |
| 容量摘要 | credential_total、credential_available、current_concurrency、rpm_used、tpm_used、status | 配置聚合与容量组件 | 每 10 秒刷新；刷新不改变表单。 |
| 引用关系 | route_candidate_count、model_alias_count | RouteCandidate 聚合 | 点击进入候选或 Alias 列表。 |
| 审计信息 | created_by、created_at、updated_by、updated_at、draft_changed | CredentialPool | draft_changed 可查看 FieldChange。 |

#### 4.2.4.2 Credential 列表字段

| 字段 | 数据来源 | 展示规则 | 可排序 |
|---|---|---|---|
| name | Credential.name | 点击打开编辑抽屉。 | 是 |
| masked_value | Credential.masked_value | 固定脱敏；EXTERNAL_REF 同时显示 secret_ref 的非敏感标识。 | 否 |
| secret_source | Credential.secret_source | 显示“加密存储”或“外部引用”。 | 是 |
| weight | Credential.weight | 仅 WEIGHTED_RANDOM 时突出显示。 | 是 |
| rpm_limit | Credential.rpm_limit | 空值显示“不限制”。 | 是 |
| tpm_limit | Credential.tpm_limit | 空值显示“不限制”。 | 是 |
| concurrent_limit | Credential.concurrent_limit | 空值显示“不限制”。 | 是 |
| current_concurrency | 容量组件 | 当前执行中 Attempt 数，每 10 秒刷新。 | 是 |
| health_status | Credential.health_status | 展示状态和最近错误入口。 | 是 |
| rate_limit_reset_at | Credential.rate_limit_reset_at | 非 RATE_LIMITED 时显示空。 | 是 |
| last_success_at | Credential.last_success_at | 无成功记录显示“暂无”。 | 是 |
| last_check_at | Credential.last_check_at | 无检测记录显示“未检测”。 | 是 |
| enabled | Credential.enabled | 只读展示，启停通过操作提交。 | 是 |
| draft_changed | Credential.draft_changed | true 显示待发布。 | 是 |
| actions | 权限和状态计算 | 编辑、轮换、检测、启用、停用、删除。 | 否 |

#### 4.2.4.3 Credential 表单字段

| 字段 | 控件形式 | 新建默认值 | 可见与编辑规则 | 校验与持久化 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 新建、编辑可见 | 2—64 字符，同一 pool_id 下唯一。 |
| secret_source | 单选 | INLINE_ENCRYPTED | 新建可选，创建后只读 | 决定 secret_value 或 secret_ref 的必填规则。 |
| secret_value | 密码输入框 | 空 | 仅新建或轮换可见 | 1—4096 字符，加密后保存；响应不返回。 |
| secret_value_confirm | 密码输入框 | 空 | 仅 INLINE_ENCRYPTED 新建或轮换可见 | 必须与 secret_value 完全一致，不持久化。 |
| secret_ref | 单行文本 | 空 | EXTERNAL_REF 时可见；编辑可修改 | 1—512 字符；只保存引用，不解析后回显值。 |
| masked_value | 只读文本 | 保存后生成 | 详情和列表可见 | 服务端计算，不接受提交。 |
| weight | 数字框 | 1 | 始终可编辑 | 1—100。 |
| rpm_limit | 数字框 | 空 | 始终可编辑 | 空或正整数。 |
| tpm_limit | 数字框 | 空 | 始终可编辑 | 空或正整数。 |
| concurrent_limit | 数字框 | 空 | 始终可编辑 | 空或 1—100000。 |
| enabled | 开关 | true | 始终可编辑 | false 发布后停止选择。 |
| version | 隐藏字段 | 空 | 编辑时只读 | 更新、轮换、启停必须提交。 |

#### 4.2.4.4 Credential 操作定义

| 操作 | 权限与前置条件 | 服务端数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新增 | 系统管理员；凭证池存在 | 创建 Credential 草稿；加密 secret_value 或保存 secret_ref；写 AuditLog | 返回不含密钥的 Credential。 |
| 编辑 | 系统管理员；version 最新 | 更新名称、引用、权重和限额；不接受 secret_value | 刷新行数据；密钥变更必须使用轮换。 |
| 轮换密钥 | 系统管理员；INLINE_ENCRYPTED | 加密新值、更新 masked_value 与 version、清除相关短时缓存、写 AuditLog | 只显示轮换成功，不返回新密钥。 |
| 检测 | 系统管理员、运维人员；已选择 Provider Model | 解析密钥并调用 Adapter；新建 ProviderCheckRecord；更新 health_status | 展示耗时、请求 ID 和错误；不生成草稿。 |
| 启用 | 系统管理员；enabled=false | enabled=true，health_status=UNKNOWN，draft_changed=true | 提示发布后参与选择。 |
| 停用 | 系统管理员；enabled=true | enabled=false，draft_changed=true | 发布后停止新选择；运行中 Attempt 继续。 |
| 删除 | 系统管理员；无运行中 Attempt，且池中仍满足候选可用性或候选已停用 | 删除未发布对象或记录删除草稿 | 条件不满足返回 OBJECT_IN_USE 或 CAPACITY_IN_USE。 |

数据流转：创建 INLINE_ENCRYPTED Credential 时，前端只通过 HTTPS 提交一次 secret_value，服务端加密后写入密钥字段并生成 masked_value，事务完成后清除请求对象中的明文引用。EXTERNAL_REF 只保存 secret_ref。运行时从已发布快照取得 credential_id，在发起 Provider 请求前解析密钥并写入内存请求头；检测和业务调用都不把密钥写入 ProviderCheckRecord、Trace、Attempt、AuditLog 或日志。

检测数据流：用户先选择 provider_model_id，页面提交 ProviderCheckCommand。服务端验证模型与凭证属于同一 Provider，预占检测并发，解析 Credential，调用 Adapter 最小请求，分类结果并保存 ProviderCheckRecord，随后更新 Credential.health_status、last_check_at 和 last_error_code。检测不计入业务 Usage 和 Cost，但产生独立运行指标。

功能流程：新增 Credential 时选择 secret_source。INLINE_ENCRYPTED 要求输入 secret_value 并二次确认；EXTERNAL_REF 要求输入 secret_ref。保存后页面只显示 masked_value。轮换密钥打开独立表单，新密钥保存成功后旧密钥立即失效，运行中的 Attempt 保持其已取得的内存值。检测时选择池所属 Provider 的一个模型，执行最小调用并更新 health_status。

规则与异常：secret_value 和 secret_ref 互斥。读取接口永远不返回 secret_value、token_hash 或已解析的外部 Secret。INVALID 由明确鉴权失败产生；RATE_LIMITED 在复位时间前不参与选择；UNKNOWN 可以参与选择，但优先级低于 HEALTHY；DISABLED 永不参与选择。运行请求产生明确成功结果后可以把 UNKNOWN 或暂时不可用状态恢复为 HEALTHY。

选择规则：LEAST_CONCURRENT 选择当前并发占上限比例最低的 Credential；ROUND_ROBIN 在可用 Credential 间轮转；WEIGHTED_RANDOM 根据 weight 随机。任一策略在选择前都过滤停用、INVALID、未复位的 RATE_LIMITED、并发已满和分钟容量不足的 Credential。

完成标准：密钥明文不出现在详情、网络读取响应、日志、Trace 和 AuditLog；停用或判定无效的 Credential 不产生新 Attempt；并发计数在成功、失败、取消和超时后均正确归还。

接口：GET/POST /admin/credential-pools/{poolId}/credentials，GET/PUT/DELETE /admin/credentials/{id}，POST /admin/credentials/{id}/rotate，POST /admin/credentials/{id}/check，POST /admin/credentials/{id}/enable，POST /admin/credentials/{id}/disable。

### 4.2.5 Provider Model 列表页

页面结构：列表按 Provider、模型标识、连接状态、流式能力和启用状态筛选，显示 display_name、model_id、Provider、context_window、max_output_tokens、support_stream、input_price、output_price、currency、connection_status、draft_changed 和操作。主操作为“新建模型”。

#### 4.2.5.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 display_name 和 model_id。 |
| 筛选区 | provider_id | 可搜索多选 | Provider | 显示 Provider.name。 |
| 筛选区 | connection_status | 多选下拉 | ProviderModel.connection_status | UNKNOWN、AVAILABLE、UNAVAILABLE。 |
| 筛选区 | support_stream | 单选下拉 | ProviderModel.support_stream | 全部、支持、不支持。 |
| 筛选区 | enabled | 单选下拉 | ProviderModel.enabled | 全部、启用、停用。 |
| 列表 | display_name | 链接文本 | ProviderModel.display_name | 点击进入模型详情。 |
| 列表 | model_id | 等宽文本 | ProviderModel.model_id | 保持大小写原值，可复制。 |
| 列表 | provider_name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | context_window | 数字 | ProviderModel.context_window | 使用千分位，不缩写。 |
| 列表 | max_output_tokens | 数字 | ProviderModel.max_output_tokens | 使用千分位。 |
| 列表 | support_stream | 是/否文本 | ProviderModel.support_stream | 显示明确文本。 |
| 列表 | input_price | 金额 | ProviderModel.input_price | 与 price_unit、currency 组合显示。 |
| 列表 | output_price | 金额 | ProviderModel.output_price | 与 price_unit、currency 组合显示。 |
| 列表 | connection_status | 状态文本 | ProviderModel.connection_status | 同时显示 last_check_at。 |
| 列表 | route_candidate_count | 数字链接 | RouteCandidate 聚合 | 点击查看引用该模型的候选。 |
| 列表 | enabled | 状态文本 | ProviderModel.enabled | 启停通过操作提交。 |
| 列表 | draft_changed | 变更状态 | ProviderModel.draft_changed | true 显示待发布。 |
| 列表 | actions | 操作菜单 | 权限和引用状态 | 查看、编辑、检测、启停、删除。 |

#### 4.2.5.2 模型导入字段与流程

| 字段或控件 | 控件形式 | 来源 | 规则 |
|---|---|---|---|
| provider_id | 单选下拉 | Provider | 必须选择一个启用或草稿启用的 Provider。 |
| source | 单选 | ProviderModelImportCommand.source | 默认 PROVIDER_API；Adapter 不支持模型列表时只允许 ADAPTER_PRESET。 |
| credential_id | 单选下拉 | Credential | PROVIDER_API 时必填，只显示同 Provider 的非停用 Credential。 |
| keyword | 文本框 | 页面状态 | 在候选 model_id 和 display_name 中过滤。 |
| candidate_table | 多选表格 | ProviderModelImportCandidate | 展示 model_id、来源、已存在、tokenizer、上下文、最大输出、流式、system、temperature、top_p 与 stop 能力默认值；未知字段明确显示“待补充”。 |
| apply_known_defaults | 开关 | ModelImportCommand | 默认 true；false 时能力字段留空并要求逐个补全。 |
| enabled | 开关 | ModelImportCommand | 默认 false，避免未经核验直接进入启用草稿。 |

数据流转：用户选择 Provider 和来源后，页面调用 GET /admin/providers/{id}/available-models。PROVIDER_API 模式由服务端解析 credential_id 并通过 Adapter 调用外部模型列表接口；ADAPTER_PRESET 模式读取当前 Adapter 版本内置目录。服务端把外部结果转换为 ProviderModelImportCandidate，并通过 provider_id + model_id 标记 existing。用户提交选中 model_ids 后，服务端在一个事务中跳过已存在项、创建其余 ProviderModel 草稿、写入逐对象 AuditLog，并返回 created、skipped 和 failed 明细。

当 Provider API 只返回 model_id 时，context_window、max_output_tokens 和能力字段保持空值，导入结果不能发布为 enabled=true，管理员必须在编辑页补齐。Adapter 预置默认值用于减少录入，不代表 Provider 实时承诺，页面需要显示默认值来源。

#### 4.2.5.3 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 结果与异常 |
|---|---|---|---|
| 新建模型 | 系统管理员；存在 Provider | 创建单个 ProviderModel 草稿 | 进入编辑详情。 |
| 导入模型 | 系统管理员；Provider 与来源可用 | 批量创建 ProviderModel 草稿和 AuditLog | 返回逐项结果，重复项跳过。 |
| 查看 | 可查看角色；对象存在 | 无 | 打开详情。 |
| 编辑 | 系统管理员；version 最新 | 更新模型草稿 | 参数与价格变更只影响发布后的新 Attempt。 |
| 检测 | 系统管理员、运维人员；选择同 Provider Credential | 新建 ProviderCheckRecord，更新检测字段 | 展示耗时、Usage 和 Provider Request ID。 |
| 批量检测 | 系统管理员、运维人员；选中 1—20 个同 Provider 模型并指定 Credential | 以最大并发 3 逐个检测，每个模型独立写记录 | 展示成功、失败和未执行数量；单项失败不终止其余项。 |
| 启用或停用 | 系统管理员；完成影响分析 | 更新 enabled 草稿 | 发布后改变候选过滤结果。 |
| 删除 | 系统管理员；无 Route Candidate 引用 | 删除草稿或记录删除变更 | 有引用返回 OBJECT_IN_USE。 |

功能流程：用户先选择 Provider，再填写模型基本能力、默认参数和价格。检测操作要求选择该 Provider 下的可用 Credential，发送最小消息并记录响应模型、耗时、Usage 和错误。停用模型时展示引用它的 Route Candidate 数量。

规则与异常：context_window 必须大于 max_output_tokens。default_max_tokens 不得超过 max_output_tokens。temperature 和 top_p 的值需要同时满足统一规则与 Adapter 能力。价格修改只影响修改后完成的 Attempt，历史费用保持原值。模型检测成功不自动覆盖管理员填写的上下文和价格。

完成标准：模型页面完整呈现 2.6.5 的上下文、能力、参数和计费字段；保存后的运行请求应用默认参数；停用并发布后不产生该模型的新 Attempt。

接口：GET/POST /admin/provider-models，GET/PUT/DELETE /admin/provider-models/{id}，POST /admin/provider-models/{id}/check，POST /admin/provider-models/{id}/enable，POST /admin/provider-models/{id}/disable。

模型导入增加 GET /admin/providers/{id}/available-models、POST /admin/provider-models/import 和 POST /admin/provider-models/batch-check。导入接口接收 ProviderModelImportCommand，返回 created_items、skipped_items 和 failed_items；批量检测接收 provider_model_ids、credential_id、mode 和 timeout_ms。

### 4.2.6 Provider Model 新建、编辑与详情页

页面结构：表单分为基础信息、能力、默认生成参数和价格四个连续区块。字段顺序为 provider_id、display_name、model_id、model_type、tokenizer_family、context_window、max_output_tokens、support_stream、support_system_message、default_temperature、default_top_p、default_max_tokens、default_stop、input_price、output_price、price_unit、currency、enabled。详情页增加关联 Model Alias 和最近检测记录。

#### 4.2.6.1 表单字段

| 区域 | 字段 | 控件形式 | 默认值 | 编辑与校验规则 |
|---|---|---|---|---|
| 基础信息 | provider_id | 可搜索单选 | 从入口带入或空 | 创建后只读；必须指向有效 Provider。 |
| 基础信息 | display_name | 单行文本 | 导入名称或空 | 2—64 字符，同一 Provider 下唯一。 |
| 基础信息 | model_id | 单行文本 | 导入标识或空 | 1—128 字符，保持大小写；同一 Provider 下唯一。 |
| 基础信息 | model_type | 只读单选 | CHAT_TEXT | V1.0 固定为 CHAT_TEXT。 |
| 能力 | tokenizer_family | Adapter 选项单选 | Adapter 默认值 | 必填；只能选择当前 Provider Adapter 声明的 TokenEstimator。 |
| 能力 | context_window | 数字框 | Adapter 已知值或空 | 必填正整数；必须大于 max_output_tokens。 |
| 能力 | max_output_tokens | 数字框 | Adapter 已知值或空 | 必填，范围 1—context_window。 |
| 能力 | support_stream | 开关 | Adapter 已知值或 true | 必填；关闭后流式请求过滤该模型。 |
| 能力 | support_system_message | 开关 | Adapter 已知值或 true | 必填；关闭后 system 请求过滤该模型。 |
| 参数能力 | support_temperature | 开关 | Adapter 已知值 | Adapter 不支持时固定为 false；false 时隐藏 temperature 范围与默认值。 |
| 参数能力 | temperature_min | 小数框 | Adapter 下限 | support_temperature=true 时必填，不得超出 Adapter 范围。 |
| 参数能力 | temperature_max | 小数框 | Adapter 上限 | support_temperature=true 时必填，必须大于等于 temperature_min。 |
| 参数能力 | support_top_p | 开关 | Adapter 已知值 | Adapter 不支持时固定为 false；false 时隐藏 top_p 范围与默认值。 |
| 参数能力 | top_p_min | 小数框 | Adapter 下限 | support_top_p=true 时必填，范围 0—1。 |
| 参数能力 | top_p_max | 小数框 | Adapter 上限 | support_top_p=true 时必填，范围 0—1 且大于等于 top_p_min。 |
| 参数能力 | support_stop | 开关 | Adapter 已知值 | Adapter 不支持时固定为 false；false 时隐藏 stop 上限与默认值。 |
| 参数能力 | max_stop_sequences | 数字框 | Adapter 上限与 4 的较小值 | support_stop=true 时必填，范围 1—4。 |
| 参数能力 | max_stop_length | 数字框 | Adapter 上限与 128 的较小值 | support_stop=true 时必填，范围 1—128。 |
| 默认参数 | default_temperature | 小数框 | 空 | 空或模型 temperature_min—temperature_max；请求值优先。 |
| 默认参数 | default_top_p | 小数框 | 空 | 空或模型 top_p_min—top_p_max；请求值优先。 |
| 默认参数 | default_max_tokens | 数字框 | 空 | 空或 1—max_output_tokens。 |
| 默认参数 | default_stop | 可增删文本列表 | 空 | 项数不超过 max_stop_sequences，每项不超过 max_stop_length，去重后提交。 |
| 价格 | input_price | 金额框 | 0 | decimal(20,8)，不得小于 0。 |
| 价格 | output_price | 金额框 | 0 | decimal(20,8)，不得小于 0。 |
| 价格 | price_unit | 单选 | 1000000 | 只能为 1000 或 1000000。 |
| 价格 | currency | 可搜索单选 | USD | ISO 4217 三位代码。 |
| 状态 | enabled | 开关 | 新建 true、导入 false | 启用模型必须补齐所有必填能力字段。 |
| 系统 | version | 隐藏字段 | 空 | 更新时提交当前 version。 |

#### 4.2.6.2 参数合并顺序

一次请求的生成参数按照“UnifiedChatRequest 显式值、Provider Model 默认值、Provider 默认行为”的顺序确定。temperature、top_p、max_tokens 和 stop 分别独立合并，调用方未填写某字段时才读取模型默认值。调用方显式值和模型默认值都必须落在 Provider Model 声明的支持状态与范围内；Adapter 能力是模型范围的上界。provider_options 只接受 Adapter 声明的白名单字段，不能覆盖 model、messages、stream 和认证请求头。

temperature 和 top_p 在各自 support 字段为 true 时按模型 min、max 校验。stop 只有在 support_stop=true 时可用，列表项数与单项长度分别受 max_stop_sequences 和 max_stop_length 限制。max_tokens 合并完成后，运行时先校验不超过 max_output_tokens，再与估算 input_tokens 相加校验 context_window。候选模型的参数范围不一致时，路由器过滤不兼容候选；所有候选均不兼容时返回 MODEL_CAPABILITY_NOT_SUPPORTED 或 CONTEXT_WINDOW_EXCEEDED。

#### 4.2.6.3 详情字段与操作

| 区域或操作 | 字段或输入 | 数据流与结果 |
|---|---|---|
| 状态摘要 | connection_status、last_check_at、last_error_code | 来源于最近 ProviderCheckRecord，不进入配置草稿。 |
| 关联 Alias | alias、priority、weight、credential_pool、candidate_status | 来源于 RouteCandidate，点击进入 Alias 候选行。 |
| 检测记录 | 检查时间、结果、耗时、失败摘要 | 按检查时间倒序展示最近 20 条。 |
| 保存 | ProviderModel 可编辑字段、version | 服务端校验组合约束，写草稿、FieldChange 和 AuditLog。 |
| 检测 | credential_id、mode、timeout_ms | Adapter 发起最小请求，记录响应模型、Token、耗时和错误。 |
| 查看用量 | provider_model_id、时间范围 | 跳转 Usage 页面并带入模型筛选。 |
| 查看调用 | provider_model_id、时间范围 | 跳转 Trace 页面并带入模型筛选。 |

数据流转：保存模型时，配置存储记录管理员确认的上下文、能力、默认参数和价格。配置发布把这些字段写入不可变 ConfigSnapshot。运行请求建立 Trace 后固定 snapshot_no，路由阶段读取模型能力过滤候选，参数合并后由 Adapter 转换外部请求；Provider 返回 Usage 后，Attempt 使用快照中的 input_price、output_price、price_unit 和 currency 固化 cost，因此后续价格编辑不会回算历史记录。

功能流程：选择 Provider 后页面加载 Adapter 参数能力。用户保存时，服务端对组合约束进行校验。详情页点击某个关联 Alias 可进入 Alias 详情，并定位到引用该模型的候选行。

规则与异常：模型标识区分大小写并按原值传给 Provider；同一 Provider 下禁止重复。support_stream 为 false 时，包含该模型的候选不能处理 stream=true 请求。support_system_message 为 false 时，含 system 消息的请求过滤该候选；没有其他候选时返回 MODEL_CAPABILITY_NOT_SUPPORTED。

完成标准：模型字段和运行能力过滤一致；编辑模型标识、上下文和能力后必须通过发布才生效；历史 Trace 保留当时的 provider_model_id 和模型标识快照。

### 4.2.7 Model Alias 列表页

页面结构：列表按 alias、启用状态和运行可用性筛选，显示 alias、display_name、route_strategy、candidate_count、available_candidate_count、support_stream、enabled、draft_changed、updated_at 和操作。主操作为“新建 Model Alias”。

#### 4.2.7.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 alias、display_name 和 description。 |
| 筛选区 | enabled | 单选下拉 | ModelAlias.enabled | 全部、启用、停用。 |
| 筛选区 | runtime_availability | 单选下拉 | available_candidate_count 计算 | 全部、可调用、无可用候选。 |
| 筛选区 | support_stream | 单选下拉 | ModelAlias.support_stream | 全部、支持、不支持。 |
| 列表 | alias | 链接与复制控件 | ModelAlias.alias | 点击进入详情，复制只复制原始字符串。 |
| 列表 | display_name | 文本 | ModelAlias.display_name | 单行展示。 |
| 列表 | route_strategy | 文本 | ModelAlias.route_strategy | V1.0 显示 PRIORITY_WEIGHTED。 |
| 列表 | candidate_count | 数字 | ModelAlias.candidate_count | 点击进入候选区域。 |
| 列表 | available_candidate_count | 数字 | ModelAlias.available_candidate_count | 实时刷新；为 0 时显示不可调用原因入口。 |
| 列表 | support_stream | 状态文本 | ModelAlias.support_stream | stream_candidate_count 小于 candidate_count 时显示“部分支持”。 |
| 列表 | request_count_24h | 数字链接 | Trace 聚合 | 点击进入最近 24 小时 Trace。 |
| 列表 | enabled | 状态文本 | ModelAlias.enabled | 启停通过操作执行。 |
| 列表 | draft_changed | 变更状态 | ModelAlias.draft_changed | true 显示待发布。 |
| 列表 | updated_at | 时间 | ModelAlias.updated_at | 按系统时区展示。 |
| 列表 | actions | 操作菜单 | 权限与引用状态 | 查看、编辑、启用、停用、删除。 |

#### 4.2.7.2 新建与编辑字段

| 字段 | 控件形式 | 默认值 | 编辑规则 | 校验 |
|---|---|---|---|---|
| alias | 单行等宽文本 | 空 | 创建后只读 | 2—64 字符，只允许字母、数字、点、短横线和下划线，全局唯一。 |
| display_name | 单行文本 | 空 | 可编辑 | 2—64 字符。 |
| description | 多行文本 | 空 | 可编辑 | 最多 500 字符，不用于模型 Prompt。 |
| route_strategy | 只读单选 | PRIORITY_WEIGHTED | V1.0 只读 | 固定优先级内按权重选择。 |
| enabled | 开关 | true | 可编辑 | enabled=true 的 Alias 发布时必须至少有一个启用且引用完整的候选。 |
| version | 隐藏字段 | 空 | 只读 | 更新时必填。 |

#### 4.2.7.3 操作定义与数据流

| 操作 | 前置条件 | 数据变化 | 成功结果 | 失败处理 |
|---|---|---|---|---|
| 新建 | 系统管理员；alias 唯一 | 创建 ModelAlias 草稿和 AuditLog | 进入详情并引导新增候选 | 重名返回 FIELD_VALIDATION_FAILED。 |
| 编辑 | version 最新 | 更新 display_name、description、enabled 和 version | 返回最新详情 | alias 字段即使提交也被拒绝。 |
| 启用 | 当前停用 | enabled=true 草稿 | 提示配置候选并发布 | 无候选允许保存，发布校验失败。 |
| 停用 | 当前启用，影响分析完成 | enabled=false 草稿 | 展示最近调用与 Access Credential 影响 | 影响分析失败禁止提交。 |
| 删除 | 无治理策略和 Access Credential 引用，活动快照未启用 | 删除草稿或记录删除变更 | 返回列表 | blockers 非空返回 OBJECT_IN_USE。 |
| 查看调用 | 对象存在 | 无 | 跳转 Trace 并带入 alias 和时间范围 | 无数据展示空状态。 |

数据流转：Alias 保存后只存在于配置草稿，新增候选后形成 Alias → RouteCandidate → ProviderModel/CredentialPool 的完整引用。发布校验把 Alias 与全部候选作为一个聚合检查，并写入同一 ConfigSnapshot。业务请求的 model 字段只解析已发布 Alias；Alias 的展示名称和描述不进入外部 Provider 请求。

功能流程：创建时填写 alias、display_name、description 和 enabled，保存后进入详情继续配置候选。停用时展示最近 24 小时调用量和使用该 Alias 的 Standalone Access Credential 数量，确认并发布后调用返回 MODEL_ALIAS_DISABLED。

规则与异常：alias 创建后不可修改，避免业务调用入口变化；如需更名，应创建新 Alias 并迁移接入方。没有候选的 Alias 可以保存草稿，但不能发布为启用状态。删除前必须确认没有 Access Credential 和治理策略引用，且最近发布快照中未启用。

完成标准：已发布 Alias 可以通过 GET /v1/models 查询；停用或不存在的 Alias 返回明确错误；列表可直接判断候选是否具备运行可用性。

接口：GET/POST /admin/model-aliases，GET/PUT/DELETE /admin/model-aliases/{id}，POST /admin/model-aliases/{id}/enable，POST /admin/model-aliases/{id}/disable。

列表查询请求包含 keyword、enabled、runtime_availability、support_stream、page、page_size 和 sort。GET /admin/model-aliases/{id}/impact 返回 ImpactAnalysis，结果增加引用它的 ReliabilityPolicy、LimitPolicy 和 StandaloneAccessCredential 标识。

### 4.2.8 Model Alias 详情与候选路由页

页面结构：详情顶部展示 Alias 基础信息、当前发布快照和运行可用状态。候选表格按 priority 升序排列，同优先级按权重展示，字段为 Provider、Provider Model、Credential Pool、priority、weight、能力、current_concurrency、runtime_status、excluded_reason、draft_changed 和操作。页面支持新增、编辑、启停、删除候选和执行探测。

#### 4.2.8.1 Alias 详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 基础信息 | id、alias、display_name、description、route_strategy、enabled、version | ModelAlias | alias 可复制，route_strategy 只读。 |
| 能力摘要 | support_stream、stream_candidate_count、candidate_count | ModelAlias 聚合 | 展示支持流式的候选比例。 |
| 运行摘要 | available_candidate_count、request_count_24h、success_rate_24h、p95_total_ms_24h | 候选实时状态与 Trace 聚合 | 每 30 秒刷新，不写入草稿。 |
| 配置状态 | current_snapshot_no、draft_changed、updated_by、updated_at | RuntimeConfig 与 ModelAlias | 可进入待发布差异。 |
| 治理策略 | LimitPolicy、ReliabilityPolicy 摘要 | 治理实体 | 未配置时显示系统默认可靠性参数入口。 |

#### 4.2.8.2 Route Candidate 列表与表单字段

| 字段 | 控件或展示形式 | 新建默认值 | 数据来源与规则 |
|---|---|---|---|
| provider_model_id | 分组搜索单选 | 空 | 表单按 Provider 分组显示启用或草稿启用模型；选中后只读展示模型能力。 |
| credential_pool_id | 单选 | 空 | 只显示与 provider_model_id 同 Provider 的凭证池。 |
| provider_name | 只读文本 | 由模型计算 | 来源于 Provider.name。 |
| provider_model_name | 链接文本 | 由模型计算 | 显示 display_name 与 model_id，可进入模型详情。 |
| credential_pool_name | 链接文本 | 由池计算 | 显示名称、可用凭证数和池状态。 |
| priority | 数字框 | 10 | 1—100，数值越小越优先。 |
| weight | 数字框 | 1 | 1—100，只在同 priority 可用集合内计算。 |
| enabled | 开关 | true | false 发布后排除。 |
| support_stream | 只读状态 | 由模型计算 | 来源于 ProviderModel.support_stream。 |
| support_system_message | 只读状态 | 由模型计算 | 来源于 ProviderModel.support_system_message。 |
| context_window | 只读数字 | 由模型计算 | 来源于 ProviderModel.context_window。 |
| current_concurrency | 只读数字 | 0 | 来自运行容量组件，定时刷新。 |
| runtime_status | 只读状态 | UNAVAILABLE | 由启停、引用、凭证、熔断和容量共同计算。 |
| excluded_reason | 只读文本 | 空 | 只在 runtime_status 非 AVAILABLE 时展示。 |
| version | 隐藏字段 | 空 | 更新时提交。 |
| draft_changed | 只读状态 | true | 与活动快照比较得到。 |

#### 4.2.8.3 候选操作定义

| 操作 | 权限与前置条件 | 数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新增候选 | 系统管理员；已选择同 Provider 模型与池 | 创建 RouteCandidate 草稿和 AuditLog | 插入对应 priority 分组。 |
| 编辑候选 | 系统管理员；version 最新 | 更新 pool、priority、weight、enabled 和 version | 重新排序并显示待发布。 |
| 调整顺序 | 系统管理员；选中一个或多个候选 | 批量更新 priority 和 version | 全部成功后重排；任一版本冲突则整批不提交。 |
| 启用或停用 | 系统管理员 | 更新 enabled 草稿 | 发布后改变候选集合。 |
| 删除 | 系统管理员；对象存在 | 删除草稿或记录删除变更 | Alias 启用且删除后无候选时允许保存，发布校验拦截。 |
| 探测 | 系统管理员、运维人员；候选引用完整且池内有可用 Credential | 选择一个 Credential，执行 ProviderCheckCommand，写 ProviderCheckRecord | 显示实际 Credential 脱敏值、耗时、Token 和错误。 |
| 查看 Trace | 可查看角色 | 无 | 跳转 Trace 并带入 route_candidate_id；无数据时展示空状态。 |

#### 4.2.8.4 候选配置数据流

新增或编辑候选时，前端先根据 provider_model_id 请求可选凭证池，服务端再次校验 ProviderModel.provider_id 与 CredentialPool.provider_id 相同，并校验相同 alias_id、provider_model_id、credential_pool_id 组合不存在。保存操作只写 RouteCandidate 草稿和 AuditLog。

批量调整顺序提交 items，每项包含 id、priority 和 version。服务端在单个事务中校验所有版本与范围，任一项失败时全部回滚。权重不通过拖动隐式修改，只有显式编辑 weight 才产生 FieldChange。

发布时，服务端从 Alias 开始装配候选图，检查 Provider、Provider Model、Credential Pool、至少一个 Credential、治理策略和候选价格币种；同一 Alias 的全部启用候选必须使用同一 currency，保证一次 Trace 在重试和 Fallback 后仍可形成单一费用总额。校验成功后把完整关系写入 ConfigSnapshot。运行时在一次 Trace 内读取固定快照，实时叠加 Credential 健康、容量和 CircuitState，产生 RouteDecision；选择成功后才创建 Attempt。

功能流程：新增候选时先选择 Provider Model，Credential Pool 下拉框只显示同 Provider 的池。保存后页面即时检查模型与池关系、重复组合和能力差异。拖动只用于调整优先级，释放后明确保存；同优先级权重通过数字字段修改。探测候选时，系统选择池内一个可用 Credential 执行最小调用并记录 ProviderCheckRecord。

路由规则：运行时按 priority 从小到大分组。先过滤停用、Provider 或模型停用、池不可用、能力不匹配、熔断 OPEN 和容量不足的候选，再在当前最低可用 priority 内按 weight 选择。选中的凭证在 Credential Pool 内按 selection_strategy 产生。当前优先级全部失败且 fallback_enabled=true 时进入下一优先级。

规则与异常：同一 Alias 下 provider_model_id 与 credential_pool_id 组合不得重复。至少一个启用候选满足模型、凭证池、Credential 和能力条件时，启用 Alias 才能发布。启用候选 currency 不一致时，ConfigValidationResult.status=FAILED，并生成 code=PRICE_CONFIGURATION_INVALID 的字段级问题，逐项指出候选和币种。候选实时 runtime_status 不写入草稿，也不触发发布。

完成标准：页面顺序与运行路由顺序一致；发布校验能定位无凭证、跨 Provider、能力冲突和重复候选；Trace 能显示最终选择和被尝试的每个候选。

接口：GET /admin/model-aliases/{id}/candidates，POST /admin/model-aliases/{id}/candidates，PUT/DELETE /admin/route-candidates/{id}，POST /admin/route-candidates/{id}/check。

候选接口增加 GET /admin/provider-models/{id}/credential-pools 和 PUT /admin/model-aliases/{id}/candidates/reorder。reorder 请求为 items 数组，每项包含 id、priority、version；响应返回更新后的全部候选和 Alias 聚合字段。

### 4.2.9 模型接入管理接口契约

模型接入管理接口统一使用 JSON。列表查询返回 PageResult，详情查询返回完整非敏感实体，写操作返回 ManagementOperationResult。更新、启停、轮换和删除必须提交 version；服务端先校验权限和 version，再校验引用与字段，在同一事务中写业务草稿和 AuditLog。检测记录属于运行数据，检测接口不修改配置 version 和 draft_changed。

#### 4.2.9.1 Provider 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/providers | keyword、type、connection_status、enabled、draft_changed、page、page_size、sort | PageResult<ProviderListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/providers | name、type、base_url、proxy_url、connect_timeout_ms、read_timeout_ms、default_headers、enabled | ManagementOperationResult | 系统管理员 | PROVIDER_ADAPTER_NOT_FOUND、FIELD_VALIDATION_FAILED |
| GET /admin/providers/{id} | id | ProviderDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/providers/{id} | Provider 可编辑字段、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| GET /admin/providers/{id}/impact | id、operation | ImpactAnalysis | 系统管理员 | OBJECT_NOT_FOUND |
| POST /admin/providers/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、PROVIDER_AUTH_FAILED、TOTAL_TIMEOUT |
| POST /admin/providers/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/providers/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED、CONFIG_VERSION_CONFLICT |
| DELETE /admin/providers/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE、IMPACT_ANALYSIS_EXPIRED |

#### 4.2.9.2 Credential Pool 与 Credential 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/credential-pools | keyword、provider_id、status、enabled、page、page_size、sort | PageResult<CredentialPoolListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/credential-pools | provider_id、name、selection_strategy、enabled | ManagementOperationResult | 系统管理员 | OBJECT_REFERENCE_INVALID、FIELD_VALIDATION_FAILED |
| GET /admin/credential-pools/{id} | id | CredentialPoolDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/credential-pools/{id} | name、selection_strategy、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| GET /admin/credential-pools/{id}/impact | id、operation | ImpactAnalysis | 系统管理员 | OBJECT_NOT_FOUND |
| POST /admin/credential-pools/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/credential-pools/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED |
| DELETE /admin/credential-pools/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE |
| GET /admin/credential-pools/{poolId}/credentials | health_status、enabled、page、page_size、sort | PageResult<CredentialListItem> | 系统管理员、运维人员 | OBJECT_NOT_FOUND |
| POST /admin/credential-pools/{poolId}/credentials | name、secret_source、secret_value 或 secret_ref、weight、rpm_limit、tpm_limit、concurrent_limit、enabled | ManagementOperationResult | 系统管理员 | SECRET_RESOLUTION_FAILED、FIELD_VALIDATION_FAILED |
| GET /admin/credentials/{id} | id | CredentialDetail | 系统管理员、运维人员 | OBJECT_NOT_FOUND；响应不含 secret_value 和 token_hash |
| PUT /admin/credentials/{id} | name、secret_ref、weight、rpm_limit、tpm_limit、concurrent_limit、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| POST /admin/credentials/{id}/rotate | secret_value、secret_value_confirm、version | ManagementOperationResult | 系统管理员 | SECRET_CONFIRM_MISMATCH、CONFIG_VERSION_CONFLICT |
| POST /admin/credentials/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、PROVIDER_AUTH_FAILED |
| POST /admin/credentials/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/credentials/{id}/disable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/credentials/{id} | version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE、CAPACITY_IN_USE |

#### 4.2.9.3 Provider Model 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/provider-models | keyword、provider_id、connection_status、support_stream、enabled、page、page_size、sort | PageResult<ProviderModelListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/provider-models | ProviderModel 可编辑字段 | ManagementOperationResult | 系统管理员 | OBJECT_REFERENCE_INVALID、FIELD_VALIDATION_FAILED |
| GET /admin/provider-models/{id} | id | ProviderModelDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/provider-models/{id} | ProviderModel 可编辑字段、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| GET /admin/providers/{id}/available-models | source、credential_id、keyword | array<ProviderModelImportCandidate> | 系统管理员 | MODEL_LIST_NOT_SUPPORTED、SECRET_RESOLUTION_FAILED |
| POST /admin/provider-models/import | ProviderModelImportCommand | ImportResult | 系统管理员 | FIELD_VALIDATION_FAILED、IMPORT_SOURCE_UNAVAILABLE |
| POST /admin/provider-models/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、TOTAL_TIMEOUT |
| POST /admin/provider-models/batch-check | provider_model_ids、credential_id、mode、timeout_ms | BatchCheckJob | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、CHECK_TARGET_INVALID |
| GET /admin/batch-check-jobs/{id} | id | BatchCheckJob 与 array<BatchCheckItem> | 系统管理员、运维人员 | OBJECT_NOT_FOUND |
| POST /admin/batch-check-jobs/{id}/cancel | id | BatchCheckJob | 系统管理员、运维人员 | JOB_ALREADY_FINISHED |
| POST /admin/provider-models/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/provider-models/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED |
| DELETE /admin/provider-models/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE |

#### 4.2.9.4 Model Alias 与候选接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误 |
|---|---|---|---|---|
| GET /admin/model-aliases | keyword、enabled、runtime_availability、support_stream、page、page_size、sort | PageResult<ModelAliasListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/model-aliases | alias、display_name、description、enabled | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED |
| GET /admin/model-aliases/{id} | id | ModelAliasDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/model-aliases/{id} | display_name、description、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| GET /admin/model-aliases/{id}/impact | id、operation | ImpactAnalysis | 系统管理员 | OBJECT_NOT_FOUND |
| POST /admin/model-aliases/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| POST /admin/model-aliases/{id}/disable | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | IMPACT_ANALYSIS_EXPIRED |
| DELETE /admin/model-aliases/{id} | version、confirmed_impact_version | ManagementOperationResult | 系统管理员 | OBJECT_IN_USE |
| GET /admin/model-aliases/{id}/candidates | id | array<RouteCandidateDetail> | 可查看角色 | OBJECT_NOT_FOUND |
| POST /admin/model-aliases/{id}/candidates | provider_model_id、credential_pool_id、priority、weight、enabled | ManagementOperationResult | 系统管理员 | OBJECT_REFERENCE_INVALID、DUPLICATE_ROUTE_CANDIDATE |
| PUT /admin/route-candidates/{id} | credential_pool_id、priority、weight、enabled、version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_REFERENCE_INVALID |
| PUT /admin/model-aliases/{id}/candidates/reorder | items[].id、items[].priority、items[].version | array<RouteCandidateDetail> | 系统管理员 | CONFIG_VERSION_CONFLICT、FIELD_VALIDATION_FAILED |
| POST /admin/route-candidates/{id}/check | ProviderCheckCommand | ProviderCheckRecord | 系统管理员、运维人员 | CHECK_TARGET_INVALID、CAPACITY_LIMITED |
| DELETE /admin/route-candidates/{id} | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |

#### 4.2.9.5 接口数据与事务规则

impact 接口返回 impact_version，值由当前引用关系摘要计算。停用和删除命令必须回传 confirmed_impact_version；引用关系变化导致版本不一致时返回 IMPACT_ANALYSIS_EXPIRED，页面重新展示影响内容，避免用户确认后对象关系已经改变。

创建、编辑、启停、删除、轮换和重排在单一数据库事务中完成配置草稿、version 和 AuditLog 写入。审计写入失败时业务变更回滚。模型导入以每个 ProviderModel 为独立结果，但同一成功对象的实体与审计仍保持原子性。批量检测任务不修改草稿，取消只阻止尚未开始的 BatchCheckItem，正在执行的 Provider 请求传播取消信号。

所有详情与列表响应在序列化前执行敏感字段过滤。Credential.secret_value、已解析 Secret、Standalone token_value、token_hash、Authorization 和自定义认证请求头不得进入 ManagementOperationResult、错误详情、导出或 AuditLog。

## 4.3 运行治理

运行治理按照“限流策略—可靠性策略—熔断状态—运行路由执行”展开。管理员先为 Alias、Provider Model 或 Credential 配置容量上限，再为 Alias 配置超时、重试、Fallback 和熔断参数；运维人员通过熔断状态页处理运行异常；路由执行章节定义这些页面配置在一次请求中的固定执行顺序。策略保存后进入草稿，熔断人工操作与运行探测即时生效并单独审计。

### 4.3.1 限流策略列表与编辑页

页面结构：列表按 scope_type、作用对象和启用状态筛选，显示 name、scope_type、对象名称、rpm_limit、tpm_limit、concurrent_limit、overflow_strategy、queue_timeout_ms、enabled、draft_changed 和操作。创建页按顺序填写名称、范围类型、范围对象、三个上限、溢出策略、排队超时和启用状态。

#### 4.3.1.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 LimitPolicy.name 和作用对象名称。 |
| 筛选区 | scope_type | 多选下拉 | LimitPolicy.scope_type | MODEL_ALIAS、PROVIDER_MODEL、CREDENTIAL。 |
| 筛选区 | scope_id | 远程搜索下拉 | 作用对象实体 | 选择 scope_type 后加载；查询时传对象 ID。 |
| 筛选区 | overflow_strategy | 单选下拉 | LimitPolicy.overflow_strategy | 全部、REJECT、QUEUE。 |
| 筛选区 | enabled | 单选下拉 | LimitPolicy.enabled | 全部、启用、停用。 |
| 列表 | name | 链接文本 | LimitPolicy.name | 点击进入编辑与运行使用抽屉。 |
| 列表 | scope_type | 文本 | LimitPolicy.scope_type | 显示范围中文名称和枚举值。 |
| 列表 | scope_name | 链接文本 | scope_id 关联实体 | 点击进入 Alias、模型或 Credential 详情。 |
| 列表 | rpm_limit | 数字 | LimitPolicy.rpm_limit | 空值显示“不限制”。 |
| 列表 | rpm_used | 数字与百分比 | LimitUsageSnapshot.rpm_used | 有上限时显示 used/limit 与百分比。 |
| 列表 | tpm_limit | 数字 | LimitPolicy.tpm_limit | 空值显示“不限制”。 |
| 列表 | tpm_used | 数字与百分比 | tpm_reserved + tpm_confirmed | 分开展示已确认和预占，百分比按总和计算。 |
| 列表 | concurrent_limit | 数字 | LimitPolicy.concurrent_limit | 空值显示“不限制”。 |
| 列表 | concurrency_used | 数字与百分比 | LimitUsageSnapshot.concurrency_used | 每 5 秒刷新。 |
| 列表 | overflow_strategy | 文本 | LimitPolicy.overflow_strategy | QUEUE 同时展示 queue_length 与 queue_max_size。 |
| 列表 | window_end | 倒计时 | LimitUsageSnapshot.window_end | 仅 RPM 或 TPM 有限制时显示。 |
| 列表 | counter_store_status | 状态文本 | LimitUsageSnapshot.counter_store_status | DEGRADED 或 UNAVAILABLE 时提供运行告警入口。 |
| 列表 | enabled | 状态文本 | LimitPolicy.enabled | 变更使用启停操作。 |
| 列表 | draft_changed | 变更状态 | LimitPolicy.draft_changed | true 显示待发布。 |
| 列表 | actions | 操作菜单 | 权限与对象状态 | 查看、编辑、启用、停用、删除、查看排队。 |

#### 4.3.1.2 表单字段

| 字段 | 控件形式 | 默认值 | 显示与编辑规则 | 校验 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 新建、编辑可见 | 2—64 字符，全局唯一。 |
| scope_type | 单选卡片 | MODEL_ALIAS | 创建后只读 | 只能为 MODEL_ALIAS、PROVIDER_MODEL、CREDENTIAL。 |
| scope_id | 远程搜索单选 | 空 | 创建后只读；选项随 scope_type 变化 | 必须指向未删除对象；同一对象最多一份启用策略。 |
| rpm_limit | 数字框 | 空 | 可编辑 | 空或正整数，最大 1000000000。 |
| tpm_limit | 数字框 | 空 | 可编辑 | 空或正整数，最大 long 安全范围。 |
| concurrent_limit | 数字框 | 空 | 可编辑 | 空或 1—100000。 |
| overflow_strategy | 单选 | REJECT | 可编辑 | REJECT 或 QUEUE。 |
| queue_timeout_ms | 数字框 | 5000 | 仅 QUEUE 时显示并必填 | 1—60000；运行时与当前 Trace 剩余 total_timeout_ms 取较小值。 |
| queue_max_size | 数字框 | 1000 | 仅 QUEUE 时显示并必填 | 1—100000。 |
| window_seconds | 只读数字 | 60 | V1.0 不可修改 | 固定分钟窗口。 |
| enabled | 开关 | true | 可编辑 | 至少一个 rpm_limit、tpm_limit、concurrent_limit 非空时才允许启用。 |
| version | 隐藏字段 | 空 | 更新时只读 | 更新、启停和删除时必填。 |

#### 4.3.1.3 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新建策略 | 系统管理员；作用对象不存在其他启用策略 | 创建 LimitPolicy 草稿与 AuditLog | 返回详情并显示待发布。 |
| 编辑策略 | 系统管理员；version 最新 | 更新限额、溢出参数和 version | 运行使用数据不清零，发布后使用新上限重新判断。 |
| 启用 | 系统管理员；至少配置一个上限 | enabled=true 草稿 | 发布校验对象引用和队列超时。 |
| 停用 | 系统管理员；当前 enabled=true | enabled=false 草稿 | 发布后新请求不再应用该策略，已有 QueueEntry 立即重新评估。 |
| 删除 | 系统管理员；策略未被活动快照引用或已先停用 | 删除草稿或记录删除变更 | 发布后删除；存在草稿冲突返回 OBJECT_IN_USE。 |
| 查看实时使用 | 系统管理员、运维人员、只读人员 | 无配置变化 | 展示 LimitUsageSnapshot 和等待队列摘要。 |
| 取消排队请求 | 不提供管理端操作 | 无 | V1.0 只允许调用方取消自身 Trace，避免管理员破坏请求顺序。 |

#### 4.3.1.4 容量计数与数据流

RPM 与 TPM 使用按 Unix 时间对齐的 60 秒固定窗口，window_start 等于 floor(epoch_seconds / 60) × 60。每次准备发起外部 Provider 请求时，系统同时收集 Alias、Provider Model 和 Credential 三层启用策略，并在共享容量存储中执行一次原子校验与预占；任一策略不足时，全部策略均不得产生部分计数。

RPM 每个实际外部 Attempt 预占 1。TPM 预占 estimated_input_tokens + effective_max_tokens，其中 effective_max_tokens 是请求值、模型默认值或 max_output_tokens 合并后的结果。Attempt 结束后使用 Provider 实际 Usage 结算；没有 Usage 时使用 TokenEstimator，差额从 tpm_reserved 转入或退回。Provider 已收到请求但响应失败时仍按实际或估算消耗结算，避免低估成本与容量。

并发在创建 Attempt 前增加，在成功、失败、超时和取消的统一清理阶段释放。CapacityReservation.expires_at 等于 Trace 总超时加 30 秒清理宽限，Watchdog 只释放超过 expires_at 且没有 RUNNING Attempt 的异常预占，并记录 WATCHDOG_EXPIRED 指标。

当所有候选均受容量限制且至少一个阻塞策略为 QUEUE 时，系统创建一个 QueueEntry，Trace 进入 QUEUED。队列按 alias_id 分区并按 sequence 先入先出；容量释放或窗口切换时，从队首重新执行完整候选、凭证和三层策略判断，队列不绑定首次不足的候选。队列长度达到所有阻塞策略中最小 queue_max_size 时返回 QUEUE_FULL。deadline_at 取 queue_timeout_ms 和 total_timeout_ms 剩余时间的较早值。

功能流程：选择 scope_type 后，对象选择器只加载对应实体。保存时检查同一对象是否已有启用策略。发布后，新请求同时受到 Alias、Provider Model 和 Credential 层策略约束，任一层无可用额度即视为当前路径容量不足。

计数规则：RPM 在 Attempt 准备调用 Provider 前预占，每次 Retry 和 Fallback 都分别计数；未实际发出外部请求的候选过滤不计 RPM。TPM 在调用前按输入估算 Token 加 max_tokens 预占，完成后以实际或最终估算 Token 结算差额。并发从 Attempt 开始前增加，在成功、失败、取消和超时的 finally 阶段归还。

溢出规则：REJECT 立即返回 CAPACITY_LIMITED 或继续寻找其他候选。QUEUE 只在至少存在一个未来可释放容量的候选时排队，采用先入先出；等待超过 queue_timeout_ms 或 total_timeout_ms 剩余时间时结束。客户端断开连接后取消队列等待。

完成标准：三类限额可以独立设置；并发不泄漏；分钟窗口复位准确；排队时间记录到 Trace.queued_ms；列表实时用量与运行计数一致。

接口：GET/POST /admin/limit-policies，GET/PUT/DELETE /admin/limit-policies/{id}，POST /admin/limit-policies/{id}/enable，POST /admin/limit-policies/{id}/disable。

### 4.3.2 可靠性策略列表与编辑页

页面结构：列表显示名称、Model Alias、总超时、最大重试次数、是否允许 Fallback、熔断窗口、失败率阈值、OPEN 时长、状态、草稿变更和操作。编辑页按调用时间、重试退避、Fallback 和 Circuit Breaker 四个区块连续展示，核心策略信息见 2.6.7，具体字段与默认值按本节表执行。

#### 4.3.2.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 文本框 | 查询参数 | 匹配 ReliabilityPolicy.name 与 Alias。 |
| 筛选区 | alias_id | 远程搜索多选 | ModelAlias | 显示 alias 和 display_name。 |
| 筛选区 | fallback_enabled | 单选下拉 | ReliabilityPolicy.fallback_enabled | 全部、启用、关闭。 |
| 筛选区 | enabled | 单选下拉 | ReliabilityPolicy.enabled | 全部、启用、停用。 |
| 列表 | name | 链接文本 | ReliabilityPolicy.name | 点击进入编辑。 |
| 列表 | alias | 链接文本 | ModelAlias.alias | 点击进入 Alias 详情。 |
| 列表 | connect_timeout_ms | 毫秒 | ReliabilityPolicy.connect_timeout_ms | 大于 1000 时同时显示秒值。 |
| 列表 | first_token_timeout_ms | 毫秒 | ReliabilityPolicy.first_token_timeout_ms | 只影响流式请求。 |
| 列表 | total_timeout_ms | 毫秒 | ReliabilityPolicy.total_timeout_ms | 包含排队、退避和全部 Attempt。 |
| 列表 | max_retries | 数字 | ReliabilityPolicy.max_retries | 表示同候选瞬时错误重试次数。 |
| 列表 | max_credential_failovers | 数字 | ReliabilityPolicy.max_credential_failovers | 表示同候选换密钥次数。 |
| 列表 | max_fallbacks | 数字 | ReliabilityPolicy.max_fallbacks | fallback_enabled=false 时显示 0。 |
| 列表 | circuit_failure_rate | 百分比 | ReliabilityPolicy.circuit_failure_rate | 与 circuit_min_requests 组合展示。 |
| 列表 | circuit_open_seconds | 秒 | ReliabilityPolicy.circuit_open_seconds | 显示自动 OPEN 时长。 |
| 列表 | enabled | 状态文本 | ReliabilityPolicy.enabled | 停用后 Alias 使用内置默认策略。 |
| 列表 | draft_changed | 变更状态 | ReliabilityPolicy.draft_changed | true 显示待发布。 |
| 列表 | actions | 操作菜单 | 权限与状态 | 查看、编辑、启用、停用、删除。 |

#### 4.3.2.2 表单字段

| 区域 | 字段 | 控件形式 | 默认值 | 校验与业务规则 |
|---|---|---|---|---|
| 基础 | name | 单行文本 | 空 | 2—64 字符，全局唯一。 |
| 基础 | alias_id | 远程搜索单选 | 空 | 创建后只读；同一 Alias 最多一份启用策略。 |
| 超时 | connect_timeout_ms | 数字框 | 3000 | 100—60000。 |
| 超时 | first_token_timeout_ms | 数字框 | 30000 | 1000—300000，必须小于 total_timeout_ms。 |
| 超时 | total_timeout_ms | 数字框 | 120000 | 1000—600000，覆盖排队、退避和全部 Attempt。 |
| 重试 | max_retries | 数字框 | 1 | 0—5。 |
| 重试 | max_credential_failovers | 数字框 | 1 | 0—10。 |
| 重试 | initial_backoff_ms | 数字框 | 200 | 0—10000。 |
| 重试 | backoff_multiplier | 小数框 | 2.00 | 1.00—5.00。 |
| 重试 | jitter_percent | 数字框 | 20 | 0—100。 |
| 重试 | respect_retry_after | 开关 | true | 只处理 Provider 可解析的 Retry-After。 |
| 重试 | max_retry_after_ms | 数字框 | 5000 | 0—60000；respect_retry_after=false 时禁用。 |
| Fallback | fallback_enabled | 开关 | true | false 时 max_fallbacks 强制为 0。 |
| Fallback | max_fallbacks | 数字框 | 2 | 0—10；只统计切换 Route Candidate。 |
| 熔断 | circuit_window_seconds | 数字框 | 60 | 10—600。 |
| 熔断 | circuit_min_requests | 数字框 | 20 | 1—10000。 |
| 熔断 | circuit_failure_rate | 百分比框 | 50.00 | 1.00—100.00。 |
| 熔断 | circuit_open_seconds | 数字框 | 30 | 1—3600。 |
| 熔断 | circuit_half_open_probes | 数字框 | 3 | 1—100。 |
| 熔断 | circuit_half_open_successes | 数字框 | 2 | 1—circuit_half_open_probes。 |
| 状态 | enabled | 开关 | true | 停用后使用不可编辑的内置默认策略。 |
| 系统 | version | 隐藏字段 | 空 | 更新、启停和删除时提交。 |

#### 4.3.2.3 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 页面结果与异常 |
|---|---|---|---|
| 新建策略 | 系统管理员；Alias 没有其他启用策略 | 创建 ReliabilityPolicy 草稿与 AuditLog | 返回详情并显示待发布。 |
| 编辑策略 | 系统管理员；version 最新 | 更新策略草稿和 version | 发布后只影响新建 Trace。 |
| 启用 | 系统管理员；参数组合有效 | enabled=true 草稿 | 发布校验 Alias 唯一策略。 |
| 停用 | 系统管理员；当前启用 | enabled=false 草稿 | 发布后 Alias 使用内置默认值，已有 Trace 继续使用原快照。 |
| 删除 | 系统管理员；当前策略已停用或只存在未发布草稿 | 删除草稿或记录删除变更 | 活动快照引用时要求先停用并发布。 |
| 查看默认策略 | 所有可查看角色 | 无 | 展示本节定义的默认值，不允许编辑。 |
| 查看近期恢复决策 | 系统管理员、运维人员 | 无 | 按 Alias 展示 RecoveryDecision 和关联 Attempt。 |

#### 4.3.2.4 尝试预算与数据流

Trace 创建时从 ConfigSnapshot 取得 Alias 的 ReliabilityPolicy；没有启用策略时装配内置默认值。策略在整个 Trace 生命周期内保持不变。理论外部请求上限为 1 + max_retries + max_credential_failovers + max_fallbacks，但每类动作只消耗自身预算，路由候选或 Credential 数量不足时不会补足预算。

RETRY 表示同一 Route Candidate 上因网络错误、连接超时、首 Token 超时或可重试 5xx 再次调用；运行时优先排除刚失败的网络连接，Credential 是否复用由错误类型决定。CREDENTIAL_FAILOVER 表示 Provider 429、鉴权失败或单密钥不可用后，在同一 Credential Pool 中更换 Credential，Attempt.attempt_type 记录为 CREDENTIAL_FAILOVER。FALLBACK 表示切换到另一个 Route Candidate，并受 fallback_enabled 与 max_fallbacks 约束。

每个失败 Attempt 完成 Token 结算与并发释放后生成 RecoveryDecision。决策器依次检查客户端是否取消、是否已经输出流式 delta、剩余 total_timeout_ms、错误分类、各类预算、熔断状态、候选与 Credential 可用性和容量。只有全部条件满足才执行下一次外部请求；下一次 Attempt 重新进行 RPM、TPM 与并发预占。

退避时间为 initial_backoff_ms × backoff_multiplier^retries_used，再按 jitter_percent 施加正负随机抖动。Provider 返回 Retry-After 且 respect_retry_after=true 时，取 Provider 等待值与 max_retry_after_ms 的较小值；等待后剩余时间不足完成最小连接预算时，跳过等待并尝试 Credential Failover 或 Fallback。所有等待都计入 total_timeout_ms。

功能流程：创建时选择尚未绑定启用策略的 Alias。保存执行范围和组合校验；发布后策略按 Trace 创建时取得的快照执行，运行中的 Trace 不切换策略。禁用策略后使用本节定义的系统默认可靠性策略。

重试规则：NETWORK_ERROR、CONNECT_TIMEOUT、FIRST_TOKEN_TIMEOUT 和标记可重试的 PROVIDER_SERVER_ERROR 允许 RETRY。PROVIDER_RATE_LIMITED 先执行 CREDENTIAL_FAILOVER 或 FALLBACK；没有替代路径且 Retry-After 在 max_retry_after_ms 与剩余总超时内时，才允许对原路径执行一次受 max_retries 约束的延迟 RETRY。请求参数错误、模型不存在、鉴权失败、内容拒绝和客户端取消不执行 RETRY。max_retries 只统计初始 Attempt 之后保持同一 Route Candidate 的瞬时错误重试。

流式规则：首个 SSE 业务块输出前可以重试或 Fallback。首块输出后若外部流中断，结束当前请求并发送包含 UnifiedError 的 SSE data，禁止拼接另一个模型的输出。first_token_timeout_ms 只作用于流式首 Token，total_timeout_ms 作用于排队、全部 Attempt、退避和流式输出全过程。

完成标准：错误分类与重试决策可从 Attempt 查看；全部 Attempt 总时长不超过策略总超时加允许的服务端清理时间；配置组合错误在发布前被拦截。

接口：GET/POST /admin/reliability-policies，GET/PUT/DELETE /admin/reliability-policies/{id}，POST /admin/reliability-policies/{id}/enable，POST /admin/reliability-policies/{id}/disable。

### 4.3.3 熔断状态页

页面结构：页面按 state、Provider、Provider Model 和 Credential 筛选，显示模型、Credential 脱敏名称、state、sample_count、failure_count、failure_rate、opened_at、next_probe_at、last_error_code 和操作。点击行打开详情，展示最近状态变化和计入统计的 Trace。

#### 4.3.3.1 页面字段

| 页面区域 | 字段或控件 | 控件形式 | 数据来源 | 展示与交互规则 |
|---|---|---|---|---|
| 筛选区 | state | 多选下拉 | CircuitState.state | CLOSED、OPEN、HALF_OPEN。 |
| 筛选区 | provider_id | 远程搜索多选 | Provider | 先筛 Provider，再联动模型与 Credential。 |
| 筛选区 | provider_model_id | 远程搜索多选 | ProviderModel | 显示 display_name 和 model_id。 |
| 筛选区 | credential_id | 远程搜索多选 | Credential | 仅系统管理员和运维人员展示，只显示 name 与 masked_value。 |
| 筛选区 | open_source | 单选下拉 | CircuitState.open_source | 全部、AUTO、MANUAL。 |
| 筛选区 | has_recent_failure | 开关 | CircuitState.last_error_code | 开启后只显示当前窗口有失败的状态。 |
| 列表 | provider_name | 链接文本 | Provider.name | 点击进入 Provider 详情。 |
| 列表 | provider_model_name | 链接文本 | ProviderModel.display_name | 点击进入模型详情。 |
| 列表 | credential_name | 文本 | Credential.name、masked_value | 仅系统管理员和运维人员展示；其他可查看角色显示“受限凭证”，响应不返回 credential_id。 |
| 列表 | state | 状态文本 | CircuitState.state | 明确显示 CLOSED、OPEN 或 HALF_OPEN。 |
| 列表 | open_source | 文本 | CircuitState.open_source | CLOSED 时显示空。 |
| 列表 | sample_count | 数字 | CircuitState.sample_count | 当前滚动窗口有效 Attempt 数。 |
| 列表 | failure_count | 数字 | CircuitState.failure_count | 当前窗口计入熔断的失败数。 |
| 列表 | failure_rate | 百分比 | CircuitState.failure_rate | sample_count 未达最小值时仍展示，但标记“样本不足”。 |
| 列表 | half_open_in_flight | 数字 | CircuitState.half_open_in_flight | 仅 HALF_OPEN 显示。 |
| 列表 | half_open_success_count | 数字 | CircuitState.half_open_success_count | 仅 HALF_OPEN 显示。 |
| 列表 | opened_at | 时间 | CircuitState.opened_at | CLOSED 时显示空。 |
| 列表 | next_probe_at | 时间与倒计时 | CircuitState.next_probe_at | OPEN 时展示。 |
| 列表 | last_error_code | 文本链接 | CircuitState.last_error_code | 点击筛选相关 Trace。 |
| 列表 | updated_at | 时间 | CircuitState.updated_at | 按系统时区展示。 |
| 列表 | actions | 操作菜单 | 权限、state、state_version | 人工打开、恢复、立即探测、查看详情。 |

#### 4.3.3.2 详情字段

| 区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| 当前状态 | 状态、窗口请求数、失败率、打开时间、下次探测时间、状态版本 | Circuit State | 每 5 秒刷新；刷新后人工操作必须使用最新状态版本。 |
| 生效阈值 | circuit_window_seconds、circuit_min_requests、circuit_failure_rate、circuit_open_seconds、circuit_half_open_probes、circuit_half_open_successes | Alias 的 ReliabilityPolicy | 同时显示 policy_id 和 snapshot_no。 |
| 状态事件 | 发生时间、变更前后状态、原因、触发来源 | Circuit Event | 按发生时间倒序，默认最近 50 条。 |
| 失败样本 | trace_id、attempt_id、ended_at、error_code、total_ms | Attempt | 只展示当前窗口计入熔断的 Attempt。 |
| 近期探测 | ProviderCheckRecord 或 HALF_OPEN_PROBE Attempt | 检测与调用记录 | 区分人工探测和业务流量探测。 |
| 人工信息 | open_source、manual_reason、manual_open_until、operator | CircuitState 与最近 CircuitEvent | 只在人工打开时展示。 |

#### 4.3.3.3 操作定义

| 操作 | 权限与前置条件 | 请求字段 | 状态与数据变化 | 结果与异常 |
|---|---|---|---|---|
| 人工打开 | 系统管理员、运维人员；state 非 OPEN 或允许延长人工 OPEN | action=MANUAL_OPEN、reason、open_seconds、state_version | state=OPEN，open_source=MANUAL，重算 next_probe_at，写 CircuitEvent 与 AuditLog | 版本变化返回 CIRCUIT_STATE_CONFLICT。 |
| 人工恢复 | 系统管理员、运维人员；state 为 OPEN 或 HALF_OPEN | action=MANUAL_RECOVER、reason、state_version | state=CLOSED，清空窗口与探测计数，写事件与审计 | 已 CLOSED 返回 CIRCUIT_STATE_CONFLICT。 |
| 立即探测 | 系统管理员、运维人员；state=OPEN 且没有进行中人工探测 | action=PROBE_NOW、state_version | 临时取得一个 HALF_OPEN 探测名额，执行最小调用并写检测记录 | Credential 不可用返回 CHECK_TARGET_INVALID。 |
| 查看失败 Trace | 可查看角色 | 时间范围、error_code | 无状态变化 | 跳转 Trace 列表。 |

#### 4.3.3.4 状态机与数据流

CLOSED 使用 circuit_window_seconds 的滚动窗口。每个计入熔断的 Attempt 结束后写入按秒分桶的成功或失败计数，计算时只汇总 now - window_seconds 之后的分桶。sample_count 达到 circuit_min_requests 且 failure_rate 大于等于 circuit_failure_rate 时，以原子状态更新进入 OPEN，设置 open_source=AUTO、opened_at=now、next_probe_at=now+circuit_open_seconds，并生成 CircuitEvent。

OPEN 状态不接收普通业务 Attempt。到达 next_probe_at 后，第一个取得探测名额的业务请求以 HALF_OPEN_PROBE 类型执行，其余请求继续绕过该模型与 Credential 组合。人工 PROBE_NOW 可以在时间未到时执行一个最小检测，但仍受探测并发限制。

HALF_OPEN 最多同时存在 circuit_half_open_probes 个探测。任一计入熔断的失败立即重新进入 OPEN并重置 next_probe_at；连续成功达到 circuit_half_open_successes 后进入 CLOSED并清空旧窗口。未达到成功数时保持 HALF_OPEN，释放探测名额后允许后续探测。

人工打开同样设置 next_probe_at，open_seconds 到期后按 HALF_OPEN 规则探测。人工恢复直接进入 CLOSED并清空统计。所有自动和人工迁移都使用 state_version 原子更新，并写 CircuitEvent；人工操作额外写 AuditLog。CircuitState 属于运行状态，不进入配置草稿，也不需要发布。

功能流程：CLOSED 状态按策略窗口统计有效 Attempt。达到 circuit_min_requests 且失败率达到阈值时进入 OPEN。到达 next_probe_at 后，第一个获得探测名额的请求使状态进入 HALF_OPEN；成功探测达到配置数量后恢复 CLOSED，任一探测失败则重新 OPEN。人工打开和恢复立即改变运行状态，不需要配置发布，并写入 AuditLog。

规则与异常：计入失败率的错误包括 Provider 网络错误、连接超时、首 Token 超时、Provider 服务错误、无法解析的响应和首个 SSE 业务块输出后的流式中断。Provider 429、鉴权失败、模型不存在、Secret 解析失败、业务参数错误、内容拒绝、客户端取消、本系统限流与排队超时均不计入失败率。人工恢复清空当前窗口统计。人工打开必须填写原因和预计恢复时间；未填写预计时间时使用策略 open_seconds。

完成标准：OPEN 候选不接收常规请求；HALF_OPEN 并发不超过 half_open_probes；状态变化在页面和指标系统中及时可见；人工操作可追溯。

接口：GET /admin/circuits，GET /admin/circuits/{id}，POST /admin/circuits/{id}/open，POST /admin/circuits/{id}/recover，POST /admin/circuits/{id}/probe。

### 4.3.4 运行路由执行

运行路由没有独立编辑页面，由 Model Alias、Route Candidate、Credential Pool、Limit Policy 和 Reliability Policy 的已发布快照共同驱动。每个 Trace 固定使用创建时的 snapshot_no，保证一次调用内规则一致。

执行顺序固定为请求校验、候选能力过滤、候选启停与引用校验、熔断过滤、容量预判、优先级与权重选择、Credential 选择、容量预占、Attempt 创建、Provider 调用、结果结算。任何阶段失败都写入可解释的 route decision；未产生外部请求的过滤结果可以保存原因，但不创建 Attempt。

当同一优先级存在多个候选时，权重只在当前可用集合内重新归一化。候选因容量不足被过滤后可以继续选择其他候选；所有候选均容量不足且存在 QUEUE 策略时进入队列。路由结果必须支持通过 Trace 详情重现当时的候选顺序、过滤原因和实际选择。

#### 4.3.4.1 路由输入与输出

| 数据 | 字段 | 来源 | 用途 |
|---|---|---|---|
| RouteExecutionContext | trace_id、snapshot_no、alias_id | Trace 与 ConfigSnapshot | 固定一次调用的追踪和配置版本。 |
| RouteExecutionContext | stream、has_system_message | UnifiedChatRequest | 过滤不支持流式或 system 的模型。 |
| RouteExecutionContext | estimated_input_tokens、effective_max_tokens | TokenEstimator 与参数合并 | 上下文校验和 TPM 预占。 |
| RouteExecutionContext | deadline_at | ReliabilityPolicy.total_timeout_ms | 约束排队、退避和全部 Attempt。 |
| RouteExecutionContext | 三类已使用预算 | RecoveryDecision 聚合 | 防止重试、换密钥和 Fallback 超限。 |
| RouteExecutionContext | excluded_candidate_ids、excluded_credential_ids | 前序失败结果 | 避免在同一 Trace 中重复选择已确认无效对象。 |
| RouteExecutionResult | route_candidate_id、credential_id | 路由与凭证选择 | 创建 Attempt 并调用 Adapter。 |
| RouteExecutionResult | capacity_reservation_id | 容量组件 | 结算 Token 和释放并发。 |
| RouteExecutionResult | attempt_type、decision_sequence | 恢复动作与 RouteDecision | 解释本次 Attempt 的来源。 |

#### 4.3.4.2 执行阶段

| 顺序 | 阶段 | 处理规则 | 产生数据 | 终止错误 |
|---:|---|---|---|---|
| 1 | 请求与身份校验 | 校验访问凭证、IP、Alias、消息和基础参数 | Trace、RouteExecutionContext | ACCESS_TOKEN_INVALID、ACCESS_IP_DENIED、FIELD_VALIDATION_FAILED |
| 2 | 配置快照装配 | 获取 Alias、候选、模型、凭证池和治理策略 | snapshot_no、候选初始集合 | MODEL_ALIAS_NOT_FOUND、MODEL_ALIAS_DISABLED |
| 3 | 静态候选过滤 | 排除停用、引用无效、Adapter 未加载的候选 | FILTERED RouteDecision | OBJECT_REFERENCE_INVALID |
| 4 | 能力与上下文过滤 | 校验 stream、system、参数范围和 context_window | FILTERED RouteDecision、estimated_input_tokens | MODEL_CAPABILITY_NOT_SUPPORTED、CONTEXT_WINDOW_EXCEEDED |
| 5 | 熔断过滤 | 排除 OPEN，控制 HALF_OPEN 探测名额 | FILTERED 或 SELECTED_FOR_PROBE RouteDecision | CIRCUIT_OPEN |
| 6 | 优先级与权重排序 | priority 从小到大；同级按 weight 无放回生成尝试顺序 | 候选有序集合 | ALL_CANDIDATES_FAILED |
| 7 | Credential 选择 | 按池策略过滤健康、限额、并发和本 Trace 排除项 | credential_id、RouteDecision | CREDENTIAL_NOT_AVAILABLE |
| 8 | 原子容量预占 | 同时校验 Alias、模型、Credential 三层策略并预占 | CapacityReservation | CAPACITY_LIMITED、QUEUE_FULL、CAPACITY_STATE_UNAVAILABLE |
| 9 | 排队 | 仅所有候选容量不足且存在 QUEUE 时创建 FIFO 条目 | QueueEntry、Trace.status=QUEUED | QUEUE_TIMEOUT、CLIENT_CANCELLED |
| 10 | Attempt 执行 | 创建 Attempt，Adapter 转换并调用 Provider | Attempt、Provider Request ID、流式事件 | Provider 分类错误 |
| 11 | 结算与健康更新 | 结算 Token、费用、容量、Credential 健康和 CircuitState | Usage、Cost、CircuitEvent | INTERNAL_ERROR |
| 12 | 恢复决策 | 根据错误矩阵和预算生成下一动作 | RecoveryDecision | ALL_CANDIDATES_FAILED 或原始最终错误 |

同一优先级的加权选择按当前可用候选重新归一化，每轮生成无放回顺序，单个候选在同一选择轮中只出现一次。静态过滤和容量过滤不会消耗 max_fallbacks；只有某个候选已经产生失败 Attempt，随后切换到另一个 Route Candidate 时才增加 fallbacks_used。

#### 4.3.4.3 错误分类与恢复决策矩阵

| 错误或状态 | Credential/模型状态变化 | RETRY | CREDENTIAL_FAILOVER | FALLBACK | 计入熔断 | 最终处理 |
|---|---|---|---|---|---|---|
| 请求字段或上下文错误 | 无 | 否 | 否 | 否 | 否 | 直接返回 400 或 422。 |
| PROVIDER_AUTH_FAILED | Credential=INVALID | 否 | 有其他 Credential 时执行 | 允许 | 否 | 无替代路径时返回鉴权失败。 |
| SECRET_RESOLUTION_FAILED | Credential=UNAVAILABLE | 否 | 有其他 Credential 时执行 | 允许 | 否 | 外部 Secret 全部失败时返回 502。 |
| PROVIDER_RATE_LIMITED | Credential=RATE_LIMITED，并记录 reset_at | 仅无替代路径且 Retry-After 在预算内时延迟执行 | 优先执行 | 允许 | 否 | 无恢复路径时返回 503 与 retry_after_ms。 |
| PROVIDER_MODEL_NOT_FOUND | ProviderModel=UNAVAILABLE，记录检测错误 | 否 | 否 | 允许 | 否 | 无其他候选时返回 502。 |
| PROVIDER_REQUEST_REJECTED | 保持配置状态并触发参数转换告警 | 否 | 否 | 否 | 否 | 返回 502，保留脱敏 Provider Request ID。 |
| NETWORK_ERROR | 保持当前健康状态，连续失败由熔断处理 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 503。 |
| CONNECT_TIMEOUT | 保持当前健康状态 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 504。 |
| FIRST_TOKEN_TIMEOUT | 保持当前健康状态 | 尚未输出 delta 时允许 | 重试耗尽后可换密钥 | 尚未输出 delta 时允许 | 是 | 已输出内容时按 STREAM_INTERRUPTED。 |
| PROVIDER_SERVER_ERROR | 保持当前健康状态 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 503。 |
| PROVIDER_BAD_RESPONSE | 保持当前健康状态 | 允许 | 重试耗尽后可换密钥 | 允许 | 是 | 预算耗尽返回 502。 |
| CONTENT_FILTER | 无 | 否 | 否 | 否 | 否 | 作为正常 finish_reason 返回。 |
| STREAM_INTERRUPTED | 保持当前健康状态 | 否 | 否 | 否 | 是 | 发送流式 UnifiedError 并结束 Trace。 |
| TOTAL_TIMEOUT | 无 | 否 | 否 | 否 | 否 | 立即取消进行中请求并返回 504。 |
| CLIENT_CANCELLED | 无 | 否 | 否 | 否 | 否 | 传播取消，Trace=CANCELLED。 |
| CAPACITY_LIMITED | 无 | 否 | 尝试其他 Credential | 尝试其他候选 | 否 | 存在 QUEUE 时排队，否则返回 429。 |

Provider 429 表示当前 Credential 或上游账户容量受限，优先通过多密钥与其他候选恢复，不进入 CircuitState 失败率。Provider 鉴权失败只影响实际使用的 Credential。模型不存在属于 Provider Model 配置或可用性问题，运行时排除该模型并触发运维告警，不使用熔断掩盖配置错误。

#### 4.3.4.4 恢复动作顺序

失败后先完成当前 Attempt 的 Usage、Cost 和 CapacityReservation 结算，再进行下一步。错误明确绑定 Credential 时，优先 CREDENTIAL_FAILOVER；错误属于瞬时网络或 Provider 服务错误时，在 max_retries 内 RETRY；当前候选重试或换密钥预算耗尽后，在 max_fallbacks 内 FALLBACK。任何动作执行前都重新检查 deadline_at、客户端连接、候选状态、Credential 健康、CircuitState 和容量。

RETRY 保持 route_candidate_id，正常情况下保持 credential_id；错误导致 Credential 状态变化时改用 CREDENTIAL_FAILOVER。CREDENTIAL_FAILOVER 保持 route_candidate_id 并更换 credential_id。FALLBACK 必须更换 route_candidate_id，并根据新候选的 Credential Pool 重新选择凭证。每个动作生成 RecoveryDecision，下一次实际调用生成新的 Attempt 和 CapacityReservation。

#### 4.3.4.5 流式边界与追踪

流式请求在第一个 SSE 业务块发给客户端前可以执行所有允许的恢复动作。首块输出后设置 response_committed=true；后续连接中断、解析错误或超时只允许结束当前流，禁止把另一个 Provider 的输出拼接到已经返回的内容。系统发送包含 UnifiedError 的 SSE data，Trace.status=STREAM_INTERRUPTED，并释放容量。

Trace 详情需要按时间顺序合并 RouteDecision、QueueEntry、CapacityReservation、Attempt、RecoveryDecision 和 CircuitEvent。开发人员可以从一条失败 Trace 看到候选为什么被过滤、是否排队、选择了哪个 Credential、消耗了哪类恢复预算、等待了多久以及最终错误从何产生。

### 4.3.5 运行治理管理接口契约

#### 4.3.5.1 限流策略接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/limit-policies | keyword、scope_type、scope_id、overflow_action、enabled、draft_changed、page、page_size、sort | PageResult<LimitPolicyListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/limit-policies | LimitPolicy 可编辑字段 | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、LIMIT_POLICY_CONFLICT、OBJECT_REFERENCE_INVALID |
| GET /admin/limit-policies/{id} | id | LimitPolicyDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/limit-policies/{id} | LimitPolicy 可编辑字段、version | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_VERSION_CONFLICT、LIMIT_POLICY_CONFLICT |
| POST /admin/limit-policies/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、LIMIT_POLICY_CONFLICT |
| POST /admin/limit-policies/{id}/disable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/limit-policies/{id} | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_IN_USE |
| GET /admin/limit-policies/{id}/usage | id | LimitUsageSnapshot | 系统管理员、运维人员 | OBJECT_NOT_FOUND、CAPACITY_STATE_UNAVAILABLE |
| GET /admin/limit-policies/{id}/queue | status、started_from、started_to、page、page_size | PageResult<QueueEntry> | 系统管理员、运维人员 | OBJECT_NOT_FOUND、FIELD_VALIDATION_FAILED |

限流列表在策略主要信息之外展示作用对象、当前 RPM、TPM 预占与结算、并发数、队列长度和容量存储状态。详情页继续展示当前窗口用量、等待数量、活动配置版本和可执行操作。用量查询只读取当前窗口与并发状态，不创建业务计数；分布式计数存储不可用时返回 CAPACITY_STATE_UNAVAILABLE，页面保留上次成功数据并标明更新时间。

队列接口只提供运行排查所需的只读分页数据。V1.0 不提供从管理页面删除单个 QueueEntry 的操作；业务客户端断开、等待超时、容量取得和运行实例关闭由运行时按状态机结束排队，避免人工删除造成 Trace 与容量状态不一致。

#### 4.3.5.2 可靠性策略接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/reliability-policies | keyword、alias_id、fallback_enabled、enabled、draft_changed、page、page_size、sort | PageResult<ReliabilityPolicyListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| POST /admin/reliability-policies | ReliabilityPolicy 可编辑字段 | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、RELIABILITY_POLICY_CONFLICT、OBJECT_REFERENCE_INVALID |
| GET /admin/reliability-policies/default | 无 | ReliabilityPolicyDefault | 可查看角色 | 无 |
| GET /admin/reliability-policies/{id} | id | ReliabilityPolicyDetail | 可查看角色 | OBJECT_NOT_FOUND |
| PUT /admin/reliability-policies/{id} | ReliabilityPolicy 可编辑字段、version | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_VERSION_CONFLICT、RELIABILITY_POLICY_CONFLICT |
| POST /admin/reliability-policies/{id}/enable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、RELIABILITY_POLICY_CONFLICT |
| POST /admin/reliability-policies/{id}/disable | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/reliability-policies/{id} | version | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_IN_USE |
| GET /admin/reliability-policies/{id}/recovery-decisions | trace_id、action、reason_code、started_from、started_to、page、page_size | PageResult<RecoveryDecision> | 系统管理员、运维人员 | OBJECT_NOT_FOUND、FIELD_VALIDATION_FAILED |

可靠性策略列表展示策略主要信息、关联 Alias、活动配置版本和可执行操作。详情页增加最近发布时间以及近一小时按重试、Fallback、终止聚合的恢复决策数量。系统默认策略使用与新建表单相同的业务配置，页面标识来源为 SYSTEM_DEFAULT，且不允许直接编辑。

恢复决策查询只返回 RecoveryDecision 与来源 Attempt 的 id、sequence、attempt_type、error_code、started_at、ended_at，不返回请求消息、响应正文和 Provider 原始错误正文。页面从决策记录跳转 Trace 详情时，以 trace_id 定位完整时间线。

#### 4.3.5.3 熔断状态接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/circuits | state、provider_id、provider_model_id、credential_id、open_source、has_recent_failure、page、page_size、sort | PageResult<CircuitStateListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| GET /admin/circuits/{id} | id | CircuitStateDetail | 可查看角色 | OBJECT_NOT_FOUND |
| GET /admin/circuits/{id}/events | trigger_type、started_from、started_to、page、page_size | PageResult<CircuitEvent> | 可查看角色 | OBJECT_NOT_FOUND、FIELD_VALIDATION_FAILED |
| POST /admin/circuits/{id}/open | action=MANUAL_OPEN、reason、open_seconds、state_version | CircuitStateDetail | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、CIRCUIT_STATE_CONFLICT |
| POST /admin/circuits/{id}/recover | action=MANUAL_RECOVER、reason、state_version | CircuitStateDetail | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、CIRCUIT_STATE_CONFLICT |
| POST /admin/circuits/{id}/probe | action=PROBE_NOW、state_version、timeout_ms | ProviderCheckRecord | 系统管理员、运维人员 | CIRCUIT_STATE_CONFLICT、CHECK_TARGET_INVALID、CAPACITY_LIMITED |

熔断列表展示状态主要信息，并补充 Provider、Provider Model、Credential 掩码、策略、活动配置版本和可执行操作。详情页展示关联对象的非敏感摘要、生效阈值、最近 50 条状态事件、当前窗口失败样本摘要和可执行操作。

人工 open 与 recover 接口必须提交页面最后读取的 state_version。服务端以 compare-and-set 更新 CircuitState；状态或版本已经改变时返回 CIRCUIT_STATE_CONFLICT 和 current_state_version，页面刷新详情后由操作者重新确认。probe 接口取得一个探测名额后生成 ProviderCheckRecord，target_type=CIRCUIT_STATE；实际外部调用同时生成 HALF_OPEN_PROBE Attempt，检测成功与失败均按 4.3.3.4 更新状态。

#### 4.3.5.4 写入、发布与一致性规则

LimitPolicy 和 ReliabilityPolicy 属于配置实体。新建、编辑、启用、停用和删除在同一数据库事务内写入草稿、递增 version、计算 draft_changed 并生成 AuditLog；运行时继续使用活动 ConfigSnapshot。配置发布成功后，新建 Trace 读取新策略，已经创建的 Trace 继续使用原 snapshot_no 和策略值。

同一 scope_type 与 scope_id 最多存在一条启用的 LimitPolicy；同一 alias_id 最多存在一条启用的 ReliabilityPolicy。唯一约束在保存和发布两个阶段执行。保存阶段发生冲突时返回对应策略冲突错误并给出 conflicting_policy_id；发布校验发现引用状态变化时生成 LIMIT_POLICY_INVALID 或 RELIABILITY_POLICY_INVALID 问题，ConfigValidationResult.status=FAILED，活动快照保持不变。

CircuitState、CircuitEvent 和 CircuitCommand 属于运行态数据，不进入配置草稿。人工状态操作在一个原子事务或等价原子写中更新 CircuitState、追加 CircuitEvent 并生成 AuditLog；任一步失败都不提交状态变化。自动熔断迁移追加 CircuitEvent，不生成管理员 AuditLog，运行指标记录触发原因。

容量预占、分钟窗口计数、队列取得和熔断探测名额必须使用支持原子比较与增减的共享状态存储。Standalone 单实例可以使用进程内实现；集群模式使用共享 Redis 实现。共享状态存储不可用时，系统停止创建新的 CapacityReservation，并返回 CAPACITY_STATE_UNAVAILABLE；已经运行的 Attempt 继续完成本地清理和 Trace 记录，防止绕过全局限额。

#### 4.3.5.5 分页、排序与敏感字段规则

运行治理列表统一使用 page 从 1 开始、page_size 默认 20 且范围 1—100。未指定 sort 时，策略列表按 updated_at desc，熔断列表按 state 优先级 OPEN、HALF_OPEN、CLOSED 后再按 updated_at desc，队列按 sequence asc，事件和恢复决策按 created_at desc。非法字段、超出范围的分页参数或不支持的排序字段返回 FIELD_VALIDATION_FAILED。

系统管理员和运维人员取得的 Credential 字段只包含 name 和 masked_value；开发人员与只读人员的 circuit、limit usage、queue 和 recovery 响应移除 credential_id、credential_name 与 masked_value。所有响应均不得包含 secret_value、完整 secret_ref、Authorization、业务消息正文和 Provider 原始响应正文。错误摘要沿用统一脱敏规则，并始终提供 trace_id、attempt_id 或操作审计 id 中至少一个可追踪标识。

## 4.4 调用观测

调用观测按照“Trace 列表检索—Trace 详情定位—Usage 与 Cost 汇总”的页面顺序组织。用户可以从概览或 Usage 数据点带入筛选进入 Trace 列表，再从单个 Trace 查看路由、Attempt、恢复动作和时间线；需要分析整体消耗时进入 Usage 与 Cost，并可在明细保留期内钻取回 Trace。

### 4.4.1 Trace 列表页

调用观测沿用同类网关按时间、模型、渠道和调用凭证查询日志，并在顶部展示统计汇总和消耗趋势的使用方式。轻享 AI 在此基础上增加 Trace、Attempt、路由判定、恢复动作和费用快照，保证一次业务调用可以完整追溯。参考依据为 [New API 日志与统计](https://docs.newapi.pro/zh/docs/guide/feature-guide/admin/log) 和 [New API 日志管理接口](https://github.com/QuantumNous/new-api-docs/blob/main/docs/en/api/fei-log.md)。

#### 4.4.1.1 页面进入与布局

用户从左侧“调用观测—Trace”进入列表。页面从上到下依次为基础筛选区、高级筛选折叠区、查询操作区、结果表格和分页区。首次进入默认查询当前页面时区最近 1 小时的数据，状态与其他条件为空，按 started_at desc 排序，每页 20 条。页面 URL 保存非敏感查询参数，刷新或从详情返回时恢复原筛选、页码和排序。

基础筛选区放置时间范围、trace_id、application、Model Alias、Provider、Provider Model 和 status。高级筛选区放置 project、tenant、标签、source_mode、Standalone Access Credential、request_user、client_ip、Provider Credential、Attempt 类型、错误、流式、Usage 来源、恢复动作和耗时范围。两类 Credential 选项与 client_ip 只向系统管理员和运维人员开放；只读人员可以看到 Provider 和模型，无法取得 Credential ID 和来源 IP。

#### 4.4.1.2 查询字段

| 顺序 | 字段 | 控件形式 | 默认值 | 查询与校验规则 |
|---:|---|---|---|---|
| 1 | start_at、end_at | 日期时间范围 | 最近 1 小时 | 按 RuntimeConfig.timezone 解释，左闭右开；普通组合查询必填，跨度最大 31 天。 |
| 2 | trace_id | 单行文本 | 空 | 精确匹配，1—128 字符；填写后停用时间以外的业务筛选并忽略分页，仍执行权限校验。 |
| 3 | application | 可输入多选 | 空 | 精确匹配 Trace.application，最多 20 项。 |
| 4 | alias_id | 远程搜索多选 | 空 | 显示 Alias 当前名称；查询使用 ID，历史结果显示 Trace.alias 快照。 |
| 5 | provider_id | 远程搜索多选 | 空 | 匹配 Trace.final_provider_id，最多 20 项。 |
| 6 | provider_model_id | 联动远程搜索多选 | 空 | Provider 已选择时只加载其模型；匹配 final_provider_model_id。 |
| 7 | status | 多选下拉 | 空 | QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED、STREAM_INTERRUPTED。 |
| 8 | project | 可输入多选 | 空 | 精确匹配 Trace.project，最多 20 项。 |
| 9 | tenant | 可输入多选 | 空 | 精确匹配 Trace.tenant，最多 20 项。 |
| 10 | tag_key | 单行文本 | 空 | 与 tag_value 成对使用，键最长 64 字符。 |
| 11 | tag_value | 单行文本 | 空 | 对指定 tag_key 精确匹配，值最长 256 字符。 |
| 12 | source_mode | 多选下拉 | 空 | SDK、EMBEDDED、STANDALONE。 |
| 13 | access_credential_id | 远程搜索多选 | 空 | 匹配 Standalone Access Credential；系统管理员和运维人员可用。 |
| 14 | request_user | 单行文本 | 空 | 精确匹配非敏感业务用户标识，最长 128 字符。 |
| 15 | client_ip | 单行文本 | 空 | 仅敏感诊断权限可用；支持单 IP 精确匹配，未开启 IP 记录时停用。 |
| 16 | credential_id | 远程搜索多选 | 空 | 受控字段，匹配 Trace.final_credential_id；选项显示 name 和 masked_value。 |
| 17 | attempt_type | 多选下拉 | 空 | 查询存在指定 Attempt 类型的 Trace。 |
| 18 | error_code | 可输入多选 | 空 | 精确匹配 Trace.error_code，最多 20 项。 |
| 19 | requested_stream | 单选下拉 | 全部 | 全部、流式、同步。 |
| 20 | usage_source | 多选下拉 | 空 | ACTUAL、ESTIMATED、MIXED。 |
| 21 | has_retry | 三态开关 | 全部 | true 匹配 retry_count>0，false 匹配 retry_count=0。 |
| 22 | has_credential_failover | 三态开关 | 全部 | 按 credential_failover_count 是否大于 0 查询。 |
| 23 | has_fallback | 三态开关 | 全部 | 按 fallback_count 是否大于 0 查询。 |
| 24 | min_total_ms | 非负整数 | 空 | 与 max_total_ms 组合过滤已结束 Trace；范围 0—600000。 |
| 25 | max_total_ms | 非负整数 | 空 | 必须大于等于 min_total_ms，最大 600000。 |
| 26 | anomalous_running | 三态开关 | 全部 | true 仅查询超过策略总超时加清理宽限的运行中 Trace。 |
| 27 | sort | 单选下拉 | started_at desc | 支持 started_at、total_ms、total_tokens、total_cost；trace_id 精确查询时忽略。 |
| 28 | page、page_size | 分页控件 | 1、20 | page 从 1 开始；page_size 支持 20、50、100。 |

#### 4.4.1.3 列表字段

| 顺序 | 列字段 | 数据来源 | 展示与交互规则 |
|---:|---|---|---|
| 1 | started_at | TraceListItem.started_at | 按页面时区展示到毫秒。 |
| 2 | trace_id | TraceListItem.trace_id | 等宽字体，点击进入详情，复制操作不进入详情。 |
| 3 | source_mode | TraceListItem.source_mode、access_credential_name | 显示产品形态；Standalone 在同一单元格展示访问凭证名称。 |
| 4 | application | TraceListItem.application | project 或 tenant 存在时在同一单元格分行展示。 |
| 5 | alias | TraceListItem.alias | 展示历史快照名称；关联对象存在时可进入 Alias 详情。 |
| 6 | final_provider_name | TraceListItem.final_provider_name | 未创建 Attempt 时显示空。 |
| 7 | final_provider_model_name | TraceListItem.final_provider_model_name | 点击进入 Provider Model 详情；对象已删除时只展示快照。 |
| 8 | requested_stream | TraceListItem.requested_stream | 显示“流式”或“同步”。 |
| 9 | status | TraceListItem.status | 使用状态文本；anomalous_running=true 时显示“运行异常”。 |
| 10 | attempt_count | TraceListItem.attempt_count | 大于 1 时可展开显示 retry、credential failover、fallback 数量。 |
| 11 | queued_ms | TraceListItem.queued_ms | 无排队显示空；有值显示毫秒和换算后的秒值。 |
| 12 | first_token_ms | TraceListItem.first_token_ms | 仅流式且已输出首 Token 时显示。 |
| 13 | total_ms | TraceListItem.total_ms | 运行中使用临时计算值并明确标识；最终值只取持久化字段。 |
| 14 | total_tokens | TraceListItem.total_tokens | 展示全部 Attempt Token，usage_source 同时显示。 |
| 15 | total_cost | TraceListItem.total_cost、currency | 固定八位小数；无计费用量时显示空。 |
| 16 | error_code | TraceListItem.error_code | 点击后把当前错误码加入筛选；成功时为空。 |
| 17 | actions | TraceListItem.actions | 查看详情、复制 Trace ID、查看 Alias、查看模型、筛选同类错误。 |

#### 4.4.1.4 操作定义

| 操作 | 权限与前置条件 | 系统处理 | 页面结果与异常 |
|---|---|---|---|
| 查询 | 可查看角色；字段校验通过 | 按统一查询对象读取 Trace 列表和 total | 回到第 1 页；失败保留输入并定位错误字段。 |
| 重置 | 可查看角色 | 清除筛选并恢复最近 1 小时 | 立即重新查询。 |
| 手动刷新 | 可查看角色 | 保持条件和页码重新查询 | 更新 RUNNING、QUEUED 和 anomalous_running。 |
| 自动刷新 | 可查看角色；当前结果存在运行中 Trace | 按 dashboard_refresh_seconds 轮询当前页 | 离开页面、浏览器隐藏或无运行中数据时停止。 |
| 查看详情 | 可查看角色；Trace 存在 | 查询完整 TraceDetail | 在当前列表路由下进入详情，返回时恢复列表状态。 |
| 复制 Trace ID | 可查看角色 | 复制 trace_id | 成功后短暂显示复制结果，不写审计。 |
| 导出当前结果 | 系统管理员、运维人员；必须填写时间范围 | 使用与列表一致的筛选生成 CSV | 超出条数限制返回 EXPORT_TOO_LARGE，页面要求缩小范围。 |

#### 4.4.1.5 查询与数据流

普通组合查询先按 started_at、权限数据范围和筛选条件定位 Trace，再通过 final_attempt_id 读取最终 Provider、模型和 Credential 快照。Attempt 类型筛选使用存在性查询，不改变列表一行一个 Trace 的结构。服务端必须在一次分页查询或批量装配中返回 TraceListItem，禁止逐行查询 Attempt。

trace_id 精确查询不要求时间范围，可以在 trace_retention_days 内直接定位；仍需验证当前角色的数据范围。找不到时返回空列表，不泄露该 ID 是否曾由其他应用产生。RUNNING 和 QUEUED 的 total_ms 只在响应层按当前时间计算，不回写数据库；达到最终状态后使用 ended_at-started_at 固化。

列表 total 表示满足筛选的 Trace 数量。排序只允许白名单字段，total_cost 排序要求查询指定单一 currency；未指定币种且结果包含多个币种时，页面停用费用排序。查询条件、列表 total 和导出必须复用同一查询规范。

完成标准：精确 trace_id 一次查询定位；组合筛选、分页和排序结果稳定；Attempt 存在性筛选不产生重复 Trace；列表、概览钻取和导出使用相同数据范围；页面与接口不出现 Credential 原文、Token、Authorization 和消息正文。

### 4.4.2 Trace 详情页

#### 4.4.2.1 页面顺序与加载

详情页按照排查问题的实际阅读顺序展示 Trace 摘要、请求摘要、统一时间线、Attempt 明细、Usage 与 Cost、最终错误六个区域。页面顶部保留“返回列表”和“复制 Trace ID”。Trace 处于 QUEUED 或 RUNNING 时，每 5 秒刷新详情；进入最终状态后停止刷新。刷新保持已展开的 Attempt 和当前滚动位置。

详情接口一次返回 TraceDetail。服务端先读取 Trace，再按 trace_id 批量读取 RouteDecision、QueueEntry、CapacityReservation、Attempt、RecoveryDecision 和 CircuitEvent，最后装配 Timeline、UsageCostSummary 和 ErrorDetail。任何下级记录读取失败都返回 OBSERVATION_DATA_UNAVAILABLE，页面不展示可能互相矛盾的局部详情。

#### 4.4.2.2 区域字段

| 页面区域 | 字段 | 数据来源 | 展示规则 |
|---|---|---|---|
| Trace 摘要 | trace_id、status、started_at、ended_at、total_ms | Trace | status 与最终时间放在首行；运行中 total_ms 动态计算。 |
| Trace 摘要 | application、project、tenant、tags | Trace | tags 按键排序；敏感键在写入阶段已经拒绝。 |
| Trace 摘要 | alias、config_snapshot_no、source_mode | Trace | Alias 使用快照名称，snapshot_no 可复制。 |
| Trace 摘要 | requested_stream、response_committed、finish_reason | Trace | finish_reason 只在成功时展示。 |
| Trace 摘要 | attempt_count、retry_count、credential_failover_count、fallback_count、queued_ms、first_token_ms | Trace | 作为本次调用执行概况。 |
| 请求摘要 | source_mode、access_credential_name、request_user、client_ip、user_agent、config_snapshot_no | TraceRequestSummary | access credential 只显示名称；client_ip 仅敏感诊断权限可见。 |
| 请求摘要 | message_count、system_message_count、user_message_count、assistant_message_count、input_char_count | TraceRequestSummary | 只展示数量。 |
| 请求摘要 | requested_stream、temperature、top_p、max_tokens、stop_count、provider_option_keys | TraceRequestSummary | 展示运行时解析后的实际值。 |
| 请求摘要 | content_sample_status、sampled_messages | TraceRequestSummary | 默认 DISABLED；只有敏感诊断权限与 AVAILABLE 同时满足时展示脱敏样本。 |
| 路由判定 | sequence、route_candidate_id、decision、reason_code、reason_detail、observed_status、created_at | RouteDecision | 按 sequence 展示候选过滤、选择、排队和失败原因。 |
| 排队记录 | blocking_policy_ids、estimated_tokens、sequence、status、enqueued_at、deadline_at、acquired_at、ended_at、wake_reason、error_code | QueueEntry | 未排队时隐藏该节点；策略 ID 可进入限流策略详情。 |
| 容量预占 | id、attempt_id、policy_ids、reserved_tokens、actual_tokens、status、release_reason、created_at、settled_at | CapacityReservation | 归属于对应 Attempt；异常 EXPIRED 明确标记。 |
| Attempt 概览 | sequence、attempt_type、status、provider_name_snapshot、provider_model_name_snapshot、model_id_snapshot、credential_name_snapshot | Attempt | Credential 只显示名称和当前 masked_value；当前对象删除时使用名称快照。 |
| Attempt 时间 | started_at、provider_started_at、response_headers_at、first_token_at、ended_at、dispatch_ms、response_header_ms、first_token_ms、total_ms | Attempt | 时间轴和毫秒数同时展示，缺失阶段为空。 |
| Attempt 外部响应 | endpoint_host、http_status、provider_request_id、response_committed、finish_reason | Attempt | endpoint_host 不含路径参数；Provider Request ID 可复制。 |
| Attempt 错误 | error_category、error_stage、error_code、error_summary、retryable、retry_after_ms | Attempt | 只展示脱敏摘要。 |
| 恢复决策 | sequence、source_attempt_id、action、reason_code、scheduled_delay_ms、target_route_candidate_id、target_credential_id、三类 used、remaining_timeout_ms、created_at | RecoveryDecision | 放在来源 Attempt 结束和目标 Attempt 开始之间。 |
| 熔断事件 | circuit_id、from_state、to_state、trigger_type、error_code、reason、created_at | CircuitEvent | 只显示 trigger_trace_id 等于本 Trace 的事件。 |
| Usage 与 Cost | 输入、输出、总 Token、费用、币种、是否估算 | Trace、Attempt、价格快照 | 总计在上，各 Attempt 按序号在下。 |
| 最终错误 | 错误码、分类、可重试标志、失败 Attempt、简要原因 | Trace 与最终 Attempt | Trace 成功时整个区域隐藏。 |
| 保留信息 | detail_expires_at、content_sample_status | TraceDetail | 告知明细和诊断样本预计保留状态。 |

#### 4.4.2.3 统一时间线规则

时间线由 TraceTimelineItem 组成。首先放置 TRACE_CREATED；QueueEntry 产生时放置 QUEUE_ENTERED，成功取得容量时放置 QUEUE_ACQUIRED，TIMEOUT、CANCELLED 或 REJECTED 时放置 QUEUE_ENDED；每条 RouteDecision 放置 ROUTE_DECISION；每个 Attempt 至少放置 ATTEMPT_STARTED 与 ATTEMPT_ENDED，存在 first_token_at 时增加 ATTEMPT_FIRST_TOKEN；失败后放置 RECOVERY_DECIDED；触发熔断迁移时放置 CIRCUIT_CHANGED；最后放置 TRACE_ENDED。

所有事件按 occurred_at 升序排列。时间相同时依次使用 Trace、Queue、RouteDecision、Attempt Started、Attempt First Token、Attempt Ended、RecoveryDecision、CircuitEvent、Trace Ended 的固定优先级，再使用来源 sequence。该排序规则由服务端实现，前端不得重新推断。时间线中的 reason_code 可以定位原始 RouteDecision、RecoveryDecision 或 Attempt。

点击 Attempt 时间线节点打开明细抽屉。抽屉顶部展示路径和状态，中部展示阶段耗时，底部依次展示外部响应、Usage/Cost、容量预占和错误。点击 RecoveryDecision 节点同时高亮来源 Attempt 与目标 Attempt；action=FAIL 时只高亮来源 Attempt。

#### 4.4.2.4 Usage、Cost 与错误口径

Trace.input_tokens、output_tokens、total_tokens 和 total_cost 汇总全部产生用量或费用的 Attempt，包括失败后的 RETRY、CREDENTIAL_FAILOVER 和 FALLBACK。response_*_tokens 只来自最终成功 Attempt，用于说明调用方收到的 Usage。最终成功但存在前序失败时，Trace.total_tokens 可以大于 response_total_tokens。

每个 Attempt 的 input_price、output_price、price_unit 和 currency 从 config_snapshot_no 固化。input_cost 等于 input_tokens ÷ price_unit × input_price，output_cost 使用相同公式；cost 等于两项之和并四舍五入到八位小数。Trace.total_cost 等于同币种 Attempt.cost 之和。同一 Alias 的启用候选发布时已经保证 currency 一致。

Provider 返回 Usage 时标记 ACTUAL；响应未提供 Usage 或失败后无法读取完整 Usage 时由 Adapter TokenEstimator 生成 ESTIMATED。Trace 同时包含两类 Attempt 时标记 MIXED。结算异常不得清空已取得的 Usage，错误阶段记录为 SETTLEMENT并触发内部告警。

ErrorDetail 使用 Trace.error_code 和 error_summary 作为最终口径，同时展示 final Attempt 的 http_status、provider_request_id、retryable 和 retry_after_ms。前序失败通过 failed_attempt_ids 进入相应 Attempt 查看。Provider 原始错误正文不直接返回管理页面。

#### 4.4.2.5 操作与权限

| 操作 | 权限与前置条件 | 处理结果 | 限制 |
|---|---|---|---|
| 返回列表 | 可查看角色 | 恢复原查询和滚动位置 | 无。 |
| 复制 Trace ID | 可查看角色 | 复制 trace_id | 不写审计。 |
| 复制 Provider Request ID | 系统管理员、运维人员；字段存在 | 复制选中 Attempt 的标识 | 只读人员不可见完整值。 |
| 查看配置快照 | 系统管理员、运维人员 | 打开 snapshot_no 对应发布记录 | 快照已超出保留范围时只显示编号。 |
| 查看关联对象 | 具有对应页面权限 | 打开 Alias、Provider、模型、凭证池或策略详情 | 对象已删除时保持历史快照展示。 |
| 查看诊断样本 | 具有敏感诊断权限；采样启用且样本未过期 | 展开 sampled_messages | 每次查看写 AuditLog，导出仍不包含样本。 |
| 筛选同类错误 | 可查看角色；error_code 存在 | 返回 Trace 列表并带入相同时间范围和错误码 | 保留当前数据权限。 |

完成标准：Attempt 数量与 Trace.attempt_count 一致；时间线能够按顺序解释过滤、排队、外部调用、恢复和最终状态；每笔 Token 与费用可以追溯到 Attempt；响应 Usage 与运行总消耗得到区分；运行中刷新不重复节点；任何页面区域、接口和复制操作均不泄露认证信息与默认消息正文。

### 4.4.3 Usage 与 Cost 页

#### 4.4.3.1 页面布局与筛选字段

页面从上到下依次为筛选区、UsageSummary 摘要区、趋势区和分组区。首次进入查询最近 7 个完整自然日加当前日，granularity=DAY，group_by=ALIAS，trend_metric=REQUEST_COUNT。筛选条件由摘要、趋势和分组三个接口共用，任一条件变化后同时刷新三个区域；页面以同一个 query_fingerprint 标识本次查询，避免区域显示不同条件的数据。

| 顺序 | 字段 | 控件形式 | 默认值 | 查询与校验规则 |
|---:|---|---|---|---|
| 1 | start_at、end_at | 日期时间范围 | 最近 7 天 | 左闭右开；不得早于 usage_retention_days 可查询范围。 |
| 2 | granularity | 单选 | DAY | HOUR、DAY；HOUR 最大跨度 31 天，DAY 最大跨度 3650 天。 |
| 3 | application | 可输入多选 | 空 | 最多 20 项，精确匹配。 |
| 4 | project | 可输入多选 | 空 | 最多 20 项，精确匹配。 |
| 5 | tenant | 可输入多选 | 空 | 最多 20 项，精确匹配。 |
| 6 | alias_id | 远程搜索多选 | 空 | 最多 20 项。 |
| 7 | provider_id | 远程搜索多选 | 空 | 最多 20 项。 |
| 8 | provider_model_id | 联动远程搜索多选 | 空 | 最多 20 项。 |
| 9 | credential_pool_id | 远程搜索多选 | 空 | 系统管理员和运维人员可用。 |
| 10 | credential_id | 联动远程搜索多选 | 空 | 只显示 name、masked_value；最多 20 项。 |
| 11 | trace_status | 多选下拉 | 空 | 按 Trace 最终状态过滤。 |
| 12 | error_code | 可输入多选 | 空 | 最多 20 项。 |
| 13 | usage_source | 多选下拉 | 空 | ACTUAL、ESTIMATED；MIXED 由 Trace 级汇总计算。 |
| 14 | requested_stream | 单选下拉 | 全部 | 全部、流式、同步。 |
| 15 | currency | 可搜索单选 | 全部 | 全部时费用分币种展示；指定后显示单一总费用。 |
| 16 | trend_metric | 单选 | REQUEST_COUNT | REQUEST_COUNT、SUCCESS_RATE、ATTEMPT_COUNT、TOKEN、COST、RETRY、CREDENTIAL_FAILOVER、FALLBACK。 |
| 17 | group_by | 单选 | ALIAS | 取 UsageGroupRow.dimension_type 支持值。 |
| 18 | group_sort | 单选 | TOTAL_COST desc | 支持 REQUEST_COUNT、ATTEMPT_COUNT、TOTAL_TOKENS、TOTAL_COST 和 DIMENSION_NAME。 |
| 19 | group_page、group_page_size | 分页控件 | 1、20 | page_size 支持 20、50、100。 |

credential_pool_id、credential_id 以及 group_by=CREDENTIAL_POOL 或 CREDENTIAL 只向系统管理员和运维人员开放。开发人员的 application 条件由访问范围强制注入且不可清除；只读人员按宿主数据范围查询。无权使用的筛选字段返回 ACCESS_DENIED，不静默忽略。

#### 4.4.3.2 摘要区字段

摘要第一行按顺序展示 request_count、success_rate、attempt_count、total_tokens 和费用；第二行展示 success_count、failure_count、cancelled_count、queued_count、stream_count、stream_interrupted_count、initial_count、retry_count、credential_failover_count、fallback_count 和 half_open_probe_count；Token 展开项显示 input_tokens、output_tokens、actual_tokens、estimated_tokens 和 actual_token_rate。

费用指定单一 currency 时展示 input_cost、output_cost 和 total_cost。查询包含多个币种时，total_cost 主卡不执行换算，按 UsageSummary.costs 逐币种显示金额；input_cost 和 output_cost 同样按币种在展开区分组。所有金额保持八位小数，页面不使用浮点数进行二次相加。

success_rate 的分母为 SUCCEEDED、FAILED 和 STREAM_INTERRUPTED，QUEUED、RUNNING 与 CANCELLED 不进入分母。摘要数据来自 summary 接口，不通过趋势点或分组行相加获得。data_updated_at 与当前时间相差超过两个 dashboard_refresh_seconds 时，页面显示数据延迟状态。

#### 4.4.3.3 趋势区字段与交互

趋势横轴使用 UsageTrendPoint.bucket_start，提示信息展示 bucket_start、bucket_end 和当前指标值。REQUEST_COUNT 展示请求总数并分成功、失败；SUCCESS_RATE 使用每个桶自己的成功率；ATTEMPT_COUNT 分 INITIAL、RETRY、CREDENTIAL_FAILOVER、FALLBACK、HALF_OPEN_PROBE；TOKEN 分 actual_tokens 与 estimated_tokens；COST 按 currency 生成独立序列；其余恢复指标展示对应 Attempt 数。

HOUR 使用页面时区自然小时，DAY 使用页面时区自然日。缺失时间桶由服务端补零；费用多币种时每个币种单独补零。点击数据点进入 Trace 列表，带入该桶时间、当前可映射筛选条件和指标对应条件。目标时间超出 trace_retention_days 时停用钻取，并保留 Usage 聚合查看能力。

#### 4.4.3.4 分组区字段与交互

分组表依次显示 dimension_name、request_count、success_count、failure_count、success_rate、attempt_count、initial_count、retry_count、credential_failover_count、fallback_count、half_open_probe_count、actual_tokens、estimated_tokens、total_tokens、input_cost、output_cost、total_cost、request_share、token_share 和 cost_share。对象维度使用当前对象名称；对象已删除时使用聚合时保存的历史名称。空 project、tenant 或 error_code 使用固定值“未设置”，不得与字符串值相同。

APPLICATION、PROJECT、TENANT 和 ALIAS 按 Trace 归属计算请求指标，并把该 Trace 的全部 Attempt、Token 和费用归入同一业务维度。PROVIDER、PROVIDER_MODEL、CREDENTIAL_POOL 和 CREDENTIAL 的 request_count 只归入 final_attempt_id 对应路径；attempt_count、Token 和费用按每个实际 Attempt 路径归入，因此前序失败渠道可能出现 request_count=0 且 cost>0。TRACE_STATUS 把 Trace 的全部 Attempt 归入最终状态。ERROR_CODE 的 request_count 使用 Trace 最终 error_code，Attempt 与费用使用各 Attempt.error_code；无错误使用“未设置”。USAGE_SOURCE 的 Token 与费用按 Attempt.usage_source 归入，request_count 使用 final_attempt_id 的 usage_source；没有 Attempt 或没有 Usage 时归入“未设置”。

所有 group_by 维度的 request_count 均按上述最终归属保持互斥，因此 request_share 合计为 100%。token_share 和 cost_share 按实际 Attempt 归属计算；没有 Token 或费用的查询中对应占比为空。

点击分组行进入 Trace 列表时，业务维度、最终路径和 Trace 状态可以直接映射。按 Attempt 错误或 Usage 来源分组时，跳转附加 attempt_type 或存在性条件，由 Trace 列表返回包含相应 Attempt 的 Trace。时间超出 Trace 明细保留期时停用跳转。

#### 4.4.3.5 操作定义

| 操作 | 权限与前置条件 | 系统处理 | 页面结果与异常 |
|---|---|---|---|
| 查询 | 可查看角色；时间与字段合法 | 使用同一筛选生成 query_fingerprint，加载摘要、趋势和分组 | 任一接口失败时该区域显示失败，保留其他已成功区域及统一条件。 |
| 重置 | 可查看角色 | 恢复最近 7 天、DAY、ALIAS | 同时刷新全部区域。 |
| 切换趋势指标 | 可查看角色 | 只重新查询 trends | 摘要和分组保持不变。 |
| 切换分组维度 | 可查看角色 | group_page 回到 1，只重新查询 groups | 保留筛选与趋势。 |
| 查看 Trace | 可查看角色；时间仍在 Trace 保留期 | 跳转 Trace 列表并映射条件 | 无法映射的聚合维度不生成错误筛选。 |
| 导出 | 系统管理员、运维人员；时间范围必填 | 按当前 granularity、group_by 和筛选流式生成 CSV | 超过 100000 行返回 EXPORT_TOO_LARGE。 |

#### 4.4.3.6 聚合计算与数据流

一次 Trace 进入最终状态后，服务端在保存最终 Trace、最后 Attempt 和 Usage/Cost 结算的同一业务事务中写入唯一 UsageAggregationEvent。聚合处理器按事件 id 幂等消费，使用 Trace.started_at 作为该 Trace 及其全部 Attempt 的时间归属，分别更新 HOUR 和 DAY 两种 UsageAggregate。采用 Trace 开始时间可以保证一次调用的请求、恢复动作、Token 和费用处于同一时间桶。

request_count 每个 Trace 只增加 1；attempt_count 每个 Attempt 增加 1；initial_count、retry_count、credential_failover_count、fallback_count 和 half_open_probe_count 分别按 attempt_type 增加。Token 与费用只统计已经完成结算的 Attempt。Provider 没有返回 Usage 时使用 TokenEstimator，并把 Token 计入 estimated_*；实际与估算不得重复累计。Trace 最终事务提交后，Trace、Attempt、Token 和价格快照保持不可变；事件处理失败只重试同一事件，不修改源数据。

聚合处理器为每个 Trace 生成一条请求贡献和每个 Attempt 一条执行贡献。请求贡献的 request_count、成功、失败、取消、排队和流式指标按 Trace 取值，路径维度使用 final_attempt_id；未创建 Attempt 时路径与 Usage 来源为空，currency 使用 Trace.currency。请求贡献的 Attempt、Token 和费用指标全部为 0。执行贡献的 request_count 等 Trace 指标为 0，Attempt、恢复动作、Token 和费用按实际 Attempt 取值。两类贡献按相同 dimension_key 合并，因此任意查询层级都不会重复统计 request_count，同时保留前序失败路径产生的 Token 和费用。

UsageAggregate 唯一键由 granularity、bucket_start、维度组合和 currency 构成。聚合更新使用数据库原子增量或等价事务，updated_at 在提交后更新。summary、trends 和 groups 使用相同的聚合查询构造器；summary 直接对符合维度的聚合行求和，groups 按 group_by 重新归并，trends 按 bucket_start 补齐时间桶。

聚合处理器每次原子取得一条 PENDING，或 next_retry_at 已到达的 FAILED 事件，写入 locked_by、locked_at 并进入 PROCESSING。处理实例超过 120 秒未完成时，其他实例可以接管。HOUR、DAY 聚合更新与事件 SUCCEEDED 状态在同一数据库事务提交；失败时回滚所有聚合增量，事件记录错误并按 1、2、4、8、16 分钟退避，之后固定每 30 分钟重试。连续失败 10 次产生运行告警，仍保留事件继续重试。

聚合允许存在不超过两个 dashboard_refresh_seconds 的延迟。Trace 明细清理前，清理任务必须确认对应 UsageAggregationEvent.status=SUCCEEDED；未消费记录跳过清理并告警。Usage 保留期到达后按 bucket_end 分批删除 HOUR 和 DAY 聚合行。

完成标准：同一筛选下摘要、趋势和分组共享查询条件；请求、Attempt、Token 和费用口径可以从保留期内 Trace/Attempt 复算；重放聚合事件不重复累计；实际与估算用量分别可见；价格修改不改变历史费用；多币种不做汇率换算；钻取条件与原聚合维度一致。

### 4.4.4 调用观测管理接口契约

#### 4.4.4.1 Trace 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/traces | TraceListQuery | PageResult<TraceListItem> | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/traces/{traceId} | traceId | TraceDetail | 可查看角色 | OBJECT_NOT_FOUND、ACCESS_DENIED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/traces/export | TraceListQuery，忽略 page、page_size | text/csv 流 | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、EXPORT_TOO_LARGE、OBSERVATION_DATA_UNAVAILABLE |

PageResult<TraceListItem> 返回 items、total、page、page_size、sort、query_started_at 和 data_updated_at。精确 trace_id 查询成功时 items 最多一条，page 固定 1。TraceDetail 中 attempts 数量必须等于 trace.attempt_count；route_decisions、recovery_decisions 和 timeline 已按各自顺序排序。

详情数据权限先按 Trace.application 和宿主数据范围判断，再读取下级数据。没有权限统一返回 ACCESS_DENIED，不通过子实体接口绕过 Trace 权限。Credential ID、Provider Request ID 和诊断样本根据字段权限在序列化阶段移除，服务端日志不得记录移除前的响应对象。

#### 4.4.4.2 Usage 与 Cost 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/usage/summary | UsageQuery，忽略 trend_metric 与分组分页字段 | UsageSummaryResult | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/usage/trends | UsageQuery，使用 trend_metric | UsageTrendResult | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/usage/groups | UsageQuery，使用 group_by、group_sort 与分组分页字段 | UsageGroupResult | 可查看角色 | FIELD_VALIDATION_FAILED、OBSERVATION_DATA_UNAVAILABLE |
| GET /admin/usage/export | UsageQuery，忽略 group_page、group_page_size | text/csv 流 | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、EXPORT_TOO_LARGE、OBSERVATION_DATA_UNAVAILABLE |

四个接口对同一组筛选字段生成相同 query_fingerprint，并在响应中返回。调用方同时请求三个页面接口时，应以相同 query_fingerprint 和服务端解析后的 start_at、end_at、timezone、currency_filters 为一致性依据。data_updated_at 允许在接口执行期间产生轻微差异，页面取三个结果中最早时间作为整页数据更新时间。

查询未指定 currency 时，summary.costs 按币种返回，trends 的同一 bucket 可以包含多个 currency 点，groups 按 dimension 与 currency 拆行。请求 total_cost 排序或 cost_share 时必须指定单一 currency；否则返回 FIELD_VALIDATION_FAILED 并指出 currency 字段。

### 4.4.5 导出、保留与清理规则

Trace 导出文件名为 traces-{start_at}-{end_at}.csv，字段顺序为 started_at、trace_id、source_mode、access_credential_name、application、project、tenant、alias、requested_stream、final_provider_name、final_provider_model_name、status、attempt_count、retry_count、credential_failover_count、fallback_count、queued_ms、first_token_ms、total_ms、usage_source、input_tokens、output_tokens、total_tokens、total_cost、currency、error_code。每行对应一个 Trace，不展开 Attempt、client_ip、user_agent 和诊断样本。

Usage 导出文件名为 usage-{granularity}-{group_by}-{start_at}-{end_at}.csv，字段顺序为 bucket_start、bucket_end、dimension_type、dimension_id、dimension_name、request_count、success_count、failure_count、success_rate、attempt_count、initial_count、retry_count、credential_failover_count、fallback_count、half_open_probe_count、actual_tokens、estimated_tokens、total_tokens、input_cost、output_cost、total_cost、currency。每行对应一个时间桶、分组值和币种组合。

导出使用 UTF-8 BOM 和 RFC 4180 CSV 转义。文本字段以 =、+、- 或 @ 开头时在值前增加单引号，防止电子表格公式执行。服务端边查询边写响应，不把完整文件保存在数据库或本地目录；最大 100000 行、最长执行 60 秒。超过行数在发送响应头前返回 EXPORT_TOO_LARGE；执行中连接断开时取消数据库游标并停止生成。

Trace、Attempt、RouteDecision、QueueEntry、CapacityReservation、RecoveryDecision、CircuitEvent 关联明细按 trace_retention_days 同批清理。诊断样本按 diagnostic_sample_retention_days 单独提前清理。UsageAggregate 按 usage_retention_days 保留，清理 Trace 前必须确认聚合完成。清理每批最多 1000 个 Trace，批次之间提交事务，失败批次记录起止 ID 和错误并等待下一次任务重试。

保留期缩短只影响新配置发布后的清理任务，不恢复已删除数据。页面时间选择器根据可查询起点限制 Trace 和 Usage 范围；从 Usage 钻取到已超出 Trace 保留期的时间时停用入口。导出与页面读取遵循相同保留期和权限，不提供绕过清理策略的历史恢复接口。

## 4.5 运行配置

运行配置按照“查看待发布变更—执行校验与发布—维护运行参数—管理 Standalone Access Credential—查询审计日志”的顺序组织。模型接入和治理配置先进入待发布变更，运行参数同样经快照发布生效；访问凭证属于安全即时配置，不进入模型运行快照；所有成功与失败管理操作最终可以在审计日志中查询。

### 4.5.1 待发布变更页

#### 4.5.1.1 页面进入与信息顺序

用户从左侧“运行配置—待发布变更”进入页面。页面从上到下依次展示草稿状态、变更摘要、筛选区、分组变更列表和底部固定操作区。草稿状态展示 base_snapshot_no、draft_revision、change_count、status、first_modified_at 和 last_modified_at。摘要按照新增、修改、启用、停用、删除以及对象类型显示 DraftChangeSummary。

变更列表默认按 entity_type 的依赖顺序分组：Provider、Credential Pool、Credential、Provider Model、Model Alias、Route Candidate、Limit Policy、Reliability Policy、Runtime Config；组内按 modified_at desc。运行状态、Trace、Usage、CircuitState、ProviderCheckRecord、Standalone Access Credential 和 AuditLog 不进入草稿列表。

#### 4.5.1.2 筛选与列表字段

| 页面区域 | 字段 | 控件或展示形式 | 数据来源 | 规则 |
|---|---|---|---|---|
| 筛选区 | keyword | 单行文本 | 查询参数 | 匹配 entity_name 或 entity_id。 |
| 筛选区 | entity_type | 多选下拉 | DraftChange.entity_type | 默认全部配置实体。 |
| 筛选区 | change_type | 多选下拉 | DraftChange.change_type | CREATE、UPDATE、ENABLE、DISABLE、DELETE。 |
| 筛选区 | modified_by | 远程搜索多选 | DraftChange.modified_by | 显示 modified_by_name。 |
| 筛选区 | modified_from、modified_to | 日期时间范围 | DraftChange.modified_at | 可为空，使用页面时区。 |
| 草稿状态 | base_snapshot_no | 数字链接 | ConfigDraftState | 点击进入当前活动快照摘要。 |
| 草稿状态 | draft_revision | 数字 | ConfigDraftState | 任一草稿写操作后变化。 |
| 草稿状态 | status | 文本 | ConfigDraftState.status | PUBLISHING 时停用配置编辑和撤销。 |
| 摘要 | total_count 与各 change count | 数字 | DraftChangeSummary | 点击某类数量写入 change_type 筛选。 |
| 列表 | entity_type | 分组标题 | DraftChange.entity_type | 按固定依赖顺序展示。 |
| 列表 | change_type | 文本 | DraftChange.change_type | 直接表达新增、修改、启用、停用或删除。 |
| 列表 | entity_name、entity_id | 链接与等宽文本 | DraftChange | 点击进入对象详情；删除草稿打开只读差异。 |
| 列表 | changed_fields | 差异表 | array<FieldChange> | 每个字段展示 before_value、after_value；sensitive=true 时只显示“敏感字段已变更”。 |
| 列表 | dependency_summary | 数量链接 | DraftChange | 展开查看关联草稿对象。 |
| 列表 | modified_by_name | 文本 | DraftChange | 同时保留 modified_by 用于查询。 |
| 列表 | modified_at | 时间 | DraftChange | 按页面时区展示。 |
| 列表 | revertable | 操作状态 | DraftChange | false 时展示 revert_blockers。 |
| 列表 | entity_version | 隐藏操作参数 | DraftChange | 单项撤销时提交。 |

#### 4.5.1.3 操作定义

| 操作 | 权限与前置条件 | 请求数据 | 服务端变化 | 页面结果与异常 |
|---|---|---|---|---|
| 查询 | 可查看角色 | 筛选、page、page_size | 无 | 返回当前 draft_revision 下的 PageResult<DraftChange>。 |
| 查看对象 | 具有对象查看权限 | entity_type、entity_id | 无 | 打开对象草稿详情。 |
| 查看字段差异 | 可查看角色 | DraftChange.id | 无 | 展开完整脱敏 FieldChange。 |
| 撤销单项 | 系统管理员；status=EDITABLE；revertable=true | RevertDraftCommand | 恢复活动快照值或删除未发布新建对象，递增 draft_revision，写 AuditLog | 成功后刷新摘要和当前组；版本变化返回 CONFIG_DRAFT_CHANGED。 |
| 撤销全部 | 系统管理员；change_count>0；status=EDITABLE | RevertAllDraftCommand | 在单一事务中恢复 base_snapshot_no 的全部配置，清空 DraftChange，递增 draft_revision，写逐对象审计和汇总审计 | 任一对象恢复失败则全部回滚。 |
| 校验并发布 | 系统管理员；change_count>0；status=EDITABLE | 当前 draft_revision | 无直接变更 | 进入 4.5.2 校验步骤。 |

#### 4.5.1.4 草稿修订与差异数据流

任一配置实体的新建、编辑、启用、停用、删除或 RuntimeConfig 修改，在同一事务内锁定 ConfigDraftState，校验实体 version，写入草稿实体和 AuditLog，再把 draft_revision 增加 1。事务失败时实体、审计和 draft_revision 一并回滚。草稿对象保留自身 version，用于同一对象并发编辑；draft_revision 用于保护跨对象校验、撤销全部和发布。

DraftChange 由当前草稿与 base_snapshot_no 对应 ConfigSnapshot 比较生成。活动快照中不存在而草稿存在的对象为 CREATE；两边存在且 enabled 从 false 变 true 为 ENABLE，从 true 变 false 为 DISABLE；草稿标记删除为 DELETE；其他字段变化为 UPDATE。一个对象只生成一条 DraftChange，changed_fields 包含全部差异。

Credential 的 secret_value、Secret 解析值和 Standalone Token 不进入 ConfigSnapshot。Credential 密钥轮换立即更新密钥存储，配置字段未变化时不产生 DraftChange；Credential 新建、池关系、限额、权重和启停属于配置草稿。Standalone Access Credential 全部操作立即生效，不参与 draft_revision。

#### 4.5.1.5 撤销规则

撤销 CREATE 会删除未发布草稿对象；撤销 UPDATE、ENABLE 或 DISABLE 会从活动快照复制完整对象值覆盖草稿；撤销 DELETE 会清除删除标记并恢复活动值。单项撤销不得级联修改其他草稿对象。其他新建草稿引用该对象时，revertable=false，并在 revert_blockers 返回引用对象 ID；管理员先处理引用对象。

撤销全部以 base_snapshot_no 为唯一目标，忽略各对象当前 change_type。页面要求输入固定确认文本并填写原因。成功后 ConfigDraftState.base_snapshot_no 保持不变、change_count=0、status=EDITABLE；草稿实体版本重新生成，防止旧编辑页面继续提交。

完成标准：所有配置草稿对象与 DraftChange 一一对应；摘要、筛选和分组数量一致；敏感字段无前后值；实体 version 防止同对象覆盖，draft_revision 防止跨对象旧校验和旧撤销；撤销失败不产生部分恢复。

### 4.5.2 配置发布页

#### 4.5.2.1 页面步骤

配置发布页面包含“校验”“确认”“实例准备与激活”“发布结果”四个连续步骤，并在下方提供发布历史。进入页面时读取 ConfigDraftState；change_count=0 时只能查看历史。校验步骤锁定页面读取到的 draft_revision，但不阻止其他管理员继续编辑；一旦草稿发生变化，原 ConfigValidationResult 立即 EXPIRED。

确认步骤展示 validation_id、base_snapshot_no、target_snapshot_no、draft_revision、change_summary、affected_alias_ids、全部 WARNING、在线实例和 DRAINING/STALE/OFFLINE 实例。管理员逐条确认 WARNING，填写 publish_note 后提交 acknowledged_warning_ids。缺少任一警告确认时页面禁止提交，服务端仍会校验其完整性。发布提交成功后页面进入只读进度状态，轮询 PublishRecordDetail；关闭页面不影响服务端流程。

#### 4.5.2.2 校验结果字段与页面行为

| 页面区域 | 字段 | 数据来源 | 展示与操作 |
|---|---|---|---|
| 校验摘要 | status、error_count、warning_count | ConfigValidationResult | error_count>0 时禁止进入确认；WARNING 需要展开阅读。 |
| 版本信息 | base_snapshot_no、draft_revision、target_snapshot_no | ConfigValidationResult | 与当前 ConfigDraftState 对比；不一致时显示已过期。 |
| 内容摘要 | content_checksum、change_summary | ConfigValidationResult | checksum 可复制，用于发布一致性排查。 |
| 影响范围 | affected_alias_ids | ConfigValidationResult | 展示 Alias 名称和近 24 小时请求量。 |
| 问题列表 | severity、code、entity_type、entity_name、field_path、message、suggestion | ConfigValidationIssue | ERROR 在前，按 entity_type 和 field_path 排序；点击进入对象编辑页。 |
| 关联对象 | related_entity_ids | ConfigValidationIssue | 展开查看导致引用或冲突的对象。 |
| 有效期 | validated_at、expires_at | ConfigValidationResult | 到期或草稿变化后只允许重新校验。 |

#### 4.5.2.3 发布校验矩阵

| 校验类别 | 具体规则 | 严重度 | ConfigValidationIssue.code |
|---|---|---|---|
| 基础字段 | 全部必填字段、长度、数值范围、枚举和组合约束有效 | ERROR | FIELD_INVALID |
| 唯一约束 | Provider、池、Credential、模型、Alias 和策略名称及业务唯一键无冲突 | ERROR | UNIQUE_CONFLICT |
| 引用完整性 | 草稿引用对象存在、未删除且关系可达 | ERROR | REFERENCE_INVALID |
| Adapter | 每个启用 Provider 的 type 已注册，schema_version 与运行实例兼容 | ERROR | ADAPTER_UNAVAILABLE |
| Provider 关系 | ProviderModel 与 CredentialPool 属于 RouteCandidate 指定的同一 Provider | ERROR | PROVIDER_RELATION_INVALID |
| Alias 候选 | 启用 Alias 至少有一个启用候选，候选链包含 Provider、模型、池和至少一个启用 Credential | ERROR | ALIAS_NO_AVAILABLE_CANDIDATE |
| 能力与上下文 | 模型 tokenizer_family、context_window、max_output_tokens、stream、system、temperature、top_p、stop 支持状态、模型级范围、默认参数与 Adapter 能力上界组合合法 | ERROR | MODEL_CAPABILITY_INVALID |
| 价格 | 启用模型具有 input_price、output_price、price_unit、currency；同一 Alias 候选 currency 一致 | ERROR | PRICE_CONFIGURATION_INVALID |
| Credential | 启用池至少有一个启用且非 INVALID Credential；EXTERNAL_REF 具有非空 secret_ref | ERROR | CREDENTIAL_CONFIGURATION_INVALID |
| Credential 检测 | 启用 Credential 或模型最近 24 小时无成功检测记录 | WARNING | CONNECTION_CHECK_STALE |
| 限流策略 | 同一作用对象最多一份启用策略，至少一个限额存在，QUEUE 参数有效 | ERROR | LIMIT_POLICY_INVALID |
| 可靠性策略 | 同一 Alias 最多一份启用策略，超时、重试、Fallback 和熔断参数组合有效 | ERROR | RELIABILITY_POLICY_INVALID |
| RuntimeConfig | 保留期、采样、消息长度、代理和实例时限字段有效；timezone 锁定规则满足 | ERROR | RUNTIME_CONFIG_INVALID |
| 运行实例版本 | ONLINE 目标实例支持 ConfigSnapshot.schema_version 和全部启用 Adapter | ERROR | INSTANCE_VERSION_INCOMPATIBLE |
| 非在线实例 | 存在 DRAINING、STALE 或 OFFLINE 实例；DRAINING 结束或实例恢复心跳后加载最新活动快照 | WARNING | INSTANCE_NOT_ONLINE |

校验只读取配置和运行实例能力，不向外部 Provider 发起模型调用，也不解析外部 Secret。连接健康通过最近 ProviderCheckRecord 形成 WARNING；发布成功不等同于外部 Provider 实时可用。

#### 4.5.2.4 发布事务与两阶段加载

提交 ConfigPublishCommand 后，服务端在事务中校验 validation_id 为 PASSED、未过期、未被使用，current draft_revision 和 content_checksum 与校验结果一致，acknowledged_warning_ids 完整，并将 ConfigDraftState 从 EDITABLE 改为 PUBLISHING。警告确认不完整返回 FIELD_VALIDATION_FAILED；版本条件不满足返回 CONFIG_VALIDATION_EXPIRED 或 CONFIG_DRAFT_CHANGED，均不创建 PublishRecord。

服务端取得单调递增 snapshot_no，把规范化配置序列化为 ConfigSnapshot.content，计算 SHA-256 content_checksum，确认不包含 Credential secret_value、完整 secret_ref、Standalone Token、运行状态和审计数据，保存 status=CREATED 的快照，并创建 status=PREPARING 的 PublishRecord。目标实例集合固定为发布开始时 status=ONLINE 的 RuntimeInstance；STALE 和 OFFLINE 只进入警告列表，不阻止发布。ABORTED 快照编号不回收，后续成功快照允许出现编号间隔。

准备阶段向每个目标实例下发 InstancePrepareCommand。实例下载 CREATED 快照，验证 schema_version 与 content_checksum，在独立内存区域解析全部对象、装配 Adapter、构建路由图并执行本地完整性检查；期间继续使用 from_snapshot_no。成功上报 READY，失败上报 FAILED。任一在线目标实例在 publish_instance_timeout_seconds 内未 READY，结果标记 TIMED_OUT，PublishRecord=FAILED，ConfigSnapshot=ABORTED，草稿解除锁定并保持全部变更，活动快照不变。

全部在线目标实例 READY 后，发布服务先把 PublishRecord 改为 ACTIVATING，再在一个数据库事务中把上一 ACTIVE 快照改为 SUPERSEDED、目标快照改为 ACTIVE、更新 RuntimeConfig.current_snapshot_no、清空已发布草稿并把 ConfigDraftState 恢复为 EDITABLE。随后向实例下发 InstanceActivationCommand；实例以单次引用替换切换已准备内存配置并上报 LOADED。新 Trace 读取新 snapshot_no，切换前已经创建的 Trace 保持原快照对象引用直到结束。全部实例在首轮时限内 LOADED 时记录 status=SUCCEEDED、completed_at=converged_at=当前时间。

激活后未在时限内确认的实例进入 TIMED_OUT，明确失败的实例进入 FAILED，PublishRecord=PARTIAL_FAILED，并写 completed_at；已确认实例继续使用新快照。实例后续心跳发现 active_snapshot_no 落后时重新下载当前 ACTIVE 快照并切换，原 PublishInstanceResult 更新为 LOADED。全部目标实例收敛后 PublishRecord 从 PARTIAL_FAILED 更新为 SUCCEEDED 并写 converged_at，completed_at 与 duration_ms 保留首轮结果。发布服务不自动把已加载实例恢复到旧快照。

#### 4.5.2.5 实例状态与异常

RuntimeInstance 每 15 秒发送心跳。有效心跳且 accepting_requests=true 时为 ONLINE；accepting_requests=false 时为 DRAINING。last_heartbeat_at 超过 instance_stale_seconds 时为 STALE，超过三倍该值时为 OFFLINE。实例启动或恢复心跳时，如果 active_snapshot_no 小于全局活动快照，先下载和验证最新快照，成功后进入 ONLINE；加载失败保留本地上一完整快照，status=STALE 并上报错误。DRAINING、STALE 和 OFFLINE 均不进入新发布的目标实例集合。

PublishInstanceResult 状态顺序为 PENDING、PREPARING、READY、ACTIVATING、LOADED。FAILED 和 TIMED_OUT 可以在后台重新加载后转为 LOADED。页面显示 instance_id、runtime_mode、runtime_version、supported_schema_versions、loaded_adapter_types、from_snapshot_no、target_snapshot_no、status、retry_count、load_duration_ms、error_code、error_summary 和 updated_at；能力集合来自 RuntimeInstance 最近一次有效心跳。

目标实例数量为 0 时禁止发布并返回 NO_ONLINE_RUNTIME_INSTANCE。单实例 Embedded 部署把当前进程注册为唯一 RuntimeInstance；Standalone 集群中的每个服务实例分别注册。本地 SDK 使用进程内 LocalRuntimeDefinition，不参与管理端发布。运行实例只通过内部认证接口取得 ConfigSnapshot.content。

#### 4.5.2.6 发布历史

历史筛选依次为时间范围、配置版本、发布状态、发布人和关键词。列表展示发布时间、前后版本、结果、发布人和变更摘要，默认按发布时间倒序。点击行进入详情，页面从上到下展示发布摘要、校验问题、配置快照摘要、各运行实例的准备或激活结果和当前状态。

FAILED 记录只允许查看；管理员修复或保持原草稿后重新执行校验。PARTIAL_FAILED 记录显示后台收敛状态，不提供人工强制覆盖实例。ConfigSnapshot.content 不通过管理页面返回，页面只展示 schema_version、checksum、大小、实体数量和状态。

完成标准：旧 draft_revision 无法发布；校验问题可以定位对象和字段；准备失败不改变活动快照；激活事务与草稿清理保持原子；实例切换不影响运行中 Trace；发布详情可以解释每个实例当前和目标快照；敏感值不进入快照、校验问题和加载错误。

### 4.5.3 运行参数页

#### 4.5.3.1 页面布局

运行参数页顶部展示 current_snapshot_no、published_at、draft_changed、draft_revision 和最近修改人。表单依次分为时间与保留、请求限制、诊断采样、来源 IP、发布协调五个区块。每个字段同时显示当前活动值和草稿输入值；存在差异时可查看 FieldChange。页面只编辑 RuntimeConfig，Standalone Access Credential 使用独立的 4.5.4 页面。

#### 4.5.3.2 表单字段

| 区域 | 字段 | 控件形式 | 默认值 | 校验与生效规则 |
|---|---|---|---|---|
| 时间与保留 | timezone | IANA 时区搜索 | Asia/Shanghai | 已存在 UsageAggregate 后 timezone_locked=true，字段只读。 |
| 时间与保留 | trace_retention_days | 数字框 | 30 | 1—365；缩短时必须确认 RetentionImpact。 |
| 时间与保留 | usage_retention_days | 数字框 | 365 | 30—3650，必须大于等于 trace_retention_days。 |
| 时间与保留 | audit_retention_days | 数字框 | 365 | 365—3650。 |
| 页面 | dashboard_refresh_seconds | 数字框 | 30 | 10—300。 |
| 请求限制 | max_message_chars | 数字框 | 100000 | 1000—1000000。 |
| 请求限制 | max_request_chars | 数字框 | 500000 | max_message_chars—5000000。 |
| 诊断采样 | diagnostic_sampling_enabled | 开关 | false | 开启时显示并启用后三个采样字段。 |
| 诊断采样 | diagnostic_sample_rate | 小数框 | 0 | 0—1，最多四位小数；关闭时强制为 0。 |
| 诊断采样 | diagnostic_sample_retention_days | 数字框 | 7 | 1—30，且不超过 trace_retention_days。 |
| 诊断采样 | diagnostic_sample_max_chars | 数字框 | 1000 | 100—10000。 |
| 来源 IP | client_ip_recording_enabled | 开关 | false | 只影响新 Trace；历史 IP 按 Trace 保留期清理。 |
| 来源 IP | trusted_proxy_cidrs | 可增删列表 | 空 | 最多 100 项，每项为 IPv4、IPv6 或 CIDR；仅 Standalone 使用。 |
| 发布协调 | publish_instance_timeout_seconds | 数字框 | 60 | 10—300；从下一次发布开始使用。 |
| 发布协调 | instance_stale_seconds | 数字框 | 60 | 30—600，必须大于两倍心跳间隔。 |
| 系统字段 | current_snapshot_no、published_at | 只读 | 系统值 | 从活动快照读取。 |
| 系统字段 | version | 隐藏 | 系统值 | 保存 RuntimeConfigUpdateCommand 时提交。 |

#### 4.5.3.3 操作与数据流

| 操作 | 权限与前置条件 | 服务端处理 | 页面结果与异常 |
|---|---|---|---|
| 查看 | 可查看角色 | 返回 RuntimeConfigDetail | 页面展示活动值、草稿值和保留影响。 |
| 估算保留影响 | 系统管理员；修改任一保留天数 | 只读统计预计删除数量和 target_cutoff_at | 展示 RetentionImpact，不产生草稿。 |
| 保存草稿 | 系统管理员；version 最新；字段合法 | 更新 RuntimeConfig 草稿、实体 version、draft_revision 和 AuditLog | 成功后显示待发布；版本冲突保留输入。 |
| 重置未保存输入 | 系统管理员 | 无服务端请求 | 恢复最近读取的 draft_config。 |
| 查看待发布差异 | 可查看角色；draft_changed=true | 跳转 4.5.1 并筛选 Runtime Config | 无。 |

RuntimeConfig 保存只写草稿，发布激活后新请求、页面查询、清理任务和下一次发布协调使用新值。已经运行的 Trace 保持创建时取得的超时与快照；dashboard_refresh_seconds 在管理页面下一次读取配置后生效。

保留期缩短时，服务端根据当前记录估算删除数量，保存请求必须携带页面确认过的 impact_version；估算后数据范围变化返回 RETENTION_IMPACT_EXPIRED。发布成功后清理任务按新 cutoff 分批执行，不恢复已清理数据。usage_retention_days 不得小于 trace_retention_days，保证 Trace 存在期间可查询对应 Usage。

开启诊断采样后只对新请求按 sample_rate 随机决定，并先脱敏再持久化。关闭采样停止新建样本，并在 10 分钟内清理现存 TraceContentSample，将关联 Trace.content_sample_status 更新为 EXPIRED。降低 sample retention 产生同样的提前清理影响。

Standalone 仅在请求的直连来源位于 trusted_proxy_cidrs 时读取 X-Forwarded-For，并从右向左移除可信代理得到真实来源；其他请求使用连接地址。client_ip_recording_enabled=false 时仍可使用解析后的 IP 执行 Access Credential 白名单校验，但不写入 Trace 和 last_used_ip。

完成标准：所有字段具有确定范围和组合校验；活动值与草稿值可区分；timezone 锁定后无法形成混合时间桶；保留期缩短有影响确认；采样开关、来源 IP 和发布协调参数只在发布后影响对应新操作。

### 4.5.4 Standalone Access Credential 页

New API 的公开令牌页面支持有效期、模型范围、IP 白名单和完整 Key 一次性显示，验证了网关访问凭证的基础管理方式。轻享 AI 保留这些与运行安全直接相关的能力，不引入充值、配额余额和商业分组。参考依据为 [New API 令牌管理](https://docs.newapi.pro/zh/docs/guide/feature-guide/user/token)。

#### 4.5.4.1 可见范围与列表页面

该页面只在 Standalone Mode 展示。SDK 和 Embedded Mode 使用宿主调用上下文，不创建 Standalone Access Credential。页面顶部依次为 keyword、application、status、allowed_alias_id、expires_before 和 has_recent_use 筛选，右侧主操作为“创建访问凭证”。

| 顺序 | 列字段 | 数据来源 | 展示与交互规则 |
|---:|---|---|---|
| 1 | name | AccessCredentialListItem.name | 点击进入详情。 |
| 2 | masked_token | AccessCredentialListItem.masked_token | 只允许复制脱敏值。 |
| 3 | application | AccessCredentialListItem.application | 点击筛选同应用 Trace。 |
| 4 | allowed_alias_count | AccessCredentialListItem | 空数组显示“全部已发布 Alias”。 |
| 5 | ip_rule_count | AccessCredentialListItem | 空数组显示“不限制”。 |
| 6 | status | AccessCredentialListItem.status | ACTIVE、DISABLED、EXPIRED；DELETED 默认不显示。 |
| 7 | expires_at | AccessCredentialListItem.expires_at | 7 天内到期时显示剩余时间。 |
| 8 | rotation_generation | AccessCredentialListItem.rotation_generation | 用于确认当前 Token 代次。 |
| 9 | last_used_at | AccessCredentialListItem.last_used_at | 未使用显示空。 |
| 10 | last_used_ip | AccessCredentialListItem.last_used_ip | 仅敏感诊断权限可见。 |
| 11 | trace_count_24h | AccessCredentialListItem.trace_count_24h | 点击进入对应 Trace。 |
| 12 | updated_at | AccessCredentialListItem.updated_at | 最近安全配置变更时间。 |
| 13 | actions | AccessCredentialListItem.actions | 查看、编辑、轮换、启停、删除、查看 Trace。 |

#### 4.5.4.2 创建与编辑字段

| 字段 | 控件形式 | 创建默认值 | 编辑规则 | 校验 |
|---|---|---|---|---|
| name | 单行文本 | 空 | 可编辑 | 2—64 字符，全局唯一。 |
| application | 单行文本 | 空 | 可编辑，立即影响新 Trace | 1—64 字符。 |
| allowed_alias_ids | 远程搜索多选 | 空 | 可编辑 | 必须指向当前已发布 Alias；空数组允许全部。 |
| ip_allowlist | 可增删列表 | 空 | 可编辑 | 最多 100 项，IPv4、IPv6 或 CIDR；空数组不限制。 |
| expires_at | 日期时间 | 空 | 可延长或缩短 | 创建时为空或晚于当前时间至少 5 分钟；编辑为过去时间后立即 EXPIRED。 |
| enabled | 开关 | true | 编辑页通过独立启停操作修改 | 创建时可选。 |
| version | 隐藏 | 空 | 编辑、启停、轮换和删除提交 | 必须为最新版本。 |

详情页从上到下展示非敏感 credential 字段、allowed_aliases、最近 24 小时调用统计、最近 10 条 Trace 和 AuditLog 摘要。token_hash、token_hash_version 和完整 token_value 永不出现在详情响应。

#### 4.5.4.3 Token 签发与一次性展示

创建和轮换时，服务端使用密码学安全随机源生成 32 字节随机值，编码为无填充 Base64URL，并添加 lai_ 前缀。token_prefix 取用于索引的前 8 个字符，token_hash 使用服务端 pepper 执行 HMAC-SHA256，比较时使用恒定时间函数。原始 token_value 只存在于当前请求内存和 AccessCredentialSecretResult，响应完成后清除引用。

页面以阻断弹窗显示 token_value、issued_at 和 rotation_generation，要求用户勾选“已安全保存”后关闭。关闭后任何读取接口都无法再次取得原文；遗失时执行轮换。页面禁止把 Token 写入 URL、浏览器持久缓存、分析事件和前端日志。

#### 4.5.4.4 操作定义

| 操作 | 权限与前置条件 | 数据变化 | 成功结果与失败处理 |
|---|---|---|---|
| 创建 | 系统管理员；Standalone Mode | 创建摘要记录和 generation=1 Token，写 AuditLog | 返回 AccessCredentialSecretResult；字段错误不生成 Token。 |
| 查看 | 系统管理员、运维人员 | 无 | 返回 AccessCredentialDetail，不含摘要与原文。 |
| 编辑 | 系统管理员；version 最新；非 DELETED | 更新名称、应用、Alias 范围、IP 和到期时间，递增 version，写审计 | 对新鉴权立即生效；版本冲突不覆盖。 |
| 轮换 | 系统管理员；非 DELETED；version 最新；填写原因 | 在单一事务替换 token_prefix、token_hash、hash version，generation+1，写 rotated_at 和审计 | 返回一次性新 Token；旧 Token 在事务提交后立即无效。 |
| 停用 | 系统管理员；status=ACTIVE；version 最新 | enabled=false、status=DISABLED、disabled_at=now，写审计 | 新请求立即返回 ACCESS_TOKEN_INVALID。 |
| 启用 | 系统管理员；status=DISABLED；尚未过期 | enabled=true、status=ACTIVE，写审计 | 已过期返回 ACCESS_CREDENTIAL_EXPIRED。 |
| 删除 | 系统管理员；非 DELETED；填写原因 | 软删除、enabled=false、清除 token_hash、status=DELETED，写审计 | 无法恢复；历史 Trace 使用名称快照。 |
| 查看 Trace | 系统管理员、运维人员 | 无 | 跳转 Trace 列表并带入 access_credential_id。 |

#### 4.5.4.5 Standalone 鉴权流程

Standalone Server 从 Authorization: Bearer 读取 Token，先校验前缀、长度和字符集，再使用 token_prefix 定位候选记录，按 token_hash_version 计算 HMAC 并恒定时间比较。匹配后依次校验 deleted_at、enabled、expires_at、来源 IP 和 requested alias_id。任一身份条件失败统一返回 ACCESS_TOKEN_INVALID；来源 IP 不允许时返回 ACCESS_IP_DENIED；Alias 不在 allowed_alias_ids 时返回 ACCESS_DENIED。

鉴权成功后把 credential_id、application 和权限范围写入 UnifiedRequestContext，创建 Trace 时保存 access_credential_id 和名称快照。last_used_at、last_used_ip 和 last_used_trace_id 在 Trace 创建成功后更新；该更新失败不阻断模型调用，但产生内部告警。鉴权失败不创建业务 Trace，安全日志记录 request_id、token_prefix、失败类别和来源 IP，不记录 Token 原文。

编辑、停用、轮换和删除只影响事务提交后的新请求。已经完成鉴权并进入运行时的 Trace 继续执行；客户端重试会重新鉴权。Standalone Access Credential 不进入 ConfigDraftState、ConfigSnapshot 和发布流程，所有写操作立即生效并生成 AuditLog。

完成标准：Token 只在创建和轮换响应出现一次；旧代次在轮换提交后不可鉴权；Alias 与 IP 范围按请求执行；停用、过期和删除状态准确；任何页面、读取接口、Trace、AuditLog 和日志均无 token_value、token_hash 和 pepper。

### 4.5.5 审计日志页

#### 4.5.5.1 页面筛选与列表

审计页面只读。默认查询最近 24 小时，按 created_at desc，每页 20 条；普通组合查询时间跨度最大 31 天，request_id 或 AuditLog.id 精确查询可以跨 audit_retention_days 内的全部数据。

| 页面区域 | 字段 | 控件或展示形式 | 查询与展示规则 |
|---|---|---|---|
| 筛选区 | start_at、end_at | 日期时间范围 | 普通查询必填，左闭右开。 |
| 筛选区 | audit_id | 单行文本 | 精确匹配，填写后忽略分页和其他业务筛选。 |
| 筛选区 | request_id | 单行文本 | 精确匹配同一管理操作链路。 |
| 筛选区 | operator | 远程搜索多选 | 匹配 operator_id 或 operator_name。 |
| 筛选区 | operator_role | 多选下拉 | 使用操作发生时角色快照。 |
| 筛选区 | operation | 多选下拉 | 取 AuditLog.operation。 |
| 筛选区 | entity_type | 多选下拉 | 配置、运行、安全对象类型。 |
| 筛选区 | entity_keyword | 单行文本 | 匹配 entity_id 或 entity_name。 |
| 筛选区 | source_mode | 多选下拉 | ADMIN_UI、MANAGEMENT_API、SYSTEM_TASK。 |
| 筛选区 | result | 多选下拉 | SUCCEEDED、FAILED。 |
| 筛选区 | error_code | 可输入多选 | 精确匹配。 |
| 筛选区 | client_ip | 单行文本 | 仅系统管理员可用。 |
| 列表 | created_at | 时间 | 页面时区，精确到毫秒。 |
| 列表 | operator_name、operator_role | 文本 | 系统任务显示固定系统身份。 |
| 列表 | operation | 文本 | 高风险操作同时显示已填写原因的存在状态。 |
| 列表 | entity_type、entity_name | 文本链接 | 对象存在且有权限时进入详情。 |
| 列表 | change_summary | 文本 | 只列字段名，不展示值。 |
| 列表 | source_mode | 文本 | 标识页面、API 或系统任务。 |
| 列表 | result、error_code | 文本 | 失败时可按 error_code 继续筛选。 |
| 列表 | duration_ms | 数字 | 服务端操作耗时。 |

#### 4.5.5.2 详情字段与操作

点击行打开 AuditLogDetail。详情从上到下展示 request_id、操作人、角色、source_mode、client_ip、user_agent、operation、operation_reason、对象、before_version、after_version、changed_fields、result、error_code、error_summary、duration_ms 和 created_at。changed_fields 按 field_name 排序，普通字段展示 before_value 与 after_value，敏感字段固定展示 sensitive=true 和空值。

页面操作包括查询、重置、查看详情、复制 request_id、按 operator 或 error_code 继续筛选、进入关联对象。页面不提供编辑、删除、批量清理和包含字段值的导出。系统管理员与运维人员可以查看审计；开发人员和只读人员无入口。

#### 4.5.5.3 写入与事务规则

成功的配置、安全和运行写操作在同一数据库事务中写业务变化与 AuditLog；审计写入失败时业务变化回滚。配置校验、连接检测、发布、草稿撤销、Credential 轮换、熔断人工操作和诊断样本查看都生成审计。一次跨对象操作使用同一 request_id，为每个对象写一条明细审计，并额外写一条汇总对象审计。

业务操作因字段、权限、版本、引用或外部调用失败时，先回滚业务事务，再在独立审计事务中记录 result=FAILED、error_code 和脱敏 error_summary。失败审计事务再次失败时，管理接口保持原业务错误，系统产生高优先级内部告警并记录不含敏感值的安全日志。

CREATE、UPDATE、ENABLE、DISABLE 和 DELETE 记录实际变化字段；CHECK 记录目标、检测模式、结果和 Provider Request ID 是否存在，不记录请求正文；PUBLISH 记录 validation_id、draft_revision、snapshot_no 和实例统计；VIEW_DIAGNOSTIC_SAMPLE 记录 Trace ID 和样本 ID，不复制样本内容。

secret_value、secret_ref 完整值、token_value、token_hash、Authorization、自定义认证头、Provider 原始错误和消息正文不得进入 before_value、after_value、operation_reason、error_summary 和元数据。operation_reason 在写入前执行同一敏感规则扫描。

AuditLog 按 RuntimeConfig.audit_retention_days 保留，最低 365 天。清理任务按 created_at 每批最多 1000 条删除，无法通过页面提前删除。保留期修改按配置发布生效。

完成标准：每个成功管理写操作都有同事务审计；失败操作可通过 request_id 定位；跨对象操作明细完整；敏感字段没有值；审计页面权限、筛选、分页和保留期一致。

### 4.5.6 运行配置管理接口契约

#### 4.5.6.1 草稿、校验与发布接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/config/draft-state | 无 | ConfigDraftState | 可查看角色 | CONFIG_DATA_UNAVAILABLE |
| GET /admin/config/draft-changes/summary | 无 | DraftChangeSummary | 可查看角色 | CONFIG_DATA_UNAVAILABLE |
| GET /admin/config/draft-changes | keyword、entity_type、change_type、modified_by、modified_from、modified_to、page、page_size | PageResult<DraftChange> | 可查看角色 | FIELD_VALIDATION_FAILED、CONFIG_DATA_UNAVAILABLE |
| POST /admin/config/draft-changes/{entityType}/{entityId}/revert | RevertDraftCommand | ConfigDraftState | 系统管理员 | CONFIG_DRAFT_CHANGED、DRAFT_REVERT_BLOCKED、CONFIG_VERSION_CONFLICT |
| POST /admin/config/draft-changes/revert-all | RevertAllDraftCommand | ConfigDraftState | 系统管理员 | CONFIG_DRAFT_CHANGED、CONFIRMATION_TEXT_MISMATCH |
| POST /admin/config/validate | ConfigValidateCommand | ConfigValidationResult 与 array<ConfigValidationIssue> | 系统管理员 | CONFIG_DRAFT_CHANGED、CONFIG_DATA_UNAVAILABLE |
| POST /admin/config/publish | ConfigPublishCommand | PublishRecord | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_VALIDATION_EXPIRED、CONFIG_DRAFT_CHANGED、CONFIG_PUBLISH_IN_PROGRESS、NO_ONLINE_RUNTIME_INSTANCE |
| GET /admin/config/publish-records | start_at、end_at、snapshot_no、status、published_by、keyword、page、page_size | PageResult<PublishRecordListItem> | 可查看角色 | FIELD_VALIDATION_FAILED |
| GET /admin/config/publish-records/{id} | id | PublishRecordDetail | 可查看角色 | OBJECT_NOT_FOUND |
| GET /admin/config/snapshots/{snapshotNo}/summary | snapshotNo | 不含 content 的 ConfigSnapshot | 系统管理员、运维人员 | OBJECT_NOT_FOUND |
| GET /admin/runtime-instances | status、runtime_mode、application、version、page、page_size | PageResult<RuntimeInstance> | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED |

#### 4.5.6.2 RuntimeConfig 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/runtime-config | 无 | RuntimeConfigDetail | 可查看角色 | CONFIG_DATA_UNAVAILABLE |
| POST /admin/runtime-config/retention-impact | trace_retention_days、usage_retention_days、audit_retention_days、diagnostic_sample_retention_days | RetentionImpactResult | 系统管理员 | FIELD_VALIDATION_FAILED |
| PUT /admin/runtime-config | RuntimeConfigUpdateCommand | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、CONFIG_FIELD_IMMUTABLE、RETENTION_IMPACT_EXPIRED、CONFIG_VERSION_CONFLICT |

#### 4.5.6.3 Standalone Access Credential 接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/access-credentials | keyword、application、status、allowed_alias_id、expires_before、has_recent_use、page、page_size、sort | PageResult<AccessCredentialListItem> | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、MODE_NOT_SUPPORTED |
| POST /admin/access-credentials | AccessCredentialCreateCommand | AccessCredentialSecretResult | 系统管理员 | FIELD_VALIDATION_FAILED、OBJECT_REFERENCE_INVALID、MODE_NOT_SUPPORTED |
| GET /admin/access-credentials/{id} | id | AccessCredentialDetail | 系统管理员、运维人员 | OBJECT_NOT_FOUND、MODE_NOT_SUPPORTED |
| PUT /admin/access-credentials/{id} | AccessCredentialUpdateCommand | ManagementOperationResult | 系统管理员 | FIELD_VALIDATION_FAILED、OBJECT_REFERENCE_INVALID、CONFIG_VERSION_CONFLICT |
| POST /admin/access-credentials/{id}/rotate | AccessCredentialRotateCommand | AccessCredentialSecretResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_NOT_FOUND |
| POST /admin/access-credentials/{id}/enable | version | ManagementOperationResult | 系统管理员 | ACCESS_CREDENTIAL_EXPIRED、CONFIG_VERSION_CONFLICT |
| POST /admin/access-credentials/{id}/disable | version、reason | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT |
| DELETE /admin/access-credentials/{id} | version、reason | ManagementOperationResult | 系统管理员 | CONFIG_VERSION_CONFLICT、OBJECT_NOT_FOUND |

#### 4.5.6.4 审计接口

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/audit-logs | 4.5.5.1 全部筛选、page、page_size、sort | PageResult<AuditLogListItem> | 系统管理员、运维人员 | FIELD_VALIDATION_FAILED、AUDIT_DATA_UNAVAILABLE |
| GET /admin/audit-logs/{id} | id | AuditLogDetail | 系统管理员、运维人员 | OBJECT_NOT_FOUND、AUDIT_DATA_UNAVAILABLE |

#### 4.5.6.5 运行实例内部接口

| 方法与路径 | 请求字段 | 成功响应 | 调用方 | 主要错误码 |
|---|---|---|---|---|
| POST /internal/runtime-instances/heartbeat | RuntimeInstanceHeartbeat | RuntimeHeartbeatResponse | 已认证运行实例 | INSTANCE_AUTH_FAILED、INSTANCE_VERSION_INCOMPATIBLE |
| GET /internal/config-snapshots/{snapshotNo} | snapshotNo、expected_checksum | ConfigSnapshot.content 与元数据 | 已认证运行实例 | OBJECT_NOT_FOUND、SNAPSHOT_CHECKSUM_MISMATCH、INSTANCE_VERSION_INCOMPATIBLE |
| POST /internal/publish-records/{publishId}/instances/{instanceId}/reports | InstanceLoadReport | PublishInstanceResult | 已认证运行实例 | OBJECT_NOT_FOUND、INSTANCE_REPORT_CONFLICT |

内部接口使用部署提供的实例服务身份或双向 TLS，与 Standalone Access Credential 隔离。实例只能读取被 Prepare 或当前 ACTIVE 指令引用的快照。RuntimeHeartbeatResponse 的 prepare_command 与 activation_command 不会同时返回；实例优先完成准备和 READY 上报，目标快照进入 ACTIVE 后才接收激活指令。心跳和上报按 instance_id 幂等，reported_at 早于当前结果 updated_at 的旧报告返回 INSTANCE_REPORT_CONFLICT。

#### 4.5.6.6 跨模块事务与安全边界

配置实体写入、DraftChange、ConfigDraftState 修订和成功 AuditLog 保持一个事务。发布激活事务同时更新 ConfigSnapshot 状态、RuntimeConfig.current_snapshot_no、配置草稿和 ConfigDraftState。Standalone Access Credential 使用独立即时事务，不等待发布锁；轮换、停用和删除必须同时提交安全状态与审计。

所有配置读取响应在序列化前移除 ConfigSnapshot.content、Credential 密钥、完整 secret_ref、Standalone token_hash 和 token_hash_version。只有内部实例接口可以读取 ConfigSnapshot.content，内容本身仍不得包含密钥和完整 secret_ref。实例需要模型密钥时，使用快照中的 credential_id 调用进程内 CredentialSecretResolver，从受保护的凭证存储取得数据库密文解密结果或完整 secret_ref，再按 3.6 的 Secret SPI 解析；解析值只存在于受控内存。AccessCredentialSecretResult 设置 Cache-Control: no-store，管理页面禁止再次请求原文。

## 4.6 开发接入

开发接入按照使用者的接入路径组织：先在接入说明页确认运行形态、Alias、依赖和认证，再使用在线测试验证协议与 Trace；随后分别定义 Java SDK、Spring Boot Starter、Embedded Admin UI 和 Standalone Server 的开发契约。统一 HTTP API 与 Provider SPI 的底层协议在 4.7 独立展开，供客户端、服务端和 Adapter 实现共同使用。

### 4.6.1 接入说明页

New API 通过统一 base_url、Bearer Token、`GET /v1/models` 和 `POST /v1/chat/completions` 降低现有客户端的接入成本。轻享 AI V1.0 采用相同的基础入口，并在响应中增加 light_ai 追踪字段；接入页围绕“确认连接方式—选择 Alias—复制示例—测试调用—查看 Trace”的页面顺序组织。参考依据为 [New API 使用 API](https://docs.newapi.pro/zh/docs/guide/feature-guide/user/api) 和 [New API 模型列表](https://docs.newapi.pro/zh/docs/api/ai-model/models/list/listmodels)。

#### 4.6.1.1 页面顺序与进入规则

用户从左侧“开发接入—接入说明”进入页面。页面从上到下依次展示运行形态与连接信息、Model Alias 选择器、依赖与配置示例、同步/异步/流式调用示例、请求字段说明、在线测试面板、测试结果和常见错误。首次进入默认选择按 alias asc 排序的第一个可用 Alias；URL 中携带 alias_id 时优先选择该 Alias，无权限或已停用时回退到第一个可用项并给出字段级提示。

系统管理员和运维人员可以查看全部已发布 Alias；开发人员只查看 application_scope 允许的 Alias；只读人员可以查看协议字段和脱敏示例，但不能执行测试调用。Standalone 页面永不读取访问凭证原文，所有 Token 位置固定显示 `lai_your_token`。系统管理员需要真实 Token 时进入 4.5.4 创建或轮换页面。

#### 4.6.1.2 页面字段

| 页面区域 | 字段 | 数据来源 | 展示与交互规则 |
|---|---|---|---|
| 连接信息 | runtime_mode | DeveloperAccessContext | SDK、Embedded 或 Standalone。 |
| 连接信息 | api_base_url | DeveloperAccessContext | Standalone 可复制；Embedded 显示“进程内调用”；SDK 显示“本地 Runtime”。 |
| 连接信息 | authentication_type | DeveloperAccessContext | NONE、HOST_CONTEXT 或 BEARER_TOKEN，并链接到对应配置说明。 |
| 版本信息 | sdk_version、server_version | DeveloperAccessContext | 用于生成依赖版本和检查主版本兼容。 |
| 配置信息 | current_snapshot_no | DeveloperAccessContext | Embedded 与 Standalone 可复制并进入发布记录。 |
| 模型选择 | selected_alias_id | DeveloperAccessContext | 远程搜索 available_models；切换后刷新全部示例。 |
| 模型摘要 | display_name、support_stream、support_system_message、context_window、max_output_tokens | UnifiedModelItem.light_ai | 直接来自当前活动快照，不展示 Provider 和 Credential。 |
| 示例条件 | sample_type | CodeSampleRequest | 依赖、配置、同步、异步、流式或 HTTP。 |
| 示例条件 | build_tool | CodeSampleRequest | Maven 或 Gradle，仅依赖示例显示。 |
| 示例内容 | language、content、placeholders | CodeSampleResult | 代码只读，可一键复制；占位符在代码下方列出。 |
| 请求说明 | 统一模型请求的主要字段 | 2.6.10 | 展示页面调用涉及的类型、必填、范围和默认来源。 |
| 错误说明 | code、HTTP 状态、retryable、说明 | 4.7.3 | 支持按 code 搜索，不展示内部堆栈。 |

#### 4.6.1.3 示例生成规则

依赖示例根据 build_tool 返回 `light-ai-client`、`light-ai-runtime` 或 `light-ai-spring-boot-starter` 的当前稳定版本。SDK 形态生成 LocalRuntimeDefinition 构建入口和同步、异步、流式 Java 调用；Embedded 形态生成 Spring 配置与注入 LightAiClient 的示例；Standalone 形态生成 Spring Standalone Client 配置、原生 Java Client 和 cURL 示例。

示例中的 model 使用用户当前选择的 Alias。base_url 可以使用部署公开地址，access_token、数据库密码、Provider Credential 和 secret_ref 始终使用占位符。示例不写入用户剪贴板，只有点击复制后由浏览器执行复制；复制操作不产生 AuditLog，但记录不含示例内容的页面行为指标。

#### 4.6.1.4 在线测试面板

测试面板字段依次为 model、可选 system_message、user_message、stream、temperature、top_p 和 max_tokens。默认 stream=true；输出区按 StreamEvent.sequence 追加内容，并同时展示 trace_id、Provider、Provider Model、finish_reason、Usage、Cost 和总耗时。正文只保存在当前浏览器页面状态，刷新或关闭页面后清除。

测试请求通过当前管理身份建立 UnifiedRequestContext。开发人员使用宿主或产品权限中的 application_scope；系统管理员和运维人员的测试 application 记录为 `ADMIN_CONSOLE`，并在 Trace.tags 写入 `light_ai_test=true` 和 operator_id。身份完成后进入与业务调用相同的 Alias 解析、路由、容量、可靠性、Provider、Usage 和 Trace 链路。测试接口不使用 Standalone Access Credential，因此不能用于验证某个 Token 的 Alias 或 IP 限制。

| 操作 | 权限与前置条件 | 服务端处理 | 页面结果与异常 |
|---|---|---|---|
| 切换 Alias | 当前角色可见 | 读取 DeveloperAccessContext 并重新生成示例 | Alias 失效时清空测试输入之外的上下文。 |
| 复制连接地址 | api_base_url 有值 | 无服务端变化 | 复制规范化 HTTPS 地址。 |
| 复制示例 | 示例已生成 | 无服务端变化 | 保留原有换行和缩进。 |
| 发起非流式测试 | 有测试权限；字段合法 | 创建 Trace 并执行统一调用 | 返回 ApiTestResult.response；错误展示 code、message 和 trace_id。 |
| 发起流式测试 | 有测试权限；字段合法 | 建立 SSE 并执行统一调用 | 逐块渲染；结束后显示 Usage 与 Trace 链接。 |
| 取消测试 | 当前测试未结束 | 关闭 SSE 或取消调用上下文 | Trace=CANCELLED，停止追加内容。 |
| 查看 Trace | 已创建 trace_id 且有权限 | 跳转 Trace 详情 | 身份校验前失败时无入口。 |

完成标准：页面顺序可以指导新接入方完成一次调用；每个请求字段均可查到类型与限制；所有示例可直接编译或在替换占位符后执行；测试调用与 Trace 数据一致；页面和前端日志无 Token、Provider Credential 与测试消息正文。

### 4.6.2 Java SDK

#### 4.6.2.1 发布物与职责

| 发布物 | 包含能力 | 不包含能力 |
|---|---|---|
| light-ai-client | 统一协议对象、Standalone HTTP 客户端、同步/异步/流式 API、错误转换 | Provider Adapter、路由、管理页面和存储实现。 |
| light-ai-runtime | light-ai-client、进程内 Runtime Core、Provider SPI、路由、容量、可靠性、Trace 与 LocalRuntimeDefinition | 管理页面、配置草稿和数据库发布流程。 |
| light-ai-spi | ProviderAdapter、SecretProvider、AuthContextProvider、TraceExporter 等扩展接口 | 默认实现与网络客户端。 |
| light-ai-spring-boot-starter | 自动装配、Embedded Runtime、Standalone Client、宿主资源复用和 Embedded Admin UI | Standalone Server 可执行程序。 |

Java SDK 的公开对象包括客户端、请求、消息、响应、用量、模型信息、流式事件、客户端配置和统一调用异常。请求、响应与用量映射 2.6.10 的统一模型调用信息；公开 API 不出现任何具体 Provider SDK 类型。

V1.0 发布物使用 Java 17 字节码并验证 Java 17 与 Java 21。公共 API 不依赖 Spring；`light-ai-runtime` 与 `light-ai-client` 可以在普通 Java 应用中单独使用。依赖版本在同一 V1.x 系列内统一管理，接入方不得混用不同版本的 client、runtime 与 spi。

#### 4.6.2.2 LightAiClient 操作

| 方法 | 输入 | 返回 | 执行规则 |
|---|---|---|---|
| builder | LightAiClientConfig 各字段 | LightAiClient.Builder | build 时执行模式必填、URL、超时和本地定义校验。 |
| models | 无 | List<ModelInfo> | LOCAL_RUNTIME 从本地快照读取；STANDALONE_CLIENT 调用 GET /v1/models。 |
| chat | ChatRequest | ChatResponse | 当前线程等待完成；失败抛出统一调用异常。 |
| chatAsync | ChatRequest | CompletableFuture<ChatResponse> | 与 chat 共用执行链，完成、失败和取消只发生一次。 |
| stream | ChatRequest | Flow.Publisher<StreamEvent> | SDK 强制 stream=true；每个 Publisher 只允许一个 Subscriber。 |
| close | 无 | void | 拒绝新调用，按 close_timeout_ms 等待现有调用并释放传输与本地 Runtime 资源。 |

LightAiClient 构造完成后线程安全，可以作为应用单例复用。ChatRequest 和响应对象不可变，Builder 在 build 时复制集合，调用期间不读取接入方后续修改。models 返回不可变列表。调用 close 后执行任何方法均抛出 CLIENT_CLOSED；重复 close 幂等。

#### 4.6.2.3 LOCAL_RUNTIME 数据流

LOCAL_RUNTIME 构建时读取本地运行定义，执行与配置发布相同的字段、引用、能力、价格、限流和可靠性校验，但不执行运行实例兼容检查，也不请求外部 Provider。校验结果一次性返回能够确定的对象、字段和原因；任一错误阻止客户端创建。

校验成功后在进程内生成 snapshot_no=1 的不可变 ConfigSnapshot，content_checksum 由规范化定义计算。V1.0 Local Runtime 不提供运行中热更新；需要变更时由宿主构造新的 LightAiClient 并完成引用切换。凭证通过 credential_secret_suppliers 按 credential_id 延迟取得，明文不进入 LocalRuntimeDefinition、快照、异常和日志。

一次调用按 4.3.4 运行路由执行。容量、队列和熔断状态保存在当前进程；Trace 默认保存在有界内存缓冲并输出 Metrics，注册 TraceExporter 后异步发送脱敏 Trace。Local Runtime 不注册 RuntimeInstance，不参与 4.5 配置发布，也不提供 Admin UI。

#### 4.6.2.4 STANDALONE_CLIENT 数据流

STANDALONE_CLIENT 在 build 时规范化 base_url、验证 HTTPS 和 Token Supplier，但不执行网络探测。models、chat 和 stream 调用时读取当前 Token，构造 Authorization: Bearer 头，并分别访问 `/v1/models` 或 `/v1/chat/completions`。application 由服务端 Access Credential 决定，客户端不能通过请求头覆盖。

SDK 把调用方 trace_id 写入请求体；调用方未提供时不预先生成，由服务端响应取得。HTTP 非 2xx 响应解析统一失败结果并转换为 SDK 统一调用异常；无法解析的响应转换为 SERVER_PROTOCOL_ERROR，并保存状态码与最多 1000 字符的脱敏摘要。响应体、SSE 与异常解析都不记录 Authorization 和消息正文。

transport_retry_count 默认 0。启用后只对 DNS、TCP 或 TLS 建连阶段且确认请求体尚未写入的失败执行退避重试；请求体可能已经发送、收到任意响应头、取得 trace_id 或开始 SSE 后均不重试。服务端 retryable 只提供给业务代码判断，SDK不自动重新发起模型调用。

#### 4.6.2.5 异步、流式与取消

chatAsync 返回的 CompletableFuture 取消时，SDK 同时触发底层调用 CancellationSignal。LOCAL_RUNTIME 取消待执行任务或向当前 Adapter 传播取消；STANDALONE_CLIENT 取消 HTTP 请求。future 以 CancellationException 结束，服务端已经创建 Trace 时记录 CANCELLED。同步 chat 所在线程被中断时执行同样的取消流程并恢复线程中断标记。

stream 返回 Java Flow.Publisher。Publisher 遵循 request(n) 背压：n 小于等于 0 时调用 onError(IllegalArgumentException)；只有存在正需求时才发送事件。每个订阅最多缓冲 32 个 StreamEvent，达到上限后暂停读取 Provider 或 HTTP 响应，不丢弃事件。Subscriber.cancel 立即停止下游事件、关闭远程连接或触发本地 CancellationSignal，不发送 DONE 和 onComplete。

正常顺序为 START、零到多个 DELTA、可选 USAGE、DONE、onComplete。Provider 或服务端错误通过 onError 携带统一调用异常并终止；onError 后不得调用 onComplete。stream_idle_timeout_ms 只计算相邻网络块等待时间，服务端 ReliabilityPolicy.total_timeout_ms 继续限制完整调用。

完成标准：两种客户端模式使用同一公开对象；同步、异步和流式结果与 HTTP 协议字段一一映射；异步和流式取消能够释放容量；背压不丢块；客户端不会自动造成重复模型调用；所有读取接口和异常均不暴露 Token 或 Provider Credential。

### 4.6.3 Spring Boot Starter 与 Embedded Admin UI

Spring Boot 官方自定义自动装配规则要求使用独立配置命名空间、条件装配和 MissingBean 回退，使宿主能够覆盖默认 Bean。轻享 AI 使用 `light-ai` 命名空间，自动装配类通过 AutoConfiguration.imports 注册，不扫描宿主业务包。参考依据为 [Spring Boot 创建自动装配](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)。

V1.0 Starter 支持 Spring Boot 3.3、3.4 与 3.5，覆盖 Servlet Web 应用和 Reactive Web 应用。宿主没有 Web 栈时仍可装配 LightAiClient 与 Embedded Runtime，但不创建 Admin UI。

#### 4.6.3.1 自动装配条件与顺序

light-ai.enabled=false 时不创建任何 Light AI Bean。mode=STANDALONE_CLIENT 时只创建 SpringLightAiProperties、HTTP Transport、LightAiClient 和健康检查，不加载 Runtime Core、数据库、Redis、管理页面与 Provider Adapter。mode=EMBEDDED 时先绑定并校验配置，再解析宿主 DataSource 和可选 RedisConnectionFactory，验证数据结构，注册 Adapter，加载活动快照，创建 Runtime Core 与 LightAiClient，最后挂载 Admin UI 并注册 RuntimeInstance。

默认 Bean 均使用 ConditionalOnMissingBean。宿主提供 LightAiClient 时 Starter 不再创建客户端；提供 ProviderAdapter、SecretProvider、AuthContextProvider 或 TraceExporter 时加入对应有序集合。两个 ProviderAdapter 返回相同 provider_type 时启动失败并列出冲突 Bean 名称，不按装配顺序静默覆盖。

#### 4.6.3.2 配置项分组

| 分组 | 字段 | 作用与模式规则 |
|---|---|---|
| 基础 | light-ai.enabled、light-ai.mode、light-ai.application | 控制装配模式；Embedded 必须具有 application。 |
| Standalone Client | base-url、access-token、connect-timeout、request-timeout、stream-idle-timeout、transport-retries | 只在 STANDALONE_CLIENT 生效；Token 属性值在 Actuator 与配置日志中脱敏。 |
| Admin UI | enabled、path、local-access-enabled、trusted-network-cidrs | 只在 EMBEDDED 生效；path 冲突或不合法时启动失败。 |
| Runtime Instance | instance-id、zone、shutdown-timeout | 决定心跳身份、区域和优雅关闭时间。 |
| Storage | data-source-bean-name、redis-connection-factory-bean-name、redis-required、schema-mode | 指定宿主 Bean、集群容量状态和数据结构处理方式。 |

核心接入信息见 2.6.11，各模式实际使用的配置项以上表和部署配置说明为准。Spring 配置元数据必须包含字段说明，使 IDE 可以补全。access-token 不允许出现在 `/actuator/configprops` 明文响应；Bean Validation 失败时启动异常指出属性路径和规则，不输出当前 Secret 值。

#### 4.6.3.3 自动装配 Bean

| Bean 或资源 | EMBEDDED | STANDALONE_CLIENT | 宿主覆盖规则 |
|---|---|---|---|
| LightAiClient | 创建 | 创建 | 宿主同类型 Bean 优先。 |
| RuntimeCore | 创建 | 不创建 | 宿主可提供完整实现。 |
| ConfigRepository、TraceRepository、UsageRepository | 创建 | 不创建 | 使用指定 DataSource。 |
| CapacityStore | Redis 或进程内 | 不创建 | 宿主可提供自定义实现。 |
| ProviderAdapterRegistry | 创建 | 不创建 | 收集全部 ProviderAdapter Bean。 |
| SecretProviderRegistry | 创建 | 不创建 | 收集 SecretProvider Bean。 |
| AuthContextProvider | 可选 | 不创建 | 宿主实现决定管理身份与数据范围。 |
| TraceExporter | 可选集合 | 不创建 | 失败不阻断业务响应并产生内部告警。 |
| Embedded Admin UI | 条件创建 | 不创建 | 需要 Web 应用且 admin.enabled=true。 |
| LightAiHealthContributor | 创建 | 创建 | Actuator 存在时注册。 |

#### 4.6.3.4 Embedded Admin UI 路径与认证

Admin UI 的静态资源、页面路由和管理 API 统一挂载在 light-ai.admin.path 下；业务模型调用仍通过注入的 LightAiClient，不增加宿主公开模型端点。启动时检查 Admin 根路径是否与 Spring MVC、WebFlux、Actuator 和静态资源映射冲突，冲突返回 ADMIN_PATH_CONFLICT。

存在 AuthContextProvider 时，每个页面和管理请求从宿主安全上下文取得 user_id、display_name、roles、application_scope，并按 2.4 校验。没有 AuthContextProvider 时 Admin UI 默认不可访问；只有 local-access-enabled=true 且直连来源为 loopback 或 trusted-network-cidrs 时，才建立 operator_id=LOCAL_ADMIN 的系统管理员上下文。代理头只在来源属于 RuntimeConfig.trusted_proxy_cidrs 时解析。拒绝访问返回 ACCESS_DENIED 并记录安全日志。

#### 4.6.3.5 启动、就绪与关闭

schema-mode=VALIDATE 时只验证所需表和版本；不一致阻止 Embedded Runtime 就绪。MIGRATE 时在数据库锁内执行产品自带的顺序迁移，失败回滚当前迁移并阻止启动。redis-required=true 且 Redis 不可用时不创建 RuntimeCore；false 时使用进程内容量状态，并在健康信息中标明仅适合单实例。

存在有效 ACTIVE 快照时加载后进入就绪并开始心跳；首次安装使用 snapshot_no=0。数据库暂时中断时继续使用内存快照处理调用，但配置管理、Trace 持久化一致性或容量安全条件不满足时按对应错误码拒绝新请求。关闭时先发送 accepting_requests=false 心跳并使健康状态 DOWN，再拒绝新调用；等待 shutdown-timeout 后取消剩余调用、结算或释放 CapacityReservation、刷新关键 Trace，最后关闭线程池。

#### 4.6.3.6 宿主与外部扩展 SPI

| SPI 方法 | 输入 | 返回 | 线程与失败规则 |
|---|---|---|---|
| SecretProvider.supports | secret_ref | boolean | 纯函数；多个实现同时匹配时返回 SECRET_PROVIDER_CONFLICT。 |
| SecretProvider.resolve | SecretResolveRequest | CompletionStage<ResolvedSecret> | 遵守 deadline 与 cancellation；失败映射 SECRET_RESOLUTION_FAILED。 |
| SecretProvider.invalidate | secret_ref、version | void | Credential 轮换或管理员显式失效时调用；重复调用幂等。 |
| AuthContextProvider.currentContext | 当前宿主管理请求 | AuthContext | 每个请求调用一次；异常按未认证处理并记录安全日志。 |
| TraceExporter.export | TraceExportBatch | CompletionStage<TraceExportResult> | 异步执行，不阻断模型响应；实现必须按 batch_id 幂等。 |

SecretProviderRegistry 按 supports 选择唯一实现。没有实现匹配、多个实现匹配、解析超时和返回空密钥均使当前 Credential 不可用；原始异常脱敏后进入 Attempt 或 ProviderCheckRecord。Runtime 可以缓存 ResolvedSecret，但缓存到期不得晚于 expires_at，Credential 轮换和 invalidate 必须主动清除。

AuthContextProvider 只用于 Embedded Admin UI 和管理 API，不参与业务 LightAiClient 的 application 判定。authenticated=false、roles 为空或 application_scope 不满足时拒绝请求。宿主 Cookie、Authorization 和完整 Principal 不复制到 AuthContext 与 AuditLog。

Trace 导出职责见 2.6.11。数据库形态在 Trace 最终事务后异步生成脱敏批次，失败按 1s、5s、30s 最多重试三次；Local Runtime 使用最多 10000 条的有界内存队列。最终失败产生 exporter_failure 指标和脱敏日志，不修改已经完成的 Trace 与业务响应。

完成标准：属性绑定、条件装配与 Bean 覆盖结果确定；Embedded 和 Standalone Client 不加载对方组件；管理路径与宿主认证安全；Secret、认证和 Trace 导出 SPI 的输入输出与失败边界确定；单实例与集群存储要求可验证；启动失败能定位属性、Bean 或数据结构；关闭后无活动容量预占和运行线程。

### 4.6.4 Standalone Server

#### 4.6.4.1 部署配置与启动顺序

Standalone Server 从环境变量、受保护配置文件或部署平台 Secret 注入 StandaloneServerConfig。普通配置可以在启动摘要中输出，database_password、redis_password、TLS 私钥和内部服务身份始终脱敏。public_base_url 必须是 HTTPS；tls_mode=TERMINATE 时进程加载证书，BEHIND_PROXY 时只信任配置的反向代理来源并由外层终止 TLS。

启动顺序固定为：校验部署配置；验证或迁移数据库结构；连接数据库；连接 Redis 并执行原子能力检查；注册内置与自定义 Provider Adapter；加载 ACTIVE ConfigSnapshot；初始化路由、容量、可靠性、Trace 和 Usage 组件；注册 RuntimeInstance 并发送首次心跳；启动业务、管理与内部 HTTP 路由；全部就绪检查通过后把 readiness 改为 UP。任一步失败均不得短暂进入 UP。

migration_mode=VALIDATE 发现版本不匹配时退出进程；MIGRATE 在全局数据库迁移锁内执行，其他实例等待并在完成后重新验证。单实例允许 redis_url 为空并使用进程内状态；检测到相同数据库存在其他 ONLINE Standalone 实例且未配置 Redis 时 readiness=DOWN，防止形成分裂的容量状态。

#### 4.6.4.2 对外与内部入口

| 方法与路径 | 认证 | 成功响应 | 主要用途 |
|---|---|---|---|
| GET /health/live | 无 | HealthResponse | 仅确认进程与 HTTP 线程可响应。 |
| GET /health/ready | 无 | ReadinessResponse 的 status 与 time | 供负载均衡摘挂实例，不暴露内部连接信息。 |
| GET /v1/models | Bearer Access Token | UnifiedModelList | 返回凭证范围内 Alias。 |
| POST /v1/chat/completions | Bearer Access Token | UnifiedChatResponse 或 SSE | 执行统一模型调用。 |
| GET /admin | 管理身份 | Admin UI | 进入管理端。 |
| /admin/** | 管理身份与角色 | 管理 API | 执行 4.1—4.6 管理能力。 |
| /internal/** | 实例服务身份或双向 TLS | 内部对象 | 心跳、快照和发布实例上报。 |

业务 API、管理 API 和内部 API 使用独立认证过滤链。Bearer Access Token 只能访问 `/v1/**`，管理会话不能作为业务 Token，实例身份只能访问 `/internal/**`。路由未命中统一返回 404，不把管理或内部路由是否存在暴露给无权限身份。

#### 4.6.4.3 健康与就绪规则

/health/live 只在进程无法继续运行时失败，不检查外部 Provider、数据库和 Redis。/health/ready 同时要求 DATABASE、CAPACITY_STORE、CONFIG_SNAPSHOT 和 ADAPTER_REGISTRY 为 UP，并要求 accepting_requests=true。外部 Provider 暂时不可用不影响 Server readiness，由候选运行状态、Fallback 和统一错误处理。

公开 readiness 响应只包含 status 与 time。系统管理员通过运行实例详情查看完整 ReadinessCheck，包括 active_snapshot_no、检查项、机器码与 last_success_at。数据库或共享容量状态失效时实例立即停止接受新请求并进入 DRAINING 或 STALE；已建立流按当前本地快照继续到完成或 ReliabilityPolicy 超时。

#### 4.6.4.4 集群一致性

集群实例共享数据库中的配置、访问凭证摘要、Trace、Usage、发布和审计数据，并共享 Redis 中 RPM、TPM、并发、队列和熔断状态。业务请求不要求会话粘滞；一个 SSE 调用建立后由接收实例持续处理。每个实例使用唯一 instance_id 心跳，并按 4.5.2 完成快照准备与激活。

Access Credential 编辑、轮换、停用和删除提交后，新请求在全部实例上立即读取相同持久状态；允许最多一个 dashboard_refresh_seconds 的本地只读缓存，轮换、停用和删除通过失效通知主动清除缓存。失效通知不可用时，安全状态读取绕过缓存，避免旧 Token 继续鉴权。

#### 4.6.4.5 优雅关闭与安全边界

实例收到正常终止信号后立即把 accepting_requests 改为 false、发送 DRAINING 心跳并使 readiness=DOWN。负载均衡停止分配新连接后，实例等待当前同步与流式调用；到达 shutdown_timeout_seconds 后向 Provider 传播取消，最终化 Trace，释放或结算 CapacityReservation，并关闭数据库与 Redis 连接。异常退出遗留的容量预占由 Watchdog 按 expires_at 回收。

请求正文上限使用 RuntimeConfig.max_request_chars 与 HTTP 解码上限中的较小值。Authorization、Cookie、数据库与 Redis 密码、Provider Credential、完整 secret_ref 和消息正文不得进入访问日志。SSE 响应禁用代理缓冲和内容压缩，定期数据由真实 Provider 事件驱动，不额外发送会被 SDK 当作模型内容的心跳块。

完成标准：部署字段有确定来源、默认值与条件校验；启动和迁移不会产生假就绪；三类入口身份隔离；健康检查可以安全驱动负载均衡；多实例容量、凭证状态与配置一致；优雅关闭不接收新请求并最终释放运行资源。

### 4.6.5 开发接入管理接口契约

| 方法与路径 | 请求字段 | 成功响应 | 权限 | 主要错误码 |
|---|---|---|---|---|
| GET /admin/developer-access/context | alias_id | DeveloperAccessContext | 可查看角色 | MODEL_ALIAS_NOT_FOUND、ACCESS_DENIED |
| GET /admin/developer-access/code-sample | CodeSampleRequest 查询字段 | CodeSampleResult | 可查看角色 | FIELD_VALIDATION_FAILED、MODEL_ALIAS_NOT_FOUND |
| POST /admin/developer-access/test/chat | ApiTestCommand | ApiTestResult | 系统管理员、运维人员、开发人员 | FIELD_VALIDATION_FAILED、MODEL_ALIAS_NOT_FOUND、MODEL_CAPABILITY_NOT_SUPPORTED、CAPACITY_LIMITED |
| POST /admin/developer-access/test/chat/stream | ApiTestCommand，stream 固定 true | text/event-stream，数据映射 StreamEvent | 系统管理员、运维人员、开发人员 | FIELD_VALIDATION_FAILED、MODEL_ALIAS_NOT_FOUND、MODEL_CAPABILITY_NOT_SUPPORTED、CAPACITY_LIMITED |

开发测试接口使用管理身份与 application_scope，不接受 Authorization Token 字段。普通错误响应使用统一失败结果；流建立后的错误按 2.6.10 的流式规则结束。测试输入转换为统一模型请求时，system_message 位于 messages 首项，user_message 位于末项，metadata.tags 自动增加 light_ai_test 和 operator_id；页面不能覆盖这些系统标签。

## 4.7 统一模型调用

### 4.7.1 HTTP 接口

#### 4.7.1.1 协议边界与公共请求规则

Standalone Server 对外提供 `GET /v1/models` 和 `POST /v1/chat/completions`。请求与响应使用 UTF-8 JSON；流式响应使用 UTF-8 SSE。V1.0 不提供文本补全、Embedding、图片、音频、Realtime、Responses、Tool Calling 与多模态端点，这些路径返回 404。

| 请求项 | 规则 | 失败处理 |
|---|---|---|
| Authorization | 固定 `Bearer <Standalone Access Token>` | 缺失、格式错误、摘要不匹配、停用或过期返回 ACCESS_TOKEN_INVALID。 |
| Content-Type | POST 固定 application/json，可带 charset=utf-8 | 其他类型返回 UNSUPPORTED_CONTENT_TYPE。 |
| Accept | 非流式允许 application/json 或 */*；流式允许 text/event-stream 或 */* | 不兼容返回 FIELD_VALIDATION_FAILED。 |
| Content-Encoding | V1.0 只接受 identity | 压缩请求返回 UNSUPPORTED_CONTENT_ENCODING。 |
| X-Trace-Id | 可选，规则与请求体 trace_id 相同 | 与请求体不一致或格式错误返回 FIELD_VALIDATION_FAILED。 |
| User-Agent | 可选，最多 512 字符 | 超长截断后仅写 Trace.user_agent，不影响调用。 |
| 请求体大小 | JSON 解码前限制为 `min(部署 HTTP 上限, max_request_chars × 4 + 65536)` 字节 | 超限返回 REQUEST_TOO_LARGE，且不解析正文。 |
| 未知字段 | 顶层、Message、metadata、stream_options 中未知字段均拒绝 | 返回 FIELD_VALIDATION_FAILED 并在 error.param 指出字段。 |

服务端响应始终设置 X-Light-AI-Version，并在存在 Trace 时设置 X-Trace-Id；身份、IP、请求体解码或 Alias 解析前失败时不返回 X-Trace-Id。错误响应设置 Cache-Control: no-store。任何响应头不得包含 Access Credential、Provider Credential、完整 secret_ref 和消息正文。

#### 4.7.1.2 GET /v1/models

服务端完成 Token、状态、有效期和来源 IP 鉴权后，从当前 ACTIVE ConfigSnapshot 读取 ModelAlias，只保留 enabled=true、具有至少一个可用配置候选且位于 allowed_alias_ids 范围内的对象。空 allowed_alias_ids 表示全部已发布 Alias。实时容量耗尽和临时熔断不从列表移除模型，避免模型目录随瞬时状态抖动。

成功返回 UnifiedModelList，object 固定 list，data 按 id asc，不分页。每项包含 OpenAI 风格的 id、object、created、owned_by，以及 light_ai 下的显示名称、流式、system、temperature、top_p、stop 能力与范围、最大上下文、最大输出和更新时间。Alias 能力表示至少一个已发布候选可以处理对应参数，具体调用仍按候选模型范围过滤。列表为空仍返回 HTTP 200 与空数组。该接口不创建业务 Trace，鉴权失败进入安全日志，成功调用只记录低基数接口指标。

#### 4.7.1.3 非流式 Chat Completions

stream=false 或未提供时执行非流式流程。服务端在读取当前活动快照后按固定顺序处理：校验访问身份和来源 IP；解析 JSON 与字段结构；解析有权限的 Model Alias；检查 trace_id 唯一；生成 Trace；校验 Message、参数和 Provider Option；使用候选 TokenEstimator 估算输入 Token；过滤能力、上下文、停用、熔断和容量不满足的候选；进入 4.3.4 路由、凭证、可靠性和 Provider 调用；结算 Usage 与 Cost；最终化 Trace；构造 UnifiedChatResponse。

Alias 解析前失败不创建 Trace。Alias 解析成功后，无论能力过滤、容量、Provider、结算或内部执行成功与否都必须最终化同一 Trace，并在错误响应返回 trace_id。服务端内部 RETRY、CREDENTIAL_FAILOVER 和 FALLBACK 仍属于同一 Trace，不改变外部响应 ID。

同步成功返回 HTTP 200 和 UnifiedChatResponse。choices 只有一项，model 保留请求 Alias；Provider 实际模型只出现在 light_ai 扩展。响应 Usage 表示最终成功 Attempt，light_ai.cost 表示该 Trace 全部可计费 Attempt 成本。Trace 最终事务提交失败时不返回成功模型结果，返回 INTERNAL_ERROR 并产生高优先级告警，避免响应成功却无法追踪。

#### 4.7.1.4 流式 Chat Completions

stream=true 时，服务端先完成身份、请求、Alias、能力、上下文、容量和路由处理，并连接首个 Provider。Provider 在首个可输出内容前失败时，运行时仍可按策略 RETRY、CREDENTIAL_FAILOVER 或 FALLBACK；外部 SSE 响应尚未提交。取得首个内容或无内容的正常结束后，服务端确定最终输出路径，提交 HTTP 200 与 SSE 响应头，依次发送首个 role 块、内容块、结束选择块、可选 Usage 块和 `[DONE]`。

第一个 SSE 业务块写出时设置 Trace.response_committed=true。此后 Provider 失败、超时或协议中断不得切换候选，服务端发送 `data: {"error":<UnifiedError>}`，关闭连接，并把 Trace 标记 STREAM_INTERRUPTED。已经产生的 Provider Usage 可以结算，缺失部分按 TokenEstimator 估算；不发送成功 finish_reason 与 `[DONE]`。

| 流式阶段 | HTTP/SSE 行为 | Trace 与运行行为 |
|---|---|---|
| 响应未提交，校验失败 | 返回普通 JSON 错误与对应 HTTP 状态 | Alias 解析后错误有 Trace；可重试规则按错误分类。 |
| 响应未提交，路径失败 | Runtime 内部重试或切换候选 | 每次外部调用形成 Attempt。 |
| 首次提交 | 发送响应头、role 块及首个内容或结束块 | response_committed=true，固定最终输出路径。 |
| 内容输出 | 按到达顺序发送 UnifiedChatChunk | sequence 连续递增，不合并跨 Provider 内容。 |
| 正常结束 | 发送 finish 块、可选 Usage 块、`data: [DONE]` | Trace=SUCCEEDED，归还容量并生成聚合事件。 |
| 提交后失败 | 发送错误对象并关闭连接 | Trace=STREAM_INTERRUPTED，不执行 Fallback。 |
| 客户端断开 | 停止写出并传播取消 | Trace=CANCELLED，释放容量；不再发送任何块。 |

SSE 响应设置 Content-Type: text/event-stream、Cache-Control: no-cache、Connection: keep-alive 和 X-Accel-Buffering: no。sequence 从 0 开始连续递增。include_usage=false 时不向调用方发送 Usage 块，但 Trace 仍记录用量与成本。连接写入阻塞计入 total_timeout；客户端消费过慢导致写超时时按 CLIENT_CANCELLED 处理。

#### 4.7.1.5 字段、能力与上下文校验

基础字段校验一次性收集能够安全确定的全部字段问题，error.param 返回第一项，error.message 可以包含其余字段路径摘要。messages 至少一条 user，system 最多一条且位于首项；空正文、超长正文、总字符超限、无效枚举和未知字段均在调用 Provider 前拦截。

每个候选独立解析 temperature、top_p、max_tokens、stop、stream、system 和 provider_options 支持情况。请求未给出采样参数时使用候选 ProviderModel 默认值，因此不同候选可以具有不同最终值。temperature、top_p 与 stop 必须同时满足 ProviderModel 支持状态、模型级范围和 ProviderCapabilities 上界；请求显式给出某参数时，不支持该参数或取值超出范围的候选被过滤。max_tokens 未提供时取候选 default_max_tokens，并限制为 max_output_tokens 与 context_window - estimated_input_tokens 的较小值；结果小于 1 时过滤候选。

TokenEstimator 对每个不同 tokenizer_family 至多执行一次估算并缓存于当前 RouteExecutionContext。候选满足 `estimated_input_tokens + resolved_max_tokens <= context_window` 才能保留。所有候选因能力被过滤返回 MODEL_CAPABILITY_NOT_SUPPORTED；均因上下文被过滤返回 CONTEXT_WINDOW_EXCEEDED；两类原因同时存在时，如果至少一个候选能力满足但上下文不足，返回 CONTEXT_WINDOW_EXCEEDED。

#### 4.7.1.6 Trace ID、重试与取消边界

trace_id 只用于关联，不提供结果复用。接入方提供的 trace_id 已存在时返回 TRACE_ID_CONFLICT，不返回已有结果，也不启动新调用。V1.0 不处理 Idempotency-Key；业务方重试完整模型调用时必须接受可能产生新内容和新费用，并使用新的 trace_id 或不提供该字段。

retryable=true 表示相同业务请求在外部条件恢复后可以重新发起，不表示 SDK 或 Server 会在 Trace 结束后自动重放。Server 内部重试只发生在同一 Trace 的 ReliabilityPolicy 预算内。HTTP 客户端关闭、Java CancellationSignal、总超时与实例关闭共用取消路径；只有第一个终止信号生效，其余操作幂等。

#### 4.7.1.7 业务接口契约

| 方法与路径 | 请求 | 成功响应 | 主要错误码 |
|---|---|---|---|
| GET /v1/models | Authorization、来源 IP | UnifiedModelList | ACCESS_TOKEN_INVALID、ACCESS_IP_DENIED、INTERNAL_ERROR |
| POST /v1/chat/completions，stream=false | UnifiedChatRequest | UnifiedChatResponse | FIELD_VALIDATION_FAILED、TRACE_ID_CONFLICT、MODEL_ALIAS_NOT_FOUND、ACCESS_DENIED、MODEL_CAPABILITY_NOT_SUPPORTED、CONTEXT_WINDOW_EXCEEDED、CAPACITY_LIMITED、ALL_CANDIDATES_FAILED |
| POST /v1/chat/completions，stream=true，提交前 | UnifiedChatRequest | SSE 响应或普通错误 | 与非流式相同。 |
| POST /v1/chat/completions，stream=true，提交后 | 已建立 SSE | UnifiedChatChunk、流式错误或连接结束 | STREAM_INTERRUPTED、CLIENT_CANCELLED。 |

### 4.7.2 Provider SPI

#### 4.7.2.1 ProviderAdapter 方法契约

| 方法 | 输入 | 输出 | 规则 |
|---|---|---|---|
| providerType | 无 | string | 进程内唯一，必须与 Provider.type 完全匹配。 |
| capabilities | 无 | ProviderCapabilities | 启动后不可变化，供表单、发布和运行过滤使用。 |
| validateConfig | ProviderConfigView | ProviderConfigValidation | 只执行本地结构校验，不访问网络和 Secret。 |
| listModels | ProviderOperationContext | array<ProviderModelDescriptor> | supports_model_list=true 时实现；只用于管理员显式导入。 |
| checkConnection | ProviderOperationContext 与检测模式 | ProviderCheckRecord 数据 | 只在管理员显式检测时访问 Provider。 |
| estimateTokens | ProviderChatRequest、model_id | long | 返回大于等于 0 的输入 Token 估算；不得访问 Provider 网络。 |
| chat | ProviderCallContext | ProviderChatResponse | 执行一次非流式外部调用，不在 Adapter 内重试。 |
| streamChat | ProviderCallContext | Flow.Publisher<ProviderStreamChunk> | 执行一次流式外部调用，支持背压和取消。 |
| classifyError | ProviderFailure | ErrorClassification | 纯函数，不访问网络与持久化状态。 |

内置 OPENAI、ANTHROPIC、GEMINI 和 DEEPSEEK Adapter 与 CUSTOM_SPI 使用同一接口。ProviderAdapter 作为单例注册并必须线程安全；方法不能把可变调用状态保存在实例字段。capabilities 和 validateConfig 可以在管理请求线程执行，chat、streamChat、listModels 与 checkConnection 必须遵守 ProviderCallContext.deadline_at 和 cancellation。

#### 4.7.2.2 配置、注册与发布数据流

应用启动时 ProviderAdapterRegistry 收集所有 Adapter，读取 providerType 和 capabilities，检查唯一性与字段完整性。缺少已配置 Provider.type 对应 Adapter 时，运行实例心跳的 loaded_adapter_types 不包含该类型，发布校验生成 ADAPTER_UNAVAILABLE。重复 providerType 阻止实例就绪。

Provider 编辑保存时调用 validateConfig，对 base_url、代理、超时和 Adapter 专属静态规则生成字段问题。配置发布再次针对固定草稿执行同一校验，并检查所有 ONLINE 目标实例 loaded_adapter_types。validateConfig 不读取 Credential、不请求外部 Provider；连接可用性由 ProviderCheckRecord 形成独立 WARNING。

ProviderOptionSpec 是 provider_options 的唯一白名单来源。内置 Adapter 的 option_specs 随运行版本固定；CUSTOM_SPI 在 capabilities 中声明。请求 option key 必须使用 provider_type.option_name，路由只保留 provider_type 与全部 option key 匹配的候选，Adapter 收到的 ProviderChatRequest 已移除其他类型选项。

#### 4.7.2.3 单次 Provider 调用数据流

Runtime 先选定 RouteCandidate 和 Credential，创建 CapacityReservation、RouteDecision 与 Attempt，再通过 CredentialSecretResolver 得到 secret handle，并构造 ProviderCallContext。Adapter 根据 ProviderConfigView、outbound_headers、model_id 和 secret handle 生成外部请求；Runtime 不向 Adapter 提供其他 Credential 或候选列表。

Adapter 负责协议转换、认证格式、HTTP 发送、响应解析、Provider Usage、Provider 响应 ID 与请求 ID 提取。返回 ProviderChatResponse 后，Runtime 校验 content、finish_reason 和 Usage，缺少 Usage 时调用 estimateTokens 生成 ESTIMATED；随后结算容量、成本和 Attempt。Adapter 不计算业务费用，不写 Trace，不修改 Credential 健康，不更新熔断状态。

secret handle 只能在构造认证材料的最小作用域内读取。Adapter 不得缓存 char[]、String 密钥或完整认证头；调用结束在 finally 中清除可修改缓冲。outbound_headers 已排除认证字段，Adapter 对默认头和自身认证头进行合并，Credential 认证值具有最高优先级且不允许被 Provider.default_headers 覆盖。

#### 4.7.2.4 流式 Adapter 规则

streamChat Publisher 每个订阅对应一次外部 HTTP 调用且只允许一个 Subscriber。Provider 内容按原顺序转换为 CONTENT，Usage 转换为 USAGE，正常结束转换为唯一 FINISH；Provider 缺少明确结束事件时，只有协议允许通过正常连接关闭判断结束，才能生成 FINISH。取消 Subscription 必须关闭外部响应体并触发 cancellation。

Adapter 不把 Provider 注释、心跳、空数据行和协议控制帧转换成 CONTENT。单个 Unicode 字符被底层网络分片时，解码器等待完整 UTF-8 序列。Provider 返回多个文本 content block 时按到达顺序转换为连续 CONTENT。V1.0 遇到工具、多模态或无法转换的 block 时以 PROVIDER_BAD_RESPONSE 失败，不输出部分结构化对象。

Runtime 在向业务调用方提交首个块前可以取消当前 Adapter 并依据 ErrorClassification 选择恢复动作；提交后 Adapter 错误直接结束当前流。ProviderStreamChunk 不包含原始响应体，诊断日志只能保存事件类型、字节数、序号和脱敏 Provider Request ID。

#### 4.7.2.5 错误分类基线

| Provider 条件 | unified_code | retryable | Credential Failover | Fallback | 计入熔断 |
|---|---|---:|---:|---:|---:|
| DNS、连接重置、可重试网络错误 | NETWORK_ERROR | true | true | true | true |
| 建连超时 | CONNECT_TIMEOUT | true | true | true | true |
| Provider 401 或明确密钥无效 | PROVIDER_AUTH_FAILED | false | true | true | false |
| Provider 403 且属于密钥权限 | PROVIDER_AUTH_FAILED | false | true | true | false |
| Provider 429 | PROVIDER_RATE_LIMITED | true | true | true | false |
| Provider 明确模型不存在 | PROVIDER_MODEL_NOT_FOUND | false | false | true | false |
| Provider 拒绝已经过本系统校验的请求参数 | PROVIDER_REQUEST_REJECTED | false | false | false | false |
| Provider 500、502、503、504 | PROVIDER_SERVER_ERROR | true | true | true | true |
| 响应 JSON、SSE 或 Usage 无法解析 | PROVIDER_BAD_RESPONSE | true | true | true | true |
| 首 Token 超时 | FIRST_TOKEN_TIMEOUT | true | true | true | true |
| 当前 Trace 到达总期限 | TOTAL_TIMEOUT | false | false | false | false |
| Provider 正常内容过滤结束 | 无错误，finish_reason=CONTENT_FILTER | false | false | false | false |

Adapter 可以根据 Provider 明确语义细化分类，但不能把鉴权、权限、请求参数和正常内容过滤标记为普通可重试 5xx。classifyError 返回的 unified_code 必须存在于 4.7.3。Runtime 根据 retryable、credential_failover_allowed、fallback_allowed、counts_toward_circuit 和 ReliabilityPolicy 生成 RecoveryDecision；Adapter 自身不执行恢复动作。

#### 4.7.2.6 SPI 完成标准

每个内置 Adapter 必须通过统一契约测试：能力声明与实际方法一致；字段转换正确；同步与流式 Usage 可提取或估算；取消关闭外部连接；同一 Adapter 支持并发调用；错误分类符合基线；密钥和消息不进入日志；Adapter 内没有 Retry、Credential Failover、Fallback、费用、Trace 与熔断业务逻辑。CUSTOM_SPI 在注册时接受相同检查，缺失必需能力时不进入就绪状态。

### 4.7.3 统一错误码

| 错误码 | HTTP 状态 | retryable | 说明 |
|---|---:|---|---|
| FIELD_VALIDATION_FAILED | 400 | false | 请求或管理字段不合法。 |
| SECRET_CONFIRM_MISMATCH | 400 | false | 两次输入的密钥不一致。 |
| CONFIRMATION_TEXT_MISMATCH | 400 | false | 高风险操作的固定确认文本不匹配。 |
| REQUEST_TOO_LARGE | 413 | false | HTTP 请求体超过运行配置或部署上限。 |
| UNSUPPORTED_CONTENT_TYPE | 415 | false | 请求 Content-Type 不是 application/json。 |
| UNSUPPORTED_CONTENT_ENCODING | 415 | false | V1.0 不接受压缩请求体。 |
| ACCESS_TOKEN_INVALID | 401 | false | Standalone Access Token 无效、过期或停用。 |
| INSTANCE_AUTH_FAILED | 401 | false | 运行实例内部接口身份认证失败。 |
| ACCESS_IP_DENIED | 403 | false | 调用来源 IP 不在访问凭证允许范围。 |
| ACCESS_DENIED | 403 | false | 无页面、对象或 Alias 权限。 |
| OBJECT_NOT_FOUND | 404 | false | 管理对象不存在或已删除。 |
| MODEL_ALIAS_NOT_FOUND | 404 | false | Alias 不存在或未发布。 |
| OBJECT_IN_USE | 409 | false | 管理对象仍被其他配置引用，不能删除。 |
| CAPACITY_IN_USE | 409 | true | Credential 仍有运行中 Attempt，不能删除。 |
| IMPACT_ANALYSIS_EXPIRED | 409 | false | 确认后引用关系已经变化，需要重新确认影响。 |
| DUPLICATE_ROUTE_CANDIDATE | 409 | false | Alias 下已存在相同模型与凭证池组合。 |
| JOB_ALREADY_FINISHED | 409 | false | 批量检测任务已结束，不能取消。 |
| MODEL_ALIAS_DISABLED | 409 | false | Alias 已停用。 |
| LIMIT_POLICY_CONFLICT | 409 | false | 同一作用对象已存在另一条启用的限流策略。 |
| RELIABILITY_POLICY_CONFLICT | 409 | false | 同一 Alias 已存在另一条启用的可靠性策略。 |
| CIRCUIT_STATE_CONFLICT | 409 | true | 熔断状态或 state_version 已变化，需要刷新后重新操作。 |
| TRACE_ID_CONFLICT | 409 | false | 接入方提供的 trace_id 已被使用。 |
| ADMIN_PATH_CONFLICT | 409 | false | Embedded Admin UI 路径与宿主路由冲突。 |
| SECRET_PROVIDER_CONFLICT | 409 | false | 多个 SecretProvider 同时匹配同一 secret_ref。 |
| CONFIG_DRAFT_CHANGED | 409 | false | 全局草稿修订号已变化，当前校验、撤销或发布请求失效。 |
| DRAFT_REVERT_BLOCKED | 409 | false | 其他草稿对象仍引用目标对象，不能单独撤销。 |
| CONFIG_VALIDATION_EXPIRED | 409 | false | 配置校验已过期、已使用或不再对应当前草稿。 |
| CONFIG_PUBLISH_IN_PROGRESS | 409 | true | 已有发布占用全局草稿锁。 |
| CONFIG_FIELD_IMMUTABLE | 409 | false | 当前数据状态下字段已经锁定，不能修改。 |
| RETENTION_IMPACT_EXPIRED | 409 | false | 保留期影响估算已过期或目标参数发生变化。 |
| ACCESS_CREDENTIAL_EXPIRED | 409 | false | 已过期的访问凭证不能重新启用。 |
| SNAPSHOT_CHECKSUM_MISMATCH | 409 | true | 实例下载的快照内容摘要与发布指令不一致。 |
| INSTANCE_REPORT_CONFLICT | 409 | true | 实例上报早于已保存状态或与当前发布阶段冲突。 |
| PROVIDER_ADAPTER_NOT_FOUND | 422 | false | Provider 类型对应的 Adapter 未加载。 |
| OBJECT_REFERENCE_INVALID | 422 | false | 配置引用不存在、已删除或 Provider 关系不一致。 |
| CHECK_TARGET_INVALID | 422 | false | 检测命令中的模型、凭证或目标关系不合法。 |
| MODEL_LIST_NOT_SUPPORTED | 422 | false | Provider Adapter 不支持读取外部模型列表。 |
| MODEL_CAPABILITY_NOT_SUPPORTED | 422 | false | 没有候选满足流式、system 或上下文能力。 |
| CONTEXT_WINDOW_EXCEEDED | 422 | false | 输入与最大输出超过全部候选上下文。 |
| EXPORT_TOO_LARGE | 422 | false | 当前筛选预计导出超过 100000 行，需要缩小时间或业务范围。 |
| MODE_NOT_SUPPORTED | 422 | false | 当前运行模式不支持该管理能力。 |
| INSTANCE_VERSION_INCOMPATIBLE | 422 | false | 运行实例版本不支持目标快照结构或启用的 Adapter。 |
| CAPACITY_LIMITED | 429 | true | RPM、TPM 或并发容量不足。 |
| QUEUE_FULL | 429 | true | 当前 Alias 队列达到生效策略允许的最小上限。 |
| QUEUE_TIMEOUT | 429 | true | 排队等待超过限制。 |
| PROVIDER_AUTH_FAILED | 502 | false | 外部 Provider 鉴权失败。 |
| PROVIDER_MODEL_NOT_FOUND | 502 | false | Provider 明确返回目标模型不存在或不可用。 |
| PROVIDER_REQUEST_REJECTED | 502 | false | Provider 拒绝已经过本系统校验的请求参数。 |
| PROVIDER_RATE_LIMITED | 503 | true | Provider 返回限流且无可用 Fallback。 |
| PROVIDER_BAD_RESPONSE | 502 | true | Provider 响应无法解析或协议不完整。 |
| SERVER_PROTOCOL_ERROR | — | false | Java SDK 收到无法解析的 Standalone 响应，调用结果可能已经产生。 |
| PROVIDER_SERVER_ERROR | 503 | true | Provider 返回可重试服务端错误。 |
| SECRET_RESOLUTION_FAILED | 502 | true | 外部 Secret 无法读取或返回无效密钥。 |
| IMPORT_SOURCE_UNAVAILABLE | 503 | true | 模型导入来源当前不可用。 |
| NETWORK_ERROR | 503 | true | 与 Provider 通信时发生可重试网络错误。 |
| CREDENTIAL_NOT_AVAILABLE | 503 | true | 当前候选的凭证池没有可用 Credential。 |
| CIRCUIT_OPEN | 503 | true | 当前模型与 Credential 路径处于 OPEN，且没有其他可用路径。 |
| CAPACITY_STATE_UNAVAILABLE | 503 | true | 全局容量状态存储不可用，无法安全创建新预占。 |
| OBSERVATION_DATA_UNAVAILABLE | 503 | true | Trace 关联明细或 Usage 聚合当前无法完整读取。 |
| CONFIG_DATA_UNAVAILABLE | 503 | true | 配置草稿、快照或发布数据当前无法完整读取。 |
| AUDIT_DATA_UNAVAILABLE | 503 | true | 审计数据当前无法完整读取。 |
| NO_ONLINE_RUNTIME_INSTANCE | 503 | true | 当前没有可参与配置发布的在线运行实例。 |
| CONNECT_TIMEOUT | 504 | true | 连接 Provider 超时。 |
| FIRST_TOKEN_TIMEOUT | 504 | true | 流式首 Token 超时。 |
| TOTAL_TIMEOUT | 504 | true | 调用总超时。 |
| ALL_CANDIDATES_FAILED | 503 | true | 所有候选尝试均失败。 |
| STREAM_INTERRUPTED | 502 | true | 已输出内容后外部流中断。 |
| CLIENT_CANCELLED | 499 | false | 客户端在排队或调用过程中主动断开。 |
| CLIENT_CLOSED | — | false | Java LightAiClient 已关闭，不能创建新调用。 |
| CONFIG_VERSION_CONFLICT | 409 | false | 编辑对象版本已变化。 |
| INTERNAL_ERROR | 500 | false | 未分类内部错误。 |

同步 HTTP 错误使用 UnifiedErrorEnvelope，error 至少包含 code、type、message 和 retryable；字段错误返回 param；已经创建 Trace 时返回 trace_id；可以重试且能确定等待时间时返回 retry_after_ms。流提交后的错误把相同 UnifiedError 放入 SSE data。message 不包含 Credential、Authorization、消息正文和 Provider 原始敏感信息。表中 HTTP 状态为“—”的错误只在 Java SDK 本地产生。

## 4.8 删除、停用与引用规则

配置对象优先停用。Provider 被 Credential Pool 或 Provider Model 引用时禁止删除；Credential Pool 包含 Credential 或被 Route Candidate 引用时禁止删除；Provider Model 被 Route Candidate 引用时禁止删除；Model Alias 被 Limit Policy、Reliability Policy 或 Standalone Access Credential 引用时禁止删除。Route Candidate 可以在草稿中删除，发布后从运行快照移除。

删除无引用草稿对象需要二次确认并写审计。已进入历史 Trace、Attempt、PublishRecord 或 AuditLog 的 ID 保留为历史引用，关联对象删除后页面以历史快照名称展示。

# 5. 非功能需求

## 5.1 性能与容量

在 Provider 响应耗时之外，路由、凭证选择、容量判断和 Trace 初始化的服务端附加 P95 延迟应不高于 20ms。同步响应与流式转发不缓存完整输出后再返回。Standalone 单实例目标支持至少 200 个并发流式连接，具体发布容量通过压测基线确认。

Trace 写入不得阻塞 Provider 内容转发；关键状态先可靠记录，非关键聚合异步执行。Usage 聚合允许最多两个 dashboard_refresh_seconds 的展示延迟。

Java LightAiClient 必须线程安全并复用 HTTP 连接池。流式路径每个订阅最多缓冲 32 个 StreamEvent，达到上限后使用背压暂停上游读取，不以扩大内存缓冲处理慢消费者。输入正文受 max_request_chars 保护，模型输出受 Provider Model.max_output_tokens 保护；服务端不得在返回前复制多份完整模型文本。

## 5.2 可用性与一致性

Standalone 目标月可用性为 99.9%，外部 Provider 故障时间不计入服务自身可用性，但应正确执行 Fallback。配置快照原子加载，一次 Trace 固定使用一个 snapshot_no。集群容量计数使用共享原子存储；共享存储不可用时停止创建新的容量预占，返回 CAPACITY_STATE_UNAVAILABLE 并产生告警。单实例 SDK 或 Embedded Mode 使用进程内原子状态，不依赖共享存储。

数据库保存配置、Trace、Usage、审计和发布记录。Redis 或兼容共享缓存用于集群 RPM、TPM、并发、队列和熔断状态；单实例 Embedded Mode 可以使用进程内实现。数据库和 Redis 属于部署依赖，其选型与连接参数由工程设计确定。

Standalone 与 Embedded Runtime 只有在配置快照、Adapter Registry 和必需存储均可用时进入就绪。优雅关闭先停止接收新调用，再处理存量调用和容量归还。运行实例失去共享容量状态时停止创建新 Trace，避免在集群中突破全局限制。

## 5.3 安全

外部调用和管理接口默认使用 HTTPS。Credential secret_value 采用受保护密钥加密后存储，主加密密钥不与业务数据保存在同一位置。所有密钥字段在页面、API、日志、指标、Trace 和审计中脱敏。管理接口执行角色权限校验，Standalone Access Token 只保存单向摘要。

消息正文默认不持久化。诊断采样必须由部署方显式启用，并配置采样比例、脱敏规则和保留期。导出、日志和外部 Trace Exporter 不包含消息正文与认证信息。

SDK、Starter 和 Standalone 的配置日志、异常、Actuator、健康响应与接入示例不得输出 access_token、数据库与 Redis 密码、TLS 私钥、Provider Credential 和完整 secret_ref。业务 API 只接受预定义 JSON 字段和 identity 编码，请求体超限时在解析正文前拒绝。

## 5.4 可扩展性与兼容性

新增 Provider 通过 Provider SPI 实现，不修改路由和治理核心。Adapter 能力声明用于管理端字段校验和运行时候选过滤。Java SDK 与 Server 在同一主版本内保持请求、响应和错误码兼容；新增可选字段不改变已有字段含义。

V1.x Server 可以在响应中增加可选字段，V1.x Java Client 必须忽略未知响应字段；Server 对未知请求字段保持严格拒绝，避免调用方误以为参数已经生效。SDK 与 Server 主版本不一致时，客户端在首次成功响应读取版本头后产生兼容性告警；协议解析仍以实际字段为准。ProviderAdapter.adapter_version 与产品版本独立，但 provider_type 和既有 ProviderOptionSpec.key 的含义在 V1.0 内保持稳定。

## 5.5 可观测性

系统输出请求量、成功率、错误率、耗时分位数、首 Token 耗时、Token、实际与估算用量比例、费用、重试、Credential Failover、Fallback、限流、队列、并发、熔断、聚合待处理数量和聚合延迟指标。高基数 trace_id、attempt_id 和 credential_id 不作为 Metrics 标签。日志使用 trace_id 关联，Provider Request ID 只写入受控诊断字段。

## 5.6 数据保留与清理

Trace 默认保留 30 天，Usage 默认保留 365 天，审计至少保留 365 天。清理按时间分批执行，避免长事务；清理 Trace 时保留 UsageAggregate。配置快照和发布记录至少保留最近 100 个成功版本及全部近 365 天发布记录。

# 6. 验收要求

## 6.1 模型接入验收

系统管理员可以完成 Provider、Credential Pool、Credential、Provider Model、Model Alias 和 Route Candidate 的完整配置。Provider Model 页面包含上下文、输出、流式、system、默认采样和价格字段。配置引用错误在发布前被准确拦截，发布后业务应用可以使用 Alias 完成同步和流式调用。

## 6.2 路由与容量验收

同一 Alias 配置多个优先级和权重后，运行结果符合优先级内权重选择规则。多 Credential 按池策略选择。RPM、TPM 和并发分别达到上限时能够拒绝、排队或选择其他候选；所有结束路径正确归还并发，Token 预占与结算可核对。

## 6.3 可靠性验收

可重试网络错误、连接超时和可重试 5xx 按次数与退避执行；Provider 429 优先切换 Credential 或候选，没有替代路径且等待时间在策略预算内时允许延迟重试。参数、鉴权和内容拒绝不执行 RETRY。当前候选失败后可以 Fallback。达到失败窗口阈值后熔断 OPEN，等待期后 HALF_OPEN 探测并按结果恢复或重新打开。流式首段输出后发生中断时不切换模型。

## 6.4 观测与安全验收

每次模型调用生成 Trace，每次实际外部调用生成 Attempt。Trace 详情可以说明候选、Credential 脱敏值、重试、Fallback、Token、费用、耗时和错误。Credential 原文、访问 Token、认证请求头和默认消息正文不出现在读取接口、页面、日志、Trace、审计与导出中。

## 6.5 交付形态验收

Java SDK 同时支持 Local Runtime 和 Standalone Client，提供线程安全的同步、异步、Java Flow 流式、背压、取消、统一异常和生命周期管理。Spring Boot Starter 按 EMBEDDED 与 STANDALONE_CLIENT 条件装配，配置属性具有完整元数据，宿主 Bean 可以覆盖默认实现，Embedded Admin UI 受宿主认证或显式本地访问规则保护。Standalone Server 提供模型目录、同步与 SSE 调用、管理与内部实例入口、存活与就绪检查、集群共享状态和优雅关闭。Provider SPI 的配置校验、模型列表、Token 估算、同步、流式、取消和错误分类通过统一契约测试。

## 6.6 关键验收场景

| 场景 | 前置条件 | 操作 | 预期结果 |
|---|---|---|---|
| 概览指标一致性 | 查询范围内同时存在成功、失败、流式中断、取消和运行中 Trace，并包含 Retry 与 Fallback | 使用同一 application、Alias、Provider 和时间范围查询运行摘要、Trace 列表及 Usage 汇总 | request_count 与 Trace total 一致；success_rate 排除 CANCELLED、RUNNING、QUEUED；Token 与分币种 Cost 和 Usage 一致；恢复动作按 RecoveryDecision 计数。 |
| 概览趋势与钻取 | 最近 24 小时存在多个时间桶和两种费用币种 | 切换请求量、成功率、Token、Cost 指标并点击一个时间桶 | 时间桶连续且无重复；Cost 分币种展示；钻取页面带入 bucket_start、bucket_end 和公共筛选，结果范围与数据点一致。 |
| 概览异常权限与定位 | 存在 OPEN 熔断、不可用候选、INVALID Credential 和失败 Trace，并准备开发人员与运维人员身份 | 两种身份分别查询异常区域并点击异常项 | 运维人员可见 Credential 脱敏信息并进入对应详情；开发人员不接收 Credential 项，只看到本应用 Trace 与授权 Alias；目标详情与异常对象一致。 |
| 首次接入模型 | Provider、模型和 Credential 均未配置 | 按模型接入主流程配置并发布 | 发布成功，Alias 可查询并完成调用。 |
| 模型上下文过滤 | 两个候选 context_window 不同 | 发送只满足较大上下文候选的请求 | 小上下文候选被过滤，请求由大上下文候选完成。 |
| 模型参数能力过滤 | 同一 Alias 的两个候选具有不同 temperature 范围，其中一个不支持 stop | 发送只落在一个候选 temperature 范围内且包含 stop 的请求 | 不兼容候选在调用 Provider 前被过滤；兼容候选收到解析后的参数；全部候选不兼容时返回 MODEL_CAPABILITY_NOT_SUPPORTED。 |
| 多密钥调度 | 一个池内有三个 HEALTHY Credential | 连续发起并发请求 | 按 selection_strategy 分配，且不超过各自并发上限。 |
| Provider 限流 | 首选 Credential 返回 429，池内存在其他 Credential 或备用候选 | 发起调用 | 优先执行 Credential Failover，再执行 Fallback；Provider 429 不增加熔断失败数，Trace 保存全部 Attempt 与 RecoveryDecision。 |
| 熔断恢复 | 候选错误率达到阈值 | 等待 OPEN 期并发起探测 | 状态按 CLOSED、OPEN、HALF_OPEN 规则迁移。 |
| 流式中断 | 客户端已收到内容块 | Provider 连接中断 | 返回流式 UnifiedError，状态 STREAM_INTERRUPTED，不拼接备用模型输出。 |
| 配置冲突 | 两名管理员编辑同一对象 | 后保存旧 version | 返回 CONFIG_VERSION_CONFLICT，不覆盖最新值。 |
| 发布失败 | 启用 Alias 没有可用候选 | 执行发布 | 返回对象与字段错误，当前运行快照保持不变。 |
| 密钥安全 | 创建并轮换 Credential | 查询详情、日志、Trace、审计和导出 | 所有位置均无法获取密钥原文。 |
| 用量复算 | 一次请求包含重试和 Fallback | 对比 Trace、Attempt 和 Usage | Trace 成本等于各 Attempt 成本之和，聚合数据可复算。 |
| Trace 精确定位 | 已存在本角色范围内 Trace，并准备一个无权限 Trace ID | 分别按两个 trace_id 查询 | 有权限 Trace 一次返回，越权 ID 返回空列表，不能推断其他应用数据。 |
| Trace 时间线 | 请求经历排队、失败、Credential Failover 和成功 | 打开 Trace 详情 | 时间线按队列、路由、Attempt、恢复决策和结束顺序展示，节点数量与源实体一致。 |
| 响应用量与总消耗 | 首次 Attempt 失败并产生估算 Token，Fallback 成功并返回实际 Usage | 查看 Usage/Cost 详情 | response_total_tokens 等于成功 Attempt，Trace.total_tokens 包含两次 Attempt，usage_source=MIXED。 |
| 路径费用归因 | Provider A 失败后 Provider B 成功 | Usage 按 Provider 分组 | Provider A request_count=0 且保留失败 Attempt 的 Token 与费用，Provider B 获得 request_count=1。 |
| 聚合幂等 | 同一 UsageAggregationEvent 被处理器重复取得 | 重放事件并查询 Usage | HOUR、DAY 聚合各只增加一次，事件最终为 SUCCEEDED。 |
| 多币种汇总 | 查询范围包含不同 currency 的多个 Alias | 不指定 currency 查看摘要和趋势 | 费用按币种分别返回和绘制，不产生跨币种总额。 |
| 诊断样本权限 | 已开启采样并产生 AVAILABLE 样本 | 运维人员与只读人员分别查看详情 | 运维人员查看脱敏截断内容并产生审计，只读人员无法取得 sampled_messages 和 client_ip。 |
| 导出安全与上限 | Trace 字段包含以等号开头的文本，并准备超过 100000 行结果 | 分别导出小范围和大范围 | 小范围 CSV 对公式字符转义且不含敏感字段；大范围返回 EXPORT_TOO_LARGE。 |
| 明细与聚合保留 | Trace 超过 trace_retention_days，Usage 尚在 usage_retention_days 内 | 查询 Trace 和 Usage | Trace 明细不可查询，Usage 汇总仍存在，页面停用对应 Trace 钻取。 |
| 草稿修订失效 | 管理员甲已完成校验，管理员乙随后保存另一项配置 | 管理员甲使用原 validation_id 发布 | 返回 CONFIG_DRAFT_CHANGED 或 CONFIG_VALIDATION_EXPIRED，不创建快照和发布记录。 |
| 单项撤销依赖 | 新建 Route Candidate 引用了同一草稿中新建的 Provider Model | 直接撤销该 Provider Model | DraftChange.revertable=false，返回 DRAFT_REVERT_BLOCKED，两个草稿对象均保持原值。 |
| 实例准备失败 | 两个 ONLINE 实例参与发布，其中一个实例无法解析目标快照 | 执行校验和发布 | 目标快照进入 ABORTED，PublishRecord=FAILED，活动快照、草稿内容和运行中请求保持不变。 |
| 配置原子激活 | 两个 ONLINE 实例均已对目标快照上报 READY | 发布服务提交激活并下发 InstanceActivationCommand | 数据库只存在一个 ACTIVE 快照；两个实例切换到相同 snapshot_no；旧 Trace 保持旧快照，新 Trace 使用新快照。 |
| 激活后实例收敛 | 目标快照已 ACTIVE，一个实例未在时限内上报 LOADED | 查看发布进度并等待该实例恢复心跳 | PublishRecord 先为 PARTIAL_FAILED；实例加载当前 ACTIVE 快照后结果转为 LOADED，记录最终转为 SUCCEEDED。 |
| 保留影响过期 | 管理员已取得保留期缩短的 impact_version，估算超过 10 分钟或目标参数改变 | 保存 RuntimeConfig 草稿 | 返回 RETENTION_IMPACT_EXPIRED，不写草稿和审计成功记录；重新估算后可以提交。 |
| 时区锁定 | 已存在 UsageAggregate，RuntimeConfig.timezone_locked=true | 尝试修改 timezone | 返回 CONFIG_FIELD_IMMUTABLE，不产生新的时间桶口径。 |
| 访问 Token 一次显示与轮换 | Standalone Mode 已创建访问凭证并安全保存初始 Token | 关闭创建弹窗后查询详情，再执行轮换并分别使用新旧 Token 调用 | 详情无法取得原文；轮换响应只显示一次新 Token；旧 Token 立即返回 ACCESS_TOKEN_INVALID，新 Token 可鉴权。 |
| 访问范围控制 | 访问凭证限定一个 Alias 和一个 IP 网段 | 分别使用允许与未允许的 Alias、来源 IP 调用 | 允许组合进入统一运行链路；越界 Alias 返回 ACCESS_DENIED，越界 IP 返回 ACCESS_IP_DENIED。 |
| 审计事务一致性 | 准备一项可成功的配置修改和一项会触发版本冲突的修改 | 依次提交两项操作 | 成功修改与 SUCCEEDED AuditLog 同事务提交；冲突修改回滚，并以相同 request_id 生成脱敏 FAILED AuditLog。 |
| 配置快照敏感值检查 | 草稿包含数据库密钥凭证、外部 Secret 引用和 Standalone Access Credential | 校验发布并检查快照、校验问题、实例错误和审计 | ConfigSnapshot 不含 secret_value、Token、运行状态与审计；secret_ref、错误和差异按敏感规则脱敏。 |
| 接入示例安全 | Standalone 已配置 Alias 和访问凭证 | 打开接入说明并复制 Maven、Spring、Java 与 cURL 示例 | Alias 与公开 base_url 正确；Token 和所有模型密钥均为占位符；页面、剪贴行为指标和前端日志无真实 Secret。 |
| 模型目录访问范围 | 两个 Access Credential 分别允许不同 Alias | 分别调用 GET /v1/models | 每个响应只含其范围内已发布 Alias；临时容量耗尽不移除模型；接口不创建业务 Trace。 |
| Trace ID 冲突 | 已存在调用方指定 trace_id 的 Trace | 使用相同 trace_id 再次调用 | 返回 TRACE_ID_CONFLICT，不复用旧响应、不创建 Attempt、不产生新费用。 |
| 同步响应一致性 | Alias 经一次失败 Attempt 后 Fallback 成功 | 调用 stream=false 并对比响应与 Trace | choices 来自最终成功 Attempt；response Usage 对应最终 Attempt；light_ai.cost 等于全部可计费 Attempt 成本；X-Trace-Id 与响应 trace_id 一致。 |
| 流提交前恢复 | 首选 Provider 在首个内容块前连接失败，备用候选可用 | 发起 stream=true 调用 | 外部流只出现备用候选的一组连续块；Trace 保留失败 Attempt 与 Fallback；sequence 从 0 连续递增。 |
| 流提交后中断 | 客户端已经收到首个内容块 | Provider SSE 解析失败 | 不切换候选；发送 UnifiedError 后关闭连接；Trace=STREAM_INTERRUPTED；Java Publisher 调用 onError 且不调用 DONE、onComplete。 |
| Java 异步取消 | chatAsync 已创建 Trace 且 Provider 尚未结束 | 调用 CompletableFuture.cancel | 底层 CancellationSignal 触发，future 以 CancellationException 结束，Trace=CANCELLED，并发容量释放一次。 |
| Java 流式背压 | Subscriber 初始只 request(1)，Provider 连续产生多个块 | 分批增加 request 数并最终 cancel | 每次只收到已请求数量，事件顺序不变且不丢失；取消后停止事件并释放连接。 |
| Local Runtime 配置校验 | 本地运行定义存在无引用模型或 Alias 无候选 | 构建 LOCAL_RUNTIME 客户端 | 返回包含对象、字段和原因的配置校验结果，客户端未创建，不调用 Provider。 |
| Local Runtime 密钥边界 | 本地定义通过 credential_secret_suppliers 提供密钥 | 完成一次调用后检查定义、快照、日志、异常与 TraceExporter | 所有位置无密钥原文；secret handle 使用结束后清理。 |
| Starter 模式隔离 | 分别启动 EMBEDDED 与 STANDALONE_CLIENT 应用上下文 | 检查 Bean、存储和页面路由 | Embedded 创建 Runtime、仓库与可选 Admin；Standalone Client 只创建 HTTP Client 与健康组件，不连接业务数据库和 Redis。 |
| Starter Bean 覆盖 | 宿主声明自定义 LightAiClient 与 ProviderAdapter | 启动 EMBEDDED 应用 | 默认 LightAiClient 回退；自定义 Adapter 加入 Registry；重复 provider_type 阻止就绪并指出冲突 Bean。 |
| Embedded Admin 安全 | 未提供 AuthContextProvider，local-access-enabled=false | 从本机和外部地址访问 Admin 路径 | 两者均拒绝；显式开启后只有 loopback 或可信网段建立 LOCAL_ADMIN 上下文。 |
| Standalone 就绪与摘流 | 数据库、Redis、快照和 Adapter 初始正常 | 分别中断 Redis、恢复后发送正常终止信号 | Redis 必需时 readiness=DOWN；恢复后 UP；终止时先 DRAINING 和拒绝新请求，再等待或取消存量调用。 |
| Standalone 集群容量 | 两个实例共享数据库和 Redis | 并发请求达到 Alias 全局上限 | 两实例合计不超过上限；任一实例停止后 Watchdog 回收遗留预占。 |
| Provider SPI 错误分类 | 自定义 Adapter 模拟 401、429、5xx、无效 SSE 和正常内容过滤 | 执行契约测试 | 分类分别符合 4.7.2.5；Adapter 不自行重试；Runtime 按 RecoveryDecision 执行；内容过滤作为正常 finish_reason。 |
| SecretProvider 选择与失效 | 两个 SecretProvider 同时匹配同一引用，并准备一项可轮换引用 | 分别解析冲突引用和轮换正常引用 | 冲突返回 SECRET_PROVIDER_CONFLICT 且不读取密钥；正常轮换调用 invalidate，后续请求不再使用旧缓存。 |
| TraceExporter 失败隔离 | TraceExporter 连续返回可重试失败 | 完成模型调用并等待三次导出重试 | 业务响应与内部 Trace 成功完成；批次 batch_id 保持不变；最终输出 exporter_failure 指标，日志无正文与密钥。 |
