import { request } from './http'
import type { ImpactAnalysis, ManagementOperationResult, PageResult } from './contracts'

export type ConnectionStatus = 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE'
export type ChannelStatus = 'ACTIVE' | 'DISABLED'

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

/** BE-P21-001：渠道配置状态与运行健康分列，字段使用 V2 DTO 命名。 */
export interface ProviderListItem {
  id: string
  name: string
  provider_type: string
  base_url: string
  proxy: string | null
  status: ChannelStatus | string
  health: string
  priority: number
  weight: number
  upstream_model_count: number
  credential_count: number
  draft_changed: boolean
  last_checked_at: string | null
  last_check_latency_ms: number | null
  last_error_code: string | null
  version: number
  updated_at: string
}

export interface ChannelTimeouts {
  connect_ms: number
  read_ms: number
  stream_idle_ms: number
}

export interface ProviderDetail extends Omit<ProviderListItem, 'upstream_model_count' | 'credential_count'> {
  timeouts: ChannelTimeouts
  headers: Record<string, string>
  created_by: string
  created_at: string
  updated_by: string
  updated_at: string
  recent_check_records: ProviderCheckRecord[]
}

export interface ProviderSavePayload {
  name: string
  provider_type: string
  base_url: string
  proxy: string | null
  timeouts: ChannelTimeouts
  headers: Record<string, string>
  priority?: number
  weight?: number
  version?: number
}

export type CheckMode = 'MINIMAL_CHAT' | 'CONNECTION_ONLY'

export interface ProviderCheckCommand {
  upstream_model_id?: string
  channel_credential_id?: string
  mode: CheckMode
  timeout_ms: number
}

export interface ProviderCheckRecord {
  id: string
  target_type: 'CHANNEL' | 'PROVIDER' | 'PROVIDER_MODEL' | 'CREDENTIAL' | 'ROUTE_CANDIDATE' | string
  target_id: string
  mode: CheckMode | string
  status: 'SUCCEEDED' | 'FAILED' | string
  started_at: string
  ended_at: string | null
  total_ms: number | null
  trace_id: string | null
  attempt_id: string | null
  usage: { input_tokens: number; output_tokens: number; total_tokens: number; source: string } | null
  error_code: string | null
  error_summary: string | null
  channel_request_id?: string | null
}

export function listProviders(
  query: Record<string, import('./http').QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<ProviderListItem>> {
  return request({ path: '/channels', query, signal })
}

export function getProvider(id: string, signal?: AbortSignal): Promise<ProviderDetail> {
  return request({ path: `/channels/${id}`, signal })
}

export function createProvider(payload: ProviderSavePayload): Promise<ManagementOperationResult<ProviderDetail>> {
  return request({ path: '/channels', method: 'POST', body: payload })
}

export function updateProvider(
  id: string,
  payload: ProviderSavePayload,
): Promise<ManagementOperationResult<ProviderDetail>> {
  return request({ path: `/channels/${id}`, method: 'PUT', body: payload })
}

export function getProviderImpact(id: string, operation: 'DISABLE' | 'DELETE'): Promise<ImpactAnalysis> {
  return request({ path: `/channels/${id}/impact`, query: { operation } })
}

export function checkProvider(
  id: string,
  command: ProviderCheckCommand,
): Promise<ProviderCheckRecord> {
  return request({ path: `/channels/${id}/check`, method: 'POST', body: command })
}

export function enableProvider(id: string, version: number): Promise<ManagementOperationResult<ProviderDetail>> {
  return request({ path: `/channels/${id}/enable`, method: 'POST', body: { version } })
}

export function disableProvider(
  id: string,
  version: number,
  confirmedImpactVersion: string,
): Promise<ManagementOperationResult<ProviderDetail>> {
  return request({
    path: `/channels/${id}/disable`,
    method: 'POST',
    body: { version, confirmed_impact_version: confirmedImpactVersion },
  })
}

export function deleteProvider(
  id: string,
  version: number,
  confirmedImpactVersion: string,
): Promise<ManagementOperationResult<ProviderDetail>> {
  return request({
    path: `/channels/${id}`,
    method: 'DELETE',
    body: { version, confirmed_impact_version: confirmedImpactVersion },
  })
}
