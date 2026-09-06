# 数据库执行计划

## 1. 选型、所有权与命名

支持 PostgreSQL（默认，独立 schema `light_ai`）与 MySQL 8.0 / MySQL 5.7 自由切换；仓储层采用 DatabaseDialect 抹平方言差异，Starter 集成 dynamic-datasource-spring-boot3-starter 支持动态多数据源路由。表名 snake_case，实体 ID 为 UUID（PostgreSQL 下为 uuid 原生类型，MySQL 下为 varchar(36) 存储），API 作为不透明字符串。宿主复用 DataSource 时按方言自动适配表名修饰（PostgreSQL 为 schema.table，MySQL 为 `table` 或 `schema`.`table`）。当前无既有数据库；本文件为物理设计，不包含 DDL、迁移或生产脚本。

数据库支持主键、检查、唯一与外键约束：在 PostgreSQL 下涉及软删除的唯一性采用部分唯一索引（WHERE deleted_at IS NULL）；在 MySQL 5.7 / 8.0 下因不支持部分索引，唯一约束通过应用层写入校验结合逻辑约束保证，所有查询均显式携带 `deleted_at IS NULL` 过滤。SQL 语法全面适配 MySQL 5.7（消除 CTE、消除 UPDATE...FROM、消除 SKIP LOCKED、消除 FILTER (WHERE...)、消除原生数组，使用派生子查询、ANSI CASE WHEN、LIMIT...OFFSET、ON DUPLICATE KEY UPDATE 等）。

统一规则：timestamp采用timestamptz，存UTC、API ISO8601；数据库事务使用同一now；id由应用生成UUID；version用bigint从1递增。数量bigint非负，限额null为不限，0不合法；价格numeric(20,8)，金额numeric(30,8)，比例numeric(9,4)，币种char(3)。接口bigint/decimal字符串规则见总文档。所有字段未列默认时不得靠隐式业务默认补齐。

各表在字段表中展开公共字段。C类配置表含id、created_at、updated_at、version、deleted_at；R类运行/可变记录含id、created_at、updated_at；I类不可变事实含id、created_at；S类安全即时实体含id、created_at、updated_at、version、deleted_at。enabled不自动继承，必须表内明确声明。

配置表保存草稿工作集，deleted_at是草稿删除标记；活动数据在ConfigSnapshot不可变JSON。新请求禁止从草稿表取路由参数。删除有效配置前检查草稿及ACTIVE引用，历史使用ID与名称快照保留。业务唯一键在deleted_at为空范围保持唯一；历史ID不复用。删除后同名是否重建默认允许新ID，Alias由外部使用，重建需管理确认并审计。

运行态记录无软删除，按保留策略物理批删；Access DELETED不可恢复，摘要立即失效。历史Trace/Attempt到配置对象使用逻辑ID关联，不建阻止配置清理的硬外键；Trace明细内部使用真实外键且禁止独立孤儿写入，按清理顺序删除，不靠级联删除Usage。

下面表内的“必填”是物理NOT NULL；“条件”代表列可NULL但启用/业务阶段由CHECK或服务层验证。C/R/I/S字段模板已逐表展开，不能省略公共列。枚举varchar+CHECK便于迁移；不使用数据库原生枚举。

## 2. 表与字段

### 1. provider

用途：供应商草稿连接配置。存储类别：C。

使用接口与页面：/admin/providers；Provider列表/表单/详情。

索引、唯一约束与关联：U(name)活行；I(type,deleted_at)；无父FK。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| name | varchar(64) | 是 | 无 | 2—64全局唯一 |
| type | varchar(64) | 是 | 无 | Adapter provider_type注册值 |
| base_url | varchar(2048) | 是 | 无 | 规范化外部地址 |
| proxy_url | varchar(2048) | 否 | NULL | 可选代理 |
| connect_timeout_ms | integer | 是 | 3000 | 100—60000 |
| read_timeout_ms | integer | 是 | 120000 | 1000—600000且不少于连接超时 |
| default_headers | jsonb | 是 | {} | 最多20项非认证头键值 |
| enabled | boolean | 是 | true | 发布生效 |

### 2. credential_pool

用途：同Provider的凭证池草稿。存储类别：C。

使用接口与页面：/admin/credential-pools；池页面及候选选择。

索引、唯一约束与关联：U(provider_id,name)活行；I(provider_id,deleted_at)；FK provider_id→provider.id RESTRICT。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| provider_id | uuid | 是 | 无 | 创建后不变 |
| name | varchar(64) | 是 | 无 | 池名称 |
| selection_strategy | varchar(32) | 是 | LEAST_CONCURRENT | LEAST_CONCURRENT/ROUND_ROBIN/WEIGHTED_RANDOM |
| enabled | boolean | 是 | true | 发布生效 |

### 3. credential

用途：凭证可发布配置，不存密钥。存储类别：C。

使用接口与页面：/admin/credentials及池下credentials；凭证表单和列表。

索引、唯一约束与关联：U(pool_id,name)活行；I(pool_id,enabled)；FK pool_id→credential_pool.id RESTRICT。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| pool_id | uuid | 是 | 无 | 所属池不可变 |
| name | varchar(64) | 是 | 无 | 池内唯一 |
| secret_source | varchar(24) | 是 | INLINE_ENCRYPTED | INLINE_ENCRYPTED/EXTERNAL_REF不可变 |
| weight | integer | 是 | 1 | 1—100 |
| rpm_limit | bigint | 否 | NULL | 空或正整数 |
| tpm_limit | bigint | 否 | NULL | 空或正整数 |
| concurrent_limit | integer | 否 | NULL | 1—100000 |
| enabled | boolean | 是 | true | 发布生效 |

### 4. credential_secret

用途：受保护秘密与即时轮换值，独立于草稿快照。存储类别：R。

使用接口与页面：凭证写入/rotate和进程内CredentialSecretResolver；页面仅masked_value。

索引、唯一约束与关联：U(credential_id)；FK credential_id→credential.id RESTRICT；库账户禁止普通查询服务读取secret列。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| credential_id | uuid | 是 | 无 | 一凭证一条安全记录 |
| secret_ciphertext | bytea | 条件 | NULL | INLINE加密值含nonce/tag封装 |
| secret_ref_ciphertext | bytea | 条件 | NULL | EXTERNAL完整引用加密；与secret_ciphertext互斥 |
| encryption_key_id | varchar(128) | 是 | 无 | 外部主密钥标识非密钥 |
| masked_value | varchar(128) | 是 | 无 | 服务端生成安全掩码 |
| secret_version | bigint | 是 | 1 | 每次轮换递增用于缓存失效 |
| rotated_at | timestamptz | 否 | NULL | 最近轮换时间 |

### 5. provider_model

用途：真实文本模型能力、默认值与价格草稿。存储类别：C。

使用接口与页面：/admin/provider-models、import；模型列表/编辑/导入。

索引、唯一约束与关联：U(provider_id,model_id)、U(provider_id,display_name)活行；FK provider_id→provider.id；I(provider_id,enabled)。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| provider_id | uuid | 是 | 无 | 所属Provider不可改 |
| model_id | varchar(128) | 是 | 无 | 外部模型ID保持大小写 |
| display_name | varchar(64) | 是 | 无 | 模型展示名 |
| model_type | varchar(16) | 是 | CHAT_TEXT | 固定文本Chat |
| tokenizer_family | varchar(64) | 条件 | NULL | 启用必填且Adapter声明 |
| context_window | bigint | 条件 | NULL | 启用正整数且context大于output |
| max_output_tokens | bigint | 条件 | NULL | 启用正整数且context大于output |
| support_stream | boolean | 条件 | NULL | 导入未知留空；启用必须补齐 |
| support_system_message | boolean | 条件 | NULL | 导入未知留空；启用必须补齐 |
| support_temperature | boolean | 条件 | NULL | 导入未知留空；启用必须补齐 |
| support_top_p | boolean | 条件 | NULL | 导入未知留空；启用必须补齐 |
| support_stop | boolean | 条件 | NULL | 导入未知留空；启用必须补齐 |
| temperature_min | numeric(9,4) | 条件 | NULL | 支持对应参数时必填且不超过Adapter上界 |
| temperature_max | numeric(9,4) | 条件 | NULL | 支持对应参数时必填且不超过Adapter上界 |
| top_p_min | numeric(9,4) | 条件 | NULL | 支持对应参数时必填且不超过Adapter上界 |
| top_p_max | numeric(9,4) | 条件 | NULL | 支持对应参数时必填且不超过Adapter上界 |
| max_stop_sequences | integer | 条件 | NULL | 支持stop时分别1—4及1—128 |
| max_stop_length | integer | 条件 | NULL | 支持stop时分别1—4及1—128 |
| default_temperature | numeric(9,4) | 否 | NULL | 模型允许范围内 |
| default_top_p | numeric(9,4) | 否 | NULL | 模型允许范围内 |
| default_max_tokens | bigint | 否 | NULL | 1—max_output_tokens |
| default_stop | jsonb | 是 | [] | 唯一字符串数组 |
| input_price | numeric(20,8) | 是 | 0 | 非负 |
| output_price | numeric(20,8) | 是 | 0 | 非负 |
| price_unit | integer | 是 | 1000000 | 1000或1000000 |
| currency | char(3) | 是 | USD | ISO4217 |
| enabled | boolean | 是 | true | 导入显式false |
| import_source | varchar(24) | 否 | NULL | PROVIDER_API/ADAPTER_PRESET |
| import_adapter_version | varchar(64) | 否 | NULL | 能力默认值来源 |

### 6. model_alias

用途：业务稳定别名草稿。存储类别：C。

使用接口与页面：/admin/model-aliases、/v1/models；Alias页面和接入页。

索引、唯一约束与关联：U(alias)活行；I(enabled,deleted_at)；无父FK。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| alias | varchar(64) | 是 | 无 | 2—64字母数字点横线下划线创建后不变 |
| display_name | varchar(64) | 是 | 无 | 2—64 |
| description | varchar(500) | 否 | NULL | 不传入Prompt |
| route_strategy | varchar(32) | 是 | PRIORITY_WEIGHTED | 固定 |
| enabled | boolean | 是 | true | 启用发布需至少一候选 |

### 7. route_candidate

用途：Alias到实际模型和池的候选草稿。存储类别：C。

使用接口与页面：/admin/model-aliases/{id}/candidates、route-candidates；Alias详情。

