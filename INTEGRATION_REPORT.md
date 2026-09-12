# 轻享 AI V2.0 全栈联调报告（FS-P20）

> §1～§9 为首批 FS-201～FS-203 记录；§10 为同日追加批（FS-P20-006/007/008，渠道 V2 接入与应用版本契约）；§11 为同日 FS-P21 批（上游模型 / 虚拟模型 / 路由跨端联调，全栈联调 fsagent-0912）。

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
| FS-P20-006 | 应用列表 `version` 为字符串、应用详情 `version` 为数字，同一资源两种传输类型 | FS-203、应用域契约 | 根因：`ApplicationListItem.version` 为 `String` 且列表服务显式转字符串；`ApplicationDetail.version` 为 `long` 并直接序列化数值。按 BE-P20-102「版本以十进制字符串传输」统一为字符串（`@JsonSerialize(using = ToStringSerializer.class)`），前端 `applications.ts` 同步按 `string` 声明 | 已验证：真实链路 `GET /admin/applications` 与 `GET /admin/applications/{id}` 的 `version` 均为 JSON 字符串且文本相等；签名写操作回传字符串版本成功；见 §10.2 | fsagent-0912 | 已验证 |
| FS-P20-007 | 渠道创建：前端仍发 `type/proxy_url/connect_timeout_ms/read_timeout_ms/default_headers/enabled`，后端返回 400「请求体不合法」；后端要求 `provider_type/proxy/timeouts/headers/priority/weight` | FS-201 相邻流程、渠道接入 | 根因：前端 `providers.ts` 与三个渠道页面仍消费旧 V1 字段，后端已按 BE-211/BE-P21-001 收口 V2 DTO。已按 BE-211 字段清单切换 API 类型、表单（`stream_idle_ms`/`priority`/`weight`）、移除 `enabled` 复选框、启停改走独立命令，并同步测试夹具 | 已验证：V2 载荷 `POST /admin/channels` 200（修复前同载荷 400）；创建→列表→详情→编辑→停用→启用→删除全链路通过；页面渲染 V2 字段；见 §10.1 | fsagent-0912 | 已验证 |
| FS-P20-008 | 渠道详情页/编辑表单的「编辑」「停用」「启用」「删除」全部失败，返回 400「编辑操作必须提交正整数 version」 | 渠道接入、详情页写操作 | 根因：`ChannelDetail` 未包含 `version`（BACKEND_PLAN BE-211 字段清单含 `version`），详情接口不回传配置版本；前端 `applyDetail` 读到 `undefined` → `version.value = undefined` → 提交体缺 `version` → `ChannelService.requireVersion` 抛 `FIELD_VALIDATION_FAILED`。已在 `ChannelDetail` 补 `long version`，`ChannelService.toDetail` 传入 `record.version()`；并在 `ResourceApiContractTest` 补断言（原测试未断言 `version`，是漏检原因） | 已验证：真实链路详情回传 `version` 且与列表一致；携带 `version` 的 PUT/启用/停用/删除全部 200；缺失 `version` 的 PUT 复现 400；陈旧版本返回 409 `CONFIG_VERSION_CONFLICT`；见 §10.1、§10.3 | fsagent-0912 | 已验证 |

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
- 渠道**检测（`POST /admin/channels/{id}/check`）**与真实上游 Provider 连通：无可用测试渠道凭证，仅覆盖到命令校验层。
- 上游模型 / 虚拟模型 / 路由（P21 其余部分）与草稿发布链路：本轮只覆盖渠道实体，未覆盖 `upstream_model`/`virtual_model`/`route` 的发布与生效。
- 渠道页面的**点击级**写操作（在浏览器中实际点击「停用/编辑」提交）：本轮以 DOM 渲染 + API 全链路证据覆盖字段与请求形态，未做真实点击回放；浏览器点击级联调保留为待验证。

## 9. 交付状态

