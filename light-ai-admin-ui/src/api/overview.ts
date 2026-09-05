// 运行概览查询（FE-031~033，附录 4.1.4）。
import { request, type QueryValue } from './http'

export interface OverviewFilterOptions {
  applications: string[]
  aliases: Array<{ id: string; name: string }>
  providers: Array<{ id: string; name: string }>
  currencies: string[]
}

export interface OverviewQuery extends Record<string, QueryValue> {
  start_at: string
  end_at: string
  application?: string
  alias_id?: string
  provider_id?: string
  currency?: string
  granularity?: 'HOUR' | 'DAY'
}

export interface CostAmount {
  currency: string
  amount: string
}

export interface OverviewSummary {
  request_count: number
  success_count: number
  failure_count: number
  stream_interrupted_count: number
  cancelled_count: number
  active_count: number
  success_rate: number | null
  average_total_ms: number | null
  p95_first_token_ms: number | null
  total_tokens: number | null
  actual_tokens: number | null
  estimated_tokens: number | null
  costs: CostAmount[]
  retry_count: number
  credential_failover_count: number
  fallback_count: number
  open_circuit_count: number
  unavailable_candidate_count: number
  data_updated_at: string
}

export interface OverviewTrendPoint {
  bucket_start: string
  bucket_end: string
  request_count: number
  success_count: number
  failure_count: number
  success_rate: number | null
  average_total_ms: number | null
  p95_first_token_ms: number | null
  actual_tokens: number
  estimated_tokens: number
  total_tokens: number
  costs: CostAmount[]
  retry_count: number
  fallback_count: number
}

export interface OverviewTrendResult {
  points: OverviewTrendPoint[]
  data_updated_at: string
}

export type OverviewExceptionItemType = 'CIRCUIT' | 'CANDIDATE' | 'CREDENTIAL' | 'TRACE' | string

export interface OverviewExceptionItem {
  item_type: OverviewExceptionItemType
  object_id: string
  object_name: string
  status: string
  error_code: string | null
  error_summary: string | null
  occurrence_count: number
  latest_at: string
  provider_name: string | null
  model_name: string | null
  alias_name: string | null
}

export interface OverviewExceptionResult {
  summary: {
    open_circuit_count: number
    half_open_circuit_count: number
    unavailable_candidate_count: number
    invalid_credential_count: number | null
    recent_failure_trace_count: number
  }
  items: OverviewExceptionItem[]
  data_updated_at: string
}

export function fetchOverviewFilters(
  signal?: AbortSignal,
): Promise<OverviewFilterOptions> {
  return request({ path: '/overview/filters', signal })
}

export function fetchOverviewSummary(
  query: OverviewQuery,
  signal: AbortSignal,
): Promise<OverviewSummary> {
  return request({ path: '/overview/summary', query, signal })
}

export function fetchOverviewTrends(
  query: OverviewQuery,
  signal: AbortSignal,
): Promise<OverviewTrendResult> {
  return request({ path: '/overview/trends', query, signal })
}

export function fetchOverviewExceptions(
  query: OverviewQuery,
  signal: AbortSignal,
): Promise<OverviewExceptionResult> {
  return request({ path: '/overview/exceptions', query, signal })
}