索引、唯一约束与关联：U(alias_id,provider_model_id,credential_pool_id)活行；I(alias_id,priority,id)；FK三个ID→各配置表。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| alias_id | uuid | 是 | 无 | 所属Alias |
| provider_model_id | uuid | 是 | 无 | 创建后不可改 |
| credential_pool_id | uuid | 是 | 无 | 与模型同Provider由服务和发布校验 |
| priority | integer | 是 | 10 | 1—100 |
| weight | integer | 是 | 1 | 1—100同级可用集合归一化 |
| enabled | boolean | 是 | true | 发布生效 |

### 8. limit_policy

用途：三层作用对象限额草稿。存储类别：C。

使用接口与页面：/admin/limit-policies；策略表单/容量详情。

索引、唯一约束与关联：U(name)活行；U(scope_type,scope_id) WHERE enabled且未删；I(scope_id)；scope逻辑引用由服务校验。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| name | varchar(64) | 是 | 无 | 2—64 |
| scope_type | varchar(24) | 是 | MODEL_ALIAS | MODEL_ALIAS/PROVIDER_MODEL/CREDENTIAL |
| scope_id | uuid | 是 | 无 | 类型和ID创建后不可改 |
| rpm_limit | bigint | 否 | NULL | 正数，RPM≤1000000000 |
| tpm_limit | bigint | 否 | NULL | 正数，RPM≤1000000000 |
| concurrent_limit | integer | 否 | NULL | 1—100000 |
| overflow_strategy | varchar(12) | 是 | REJECT | REJECT/QUEUE |
| queue_timeout_ms | integer | 条件 | NULL | QUEUE时默认5000/1000且必填；REJECT为NULL |
| queue_max_size | integer | 条件 | NULL | QUEUE时默认5000/1000且必填；REJECT为NULL |
| window_seconds | integer | 是 | 60 | 固定 |
| enabled | boolean | 是 | true | 启用要求至少一个限额 |

### 9. reliability_policy

用途：Alias超时恢复熔断策略草稿。存储类别：C。

使用接口与页面：/admin/reliability-policies；可靠性页面。

索引、唯一约束与关联：U(name)活行；U(alias_id) WHERE enabled且未删；FK alias_id→model_alias.id。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| name | varchar(64) | 是 | 无 | 2—64 |
| alias_id | uuid | 是 | 无 | 创建后不可改 |
| connect_timeout_ms | integer | 是 | 3000 | 100—60000 |
| first_token_timeout_ms | integer | 是 | 30000 | 1000—300000且小于total |
| total_timeout_ms | integer | 是 | 120000 | 1000—600000 |
| max_retries | integer | 是 | 1 | 分别0—5及0—10 |
| max_credential_failovers | integer | 是 | 1 | 分别0—5及0—10 |
| initial_backoff_ms | integer | 是 | 200 | 0—10000 |
| backoff_multiplier | numeric(5,2) | 是 | 2 | 1—5 |
| jitter_percent | integer | 是 | 20 | 0—100 |
| respect_retry_after | boolean | 是 | true | 是否尊重上游头 |
| max_retry_after_ms | integer | 是 | 5000 | 0—60000 |
| fallback_enabled | boolean | 是 | true | false强制max_fallbacks0 |
| max_fallbacks | integer | 是 | 2 | 0—10 |
| circuit_window_seconds | integer | 是 | 60 | 10—600 |
| circuit_min_requests | integer | 是 | 20 | 1—10000 |
| circuit_failure_rate | numeric(9,4) | 是 | 0.5 | 0.01—1 |
| circuit_open_seconds | integer | 是 | 30 | 1—3600 |
| circuit_half_open_probes | integer | 是 | 3 | 1—100 |
| circuit_half_open_successes | integer | 是 | 2 | 1—probes |
| enabled | boolean | 是 | true | 停用后采用内置默认策略 |

### 10. runtime_config

用途：运行参数草稿及只读运行指针。存储类别：C。

使用接口与页面：/admin/runtime-config；运行参数与顶部上下文。

索引、唯一约束与关联：单例I常量singleton_key唯一；default_alias_id逻辑引用Alias；current_snapshot_no逻辑关联snapshot。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次成功配置写更新 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 草稿删除标记 |
| singleton_key | integer | 是 | 1 | CHECK=1 |
| timezone | varchar(64) | 是 | Asia/Shanghai | IANA |
| timezone_locked | boolean | 是 | false | 首次聚合同事务锁定后不可逆 |
| trace_retention_days | integer | 是 | 30 | 1—365 |
| usage_retention_days | integer | 是 | 365 | 30—3650且≥Trace |
| audit_retention_days | integer | 是 | 365 | 365—3650 |
| dashboard_refresh_seconds | integer | 是 | 30 | 10—300 |
| max_message_chars | integer | 是 | 100000 | 1000—1000000 |
| max_request_chars | integer | 是 | 500000 | 不少于message且≤5000000 |
| diagnostic_sampling_enabled | boolean | 是 | false | 显式启用 |
| diagnostic_sample_rate | numeric(9,4) | 是 | 0 | 0—1关闭强制0 |
| diagnostic_sample_retention_days | integer | 是 | 7 | 1—30且≤Trace |
| diagnostic_sample_max_chars | integer | 是 | 1000 | 100—10000 |
| client_ip_recording_enabled | boolean | 是 | false | 仅新Trace |
| trusted_proxy_cidrs | jsonb | 是 | [] | 最多100网络项 |
| publish_instance_timeout_seconds | integer | 是 | 60 | 10—300 |
| instance_stale_seconds | integer | 是 | 60 | 30—600且>30 |
| current_snapshot_no | bigint | 是 | 0 | 运行指针不进可编辑DTO或快照content |
| published_at | timestamptz | 否 | NULL | 活动发布时间 |
| default_alias_id | uuid | 否 | NULL | C-010补充默认Alias |

### 11. object_runtime_state

用途：Provider/模型/凭证最近运行健康摘要。存储类别：R。

使用接口与页面：管理接入列表/检测；池和候选状态由此组合派生。

索引、唯一约束与关联：U(entity_type,entity_id)；I(health_status,reset_at)；配置ID逻辑关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| entity_type | varchar(24) | 是 | 无 | PROVIDER/PROVIDER_MODEL/CREDENTIAL |
| entity_id | uuid | 是 | 无 | 配置对象 |
| connection_status | varchar(16) | 否 | NULL | UNKNOWN/AVAILABLE/UNAVAILABLE |
| health_status | varchar(24) | 否 | NULL | HEALTHY/UNKNOWN/RATE_LIMITED/INVALID/UNAVAILABLE/DISABLED |
| reset_at | timestamptz | 否 | NULL | 健康时点 |
| last_success_at | timestamptz | 否 | NULL | 健康时点 |
| last_checked_at | timestamptz | 否 | NULL | 健康时点 |
| last_failed_at | timestamptz | 否 | NULL | 健康时点 |
| last_error_code | varchar(64) | 否 | NULL | 统一错误 |
| last_error_summary | varchar(1000) | 否 | NULL | 脱敏摘要 |
| state_version | bigint | 是 | 1 | CAS运行版本 |

### 12. provider_check_record

用途：一次主动检测事实。存储类别：I。

使用接口与页面：各check/probe；检测结果与最近检测区域。

索引、唯一约束与关联：I(target_type,target_id,created_at desc)；trace_id/attempt_id逻辑引用；无配置级联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| target_type | varchar(32) | 是 | 无 | PROVIDER/CREDENTIAL/PROVIDER_MODEL/ROUTE_CANDIDATE/CIRCUIT_STATE |
| target_id | uuid | 是 | 无 | 检测目标 |
| mode | varchar(24) | 是 | 无 | 检测模式 |
| status | varchar(16) | 是 | 无 | SUCCEEDED/FAILED |
| operator_id | varchar(128) | 是 | 无 | 当前操作者 |
| trace_id | varchar(128) | 否 | NULL | 有真实调用才有Trace |
| attempt_id | uuid | 否 | NULL | 真实外部尝试 |
| started_at | timestamptz | 是 | 无 | 开始结束 |
| ended_at | timestamptz | 是 | 无 | 开始结束 |
| total_ms | integer | 是 | 无 | 非负 |
| usage | jsonb | 否 | NULL | 输入输出总token与source |
| provider_request_id | varchar(256) | 否 | NULL | 受控字段 |
| error_code | varchar(64) | 否 | NULL | 失败码 |
| error_summary | varchar(1000) | 否 | NULL | 脱敏错误 |

### 13. batch_check_job

用途：批量检测生命周期。存储类别：R。

使用接口与页面：/admin/batch-check-jobs/{id}；批量检测面板。

索引、唯一约束与关联：I(status,created_at)；无父FK。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| status | varchar(24) | 是 | PENDING | PENDING/RUNNING/SUCCEEDED/PARTIAL_FAILED/FAILED/CANCELLED |
| operator_id | varchar(128) | 是 | 无 | 发起人 |
| total_count | integer | 是 | 0 | 汇总由明细更新 |
| completed_count | integer | 是 | 0 | 汇总由明细更新 |
| success_count | integer | 是 | 0 | 汇总由明细更新 |
| failure_count | integer | 是 | 0 | 汇总由明细更新 |
| cancelled_count | integer | 是 | 0 | 汇总由明细更新 |
| started_at | timestamptz | 否 | NULL | 任务时间 |
| ended_at | timestamptz | 否 | NULL | 任务时间 |
| command | jsonb | 是 | 无 | provider_model_ids/credential_id/mode/timeout_ms，无密钥 |

### 14. batch_check_item

用途：批量检测逐模型结果。存储类别：R。

使用接口与页面：batch-check-job详情；批量检测面板。

索引、唯一约束与关联：U(job_id,provider_model_id)；I(job_id,sequence)；FK job_id→batch_check_job.id；检测记录逻辑引用。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| job_id | uuid | 是 | 无 | 任务与目标 |
| provider_model_id | uuid | 是 | 无 | 任务与目标 |
| sequence | integer | 是 | 无 | 从1递增 |
| status | varchar(16) | 是 | PENDING | PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED |
| check_record_id | uuid | 否 | NULL | 完成记录 |
| started_at | timestamptz | 否 | NULL | 检测时间 |
| ended_at | timestamptz | 否 | NULL | 检测时间 |
| error_code | varchar(64) | 否 | NULL | 失败码 |

### 15. trace