| 项 | 状态 |
|---|---|
| 代码提交 | 首批 deb93f4（FS-P20-001 修复）；71e19a4（FS-202 回归）；88d158b（FS-P20-002/003/004 修复）；cdbd584（FS-203 夹具）；f46d8a1（联调记录）。追加批 39468a7（FS-P20-006/007 版本类型与渠道 V2 切换）；91e2d7b（FS-P20-008 详情 `version` 修复）；286cf73（FS-P20-008 契约断言） |
| 远程合并 | 首批已普通推送至 `origin/dev`（回读 `f46d8a1`）；追加批见 §10.4 |
| 联调通过 | FS-201/FS-202/FS-203 在 H2 + 单机 Redis + 真实浏览器链路通过；追加批渠道全链路与应用版本契约在 H2 + 单机 Redis + 真实页面渲染链路通过 |
| 上线验收 | 未进行；上述未验证环节不得视为生产可用 |

## 10. FS-P20 追加批次（2026-09-12，全栈联调 fsagent-0912）

### 10.1 范围与结果

针对首批遗留的 FS-P20-006/007（`TASK_STATUS.md` 记为待定位）与本批新定位的 FS-P20-008，重新在真实链路上复现、修复并复验。

| 编号 | 流程 | 结果 |
|---|---|---|
| FS-P20-007 | 渠道接入全链路：创建 → 列表 → 详情 → 编辑 → 停用 → 启用 → 删除 → 删除后读取 | 通过 |
| FS-P20-006 | 应用域版本传输：创建 → 列表/详情版本类型一致 → 带版本编辑 → 签发密钥 → 额度读取 | 通过 |
| FS-P20-008 | 渠道详情回传配置版本（修复前详情页所有写操作 400） | 通过 |

### 10.2 联调环境（追加批）

| 项 | 实际值 |
|---|---|
| Java / Maven | Temurin OpenJDK 17.0.19 / Maven 3.9.11（`.m2\wrapper` 内绝对路径调用） |
| 数据库 | H2 内存库 `jdbc:h2:mem:lightai`，`MODE=MySQL`，`schema-mode=MIGRATE`，启动时应用 V1～V8 双方言迁移 |
| 共享状态 | 本机 Redis 127.0.0.1:6379（PID 30908，运行中） |
| 后端 | `light-ai-server-0.1.0-SNAPSHOT.jar`，**实际监听 18080**（见下方环境说明），`light-ai.server.auth.trusted-local=true` |
| 前端 | Vite 7.3.6 dev server，127.0.0.1:5173，`VITE_BACKEND_TARGET=http://127.0.0.1:18080`，**未启用 Mock**（未设置 `VITE_USE_MOCK`，未使用 `--mode mock`） |
| 浏览器 | `chromium_headless_shell-1223`（本机 ms-playwright），`--headless --dump-dom --virtual-time-budget=12000` |

**环境说明（非产品缺陷）**：`light-ai-server/src/main/resources/application.properties` 声明 `server.port=8080`，`start-server.bat` 亦标注 8080；但在本机沙箱环境下直接以默认配置启动时 Tomcat 实际绑定 **8800**（8800 被宿主应用占用，导致启动失败）。因此本批显式以 `--server.port=18080` 启动，并将 Vite 代理指向 18080。这是沙箱端口注入导致的环境差异，仓库配置本身正确；换机复现时请确认实际监听端口并在 `VITE_BACKEND_TARGET` 中显式指定。

### 10.3 渠道链路验证记录（真实 HTTP + H2 + Redis）

