import type { ServerResponse } from 'node:http'
import type { Connect } from 'vite'

/**
 * FE-P06 契约夹具：运行概览与 Usage 聚合 Mock（附录 4.1.4 / 4.4.4.2）。
 * 时间桶连续补零；多币种分线不做跨币种总额（C-009）；三接口同 fingerprint。
 */

const FINGERPRINT = 'fp-mock-001'
const UPDATED_AT = '2026-09-05T10:00:00Z'

function hourBuckets(startIso: string, count: number): Array<{ start: string; end: string }> {
  const startMs = new Date(startIso).getTime()
  return Array.from({ length: count }, (_, index) => ({
    start: new Date(startMs + index * 3600_000).toISOString(),
    end: new Date(startMs + (index + 1) * 3600_000).toISOString(),
  }))
}

function dayBuckets(startIso: string, count: number): Array<{ start: string; end: string }> {
  const startMs = new Date(startIso).getTime()
  return Array.from({ length: count }, (_, index) => ({
    start: new Date(startMs + index * 24 * 3600_000).toISOString(),
    end: new Date(startMs + (index + 1) * 24 * 3600_000).toISOString(),
  }))
}

export function handleOverviewApi(req: Connect.IncomingMessage, url: URL, res: ServerResponse): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')

  if (path === '/overview/filters' && method === 'GET') {
    sendJson(res, 200, {
      data: {
        applications: ['app-demo', 'app-billing'],
        aliases: [
          { id: 'alias-1', name: 'chat-default' },
          { id: 'alias-2', name: 'embed-docs' },
        ],
        providers: [
          { id: 'prov-001', name: 'OpenAI 生产' },
          { id: 'prov-002', name: 'DeepSeek 备用' },
        ],
        currencies: ['USD', 'CNY'],
      },
    })
    return true
  }

  if (path === '/overview/summary' && method === 'GET') {
    sendJson(res, 200, {
      data: {
        request_count: 428,
        success_count: 401,
        failure_count: 19,
        stream_interrupted_count: 2,
        cancelled_count: 3,
        active_count: 3,
        success_rate: 401 / (401 + 19 + 2),
        average_total_ms: 1840,
        p95_first_token_ms: 720,
        total_tokens: 98450,
        actual_tokens: 80220,
        estimated_tokens: 18230,
        costs: [
          { currency: 'USD', amount: '1.84230000' },
          { currency: 'CNY', amount: '3.10000000' },
        ],
        retry_count: 12,
        credential_failover_count: 5,
        fallback_count: 7,
        open_circuit_count: 1,
        unavailable_candidate_count: 2,
        data_updated_at: UPDATED_AT,
      },
    })
    return true
  }

  if (path === '/overview/trends' && method === 'GET') {
    const span = new Date(url.searchParams.get('end_at') ?? Date.now()).getTime() -
      new Date(url.searchParams.get('start_at') ?? Date.now()).getTime()
    const granularity = url.searchParams.get('granularity') ?? 'HOUR'
    const buckets =
      granularity === 'DAY'
        ? dayBuckets(url.searchParams.get('start_at') ?? '2026-09-01T00:00:00Z', Math.max(1, Math.min(30, Math.ceil(span / 86400000))))
        : hourBuckets(url.searchParams.get('start_at') ?? '2026-09-05T00:00:00Z', Math.max(1, Math.min(24, Math.ceil(span / 3600000))))
    const points = buckets.map((bucket, index) => {
      const failure = index === 2 ? 5 : 0
      const request = index === 1 ? 0 : 40 - index
      return {
        bucket_start: bucket.start,
        bucket_end: bucket.end,
        request_count: request,
        success_count: request - failure,
        failure_count: failure,
        success_rate: request === 0 ? null : (request - failure) / request,
        average_total_ms: request === 0 ? null : 1500 + index * 40,
        p95_first_token_ms: request === 0 ? null : 600 + index * 15,
        actual_tokens: request === 0 ? 0 : 9000 + index * 100,
        estimated_tokens: request === 0 ? 0 : 800,
        total_tokens: request === 0 ? 0 : 9800 + index * 100,
        costs: [
          { currency: 'USD', amount: request === 0 ? '0.00000000' : (0.08 + index * 0.005).toFixed(8) },
          ...(index % 4 === 0 ? [{ currency: 'CNY', amount: '0.30000000' }] : []),
        ],
        retry_count: failure > 0 ? 3 : 0,
        fallback_count: failure > 0 ? 2 : 0,
      }
    })
    sendJson(res, 200, { data: { points, data_updated_at: UPDATED_AT } })
    return true
  }

  if (path === '/overview/exceptions' && method === 'GET') {
    sendJson(res, 200, {
      data: {
        summary: {
          open_circuit_count: 1,
          half_open_circuit_count: 1,
          unavailable_candidate_count: 2,
          invalid_credential_count: 1,
          recent_failure_trace_count: 21,
        },
        items: [
          {
            item_type: 'CIRCUIT',
            object_id: 'circuit-1',
            object_name: 'gpt-4o + sk-****a1b2',
            status: 'OPEN',
            error_code: 'PROVIDER_SERVER_ERROR',
            error_summary: '连续失败触发熔断',
            occurrence_count: 14,
            latest_at: '2026-09-05T09:40:00Z',
            provider_name: 'OpenAI 生产',
            model_name: 'gpt-4o',
            alias_name: 'chat-default',
          },
          {
            item_type: 'CANDIDATE',
            object_id: 'cand-9',
            object_name: 'embed-docs 候选',
            status: 'UNAVAILABLE',
            error_code: null,
            error_summary: '凭证池无可用 Credential',
            occurrence_count: 3,
            latest_at: '2026-09-05T09:20:00Z',
            provider_name: 'DeepSeek 备用',
            model_name: 'deepseek-chat',
            alias_name: 'embed-docs',
          },
          {
            item_type: 'CREDENTIAL',
            object_id: 'cred-3',
            object_name: '备份密钥',
            status: 'INVALID',
            error_code: 'PROVIDER_AUTH_FAILED',
            error_summary: '鉴权失败',
            occurrence_count: 6,
            latest_at: '2026-09-05T08:50:00Z',
            provider_name: 'OpenAI 生产',
            model_name: null,
            alias_name: null,
          },
          {
            item_type: 'TRACE',
            object_id: 'trace-failed-003',
            object_name: 'trace-failed-003',
            status: 'FAILED',
            error_code: 'ALL_CANDIDATES_FAILED',
            error_summary: '所有候选尝试均失败',
            occurrence_count: 1,
            latest_at: '2026-09-05T09:55:00Z',
            provider_name: 'OpenAI 生产',
            model_name: 'gpt-4o',
            alias_name: 'chat-default',
          },
        ],
        data_updated_at: UPDATED_AT,
      },
    })
    return true
  }
  return false
}

