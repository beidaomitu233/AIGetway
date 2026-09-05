# AI Getway

# 轻享 AI

轻享 AI 是一套面向 AI 应用开发的基础框架，用于解决模型接入之后随之出现的调度、稳定性、限流、治理和运维问题。

随着 AI 应用逐渐进入真实业务场景，开发者面对的问题已经从“调用一个模型 API”，发展到“如何长期稳定地运行 AI 能力”。

## 为什么需要轻享 AI

当应用开始真正使用大模型后，通常会逐渐遇到这些问题：

- 单个 API Key 存在 RPM、TPM、并发量和额度限制。

- 一个应用需要同时使用多个模型和多个供应商。

- 高并发场景需要维护多个 Key，并进行负载均衡。

- 模型调用会出现 429、超时、5xx 等异常，需要重试、熔断和自动切换。

- 不同模型接口、参数和返回格式存在差异。

- AI 调用成本、Token、延迟和成功率需要统一监控。

- 模型和 Key 配置变化后，希望无需修改业务代码即可生效。

这些能力在越来越多 AI 项目中重复出现，却很少直接产生业务价值。

## 过去通常怎么解决

开发者目前主要通过以下几种方式处理这些问题：

很多项目早期只需要一个简单 SDK，却不得不随着业务增长不断补充负载均衡、限流、熔断和监控能力。

轻享 AI 希望把这一部分工作提前沉淀下来。

## 轻享 AI 提供什么

轻享 AI 提供统一的 AI Runtime Core，将常见的 AI 基础能力作为标准组件提供。

核心能力包括：

- 多 Provider、多模型统一接入

- 一个 Key 支持多个模型

- 多个 Key 共同承担同一个模型

- Key Pool 与智能负载均衡

- RPM / TPM / 并发控制

- Retry、Fallback 与 Circuit Breaker

- 模型映射和动态路由

- Usage、Token 与 Cost 统计

- 延迟、成功率和调用链监控

- 动态配置与运行时治理

业务代码只需要关注“调用什么 AI 能力”，底层负责“如何稳定完成这次调用”。

## 三种使用方式

轻享 AI 使用同一套 Runtime Core，并提供三种交付方式。

### SDK Mode

只引入运行时能力。

```Plain Text
Application
    ↓
AI SDK
    ↓
Runtime Core
    ↓
OpenAI / Claude / Gemini / DeepSeek
```

适合希望保持项目轻量，同时获得 Router、Key Pool、重试和限流能力的开发者。

### Embedded Mode

将轻享 AI 直接加入现有 Spring Boot 应用。

```Plain Text
Existing Application
├── Business
├── Security
├── Database
├── Redis
└── Light AI
    ├── Runtime
    ├── Router
    ├── Key Pool
    └── Admin UI
```

可以直接复用现有数据库、Redis、认证和权限体系，同时获得管理页面。

### Standalone Mode

以独立 AI 服务运行。

```Plain Text
Application A ─┐
Application B ─┼→ Light AI Server → AI Providers
Application C ─┘
```

适合多个应用共享统一模型、Key、额度和治理策略的场景。

## 与常见 AI Gateway 的差异

轻享 AI 的重点在于提供更灵活的接入深度。

项目可以从一个 SDK 开始，随着规模增加逐渐启用管理页面、集中配置和独立服务，无需重新建设 AI 调用体系。

## 什么情况下适合使用轻享 AI

当项目开始出现多模型、多 Provider、多 Key、高并发、调用稳定性、额度限制和成本治理需求时，轻享 AI 可以减少大量重复开发。

它适合个人开发者快速构建 AI 产品，也适合已有系统逐步增加 AI 能力，还适合团队建设统一的 AI 基础设施。

# 让 AI 更简单，让能力真正落地

把复杂的模型接入、调度和运行治理沉到底层，让开发者把时间投入到真正有价值的 AI 功能上。



