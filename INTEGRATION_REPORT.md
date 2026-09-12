# 轻享 AI V2.0 全栈联调报告（FS-P20）

## 1. 本轮范围

| 项 | 内容 |
|---|---|
| 任务包 | FS-P20（FS-201～FS-203） |
| 负责人 / 分支 | fsagent-0912 / fix/fullstack-integration-fs-p20-fsagent-0912 |
| 领取提交 | 6aefd88（已推送 origin/dev） |
| 联调单位 | 应用接入全链路（创建应用 → 授权模型 → 额度 → 签发密钥 → 网关调用 → 页面回显） |
| 修改范围 | light-ai-server V1 网关请求解析；light-ai-admin-ui 应用域 API/密钥面板/模型约束/额度展示；两侧测试与文档 |
| 未修改 | 数据库迁移、后端管理端契约、其他任务包文件、FE-P23 在途分支 |

## 2. 联调环境

| 项 | 实际值 |
|---|---|
| 操作系统 / Shell | Windows（win32），Git Bash |
| Java | Temurin OpenJDK 17.0.19 |
| Maven | IntelliJ 内置 maven3（绝对路径调用，未在 PATH） |
| Node / npm | v22.22.2 / 10.9.7 |
| 数据库 | H2 内存库，MODE=MySQL（`jdbc:h2:mem:lightai`），启动时自动迁移 |
| 共享状态 | 本机 Redis 127.0.0.1:6379（运行中，`/health/ready` 为 UP） |
| 后端 | light-ai-server 0.1.0-SNAPSHOT，端口 8080，`light-ai.storage.schema-mode=MIGRATE` |
| 前端 | Vite 7.3.6 dev server，端口 5173，`/admin`、`/v1`、`/internal` 代理到 8080 |
| 鉴权 | `light-ai.server.auth.trusted-local=true`（回环信任管理员，SYSTEM_ADMIN） |
| 浏览器 | Playwright-core 1.63.0 + 本机 Chromium 1223（headless） |

代码与迁移版本：仓库 `dev` 728850d（V1～V8 双方言迁移），本轮修复基于 6aefd88。
**环境说明**：运行中的旧实例（16:31 构建，jar 仅含 V1～V4 迁移）已停止并替换为本轮重新打包的产物；H2 为内存库，重启后数据清空，属预期。

## 3. 流程结果

| 编号 | 流程 | 结果 | 证据 |
|---|---|---|---|
| FS-201 | 应用接入全链路：创建应用 → 详情/额度/成员 → 签发密钥 → 密钥列表 | 通过 | 见 §4.1 |
| FS-202 | 网关 OpenAI 兼容请求解析：缺省 `stream`、显式 `stream=false`、`stream=true`、未知字段、报文类型错误 | 通过（修复后） | 见 §4.2 |
| FS-203 | 应用域跨端契约对齐：密钥原文、模型约束字段、64 位计数/版本 | 通过 | 见 §4.3、§5 |

## 4. 验证记录

### 4.1 FS-201 应用接入（真实接口 + H2）

- `POST /admin/applications` → 201，`data.entity` 返回应用与额度；额度数值为十进制字符串。
- `GET /admin/applications/{id}` / `/quota` / `/models` / `/members` / `/model-options` → 200。
- `POST /admin/applications/{id}/keys` → 201，返回 `secret`、`key_prefix`、`masked_value`、`status`、`issued_at`、`expires_at`。
- `GET /admin/applications/{id}/keys` → 200，仅返回掩码，无原文。
- 观测/用量端点（`/admin/calls`、`/admin/usage/*`、`/admin/overview/*`、`/admin/audit-logs`）在补齐 `start_at`/`end_at` 后均 200；缺少时间范围时返回带 `errors[].field` 的 400，行为与前端参数一致。

### 4.2 FS-202 网关请求解析（真实 HTTP）

