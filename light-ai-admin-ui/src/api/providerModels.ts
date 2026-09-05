// Provider Model 查询、能力表单、导入与批量检测（FE-015/016，附录 4.2.9.3）。
// 价格为 decimal(20,8) 十进制字符串，前端不做数值运算；能力字段未知为 null（待补充）。

import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'
import type { CheckMode, ProviderCheckCommand, ProviderCheckRecord } from './credentials'

export type { CheckMode }

export type ConnectionStatus = 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE'

export interface ProviderModelListItem {
  id: string
  provider_id: string
  provider_name: string
  display_name: string
  model_id: string
  context_window: number | null
  max_output_tokens: number | null
  support_stream: boolean | null
  input_price: string
  output_price: string
  price_unit: number
  currency: string
  connection_status: ConnectionStatus
  last_check_at: string | null
  route_candidate_count: number
  enabled: boolean
  draft_changed: boolean
  version: number
}

export interface ModelAliasSummary {
  id: string
  alias_id: string
  alias: string
  priority: number
  weight: number
  credential_pool_name: string
  candidate_status: string
}

export interface ProviderModelDetail extends ProviderModelListItem {
  model_type: 'CHAT_TEXT'
  tokenizer_family: string | null
  support_system_message: boolean | null
  support_temperature: boolean | null
  temperature_min: string | null
  temperature_max: string | null
  support_top_p: boolean | null
  top_p_min: string | null
  top_p_max: string | null
  support_stop: boolean | null
  max_stop_sequences: number | null
  max_stop_length: number | null
  default_temperature: string | null
  default_top_p: string | null
  default_max_tokens: number | null
  default_stop: string[]
  last_error_code: string | null
  created_at: string
  updated_at: string
  /** 关联候选摘要（附录 4.2.6.3）。 */
  related_aliases: ModelAliasSummary[]
  /** 最近检测记录（按时间倒序，最多 20 条）。 */
  recent_checks: ProviderCheckRecord[]
}

export interface ProviderModelListQuery {
  keyword?: string | undefined
  provider_id?: string[] | string | undefined
  connection_status?: string[] | string | undefined
  support_stream?: boolean | undefined
  enabled?: boolean | undefined
  page?: number | undefined
  page_size?: number | undefined
  sort?: string | undefined
}

export interface ProviderModelCommand {
  provider_id: string
  display_name: string
  model_id: string
  tokenizer_family: string | null
  context_window: number | null
  max_output_tokens: number | null
  support_stream: boolean
  support_system_message: boolean
  support_temperature: boolean
  temperature_min?: string | null
  temperature_max?: string | null
  support_top_p: boolean
  top_p_min?: string | null
  top_p_max?: string | null
  support_stop: boolean
  max_stop_sequences?: number | null
  max_stop_length?: number | null
  default_temperature?: string | null
  default_top_p?: string | null
  default_max_tokens?: number | null
  default_stop: string[]
  input_price: string
  output_price: string
  price_unit: number
  currency: string
  enabled: boolean
  version?: number | undefined
}

export interface ProviderModelImportCandidate {
  model_id: string
  display_name: string | null
  existing: boolean
  source: 'PROVIDER_API' | 'ADAPTER_PRESET'
  tokenizer_family: string | null
  context_window: number | null
  max_output_tokens: number | null
  support_stream: boolean | null
  support_system_message: boolean | null
  support_temperature: boolean | null
  support_top_p: boolean | null
  support_stop: boolean | null
}

export interface ProviderModelImportCommand {
  provider_id: string
  source: 'PROVIDER_API' | 'ADAPTER_PRESET'
  credential_id?: string | undefined
  model_ids: string[]
  apply_known_defaults: boolean
  enabled: boolean
}

export interface ImportResult {
  created: { model_id: string; id: string; version: number }[]
  skipped: { model_id: string; reason: string }[]
  failed: { model_id: string; error: string }[]
}

export type BatchCheckJobStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'PARTIAL_FAILED'
  | 'FAILED'
  | 'CANCELLED'

export interface BatchCheckJob {
  id: string
  status: BatchCheckJobStatus
  total_count: number
  completed_count: number
  success_count: number
  failure_count: number
  cancelled_count: number
  started_at: string | null
  ended_at: string | null
  command: { provider_model_ids: string[]; credential_id: string; mode: CheckMode; timeout_ms: number }
}

export interface BatchCheckItem {
  id: string
  provider_model_id: string
  provider_model_name: string
  sequence: number
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  check_record_id: string | null
  error_code: string | null
}

export interface BatchCheckJobDetail {
  job: BatchCheckJob
  items: BatchCheckItem[]
}

