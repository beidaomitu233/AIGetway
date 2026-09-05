// Usage 与 Cost 查询（FE-034~036，附录 4.4.4.2）。
// 金额为十进制字符串；多币种不汇总，跨币种排序在服务端拒绝（C-009）。
import { request, type QueryValue } from './http'
import { getRuntimeConfig } from '@/app/runtimeConfig'

export interface UsageCostAmount {
  currency: string
  input_cost: string
  output_cost: string
  total_cost: string
}

export interface UsageSummaryResult {
  query_fingerprint: string
  data_updated_at: string
  request_count: number
  success_count: number
  failure_count: number
  cancelled_count: number
  queued_count: number
  stream_count: number
  stream_interrupted_count: number
  success_rate: number | null
  attempt_count: number
  initial_count: number
  retry_count: number
  credential_failover_count: number
  fallback_count: number
  half_open_probe_count: number
  total_tokens: number
  input_tokens: number
  output_tokens: number
  actual_tokens: number
  estimated_tokens: number
  actual_token_rate: number | null
  costs: UsageCostAmount[]
}

export interface UsageTrendPoint {
  bucket_start: string
  bucket_end: string
  request_count: number
  success_count: number
  failure_count: number
  success_rate: number | null
  attempt_count: number
  initial_count: number
  retry_count: number
  credential_failover_count: number
  fallback_count: number
  half_open_probe_count: number
  actual_tokens: number
  estimated_tokens: number
  total_tokens: number
  costs: UsageCostAmount[]
}

export interface UsageTrendResult {
  query_fingerprint: string
  data_updated_at: string
  points: UsageTrendPoint[]
}

export interface UsageGroupRow {
  dimension_type: string
  dimension_id: string | null
  dimension_name: string
  currency: string
  request_count: number
  success_count: number
  failure_count: number
  success_rate: number | null
  attempt_count: number
  initial_count: number
  retry_count: number
  credential_failover_count: number
  fallback_count: number
  half_open_probe_count: number
  actual_tokens: number
  estimated_tokens: number
  total_tokens: number
  input_cost: string
  output_cost: string
  total_cost: string
  request_share: number | null
  token_share: number | null
  cost_share: number | null
}

export interface UsageGroupResult {
  query_fingerprint: string
  data_updated_at: string
  total: number
  page: number
  page_size: number
  rows: UsageGroupRow[]
}

export interface UsageQuery extends Record<string, QueryValue> {
  start_at: string
  end_at: string
  granularity: 'HOUR' | 'DAY'
  application?: string[]
  project?: string[]
  tenant?: string[]
  alias_id?: string[]
  provider_id?: string[]
  provider_model_id?: string[]
  credential_pool_id?: string[]
  credential_id?: string[]
  trace_status?: string[]
  error_code?: string[]
  usage_source?: string[]
  requested_stream?: string
  currency?: string
  trend_metric?: string
  group_by?: string
  group_sort?: string
  group_page?: number
  group_page_size?: number
}

export function fetchUsageSummary(
  query: UsageQuery,
  signal: AbortSignal,
): Promise<UsageSummaryResult> {
  return request({ path: '/usage/summary', query, signal })
}

export function fetchUsageTrends(
  query: UsageQuery,
  signal: AbortSignal,
): Promise<UsageTrendResult> {
  return request({ path: '/usage/trends', query, signal })
}

export function fetchUsageGroups(
  query: UsageQuery,
  signal: AbortSignal,
): Promise<UsageGroupResult> {
  return request({ path: '/usage/groups', query, signal })
}

export interface UsageExportError extends Error {
  code: string
}

/** 导出使用当前聚合筛选；错误响应解析 error 信封后抛出（EXPORT_TOO_LARGE 等）。 */
export async function exportUsage(query: UsageQuery, signal: AbortSignal): Promise<void> {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      for (const item of value) params.append(key, String(item))
    } else {
      params.append(key, String(value))
    }
  }
  const url = `${getRuntimeConfig().adminApiBase}/usage/export?${params.toString()}`
  const response = await fetch(url, { signal, credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = '导出失败'
    try {
      const payload = (await response.json()) as { error?: { code?: string; message?: string } }
      if (payload.error?.code) code = payload.error.code
      if (payload.error?.message) message = payload.error.message
    } catch {
      // 非 JSON 错误响应保持默认
    }
    const error = new Error(message) as UsageExportError
    error.code = code
    throw error
  }
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)/i)
  const filename = match ? decodeURIComponent(match[1]) : 'usage.csv'
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}
