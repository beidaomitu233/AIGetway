// 熔断状态、事件与人工操作（FE-023/024，附录 4.3.5.3）。
// 人工 open/recover/probe 必须提交页面最后读取的 state_version；CAS 冲突返回
// CIRCUIT_STATE_CONFLICT 与 current_state_version；未收敛命令不显示已完成（C-013）。

import { request, type QueryValue } from './http'
import type { PageResult } from './contracts'
import type { ProviderCheckRecord } from './credentials'

export type CircuitState = 'CLOSED' | 'OPEN' | 'HALF_OPEN'
export type OpenSource = 'AUTO' | 'MANUAL'

export interface CircuitStateListItem {
  id: string
  provider_id: string
  provider_name: string
  provider_model_id: string
  provider_model_name: string
  credential_id: string | null
  credential_name: string | null
  credential_masked_value: string | null
  state: CircuitState
  state_version: number
  open_source: OpenSource | null
  sample_count: number
  failure_count: number
  failure_rate: string
  half_open_in_flight: number
  half_open_success_count: number
  opened_at: string | null
  next_probe_at: string | null
  last_error_code: string | null
  updated_at: string
}

export interface CircuitPolicySnapshot {
  policy_id: string | null
  snapshot_no: number | null
  circuit_window_seconds: number
  circuit_min_requests: number
  circuit_failure_rate: string
  circuit_open_seconds: number
  circuit_half_open_probes: number
  circuit_half_open_successes: number
}

export interface CircuitFailureSample {
  trace_id: string
  attempt_id: string
  ended_at: string
  error_code: string | null
  total_ms: number
}

export interface CircuitProbeRecord {
  id: string
  kind: 'MANUAL_PROBE' | 'HALF_OPEN_PROBE'
  status: 'SUCCEEDED' | 'FAILED'
  started_at: string
  total_ms: number
  error_code: string | null
}

export interface CircuitPendingCommand {
  command_id: string
  action: string
  status: string
  created_at: string
}

export interface CircuitStateDetail extends CircuitStateListItem {
  manual_reason: string | null
  manual_open_until: string | null
  operator: string | null
  policy_snapshot: CircuitPolicySnapshot
  window_samples: CircuitFailureSample[]
  recent_probes: CircuitProbeRecord[]
  pending_command: CircuitPendingCommand | null
}

export interface CircuitEvent {
  id: string
  event_key: string
  from_state: CircuitState
  to_state: CircuitState
  trigger_type: string
  trigger_trace_id: string | null
  command_id: string | null
  error_code: string | null
  reason: string | null
  occurred_at: string
}

export interface CircuitListQuery {
  state?: string | undefined
  provider_id?: string | undefined
  provider_model_id?: string | undefined
  credential_id?: string | undefined
  open_source?: string | undefined
  has_recent_failure?: boolean | undefined
  page?: number | undefined
  page_size?: number | undefined
  sort?: string | undefined
}

export interface ManualOpenCommand {
  action: 'MANUAL_OPEN'
  reason: string
  open_seconds?: number | undefined
  state_version: number
}

export interface ManualRecoverCommand {
  action: 'MANUAL_RECOVER'
  reason: string
  state_version: number
}

export interface ProbeNowCommand {
  action: 'PROBE_NOW'
  state_version: number
  timeout_ms?: number | undefined
}

export function fetchCircuits(query: CircuitListQuery, signal?: AbortSignal): Promise<PageResult<CircuitStateListItem>> {
  return request<PageResult<CircuitStateListItem>>({
    path: '/circuits',
    query: query as Record<string, QueryValue>,
    signal,
  })
}

export function fetchCircuit(id: string, signal?: AbortSignal): Promise<CircuitStateDetail> {
  return request<CircuitStateDetail>({ path: `/circuits/${id}`, signal })
}

export function fetchCircuitEvents(
  id: string,
  query: { trigger_type?: string | undefined; started_from?: string | undefined; started_to?: string | undefined; page?: number | undefined; page_size?: number | undefined },
  signal?: AbortSignal,
): Promise<PageResult<CircuitEvent>> {
  return request<PageResult<CircuitEvent>>({
    path: `/circuits/${id}/events`,
    query: query as Record<string, QueryValue>,
    signal,
  })
}

export function openCircuit(id: string, command: ManualOpenCommand): Promise<CircuitStateDetail> {
  return request<CircuitStateDetail>({ path: `/circuits/${id}/open`, method: 'POST', body: command })
}

export function recoverCircuit(id: string, command: ManualRecoverCommand): Promise<CircuitStateDetail> {
  return request<CircuitStateDetail>({ path: `/circuits/${id}/recover`, method: 'POST', body: command })
}

export function probeCircuit(id: string, command: ProbeNowCommand): Promise<ProviderCheckRecord> {
  return request<ProviderCheckRecord>({ path: `/circuits/${id}/probe`, method: 'POST', body: command })
}