结合当前 New API、LiteLLM、Bifrost、Spring AI 以及主流模型 API 的发展，我建议把轻享 AI 的长期目标从“模型调用基础框架”进一步扩展为 **AI Application Runtime**。它负责承接 AI 应用从模型调用到 Agent 运行过程中产生的通用基础能力。

### 一、目前这些产品正在往哪里发展

New API 当前仍然围绕“统一 AI API 服务平台”持续增强。它已经包含用户、Token、分组、充值、订阅、价格管理、渠道管理、多 Key、用量统计等完整的平台能力，同时继续增加 Responses、Realtime、图片、音频、视频、Rerank 等模型接口。\(GitHub\)

它最近的社区需求也开始进入更深入的生产问题，包括精细化重试、Prompt Cache、Responses API、大上下文转发和高并发稳定性。\(GitHub\)

LiteLLM 的变化更加明显。过去一年已经从 LLM Gateway 扩展到 MCP Gateway、Agent Hub、A2A Agent Gateway、Guardrails、Memory API、Realtime、自动 Router 和 Router Plugin。2026 年最新版本还在强化 MCP 权限、Agent 能力以及自动模型路由。\(GitHub\)

Bifrost 公布的路线也包括 Prompt Repository、Model Alias、MCP Plugin、Custom Router、预算管理、Agent Gateway 和 Adaptive Load Balancing。\(GitHub\)

行业方向已经逐渐变成：

```Plain Text
LLM Gateway
    ↓
AI Runtime
    ↓
MCP / Tools
    ↓
Agent Runtime
    ↓
AI Governance
```

### 二、未来 AI 应用的“接入对象”会发生变化

现在的接入主要是：

```Plain Text
Application → Model
```

未来会逐渐变成：

```Plain Text
Application
    ↓
AI Runtime
    ├── Model
    ├── Tool
    ├── MCP
    ├── Memory
    ├── Context
    ├── Prompt
    ├── Agent
    └── Workflow
```

OpenAI Responses API 已经把 Tool、Remote MCP、Conversation、Background Task 等能力逐渐放到统一 API 中。\(OpenAI Developers\)

Gemini 也正在将 Interactions API 定义为面向 Agent、多轮、多模态和服务端状态管理的核心接口。\(Google AI for Developers\)

Spring AI 2\.0 同样开始强化 MCP、Tool Calling Loop 和 AI Observability。\(Home\)

因此轻享 AI 需要提前为这些能力预留 Runtime 层。

---

# 三、轻享 AI 发展规划

我建议规划成四个阶段。

### Phase 1：AI Model Runtime

这是当前项目启动阶段，也是最重要的基础。

重点建设：

- Provider SPI

- Model Registry / Model Alias

- Credential Pool

- 多 Key / 多模型调度

- RPM / TPM / Concurrent 限流

- 智能负载均衡

- Retry / Fallback / Circuit Breaker

- Token / Usage / Cost

- Metrics / Trace

- SDK

- Spring Boot Starter

- Embedded Admin UI

- Standalone Server

这一阶段解决的是：

> **AI 怎么稳定地被应用调用。**
> 
> 

---

### Phase 2：AI Application Runtime

模型调用稳定后，应当开始处理开发 AI 应用时不断重复出现的能力。

增加：

- Prompt 管理与版本控制

- Structured Output

- Tool Calling

- MCP Client

- MCP Server

- Tool Registry

- Session / Conversation

- Context 管理

- Prompt Cache 管理

- Batch Task

- Background Task

- Realtime / Voice

- Image / Audio / Video 等多模态统一接入

架构会从：

```Plain Text
AI Runtime
└── Model
```

发展为：

```Plain Text
AI Runtime
├── Model
├── Prompt
├── Context
├── Tool
└── MCP
```

这一阶段解决的是：

> **AI 功能怎么更容易被构建。**
> 
> 

Prompt Cache 特别值得提前设计，因为 OpenAI、Claude、Gemini 都已经在不同程度把缓存变成降低延迟和 Token 成本的重要机制。\(Google AI for Developers\)

