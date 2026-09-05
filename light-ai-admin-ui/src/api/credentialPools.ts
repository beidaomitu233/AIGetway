import { request } from './http'
import type { ImpactAnalysis, ManagementOperationResult, PageResult } from './contracts'
import type { ProviderCheckCommand, ProviderCheckRecord } from './providers'

export type PoolStatus = 'AVAILABLE' | 'PARTIAL_AVAILABLE' | 'UNAVAILABLE' | 'DISABLED'
export type SelectionStrategy = 'LEAST_CONCURRENT' | 'ROUND_ROBIN' | 'WEIGHTED_RANDOM'
export type CredentialHealth = 'HEALTHY' | 'UNKNOWN' | 'RATE_LIMITED' | 'INVALID' | 'UNAVAILABLE' | 'DISABLED'
export type SecretSource = 'INLINE_ENCRYPTED' | 'EXTERNAL_REF'

export interface CredentialPoolListItem {
  id: string
  provider_id: string
  provider_name: string
  name: string
  selection_strategy: SelectionStrategy | string
  credential_total: number
  credential_available: number
  current_concurrency: number
  rpm_used: number
  tpm_used: number
  status: PoolStatus | string
  enabled: boolean
  draft_changed: boolean
  version: number
  updated_at: string
}

export interface CredentialPoolDetail extends CredentialPoolListItem {
  route_candidate_count: number
  model_alias_count: number
  created_by: string
  created_at: string
  updated_by: string
  updated_at: string
}

export interface PoolSavePayload {
  provider_id: string
  name: string
  selection_strategy: SelectionStrategy
  enabled: boolean
  version?: number
}

export interface CredentialListItem {
  id: string
  pool_id: string
  name: string
  masked_value: string
  secret_ref_display: string | null
  secret_source: SecretSource | string
  weight: number
  rpm_limit: number | null
  tpm_limit: number | null
  concurrent_limit: number | null
  current_concurrency: number
  health_status: CredentialHealth | string
  rate_limit_reset_at: string | null
  last_success_at: string | null
  last_check_at: string | null
  enabled: boolean
  draft_changed: boolean
  version: number
}

export function listPools(
  query: Record<string, import('./http').QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<CredentialPoolListItem>> {
  return request({ path: '/credential-pools', query, signal })
}

export function getPool(id: string, signal?: AbortSignal): Promise<CredentialPoolDetail> {
  return request({ path: `/credential-pools/${id}`, signal })
}

export function createPool(payload: PoolSavePayload): Promise<ManagementOperationResult> {
  return request({ path: '/credential-pools', method: 'POST', body: payload })
}

export function updatePool(
  id: string,
  payload: PoolSavePayload,
): Promise<ManagementOperationResult> {
  return request({ path: `/credential-pools/${id}`, method: 'PUT', body: payload })
}

export function getPoolImpact(id: string, operation: 'DISABLE' | 'DELETE'): Promise<ImpactAnalysis> {
  return request({ path: `/credential-pools/${id}/impact`, query: { operation } })
}

export function enablePool(id: string, version: number): Promise<ManagementOperationResult> {
  return request({ path: `/credential-pools/${id}/enable`, method: 'POST', body: { version } })
}

export function disablePool(
  id: string,
  version: number,
  confirmedImpactVersion: string,
): Promise<ManagementOperationResult> {
  return request({
    path: `/credential-pools/${id}/disable`,
    method: 'POST',
    body: { version, confirmed_impact_version: confirmedImpactVersion },
  })
}

export function deletePool(
  id: string,
  version: number,
  confirmedImpactVersion: string,
): Promise<ManagementOperationResult> {
  return request({
    path: `/credential-pools/${id}`,
    method: 'DELETE',
    body: { version, confirmed_impact_version: confirmedImpactVersion },
  })
}

export function listCredentials(
  poolId: string,
  query: Record<string, string | number | boolean | undefined>,
  signal: AbortSignal,
): Promise<PageResult<CredentialListItem>> {
  return request({ path: `/credential-pools/${poolId}/credentials`, query, signal })
}

export function checkCredential(
  credentialId: string,
  command: ProviderCheckCommand,
): Promise<ProviderCheckRecord> {
  return request({ path: `/credentials/${credentialId}/check`, method: 'POST', body: command })
}