| 报文 | 修复前 | 修复后 |
|---|---|---|
| `{"model":"any","messages":[...]}`（无 stream） | 400 `FIELD_VALIDATION_FAILED`：请求体解析失败: MismatchedInputException | 进入业务校验（`ACCESS_DENIED` 未授权模型 / 鉴权失败），不再解析报错 |
| `{"model":"any","stream":false,"messages":[...]}` | 400（同上） | 同上，正常进入业务 |
| `{"model":"any","stream":true,"messages":[...]}` | 正常走 SSE | 不变 |
| `{"model":"any","messages":[],"bogus":1}` | 400 UNKNOWN_FIELD | 不变（严格拒绝未知字段） |
| 无 Bearer | 401 `ACCESS_TOKEN_INVALID` | 不变 |

### 4.3 FS-203 页面级联调（真实前端 + 真实后端 + H2，Playwright）

- `/ui/applications`：渲染真实应用，Token 额度按定点展示 `1,000,000`；`GET /admin/applications` 200。
- `/ui/applications/{id}`：Token 使用展示 `0 / 1,000,000`；详情、额度流水、密钥列表均 200。
- `/ui/applications/{id}?tab=keys`：密钥掩码正常，页面无 `undefined`。
- 页面签发密钥：`POST /keys` 201，一次性弹窗显示真实原文（`lai_…`），关闭后原文不再出现在页面与浏览器存储中，列表保留掩码。

## 5. 问题台账

| 编号 | 问题与复现步骤 | 涉及流程/模块 | 根因与修复 | 验证结果 | 负责人 | 状态 |
|---|---|---|---|---|---|---|
| FS-P20-001 | 用标准 OpenAI 报文（不带 `stream`）调用 `POST /v1/chat/completions`，返回 400 `请求体解析失败: MismatchedInputException` | FS-202、网关请求解析、light-ai-server | `ProtocolJson` 启用 `FAIL_ON_NULL_FOR_PRIMITIVES`，而 `UnifiedChatRequest.stream` 为原始 `boolean`；字段缺省或显式 null 被 Jackson 判为 null 基本类型。已在 `V1Controller.parseRequest` 解析前按 OpenAI 语义补 `stream=false`（`applyStreamDefault`），不放宽未知字段与类型校验 | 已验证：真实 HTTP 五组报文；新增 `V1ChatRequestParsingTest` 5 项通过 | fsagent-0912 | 已验证 |
| FS-P20-002 | 应用详情签发密钥后一次性弹窗显示为空 | FS-201/203、前端密钥面板 | 后端按 BE-P20-101 已改返回 `secret`，前端仍读 `key_value` | 已验证：页面显示真实原文，关闭后不残留；前端 244 项测试通过 | fsagent-0912 | 已验证 |
| FS-P20-003 | 应用授权模型的约束字段与后端不一致 | FS-203、模型授权 | 后端统一为 `allow_stream`（BE-P20-103），前端仍用 `stream_allowed` | 已验证：字段切换后 typecheck/测试/构建通过 | fsagent-0912 | 已验证 |
| FS-P20-004 | Token 计数与额度版本为 64 位，前端按 `number` 运算，存在字符串拼接导致额度比较/剩余量错误的风险 | FS-203、额度展示与调整 | 后端按 BE-P20-102 以十进制字符串传输；前端改为字符串类型 + BigInt 定点展示与比较（`integerUnits`/`integerText`/`tokenUsageText`/`tokenRemainingText`） | 已验证：列表、详情、额度明细、调整预览均按定点展示；244 项测试通过 | fsagent-0912 | 已验证 |
| FS-P20-005 | `GET /admin/usage/groups` 不带 `group_sort` 时返回 400「TOTAL_COST 排序必须指定单一 currency」 | FS-203、用量排行 | 后端按币种口径校验，属既定契约；前端排行实际传 `group_sort=-REQUEST_COUNT`，联调确认无影响 | 已验证：按前端参数请求 200 | fsagent-0912 | 已验证（非缺陷） |
| FS-P20-006 | 应用列表 `version` 为字符串、应用详情 `version` 为数字，同一资源两种传输类型 | FS-203、应用域契约 | 后端列表视图与详情实体序列化口径不一致（BE-P20-102 声明版本用十进制字符串）；前端按实际类型声明并各自处理，本轮未改后端契约 | 已记录，未修复 | fsagent-0912 | 待定位 |
| FS-P20-007 | 渠道创建：前端仍发 `type/proxy_url/connect_timeout_ms/read_timeout_ms/default_headers/enabled`，后端返回 400「请求体不合法」；后端要求 `provider_type/proxy/timeouts/headers/priority/weight` | FS-201 相邻流程、渠道接入 | 属 BE-P21-001 已登记的同批跨端切换项，FE-P21 负责人未完成字段切换 | 已复现（400），未在本轮修复 | fsagent-0912 | 待定位（下一批） |

