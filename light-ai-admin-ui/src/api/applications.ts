import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'

export type ApplicationStatus = 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
export type ApplicationEnvironment = 'DEV' | 'TEST' | 'STAGING' | 'PROD'

export interface ApplicationQuotaPolicy {
  id: string
  token_limit: number | null
  tokens_used: number
  tokens_reserved: number
  amount_limit: string | null
  amount_used: string
  amount_reserved: string
  currency: string
  rpm: number | null
  tpm: number | null
  period_type: 'LIFECYCLE' | 'DAY' | 'MONTH' | 'CUSTOM'
  period_start: string | null
  period_end: string | null
  version: number
}

export interface ApplicationModelPermission {
  id: string
  virtual_model_id: string
  virtual_model_code: string | null
  enabled: boolean
  version: number
}

export interface ApplicationListItem {
  id: string
  code: string
  name: string
  department: string | null
  owner_id: string
  owner_name: string
  environment: ApplicationEnvironment
  status: ApplicationStatus
  model_count: number
  active_key_count: number
  token_limit: number | null
  tokens_used: number
  tokens_reserved: number
  amount_limit: string | null
  amount_used: string
  amount_reserved: string
  currency: string
  rpm: number | null
  tpm: number | null
  last_called_at: string | null
  updated_at: string
  version: number
}

export interface ApplicationDetail {
  id: string
  code: string
  name: string
  department: string | null
  owner_id: string
  owner_name: string
  environment: ApplicationEnvironment
  description: string | null
  status: ApplicationStatus
  active_key_count: number
  quota: ApplicationQuotaPolicy
  models: ApplicationModelPermission[]
  last_called_at: string | null
  created_at: string
  updated_at: string
  version: number
}

export interface ApplicationKeyView {
  id: string
  application_id: string
  name: string
  masked_value: string
  ip_allowlist: string[]
  expires_at: string | null
  rpm: number | null
  tpm: number | null
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED'
  last_used_at: string | null
  last_used_ip_masked: string | null
  issued_at: string
  rotated_at: string | null
  revoked_at: string | null
  rotation_generation: number
  version: number
}

export interface ApplicationKeySecretResult {
  key_id: string
  application_id: string
  key_value: string
  masked_value: string
  issued_at: string
  rotation_generation: number
  version: number
}

export interface ApplicationCreatePayload {
  code: string
  name: string
  department: string | null
  owner_id: string
  owner_name: string
  environment: ApplicationEnvironment
  description: string | null
  status: 'ACTIVE' | 'DISABLED'
  token_limit: number | null
  amount_limit: string | null
  currency: string
  rpm: number | null
  tpm: number | null
  period_type: 'LIFECYCLE' | 'DAY' | 'MONTH' | 'CUSTOM'
  period_start: string | null
  period_end: string | null
  virtual_model_ids: string[]
}

export interface ApplicationUpdatePayload {
  name: string
  department: string | null
  owner_id: string
  owner_name: string
  environment: ApplicationEnvironment
  description: string | null
  version: number
}

export interface ApplicationQuotaUpdatePayload {
  token_limit: number | null
  amount_limit: string | null
  currency: string
  rpm: number | null
  tpm: number | null
  period_type: ApplicationQuotaPolicy['period_type']
  period_start: string | null
  period_end: string | null
  version: number
  reason: string
}

export function fetchApplications(
  query: Record<string, QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<ApplicationListItem>> {
  return request({ path: '/applications', query, signal })
}

export function fetchApplication(id: string, signal?: AbortSignal): Promise<ApplicationDetail> {
  return request({ path: `/applications/${id}`, signal })
}

export function createApplication(
  payload: ApplicationCreatePayload,
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({ path: '/applications', method: 'POST', body: payload })
}

export function updateApplication(
  id: string,
  payload: ApplicationUpdatePayload,
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({ path: `/applications/${id}`, method: 'PUT', body: payload })
}

export function changeApplicationStatus(
  id: string,
  payload: { status: ApplicationStatus; version: number; reason: string },
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({ path: `/applications/${id}/status`, method: 'POST', body: payload })
}

export function updateApplicationQuota(
  id: string,
  payload: ApplicationQuotaUpdatePayload,
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({ path: `/applications/${id}/quota`, method: 'PUT', body: payload })
}

export function updateApplicationModels(
  id: string,
  payload: { virtual_model_ids: string[]; application_version: number; reason: string },
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({ path: `/applications/${id}/models`, method: 'PUT', body: payload })
}

export function fetchApplicationKeys(
  applicationId: string,
  signal?: AbortSignal,
): Promise<ApplicationKeyView[]> {
  return request({ path: `/applications/${applicationId}/keys`, signal })
}

export function createApplicationKey(
  applicationId: string,
  payload: {
    name: string
    ip_allowlist: string[]
    expires_at: string | null
    rpm: number | null
    tpm: number | null
  },
): Promise<ApplicationKeySecretResult> {
  return request({ path: `/applications/${applicationId}/keys`, method: 'POST', body: payload })
}

export function rotateApplicationKey(
  applicationId: string,
  keyId: string,
  payload: { version: number; reason: string },
): Promise<ApplicationKeySecretResult> {
  return request({ path: `/applications/${applicationId}/keys/${keyId}/rotate`, method: 'POST', body: payload })
}

export function revokeApplicationKey(
  applicationId: string,
  keyId: string,
  payload: { version: number; reason: string },
): Promise<ManagementOperationResult<ApplicationKeyView>> {
  return request({ path: `/applications/${applicationId}/keys/${keyId}/revoke`, method: 'POST', body: payload })
}
