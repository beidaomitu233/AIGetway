// Credential 查询与操作（FE-013/014，附录 4.2.9.2）。
// secret_value/token_hash 永不出现在任何响应类型中；rpm/tpm 空值表示不限制。

import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'

export type SecretSource = 'INLINE_ENCRYPTED' | 'EXTERNAL_REF'

export type CredentialHealthStatus =
  | 'HEALTHY'
  | 'UNKNOWN'
  | 'RATE_LIMITED'
  | 'INVALID'
  | 'UNAVAILABLE'
  | 'DISABLED'

export interface CredentialListItem {
  id: string
  pool_id: string
  name: string
  masked_value: string
  secret_source: SecretSource
  weight: number
  rpm_limit: number | null
  tpm_limit: number | null
  concurrent_limit: number | null
  current_concurrency: number
  health_status: CredentialHealthStatus
  rate_limit_reset_at: string | null
  last_success_at: string | null
  last_check_at: string | null
  enabled: boolean
  draft_changed: boolean
  version: number
}

export interface CredentialDetail extends CredentialListItem {
  created_at: string
  updated_at: string
}

export interface CredentialListQuery {
  health_status?: string | undefined
  enabled?: boolean | undefined
  page?: number | undefined
  page_size?: number | undefined
  sort?: string | undefined
}

export interface CredentialCreateCommand {
  name: string
  secret_source: SecretSource
  secret_value?: string | undefined
  secret_ref?: string | undefined
  weight: number
  rpm_limit?: number | null
  tpm_limit?: number | null
  concurrent_limit?: number | null
  enabled: boolean
}

export interface CredentialUpdateCommand {
  name: string
  secret_ref?: string | null | undefined
  weight: number
  rpm_limit?: number | null
  tpm_limit?: number | null
  concurrent_limit?: number | null
  enabled: boolean
  version: number
}

export interface CredentialRotateCommand {
  secret_value: string
  secret_value_confirm: string
  version: number
}

export type CheckMode = 'MINIMAL_CHAT' | 'CONNECTION_ONLY'

export interface ProviderCheckCommand {
  provider_model_id?: string | undefined
  mode: CheckMode
  timeout_ms?: number | undefined
  credential_id?: string | undefined
}

export interface ProviderCheckRecord {
  id: string
  target_type: string
  target_id: string
  mode: CheckMode
  status: 'SUCCEEDED' | 'FAILED'
  operator_id: string
  trace_id?: string | null
  attempt_id?: string | null
  started_at: string
  ended_at: string
  total_ms: number
  usage?: { prompt_tokens: number; completion_tokens: number; total_tokens: number } | null
  provider_request_id?: string | null
  error_code?: string | null
  error_summary?: string | null
  created_at: string
}

export function fetchCredentials(
  poolId: string,
  query: CredentialListQuery,
  signal?: AbortSignal,
): Promise<PageResult<CredentialListItem>> {
  return request<PageResult<CredentialListItem>>({
    path: `/credential-pools/${poolId}/credentials`,
    query: query as Record<string, QueryValue>,
    signal,
  })
}

export function createCredential(
  poolId: string,
  command: CredentialCreateCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({
    path: `/credential-pools/${poolId}/credentials`,
    method: 'POST',
    body: command,
  })
}

export function updateCredential(
  id: string,
  command: CredentialUpdateCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({
    path: `/credentials/${id}`,
    method: 'PUT',
    body: command,
  })
}

export function rotateCredential(
  id: string,
  command: CredentialRotateCommand,
): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({
    path: `/credentials/${id}/rotate`,
    method: 'POST',
    body: command,
  })
}

export function checkCredential(
  id: string,
  command: ProviderCheckCommand,
): Promise<ProviderCheckRecord> {
  return request<ProviderCheckRecord>({ path: `/credentials/${id}/check`, method: 'POST', body: command })
}

export function enableCredential(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/credentials/${id}/enable`, method: 'POST', body: { version } })
}

export function disableCredential(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/credentials/${id}/disable`, method: 'POST', body: { version } })
}

export function deleteCredential(id: string, version: number): Promise<ManagementOperationResult> {
  return request<ManagementOperationResult>({ path: `/credentials/${id}`, method: 'DELETE', body: { version } })
}