用途：一次业务请求事实和最终汇总。存储类别：R。

使用接口与页面：/v1/chat、/admin/traces、overview；Trace/概览/在线测试。

索引、唯一约束与关联：U(trace_id)；I(application,started_at desc,trace_id)、I(alias_id,started_at)、I(final_provider_id,started_at)、I(status,started_at)；配置均逻辑关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| trace_id | varchar(128) | 是 | 无 | 外部唯一，清理策略见正文 |
| application | varchar(64) | 是 | 无 | 身份派生不可伪造 |
| project | varchar(64) | 否 | NULL | 检索标签 |
| tenant | varchar(64) | 否 | NULL | 检索标签 |
| request_user | varchar(128) | 否 | NULL | 业务用户非认证Principal |
| tags | jsonb | 是 | {} | 受控字符串标签 |
| source_mode | varchar(32) | 是 | 无 | LOCAL_RUNTIME/EMBEDDED/STANDALONE_SERVER |
| invocation_source | varchar(16) | 是 | APPLICATION | APPLICATION/ADMIN_TEST/PROVIDER_CHECK |
| alias_id | uuid | 条件 | NULL | APPLICATION/ADMIN_TEST必填；PROVIDER_CHECK可空 |
| alias | varchar(64) | 条件 | NULL | 业务Alias名称快照；Provider独立检测可空 |
| config_snapshot_no | bigint | 是 | 无 | 整个Trace固定 |
| requested_stream | boolean | 是 | false | 响应边界 |
| response_committed | boolean | 是 | false | 响应边界 |
| status | varchar(24) | 是 | RUNNING | QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/STREAM_INTERRUPTED |
| started_at | timestamptz | 是 | 无 | 开始与总截止 |
| deadline_at | timestamptz | 是 | 无 | 开始与总截止 |
| ended_at | timestamptz | 否 | NULL | 仅终态 |
| total_ms | integer | 否 | NULL | 阶段缺失空值 |
| first_token_ms | integer | 否 | NULL | 阶段缺失空值 |
| queued_ms | integer | 是 | 0 | 排队累计 |
| attempt_count | integer | 是 | 0 | 实际次数 |
| retry_count | integer | 是 | 0 | 实际次数 |
| credential_failover_count | integer | 是 | 0 | 实际次数 |
| fallback_count | integer | 是 | 0 | 实际次数 |
| final_attempt_id | uuid | 否 | NULL | 最终路径和访问凭证逻辑引用 |
| final_provider_id | uuid | 否 | NULL | 最终路径和访问凭证逻辑引用 |
| final_provider_model_id | uuid | 否 | NULL | 最终路径和访问凭证逻辑引用 |
| final_credential_id | uuid | 否 | NULL | 最终路径和访问凭证逻辑引用 |
| access_credential_id | uuid | 否 | NULL | 最终路径和访问凭证逻辑引用 |
| final_provider_name | varchar(128) | 否 | NULL | 安全名称快照 |
| final_provider_model_name | varchar(128) | 否 | NULL | 安全名称快照 |
| access_credential_name | varchar(128) | 否 | NULL | 安全名称快照 |
| input_tokens | bigint | 是 | 0 | 总消耗与最终成功响应分开 |
| output_tokens | bigint | 是 | 0 | 总消耗与最终成功响应分开 |
| total_tokens | bigint | 是 | 0 | 总消耗与最终成功响应分开 |
| response_input_tokens | bigint | 是 | 0 | 总消耗与最终成功响应分开 |
| response_output_tokens | bigint | 是 | 0 | 总消耗与最终成功响应分开 |
| response_total_tokens | bigint | 是 | 0 | 总消耗与最终成功响应分开 |
| usage_source | varchar(12) | 否 | NULL | ACTUAL/ESTIMATED/MIXED无Attempt可空 |
| input_cost | numeric(30,8) | 是 | 0 | 全部已结算Attempt之和 |
| output_cost | numeric(30,8) | 是 | 0 | 全部已结算Attempt之和 |
| total_cost | numeric(30,8) | 是 | 0 | 全部已结算Attempt之和 |
| currency | char(3) | 是 | 无 | 来自Alias固定币种 |
| finish_reason | varchar(32) | 否 | NULL | 成功时有效 |
| error_code | varchar(64) | 否 | NULL | 统一错误 |
| error_category | varchar(64) | 否 | NULL | 统一错误 |
| error_stage | varchar(64) | 否 | NULL | 统一错误 |
| error_summary | varchar(1000) | 否 | NULL | 无正文 |
| retryable | boolean | 是 | false | 外部业务可重试提示 |
| request_summary | jsonb | 是 | {} | 非正文计数及参数结构见JSON字典 |
| client_ip | inet | 否 | NULL | 显式启用才存且受控 |
| user_agent | varchar(512) | 否 | NULL | 截断安全值 |
| owner_instance_id | uuid | 否 | NULL | 失联清理关联 |
| lease_expires_at | timestamptz | 否 | NULL | 运行租约供Watchdog核对 |
| terminal_version | bigint | 是 | 0 | 终止CAS令牌 |

### 16. attempt

用途：一次真实外部调用及不可变结算事实。存储类别：R。

使用接口与页面：Trace详情、检测；/v1和在线测试响应来源。

索引、唯一约束与关联：U(trace_id,sequence)；I(trace_id,started_at)、I(provider_model_id,started_at)；FK trace_id→trace.trace_id；历史配置逻辑关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| trace_id | varchar(128) | 是 | 无 | 所属Trace |
| sequence | integer | 是 | 无 | 从1开始连续 |
| attempt_type | varchar(32) | 是 | INITIAL | INITIAL/RETRY/CREDENTIAL_FAILOVER/FALLBACK/HALF_OPEN_PROBE |
| route_candidate_id | uuid | 条件 | NULL | 业务路由必填；独立Provider/模型/凭证检测可空 |
| provider_id | uuid | 是 | 无 | 实际路径快照ID |
| provider_model_id | uuid | 是 | 无 | 实际路径快照ID |
| credential_pool_id | uuid | 是 | 无 | 实际路径快照ID |
| credential_id | uuid | 是 | 无 | 实际路径快照ID |
| provider_name_snapshot | varchar(128) | 是 | 无 | 历史名称无秘密 |
| provider_model_name_snapshot | varchar(128) | 是 | 无 | 历史名称无秘密 |
| model_id_snapshot | varchar(128) | 是 | 无 | 历史名称无秘密 |
| credential_name_snapshot | varchar(128) | 是 | 无 | 历史名称无秘密 |
| status | varchar(16) | 是 | RUNNING | RUNNING/SUCCEEDED/FAILED/CANCELLED |
| started_at | timestamptz | 是 | 无 | 创建时间 |
| provider_started_at | timestamptz | 否 | NULL | 阶段时点 |
| response_headers_at | timestamptz | 否 | NULL | 阶段时点 |
| first_token_at | timestamptz | 否 | NULL | 阶段时点 |
| ended_at | timestamptz | 否 | NULL | 阶段时点 |
| dispatch_ms | integer | 否 | NULL | 非负阶段耗时 |
| response_header_ms | integer | 否 | NULL | 非负阶段耗时 |
| first_token_ms | integer | 否 | NULL | 非负阶段耗时 |
| total_ms | integer | 否 | NULL | 非负阶段耗时 |
| endpoint_host | varchar(255) | 是 | 无 | 无路径参数 |
| http_status | integer | 否 | NULL | 上游HTTP状态 |
| provider_request_id | varchar(256) | 否 | NULL | 受控诊断 |
| response_committed | boolean | 是 | false | 是否输出客户端 |
| finish_reason | varchar(32) | 否 | NULL | 成功原因 |
| error_code | varchar(64) | 否 | NULL | 分类 |
| error_category | varchar(64) | 否 | NULL | 分类 |
| error_stage | varchar(64) | 否 | NULL | 分类 |
| error_summary | varchar(1000) | 否 | NULL | 脱敏 |
| retryable | boolean | 是 | false | 分类结果 |
| retry_after_ms | integer | 否 | NULL | 可解析等待 |
| resolved_parameters | jsonb | 是 | {} | 模型参数及option keys不含正文 |
| input_tokens | bigint | 是 | 0 | 结算用量 |
| output_tokens | bigint | 是 | 0 | 结算用量 |
| total_tokens | bigint | 是 | 0 | 结算用量 |
| usage_source | varchar(12) | 否 | NULL | ACTUAL/ESTIMATED |
| input_price | numeric(20,8) | 是 | 无 | 调用时价格快照 |
| output_price | numeric(20,8) | 是 | 无 | 调用时价格快照 |
| price_unit | integer | 是 | 无 | 1000/1000000 |
| currency | char(3) | 是 | 无 | 调用币种 |
| input_cost | numeric(30,8) | 是 | 0 | 定点结算结果 |
| output_cost | numeric(30,8) | 是 | 0 | 定点结算结果 |
| total_cost | numeric(30,8) | 是 | 0 | 定点结算结果 |
| settled_at | timestamptz | 否 | NULL | 完成后不可变 |

### 17. trace_content_sample

用途：脱敏截断诊断样本，独立提前过期。存储类别：R。

使用接口与页面：GET Trace详情 include_diagnostics；仅诊断区域。

索引、唯一约束与关联：U(trace_id)；I(expires_at)；FK trace_id→trace.trace_id。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| trace_id | varchar(128) | 是 | 无 | 对应请求 |
| sampled_messages | jsonb | 是 | 无 | 脱敏role/content列表，只诊断有权读取 |
| sampled_response | text | 否 | NULL | 脱敏截断文本 |
| redaction_version | varchar(64) | 是 | 无 | 脱敏规则版本 |
| expires_at | timestamptz | 是 | 无 | 独立留存截止 |

### 18. route_decision

用途：未实际调用也可记录的路由判断。存储类别：I。

使用接口与页面：Trace详情route_decisions/timeine；无独立公开写接口。

索引、唯一约束与关联：U(trace_id,sequence)；FK trace_id→trace.trace_id。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| trace_id | varchar(128) | 是 | 无 | 所属请求 |
| sequence | integer | 是 | 无 | 单Trace顺序 |
| route_candidate_id | uuid | 否 | NULL | 过滤对象 |
| decision | varchar(32) | 是 | 无 | FILTERED/SELECTED/SELECTED_FOR_PROBE/QUEUED |
| reason_code | varchar(64) | 是 | 无 | 稳定原因 |
| reason_detail | varchar(1000) | 否 | NULL | 安全解释 |
| observed_status | varchar(32) | 否 | NULL | 当时运行状态 |
| observed_values | jsonb | 是 | {} | 容量/能力/优先级观察值 |