统一以 `curl` 直连 `http://127.0.0.1:18080`：

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1 | `POST /admin/channels`（V2：`provider_type`/`base_url`/`proxy`/`timeouts{connect_ms,read_ms,stream_idle_ms}`/`headers`/`priority`/`weight`） | 200，`data.entity.version=1`，`status=ACTIVE`，`health=UNKNOWN`，`draft_changed=true` |
| 1b | 同上但 `proxy=http://127.0.0.1:7890` | 400 `FIELD_VALIDATION_FAILED` / `proxy FORBIDDEN_TARGET` —— SSRF 防护按预期拒绝回环目标 |
| 2 | `GET /admin/channels` | 200，列表项含 `version`/`status`/`health`，无旧字段 `enabled`/`connection_status` |
| 3 | `GET /admin/channels/{id}` | 200，详情含 `version`，且 `version` 与列表项**相等** |
| 4 | `PUT /admin/channels/{id}` **不带** `version` | 400 `FIELD_VALIDATION_FAILED` / `version REQUIRED`「编辑操作必须提交正整数 version」（FS-P20-008 的缺陷机制复现） |
| 5 | `PUT /admin/channels/{id}` 带 `version=1` | 200，`version` 递增为 2，`name`/`priority`/`weight`/`read_ms`/`headers` 均已更新 |
| 6 | `GET /admin/channels/{id}` | 200，编辑结果已持久化（`name=fs-int-channel-a-renamed`、`priority=35`、`weight=9`、`read_ms=120000`、`header=updated`） |
| 7 | `GET /admin/channels/{id}/impact?operation=DISABLE` | 200，返回 `impact_version`、`can_delete=true` |
| 8 | `POST /admin/channels/{id}/disable`（`version` + `confirmed_impact_version`） | 200，`status=DISABLED`，`version=4` |
| 9 | `GET /admin/channels/{id}` 与 `GET /admin/channels?status=DISABLED` | 200，详情与筛选结果均为 `DISABLED`，持久化一致 |
| 10 | 重复 `disable` 使用陈旧 `version` | 409 `CONFIG_VERSION_CONFLICT`，响应携带 `current_version=4`（乐观锁生效） |
| 11 | `POST /admin/channels/{id}/enable`（`version`） | 200，`status=ACTIVE`，`version=5` |
| 12 | `GET /admin/channels/{id}/impact?operation=DELETE` | 200，`can_delete=true` |
| 13 | `DELETE /admin/channels/{id}` | 200 |
| 14 | `GET /admin/channels/{id}` | 404 `OBJECT_NOT_FOUND` |
| 15 | `GET /admin/channels` | 200，`total=0` |

补充：`GET /admin/channels/{id}/impact?operation=disable`（小写）返回 400「operation 仅支持 DISABLE/DELETE」；前端 `providers.ts` 与 `useLifecycleActions` 实际传大写 `DISABLE`/`DELETE`，两侧口径一致，**非缺陷**，已核验后排除。

### 10.4 应用域版本契约验证记录（FS-P20-006）

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1 | `POST /admin/applications` | 201，`data.version="1"`、`data.entity.version="1"`，额度 `token_limit="1000000"`、`amount_limit="1000"`、`tokens_remaining="1000000"` 均为**十进制字符串** |
| 2 | `GET /admin/applications/{id}` | 200，`version` 为字符串 `"1"` |
| 3 | `GET /admin/applications` | 200，列表项 `version` 为字符串 `"1"`，与详情**文本相等** |
| 4 | `PUT /admin/applications/{id}` 携带字符串 `version` | 200，`version` 递增为 `"2"`，`name` 更新为 `FS Integration App Renamed` |
| 5 | `POST /admin/applications/{id}/keys` | 201，返回真实 `secret`（`lai_…`）、`key_prefix`、`masked_value`、`rotation_generation=1` |
| 6 | `GET /admin/applications/{id}/quota` | 200，全部 64 位数值为字符串 |
| 7 | `GET /admin/applications/{id}`（写后重读） | 200，`name=FS Integration App Renamed`、`version="2"`、`active_key_count=1`，与写入一致 |

备注：`ManagementOperationResult.version` 仍为 `long`（响应中为 JSON 数字），与详情/列表的字符串版本不同形态。经核对前端 `ApplicationDetailPage`/`ApplicationFormPage` 均只使用**详情接口**的 `version` 作为乐观锁令牌（`detail.version` / `latest.value.version`），未消费写结果中的 `version`，因此不构成功能缺陷；已在 COMMUNICATION.md 记为口径观察项。

### 10.5 页面级验证记录（真实 Vite 页面 → 真实后端 → H2）

