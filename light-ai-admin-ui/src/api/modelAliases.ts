// Model Alias 与 Route Candidate（FE-017/018，附录 4.2.9.4）。

import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'
import type { ProviderCheckCommand, ProviderCheckRecord } from './credentials'
import { fetchEntityImpact, fetchProviderModel } from './providerModels'
import { getProvider } from './providers'
import { fetchCredentials } from './credentials'

export type RuntimeAvailability = 'AVAILABLE' | 'CAPACITY_EXHAUSTED' | 'CIRCUIT_OPEN' | 'DISABLED' | 'UNAVAILABLE'

export interface ModelAliasListItem {
  id: string
  alias: string
  display_name: string
  route_strategy: string
  candidate_count: number
  available_candidate_count: number
  stream_candidate_count: number
  request_count_24h: number
  enabled: boolean
  draft_changed: boolean
  updated_at: string
  version: number
}

export interface ModelAliasDetail extends ModelAliasListItem {
  description: string | null
  current_snapshot_no: number | null
  updated_by: string
  /** 运行摘要（附录 4.2.8.1，30 秒刷新）。 */
  success_rate_24h: number | null
  p95_total_ms_24h: number | null
}

export interface ModelAliasListQuery {
  keyword?: string | undefined
  enabled?: boolean | undefined
  runtime_availability?: string | undefined
  support_stream?: boolean | undefined
  page?: number | undefined
  page_size?: number | undefined
  sort?: string | undefined
}

export interface ModelAliasCreateCommand {
  alias: string
  display_name: string
  description?: string | null
  enabled: boolean
}

export interface ModelAliasUpdateCommand {
  display_name: string
  description?: string | null
  enabled: boolean
  version: number
}

export interface RouteCandidateDetail {
  id: string
  alias_id: string
  channel_id: string
  channel_name: string
  upstream_model_id: string
  upstream_model_name: string
  upstream_model_id_label: string
  priority: number
  weight: number
  enabled: boolean
  support_stream: boolean
  support_system_message: boolean
  context_window: number | null
  current_concurrency: number
  runtime_status: RuntimeAvailability
  excluded_reason: string | null
  draft_changed: boolean
  version: number
}

export interface RouteCandidateCommand {
  upstream_model_id: string
  channel_id: string
  priority: number
  weight: number
  enabled: boolean
}

export interface ReorderItem {
  id: string
  priority: number
  version: number
}

export interface CredentialPoolOption {
  id: string
  name: string
  channel_id: string
  credential_available: number
  status: string
}

export function fetchModelAliases(
  query: ModelAliasListQuery,
  signal?: AbortSignal,
): Promise<PageResult<ModelAliasListItem>> {
  return request<PageResult<ModelAliasListItem>>({
    path: '/virtual-models',
    query: query as Record<string, QueryValue>,
    signal,
  })
}

export function fetchModelAlias(id: string, signal?: AbortSignal): Promise<ModelAliasDetail> {
  return request<ModelAliasDetail>({ path: `/virtual-models/${id}`, signal })
}

export function createModelAlias(command: ModelAliasCreateCommand): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: '/virtual-models', method: 'POST', body: command })
}

export function updateModelAlias(
  id: string,
  command: ModelAliasUpdateCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/virtual-models/${id}`, method: 'PUT', body: command })
}

export function fetchModelAliasImpact(path: string, operation: string, signal?: AbortSignal) {
  return fetchEntityImpact(path, operation, signal)
}

export function enableModelAlias(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/virtual-models/${id}/enable`, method: 'POST', body: { version } })
}

export function disableModelAlias(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/virtual-models/${id}/disable`, method: 'POST', body: { version } })
}

export function deleteModelAlias(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/virtual-models/${id}`, method: 'DELETE', body: { version } })
}

export function fetchCandidates(aliasId: string, signal?: AbortSignal): Promise<RouteCandidateDetail[]> {
  return request<RouteCandidateDetail[]>({ path: `/virtual-models/${aliasId}/routes`, signal })
}

export function createCandidate(
  aliasId: string,
  command: RouteCandidateCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({
    path: `/virtual-models/${aliasId}/routes`,
    method: 'POST',
    body: command,
  })
}

export function updateCandidate(
  aliasId: string,
  candidateId: string,
  command: RouteCandidateCommand & { version: number },
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({
    path: `/virtual-models/${aliasId}/routes/${candidateId}`,
    method: 'PUT',
    body: command,
  })
}

export function deleteCandidate(aliasId: string, candidateId: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({
    path: `/virtual-models/${aliasId}/routes/${candidateId}`,
    method: 'DELETE',
    body: { version },
  })
}

export function reorderCandidates(
  aliasId: string,
  items: ReorderItem[],
): Promise<RouteCandidateDetail[]> {
  return request<RouteCandidateDetail[]>({
    path: `/virtual-models/${aliasId}/routes/reorder`,
    method: 'PUT',
    body: { items },
  })
}

export function checkCandidate(
  aliasId: string,
  candidateId: string,
  command: ProviderCheckCommand,
): Promise<ProviderCheckRecord> {
  return request<ProviderCheckRecord>({
    path: `/virtual-models/${aliasId}/routes/${candidateId}/check`,
    method: 'POST',
    body: command,
  })
}

/** 当前模型只能使用所属渠道的 Key；这里只查询配置，不推算运行容量。 */
export async function fetchModelCredentialPools(modelId: string, signal?: AbortSignal): Promise<CredentialPoolOption[]> {
  const model = await fetchProviderModel(modelId, signal)
  const [channel, keys] = await Promise.all([getProvider(model.channel_id, signal), fetchCredentials(model.channel_id, { enabled: true, page_size: 100 }, signal)])
  return [{ id: channel.id, name: channel.name, channel_id: channel.id, credential_available: keys.total, status: channel.status === 'ACTIVE' ? 'ACTIVE' : 'DISABLED' }]
}
