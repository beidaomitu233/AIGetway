// Standalone Access Credential（FE-045~047，附录 4.5.6.3）。
// token_value 只在创建/轮换响应出现一次；页面弹窗展示后清除，不进入存储或日志。
import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'

export type AccessCredentialStatus = 'ACTIVE' | 'DISABLED' | 'EXPIRED' | 'DELETED' | string

export interface AccessCredentialListItem {
  id: string
  name: string
  application: string
  masked_token: string
  allowed_alias_count: number
  ip_rule_count: number
  status: AccessCredentialStatus
  expires_at: string | null
  rotation_generation: number
  last_used_at: string | null
  last_used_ip: string | null
  trace_count_24h: number
  updated_at: string
  version: number
}

export interface AccessCredentialDetail extends AccessCredentialListItem {
  allowed_alias_ids: string[]
  ip_allowlist: string[]
  created_at: string
  disabled_at: string | null
  recent_traces: Array<{ trace_id: string; started_at: string; status: string; alias: string }>
  audit_summary: Array<{ created_at: string; operation: string; result: string }>
}

export interface AccessCredentialSavePayload {
  name: string
  application: string
  allowed_alias_ids: string[]
  ip_allowlist: string[]
  expires_at: string | null
  enabled: boolean
  version?: number
}

export interface AccessCredentialSecretResult {
  credential: AccessCredentialListItem
  token_value: string
  issued_at: string
  rotation_generation: number
}

export function fetchAccessCredentials(
  query: Record<string, QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<AccessCredentialListItem>> {
  return request({ path: '/access-credentials', query, signal })
}

export function fetchAccessCredential(
  id: string,
  signal?: AbortSignal,
): Promise<AccessCredentialDetail> {
  return request({ path: `/access-credentials/${id}`, signal })
}

export function createAccessCredential(
  payload: AccessCredentialSavePayload,
): Promise<AccessCredentialSecretResult> {
  return request({ path: '/access-credentials', method: 'POST', body: payload })
}

export function updateAccessCredential(
  id: string,
  payload: AccessCredentialSavePayload,
): Promise<ManagementOperationResult> {
  return request({ path: `/access-credentials/${id}`, method: 'PUT', body: payload })
}

/** 轮换返回一次性新 Token；旧 Token 立即失效。 */
export function rotateAccessCredential(
  id: string,
  payload: { version: number; reason: string },
): Promise<AccessCredentialSecretResult> {
  return request({ path: `/access-credentials/${id}/rotate`, method: 'POST', body: payload })
}

export function enableAccessCredential(id: string, version: number): Promise<ManagementOperationResult> {
  return request({ path: `/access-credentials/${id}/enable`, method: 'POST', body: { version } })
}

export function disableAccessCredential(
  id: string,
  payload: { version: number; reason: string },
): Promise<ManagementOperationResult> {
  return request({ path: `/access-credentials/${id}/disable`, method: 'POST', body: payload })
}

export function deleteAccessCredential(
  id: string,
  payload: { version: number; reason: string },
): Promise<ManagementOperationResult> {
  return request({ path: `/access-credentials/${id}`, method: 'DELETE', body: payload })
}