| 页面 | 证据 |
|---|---|
| `/ui/channels` | 渲染真实渠道 `fs-ui-channel-b`、`DEEPSEEK`、`api.deepseek.com`、`未检测`、`启用`，操作列 `查看 编辑 检测 停用 删除`，`共 1 条` |
| `/ui/channels/{id}` | 渲染 `渠道 详情`，基础配置 `名称 fs-ui-channel-b`、`类型 DEEPSEEK`、`服务地址 https://api.deepseek.com/v1`、`代理地址 直连`、`连接超时 4000 ms`、`读取超时 90000 ms`、`流式空闲超时 20000 ms`、`优先级 / 权重 15 / 3`、`配置状态 启用`、**`版本 1`**、`默认请求头 X-Ui-Probe: page` |
| `/ui/applications` | 渲染 `FS Integration App Renamed`、`fs-int-app-01 · 生产`、`Probe Owner`、`Engineering`、`启用`、`0 / 1,000,000（0%）`、`0.00 / 1000.00 CNY`、`600 / 200,000` |

页面中「版本」一栏的存在，即 `ChannelDetail.version` 修复在 UI 上的直接体现；`1,000,000` 的定点渲染证明 64 位字符串在页面上按定点展示而非字符串拼接。

### 10.6 测试命令与结果（追加批）

| 范围 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `mvn -B clean verify`（14 模块） | BUILD SUCCESS，0 失败 / 0 错误，521 项中 505 通过、16 项环境跳过 |
| 渠道契约 | `ResourceApiContractTest` | 14 项通过（含新增 `version` 断言） |
| 应用契约 | `ApplicationApiContractTest` | 6 项通过（含详情/列表 `version` 文本一致断言） |
| 前端类型 | `npm run typecheck` | 通过，0 error |
| 前端测试 | `npm test` | 28 文件 / 244 项通过 |
| 前端构建 | `npm run build` | 通过 |
| 页面渲染 | chrome-headless-shell `--dump-dom` | 三个真实页面均渲染真实后端数据，见 §10.5 |

### 10.7 交付状态（追加批）

| 项 | 状态 |
|---|---|
| 代码提交 | 39468a7、91e2d7b、286cf73、62c0268（分支 `fix-fullstack-integration-fs20-followup-fsagent-0912`） |
| 远程合并 | 已普通推送：分支创建成功；`dev` 快进至 `62c0268`，`git ls-remote origin refs/heads/dev` 回读 `62c02680b90faa8f7e27dbecaf398a14a75e24e9`，未强推。推送前远程 `dev` 为 `181a68a`，无并行新提交需合并。本地 `dev` 已同步至同一提交 |
| 联调通过 | 渠道全链路、应用域版本契约在 H2 + 单机 Redis + 真实页面渲染链路通过；页面**点击级**写操作未回放，保留为待验证 |
| 上线验收 | 未进行 |

---

## 11. FS-P21 上游模型 / 虚拟模型 / 路由跨端联调（2026-09-12，全栈联调 fsagent-0912）

经用户授权，以全栈联调身份接管 `FE-P21`/`BE-P21` 名下登记为「阻塞」、且 `BACKEND_PLAN` BE-213/214/215 明确标注「本轮未覆盖」的上游模型 / 虚拟模型 / 路由跨端联调与复验。本条即 §10.7 与 §8 保留的未验证环节之一（「`upstream_model`/`virtual_model`/`route` 的草稿发布与生效」），用同一套「真实 jar + H2 + Redis + Vite + 无头浏览器」方法复现。

### 11.1 范围与结果

| 项 | 内容 |
|---|---|
| 任务包 | FS-P21（FS-211～FS-213） |
| 负责人 / 分支 | fsagent-0912 / `fix-fullstack-integration-fsp21-takeover-fsagent-0912` |
| 领取提交 | 5007b2c（已推送 `origin/dev`） |
| 基线 | `origin/dev` = 6242fd0（无并行新提交） |
| 联调单位 | 上游模型 → 虚拟模型 → 路由候选 的资源接入与回显链路（对应 FE-213/214/215 ↔ BE-213/214/215） |
| 修改范围 | light-ai-client `UpstreamModelDetail`/`ModelAliasDetail`；light-ai-admin `UpstreamModelService.toDetail`；light-ai-admin-ui `providerModels.ts`、`models/` 两页、`mocks/`、`tests/`；两侧契约测试与文档 |
| 未修改 | 数据库迁移、`FE-P21`/`BE-P21` 主任务勾选状态、其他任务包文件、主任务负责人占用 |

