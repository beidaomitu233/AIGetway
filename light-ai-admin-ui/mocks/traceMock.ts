import type { ServerResponse } from 'node:http'
import type { Connect } from 'vite'

/**
 * FE-P05 契约夹具：Trace 列表/详情/导出 Mock（附录 4.4.4.1）。
 * 时间线由服务端按固定优先级排序，前端不重排。
 */

interface MockAttempt {
  id: string
  sequence: number
  attempt_type: string
  status: string
  provider_name_snapshot: string
  provider_model_name_snapshot: string
  model_id_snapshot: string
  credential_name_snapshot: string | null
  route_candidate_id: string | null
  started_at: string
  ended_at: string | null
  total_ms: number | null
  http_status: number | null
  endpoint_host: string | null
  provider_request_id: string | null
  finish_reason: string | null
  error_code: string | null
  error_category: string | null
  error_stage: string | null
  error_summary: string | null
  usage_source: string
  input_tokens: number
  output_tokens: number
  total_tokens: number
  input_price: string
  output_price: string
  price_unit: number
  currency: string
  input_cost: string
  output_cost: string
  cost: string
}

interface MockTrace {
  trace_id: string
  started_at: string
  ended_at: string | null
  source_mode: string
  access_credential_name: string | null
  application: string
  project: string | null
  tenant: string | null
  alias: string
  alias_id: string | null
  final_provider_id: string | null
  final_provider_name: string | null
  final_provider_model_id: string | null
  final_provider_model_name: string | null
  requested_stream: boolean
  status: string
  anomalous_running: boolean
  attempt_count: number
  retry_count: number
  credential_failover_count: number
  fallback_count: number
  queued_ms: number | null
  first_token_ms: number | null
  config_snapshot_no: number
  response_committed: boolean
  finish_reason: string | null
  input_tokens: number
  output_tokens: number
  total_tokens: number
  response_input_tokens: number | null
  response_output_tokens: number | null
  response_total_tokens: number | null
  total_cost: string
  currency: string
  usage_source: string | null
  error_code: string | null
  error_summary: string | null
  attempts: MockAttempt[]
}

function buildAttempt(sequence: number, overrides: Partial<MockAttempt>): MockAttempt {
  const input = 120 + sequence * 10
  return {
    id: `att-${sequence}`,
    sequence,
    attempt_type: sequence === 0 ? 'INITIAL' : 'RETRY',
    status: 'FAILED',
    provider_name_snapshot: 'OpenAI 生产',
    provider_model_name_snapshot: 'gpt-4o',
    model_id_snapshot: 'gpt-4o',
    credential_name_snapshot: 'sk-****a1b2',
    route_candidate_id: `cand-${sequence}`,
    started_at: '2026-09-05T02:00:00Z',
    ended_at: '2026-09-05T02:00:01Z',
    total_ms: 800 + sequence * 200,
    http_status: 502,
    endpoint_host: 'api.openai.com',
    provider_request_id: null,
    finish_reason: null,
    error_code: 'NETWORK_ERROR',
    error_category: 'NETWORK',
    error_stage: 'DISPATCH',
    error_summary: '连接中断',
    usage_source: 'ESTIMATED',
    input_tokens: input,
    output_tokens: 0,
    total_tokens: input,
    input_price: '0.00000250',
    output_price: '0.00001000',
    price_unit: 1000000,
    currency: 'USD',
    input_cost: '0.00030000',
    output_cost: '0.00000000',
    cost: '0.00030000',
    ...overrides,
  } as MockAttempt
}

function assembleTrace(
  base: Omit<MockTrace, 'input_tokens' | 'output_tokens' | 'total_tokens' | 'total_cost'>,
): MockTrace {
  const inputTokens = base.attempts.reduce((sum, a) => sum + a.input_tokens, 0)
  const outputTokens = base.attempts.reduce((sum, a) => sum + a.output_tokens, 0)
  const totalTokens = inputTokens + outputTokens
  const totalCostNum = base.attempts.reduce((sum, a) => sum + Number(a.cost), 0)
  return {
    ...base,
    input_tokens: inputTokens,
    output_tokens: outputTokens,
    total_tokens: totalTokens,
    total_cost: totalCostNum.toFixed(8),
  }
}