### 19. capacity_reservation

用途：容量预占和结算追踪，Redis为实时权威。存储类别：R。

使用接口与页面：Trace容量区域、策略usage；Runtime内部。

索引、唯一约束与关联：U(attempt_id)非空；I(status,expires_at)；FK trace_id→trace.trace_id；attempt_id可在外部发送前绑定。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| trace_id | varchar(128) | 是 | 无 | 所属Trace |
| attempt_id | uuid | 否 | NULL | 预占后绑定的实际尝试 |
| status | varchar(16) | 是 | ACTIVE | ACTIVE/SETTLED/RELEASED/EXPIRED |
| reserved_tokens | bigint | 是 | 无 | 输入估算+输出上限 |
| actual_tokens | bigint | 否 | NULL | 最终结算 |
| expires_at | timestamptz | 是 | 无 | Trace总截止+30秒 |
| settled_at | timestamptz | 否 | NULL | 结算和归还时点 |
| released_at | timestamptz | 否 | NULL | 结算和归还时点 |
| release_reason | varchar(64) | 否 | NULL | 正常/取消/未发送/Watchdog |
| settlement_payload | jsonb | 否 | NULL | 幂等结算意图 |
| settlement_applied | boolean | 是 | false | 共享状态是否确认应用 |

### 20. capacity_reservation_item

用途：三层维度预占明细。存储类别：I。

使用接口与页面：Trace容量表、LimitUsageSnapshot；无单独页面。

索引、唯一约束与关联：U(reservation_id,scope_type,scope_id)；FK reservation_id→capacity_reservation.id。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| reservation_id | uuid | 是 | 无 | 预占与作用对象 |
| scope_id | uuid | 是 | 无 | 预占与作用对象 |
| scope_type | varchar(24) | 是 | 无 | MODEL_ALIAS/PROVIDER_MODEL/CREDENTIAL |
| policy_ids | jsonb | 是 | [] | 贡献上限的策略ID列表 |
| window_start | timestamptz | 是 | 无 | Unix对齐60秒原窗口 |
| reserved_rpm | integer | 是 | 1 | 每实际Attempt各1 |
| reserved_concurrency | integer | 是 | 1 | 每实际Attempt各1 |
| reserved_tokens | bigint | 是 | 无 | 三层同Token预算 |

### 21. queue_entry

用途：Alias FIFO等待事实。存储类别：R。

使用接口与页面：GET策略queue；Trace时间线。

索引、唯一约束与关联：U(alias_id,sequence)；I(alias_id,status,sequence)；FK trace_id→trace.trace_id。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| trace_id | varchar(128) | 是 | 无 | 所属Trace |
| alias_id | uuid | 是 | 无 | 队列分区 |
| sequence | bigint | 是 | 无 | 共享单调队列号 |
| blocking_policy_ids | jsonb | 是 | [] | 阻塞策略ID |
| estimated_tokens | bigint | 是 | 无 | 入队预算 |
| status | varchar(16) | 是 | WAITING | WAITING/ACQUIRED/TIMEOUT/REJECTED/CANCELLED |
| enqueued_at | timestamptz | 是 | 无 | 队列开始截止 |
| deadline_at | timestamptz | 是 | 无 | 队列开始截止 |
| acquired_at | timestamptz | 否 | NULL | 取得/终止 |
| ended_at | timestamptz | 否 | NULL | 取得/终止 |
| wake_reason | varchar(64) | 否 | NULL | 唤醒或失败原因 |
| error_code | varchar(64) | 否 | NULL | 唤醒或失败原因 |

### 22. recovery_decision

用途：失败后的不可变恢复动作。存储类别：I。

使用接口与页面：Trace时间线、可靠性recovery-decisions。

索引、唯一约束与关联：U(trace_id,sequence)；FK trace_id→trace.trace_id；FK source_attempt_id→attempt.id。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| trace_id | varchar(128) | 是 | 无 | 所属Trace |
| sequence | integer | 是 | 无 | 决策序号 |
| source_attempt_id | uuid | 是 | 无 | 失败来源 |
| action | varchar(32) | 是 | 无 | RETRY/CREDENTIAL_FAILOVER/FALLBACK/FAIL |
| reason_code | varchar(64) | 是 | 无 | 错误矩阵原因 |
| scheduled_delay_ms | integer | 是 | 0 | 本次退避 |
| target_route_candidate_id | uuid | 否 | NULL | FAIL无目标 |
| target_credential_id | uuid | 否 | NULL | FAIL无目标 |
| retries_used | integer | 是 | 0 | 决策后的累计预算 |
| credential_failovers_used | integer | 是 | 0 | 决策后的累计预算 |
| fallbacks_used | integer | 是 | 0 | 决策后的累计预算 |
| remaining_timeout_ms | integer | 是 | 无 | 剩余总预算 |

### 23. circuit_state

用途：SQL运行镜像与查询快照，Redis实时执行CAS。存储类别：R。

使用接口与页面：/admin/circuits；熔断页、概览异常。

索引、唯一约束与关联：U(provider_model_id,credential_id)；I(state,updated_at)；逻辑配置关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| provider_model_id | uuid | 是 | 无 | 路径键C-008 |
| credential_id | uuid | 是 | 无 | 路径键C-008 |
| state | varchar(12) | 是 | CLOSED | CLOSED/OPEN/HALF_OPEN |
| state_version | bigint | 是 | 1 | 共享CAS版本 |
| policy_snapshot | jsonb | 是 | {} | 当前评估阈值及policy_id/snapshot_no |
| window_started_at | timestamptz | 否 | NULL | 窗口起点 |
| request_count | integer | 是 | 0 | 有效窗口和探测 |
| failure_count | integer | 是 | 0 | 有效窗口和探测 |
| probe_inflight | integer | 是 | 0 | 有效窗口和探测 |
| probe_success_count | integer | 是 | 0 | 有效窗口和探测 |
| opened_at | timestamptz | 否 | NULL | OPEN时点 |
| next_probe_at | timestamptz | 否 | NULL | OPEN时点 |
| open_source | varchar(16) | 否 | NULL | AUTO/MANUAL |
| last_reason | varchar(1000) | 否 | NULL | 安全摘要 |
| last_applied_command_id | uuid | 否 | NULL | 幂等人工命令 |

### 24. circuit_event

用途：自动或人工熔断转换事实。存储类别：I。

使用接口与页面：circuits/{id}/events、Trace时间线。

索引、唯一约束与关联：U(event_key)；I(circuit_id,created_at desc)；trigger_trace_id逻辑关联可空。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| event_key | varchar(128) | 是 | 无 | 跨Redis/SQL重放去重键 |
| circuit_id | uuid | 是 | 无 | 逻辑状态引用 |
| from_state | varchar(12) | 是 | 无 | CLOSED/OPEN/HALF_OPEN |
| to_state | varchar(12) | 是 | 无 | CLOSED/OPEN/HALF_OPEN |
| trigger_type | varchar(32) | 是 | 无 | AUTO_THRESHOLD/PROBE_SUCCESS/PROBE_FAILURE/MANUAL_OPEN/MANUAL_RECOVER |
| trigger_trace_id | varchar(128) | 否 | NULL | 人工可无Trace |
| command_id | uuid | 否 | NULL | 人工命令 |
| error_code | varchar(64) | 否 | NULL | 触发错误 |
| reason | varchar(1000) | 否 | NULL | 原因 |
| occurred_at | timestamptz | 是 | 无 | 共享状态真实变化时间 |

### 25. circuit_command

用途：跨SQL/Redis人工操作可靠交接。存储类别：R。

使用接口与页面：circuits open/recover/probe返回pending_command；熔断操作弹窗。

索引、唯一约束与关联：U(request_id)；I(status,created_at)；逻辑circuit_id关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| request_id | varchar(128) | 是 | 无 | 去重操作ID |
| circuit_id | uuid | 是 | 无 | 目标 |
| action | varchar(24) | 是 | 无 | MANUAL_OPEN/MANUAL_RECOVER/PROBE_NOW |
| expected_state_version | bigint | 是 | 无 | 请求CAS版本 |
| reason | varchar(500) | 是 | 无 | 操作原因 |
| open_seconds | integer | 否 | NULL | 人工打开时长 |
| operator_id | varchar(128) | 是 | 无 | 操作者 |
| status | varchar(16) | 是 | PENDING | PENDING/APPLIED/SUCCEEDED/FAILED |
| applied_at | timestamptz | 否 | NULL | 共享应用与完成 |
| completed_at | timestamptz | 否 | NULL | 共享应用与完成 |
| error_code | varchar(64) | 否 | NULL | 最终失败 |

### 26. usage_aggregation_event

用途：Trace最终事务Outbox事件与租约。存储类别：R。

使用接口与页面：内部聚合；概览延迟指标。

索引、唯一约束与关联：U(trace_id)；I(status,next_retry_at)；逻辑trace_id保留至事件成功与清理。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| trace_id | varchar(128) | 是 | 无 | 每Trace一事件 |
| status | varchar(16) | 是 | PENDING | PENDING/PROCESSING/SUCCEEDED/FAILED |
| locked_by | varchar(128) | 否 | NULL | worker |
| locked_at | timestamptz | 否 | NULL | 120秒租约与退避 |
| next_retry_at | timestamptz | 否 | NULL | 120秒租约与退避 |
| completed_at | timestamptz | 否 | NULL | 120秒租约与退避 |
| lock_generation | bigint | 是 | 0 | 接管递增fencing |
| retry_count | integer | 是 | 0 | 10次告警继续重试 |
| error_code | varchar(64) | 否 | NULL | 聚合错误 |
| error_summary | varchar(1000) | 否 | NULL | 安全摘要 |

### 27. usage_aggregate

用途：HOUR/DAY多维可复算聚合。存储类别：R。

