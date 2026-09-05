// 运行治理契约 Mock（FE-019—FE-024）：仅用于本地开发（后端 BE-021—BE-023 未交付）。
// 内存态覆盖限流/可靠性/熔断列表、详情、启停、人工操作与 CAS 冲突模拟；后端完成后整文件删除。
import type { ServerResponse } from 'node:http'
import { readMockBody } from './readBody'

function nowIso(): string {
  return new Date().toISOString()
}

function rid(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`
}

function ok(res: ServerResponse, body: unknown): void {
  res.statusCode = 200
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify(body))
}

function error(res: ServerResponse, status: number, code: string, message: string, extra: Record<string, unknown> = {}): void {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify({ error: { code, type: 'api', message, retryable: false, ...extra } }))
}

function page<T>(items: T[]): unknown {
  return {
    data: {
      items,
      total: items.length,
      page: 1,
      page_size: items.length,
      sort: 'state_priority',
      query_started_at: nowIso(),
      data_updated_at: nowIso(),
    },
  }
}

function opResult(row: unknown, requestId: string): unknown {
  return {
    data: {
      id: (row as { id?: string } | null)?.id ?? null,
      version: (row as { version?: number } | null)?.version ?? null,
      entity: row,
      draft_changed: true,
      draft_revision: null,
      request_id: requestId,
    },
  }
}

interface LimitRow {
  id: string
  version: number
  draft_changed: boolean
  name: string
  scope_type: string
  scope_id: string
  scope_name: string
  rpm_limit: number | null
  tpm_limit: number | null
  concurrent_limit: number | null
  rpm_used: number
  tpm_reserved: number
  tpm_confirmed: number
  concurrency_used: number
  queue_length: number
  queue_max_size: number | null
  overflow_strategy: string
  queue_timeout_ms: number | null
  window_end: string | null
  counter_store_status: string
  enabled: boolean
  updated_at: string
  window_seconds: number
  created_at: string
}

const limitPolicies: LimitRow[] = [
  {
    id: 'lp-1', version: 2, draft_changed: false, name: 'alias-chat-guard', scope_type: 'MODEL_ALIAS',
    scope_id: 'alias-1', scope_name: '默认对话（chat-default）', rpm_limit: 1000, tpm_limit: 200000,
    concurrent_limit: 50, rpm_used: 320, tpm_reserved: 48000, tpm_confirmed: 41000, concurrency_used: 6,
    queue_length: 2, queue_max_size: 1000, overflow_strategy: 'QUEUE', queue_timeout_ms: 5000,
    window_end: nowIso(), counter_store_status: 'OK', enabled: true, updated_at: nowIso(), window_seconds: 60,
    created_at: nowIso(),
  },
  {
    id: 'lp-2', version: 1, draft_changed: true, name: 'credential-guard', scope_type: 'CREDENTIAL',
    scope_id: 'cred-1', scope_name: 'openai-key-1（sk-****abcd）', rpm_limit: 500, tpm_limit: null,
    concurrent_limit: null, rpm_used: 210, tpm_reserved: 0, tpm_confirmed: 0, concurrency_used: 1,
    queue_length: 0, queue_max_size: null, overflow_strategy: 'REJECT', queue_timeout_ms: null,
    window_end: nowIso(), counter_store_status: 'DEGRADED', enabled: true, updated_at: nowIso(),
    window_seconds: 60, created_at: nowIso(),
  },
]

const queueEntries = [
  {
    id: 'q-1', trace_id: 'tr-queued-1', alias_id: 'alias-1', alias_name: '默认对话', sequence: 18,
    status: 'WAITING', blocking_policy_ids: ['lp-1'], estimated_tokens: 2400, enqueued_at: nowIso(), deadline_at: nowIso(),
  },
  {
    id: 'q-2', trace_id: 'tr-queued-2', alias_id: 'alias-1', alias_name: '默认对话', sequence: 19,
    status: 'WAITING', blocking_policy_ids: ['lp-1'], estimated_tokens: 1200, enqueued_at: nowIso(), deadline_at: nowIso(),
  },
  {
    id: 'q-3', trace_id: 'tr-queued-0', alias_id: 'alias-1', alias_name: '默认对话', sequence: 17,
    status: 'TIMEOUT', blocking_policy_ids: ['lp-1'], estimated_tokens: 800, enqueued_at: nowIso(), deadline_at: nowIso(),
  },
]

interface ReliabilityRow {
  id: string
  version: number
  draft_changed: boolean
  name: string
  alias_id: string
  alias: string
  connect_timeout_ms: number
  first_token_timeout_ms: number
  total_timeout_ms: number
  max_retries: number
  max_credential_failovers: number
  initial_backoff_ms: number
  backoff_multiplier: string
  jitter_percent: number
  respect_retry_after: boolean
  max_retry_after_ms: number
  fallback_enabled: boolean
  max_fallbacks: number
  circuit_window_seconds: number
  circuit_min_requests: number
  circuit_failure_rate: string
  circuit_open_seconds: number
  circuit_half_open_probes: number
  circuit_half_open_successes: number
  enabled: boolean
  updated_at: string
  created_at: string
}

const reliabilityPolicies: ReliabilityRow[] = [
  {
    id: 'rp-1', version: 3, draft_changed: false, name: 'chat-default-reliability', alias_id: 'alias-1',
    alias: 'chat-default', connect_timeout_ms: 3000, first_token_timeout_ms: 30000, total_timeout_ms: 120000,
    max_retries: 1, max_credential_failovers: 1, initial_backoff_ms: 200, backoff_multiplier: '2.00',
    jitter_percent: 20, respect_retry_after: true, max_retry_after_ms: 5000, fallback_enabled: true,
    max_fallbacks: 2, circuit_window_seconds: 60, circuit_min_requests: 20, circuit_failure_rate: '0.5000',
    circuit_open_seconds: 30, circuit_half_open_probes: 3, circuit_half_open_successes: 2, enabled: true,
    updated_at: nowIso(), created_at: nowIso(),
  },
]

const recoveryDecisions = [
  {
    id: 'rd-1', trace_id: 'tr-rec-1', sequence: 2, action: 'CREDENTIAL_FAILOVER',
    reason_code: 'PROVIDER_RATE_LIMITED', source_attempt_id: 'at-1', scheduled_delay_ms: 0,
    target_route_candidate_id: 'cand-1', target_credential_id: 'cred-2', retries_used: 0,
    credential_failovers_used: 1, fallbacks_used: 0, remaining_timeout_ms: 98000, created_at: nowIso(),
    source_attempt: { id: 'at-1', sequence: 1, attempt_type: 'INITIAL', error_code: 'PROVIDER_RATE_LIMITED', started_at: nowIso(), ended_at: nowIso() },
  },
  {
    id: 'rd-2', trace_id: 'tr-rec-2', sequence: 3, action: 'FALLBACK', reason_code: 'PROVIDER_SERVER_ERROR',
    source_attempt_id: 'at-2', scheduled_delay_ms: 200, target_route_candidate_id: 'cand-2',
    target_credential_id: 'cred-1', retries_used: 1, credential_failovers_used: 0, fallbacks_used: 1,
    remaining_timeout_ms: 90000, created_at: nowIso(),
    source_attempt: { id: 'at-2', sequence: 2, attempt_type: 'RETRY', error_code: 'PROVIDER_SERVER_ERROR', started_at: nowIso(), ended_at: nowIso() },
  },
  {
    id: 'rd-3', trace_id: 'tr-rec-3', sequence: 4, action: 'FAIL', reason_code: 'TOTAL_TIMEOUT',
    source_attempt_id: 'at-3', scheduled_delay_ms: 0, target_route_candidate_id: null, target_credential_id: null,
    retries_used: 1, credential_failovers_used: 1, fallbacks_used: 2, remaining_timeout_ms: 0, created_at: nowIso(),
    source_attempt: { id: 'at-3', sequence: 3, attempt_type: 'CREDENTIAL_FAILOVER', error_code: 'TOTAL_TIMEOUT', started_at: nowIso(), ended_at: nowIso() },
  },
]

const defaultReliability: ReliabilityRow = {
  id: 'rp-default', version: 1, draft_changed: false, name: 'SYSTEM_DEFAULT', alias_id: '', alias: '—',
  connect_timeout_ms: 3000, first_token_timeout_ms: 30000, total_timeout_ms: 120000, max_retries: 1,
  max_credential_failovers: 1, initial_backoff_ms: 200, backoff_multiplier: '2.00', jitter_percent: 20,
  respect_retry_after: true, max_retry_after_ms: 5000, fallback_enabled: true, max_fallbacks: 2,
  circuit_window_seconds: 60, circuit_min_requests: 20, circuit_failure_rate: '0.5000',
  circuit_open_seconds: 30, circuit_half_open_probes: 3, circuit_half_open_successes: 2, enabled: true,
  updated_at: nowIso(), created_at: nowIso(),
}

let circuitVersion = 7

interface CircuitRow {
  id: string
  provider_id: string
  provider_name: string
  provider_model_id: string
  provider_model_name: string
  credential_id: string | null
  credential_name: string | null
  credential_masked_value: string | null
  state: string
  state_version: number
  open_source: string | null
  sample_count: number
  failure_count: number
  failure_rate: string
  half_open_in_flight: number
  half_open_success_count: number
  opened_at: string | null
  next_probe_at: string | null
  last_error_code: string | null
  updated_at: string
  manual_reason: string | null
  manual_open_until: string | null
  operator: string | null
  policy_snapshot: Record<string, unknown>
  window_samples: { trace_id: string; attempt_id: string; ended_at: string; error_code: string | null; total_ms: number }[]
  recent_probes: { id: string; kind: string; status: string; started_at: string; total_ms: number; error_code: string | null }[]
  pending_command: Record<string, unknown> | null
}

const circuits: CircuitRow[] = [
  {
    id: 'cir-1', provider_id: 'prov-1', provider_name: 'OpenAI', provider_model_id: 'model-1',
    provider_model_name: 'GPT-4o', credential_id: 'cred-1', credential_name: 'openai-key-1',
    credential_masked_value: 'sk-****abcd', state: 'OPEN', state_version: circuitVersion, open_source: 'AUTO',
    sample_count: 24, failure_count: 14, failure_rate: '0.5833', half_open_in_flight: 0, half_open_success_count: 0,
    opened_at: nowIso(), next_probe_at: nowIso(), last_error_code: 'NETWORK_ERROR', updated_at: nowIso(),
    manual_reason: null, manual_open_until: null, operator: null,
    policy_snapshot: { policy_id: 'rp-1', snapshot_no: 12, circuit_window_seconds: 60, circuit_min_requests: 20, circuit_failure_rate: '0.5000', circuit_open_seconds: 30, circuit_half_open_probes: 3, circuit_half_open_successes: 2 },
    window_samples: [
      { trace_id: 'tr-fail-1', attempt_id: 'at-f1', ended_at: nowIso(), error_code: 'NETWORK_ERROR', total_ms: 3010 },
      { trace_id: 'tr-fail-2', attempt_id: 'at-f2', ended_at: nowIso(), error_code: 'CONNECT_TIMEOUT', total_ms: 3005 },
    ],
    recent_probes: [
      { id: 'pr-1', kind: 'HALF_OPEN_PROBE', status: 'FAILED', started_at: nowIso(), total_ms: 1200, error_code: 'NETWORK_ERROR' },
    ],
    pending_command: null,
  },
  {
    id: 'cir-2', provider_id: 'prov-1', provider_name: 'OpenAI', provider_model_id: 'model-2',
    provider_model_name: 'GPT-4o mini', credential_id: 'cred-2', credential_name: 'openai-key-2',
    credential_masked_value: 'sk-****ef01', state: 'CLOSED', state_version: 2, open_source: null,
    sample_count: 3, failure_count: 0, failure_rate: '0.0000', half_open_in_flight: 0, half_open_success_count: 0,
    opened_at: null, next_probe_at: null, last_error_code: null, updated_at: nowIso(),
    manual_reason: null, manual_open_until: null, operator: null,
    policy_snapshot: { policy_id: null, snapshot_no: 12, circuit_window_seconds: 60, circuit_min_requests: 20, circuit_failure_rate: '0.5000', circuit_open_seconds: 30, circuit_half_open_probes: 3, circuit_half_open_successes: 2 },
    window_samples: [], recent_probes: [], pending_command: null,
  },
]

const circuitEvents = [
  {
    id: 'ce-1', event_key: 'ek-1', circuit_id: 'cir-1', from_state: 'CLOSED', to_state: 'OPEN',
    trigger_type: 'AUTO_THRESHOLD', trigger_trace_id: 'tr-fail-2', command_id: null,
    error_code: 'NETWORK_ERROR', reason: '窗口内失败率达到阈值', occurred_at: nowIso(),
  },
  {
    id: 'ce-2', event_key: 'ek-2', circuit_id: 'cir-1', from_state: 'OPEN', to_state: 'OPEN',
    trigger_type: 'MANUAL_OPEN', trigger_trace_id: null, command_id: null, error_code: null,
    reason: '上游容量告警，人工预防性打开', occurred_at: nowIso(),
  },
]

async function handle(req: { method?: string | undefined; url?: string | undefined; on?: ((event: string, cb: (chunk?: Buffer) => void) => void) | undefined }, res: ServerResponse): Promise<boolean> {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')
  const requestId = rid('req')

  const body = await readMockBody(req as import('vite').Connect.IncomingMessage)

  // —— 限流策略 ——
  if (method === 'GET' && path === '/limit-policies') {
    ok(res, page(limitPolicies))
    return true
  }
  if (method === 'POST' && path === '/limit-policies') {
    const row = { id: rid('lp'), version: 1, draft_changed: true, rpm_used: 0, tpm_reserved: 0, tpm_confirmed: 0, concurrency_used: 0, queue_length: 0, window_end: null, counter_store_status: 'OK', updated_at: nowIso(), window_seconds: 60, created_at: nowIso(), scope_name: '新对象', ...body } as unknown as LimitRow
    limitPolicies.push(row)
    ok(res, opResult(row, requestId))
    return true
  }
  let match = path.match(/^\/limit-policies\/([^/]+)\/usage$/)
  if (match && method === 'GET') {
    const row = limitPolicies.find((item) => item.id === match![1])
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '策略不存在')
      return true
    }
    if (url.searchParams.get('__unavailable') === '1') {
      error(res, 503, 'CAPACITY_STATE_UNAVAILABLE', '容量计数存储暂不可用')
      return true
    }
    ok(res, {
      data: {
        policy_id: row.id, scope_type: row.scope_type, scope_name: row.scope_name,
        rpm_used: row.rpm_used, rpm_limit: row.rpm_limit,
        tpm_reserved: row.tpm_reserved, tpm_confirmed: row.tpm_confirmed, tpm_limit: row.tpm_limit,
        concurrency_used: row.concurrency_used, concurrent_limit: row.concurrent_limit,
        queue_length: row.queue_length, queue_max_size: row.queue_max_size,
        window_start: nowIso(), window_end: row.window_end,
        counter_store_status: row.counter_store_status, data_updated_at: nowIso(),
      },
    })
    return true
  }
  match = path.match(/^\/limit-policies\/([^/]+)\/queue$/)
  if (match && method === 'GET') {
    const status = url.searchParams.get('status')
    const filtered = status ? queueEntries.filter((item) => item.status === status) : queueEntries
    ok(res, page(filtered))
    return true
  }
  match = path.match(/^\/limit-policies\/([^/]+)\/impact$/)
  if (match && method === 'GET') {
    ok(res, { data: { impact_version: `v:${match[1]}:${url.searchParams.get('operation') ?? ''}`, entity_type: 'limit_policy', entity_id: match[1], references: [], affected_alias_ids: [], can_delete: true, blockers: [] } })
    return true
  }
  match = path.match(/^\/limit-policies\/([^/]+)$/)
  if (match) {
    const row = limitPolicies.find((item) => item.id === match![1])
    const action = path.slice(path.lastIndexOf('/'), path.length)
    const enableMatch = path.match(/^\/limit-policies\/([^/]+)\/(enable|disable)$/)
    if (enableMatch) {
      const target = limitPolicies.find((item) => item.id === enableMatch[1])
      if (!target) {
        error(res, 404, 'OBJECT_NOT_FOUND', '策略不存在')
        return true
      }
      if (Number(body.version) !== target.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '策略已被其他管理员修改')
        return true
      }
      if (enableMatch[2] === 'enable' && target.rpm_limit == null && target.tpm_limit == null && target.concurrent_limit == null) {
        error(res, 400, 'LIMIT_POLICY_CONFLICT', '至少一个限额非空时才允许启用')
        return true
      }
      target.enabled = enableMatch[2] === 'enable'
      target.version += 1
      target.draft_changed = true
      ok(res, opResult(target, requestId))
      return true
    }
    if (action === '/usage' || action === '/queue') return false
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '策略不存在')
      return true
    }
    if (method === 'GET') {
      ok(res, { data: row })
      return true
    }
    if (method === 'PUT') {
      if (Number(body.version) !== row.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '策略已被其他管理员修改')
        return true
      }
      Object.assign(row, body, { version: row.version + 1, draft_changed: true })
      ok(res, opResult(row, requestId))
      return true
    }
    if (method === 'DELETE') {
      limitPolicies.splice(limitPolicies.indexOf(row), 1)
      ok(res, opResult(null, requestId))
      return true
    }
  }

  // —— 可靠性策略 ——
  if (method === 'GET' && path === '/reliability-policies/default') {
    ok(res, { data: defaultReliability })
    return true
  }
  if (method === 'GET' && path === '/reliability-policies') {
    ok(res, page(reliabilityPolicies))
    return true
  }
  if (method === 'POST' && path === '/reliability-policies') {
    const row = { id: rid('rp'), version: 1, draft_changed: true, updated_at: nowIso(), created_at: nowIso(), alias: '新 Alias', ...body } as unknown as ReliabilityRow
    reliabilityPolicies.push(row)
    ok(res, opResult(row, requestId))
    return true
  }
  match = path.match(/^\/reliability-policies\/([^/]+)\/recovery-decisions$/)
  if (match && method === 'GET') {
    const action = url.searchParams.get('action')
    const filtered = action ? recoveryDecisions.filter((item) => item.action === action) : recoveryDecisions
    ok(res, page(filtered))
    return true
  }
  match = path.match(/^\/reliability-policies\/([^/]+)\/impact$/)
  if (match && method === 'GET') {
    ok(res, { data: { impact_version: `v:${match[1]}:${url.searchParams.get('operation') ?? ''}`, entity_type: 'reliability_policy', entity_id: match[1], references: [], affected_alias_ids: [], can_delete: true, blockers: [] } })
    return true
  }
  match = path.match(/^\/reliability-policies\/([^/]+)\/(enable|disable)$/)
  if (match) {
    const row = reliabilityPolicies.find((item) => item.id === match![1])
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '策略不存在')
      return true
    }
    if (Number(body.version) !== row.version) {
      error(res, 409, 'CONFIG_VERSION_CONFLICT', '策略已被其他管理员修改')
      return true
    }
    row.enabled = match[2] === 'enable'
    row.version += 1
    row.draft_changed = true
    ok(res, opResult(row, requestId))
    return true
  }
  match = path.match(/^\/reliability-policies\/([^/]+)$/)
  if (match) {
    const row = reliabilityPolicies.find((item) => item.id === match![1])
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '策略不存在')
      return true
    }
    if (method === 'GET') {
      ok(res, { data: row })
      return true
    }
    if (method === 'PUT') {
      if (Number(body.version) !== row.version) {
        error(res, 409, 'CONFIG_VERSION_CONFLICT', '策略已被其他管理员修改')
        return true
      }
      Object.assign(row, body, { version: row.version + 1, draft_changed: true })
      ok(res, opResult(row, requestId))
      return true
    }
    if (method === 'DELETE') {
      reliabilityPolicies.splice(reliabilityPolicies.indexOf(row), 1)
      ok(res, opResult(null, requestId))
      return true
    }
  }

  // —— 熔断状态 ——
  if (method === 'GET' && path === '/circuits') {
    const state = url.searchParams.get('state')
    const filtered = state ? circuits.filter((item) => item.state === state) : [...circuits].sort((a, b) => {
      const order = { OPEN: 0, HALF_OPEN: 1, CLOSED: 2 } as Record<string, number>
      return (order[a.state] ?? 9) - (order[b.state] ?? 9)
    })
    ok(res, page(filtered))
    return true
  }
  match = path.match(/^\/circuits\/([^/]+)\/events$/)
  if (match && method === 'GET') {
    const triggerType = url.searchParams.get('trigger_type')
    const filtered = triggerType ? circuitEvents.filter((item) => item.trigger_type === triggerType) : circuitEvents
    ok(res, page(filtered))
    return true
  }
  match = path.match(/^\/circuits\/([^/]+)\/(open|recover|probe)$/)
  if (match) {
    const row = circuits.find((item) => item.id === match![1])
    const action = match[2]!
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '熔断记录不存在')
      return true
    }
    if (Number(body.state_version) !== row.state_version) {
      error(res, 409, 'CIRCUIT_STATE_CONFLICT', '熔断状态已被其他操作更新', { current_state_version: row.state_version })
      return true
    }
    if (action === 'probe') {
      ok(res, {
        data: {
          id: rid('chk'), version: 1, draft_changed: false, target_type: 'CIRCUIT_STATE', target_id: row.id,
          mode: 'MINIMAL_CHAT', status: 'SUCCEEDED', operator_id: 'user-admin', started_at: nowIso(),
          ended_at: nowIso(), total_ms: 640, usage: { prompt_tokens: 8, completion_tokens: 12, total_tokens: 20 },
          provider_request_id: 'req-probe-1', error_code: null, error_summary: null, created_at: nowIso(),
        },
      })
      return true
    }
    row.state_version += 1
    circuitVersion = row.state_version
    if (action === 'open') {
      row.state = 'OPEN'
      row.open_source = 'MANUAL'
      row.manual_reason = String(body.reason ?? '')
      row.opened_at = nowIso()
      row.next_probe_at = nowIso()
    } else {
      row.state = 'CLOSED'
      row.open_source = null
      row.manual_reason = null
      row.opened_at = null
      row.next_probe_at = null
      row.sample_count = 0
      row.failure_count = 0
    }
    ok(res, { data: row })
    return true
  }
  match = path.match(/^\/circuits\/([^/]+)$/)
  if (match && method === 'GET') {
    const row = circuits.find((item) => item.id === match![1])
    if (!row) {
      error(res, 404, 'OBJECT_NOT_FOUND', '熔断记录不存在')
      return true
    }
    ok(res, { data: row })
    return true
  }

  return false
}

export function handleGovernanceApi(
  req: { method?: string | undefined; url?: string | undefined; on?: ((event: string, cb: (chunk?: Buffer) => void) => void) | undefined },
  res: ServerResponse,
): Promise<boolean> {
  return handle(req, res)
}
