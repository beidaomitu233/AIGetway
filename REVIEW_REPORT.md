# 轻享 AI 生产交付审查报告

审查日期：2026-09-06。基线：`dev` / `37a60aa`；开始时无未提交差异。
审查分支：`docs/architecture-plan-review-20260906`。
范围：现有前端、Java SDK/Runtime/Provider、Admin/Server/Starter、JDBC仓储与测试，重点检查完成声明与实际可交付链路。

## 1. 审查结论

**不通过。当前产品基线不允许按生产验收通过版本合并至 dev / main，也不允许发布。**

常规测试和普通JAR打包通过，但 Standalone 制品无法启动、Embedded 使用固定本地定义，管理安全与协议存在缺陷，真实数据库/Redis和兼容性能验收尚缺证据。19项确定问题已写入 COMMUNICATION.md，全部为“待修复 / 待执行模型修复”。本轮没有修改业务代码、接口、字段、迁移或计划勾选。

本报告基于文档、静态调用链、现有测试及局部可复现实验。没有把单元测试通过解释为生产联调通过；未执行的浏览器真实后端验收、真实存储验证、完整性能与版本矩阵均保持未通过验证状态。本报告不保证已穷尽全部缺陷。

## 2. 问题统计

| 等级 | 数量 |
| --- | ---: |
| P0 阻塞 | 2 |
| P1 严重 | 16 |
| P2 一般 | 1 |
| P3 建议 | 0 |
| 合计 | 19 |

| 编号 | 等级 | 问题 |
| --- | --- | --- |
| CR-001 | P0 | Standalone 交付不可启动 |
| CR-002 | P0 | Embedded Runtime 未接入发布配置 |
| CR-003 | P1 | BE-P08 服务未进入默认管理装配 |
| CR-004 | P1 | 集群共享容量实现缺失 |
| CR-005 | P1 | 业务 Token 的 IP 白名单未执行 |
| CR-006 | P1 | 开发人员在线测试可越过 Alias 范围 |
| CR-007 | P1 | 内部实例身份未绑定 |
| CR-008 | P1 | Provider 目标地址校验可绕过 |
| CR-009 | P1 | 运行参数未走草稿发布 |
| CR-010 | P1 | 运行参数事务提前关闭连接 |
| CR-011 | P1 | 管理流式测试与前端协议不一致 |
| CR-012 | P1 | 流式测试请求缺少 CSRF 头 |
| CR-013 | P1 | 留存影响请求响应与页面不兼容 |
| CR-014 | P1 | 留存清理仅有端口存根 |
| CR-015 | P1 | 数据库交付与一致性门禁未完成 |
| CR-016 | P1 | 性能和兼容性任务勾选缺少对应验收 |
| CR-017 | P2 | 管理请求超时未覆盖响应体 |
| CR-018 | P1 | 草稿查询使用未定义数据库字段 |
| CR-019 | P1 | 运行参数并发版本检查可被绕过 |

具体文件、行号、接口、表、风险和修复方向均见 [COMMUNICATION.md 第7节](COMMUNICATION.md)。C-026 的已有问题按 CR-009 提升为明确交付阻断，并未将其另算一次。

## 3. 文档一致性检查

已读取和交叉检查 PROJECT_DOCUMENT.md、FRONTEND_PLAN.md、BACKEND_PLAN.md、DATABASE_PLAN.md、COMMUNICATION.md 的任务、公共规则和相关接口/实体契约，结合源码结构、POM/package.json、测试及最近提交进行抽样追踪。