| 编号 | 流程 | 结果 |
|---|---|---|
| FS-P21-001 | 上游模型表单/导入页渠道下拉（渠道 V2 字段切换遗漏） | 通过（修复后） |
| FS-P21-002 | 上游模型列表/详情的「最近检测 / 错误码 / 候选数」回显 | 通过（修复后） |
| FS-P21-003 | 虚拟模型列表/详情的 24h 摘要回显 | 通过（修复后） |
| FS-P21-101 | 应用域同类 SNAKE_CASE 命名隐患 | 登记待确认，本轮未动 |

### 11.2 联调环境（本批）

| 项 | 实际值 |
|---|---|
| Java / Maven | Temurin OpenJDK 17.0.19 / Maven 3.9.11（`.m2\wrapper` 内绝对路径调用） |
| 数据库 | H2 内存库 `jdbc:h2:mem:lightai`，`MODE=MySQL`，`schema-mode=MIGRATE`，启动应用 V1～V8 双方言迁移（数据随 JVM 进程存活） |
| 共享状态 | 本机 Redis 127.0.0.1:6379（运行中） |
| 后端 | 本批重新打包的 `light-ai-server-0.1.0-SNAPSHOT.jar`，**实际监听 18080**，`light-ai.server.auth.trusted-local=true` |
| 前端 | Vite 7.3.6 dev server 127.0.0.1:5173，`VITE_BACKEND_TARGET=http://127.0.0.1:18080`，**未启用 Mock**（未设 `VITE_USE_MOCK`、未用 `--mode mock`） |
| 浏览器 | `chromium_headless_shell-1223`，`--headless --disable-gpu --virtual-time-budget=9000 --dump-dom` |

打包注意（环境记录，非产品缺陷）：旧 jar 被上一批仍在运行的 18080 进程占用，`spring-boot:repackage` 会因 `Unable to rename ... .jar.original` 失败；须先停止监听 18080 的进程再打包。

### 11.3 验证记录

**11.3.1 真实链路探针（`p21-probe3`，真实 jar@18080 + H2 + Redis + Vite@5173）**

| 步骤 | 请求 | 结果 |
|---|---|---|
| Q1 | `POST /admin/channels`（V2 载荷） | 200，创建 `fs21-ch<rand>` |
| Q2 | `POST /admin/upstream-models` | 200，绑定上一步渠道 |
| Q3 | `GET /admin/upstream-models` 与 `/{id}` | 200，`route_candidate_count=0`、`connection_status=UNKNOWN`；未检测时 `last_check_at`/`last_error_code` **不下发**（`@JsonInclude(NON_NULL)`） |
| Q4 | `POST /admin/virtual-models` | 200，创建 `fs21-alias<rand>` |
| Q5 | `GET /admin/virtual-models` 与 `/{id}` | 200，`request_count_24h` 存在（值 0），旧键 `request_count24h` **不存在** |
| Q6 | `POST /admin/virtual-models/{id}/routes`（引用上游模型 + 渠道） | 200，候选创建 |
| Q7 | `GET /admin/upstream-models` 与 `/{id}` 重读 | 200，`route_candidate_count` 由 0 → **1**，详情与列表一致 |
| Q8 | `GET /admin/channels` | 200，列表项含 `provider_type`/`status`，不含旧 `type`/`enabled` |

**11.3.2 页面级验证（真实 Vite 页面 → 真实后端 → H2）**

| 页面 | 证据 |
|---|---|
| `/ui/models`（上游模型列表） | 「候选」列渲染真实值 **1**；渠道列渲染真实渠道名 `fs21-ch784871`，无空白/`undefined` |
| `/ui/models/new`（新建模型） | 渠道下拉渲染 `fs21-ch784871（OPENAI）`，无 `（undefined）` |
| `/ui/aliases`（虚拟模型列表） | 「24h 调用」列渲染 **0**（修复前为空白） |

