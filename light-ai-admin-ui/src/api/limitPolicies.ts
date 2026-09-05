// 限流策略与运行用量（FE-019/020，附录 4.3.5.1）。
// 查询与写入统一使用 overflow_strategy（C-004 口径）；null 表示无上限，0 不代替空。

import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'

export type ScopeType = 'MODEL_ALIAS' | 'PROVIDER_MODEL' | 'CREDENTIAL'
export type OverflowStrategy = 'REJECT' | 'QUEUE'
export type CounterStoreStatus = 'OK' | 'DEGRADED' | 'UNAVAILABLE'

export interface LimitPolicyListItem {
  id: string
  name: string
  scope_type: ScopeType
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
  overflow_strategy: OverflowStrategy
  window_end: string | null
  counter_store_status: CounterStoreStatus
  enabled: boolean
  draft_changed: boolean
  updated_at: string
  version: number
}

export interface LimitPolicyDetail extends LimitPolicyListItem {
  queue_timeout_ms: number | null
  window_seconds: number
  created_at: string
}

export interface LimitPolicyListQuery {
  keyword?: string | undefined
  scope_type?: string | undefined
  scope_id?: string | undefined
  overflow_strategy?: string | undefined
  enabled?: boolean | undefined
  draft_changed?: boolean | undefined
  page?: number | undefined
  page_size?: number | undefined
  sort?: string | undefined
}

export interface LimitPolicyCommand {
  name: string
  scope_type: ScopeType
  scope_id: string
  rpm_limit?: number | null
  tpm_limit?: number | null
  concurrent_limit?: number | null
  overflow_strategy: OverflowStrategy
  queue_timeout_ms?: number | null
  queue_max_size?: number | null
  enabled: boolean
  version?: number | undefined
}

export interface LimitUsageSnapshot {
  policy_id: string
  scope_type: ScopeType
  scope_name: string
  rpm_used: number
  rpm_limit: number | null
  tpm_reserved: number
  tpm_confirmed: number
  tpm_limit: number | null
  concurrency_used: number
  concurrent_limit: number | null
  queue_length: number
  queue_max_size: number | null
  window_start: string | null
  window_end: string | null
  counter_store_status: CounterStoreStatus
  data_updated_at: string
}

export type QueueEntryStatus = 'WAITING' | 'ACQUIRED' | 'TIMEOUT' | 'REJECTED' | 'CANCELLED'

export interface QueueEntry {
  id: string
  trace_id: string
  alias_id: string
  alias_name: string
  sequence: number
  status: QueueEntryStatus
  blocking_policy_ids: string[]
  estimated_tokens: number
  enqueued_at: string
  deadline_at: string | null
}

export interface ScopeOption {
  id: string
  label: string
}

/** 限流作用对象选项：按 scope_type 加载对应实体（附录 4.3.1.2）。 */
export async function fetchScopeOptions(scopeType: ScopeType, signal?: AbortSignal): Promise<ScopeOption[]> {
  if (scopeType === 'MODEL_ALIAS') {
    const page = await request<PageResult<{ id: string; alias: string; display_name: string }>>({
      path: '/model-aliases',
      query: { page: 1, page_size: 100, sort: 'alias' },
      signal,
    })
    return page.items.map((item) => ({ id: item.id, label: `${item.display_name}（${item.alias}）` }))
  }
  if (scopeType === 'PROVIDER_MODEL') {
    const page = await request<PageResult<{ id: string; display_name: string; model_id: string }>>({
      path: '/provider-models',
      query: { page: 1, page_size: 100, sort: 'updated_at' },
      signal,
    })
    return page.items.map((item) => ({ id: item.id, label: `${item.display_name}（${item.model_id}）` }))
  }
  const pools = await request<PageResult<{ id: string; name: string }>>({
    path: '/credential-pools',
    query: { page: 1, page_size: 100, sort: 'name' },
    signal,
  })
  const options: ScopeOption[] = []
  for (const pool of pools.items) {
    const credentials = await request<PageResult<{ id: string; name: string; masked_value: string }>>({
      path: `/credential-pools/${pool.id}/credentials`,
      query: { page: 1, page_size: 100, sort: 'name' },
      signal,
    })
    for (const item of credentials.items) {
      options.push({ id: item.id, label: `${item.name}（${item.masked_value} · ${pool.name}）` })
    }
  }
  return options
}

export function fetchLimitPolicies(
  query: LimitPolicyListQuery,
  signal?: AbortSignal,
): Promise<PageResult<LimitPolicyListItem>> {
  return request<PageResult<LimitPolicyListItem>>({
    path: '/limit-policies',
    query: query as Record<string, QueryValue>,
    signal,
  })
}

export function fetchLimitPolicy(id: string, signal?: AbortSignal): Promise<LimitPolicyDetail> {
  return request<LimitPolicyDetail>({ path: `/limit-policies/${id}`, signal })
}

export function createLimitPolicy(command: LimitPolicyCommand): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: '/limit-policies', method: 'POST', body: command })
}

export function updateLimitPolicy(
  id: string,
  command: LimitPolicyCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/limit-policies/${id}`, method: 'PUT', body: command })
}

export function enableLimitPolicy(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/limit-policies/${id}/enable`, method: 'POST', body: { version } })
}

export function disableLimitPolicy(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/limit-policies/${id}/disable`, method: 'POST', body: { version } })
}

export function deleteLimitPolicy(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/limit-policies/${id}`, method: 'DELETE', body: { version } })
}

export function fetchLimitUsage(id: string, signal?: AbortSignal): Promise<LimitUsageSnapshot> {
  return request<LimitUsageSnapshot>({ path: `/limit-policies/${id}/usage`, signal })
}

export function fetchLimitQueue(
  id: string,
  query: { status?: string | undefined; started_from?: string | undefined; started_to?: string | undefined; page?: number | undefined; page_size?: number | undefined },
  signal?: AbortSignal,
): Promise<PageResult<QueueEntry>> {
  return request<PageResult<QueueEntry>>({
    path: `/limit-policies/${id}/queue`,
    query: query as Record<string, QueryValue>,
    signal,
  })
}