使用接口与页面：/admin/usage/*、overview趋势；Usage页面。

索引、唯一约束与关联：U(granularity,bucket_start,dimension_key,currency)；I(application,granularity,bucket_start)、I(alias_id,granularity,bucket_start)、I(provider_id,granularity,bucket_start)；不FK明细。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| granularity | varchar(4) | 是 | 无 | HOUR/DAY |
| bucket_start | timestamptz | 是 | 无 | 配置时区桶转换UTC |
| bucket_end | timestamptz | 是 | 无 | 配置时区桶转换UTC |
| dimension_key | char(64) | 是 | 无 | 规范化完整维度SHA256 |
| application | varchar(64) | 是 | 无 | 身份范围 |
| project | varchar(64) | 否 | NULL | 维度 |
| tenant | varchar(64) | 否 | NULL | 维度 |
| alias_id | uuid | 否 | NULL | 请求贡献最终路径/执行贡献实际路径 |
| provider_id | uuid | 否 | NULL | 请求贡献最终路径/执行贡献实际路径 |
| provider_model_id | uuid | 否 | NULL | 请求贡献最终路径/执行贡献实际路径 |
| credential_pool_id | uuid | 否 | NULL | 请求贡献最终路径/执行贡献实际路径 |
| credential_id | uuid | 否 | NULL | 请求贡献最终路径/执行贡献实际路径 |
| trace_status | varchar(24) | 是 | 无 | Trace终态 |
| error_code | varchar(64) | 否 | NULL | Trace错误用于筛选 |
| usage_source | varchar(12) | 否 | NULL | ACTUAL/ESTIMATED；无执行可空 |
| requested_stream | boolean | 是 | false | 请求模式 |
| currency | char(3) | 是 | 无 | 币种独立 |
| dimension_names | jsonb | 是 | {} | 显示名快照 |
| request_count | bigint | 是 | 0 | 请求贡献指标 |
| success_count | bigint | 是 | 0 | 请求贡献指标 |
| failure_count | bigint | 是 | 0 | 请求贡献指标 |
| cancelled_count | bigint | 是 | 0 | 请求贡献指标 |
| stream_interrupted_count | bigint | 是 | 0 | 请求贡献指标 |
| queued_count | bigint | 是 | 0 | 请求贡献指标 |
| stream_count | bigint | 是 | 0 | 请求贡献指标 |
| attempt_count | bigint | 是 | 0 | 执行贡献指标 |
| initial_count | bigint | 是 | 0 | 执行贡献指标 |
| retry_count | bigint | 是 | 0 | 执行贡献指标 |
| credential_failover_count | bigint | 是 | 0 | 执行贡献指标 |
| fallback_count | bigint | 是 | 0 | 执行贡献指标 |
| half_open_probe_count | bigint | 是 | 0 | 执行贡献指标 |
| input_tokens | bigint | 是 | 0 | 实际估算互斥 |
| output_tokens | bigint | 是 | 0 | 实际估算互斥 |
| total_tokens | bigint | 是 | 0 | 实际估算互斥 |
| actual_input_tokens | bigint | 是 | 0 | 实际估算互斥 |
| actual_output_tokens | bigint | 是 | 0 | 实际估算互斥 |
| estimated_input_tokens | bigint | 是 | 0 | 实际估算互斥 |
| estimated_output_tokens | bigint | 是 | 0 | 实际估算互斥 |
| input_cost | numeric(30,8) | 是 | 0 | 执行贡献费用 |
| output_cost | numeric(30,8) | 是 | 0 | 执行贡献费用 |
| total_cost | numeric(30,8) | 是 | 0 | 执行贡献费用 |
| total_ms_sum | bigint | 是 | 0 | 可合并均值所需量 |
| total_ms_count | bigint | 是 | 0 | 可合并均值所需量 |
| first_token_ms_sum | bigint | 是 | 0 | 可合并均值所需量 |
| first_token_ms_count | bigint | 是 | 0 | 可合并均值所需量 |
| queued_ms_sum | bigint | 是 | 0 | 可合并均值所需量 |
| latency_histogram | jsonb | 是 | {} | 毫秒整数值→计数，不存桶P95后求平均 |
| first_token_histogram | jsonb | 是 | {} | 毫秒整数值→计数，不存桶P95后求平均 |

### 28. config_draft_state

用途：全局草稿修订及发布锁单例。存储类别：R。

使用接口与页面：/admin/config/draft-state；顶部/草稿/发布。

索引、唯一约束与关联：U(singleton_key) CHECK=1；逻辑快照和发布关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| singleton_key | integer | 是 | 1 | 唯一行 |
| base_snapshot_no | bigint | 是 | 0 | 草稿基线 |
| draft_revision | bigint | 是 | 0 | 每配置事务+1 |
| status | varchar(16) | 是 | EDITABLE | EDITABLE/PUBLISHING |
| publish_record_id | uuid | 否 | NULL | 持锁发布 |
| lock_acquired_at | timestamptz | 否 | NULL | 恢复协调定位 |
| change_count | integer | 是 | 0 | 与draft_change同事务维护 |

### 29. draft_change

用途：每个对象当前脱敏差异。存储类别：R。

使用接口与页面：/admin/config/draft-changes；草稿页。

索引、唯一约束与关联：U(entity_type,entity_id)；I(modified_by,updated_at)；实体多态逻辑关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| entity_type | varchar(32) | 是 | 无 | 配置实体枚举 |
| entity_id | uuid | 是 | 无 | 对象ID |
| entity_name | varchar(128) | 是 | 无 | 展示名 |
| change_type | varchar(12) | 是 | 无 | CREATE/UPDATE/ENABLE/DISABLE/DELETE |
| changed_fields | jsonb | 是 | [] | FieldChange数组，秘密仅changed=true |
| modified_by | varchar(128) | 是 | 无 | 操作者 |
| entity_version | bigint | 是 | 无 | 差异对应修订 |
| draft_revision | bigint | 是 | 无 | 差异对应修订 |

### 30. config_validation

用途：固定草稿校验凭据。存储类别：R。

使用接口与页面：/admin/config/validate/publish；校验确认页。

索引、唯一约束与关联：U(validation_id)；I(expires_at)；逻辑base/target快照关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| validation_id | uuid | 是 | 无 | 外部校验ID |
| base_snapshot_no | bigint | 是 | 无 | 校验版本 |
| target_snapshot_no | bigint | 是 | 无 | 校验版本 |
| draft_revision | bigint | 是 | 无 | 校验版本 |
| content_checksum | char(64) | 是 | 无 | 规范化SHA256 |
| status | varchar(12) | 是 | 无 | PASSED/FAILED/EXPIRED |
| error_count | integer | 是 | 0 | Issue数 |
| warning_count | integer | 是 | 0 | Issue数 |
| validated_at | timestamptz | 是 | 无 | 默认10分钟有效假设 |
| expires_at | timestamptz | 是 | 无 | 默认10分钟有效假设 |
| validated_by | varchar(128) | 是 | 无 | 管理员 |
| used_by_publish_id | uuid | 否 | NULL | 单次发布绑定 |
| change_summary | jsonb | 是 | [] | 分别摘要/ID/实例能力快照 |
| affected_alias_ids | jsonb | 是 | [] | 分别摘要/ID/实例能力快照 |
| target_instances | jsonb | 是 | [] | 分别摘要/ID/实例能力快照 |

### 31. config_validation_issue

用途：字段级发布阻断或警告。存储类别：I。

使用接口与页面：ValidationResult.issues；发布问题列表。

索引、唯一约束与关联：I(validation_id,severity,entity_type)；FK validation_id→config_validation.validation_id。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| validation_id | uuid | 是 | 无 | 所属校验 |
| severity | varchar(8) | 是 | 无 | ERROR/WARNING |
| code | varchar(64) | 是 | 无 | PRD发布校验矩阵代码 |
| entity_type | varchar(32) | 是 | 无 | 对象类别 |
| entity_id | uuid | 否 | NULL | 实例/全局问题可空 |
| entity_name | varchar(128) | 否 | NULL | 安全名称 |
| field_path | varchar(256) | 否 | NULL | 字段定位 |
| message | varchar(1000) | 是 | 无 | 安全问题与改法 |
| suggestion | varchar(1000) | 是 | 无 | 安全问题与改法 |
| related_entity_ids | jsonb | 是 | [] | 引用对象ID |

### 32. config_snapshot

用途：不可变配置内容和可迁移状态。存储类别：R。

使用接口与页面：内部snapshot读取、管理summary；发布详情。

索引、唯一约束与关联：U(snapshot_no)；U(status) WHERE status=ACTIVE；I(created_at)；不含密钥与运行状态。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| snapshot_no | bigint | 是 | 无 | 初始0，序列分配不回收 |
| schema_version | integer | 是 | 1 | 结构版本 |
| status | varchar(16) | 是 | CREATED | CREATED/ACTIVE/SUPERSEDED/ABORTED |
| content | jsonb | 是 | 无 | 规范化可发布配置白名单 |
| content_checksum | char(64) | 是 | 无 | SHA256 |
| content_summary | jsonb | 是 | {} | 对象数量安全摘要 |
| activated_at | timestamptz | 否 | NULL | 原子激活时点 |
| created_by | varchar(128) | 是 | 无 | 创建者 |

### 33. publish_record

用途：发布协调持久阶段与最终结果。存储类别：R。

使用接口与页面：/admin/config/publish-records；发布进度/历史。

索引、唯一约束与关联：U(validation_id)；I(created_at,status)；FK validation_id→config_validation.validation_id；逻辑snapshot_no。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| validation_id | uuid | 是 | 无 | 合法校验绑定 |
| from_snapshot_no | bigint | 是 | 无 | 发布固定版本 |
| target_snapshot_no | bigint | 是 | 无 | 发布固定版本 |
| draft_revision | bigint | 是 | 无 | 发布固定版本 |
| status | varchar(24) | 是 | PREPARING | PREPARING/ACTIVATING/SUCCEEDED/PARTIAL_FAILED/FAILED |
| published_by | varchar(128) | 是 | 无 | 操作者 |
| publish_note | varchar(500) | 否 | NULL | 发布说明 |
| acknowledged_warning_ids | jsonb | 是 | [] | 全部确认IDs |
| target_instance_ids | jsonb | 是 | [] | 固定ONLINE集 |
| completed_at | timestamptz | 否 | NULL | 首轮结束/最终收敛 |
| converged_at | timestamptz | 否 | NULL | 首轮结束/最终收敛 |
| duration_ms | bigint | 否 | NULL | 首轮时长 |
| error_code | varchar(64) | 否 | NULL | 失败码 |
| error_summary | varchar(1000) | 否 | NULL | 脱敏原因 |

### 34. publish_instance_result

用途：每次发布目标实例加载结果。存储类别：R。

使用接口与页面：publish-record详情；实例进度。

索引、唯一约束与关联：U(publish_id,instance_id)；FK publish_id→publish_record.id；instance逻辑关联。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| publish_id | uuid | 是 | 无 | 发布和固定目标 |
| instance_id | uuid | 是 | 无 | 发布和固定目标 |
| from_snapshot_no | bigint | 是 | 无 | 前后版本 |
| target_snapshot_no | bigint | 是 | 无 | 前后版本 |
| status | varchar(16) | 是 | PENDING | PENDING/PREPARING/READY/ACTIVATING/LOADED/FAILED/TIMED_OUT |
| retry_count | integer | 是 | 0 | 加载重试 |
| load_duration_ms | bigint | 否 | NULL | 实例耗时 |
| reported_at | timestamptz | 否 | NULL | 报告时序水位 |
| error_code | varchar(64) | 否 | NULL | 错误 |
| error_summary | varchar(1000) | 否 | NULL | 脱敏 |

### 35. runtime_instance

用途：运行实例身份能力和心跳。存储类别：R。

使用接口与页面：heartbeat、runtime-instances；发布实例面板。

索引、唯一约束与关联：U(instance_id)；I(status,last_heartbeat_at)；只服务身份可写。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 每次状态成功更新 |
| instance_id | uuid | 是 | 无 | 部署唯一ID |
| runtime_mode | varchar(24) | 是 | 无 | EMBEDDED/STANDALONE_SERVER |
| runtime_version | varchar(64) | 是 | 无 | 制品版本 |
| application | varchar(64) | 是 | 无 | 实例应用 |
| zone | varchar(64) | 否 | NULL | 可选区域 |
| supported_schema_versions | jsonb | 是 | [] | 能力数组 |
| loaded_adapter_types | jsonb | 是 | [] | 能力数组 |
| active_snapshot_no | bigint | 是 | 0 | 实例当前活动版本 |
| accepting_requests | boolean | 是 | false | 就绪前false |
| status | varchar(12) | 是 | OFFLINE | ONLINE/DRAINING/STALE/OFFLINE |
| last_heartbeat_at | timestamptz | 否 | NULL | 服务接收时间 |
| last_error_code | varchar(64) | 否 | NULL | 加载错误 |
| last_error_summary | varchar(1000) | 否 | NULL | 安全摘要 |

### 36. access_credential

用途：Standalone即时业务Token摘要与范围。存储类别：S。

使用接口与页面：/admin/access-credentials与/v1鉴权；访问凭证页面。

索引、唯一约束与关联：U(name)活行；U(token_hash)；I(token_prefix)、I(application,deleted_at)；不存token_value。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 创建时间 |
| updated_at | timestamptz | 是 | 事务now | 即时操作更新时间 |
| version | bigint | 是 | 1 | 乐观锁 |
| deleted_at | timestamptz | 否 | NULL | 即时不可恢复软删除 |
| name | varchar(64) | 是 | 无 | 全局唯一 |
| application | varchar(64) | 是 | 无 | 1—64 |
| token_prefix | varchar(8) | 是 | 无 | 完整Token前8字符仅索引非唯一 |
| token_hash | bytea | 是 | 无 | HMAC-SHA256摘要32字节 |
| token_hash_version | integer | 是 | 1 | 外部pepper版本 |
| masked_value | varchar(128) | 是 | 无 | 安全掩码 |
| ip_allowlist | jsonb | 是 | [] | 最多100IPv4/IPv6/CIDR |
| expires_at | timestamptz | 否 | NULL | 到期读取计算EXPIRED |
| enabled | boolean | 是 | true | 即时生效 |
| rotation_generation | bigint | 是 | 1 | 轮换+1 |
| issued_at | timestamptz | 是 | 无 | 本代次签发 |
| rotated_at | timestamptz | 否 | NULL | 活动摘要 |
| last_used_at | timestamptz | 否 | NULL | 活动摘要 |
| last_used_ip_masked | varchar(128) | 否 | NULL | 脱敏来源 |

### 37. access_credential_alias

用途：访问凭证Alias白名单，无行表示全部。存储类别：I。

使用接口与页面：Access编辑、/v1/models；访问凭证表单。

索引、唯一约束与关联：U(access_credential_id,alias_id)；FK access_credential_id→access_credential.id；alias逻辑引用当前ACTIVE并阻止删除。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| access_credential_id | uuid | 是 | 无 | 访问凭证与允许Alias |
| alias_id | uuid | 是 | 无 | 访问凭证与允许Alias |

### 38. audit_log

用途：脱敏管理与诊断访问审计。存储类别：I。

使用接口与页面：/admin/audit-logs及export；审计页与对象摘要。

索引、唯一约束与关联：I(created_at desc,id)、I(entity_type,entity_id,created_at)、I(request_id)、I(operator_id,created_at)；历史对象逻辑引用。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| request_id | varchar(128) | 是 | 无 | 跨操作关联 |
| operator_id | varchar(128) | 是 | 无 | 当前身份 |
| action | varchar(64) | 是 | 无 | CREATE/UPDATE/ENABLE/DISABLE/DELETE/ROTATE/CHECK/REVERT/VALIDATE/PUBLISH/DIAGNOSTIC_READ/CIRCUIT_COMMAND及具体受控动作 |
| entity_type | varchar(32) | 是 | 无 | 对象类型 |
| entity_id | varchar(128) | 否 | NULL | 配置/Trace/全局 |
| result | varchar(16) | 是 | 无 | SUCCEEDED/FAILED；人工命令受理用独立action不声称状态已变 |
| changes | jsonb | 是 | [] | 脱敏FieldChange |
| error_code | varchar(64) | 否 | NULL | 失败码 |
| error_summary | varchar(1000) | 否 | NULL | 安全错误 |
| source_mode | varchar(32) | 是 | 无 | 管理部署模式 |
| source_ip_masked | varchar(128) | 否 | NULL | 安全来源摘要 |

### 39. retention_impact

用途：绑定目标参数的限时清理影响估算。存储类别：I。

使用接口与页面：runtime-config/retention-impact；影响确认弹窗。

索引、唯一约束与关联：U(impact_version)；I(expires_at)；无需FK业务明细。U为唯一索引，I为普通索引；活行表示deleted_at IS NULL。未标注的普通索引均使用B-tree。

| 字段 | 类型 | 必填 | 默认值 | 说明/约束 |
|---|---|---|---|---|
| id | uuid | 是 | 应用生成 | 主键 |
| created_at | timestamptz | 是 | 事务now | 不可变创建时间 |
| impact_version | uuid | 是 | 无 | 影响确认票据 |
| draft_revision | bigint | 是 | 无 | 绑定草稿修订 |
| target_values | jsonb | 是 | 无 | 4项留存目标 |
| counts | jsonb | 是 | 无 | 将删Trace/Usage/Audit/Sample数 |
| estimated_at | timestamptz | 是 | 无 | 有效期10分钟 |
| expires_at | timestamptz | 是 | 无 | 有效期10分钟 |
| estimated_by | varchar(128) | 是 | 无 | 操作者 |

## 3. JSON列及派生响应字典

JSON列只用于有边界的嵌套结构，不代替可索引业务主字段。所有结构必须在后端DTO/校验器中定义，禁止任意Map直接落库。

| 结构 | 必需字段、类型与限制 | 使用位置 |
|---|---|---|
| FieldChange | field_path:string≤256、before/after:非敏感JSON标量或数组、changed:boolean；敏感字段只存field_path和changed，不存值或引用 | draft_change.changed_fields、audit_log.changes |
| request_summary | message_count/system_message_count/user_message_count/assistant_message_count/input_char_count:非负整数；temperature/top_p:可空decimal；max_tokens:可空bigint；stop_count:整数；provider_option_keys:string[]；不存messages/stop正文/option值 | trace.request_summary |
| resolved_parameters | temperature/top_p/max_tokens/stop_count/tokenizer_family/provider_option_keys；每个Attempt记录实际解析值，不把不同候选参数写成同一Trace参数 | attempt.resolved_parameters |
| usage | input_tokens/output_tokens/total_tokens:bigint，source:ACTUAL或ESTIMATED，total=input+output | provider_check_record.usage |
| batch command | provider_model_ids:uuid[]≤100；credential_id:uuid可空；mode枚举；timeout_ms整数 | batch_check_job.command |
| settlement_payload | reservation_id、attempt_id、items[{scope_type,scope_id,window_start,reserved_tokens,actual_tokens,request_sent,release_concurrency}]；结算键reservation_id且单次应用 | capacity_reservation.settlement_payload |
| policy_snapshot | policy_id可空、snapshot_no、window_seconds、min_requests、failure_rate、open_seconds、half_open_probes、half_open_successes | circuit_state.policy_snapshot |
| target_instances | [{instance_id,runtime_mode,runtime_version,supported_schema_versions:int[],loaded_adapter_types:string[]}] | config_validation.target_instances |
| change_summary | {entity_type,create_count,update_count,enable_count,disable_count,delete_count}数组；其余数均非负 | config_validation.change_summary |
| snapshot.content | schema_version、providers[]、credential_pools[]、credentials[]、provider_models[]、model_aliases[]、route_candidates[]、limit_policies[]、reliability_policies[]、runtime_config对象；每对象仅配置白名单字段和id | config_snapshot.content |
| dimension_names | alias/provider/provider_model/credential_pool/credential:string可空；Credential序列化仍有字段权限 | usage_aggregate.dimension_names |
| latency histogram | JSON对象，键为非负整数毫秒十进制字符串，值非负bigint计数；Trace.total_ms范围0—600000，逐值精确计数；first_token同式 | usage_aggregate两直方图 |
| target_values | trace_retention_days、usage_retention_days、audit_retention_days、diagnostic_sample_retention_days:正整数 | retention_impact.target_values |
| counts | trace、usage、audit、sample:非负bigint | retention_impact.counts |

Snapshot排除created_at/updated_at/deleted_at等工作集元数据、所有秘密列、完整secret_ref、运行健康、DraftChange、AuditLog及Access Credential。可保留配置version用于追溯；密钥通过credential_id从受保护仓储即时解析。RuntimeConfig仅可发布参数，排除current_snapshot_no、published_at、timezone_locked；锁定标志是全局事实，不能被撤销全部改回false。checksum用固定键序、固定数组ID排序、UTC时间、十进制规范序列化后的UTF-8字节计算，不直接对数据库JSON文本表示求hash。

派生字段不单独落库：Pool.runtime_status由enabled与Credential健康计数；Candidate.runtime_status由快照引用/健康/容量/Circuit计算；draft_changed来自差异；revertable和blockers实时查引用；Access.status按deleted_at→expires_at→enabled计算DELETED/EXPIRED/DISABLED/ACTIVE；Trace.content_sample_status按采样开关/是否命中/留存计算DISABLED/NOT_SAMPLED/AVAILABLE/EXPIRED（C-015补充）。这些值不能出现在配置写命令中。

## 4. 约束、状态和原子操作

配置关系以FK阻止真实孤儿，以服务层/发布校验控制enabled、软删除和跨Provider一致性；FK本身不能验证软删除行可引用。limit_policy.scope_id多态逻辑关联须按scope_type查询对应表；不能用一张通用对象表替代现有模块。Access白名单必须指向当前ACTIVE Alias，并在Alias删除影响分析中计入。

同对象版本与全局revision必须在同事务递增。DraftChange唯一(entity_type,entity_id)，diff为空删除差异行；新建再删若无引用可去掉工作草稿，秘密存储也需安全清理；轮换不回滚到旧Secret。撤销全部按依赖顺序恢复父对象再子对象、删除子对象再父对象，更新version并保持秘密轮换和即时Access不受影响。

初始数据只有runtime_config默认值、config_draft_state revision0/base0、schema_version1且snapshot_no0的ACTIVE空配置（content只有默认RuntimeConfig）。不植入真实Provider、模型、Token、用户或密码。首次实例启动先加载0快照并就绪管理能力，业务无Alias按404处理。

快照状态字段可迁移，content/checksum不可变。唯一ACTIVE用部分唯一索引兜底；同事务先将旧ACTIVE改SUPERSEDED再激活目标并更新指针、草稿基线，事务外无中间可见状态。ConfigValidation.used_by_publish_id/PublishRecord.validation_id共同防重复发布。发布协调重启从持久化phase恢复，不只依赖内存锁。

Trace/Attempt允许运行阶段追加字段，终态与settled_at写入后拒绝改写Usage/价格/最终路径。final_attempt_id必须属于本Trace，由服务层或约束触发校验；sequence唯一且连续性由Runtime保证。所有关联实体错误不允许“补造”不存在的Attempt。预占到外部发送之间失败可有reservation无Attempt，SQL接受该空引用且必须最终RELEASED。

### 4.1 Redis/内存运行键契约

| 键语义 | 字段和原子操作 | 生命周期 |
|---|---|---|
| capacity:scope_type:scope_id:window_start | rpm_used、tpm_reserved、tpm_settled；收集所有层一次原子预占/拒绝 | 分钟结束后保留至最长Trace和30秒宽限完成；不能窗口刚结束就删 |
| concurrency:scope_type:scope_id | active计数 | 预占+1，终止幂等-1，不允许负数 |
| reservation:uuid | status、trace_id、attempt_id、items、expires_at、settled_tokens、applied结算标识 | 与SQL追踪一致；清理前保留幂等终态至最长重放窗口 |
| queue:alias_id | 递增sequence、等待项有序集合、deadline、owner lease | FIFO取得需原子校验队首和完整容量；一次摘除 |
| circuit:model_id:credential_id | state、state_version、window计数、probe名额、next_probe_at、last_applied_command_id | 状态迁移CAS；SQL只读镜像不可作热路径判断 |
| pool:pool_id:cursor | ROUND_ROBIN游标原子递增 | 成员来自固定Trace快照，游标模当前可用集合 |

集群相关键全部使用同一容量命名空间hash tag（部署单主/同slot），保持跨Alias/Model/Credential一次原子执行。单机内存实现保持同样接口及原子边界。新预占失败关闭，不退化成每实例私有计数。SQL落库失败和Redis结算失败通过reservation_id意图及人工命令表重放；状态不明时readiness不允许新调用。具体持续故障持久保障见C-016。

Watchdog：到expires_at → 查owner心跳及Attempt租约 → 仍RUNNING且有效不回收 → 确认失联的Attempt按统一终止服务最终化 → 幂等释放 → 保存EXPIRED和WATCHDOG_EXPIRED指标。不能只看创建时间释放仍活跃调用，也不能永久跳过失联RUNNING记录。

### 4.2 聚合完整维度与P95

dimension_key输入固定顺序为application、project、tenant、alias_id、provider_id、provider_model_id、credential_pool_id、credential_id、trace_status、error_code、usage_source、requested_stream；null使用规范化JSON null，不能与空字符串混用；currency另列纳入唯一键。应用范围是实际列，不能只保存不可查询hash。

请求贡献：只有Trace计数、成功失败取消/排队/流式、耗时与直方图；使用final_attempt路径及usage_source，无Attempt则路径/source为空。执行贡献：只有Attempt类型计数、实际/估算Token、成本，路径为各Attempt真实路径。请求贡献不加Token，执行贡献不加request_count和请求耗时。同币种/维度合并可避免Fallback把请求数翻倍。

聚合器锁定事件并检查lock_generation，用同一事务锁目标聚合行、加HOUR/DAY指标、标SUCCEEDED；失败全部回滚。120秒接管时先递增generation，旧worker提交前必须对比generation，不能两个worker提交。退避1/2/4/8/16分钟后每30分钟，10次告警且继续保留。

P95合并毫秒直方图后按nearest-rank ceil(0.95×count)取对应毫秒，均值用sum/count，空分母返回null。不把HOUR/DAY同时计入summary；请求granularity选定一套。查询时间必须桶对齐：HOUR按整点、DAY按配置时区自然日，服务端返回解析后边界（C-017）。Trace详情仍可精确时间查询；非对齐聚合查询不能假装提供精确明细结果。DST重叠小时用UTC bucket_start区分，日桶按本地日历而非固定86400秒。

直方图最多600001个取值，稀疏存储。需在BE-060检查大维度下JSON行膨胀；若不达标，登记C-017选择固定误差直方图并同步精度验收，禁止暗换为平均P95。

## 5. 迁移、备份与清理

迁移顺序：公共草稿/审计 → Provider/Pool/Credential与秘密 → Model/Alias/Candidate/策略 → Trace/Attempt及明细 → 聚合 → 发布/实例/Access。循环逻辑引用（snapshot指针、final_attempt）在服务层核对，避免创建循环必填FK。每个迁移只承担一个结构目标；默认schema-mode=VALIDATE，MIGRATE仅在部署显式开启时执行产品schema锁内的顺序迁移。

不可修改已发布迁移校验值；增加新列先可空/带安全默认，分批回填，最后约束；运行版本能读旧schema时才允许滚动升级。迁移失败不得启动半就绪实例；恢复从迁移前备份验证，破坏性变更不写自动数据丢失回滚。schema版本、快照schema_version和产品版本分别管理。

Trace清理按started_at与当前发布留存，每批≤1000；仅聚合SUCCEEDED且终态可删。顺序sample/decision/recovery/queue/reservation item/reservation/circuit触发明细/attempt/trace/event，手工Circuit事件按审计留存独立清理，不能因无Trace永远保留。Trace主表final_attempt逻辑关联不会阻止该顺序。事件成功后可随对应Trace清理；Usage行不FK Trace并按bucket_end及自身留存清理。检测/批量任务默认与Trace留存一致，命令最终状态和校验票据默认30天，未完成任务不删（C-018）。

Trace ID唯一只覆盖仍保留的Trace；PRD要求“已存在”重复拒绝，不承诺超出留存后全球永不复用。外部客户端始终使用新ID；需要跨留存永久唯一需产品另行确认，不能静默保留无限ID表。

快照保留最近100个成功版本及近365天发布相关快照的并集，并额外保护ACTIVE、草稿base、非终态发布、运行中Trace所引用版本；0快照可始终保留。历史名称/价格存Attempt，配置删除不丢历史显示。主加密密钥与pepper单独备份；数据库备份只包含密文和摘要，不记录明文。恢复演练验证秘密仍可解析、活动快照一致、未完成事件不重复聚合。

## 6. 数据库执行任务包

每包6项，全部为执行模型后续工作。本轮不生成迁移脚本。任务验收至少在实际PostgreSQL环境运行约束和事务检查；Redis相关用真实原子存储验证，不能以Mock证明跨实例容量正确。每包测试、自检、勾选、Git提交，SQL变更需后端审查。

## DB-P01 基础结构（6项）

- [ ] 任务编号：DB-001
  模块：基础结构；目标：公共字段与类型契约。
  数据表/字段范围：39表公共列、ID、UTC、decimal/bigint接口规则；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-001/003、FE-P01。
  实现说明：将本文件字段映射为迁移设计，确认null与0、软删唯一及schema。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：必填/默认与接口一致，无金额浮点。
  测试要求：类型往返与边界数值；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-002
  模块：基础结构；目标：Schema迁移版本与初始快照。
  数据表/字段范围：runtime_config、config_snapshot、config_draft_state；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-003/006。
  实现说明：创建独立schema和最小0快照，不植入凭证或业务数据。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：重复启动不重复种子，只有一个ACTIVE。
  测试要求：空库安装、重复MIGRATE、VALIDATE缺表；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-003
  模块：基础结构；目标：审计与事务底座。
  数据表/字段范围：audit_log；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-005、FE-048。
  实现说明：请求关联索引、脱敏changes与成功/失败写入事务设计。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：成功审计失败使配置回滚，失败审计独立。
  测试要求：事务回滚和Secret扫描；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-004
  模块：基础结构；目标：草稿锁与差异唯一。
  数据表/字段范围：config_draft_state、draft_change；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-006/037。
  实现说明：单例锁、对象差异唯一、revision单调，PUBLISHING拒绝写。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：并发旧version不覆盖、失败revision不增。
  测试要求：双连接竞争与回滚；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-005
  模块：基础结构；目标：配置引用与删除规则。
  数据表/字段范围：配置表逻辑/硬FK与deleted_at；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-010/012/018。
  实现说明：定义活行引用验证，历史逻辑ID不级联删Trace。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：父对象有引用删除被拒，历史记录可读。
  测试要求：软删引用与同名新ID；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-006
  模块：基础结构；目标：数据库访问与查询权限边界。
  数据表/字段范围：credential_secret、access_credential、audit_log；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-002/004。
  实现说明：分离普通读与Secret resolver读权限，日志禁绑定敏感值。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：普通详情不能返回秘密列，迁移仅产品schema。
  测试要求：低权限账户读写验证；记录真实存储环境和结果，审查通过后勾选并提交。


## DB-P02 模型接入（6项）

- [ ] 任务编号：DB-007
  模块：模型接入；目标：Provider和Pool约束。
  数据表/字段范围：provider、credential_pool；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-007至012、FE-P02。
  实现说明：创建唯一键和Provider外键，校验超时和策略枚举。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：重复名称正确冲突，池不能成为孤儿。
  测试要求：唯一和FK事务测试；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-008
  模块：模型接入；目标：Credential秘密分离。
  数据表/字段范围：credential、credential_secret；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-013/020、FE-013/014。
  实现说明：配置表不含明文，秘密互斥列/版本/掩码；轮换即时事务。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：互斥约束成立，快照与diff无秘密。
  测试要求：双来源、轮换并发、回滚；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-009
  模块：模型接入；目标：模型能力与价格列。
  数据表/字段范围：provider_model；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-014、FE-015。
  实现说明：可空导入能力与enabled条件验证、decimal价格和业务唯一。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：未知disabled可存，enabled缺能力不通过。
  测试要求：条件约束、价格8位边界；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-010
  模块：模型接入；目标：Alias候选同源关联。
  数据表/字段范围：model_alias、route_candidate；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-016/017/018、FE-017/018。
  实现说明：三元组唯一、Alias候选索引、同Provider服务查询。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：重复候选拒绝，重排任一冲突全回滚。
  测试要求：跨Provider、重排事务；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-011
  模块：模型接入；目标：检测记录与任务。
  数据表/字段范围：provider_check_record、batch_check_job、batch_check_item；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-009/015、FE-009/016。
  实现说明：任务项去重、顺序索引、取消终态、检测与Trace逻辑关联。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：不生成草稿差异，历史检测独立保留。
  测试要求：部分完成取消和批次汇总；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-012
  模块：模型接入；目标：接入索引与历史名称。
  数据表/字段范围：object_runtime_state、模型/池/候选索引；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-004/007/014、FE-P02/P03。
  实现说明：运行状态不进入配置表，历史名称来自Attempt。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：列表无逐行N+1，快照查询不读工作集。
  测试要求：代表数据执行计划和删除后详情；记录真实存储环境和结果，审查通过后勾选并提交。


## DB-P03 运行治理（6项）

- [ ] 任务编号：DB-013
  模块：运行治理；目标：策略唯一与组合约束。
  数据表/字段范围：limit_policy、reliability_policy；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-021/022、FE-019/021。
  实现说明：每对象仅一启用策略，QUEUE/超时/半开参数组合。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：并发启用仅一个成功，null限额保留语义。
  测试要求：部分唯一与范围检查；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-014
  模块：运行治理；目标：Trace与Attempt终态事实。
  数据表/字段范围：trace、attempt；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-027/029/030、FE-P05。
  实现说明：trace_id与sequence唯一，时点空值和最终路径属于当前Trace。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：重复Trace无Attempt；终态Usage不可改。
  测试要求：唯一竞争、终态CAS；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-015
  模块：运行治理；目标：预占三层与结算意图。
  数据表/字段范围：capacity_reservation、capacity_reservation_item；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-024/029。
  实现说明：reservation幂等键、原窗口、外部发送前空Attempt、结算重放。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：无部分预占，未发请求可RELEASED。
  测试要求：真实Redis双实例和SQL失败重放；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-016
  模块：运行治理；目标：FIFO与恢复决策。
  数据表/字段范围：queue_entry、route_decision、recovery_decision；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-019/022/024、FE-020/022。
  实现说明：按Alias序列索引，Trace级动作序号，目标允许空。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：队首先取得，FAIL无目标，过滤无Attempt。
  测试要求：取消/唤醒竞争与预算明细；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-017
  模块：运行治理；目标：熔断镜像事件命令。
  数据表/字段范围：circuit_state、circuit_event、circuit_command；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-023、FE-023/024。
  实现说明：路径键唯一，command/event跨存储去重，state_version CAS。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：重放不重复改变状态，未应用不标成功。
  测试要求：Redis应用后SQL故障恢复；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-018
  模块：运行治理；目标：Watchdog与租约清理。
  数据表/字段范围：trace、attempt、capacity_reservation、runtime_instance；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-024/056。
  实现说明：实例失联与运行租约关联，先终止孤儿再释放。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：活跃Attempt不误释放，失联容量可回收。
  测试要求：到期边界、延迟心跳、进程崩溃；记录真实存储环境和结果，审查通过后勾选并提交。


## DB-P04 观测（6项）

- [ ] 任务编号：DB-019
  模块：观测；目标：Trace筛选与时间线查询。
  数据表/字段范围：trace、attempt及全部事件表；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-031/032、FE-025至029。
  实现说明：应用/时间优先索引，Attempt类型用EXISTS，合并顺序稳定。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：分页不重复Trace；详情attempt数一致。
  测试要求：精确ID、组合查询、同毫秒排序；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-020
  模块：观测；目标：诊断样本隔离。
  数据表/字段范围：trace_content_sample；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-032、FE-027。
  实现说明：独立expires索引，采样正文脱敏截断，读取审计。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：样本过期不删Trace，禁采样时无正文。
  测试要求：权限、到期删除、内容扫描；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-021
  模块：观测；目标：最终化Outbox及租约。
  数据表/字段范围：usage_aggregation_event；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-033、FE-030。
  实现说明：终态同事务写唯一事件，120秒接管带generation。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：双worker最多一次成功，失败保持可重试。
  测试要求：接管旧worker提交被拒；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-022
  模块：观测；目标：聚合维度和贡献拆分。
  数据表/字段范围：usage_aggregate；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-033/035、FE-034/035。
  实现说明：完整维度唯一，请求/执行贡献分离，HOUR/DAY同事务。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：Fallback请求只计1，失败路径Token仍保留。
  测试要求：事件重放、跨Provider归因、回滚；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-023
  模块：观测；目标：均值P95与多币种。
  数据表/字段范围：usage_aggregate直方图/金额列；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-034/035、FE-031至035。
  实现说明：合并直方图取P95，时区桶，货币各自聚合。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：不平均P95、不合币种、不重复HOUR/DAY。
  测试要求：两个分布夹具、DST、舍入复算；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-024
  模块：观测；目标：查询与导出执行计划。
  数据表/字段范围：trace、usage_aggregate相关索引；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-036/060、FE-026/036。
  实现说明：代表规模测试application/time/alias组合与游标导出。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：10万行边界可提前判断，内存不随文件增长。
  测试要求：EXPLAIN、100001行、断开游标；记录真实存储环境和结果，审查通过后勾选并提交。


## DB-P05 配置发布（6项）

- [ ] 任务编号：DB-025
  模块：配置发布；目标：校验票据与不可变快照。
  数据表/字段范围：config_validation、config_validation_issue、config_snapshot；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-039/040、FE-039/040。
  实现说明：修订/checksum/期限绑定，单ACTIVE、单校验发布。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：旧校验不创建记录，ABORTED号不回收。
  测试要求：修订竞争、敏感快照扫描；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-026
  模块：配置发布；目标：发布实例与原子激活。
  数据表/字段范围：publish_record、publish_instance_result、runtime_instance；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-040/041/042、FE-041/042。
  实现说明：固定ONLINE目标、报告水位、激活事务同时指针与草稿。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：准备失败旧ACTIVE不变，重启不双激活。
  测试要求：双实例故障与阶段恢复；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-027
  模块：配置发布；目标：RuntimeConfig影响与时区锁。
  数据表/字段范围：runtime_config、retention_impact；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-043、FE-043/044。
  实现说明：缩短留存确认绑定参数/期限，首次聚合原子锁时区。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：过期impact写入回滚，撤销不能解锁时区。
  测试要求：10分钟边界、并发首聚合；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-028
  模块：配置发布；目标：Access摘要与Alias白名单。
  数据表/字段范围：access_credential、access_credential_alias；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-044、FE-045至047。
  实现说明：摘要索引/代次/即时状态与审计同事务，白名单原子替换。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：旧Token立即拒绝、空列表全允许、Alias删除被引用拦截。
  测试要求：两实例轮换、过期、删除、事务失败；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-029
  模块：配置发布；目标：分批保留与恢复验证。
  数据表/字段范围：Trace明细、Usage、Audit、Snapshot和事件；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-048/056。
  实现说明：≤1000Trace分批，聚合未完跳过，快照保留并集和活跃引用。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：明细删除不删未过期Usage，恢复无重复聚合。
  测试要求：备份还原、未消费事件、快照100/365边界；记录真实存储环境和结果，审查通过后勾选并提交。

- [ ] 任务编号：DB-030
  模块：配置发布；目标：全库契约和迁移验收。
  数据表/字段范围：39表及schema历史；字段类型、必填、默认、索引和枚举按本文逐表定义。
  依赖接口与页面：BE-058/059/060、FE-054。
  实现说明：逐字段比对DTO，验证空库安装/升级/失败恢复/宿主schema隔离。
  异常处理：约束失败返回后端可映射的确定异常，事务失败不留下部分数据；不得修改历史迁移规避错误。
  验收标准：字段/类型/索引/枚举无漂移，必要检查证据齐全。
  测试要求：约束回归、升级演练、数据对账；记录真实存储环境和结果，审查通过后勾选并提交。




## 独立检测的关系约束

Provider/模型/凭证可以在Alias与候选建立前检测。CONNECTION_ONLY不执行模型推理时仅生成ProviderCheckRecord；MINIMAL_CHAT产生Trace与Attempt，Trace.invocation_source=PROVIDER_CHECK、application=ADMIN_CONSOLE、alias_id/alias可空，Attempt.route_candidate_id可空，其余Provider/Model/Pool/Credential路径必填。currency和价格来自检测选定模型，可靠性采用固定单次检测预算，超时采用命令；不进行Alias Fallback。触发半开探测时attempt_type=HALF_OPEN_PROBE。

数据库对APPLICATION/ADMIN_TEST的Trace施加alias_id/alias非空条件，对普通路由Attempt施加route_candidate_id非空服务约束。检测Trace仍结算并生成Usage事件，使用null Alias维度，页面显示“模型检测”；该文字由invocation_source派生，不伪造可调用Alias。此补充为C-021，防止执行方为满足外键而创建虚假Alias/候选。
