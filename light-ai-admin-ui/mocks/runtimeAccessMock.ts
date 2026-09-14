import type { ServerResponse } from 'node:http'
import type { Connect } from 'vite'

/** FE-P08 契约夹具：运行参数、保留影响与审计日志 Mock。 */

let configVersion = 5

const runtimeConfig = () => ({
  version: configVersion,
  timezone: 'Asia/Shanghai',
  timezone_locked: true,
  trace_retention_days: 30,
  usage_retention_days: 365,
  audit_retention_days: 365,
  diagnostic_sample_retention_days: 7,
  dashboard_refresh_seconds: 30,
  max_message_chars: 100000,
  max_request_chars: 500000,
  diagnostic_sampling_enabled: false,
  diagnostic_sample_rate: '0',
  diagnostic_sample_max_chars: 1000,
  client_ip_recording_enabled: false,
  trusted_proxy_cidrs: ['10.0.0.0/8'],
  publish_instance_timeout_seconds: 60,
  instance_stale_seconds: 60,
  current_snapshot_no: 13,
  published_at: '2026-09-05T09:00:00Z',
  draft_changed: false,
  draft_revision: 37,
  last_modified_by_name: '系统管理员',
  updated_at: '2026-09-05T09:00:00Z',
})

const auditLogs = [
  {
    id: 'aud-1',
    created_at: '2026-09-05T09:00:00Z',
    request_id: 'req-audit-001',
    operator_id: 'user-admin',
    operator_name: '系统管理员',
    operator_role: 'SYSTEM_ADMIN',
    operation: 'UPDATE',
    operation_reason: null,
    entity_type: 'provider',
    entity_id: 'prov-001',
    entity_name: 'OpenAI 生产',
    change_summary: 'read_timeout_ms',
    source_mode: 'ADMIN_UI',
    result: 'SUCCEEDED',
    error_code: null,
    duration_ms: 45,
  },
  {
    id: 'aud-2',
    created_at: '2026-09-05T08:40:00Z',
    request_id: 'req-audit-002',
    operator_id: 'user-admin',
    operator_name: '系统管理员',
    operator_role: 'SYSTEM_ADMIN',
    operation: 'PUBLISH',
    operation_reason: '集成验证发布',
    entity_type: 'config_snapshot',
    entity_id: '13',
    entity_name: '快照 #13',
    change_summary: '',
    source_mode: 'ADMIN_UI',
    result: 'FAILED',
    error_code: 'CONFIG_VALIDATION_EXPIRED',
    duration_ms: 80,
  },
]

function auditDetail() {
  return {
    ...auditLogs[0],
    client_ip: '10.1.1.2',
    user_agent: 'Mozilla/5.0',
    before_version: 4,
    after_version: 5,
    changed_fields: [
      { field_name: 'read_timeout_ms', before_value: '120000', after_value: '90000', sensitive: false },
      { field_name: 'api_key', before_value: null, after_value: null, sensitive: true },
    ],
    error_summary: null,
  }
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

function readBody(req: Connect.IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (chunk) => {
      raw += String(chunk)
    })
    req.on('end', () => {
      try {
        resolve(raw === '' ? {} : (JSON.parse(raw) as Record<string, unknown>))
      } catch {
        resolve({})
      }
    })
  })
}

function csvEscape(value: unknown): string {
  const text = value === null || value === undefined ? '' : String(value)
  if (/[",\n\r]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`
  }
  return text
}

export function handleRuntimeConfigApi(
  req: Connect.IncomingMessage,
  url: URL,
  res: ServerResponse,
): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')

  if (path === '/runtime-config' && method === 'GET') {
    sendJson(res, 200, { data: runtimeConfig() })
    return true
  }
  if (path === '/runtime-config/retention-impact' && method === 'POST') {
    void readBody(req).then((body) => {
      sendJson(res, 200, {
        data: {
          impact_version: `impact-${Date.now()}`,
          estimated_at: new Date().toISOString(),
          expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
          target_values: {
            trace_retention_days: Number(body.trace_retention_days ?? 30),
            usage_retention_days: Number(body.usage_retention_days ?? 365),
            audit_retention_days: Number(body.audit_retention_days ?? 365),
            diagnostic_sample_retention_days: Number(body.diagnostic_sample_retention_days ?? 7),
          },
          counts: { trace: 128, usage: 96, audit: 2100, sample: 18 },
          earliest_remaining_at: new Date(Date.now() - 10 * 24 * 3600_000).toISOString(),
        },
      })
    })
    return true
  }
  if (path === '/runtime-config' && method === 'PUT') {
    void readBody(req).then((body) => {
      if (typeof body.version === 'number' && body.version !== configVersion) {
        sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '运行参数已被其他管理员修改')
        return
      }
      if (body.confirmed_impact_version === 'expired') {
        sendError(res, 409, 'RETENTION_IMPACT_EXPIRED', '保留期影响估算已过期')
        return
      }
      configVersion += 1
      sendJson(res, 200, {
        data: {
          id: 'runtime-config',
          version: configVersion,
          entity: null,
          draft_changed: true,
          draft_revision: 38,
          request_id: `mock-${Date.now()}`,
        },
      })
    })
    return true
  }
  return false
}

export function handleAuditApi(
  req: Connect.IncomingMessage,
  url: URL,
  res: ServerResponse,
): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')

  if (path === '/audit-logs' && method === 'GET') {
    const requestId = url.searchParams.get('request_id') ?? ''
    const result = url.searchParams.get('result') ?? ''
    let rows = auditLogs
    if (requestId !== '') rows = rows.filter((row) => row.request_id === requestId)
    if (result !== '') rows = rows.filter((row) => row.result === result)
    sendJson(res, 200, {
      data: {
        items: rows,
        total: rows.length,
        page: 1,
        page_size: 20,
        sort: '-created_at',
        query_started_at: '2026-09-05T10:00:00Z',
        data_updated_at: '2026-09-05T10:00:01Z',
      },
    })
    return true
  }
  const detailMatch = path.match(/^\/audit-logs\/([^/]+)$/)
  if (detailMatch && method === 'GET') {
    const row = auditLogs.find((item) => item.id === detailMatch[1])
    if (!row) {
      sendError(res, 404, 'OBJECT_NOT_FOUND', '对象不存在或已删除')
      return true
    }
    sendJson(res, 200, { data: { ...row, ...auditDetail(), id: row.id } })
    return true
  }
  if (path === '/audit-logs/export' && method === 'GET') {
    if (url.searchParams.get('huge') === '1') {
      sendError(res, 422, 'EXPORT_TOO_LARGE', '当前筛选预计导出超过 100000 行，请缩小范围')
      return true
    }
    const header = 'created_at,request_id,operator_name,operation,entity_name,result,error_code'
    const body = auditLogs
      .map((row) =>
        [
          row.created_at,
          row.request_id,
          row.operator_name,
          row.operation,
          row.entity_name,
          row.result,
          row.error_code,
        ]
          .map(csvEscape)
          .join(','),
      )
      .join('\n')
    res.statusCode = 200
    res.setHeader('Content-Type', 'text/csv; charset=utf-8')
    res.setHeader('Content-Disposition', 'attachment; filename="audit-logs.csv"')
    res.end(`${header}\n${body}\n`)
    return true
  }
  return false
}