const successAttempt: MockAttempt = buildAttempt(2, {
  status: 'SUCCEEDED',
  attempt_type: 'FALLBACK',
  provider_name_snapshot: 'DeepSeek 备用',
  provider_model_name_snapshot: 'deepseek-chat',
  model_id_snapshot: 'deepseek-chat',
  endpoint_host: 'api.deepseek.com',
  http_status: 200,
  provider_request_id: 'req-batch-777',
  finish_reason: 'stop',
  error_code: null,
  error_category: null,
  error_stage: null,
  error_summary: null,
  usage_source: 'ACTUAL',
  input_tokens: 140,
  output_tokens: 45,
  total_tokens: 185,
  input_cost: '0.00035000',
  output_cost: '0.00045000',
  cost: '0.00080000',
})

const retryTrace: MockTrace = assembleTrace({
  trace_id: 'trace-retry-001',
  started_at: '2026-09-05T02:00:00Z',
  ended_at: '2026-09-05T02:00:03Z',
  source_mode: 'STANDALONE_SERVER',
  access_credential_name: 'ac-demo',
  application: 'app-demo',
  project: null,
  tenant: null,
  alias: 'chat-default',
  alias_id: 'alias-1',
  final_provider_id: 'prov-002',
  final_provider_name: 'DeepSeek 备用',
  final_provider_model_id: 'pm-2',
  final_provider_model_name: 'deepseek-chat',
  requested_stream: false,
  status: 'SUCCEEDED',
  anomalous_running: false,
  attempt_count: 3,
  retry_count: 1,
  credential_failover_count: 1,
  fallback_count: 1,
  queued_ms: 120,
  first_token_ms: 1900,
  config_snapshot_no: 12,
  response_committed: true,
  finish_reason: 'stop',
  response_input_tokens: 140,
  response_output_tokens: 45,
  response_total_tokens: 185,
  currency: 'USD',
  usage_source: 'MIXED',
  error_code: null,
  error_summary: null,
  attempts: [
    buildAttempt(0, {}),
    buildAttempt(1, {
      attempt_type: 'CREDENTIAL_FAILOVER',
      error_code: 'PROVIDER_RATE_LIMITED',
      error_category: 'RATE_LIMIT',
      error_stage: 'RESPONSE_HEADERS',
      http_status: 429,
      error_summary: 'Provider 限流',
    }),
    successAttempt,
  ],
})

const runningTrace: MockTrace = assembleTrace({
  trace_id: 'trace-running-002',
  started_at: '2026-09-05T02:10:00Z',
  ended_at: null,
  source_mode: 'STANDALONE_SERVER',
  access_credential_name: null,
  application: 'app-demo',
  project: 'proj-a',
  tenant: null,
  alias: 'chat-default',
  alias_id: 'alias-1',
  final_provider_id: null,
  final_provider_name: null,
  final_provider_model_id: null,
  final_provider_model_name: null,
  requested_stream: true,
  status: 'RUNNING',
  anomalous_running: false,
  attempt_count: 0,
  retry_count: 0,
  credential_failover_count: 0,
  fallback_count: 0,
  queued_ms: null,
  first_token_ms: null,
  config_snapshot_no: 12,
  response_committed: false,
  finish_reason: null,
  response_input_tokens: null,
  response_output_tokens: null,
  response_total_tokens: null,
  currency: 'USD',
  usage_source: null,
  error_code: null,
  error_summary: null,
  attempts: [],
})

const failedTrace: MockTrace = assembleTrace({
  trace_id: 'trace-failed-003',
  started_at: '2026-09-05T02:20:00Z',
  ended_at: '2026-09-05T02:20:02Z',
  source_mode: 'EMBEDDED',
  access_credential_name: null,
  application: 'app-demo',
  project: null,
  tenant: 'tenant-b',
  alias: 'chat-default',
  alias_id: 'alias-1',
  final_provider_id: 'prov-001',
  final_provider_name: 'OpenAI 生产',
  final_provider_model_id: 'pm-1',
  final_provider_model_name: 'gpt-4o',
  requested_stream: false,
  status: 'FAILED',
  anomalous_running: false,
  attempt_count: 1,
  retry_count: 0,
  credential_failover_count: 0,
  fallback_count: 0,
  queued_ms: null,
  first_token_ms: null,
  config_snapshot_no: 12,
  response_committed: false,
  finish_reason: null,
  response_input_tokens: null,
  response_output_tokens: null,
  response_total_tokens: null,
  currency: 'USD',
  usage_source: 'ESTIMATED',
  error_code: 'ALL_CANDIDATES_FAILED',
  error_summary: '所有候选尝试均失败',
  attempts: [buildAttempt(0, {})],
})