| 文档 | 当前勾选 | 结论 |
| --- | --- | --- |
| FRONTEND_PLAN | 53/54；FE-054 未勾选 | 页面/组件/测试已有实现，但管理流、CSRF、留存影响实际契约不一致，相关已勾选项须重验。 |
| BACKEND_PLAN | 54/60；BE-025~030 未勾选 | BE-P05 说明称已实现，仍保留生产接线依赖；BE-043~048、055~060 的完成声明存在服务装配和验收缺口。 |
| DATABASE_PLAN | 0/30 | 设计未交付为迁移，不将未勾选数据库任务认定为虚假完成；依赖这些任务的生产验收不能放行。 |
| COMMUNICATION | C-001/C-003~018/C-021~023/C-025/C-026 等仍有待确认项 | 身份来源、数据口径与部署依赖未全部闭环；C-026 已承认草稿缺口，不能以待确认规避既定发布规则。 |

最近提交追踪：`37a60aa` 更新多数据库文档；`c6f3920` 修改48个文件适配方言；`22d36d9` 交付BE-P10；`ed7821c` 交付SDK；`76f74dc`/`ffd3180` 交付发布及登记C-026；`18ee4ea` 交付BE-P08。任务有对应代码提交，但提交存在不等于验收充分。当前 diff 初始为空，没有将他人未提交改动混入审查交付。

## 4. 功能验收结果

| 功能链路 | 结果及证据 |
| --- | --- |
| Standalone启动 → 健康 → /v1 | 不通过：执行生成的JAR返回缺少主清单属性；缺启动入口及生产装配（CR-001）。 |
| Embedded配置 → 发布 → 新调用 | 不通过：使用固定LocalRuntimeDefinition，未接活动快照（CR-002）。 |
| 管理运行参数/访问凭证/开发接入 | 不通过：默认自动装配未注册相关Service/Controller；仅扫描Controller不能补全服务（CR-003）。 |
| Token/IP/应用与Alias范围 | 不通过：IP白名单未执行，开发在线测试构造无限制Alias身份（CR-005/006）。 |
| 草稿/运行参数保存 | 不通过：草稿联动缺失、连接提前关闭、版本竞争保护不足（CR-009/010/019）。 |
| 流式在线测试 | 不通过：协议/编码/错误终态与前端不一致，CSRF会话下请求缺头（CR-011/012）。 |
| 留存确认及清理 | 不通过：请求响应不匹配、估算固定0、清理生产实现缺失（CR-013/014）。 |
| 发布实例上报 | 不通过：共享口令不绑定instance_id（CR-007）；真实双实例准备/激活/收敛未验证。 |
| 同步/恢复/取消算法 | 现有Java单元测试通过；不代表HTTP、持久化、集群联调通过。 |

未访问真实Provider、未使用真实密钥、未运行对生产库的读写或清理。页面功能未以Mock界面代替真实后端验收。

## 5. 前端审查结果

Vue页面、API封装、权限常量、分页/筛选、状态与组件测试已存在；常规lint/typecheck/test/build均完成。API封装支持data解包、取消、错误码与手动重试设计，测试覆盖部分表单错误/409/空态/竞态/权限守卫。

主要缺陷为CR-011/012/013/017。运行参数页面直接消费 `impact.counts.trace` 与 `target_values`，后端扁平响应无法满足它；流请求独立fetch绕过统一CSRF头处理。普通请求收到响应头后取消超时，慢响应体可长时间占用页面loading。

开发和preview的vite插件均注册Mock API；它不作为生产后端或真实成功证据。本次未运行真实后端浏览器全流程和尺寸截图，加载/空态/失败/401/403/取消仅有静态与现有组件测试范围内的证据。

lint产生37条warning、0 error，主要位于AuditDetailPage.vue和AuditListPage.vue的模板格式；未为这些非阻断格式告警新增重复问题。测试日志有RouterLink/路由上下文警告，测试通过仍不能替代完整路由集成验证。

## 6. 后端审查结果

代码已有Controller/Service/Repository和Runtime端口分层，DTO/协议、Provider单次调用和部分错误分类有单元测试。生产装配缺口是首要阻断：Server仅为普通JAR，Starter硬编码本地定义，BE-P08服务未注册，运行端口与管理/观测持久化未形成可启动验收链。

