// Trace 列表、详情与导出（FE-025~030，附录 4.4.4.1）。
// 金额为十进制字符串，前端不做浮点运算；开发/只读的凭证与诊断字段由后端裁剪（C-012）。
import { request, type QueryValue } from './http'
import type { PageResult } from './contracts'
import { getRuntimeConfig } from '@/app/runtimeConfig'

export type TraceStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'STREAM_INTERRUPTED'

export interface TraceListItem {
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
  status: TraceStatus | string
  anomalous_running: boolean
  attempt_count: number
  retry_count: number
  credential_failover_count: number
  fallback_count: number
  queued_ms: number | null
  first_token_ms: number | null
  total_ms: number | null
  usage_source: string | null
  input_tokens: number | null
  output_tokens: number | null
  total_tokens: number | null
  total_cost: string | null
  currency: string | null
  error_code: string | null
}

export interface TraceRequestSummary {
  source_mode: string
  access_credential_name: string | null
  request_user: string | null
  client_ip: string | null
  user_agent: string | null
  config_snapshot_no: number
  message_count: number
  system_message_count: number
  user_message_count: number
  assistant_message_count: number
  input_char_count: number
  requested_stream: boolean
  temperature: string | null
  top_p: string | null
  max_tokens: number | null
  stop_count: number | null
  provider_option_keys: string[]
  content_sample_status: string
  sampled_messages: string | null
}

export interface AttemptItem {
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
  provider_started_at: string | null
  response_headers_at: string | null
  first_token_at: string | null
  ended_at: string | null
  dispatch_ms: number | null
  response_header_ms: number | null
  first_token_ms: number | null
  total_ms: number | null
  endpoint_host: string | null
  http_status: number | null
  provider_request_id: string | null
  response_committed: boolean | null
  finish_reason: string | null
  error_category: string | null
  error_stage: string | null
  error_code: string | null
  error_summary: string | null
  retryable: boolean | null
  retry_after_ms: number | null
  usage_source: string | null
  input_tokens: number | null
  output_tokens: number | null
  total_tokens: number | null
  input_price: string | null
  output_price: string | null
  price_unit: number | null
  currency: string | null
  input_cost: string | null
  output_cost: string | null
  cost: string | null
}

export interface RouteDecisionItem {
  id: string
  sequence: number
  route_candidate_id: string | null
  decision: string
  reason_code: string | null
  reason_detail: string | null
  observed_status: string | null
  created_at: string
}

export interface QueueEntryItem {
  id: string
  blocking_policy_ids: string[]
  estimated_tokens: number | null
  sequence: number
  status: string
  enqueued_at: string
  deadline_at: string | null
  acquired_at: string | null
  ended_at: string | null
  wake_reason: string | null
  error_code: string | null
}

export interface CapacityReservationItem {
  id: string
  attempt_id: string | null
  policy_ids: string[]
  reserved_tokens: number | null
  actual_tokens: number | null
  status: string
  release_reason: string | null
  created_at: string
  settled_at: string | null
}

export interface RecoveryDecisionItem {
  id: string
  sequence: number
  source_attempt_id: string
  action: string
  reason_code: string | null
  scheduled_delay_ms: number | null
  target_route_candidate_id: string | null
  target_credential_id: string | null
  retries_used: number | null
  credential_failovers_used: number | null
  fallbacks_used: number | null
  remaining_timeout_ms: number | null
  created_at: string
}

export interface CircuitEventItem {
  id: string
  circuit_id: string
  from_state: string
  to_state: string
  trigger_type: string
  error_code: string | null
  reason: string | null
  trigger_trace_id: string | null
  created_at: string
}

export interface TimelineItem {
  id: string
  type: string
  occurred_at: string
  source_id: string
  sequence: number
  attempt_id: string | null
  reason_code: string | null
}

export interface TraceDetail {
  trace: TraceListItem & {
    config_snapshot_no: number
    response_committed: boolean | null
    finish_reason: string | null
    response_total_tokens: number | null
    response_input_tokens: number | null
    response_output_tokens: number | null
    updated_by: string
  }
  request_summary: TraceRequestSummary
  route_decisions: RouteDecisionItem[]
  queue_entries: QueueEntryItem[]
  capacity_reservations: CapacityReservationItem[]
  attempts: AttemptItem[]
  recovery_decisions: RecoveryDecisionItem[]
  circuit_events: CircuitEventItem[]
  timeline: TimelineItem[]
  detail_expires_at: string | null
}

export interface TraceListQueryParams extends Record<string, QueryValue> {
  start_at?: string
  end_at?: string
  trace_id?: string
  application?: string[]
  alias_id?: string[]
  provider_id?: string[]
  provider_model_id?: string[]
  status?: string[]
  source_mode?: string[]
  error_code?: string[]
  requested_stream?: string
  usage_source?: string[]
  has_retry?: string
  has_credential_failover?: string
  has_fallback?: string
  min_total_ms?: number
  max_total_ms?: number
  anomalous_running?: string
  client_ip?: string
  sort?: string
  page?: number
  page_size?: number
}

export function fetchTraces(
  query: TraceListQueryParams,
  signal: AbortSignal,
): Promise<PageResult<TraceListItem>> {
  return request({ path: '/traces', query, signal })
}

/** 诊断样本按需显式请求（C-011）；无权限返回 403。 */
export function fetchTrace(
  traceId: string,
  options: { includeDiagnostics?: boolean; signal?: AbortSignal } = {},
): Promise<TraceDetail> {
  const { includeDiagnostics, signal } = options
  const query: Record<string, QueryValue> = {}
  if (includeDiagnostics) query.include_diagnostics = true
  return request({ path: `/traces/${traceId}`, query, signal })
}

/** 导出使用与列表一致的查询；返回可中止的 fetch，调用方负责取消与错误转换。 */
export async function exportTraces(
  query: TraceListQueryParams,
  signal: AbortSignal,
): Promise<void> {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      for (const item of value) params.append(key, String(item))
    } else {
      params.append(key, String(value))
    }
  }
  const url = `${getRuntimeConfig().adminApiBase}/traces/export?${params.toString()}`
  const response = await fetch(url, { signal, credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = '导出失败'
    let retryable = false
    try {
      const payload = (await response.json()) as { error?: { code?: string; message?: string; retryable?: boolean } }
      if (payload.error?.code) code = payload.error.code
      if (payload.error?.message) message = payload.error.message
      retryable = payload.error?.retryable === true
    } catch {
      // 非 JSON 错误响应保持默认
    }
    const error = new Error(message) as Error & { code: string; retryable: boolean }
    error.code = code
    error.retryable = retryable
    throw error
  }
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)/i)
  const filename = match ? decodeURIComponent(match[1]) : 'traces.csv'
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}