**11.3.3 契约测试（MockMvc + 真实 service + H2）**

`ResourceApiContractTest` 由 14 项增至 **16 项**，0 失败：

- `upstreamModelExposesRuntimeSnapshotAndReferenceCount`：未检测时 `connection_status=UNKNOWN`、`has("last_check_at")==false`、`has("last_error_code")==false`、`route_candidate_count=0`；直写 `object_runtime_state`（`MERGE INTO object_runtime_state (id, entity_type, entity_id, connection_status, last_checked_at, last_error_code, state_version, created_at, updated_at) KEY(entity_type, entity_id) VALUES (?, 'UPSTREAM_MODEL', ?, 'AVAILABLE', CURRENT_TIMESTAMP, 'UPSTREAM_TIMEOUT', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`）后断言 `AVAILABLE` 且 `last_check_at` 下发、`last_error_code=UPSTREAM_TIMEOUT`；建候选后详情与列表 `route_candidate_count=1`。
- `alias24hSummaryUsesUnderscoreSnakeCase`：详情与列表 `request_count_24h` 存在且 `has("request_count24h")==false`。

### 11.4 问题台账

| 编号 | 问题与复现步骤 | 涉及流程/模块 | 根因与修复 | 验证结果 | 负责人 | 状态 |
|---|---|---|---|---|---|---|
| FS-P21-001 | 打开「新建上游模型」或「导入模型」，渠道下拉显示为「OpenAI（）」而非「OpenAI（OPENAI）」；代码中亦无法判断渠道是否停用 | FS-P21-001、上游模型表单/导入页 | 前端 `providerModels.ts::fetchProviderOptions` 仍按渠道旧形状 `ProviderOption{id,name,type,enabled}` 消费 `/channels`；BE-211 渠道 V2 列表项已无 `type`/`enabled`（实测为 `provider_type`/`status` 等）。属 FE-211 跨端切换遗漏。已把 `ProviderOption` 收口为 `{id,name,provider_type,status}`，两页下拉改渲染 `provider_type`，并同步 `mocks/modelAccessMock.ts` 与 `tests/modelFormAndImport.test.ts` 夹具 | 已验证：真实链路 `GET /admin/channels` 下发 V2 字段；页面下拉渲染 `fs21-ch784871（OPENAI）`；typecheck/244 项测试/build 通过 | fsagent-0912 | 已验证 |
| FS-P21-002 | 上游模型列表「候选」列恒空白、「最近检测」恒显示「未检测」；详情页「最近错误码」恒空 | FS-P21-002、上游模型回显 | `UpstreamModelDetail` 只投影 `connection_status`，丢弃同一 `object_runtime_state` 快照中的 `last_checked_at`/`last_error_code`，且无被引用候选数；而 `ModelListPage.vue:289/291`、`ModelDetailPage.vue:280` 按 `last_check_at`/`route_candidate_count`/`last_error_code` 渲染。渠道 V2（`ChannelListItem`/`ChannelDetail`）已交付同口径三字段，属上游模型侧遗漏。已在 `UpstreamModelDetail` 增 `lastCheckAt`/`lastErrorCode`/`routeCandidateCount`：前二者取 `toDetail` 已加载的 `RuntimeStateSnapshot`（零额外查询），后者复用既有 `JdbcCandidateRepository.countLiveByProviderModel`（与 BE-014 删除拦截同源）；未检测时不下发空值 | 已验证：真实链路 `route_candidate_count` 0→1（详情与列表一致）；未检测时字段不下发；新增契约断言 2 项中 1 项覆盖本缺陷 | fsagent-0912 | 已验证 |
| FS-P21-003 | 虚拟模型列表「24h 调用」列与详情同名字段恒为空白 | FS-P21-003、虚拟模型回显 | `ModelAliasDetail` 的 `requestCount24h`/`successRate24h`/`p95TotalMs24h` 经 Jackson `SNAKE_CASE` 产出 `request_count24h`/`success_rate24h`/`p95_total_ms24h`（数字结尾不加下划线），与 `BACKEND_PLAN:250` 的 `requests_24h`/`success_rate_24h` 口径及前端 `modelAliases.ts`、`tests/aliasPages.test.ts`、`mocks/modelAccessMock.ts` 的 `_24h` 命名不一致。已对三个分量显式声明 `@JsonProperty("request_count_24h")`/`("success_rate_24h")`/`("p95_total_ms_24h")`，Java 访问器不变。先例：`UpstreamModelDetail` 对 `top_p_min`/`top_p_max` 同样使用显式 JSON 名 | 已验证：真实链路旧键 `request_count24h` 消失、新键到位；页面渲染 0；新增契约断言 `alias24hSummaryUsesUnderscoreSnakeCase` | fsagent-0912 | 已验证 |
| FS-P21-101 | 同一 SNAKE_CASE 问题存在于应用域：`ApplicationListItem.requests24h`/`successRate24h` 产出 `requests24h`/`success_rate24h`，与 `BACKEND_PLAN:250` 口径不一致 | FS-P20 相邻、应用列表 24h 摘要 | 建议按 FS-P21-003 加显式 JSON 名；因 FE-P20 页面未消费该二字段（`applications.ts` 无对应声明）故**无页面影响**，本轮不改动 P20 已交付代码，避免与 `ApplicationApiContractTest` 断言交叉 | 未修改，登记待确认 | fsagent-0912 | 待确认 |