const mockTraces: MockTrace[] = [retryTrace, runningTrace, failedTrace]

function timelineFor(trace: MockTrace): Array<Record<string, unknown>> {
  const items: Array<Record<string, unknown>> = []
  const baseMs = new Date(trace.started_at).getTime()
  const push = (
    type: string,
    sourceId: string,
    sequence: number,
    attemptId: string | null = null,
    reasonCode: string | null = null,
  ) => {
    items.push({
      id: `tl-${type}-${sequence}`,
      type,
      occurred_at: new Date(baseMs + sequence * 300).toISOString(),
      source_id: sourceId,
      sequence,
      attempt_id: attemptId,
      reason_code: reasonCode,
    })
  }
  push('TRACE_CREATED', trace.trace_id, 0)
  trace.attempts.forEach((attempt, index) => {
    push('ATTEMPT_STARTED', attempt.id, attempt.sequence, attempt.id)
    push('ATTEMPT_ENDED', attempt.id, attempt.sequence + 0.5, attempt.id)
    const next = trace.attempts[index + 1]
    if (next && attempt.status !== 'SUCCEEDED') {
      push('RECOVERY_DECIDED', `rec-${attempt.sequence}`, attempt.sequence + 0.75, null, attempt.error_code)
    }
  })
  push('TRACE_ENDED', trace.trace_id, 99)
  return items
}

function traceListItem(trace: MockTrace): Record<string, unknown> {
  const { attempts: _attempts, ...rest } = trace
  return rest
}

function traceDetail(trace: MockTrace): Record<string, unknown> {
  return {
    trace: traceListItem(trace),
    request_summary: {
      source_mode: trace.source_mode,
      access_credential_name: trace.access_credential_name,
      request_user: 'user-42',
      client_ip: '10.1.2.3',
      user_agent: 'light-ai-sdk/1.0',
      config_snapshot_no: trace.config_snapshot_no,
      message_count: 3,
      system_message_count: 1,
      user_message_count: 1,
      assistant_message_count: 1,
      input_char_count: 560,
      requested_stream: trace.requested_stream,
      temperature: '0.7',
      top_p: null,
      max_tokens: 1024,
      stop_count: 0,
      provider_option_keys: [],
      content_sample_status: 'AVAILABLE',
      sampled_messages: null,
    },
    route_decisions: [
      {
        id: 'rd-1',
        sequence: 0,
        route_candidate_id: 'cand-0',
        decision: 'SELECTED',
        reason_code: 'PRIORITY',
        reason_detail: '按优先级选择首选候选',
        observed_status: 'AVAILABLE',
        created_at: trace.started_at,
      },
    ],
    queue_entries: [],
    capacity_reservations: trace.attempts.map((attempt) => ({
      id: `res-${attempt.sequence}`,
      attempt_id: attempt.id,
      policy_ids: ['lp-1'],
      reserved_tokens: 200,
      actual_tokens: attempt.total_tokens,
      status: 'SETTLED',
      release_reason: null,
      created_at: trace.started_at,
      settled_at: trace.ended_at,
    })),
    attempts: trace.attempts,
    recovery_decisions: trace.attempts
      .filter((attempt) => attempt.sequence < trace.attempts.length - 1)
      .map((attempt, index) => {
        const next = trace.attempts[attempt.sequence + 1]
        return {
          id: `rec-${attempt.sequence}`,
          sequence: attempt.sequence,
          source_attempt_id: attempt.id,
          action: next.attempt_type,
          reason_code: attempt.error_code,
          scheduled_delay_ms: null,
          target_route_candidate_id: next.route_candidate_id,
          target_credential_id: null,
          retries_used: index,
          credential_failovers_used: 0,
          fallbacks_used: 0,
          remaining_timeout_ms: 50000,
          created_at: attempt.ended_at,
        }
      }),
    circuit_events: [],
    timeline: timelineFor(trace),
    detail_expires_at: '2026-10-05T02:00:00Z',
  }
}