export function handleUsageApi(req: Connect.IncomingMessage, url: URL, res: ServerResponse): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')
  if (!path.startsWith('/usage/')) return false

  if (path === '/usage/export' && method === 'GET') {
    if (url.searchParams.get('anomalous_running') === 'huge') {
      sendError(res, 422, 'EXPORT_TOO_LARGE', '当前筛选预计导出超过 100000 行，请缩小范围')
      return true
    }
    res.statusCode = 200
    res.setHeader('Content-Type', 'text/csv; charset=utf-8')
    res.setHeader('Content-Disposition', 'attachment; filename="usage-DAY-ALIAS.csv"')
    res.end('bucket_start,dimension_name,currency,request_count,total_tokens,total_cost\n')
    return true
  }

  if (method !== 'GET') return false

  if (path === '/usage/summary') {
    sendJson(res, 200, {
      data: {
        query_fingerprint: FINGERPRINT,
        data_updated_at: UPDATED_AT,
        request_count: 1284,
        success_count: 1202,
        failure_count: 64,
        cancelled_count: 12,
        queued_count: 6,
        stream_count: 300,
        stream_interrupted_count: 4,
        success_rate: 1202 / (1202 + 64 + 4),
        attempt_count: 1302,
        initial_count: 1284,
        retry_count: 10,
        credential_failover_count: 4,
        fallback_count: 4,
        half_open_probe_count: 0,
        total_tokens: 512300,
        input_tokens: 400100,
        output_tokens: 112200,
        actual_tokens: 460100,
        estimated_tokens: 52200,
        actual_token_rate: 460100 / 512300,
        costs: [
          { currency: 'USD', input_cost: '1.00025000', output_cost: '1.12200000', total_cost: '2.12225000' },
          { currency: 'CNY', input_cost: '2.00000000', output_cost: '2.50000000', total_cost: '4.50000000' },
        ],
      },
    })
    return true
  }

  if (path === '/usage/trends') {
    const buckets = dayBuckets('2026-08-30T00:00:00Z', 7)
    const points = buckets.map((bucket, index) => ({
      bucket_start: bucket.start,
      bucket_end: bucket.end,
      request_count: 150 + index * 20,
      success_count: 145 + index * 20,
      failure_count: 5,
      success_rate: (145 + index * 20) / (150 + index * 20),
      attempt_count: 152 + index * 20,
      initial_count: 150 + index * 20,
      retry_count: 2,
      credential_failover_count: 0,
      fallback_count: 0,
      half_open_probe_count: 0,
      actual_tokens: 50000 + index * 1000,
      estimated_tokens: 4000,
      total_tokens: 54000 + index * 1000,
      costs: [
        {
          currency: 'USD',
          input_cost: (0.4 + index * 0.01).toFixed(8),
          output_cost: (0.5 + index * 0.01).toFixed(8),
          total_cost: (0.9 + index * 0.02).toFixed(8),
        },
      ],
    }))
    sendJson(res, 200, { data: { query_fingerprint: FINGERPRINT, data_updated_at: UPDATED_AT, points } })
    return true
  }

  if (path === '/usage/groups') {
    const rows = [
      {
        dimension_type: 'ALIAS',
        dimension_id: 'alias-1',
        dimension_name: 'chat-default',
        currency: 'USD',
        request_count: 900,
        success_count: 880,
        failure_count: 20,
        success_rate: 880 / 900,
        attempt_count: 910,
        initial_count: 900,
        retry_count: 6,
        credential_failover_count: 2,
        fallback_count: 2,
        half_open_probe_count: 0,
        actual_tokens: 300000,
        estimated_tokens: 30000,
        total_tokens: 330000,
        input_cost: '0.66000000',
        output_cost: '0.94000000',
        total_cost: '1.60000000',
        request_share: 0.7,
        token_share: 0.64,
        cost_share: 0.75,
      },
      {
        dimension_type: 'ALIAS',
        dimension_id: 'alias-2',
        dimension_name: 'embed-docs',
        currency: 'CNY',
        request_count: 384,
        success_count: 322,
        failure_count: 44,
        success_rate: 322 / 384,
        attempt_count: 392,
        initial_count: 384,
        retry_count: 4,
        credential_failover_count: 2,
        fallback_count: 2,
        half_open_probe_count: 0,
        actual_tokens: 160100,
        estimated_tokens: 22200,
        total_tokens: 182300,
        input_cost: '2.00000000',
        output_cost: '2.50000000',
        total_cost: '4.50000000',
        request_share: 0.3,
        token_share: 0.36,
        cost_share: 0.25,
      },
    ]
    sendJson(res, 200, {
      data: {
        query_fingerprint: FINGERPRINT,
        data_updated_at: UPDATED_AT,
        total: rows.length,
        page: 1,
        page_size: Number(url.searchParams.get('group_page_size') ?? '20'),
        rows,
      },
    })
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