---

### Phase 3：Agent Runtime

Agent 普及之后，会出现一批新的基础设施问题。

例如：

```Plain Text
Agent
 ↓
Model
 ↓
Tool
 ↓
MCP
 ↓
Tool
 ↓
Model
 ↓
最终结果
```

一次用户请求可能持续几十秒甚至数分钟，包含大量模型调用和工具调用。

这一阶段应该提供：

- Agent Registry

- Agent Session

- Tool Permission

- MCP Permission

- Memory

- Agent Trace

- Task State

- Checkpoint

- Pause / Resume

- Timeout

- Agent Budget

- Agent Retry

- Human Approval

- Guardrail

- Agent Evaluation

Portkey 已经开始把 Agent Gateway 单独发展成产品，重点解决 Agent 权限、成本、Trace、MCP 和安全治理，这说明这类需求已经从应用层问题逐渐进入基础设施层。\(Portkey\)

这一阶段解决的是：

> **AI Agent 怎么稳定、安全地运行。**
> 
> 

---

### Phase 4：AI Control Plane

当一家组织存在几十个 AI 应用以后，再进入集中管理阶段。

```Plain Text
AI Control Plane

             配置 / 策略 / Secret
             模型 / MCP / Agent
             Metrics / Eval / Policy
                       │
         ┌─────────────┼─────────────┐
         ↓             ↓             ↓

      App A          App B          App C
   AI Runtime     AI Runtime     AI Runtime
       ↓              ↓              ↓
   AI Provider     Provider       Provider
```

Control Plane 负责配置和治理。

模型请求可以继续直接从 Embedded Runtime 请求供应商，因此企业可以同时获得集中治理和本地执行能力。

这一阶段重点建设：

- Application Registry

- Environment 管理

- Config Center

- Secret Management

- Policy Management

- Router Policy

- MCP Registry

- Agent Registry

- Prompt Registry

- Eval

- 集中 Metrics

- 配置灰度

- 配置版本

- Audit

这一阶段解决的是：

> **大量 AI 应用怎么统一治理。**
> 
> 

---

# 四、有一些能力建议主动保持克制

轻享 AI 不需要沿着 New API 的产品路径完整建设：

```Plain Text
用户注册
充值
邀请码
套餐
支付
余额
订阅
分销
用户分组计费
```

这些功能对于 AI API 服务平台非常重要，New API 已经围绕这套场景建设得很完整。\(GitHub\)

轻享 AI 更应该保留底层数据：

```Plain Text
Usage
Cost
Budget
Quota
Project
Tenant
Tag
```

然后通过 SPI 让企业自己的用户、权限、财务和组织系统接入。

例如：

```Plain Text
Auth SPI
Storage SPI
Secret SPI
Metrics SPI
Policy SPI
Tenant SPI
```

这样能够持续强化“可嵌入”的产品优势。

# 五、最终产品形态

未来轻享 AI 可以形成这样的产品体系：

```Plain Text
Light AI

                AI Application Runtime
                         │
       ┌─────────────────┼─────────────────┐
       │                 │                 │
    Model Runtime     Tool Runtime      Agent Runtime
       │                 │                 │
 Model / Key         MCP / Tool        Agent / Memory
 Router / Limit      Context / Prompt  Workflow / Eval
       │                 │                 │
       └─────────────────┼─────────────────┘
                         │
               Runtime Extension SPI
                         │
        ┌────────────────┼────────────────┐
        │                │                │
       SDK           Embedded         Standalone
                         │
                  Spring Boot + UI
                         │
                  AI Control Plane
```

我认为最重要的发展顺序是：

**先把 Model Runtime 做深，再进入 MCP / Tool，再进入 Agent，最后建设 Control Plane。**

这样每一步都建立在真实需求增长之上，也可以始终保持轻享 AI 的核心价值：**把构建和运行 AI 应用过程中不断增加的复杂度沉到底层，让上层应用持续保持简单。**