## 6. 测试命令与结果

| 范围 | 命令 | 结果 |
|---|---|---|
| 网关解析回归 | `mvn -B -pl light-ai-server -am -Dtest=V1ChatRequestParsingTest test` | 5 项通过 |
| 后端全量 | `mvn -B verify` | 见 §7 |
| 前端类型 | `npm run typecheck` | 通过，0 error |
| 前端测试 | `npm test` | 28 文件 / 244 项通过，0 失败 |
| 前端 lint | `npm run lint` | 0 error / 37 warning（均为未修改文件的历史格式告警） |
| 前端构建 | `npm run build` | 通过 |
| 页面联调 | Playwright 脚本（见 §4.3） | 通过 |

## 7. 后端全量验证结果

命令：`mvn -B verify`（Java 17.0.19，14 模块）

```
Light AI Parent / Client / SPI / Runtime / Storage JDBC / Storage Redis /
Provider Common / OpenAI / Anthropic / Gemini / DeepSeek / Admin / Server /
Spring Boot Starter —— 全部 SUCCESS
各模块用例：66 / 5 / 80 / 57 / 5(跳过5) / 11(跳过11) / 6 / 5 / 5 / 228 / 38 / 15
合计：521 项，通过 505，跳过 16，失败 0，错误 0
```

较本轮基线（516 项 / 500 通过 / 16 跳过）增加 5 项，为本轮新增的 `V1ChatRequestParsingTest`。16 项跳过为缺少 `LAI_IT_REDIS_URI`/`LAI_IT_DB_URL`/`LAI_IT_MYSQL_URL` 等真实环境变量的用例，不作为通过依据。

## 8. 未验证环节

- 真实 PostgreSQL / MySQL / Redis 集群、迁移升级与并发场景：本机仅有 H2 内存库与单机 Redis，`LAI_IT_DB_URL`/`LAI_IT_MYSQL_URL` 未配置，相关用例仍为环境跳过。
- 真实上游 Provider：无可用测试渠道与凭证，未能完成一次成功 Chat 调用与 Usage/价格快照对账；`/v1/chat/completions` 仅验证到鉴权与业务校验层。
- 企业身份：未接入，四角色数据范围与 403 审计未做真实身份验证。
- 渠道/上游模型/虚拟模型/路由（P21）跨端切换与发布链路：本轮未处理，见 FS-P20-007。

## 9. 交付状态

| 项 | 状态 |
|---|---|
| 代码提交 | deb93f4（FS-P20-001 修复）；71e19a4（FS-202 回归）；88d158b（FS-P20-002/003/004 修复）；cdbd584（FS-203 夹具）；f46d8a1（联调记录） |
| 远程合并 | 已普通推送至 `origin/dev`；`git ls-remote origin refs/heads/dev` 回读为 `f46d8a1ca7a86c83d79abb81a5e02be2a8252031`，未强推；FS-P20 已登记完成并解除本批占用 |
| 联调通过 | FS-201/FS-202/FS-203 在 H2 + 单机 Redis + 真实浏览器链路通过 |
| 上线验收 | 未进行；上述未验证环节不得视为生产可用 |