鉴权需补CR-005/006/007；发布/保存一致性需补CR-009/010/019。运行参数版本检查在事务外发生、UPDATE不带version，不能保护并发覆盖；try-with-resources提前关闭Spring管理连接经最小探针复现。

管理SSE使用业务Chunk、将SSE文本再次传入SseEmitter且错误后可继续DONE。必须按既有管理StreamEvent契约修复，覆盖真实HTTP消费，不能只测SseEncoder字符串。

## 7. 数据库审查结果

39表仍为设计；没有版本化迁移、初始化脚本或SchemaMigrator实现。无法验收空库安装、重复迁移、回滚/恢复、唯一约束、外键、CHECK、索引、真实并发事务和查询执行计划（CR-015）。默认SchemaGuard只核表名，存在空表/缺列但通过启动检查的风险。

确认字段冲突：draft_change为R类、逐字段表无deleted_at，但仓储多个查询和删除依赖该列（CR-018）。同时数据库总则“所有查询携带deleted_at过滤”与R类无软删除规定冲突，执行方须以已确认实体语义统一。

数据库方言已有SPI和分支实现；动态数据源测试使用代理Connection并仅核对方言字符串，未执行实际仓储SQL。MySQL5.7/8.0与PostgreSQL等价性没有真实验证，不能照抄H-025“无缝支持”的完成结论。

列表仓储存在分页与批量读取设计；因无实际schema/数据量，索引命中、N+1全链路和高基数查询成本均未验收。Trace/Attempt、草稿/快照、共享状态/审计具有不同语义，未发现足以支持直接合表的证据，不提出未经测量的表数量精简。

## 8. 安全审查结果

| 检查项 | 结论 |
| --- | --- |
| 管理身份/角色 | 已有默认拒绝、权限矩阵与部分测试；生产装配和Embedded本地身份衔接尚需重验。 |
| Token/IP/数据范围/越权 | CR-005/006；限制IP和开发Alias的要求未被运行链强制执行。 |
| 内部身份 | CR-007；持有全局口令即可声称其他实例ID，READY/LOADED可信性不足。 |
| SSRF | CR-008；完整IPv6 loopback被禁止内网策略接受，实际调用无解析地址复核。仅作本地验证，没有请求内网目标。 |
| CSRF | 普通请求具备受保护头处理；流测试缺头导致合法请求被拒（CR-012），修复不能关闭CSRF。 |
| 参数污染/SQL注入 | 已有严格写DTO和查询白名单/参数绑定；本轮未确认可利用SQL注入。缺真实HTTP/DB反例验收，不能宣称全面通过。 |
| XSS/导出 | 页面模板和CSV保护存在测试基础；未执行真实浏览器攻击负载验收。 |
| 密钥/Token/错误脱敏 | 已有HMAC摘要、AES-GCM、掩码和安全日志相关单元测试通过；没有读取或输出真实秘密。全链路日志/诊断导出仍需真实部署复核。 |
| 密码/上传 | 项目未建设账户密码和文件上传功能，本轮无对应交付面；不要求额外新增这些系统。 |
| 接口频率限制 | 配置与内存实现存在；集群共享原子限额缺失，见CR-004。 |
| 诊断留存 | 生产清理未装配且样本清理被全局pending事件阻断，见CR-014。 |

安全缺陷按至少P1登记；未把未运行的安全检查列为通过。

## 9. 性能审查结果

前端Vite构建完成且有路由分块，入口JS约129.82kB（gzip50.10kB），未发现构建器的大块异常告警。该体积证据不能证明首屏耗时；真实首屏网络、长列表、重复请求和数据量相关成本未测。

BE-060测试是内存Stub管线微基准，200任务以64线程执行，未建立200条HTTP流连接，且CapacityPort.unlimited绕过真实容量。其P95结果不能证明总文档要求的同网数据库/Redis、2分钟预热、10分钟稳态与双实例故障恢复（CR-016）。

不额外建议缓存或增加依赖；执行方先完成真实链路和必要负载，再依据测量确定瓶颈。