function csvEscape(value: unknown): string {
  const text = value === null || value === undefined ? '' : String(value)
  if (/[",\n\r]/.test(text) || value !== null && /^[=+\-@]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`
  }
  return text
}

export function handleTraceApi(req: Connect.IncomingMessage, url: URL, res: ServerResponse): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')

  if (path === '/traces' && method === 'GET') {
    const traceId = url.searchParams.get('trace_id') ?? ''
    let rows = mockTraces
    if (traceId !== '') {
      rows = rows.filter((trace) => trace.trace_id === traceId)
      sendJson(res, 200, {
        data: {
          items: rows.map(traceListItem),
          total: rows.length,
          page: 1,
          page_size: 20,
          sort: '-started_at',
          query_started_at: '2026-09-05T10:00:00Z',
          data_updated_at: '2026-09-05T10:00:01Z',
        },
      })
      return true
    }
    const statusFilter = url.searchParams.getAll('status')
    const errorFilter = url.searchParams.get('error_code') ?? ''
    const hasRetry = url.searchParams.get('has_retry')
    if (statusFilter.length > 0) rows = rows.filter((trace) => statusFilter.includes(trace.status))
    if (errorFilter !== '') rows = rows.filter((trace) => trace.error_code === errorFilter)
    if (hasRetry === 'true') rows = rows.filter((trace) => trace.attempts.length > 1)
    if (hasRetry === 'false') rows = rows.filter((trace) => trace.attempts.length <= 1)
    sendJson(res, 200, {
      data: {
        items: rows.map(traceListItem),
        total: rows.length,
        page: Number(url.searchParams.get('page') ?? '1'),
        page_size: Number(url.searchParams.get('page_size') ?? '20'),
        sort: url.searchParams.get('sort') ?? '-started_at',
        query_started_at: '2026-09-05T10:00:00Z',
        data_updated_at: '2026-09-05T10:00:01Z',
      },
    })
    return true
  }

  const detailMatch = path.match(/^\/traces\/([^/]+)$/)
  if (detailMatch && method === 'GET') {
    const trace = mockTraces.find((item) => item.trace_id === detailMatch[1])
    if (!trace) {
      sendError(res, 404, 'OBJECT_NOT_FOUND', '对象不存在或已删除')
      return true
    }
    const detail = traceDetail(trace) as Record<string, unknown>
    if (url.searchParams.get('include_diagnostics') === 'true') {
      ;(detail.request_summary as Record<string, unknown>).sampled_messages =
        '[{"role":"user","content":"（已脱敏样本）你好"}]'
    }
    sendJson(res, 200, { data: detail })
    return true
  }

  if (path === '/traces/export' && method === 'GET') {
    const huge = url.searchParams.get('anomalous_running') === 'huge'
    if (huge) {
      sendError(res, 422, 'EXPORT_TOO_LARGE', '当前筛选预计导出超过 100000 行，请缩小范围')
      return true
    }
    const rows = mockTraces.map(traceListItem)
    const header =
      'started_at,trace_id,source_mode,application,alias,status,attempt_count,total_tokens,total_cost,currency,error_code'
    const body = rows
      .map((row) =>
        [
          row.started_at,
          row.trace_id,
          row.source_mode,
          row.application,
          row.alias,
          row.status,
          row.attempt_count,
          row.total_tokens,
          row.total_cost,
          row.currency,
          row.error_code,
        ]
          .map(csvEscape)
          .join(','),
      )
      .join('\n')
    res.statusCode = 200
    res.setHeader('Content-Type', 'text/csv; charset=utf-8')
    res.setHeader(
      'Content-Disposition',
      `attachment; filename="traces-2026-09-05T00-00Z.csv"`,
    )
    res.end(`${header}\n${body}\n`)
    return true
  }
  return false
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify(body))
}

function sendError(res: ServerResponse, status: number, code: string, message: string): void {
  sendJson(res, status, { error: { code, type: 'api', message, retryable: false } })
}
