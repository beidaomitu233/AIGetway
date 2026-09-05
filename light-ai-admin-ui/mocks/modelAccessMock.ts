// 模型接入契约 Mock（FE-013—FE-018）：仅用于本地开发（后端 BE-013—BE-018 未交付）。
// 内存态数据支持增改启停删除轮换与批量检测推进；不进入构建产物，后端完成后整文件删除。
import type { ServerResponse } from 'node:http'
import { readMockBody } from './readBody'

interface Row {
  id: string
  version: number
  draft_changed: boolean
  [key: string]: unknown
}

function rid(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`
}

function nowIso(): string {
  return new Date().toISOString()
}

function opResult(row: Row | null, requestId: string): unknown {
  return {
    data: {
      id: row?.id ?? null,
      version: row?.version ?? null,
      entity: row ?? null,
      draft_changed: true,
      draft_revision: null,
      request_id: requestId,
    },
  }
}

function page<T>(items: T[]): unknown {
  return {
    data: {
      items,
      total: items.length,
      page: 1,
      page_size: items.length,
      sort: 'updated_at',
      query_started_at: nowIso(),
      data_updated_at: nowIso(),
    },
  }
}

function error(res: ServerResponse, status: number, code: string, message: string): void {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify({ error: { code, type: 'api', message, retryable: false } }))
}

function ok(res: ServerResponse, body: unknown): void {
  res.statusCode = 200
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify(body))
}

const pools: Row[] = [
  { id: 'pool-1', version: 3, draft_changed: false, name: 'openai-main', provider_id: 'prov-1', selection_strategy: 'LEAST_CONCURRENT', enabled: true, credential_total: 3, credential_available: 3, current_concurrency: 0, rpm_used: 0, tpm_used: 0, status: 'AVAILABLE', route_candidate_count: 2, model_alias_count: 1 },
  { id: 'pool-2', version: 1, draft_changed: false, name: 'deepseek-backup', provider_id: 'prov-2', selection_strategy: 'WEIGHTED_RANDOM', enabled: true, credential_total: 1, credential_available: 1, current_concurrency: 0, rpm_used: 0, tpm_used: 0, status: 'AVAILABLE', route_candidate_count: 0, model_alias_count: 0 },
]

const credentials: Row[] = [
  { id: 'cred-1', version: 2, draft_changed: false, pool_id: 'pool-1', name: 'openai-key-1', masked_value: 'sk-****abcd', secret_source: 'INLINE_ENCRYPTED', weight: 1, rpm_limit: null, tpm_limit: null, concurrent_limit: null, current_concurrency: 0, health_status: 'HEALTHY', rate_limit_reset_at: null, last_success_at: nowIso(), last_check_at: nowIso(), enabled: true },
  { id: 'cred-2', version: 1, draft_changed: false, pool_id: 'pool-1', name: 'openai-key-2', masked_value: 'sk-****ef01', secret_source: 'EXTERNAL_REF', weight: 1, rpm_limit: 60, tpm_limit: 100000, concurrent_limit: 8, current_concurrency: 1, health_status: 'RATE_LIMITED', rate_limit_reset_at: nowIso(), last_success_at: null, last_check_at: nowIso(), enabled: true },
]

const models: Row[] = [
  { id: 'model-1', version: 4, draft_changed: true, provider_id: 'prov-1', provider_name: 'OpenAI', model_id: 'gpt-4o', display_name: 'GPT-4o', model_type: 'CHAT_TEXT', tokenizer_family: 'o200k', context_window: 128000, max_output_tokens: 16384, support_stream: true, support_system_message: true, support_temperature: true, temperature_min: '0.0000', temperature_max: '2.0000', support_top_p: true, top_p_min: '0.0000', top_p_max: '1.0000', support_stop: true, max_stop_sequences: 4, max_stop_length: 128, default_temperature: null, default_top_p: null, default_max_tokens: null, default_stop: [], input_price: '2.50000000', output_price: '10.00000000', price_unit: 1000000, currency: 'USD', connection_status: 'AVAILABLE', last_check_at: nowIso(), last_error_code: null, route_candidate_count: 2, enabled: true, related_aliases: [{ id: 'cand-1', alias_id: 'alias-1', alias: 'chat-default', priority: 10, weight: 1, credential_pool_name: 'openai-main', candidate_status: 'AVAILABLE' }], recent_checks: [] },
  { id: 'model-2', version: 2, draft_changed: false, provider_id: 'prov-1', provider_name: 'OpenAI', model_id: 'gpt-4o-mini', display_name: 'GPT-4o mini', model_type: 'CHAT_TEXT', tokenizer_family: 'o200k', context_window: 128000, max_output_tokens: 16384, support_stream: true, support_system_message: true, support_temperature: true, temperature_min: '0.0000', temperature_max: '2.0000', support_top_p: true, top_p_min: '0.0000', top_p_max: '1.0000', support_stop: true, max_stop_sequences: 4, max_stop_length: 128, default_temperature: null, default_top_p: null, default_max_tokens: null, default_stop: [], input_price: '0.15000000', output_price: '0.60000000', price_unit: 1000000, currency: 'USD', connection_status: 'UNKNOWN', last_check_at: null, last_error_code: null, route_candidate_count: 0, enabled: true, related_aliases: [], recent_checks: [] },
  { id: 'model-3', version: 1, draft_changed: false, provider_id: 'prov-2', provider_name: 'DeepSeek', model_id: 'deepseek-chat', display_name: 'DeepSeek Chat', model_type: 'CHAT_TEXT', tokenizer_family: null, context_window: null, max_output_tokens: null, support_stream: null, support_system_message: null, support_temperature: null, temperature_min: null, temperature_max: null, support_top_p: null, top_p_min: null, top_p_max: null, support_stop: null, max_stop_sequences: null, max_stop_length: null, default_temperature: null, default_top_p: null, default_max_tokens: null, default_stop: [], input_price: '0.00000000', output_price: '0.00000000', price_unit: 1000000, currency: 'CNY', connection_status: 'UNKNOWN', last_check_at: null, last_error_code: null, route_candidate_count: 0, enabled: false, related_aliases: [], recent_checks: [] },
]

const aliases: Row[] = [
  { id: 'alias-1', version: 5, draft_changed: true, alias: 'chat-default', display_name: '默认对话', description: '业务默认入口', route_strategy: 'PRIORITY_WEIGHTED', enabled: true, candidate_count: 2, available_candidate_count: 2, stream_candidate_count: 2, request_count_24h: 128, success_rate_24h: 99.2, p95_total_ms_24h: 1840, current_snapshot_no: 12, updated_by: 'user-admin', updated_at: nowIso() },
  { id: 'alias-2', version: 1, draft_changed: false, alias: 'summary', display_name: '摘要生成', description: null, route_strategy: 'PRIORITY_WEIGHTED', enabled: false, candidate_count: 0, available_candidate_count: 0, stream_candidate_count: 0, request_count_24h: 0, success_rate_24h: null, p95_total_ms_24h: null, current_snapshot_no: 12, updated_by: 'user-admin', updated_at: nowIso() },
]

const candidates: Row[] = [
  { id: 'cand-1', version: 2, draft_changed: true, alias_id: 'alias-1', provider_id: 'prov-1', provider_name: 'OpenAI', provider_model_id: 'model-1', provider_model_display_name: 'GPT-4o', provider_model_id_label: 'gpt-4o', credential_pool_id: 'pool-1', credential_pool_name: 'openai-main', priority: 10, weight: 1, enabled: true, support_stream: true, support_system_message: true, context_window: 128000, current_concurrency: 0, runtime_status: 'AVAILABLE', excluded_reason: null },
  { id: 'cand-2', version: 1, draft_changed: false, alias_id: 'alias-1', provider_id: 'prov-1', provider_name: 'OpenAI', provider_model_id: 'model-2', provider_model_display_name: 'GPT-4o mini', provider_model_id_label: 'gpt-4o-mini', credential_pool_id: 'pool-1', credential_pool_name: 'openai-main', priority: 20, weight: 1, enabled: true, support_stream: true, support_system_message: true, context_window: 128000, current_concurrency: 0, runtime_status: 'AVAILABLE', excluded_reason: null },
]

interface ImportCandidateRow {
  model_id: string
  display_name: string | null
  existing: boolean
  source: string
  tokenizer_family: string | null
  context_window: number | null
  max_output_tokens: number | null
  support_stream: boolean | null
  support_system_message: boolean | null
  support_temperature: boolean | null
  support_top_p: boolean | null
  support_stop: boolean | null
}

const availableModels: ImportCandidateRow[] = [
  { model_id: 'gpt-4o', display_name: 'GPT-4o', existing: true, source: 'ADAPTER_PRESET', tokenizer_family: 'o200k', context_window: 128000, max_output_tokens: 16384, support_stream: true, support_system_message: true, support_temperature: true, support_top_p: true, support_stop: true },
  { model_id: 'gpt-4.1', display_name: 'GPT-4.1', existing: false, source: 'ADAPTER_PRESET', tokenizer_family: 'o200k', context_window: 1000000, max_output_tokens: 32768, support_stream: true, support_system_message: true, support_temperature: true, support_top_p: true, support_stop: true },
  { model_id: 'gpt-5-mini', display_name: null, existing: false, source: 'PROVIDER_API', tokenizer_family: null, context_window: null, max_output_tokens: null, support_stream: null, support_system_message: null, support_temperature: null, support_top_p: null, support_stop: null },
]

interface BatchJobRow {
  id: string
  status: string
  total_count: number
  completed_count: number
  success_count: number
  failure_count: number
  cancelled_count: number
  started_at: string | null
  ended_at: string | null
  command: { provider_model_ids: string[]; credential_id: string; mode: string; timeout_ms: number }
  items: { id: string; provider_model_id: string; provider_model_name: string; sequence: number; status: string; check_record_id: string | null; error_code: string | null }[]
}

let batchJob: BatchJobRow | null = null

function findRow(list: Row[], id: string): Row | undefined {
  return list.find((item) => item.id === id)
}

function checkRecord(targetId: string, status: 'SUCCEEDED' | 'FAILED'): Row {
  return {
    id: rid('chk'),
    version: 1,
    draft_changed: false,
    target_type: 'PROVIDER_MODEL',
    target_id: targetId,
    mode: 'MINIMAL_CHAT',
    status,
    operator_id: 'user-admin',
    started_at: nowIso(),
    ended_at: nowIso(),
    total_ms: 830,
    usage: status === 'SUCCEEDED' ? { prompt_tokens: 12, completion_tokens: 24, total_tokens: 36 } : null,
    provider_request_id: status === 'SUCCEEDED' ? 'req-mock-1' : null,
    error_code: status === 'FAILED' ? 'PROVIDER_AUTH_FAILED' : null,
    error_summary: status === 'FAILED' ? '模拟鉴权失败' : null,
    created_at: nowIso(),
  }
}

async function handle(
  req: { method?: string | undefined; url?: string | undefined; on?: ((event: string, cb: (chunk?: Buffer) => void) => void) | undefined },
  res: ServerResponse,
): Promise<boolean> {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')
  const requestId = rid('req')
  const body = await readMockBody(req as import('vite').Connect.IncomingMessage)

  // —— Provider / 凭证池选项 ——
  if (method === 'GET' && path === '/providers') {
    ok(res, page([
      { id: 'prov-1', name: 'OpenAI', type: 'OPENAI', enabled: true },
      { id: 'prov-2', name: 'DeepSeek', type: 'DEEPSEEK', enabled: true },
    ]))
    return true
  }
  if (method === 'GET' && path === '/credential-pools') {
    ok(res, page(pools))
    return true
  }

  // —— Credential ——
  let match = path.match(/^\/credential-pools\/([^/]+)\/credentials$/)
  if (match) {
    const poolId = match[1]!
    if (method === 'GET') {
      const filtered = credentials.filter((item) => item.pool_id === poolId)
      ok(res, page(filtered))
      return true
    }
    if (method === 'POST') {
      const row: Row = {
        id: rid('cred'),
        version: 1,
        draft_changed: true,
        pool_id: poolId,
        name: String(body.name ?? ''),
        masked_value: `${String(body.secret_value ?? body.secret_ref ?? '').slice(0, 3)}****mock`,
        secret_source: body.secret_source ?? 'INLINE_ENCRYPTED',
        weight: Number(body.weight ?? 1),
        rpm_limit: body.rpm_limit ?? null,
        tpm_limit: body.tpm_limit ?? null,
        concurrent_limit: body.concurrent_limit ?? null,
        current_concurrency: 0,
        health_status: 'UNKNOWN',
        rate_limit_reset_at: null,
        last_success_at: null,
        last_check_at: null,
        enabled: body.enabled ?? true,
      }
      credentials.push(row)
      ok(res, opResult(row, requestId))
      return true
    }
  }

  match = path.match(/^\/credentials\/([^/]+)$/)
  if (match) {
    const row = findRow(credentials, match[1]!)
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', 'Credential 不存在')
      return true
    }
    if (method === 'PUT') {
      if (Number(body.version) !== row.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return true
      }
      Object.assign(row, body, { version: row.version + 1, draft_changed: true })
      ok(res, opResult(row, requestId))
      return true
    }
    if (method === 'DELETE') {
      if (Number(row.current_concurrency ?? 0) > 0) {
        error(res, 409, 'CAPACITY_IN_USE', '凭证正在被运行中的调用占用')
        return true
      }
      credentials.splice(credentials.indexOf(row), 1)
      ok(res, opResult(null, requestId))
      return true
    }
  }

  match = path.match(/^\/credentials\/([^/]+)\/(rotate|check|enable|disable)$/)
  if (match) {
    const row = findRow(credentials, match[1]!)
    const action = match[2]!
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', 'Credential 不存在')
      return true
    }
    if (action === 'rotate') {
      if (String(body.secret_value) !== String(body.secret_value_confirm)) {
        error(res, 400, 'SECRET_CONFIRM_MISMATCH', '两次输入的密钥不一致')
        return true
      }
      if (Number(body.version) !== row.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return true
      }
      row.version += 1
      row.draft_changed = true
      row.masked_value = `${String(body.secret_value).slice(0, 3)}****rot`
      ok(res, opResult(row, requestId))
      return true
    }
    if (action === 'check') {
      const failed = url.searchParams.get('__fail') === '1'
      ok(res, { data: checkRecord(row.id, failed ? 'FAILED' : 'SUCCEEDED') })
      return true
    }
    if (Number(body.version) !== row.version) {
      error(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
      return true
    }
    row.version += 1
    row.draft_changed = true
    row.enabled = action === 'enable'
    if (action === 'enable') row.health_status = 'UNKNOWN'
    ok(res, opResult(row, requestId))
    return true
  }

  // —— Provider Model ——
  if (method === 'GET' && path === '/provider-models') {
    const providerId = url.searchParams.get('provider_id')
    const filtered = providerId ? models.filter((item) => item.provider_id === providerId) : models
    ok(res, page(filtered))
    return true
  }
  if (method === 'POST' && path === '/provider-models') {
    const row: Row = {
      id: rid('model'),
      version: 1,
      draft_changed: true,
      provider_name: 'OpenAI',
      connection_status: 'UNKNOWN',
      last_check_at: null,
      last_error_code: null,
      route_candidate_count: 0,
      related_aliases: [],
      recent_checks: [],
      ...body,
    }
    models.push(row)
    ok(res, opResult(row, requestId))
    return true
  }
  if (method === 'GET' && path === '/provider-models/import') {
    ok(res, { data: { created: [], skipped: [], failed: [] } })
    return true
  }
  if (method === 'POST' && path === '/provider-models/import') {
    const created: { model_id: string; id: string; version: number }[] = []
    const skipped: { model_id: string; reason: string }[] = []
    const failed: { model_id: string; error: string }[] = []
    for (const modelId of (body.model_ids ?? []) as string[]) {
      const existing = models.find((item) => item.model_id === modelId)
      if (existing) {
        skipped.push({ model_id: modelId, reason: '已存在' })
        continue
      }
      const candidate = availableModels.find((item) => item.model_id === modelId)
      if (!candidate) {
        failed.push({ model_id: modelId, error: '候选不存在' })
        continue
      }
      const row: Row = {
        id: rid('model'),
        version: 1,
        draft_changed: true,
        provider_id: body.provider_id,
        provider_name: 'OpenAI',
        model_id: modelId,
        display_name: candidate.display_name ?? modelId,
        model_type: 'CHAT_TEXT',
        tokenizer_family: body.apply_known_defaults ? candidate.tokenizer_family : null,
        context_window: body.apply_known_defaults ? candidate.context_window : null,
        max_output_tokens: body.apply_known_defaults ? candidate.max_output_tokens : null,
        support_stream: body.apply_known_defaults ? candidate.support_stream : null,
        support_system_message: body.apply_known_defaults ? candidate.support_system_message : null,
        support_temperature: body.apply_known_defaults ? candidate.support_temperature : null,
        support_top_p: body.apply_known_defaults ? candidate.support_top_p : null,
        support_stop: body.apply_known_defaults ? candidate.support_stop : null,
        temperature_min: null, temperature_max: null, top_p_min: null, top_p_max: null,
        max_stop_sequences: null, max_stop_length: null,
        default_temperature: null, default_top_p: null, default_max_tokens: null, default_stop: [],
        input_price: '0.00000000', output_price: '0.00000000', price_unit: 1000000, currency: 'USD',
        connection_status: 'UNKNOWN', last_check_at: null, last_error_code: null,
        route_candidate_count: 0, related_aliases: [], recent_checks: [],
        enabled: body.enabled ?? false,
      }
      models.push(row)
      created.push({ model_id: modelId, id: row.id, version: row.version })
    }
    ok(res, { data: { created, skipped, failed } })
    return true
  }
  if (method === 'POST' && path === '/provider-models/batch-check') {
    const ids = (body.provider_model_ids ?? []) as string[]
    batchJob = {
      id: rid('job'),
      status: 'RUNNING',
      total_count: ids.length,
      completed_count: 0,
      success_count: 0,
      failure_count: 0,
      cancelled_count: 0,
      started_at: nowIso(),
      ended_at: null,
      command: { provider_model_ids: ids, credential_id: String(body.credential_id), mode: String(body.mode), timeout_ms: Number(body.timeout_ms) },
      items: ids.map((id, index) => ({
        id: rid('item'),
        provider_model_id: id,
        provider_model_name: String(findRow(models, id)?.display_name ?? id),
        sequence: index + 1,
        status: 'PENDING',
        check_record_id: null,
        error_code: null,
      })),
    }
    ok(res, { data: batchJob })
    return true
  }
  match = path.match(/^\/batch-check-jobs\/([^/]+)$/)
  if (match && method === 'GET') {
    if (!batchJob || batchJob.id !== match[1]) {
      error(res, 404, 'OBJECT_NOT_FOUND', '任务不存在')
      return true
    }
    const pending = batchJob.items.find((item) => item.status === 'PENDING')
    if (pending) {
      const failed = pending.sequence % 3 === 0
      pending.status = failed ? 'FAILED' : 'SUCCEEDED'
      pending.error_code = failed ? 'PROVIDER_AUTH_FAILED' : null
      batchJob.completed_count += 1
      if (failed) batchJob.failure_count += 1
      else batchJob.success_count += 1
      if (batchJob.completed_count >= batchJob.total_count) {
        batchJob.status = batchJob.failure_count > 0 ? 'PARTIAL_FAILED' : 'SUCCEEDED'
        batchJob.ended_at = nowIso()
      }
    }
    ok(res, { data: { job: batchJob, items: batchJob.items } })
    return true
  }
  match = path.match(/^\/batch-check-jobs\/([^/]+)\/cancel$/)
  if (match && method === 'POST') {
    if (!batchJob || batchJob.id !== match[1]) {
      error(res, 404, 'OBJECT_NOT_FOUND', '任务不存在')
      return true
    }
    let cancelled = 0
    for (const item of batchJob.items) {
      if (item.status === 'PENDING') {
        item.status = 'CANCELLED'
        cancelled += 1
      }
    }
    batchJob.cancelled_count += cancelled
    batchJob.status = 'CANCELLED'
    batchJob.ended_at = nowIso()
    ok(res, { data: batchJob })
    return true
  }
  match = path.match(/^\/providers\/([^/]+)\/available-models$/)
  if (match && method === 'GET') {
    ok(res, { data: availableModels })
    return true
  }
  match = path.match(/^\/provider-models\/([^/]+)\/credential-pools$/)
  if (match && method === 'GET') {
    const row = findRow(models, match[1]!)
    const list = row ? pools.filter((pool) => pool.provider_id === row.provider_id) : []
    ok(res, {
      data: list.map((pool) => ({
        id: pool.id,
        name: pool.name,
        provider_id: pool.provider_id,
        credential_available: Number(pool.credential_available ?? 0),
        status: pool.status,
      })),
    })
    return true
  }
  match = path.match(/^\/provider-models\/([^/]+)$/)
  if (match) {
    const row = findRow(models, match[1]!)
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '模型不存在')
      return true
    }
    if (method === 'GET') {
      ok(res, { data: row })
      return true
    }
    if (method === 'PUT') {
      if (Number(body.version) !== row.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return true
      }
      Object.assign(row, body, { version: row.version + 1, draft_changed: true })
      ok(res, opResult(row, requestId))
      return true
    }
    if (method === 'DELETE') {
      models.splice(models.indexOf(row), 1)
      ok(res, opResult(null, requestId))
      return true
    }
  }
  match = path.match(/^\/provider-models\/([^/]+)\/(check|enable|disable|impact)$/)
  if (match) {
    const row = findRow(models, match[1]!)
    const action = match[2]!
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '模型不存在')
      return true
    }
    if (action === 'check') {
      ok(res, { data: checkRecord(row.id, url.searchParams.get('__fail') === '1' || url.searchParams.get('__fail') === null ? 'SUCCEEDED' : 'FAILED') })
      return true
    }
    if (action === 'impact') {
      const operation = url.searchParams.get('operation') ?? 'DISABLE'
      ok(res, {
        data: {
          impact_version: `${row.version}:${operation}`,
          entity_type: 'provider_model',
          entity_id: row.id,
          references: Number(row.route_candidate_count ?? 0) > 0
            ? [{ entity_type: 'route_candidate', id: 'cand-1', name: 'chat-default 候选', relation: '候选引用' }]
            : [],
          affected_alias_ids: Number(row.route_candidate_count ?? 0) > 0 ? ['alias-1'] : [],
          can_delete: Number(row.route_candidate_count ?? 0) === 0,
          blockers: Number(row.route_candidate_count ?? 0) > 0 ? ['存在引用候选'] : [],
        },
      })
      return true
    }
    if (Number(body.version) !== row.version) {
      error(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
      return true
    }
    row.version += 1
    row.draft_changed = true
    row.enabled = action === 'enable'
    ok(res, opResult(row, requestId))
    return true
  }

  // —— Model Alias 与候选 ——
  if (method === 'GET' && path === '/model-aliases') {
    ok(res, page(aliases))
    return true
  }
  if (method === 'POST' && path === '/model-aliases') {
    if (aliases.some((item) => item.alias === body.alias)) {
      error(res, 400, 'FIELD_VALIDATION_FAILED', 'alias 已存在')
      return true
    }
    const row: Row = {
      id: rid('alias'),
      version: 1,
      draft_changed: true,
      route_strategy: 'PRIORITY_WEIGHTED',
      candidate_count: 0,
      available_candidate_count: 0,
      stream_candidate_count: 0,
      request_count_24h: 0,
      success_rate_24h: null,
      p95_total_ms_24h: null,
      current_snapshot_no: 12,
      updated_by: 'user-admin',
      updated_at: nowIso(),
      ...body,
    }
    aliases.push(row)
    ok(res, opResult(row, requestId))
    return true
  }
  match = path.match(/^\/model-aliases\/([^/]+)$/)
  if (match) {
    const row = findRow(aliases, match[1]!)
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '别名不存在')
      return true
    }
    if (method === 'GET') {
      ok(res, { data: row })
      return true
    }
    if (method === 'PUT') {
      if (Number(body.version) !== row.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return true
      }
      Object.assign(row, body, { version: row.version + 1, draft_changed: true })
      ok(res, opResult(row, requestId))
      return true
    }
    if (method === 'DELETE') {
      aliases.splice(aliases.indexOf(row), 1)
      ok(res, opResult(null, requestId))
      return true
    }
  }
  match = path.match(/^\/model-aliases\/([^/]+)\/(impact|enable|disable)$/)
  if (match) {
    const row = findRow(aliases, match[1]!)
    const action = match[2]!
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '别名不存在')
      return true
    }
    if (action === 'impact') {
      const operation = url.searchParams.get('operation') ?? 'DISABLE'
      ok(res, {
        data: {
          impact_version: `${row.version}:${operation}`,
          entity_type: 'model_alias',
          entity_id: row.id,
          references: [],
          affected_alias_ids: [],
          can_delete: true,
          blockers: [],
        },
      })
      return true
    }
    if (Number(body.version) !== row.version) {
      error(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
      return true
    }
    row.version += 1
    row.draft_changed = true
    row.enabled = action === 'enable'
    ok(res, opResult(row, requestId))
    return true
  }
  match = path.match(/^\/model-aliases\/([^/]+)\/candidates\/reorder$/)
  if (match && method === 'PUT') {
    const items = (body.items ?? []) as { id: string; priority: number; version: number }[]
    for (const item of items) {
      const row = findRow(candidates, item.id)
      if (!row || row.version !== item.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '候选已被其他管理员修改')
        return true
      }
    }
    for (const item of items) {
      const row = findRow(candidates, item.id)!
      row.priority = item.priority
      row.version += 1
      row.draft_changed = true
    }
    const aliasRow = findRow(aliases, match[1]!)
    if (aliasRow) aliasRow.version += 1
    ok(res, { data: candidates.filter((row) => row.alias_id === match![1]) })
    return true
  }
  match = path.match(/^\/model-aliases\/([^/]+)\/candidates$/)
  if (match) {
    const aliasId = match[1]!
    if (method === 'GET') {
      ok(res, { data: candidates.filter((row) => row.alias_id === aliasId) })
      return true
    }
    if (method === 'POST') {
      const duplicate = candidates.some(
        (row) => row.alias_id === aliasId && row.provider_model_id === body.provider_model_id && row.credential_pool_id === body.credential_pool_id,
      )
      if (duplicate) {
        error(res, 409, 'DUPLICATE_ROUTE_CANDIDATE', '相同的模型与凭证池组合已存在')
        return true
      }
      const modelRow = findRow(models, String(body.provider_model_id))
      const poolRow = findRow(pools, String(body.credential_pool_id))
      if (!modelRow || !poolRow || modelRow.provider_id !== poolRow.provider_id) {
        error(res, 400, 'OBJECT_REFERENCE_INVALID', '模型与凭证池必须属于同一 Provider')
        return true
      }
      const row: Row = {
        id: rid('cand'),
        version: 1,
        draft_changed: true,
        alias_id: aliasId,
        provider_id: modelRow.provider_id,
        provider_name: modelRow.provider_name,
        provider_model_id: modelRow.id,
        provider_model_display_name: modelRow.display_name,
        provider_model_id_label: modelRow.model_id,
        credential_pool_id: poolRow.id,
        credential_pool_name: poolRow.name,
        priority: Number(body.priority),
        weight: Number(body.weight),
        enabled: body.enabled ?? true,
        support_stream: modelRow.support_stream ?? false,
        support_system_message: modelRow.support_system_message ?? false,
        context_window: modelRow.context_window,
        current_concurrency: 0,
        runtime_status: 'UNAVAILABLE',
        excluded_reason: '尚未发布',
      }
      candidates.push(row)
      ok(res, opResult(row, requestId))
      return true
    }
  }
  match = path.match(/^\/route-candidates\/([^/]+)$/)
  if (match) {
    const row = findRow(candidates, match[1]!)
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '候选不存在')
      return true
    }
    if (method === 'PUT') {
      if (Number(body.version) !== row.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '候选已被其他管理员修改')
        return true
      }
      Object.assign(row, body, { version: row.version + 1, draft_changed: true })
      ok(res, opResult(row, requestId))
      return true
    }
    if (method === 'DELETE') {
      candidates.splice(candidates.indexOf(row), 1)
      ok(res, opResult(null, requestId))
      return true
    }
  }
  match = path.match(/^\/route-candidates\/([^/]+)\/check$/)
  if (match && method === 'POST') {
    ok(res, { data: checkRecord(match[1]!, 'SUCCEEDED') })
    return true
  }

  return false
}


export function handleModelAccessApi(
  req: { method?: string | undefined; url?: string | undefined; on?: ((event: string, cb: (chunk?: Buffer) => void) => void) | undefined },
  res: ServerResponse,
): Promise<boolean> {
  return handle(req, res)
}