export function fetchProviderModels(
  query: ProviderModelListQuery,
  signal?: AbortSignal,
): Promise<PageResult<ProviderModelListItem>> {
  return request<PageResult<ProviderModelListItem>>({
    path: '/provider-models',
    query: query as Record<string, QueryValue>,
    signal,
  })
}

export function fetchProviderModel(id: string, signal?: AbortSignal): Promise<ProviderModelDetail> {
  return request<ProviderModelDetail>({ path: `/provider-models/${id}`, signal })
}

export function createProviderModel(command: ProviderModelCommand): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: '/provider-models', method: 'POST', body: command })
}

export function updateProviderModel(
  id: string,
  command: ProviderModelCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/provider-models/${id}`, method: 'PUT', body: command })
}

export function fetchAvailableModels(
  providerId: string,
  query: { source: string; credential_id?: string; keyword?: string | undefined },
  signal?: AbortSignal,
): Promise<ProviderModelImportCandidate[]> {
  return request<ProviderModelImportCandidate[]>({
    path: `/providers/${providerId}/available-models`,
    query,
    signal,
  })
}

export function importProviderModels(command: ProviderModelImportCommand): Promise<ImportResult> {
  return request<ImportResult>({ path: '/provider-models/import', method: 'POST', body: command })
}

export function checkProviderModel(
  id: string,
  command: ProviderCheckCommand,
): Promise<ProviderCheckRecord> {
  return request<ProviderCheckRecord>({
    path: `/provider-models/${id}/check`,
    method: 'POST',
    body: command,
  })
}

export function startBatchCheck(command: {
  provider_model_ids: string[]
  credential_id: string
  mode: CheckMode
  timeout_ms: number
}): Promise<BatchCheckJob> {
  return request<BatchCheckJob>({ path: '/provider-models/batch-check', method: 'POST', body: command })
}

export function fetchBatchCheckJob(id: string, signal?: AbortSignal): Promise<BatchCheckJobDetail> {
  return request<BatchCheckJobDetail>({ path: `/batch-check-jobs/${id}`, signal })
}

export function cancelBatchCheckJob(id: string): Promise<BatchCheckJob> {
  return request<BatchCheckJob>({ path: `/batch-check-jobs/${id}/cancel`, method: 'POST', body: { id } })
}

export function enableProviderModel(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/provider-models/${id}/enable`, method: 'POST', body: { version } })
}

export function disableProviderModel(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/provider-models/${id}/disable`, method: 'POST', body: { version } })
}

export function deleteProviderModel(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/provider-models/${id}`, method: 'DELETE', body: { version } })
}

// GET .../impact?operation=DISABLE|DELETE 返回 ImpactAnalysis（C-011 补充接口）。
export function fetchEntityImpact<T>(path: string, operation: string, signal?: AbortSignal): Promise<T> {
  return request<T>({ path, query: { operation }, signal })
}

export interface ProviderOption {
  id: string
  name: string
  type: string
  enabled: boolean
}

/** Provider 选项（表单/向导使用）；上限 100 个，超出时按 keyword 收敛。 */
export function fetchProviderOptions(keyword?: string | undefined, signal?: AbortSignal): Promise<ProviderOption[]> {
  return request<PageResult<ProviderOption>>({
    path: '/providers',
    query: { keyword: keyword || undefined, page: 1, page_size: 100, sort: 'name' },
    signal,
  }).then((result) => result.items)
}

export interface ProviderCredentialOption {
  id: string
  pool_id: string
  pool_name: string
  name: string
  enabled: boolean
}

/** 同 Provider 的非停用 Credential 选项：先查池，再逐池查询凭证（池数量有限）。 */
export async function fetchProviderCredentials(
  providerId: string,
  signal?: AbortSignal,
): Promise<ProviderCredentialOption[]> {
  const pools = await request<PageResult<{ id: string; name: string }>>({
    path: '/credential-pools',
    query: { provider_id: providerId, enabled: true, page: 1, page_size: 100, sort: 'name' },
    signal,
  })
  const options: ProviderCredentialOption[] = []
  for (const pool of pools.items) {
    const credentials = await request<PageResult<{ id: string; name: string; enabled: boolean }>>({
      path: `/credential-pools/${pool.id}/credentials`,
      query: { enabled: true, page: 1, page_size: 100, sort: 'name' },
      signal,
    })
    for (const item of credentials.items) {
      options.push({ id: item.id, pool_id: pool.id, pool_name: pool.name, name: item.name, enabled: item.enabled })
    }
  }
  return options
}