参考口径（`BACKEND_PLAN.md` 第 250 行）：24h 摘要字段为 `requests_24h` / `success_rate_24h`。代码内既有正向先例为 `UpstreamModelDetail` 的 `top_p_min`/`top_p_max`。

### 11.5 测试命令与结果

| 范围 | 命令 | 结果 |
|---|---|---|
| 后端目标模块 | `mvn -B -pl light-ai-admin -am test` | BUILD SUCCESS；`ResourceApiContractTest` **16 项 0 失败** |
| 后端全量 | `mvn -B clean verify`（14 模块） | **BUILD SUCCESS**，0 失败 / 0 错误；示例模块用例数：client 66、spi 5、runtime 80、storage-jdbc 57（+5 环境跳过）、provider-common 6、anthropic 5、gemini 5、admin 230、server 38、starter 15；Redis IT 11 项环境跳过 |
| 前端类型 | `npm run typecheck` | 通过，exit 0 |
| 前端测试 | `npm test` | **28 文件 / 244 项通过** |
| 前端构建 | `npm run build` | 通过，exit 0 |
| 真实链路 | `p21-probe3.ps1`（Q1～Q8） | 全部 HTTP 200，见 §11.3.1 |
| 页面渲染 | `chrome-headless-shell --dump-dom` 三个页面 | 均渲染真实后端数据，见 §11.3.2 |

### 11.6 未验证环节

- **真实上游检测写入的运行态**：`POST /admin/channels/{id}/check` 在无适配器时返回 503 `PROVIDER_ADAPTER_NOT_FOUND`，真实检测未执行；`last_check_at`/`last_error_code` 的非空路径仅在契约测试中通过直写 `object_runtime_state` 验证，真实写入待真实 Provider。
- **发布生效链路**（草稿 → 校验 → 发布 → 不可变快照）与运行态容量/熔断维度依赖 DB-P21 迁移余项与运行可用性端口（BE-P21-003/004/005 余项），本包未覆盖。
- **页面点击级写操作回放**未执行（沿用 FS-P20 口径：以 DOM 渲染 + API 全链路证据覆盖字段与请求形态）。
- 真实 PostgreSQL/MySQL/Redis、企业身份四角色、真实上游 Provider 成功调用与 Usage 对账：沿用 FS-P20 未验证结论，本轮未新增证据。

### 11.7 交付状态

