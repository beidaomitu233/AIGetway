import { request } from './http'
import type { ImpactAnalysis, ManagementOperationResult, PageResult } from './contracts'

export type ConnectionStatus = 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE'

export interface AdapterDeclaration {
  provider_type: string
  adapter_version: string
  default_base_url: string
  tokenizer_families: string[]
  capabilities: string[]
  provider_option_specs: Array<{
    key: string
    type: 'STRING' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN'
    required: boolean
    default?: string | number | boolean
    min?: number
    max?: number
    enum_values?: string[]
    description?: string
  }>
}

export interface ProviderListItem {
  id: string
  name: string
  type: string
  base_url: string
  proxy_url: string | null
  connection_status: ConnectionStatus | string
  last_check_at: string | null
  last_check_latency_ms: number | null
  last_error_code: string | null
  provider_model_count: number
  credential_pool_count: number
  enabled: boolean
  draft_changed: boolean
  version: number
  updated_at: string
}

export interface ProviderDetail
  extends Omit<ProviderListItem, 'provider_model_count' | 'credential_pool_count'> {
  connect_timeout_ms: number
  read_timeout_ms: number
  default_headers: Record<string, string>
  created_by: string
  created_at: string
  updated_by: string
  updated_at: string
  /** 详情页展示的最近检测记录（口径登记于 COMMUNICATION.md C-024）。 */
  recent_check_records: ProviderCheckRecord[]
}

export interface ProviderSavePayload {
  name: string
  type: string
  base_url: string
  proxy_url: string | null
  connect_timeout_ms: number
  read_timeout_ms: number
  default_headers: Record<string, string>
  enabled: boolean
  version?: number
}

export type CheckMode = 'MINIMAL_CHAT' | 'CONNECTION_ONLY'

export interface ProviderCheckCommand {
  provider_model_id?: string
  credential_id?: string
  mode: CheckMode
  timeout_ms: number
}

export interface ProviderCheckRecord {
  id: string
  target_type: 'PROVIDER' | 'PROVIDER_MODEL' | 'CREDENTIAL' | 'ROUTE_CANDIDATE' | string
  target_id: string
  status: 'SUCCEEDED' | 'FAILED' | string
  started_at: string
  ended_at: string | null
  total_ms: number | null
  trace_id: string | null
  usage: { total_tokens: number } | null
  error_code: string | null
  error_summary: string | null
  provider_request_id?: string | null
}

export function listProviders(
  query: Record<string, import('./http').QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<ProviderListItem>> {
  return request({ path: '/providers', query, signal })
}

export function getProvider(id: string, signal?: AbortSignal): Promise<ProviderDetail> {
  return request({ path: `/providers/${id}`, signal })
}

export function createProvider(payload: ProviderSavePayload): Promise<ManagementOperationResult> {
  return request({ path: '/providers', method: 'POST', body: payload })
}

export function updateProvider(
  id: string,
  payload: ProviderSavePayload,
): Promise<ManagementOperationResult> {
  return request({ path: `/providers/${id}`, method: 'PUT', body: payload })
}

export function getProviderImpact(id: string, operation: 'DISABLE' | 'DELETE'): Promise<ImpactAnalysis> {
  return request({ path: `/providers/${id}/impact`, query: { operation } })
}

export function checkProvider(
  id: string,
  command: ProviderCheckCommand,
): Promise<ProviderCheckRecord> {
  return request({ path: `/providers/${id}/check`, method: 'POST', body: command })
}

export function enableProvider(id: string, version: number): Promise<ManagementOperationResult> {
  return request({ path: `/providers/${id}/enable`, method: 'POST', body: { version } })
}

export function disableProvider(
  id: string,
  version: number,
  confirmedImpactVersion: string,
): Promise<ManagementOperationResult> {
  return request({
    path: `/providers/${id}/disable`,
    method: 'POST',
    body: { version, confirmed_impact_version: confirmedImpactVersion },
  })
}

export function deleteProvider(
  id: string,
  version: number,
  confirmedImpactVersion: string,
): Promise<ManagementOperationResult> {
  return request({
    path: `/providers/${id}`,
    method: 'DELETE',
    body: { version, confirmed_impact_version: confirmedImpactVersion },
  })
}
