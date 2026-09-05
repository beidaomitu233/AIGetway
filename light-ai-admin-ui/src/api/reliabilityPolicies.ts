// 可靠性策略、系统默认值与恢复决策（FE-021/022，附录 4.3.5.2）。
// circuit_failure_rate 以 0—1 小数传输，界面百分比在显示/提交边界转换。

import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'

export interface ReliabilityPolicyListItem {
  id: string
  name: string
  alias_id: string
  alias: string
  connect_timeout_ms: number
  first_token_timeout_ms: number
  total_timeout_ms: number
  max_retries: number
  max_credential_failovers: number
  max_fallbacks: number
  fallback_enabled: boolean
  circuit_window_seconds: number
  circuit_min_requests: number
  circuit_failure_rate: string
  circuit_open_seconds: number
  enabled: boolean
  draft_changed: boolean
  updated_at: string
  version: number
}

export interface ReliabilityPolicyDetail extends ReliabilityPolicyListItem {
  initial_backoff_ms: number
  backoff_multiplier: string
  jitter_percent: number
  respect_retry_after: boolean
  max_retry_after_ms: number
  circuit_half_open_probes: number
  circuit_half_open_successes: number
  created_at: string
}

export interface ReliabilityPolicyListQuery {
  keyword?: string | undefined
  alias_id?: string | undefined
  fallback_enabled?: boolean | undefined
  enabled?: boolean | undefined
  draft_changed?: boolean | undefined
  page?: number | undefined
  page_size?: number | undefined
  sort?: string | undefined
}

export interface ReliabilityPolicyCommand {
  name: string
  alias_id: string
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
  version?: number | undefined
}

export type RecoveryAction = 'RETRY' | 'CREDENTIAL_FAILOVER' | 'FALLBACK' | 'FAIL'

export interface RecoveryDecision {
  id: string
  trace_id: string
  sequence: number
  action: RecoveryAction
  reason_code: string
  source_attempt_id: string
  scheduled_delay_ms: number
  target_route_candidate_id: string | null
  target_credential_id: string | null
  retries_used: number
  credential_failovers_used: number
  fallbacks_used: number
  remaining_timeout_ms: number
  created_at: string
  source_attempt: {
    id: string
    sequence: number
    attempt_type: string
    error_code: string | null
    started_at: string
    ended_at: string
  } | null
}

export function fetchReliabilityPolicies(
  query: ReliabilityPolicyListQuery,
  signal?: AbortSignal,
): Promise<PageResult<ReliabilityPolicyListItem>> {
  return request<PageResult<ReliabilityPolicyListItem>>({
    path: '/reliability-policies',
    query: query as Record<string, QueryValue>,
    signal,
  })
}

export function fetchReliabilityPolicy(id: string, signal?: AbortSignal): Promise<ReliabilityPolicyDetail> {
  return request<ReliabilityPolicyDetail>({ path: `/reliability-policies/${id}`, signal })
}

export function fetchReliabilityDefault(signal?: AbortSignal): Promise<ReliabilityPolicyDetail> {
  return request<ReliabilityPolicyDetail>({ path: '/reliability-policies/default', signal })
}

export function createReliabilityPolicy(command: ReliabilityPolicyCommand): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: '/reliability-policies', method: 'POST', body: command })
}

export function updateReliabilityPolicy(
  id: string,
  command: ReliabilityPolicyCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({
    path: `/reliability-policies/${id}`,
    method: 'PUT',
    body: command,
  })
}

export function enableReliabilityPolicy(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/reliability-policies/${id}/enable`, method: 'POST', body: { version } })
}

export function disableReliabilityPolicy(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/reliability-policies/${id}/disable`, method: 'POST', body: { version } })
}

export function deleteReliabilityPolicy(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/reliability-policies/${id}`, method: 'DELETE', body: { version } })
}

export function fetchRecoveryDecisions(
  policyId: string,
  query: {
    trace_id?: string | undefined
    action?: string | undefined
    reason_code?: string | undefined
    started_from?: string | undefined
    started_to?: string | undefined
    page?: number | undefined
    page_size?: number | undefined
  },
  signal?: AbortSignal,
): Promise<PageResult<RecoveryDecision>> {
  return request<PageResult<RecoveryDecision>>({
    path: `/reliability-policies/${policyId}/recovery-decisions`,
    query: query as Record<string, QueryValue>,
    signal,
  })
}