## 10. 测试命令与结果

环境：Windows；Java17.0.19；Maven3.9.9；Spring Boot3.5.5；Node20.19.6。
Maven可执行路径：`D:/IntelliJ IDEA 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd`。以下Maven命令在仓库根执行，npm命令在light-ai-admin-ui执行。

| 命令 | 实际结果 |
| --- | --- |
| mvn -B verify | BUILD SUCCESS，356测试、0失败/错误/跳过；12子模块普通JAR打包成功，含父POM共13个Reactor项。未配置独立Java lint任务。 |
| npm run lint | 完成，0 error、37 warning。 |
| npm run typecheck | vue-tsc --noEmit通过。 |
| npm test | Vitest通过；为避免首次长输出截断，再以JSON reporter记录160测试全部通过。 |
| npm test -- --reporter=json --outputFile=<TEMP>/light-ai-review-vitest.json | 160总数/160通过/0失败/0待执行，success=true。 |
| npm run build | vue-tsc与vite build通过；vite阶段3.13秒。 |
| mvn -B -pl light-ai-storage-jdbc -am -Dtest=PostgresSchemaGuardIT -Dsurefire.failIfNoSpecifiedTests=false test | 3项全部跳过；LAI_IT_DB_URL未设置。命令成功不代表集成测试通过。 |
| java -jar light-ai-server/target/light-ai-server-0.1.0-SNAPSHOT.jar | 失败，退出1：没有主清单属性。 |
| Java临时最小探针（既有Spring事务管理器/Mockito连接替身） | 确认commit之前connection已close；同时确认TargetUrlPolicy(false)接受完整IPv6 loopback、空Alias范围允许任意Alias。没有真实数据库或外网访问。 |
| Node临时探针（读取原http.ts并转译，fetch响应体延迟100ms） | timeoutMs=10仍在114ms成功返回，复现CR-017。 |
| git diff --check | 初始和文档落盘检查均通过；19个问题编号唯一、台账均为10列、报告12项齐备。 |
| 数据库迁移/真实SQL/Redis双实例/性能/Java21+其他Boot+Reactive矩阵 | 未运行：缺迁移与已配置测试库/Redis验收环境，且Server生产启动链路不完整；不判通过。 |

Maven测试分布：client62、spi4、storage16、runtime61、provider-common5、anthropic5、gemini5、admin164、server25、starter9，共356。OpenAI和DeepSeek模块本轮没有独立测试类，部分协议测试位于common，不虚报其模块测试数。

原始本机日志：`%TEMP%/light-ai-review-maven.log`、`light-ai-review-it.log`、`light-ai-review-vitest.json`、`light-ai-review-vitest.log`；最小探针保存在系统临时目录，未加入业务仓库。日志仅为本次本机证据，报告已摘录关键结果。

## 11. 修复优先级建议

1. 先恢复可交付链路：CR-001/002/003，明确生产端口与活动快照装配；由数据库执行方完成CR-015的迁移基础。
2. 在开放真实调用前修复CR-005/006/007/008等安全问题与CR-004集群限额。
3. 修复CR-009/010/019保存事务、CR-018表契约，再处理CR-013/014留存确认与执行。
4. 联调CR-011/012流式入口，补CR-017响应体超时；使用真实HTTP而非相互独立的Mock完成回归。
5. 按CR-016补真实存储、双实例、兼容版本与性能证据；逐项复审后由执行方更新Plan勾选。

## 12. 是否允许合并到 dev / main

**不允许当前产品代码作为通过质量闸门的版本继续合并或发布。**基线已经在dev中，本轮不回退、不改写已有提交。审查文档可独立评阅和接收；代码修复应在对应功能分支完成，P0/P1关闭并复验后才可申请集成。

交付文件仅COMMUNICATION.md和REVIEW_REPORT.md。无业务代码修改，无推送、发布或部署；不代执行方修复，也不擅自变更计划状态。