| 项 | 状态 |
|---|---|
| 代码提交 | `c71edcf`（fix：FS-P21-001/002/003）、`b625b95`（test：契约与夹具回归）、`8aa625d`（docs：本节记录与任务状态） |
| 远程合并 | 已普通推送：分支 `fix-fullstack-integration-fsp21-takeover-fsagent-0912` 创建成功；推送前远程 `dev` = `5007b2c`（本包领取提交），无并行新提交；推送后 `git ls-remote --heads origin` 回读 `refs/heads/dev = 8aa625d89c8ccf9c9e2fb16023dc3585beaa72cc`，未强推、未改写他人提交。确认记录见 COMMUNICATION.md「FS-P21 远程交付确认」 |
| 联调通过 | FS-P21-001/002/003 在 H2 + 单机 Redis + 真实 jar + 真实页面渲染链路通过 |
| 主任务状态 | FE-P21 / BE-P21 主任务勾选状态与负责人占用**均未改动**（保持阻塞、保留原负责人） |
| 上线验收 | 未进行；§11.6 未验证环节不得视为生产可用 |


## 12. RV-P20 应用域跨层复验（2026-09-13）

### 12.1 范围与环境

| 项 | 实际值 |
|---|---|
| 代码基线 | 当前工作区分支 `fix-review-rvp20-rvagent-0912`；保留并复验已有 RV-P20 未提交改动 |
| 后端 | 重新打包 `light-ai-server-0.1.0-SNAPSHOT.jar`，实际监听 `127.0.0.1:18080` |
| 前端 | Vite 7.3.6，`127.0.0.1:5173`，`VITE_BACKEND_TARGET=http://127.0.0.1:18080`，未启用 Mock |
| 数据库 / 共享状态 | H2 内存库（MySQL 模式，启动自动迁移 V1～V8）/ 本机 Redis `127.0.0.1:6379` |
| 浏览器 | Chromium 1223 headless，真实访问 Vite 页面并 dump DOM |

### 12.2 流程结果

| 编号 | 流程 | 结果 | 证据 |
|---|---|---|---|
| FS-RV-201 | 创建应用 → H2 持久化 → 预算/部门筛选 → `updated_at` 排序 → 页面回显 | 通过 | `POST /admin/applications` 返回 201；真实列表筛选返回 200、`budget_status=NORMAL`；页面渲染名称、部门、额度、预算状态 |
| FS-RV-202 | 创建页加载活动配置模型候选 | 通过（空态） | `GET /admin/applications/model-options` 返回 200 空数组；真实创建页显示“当前没有可授权的虚拟模型”，基本信息表单仍可用 |
| FS-RV-203 | 发布校验 V1/V2 快照兼容 | 通过 | `ConfigValidationServiceTest` 8 项 + `ConfigPublishServiceTest` 16 项全通过；V2 优先、V1 回退 |

### 12.3 实际验证命令

| 范围 | 命令 | 结果 |
|---|---|---|
| 前端类型 | `npm run typecheck` | 通过 |
| 前端测试 | `npm test -- --run` | 28 文件 / 247 项通过 |
| 后端发布校验 | `mvn -B -pl light-ai-admin -am -Dtest=ConfigValidationServiceTest,ConfigPublishServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 24 项通过 |
| 后端应用契约 | `mvn -B -pl light-ai-admin -am -Dtest=ApplicationApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 7 项通过 |
| 存储仓储 | `mvn -B -pl light-ai-storage-jdbc -am -Dtest=JdbcApplicationRepositoryTest test` | 1 项通过，覆盖预算筛选+更新时间排序 |
| 后端构建 | `mvn -B -DskipTests package` | 14 模块构建成功 |
| 真实 HTTP | H2+Redis 后端：创建应用、列表筛选、模型候选接口 | 创建 201；筛选/候选均 200 |
| 真实页面 | Chromium 1223：`/ui/applications`、`/ui/applications/new` | 应用列表真实回显“Budget Integration / 客户成功部 / 0 / 1,000,000 / 额度正常”；创建页真实空态与表单回显 |

### 12.4 未验证项

- 真实 PostgreSQL/MySQL/Redis 集群迁移、并发与故障恢复仍未执行。
- 无已发布路由候选的隔离库无法验证 model-options 非空能力交集；当前已验证其真实空态与错误边界。
- 真实 Provider 成功调用、企业身份四角色与页面点击级写操作仍沿用既有报告的未验证结论。
