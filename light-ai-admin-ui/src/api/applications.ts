import { request, type QueryValue } from './http'
import type { ManagementOperationResult, PageResult } from './contracts'

export type ApplicationStatus = 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
export type ApplicationEnvironment = 'DEV' | 'TEST' | 'STAGING' | 'PROD'

export interface ApplicationQuotaPolicy {
  id: string
  /** 64 位计数以十进制字符串传输（BE-P20-102），避免超出 JS 安全整数范围。 */
  token_limit: string | null
  tokens_used: string
  tokens_reserved: string
  amount_limit: string | null
  amount_used: string
  amount_reserved: string
  currency: string
  rpm: number | null
  tpm: number | null
  period_type: 'LIFECYCLE' | 'DAY' | 'MONTH' | 'CUSTOM'
  period_start: string | null
  period_end: string | null
  period_id: string | null
  policy_version: string | null
  timezone: string | null
  reset_at: string | null
  tokens_remaining: string
  amount_remaining: string
  admission_blocked: boolean
  version: string
}

export interface ApplicationModelPermission {
  id: string
  virtual_model_id: string
  virtual_model_code: string | null
  enabled: boolean
  max_output_tokens: number | null
  /** BE-P20-103：约束字段统一为 allow_stream。 */
  allow_stream: boolean | null
  version: number
}

/** 应用对某个虚拟模型的请求参数上限；null 表示不施加该维度限制。 */
export interface ApplicationModelConstraintPayload {
  virtual_model_id: string
  max_output_tokens: number | null
  allow_stream: boolean | null
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
  token_limit: string | null
  tokens_used: string
  tokens_reserved: string
  amount_limit: string | null
  amount_used: string
  amount_reserved: string
  currency: string
  rpm: number | null
  tpm: number | null
  last_called_at: string | null
  updated_at: string
  version: string
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
  status: 'ACTIVE' | 'DISABLED' | 'EXPIRED' | 'REVOKED'
  last_used_at: string | null
  last_used_ip_masked: string | null
  issued_at: string
  rotated_at: string | null
  revoked_at: string | null
  rotation_generation: number
  version: number
  virtual_model_ids: string[]
}

/** 创建/轮换成功时的一次性密钥结果；secret 原文只在此响应出现（BE-P20-101）。 */
export interface ApplicationKeySecretResult {
  key_id: string
  application_id: string
  secret: string
  key_prefix: string
  masked_value: string
  status: ApplicationKeyView['status']
  issued_at: string
  expires_at: string | null
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

export interface ApplicationQuotaAdjustment {
  id: string
  application_id: string
  dimension: 'TOKEN_LIMIT' | 'AMOUNT_LIMIT' | 'TOKEN_USAGE_RESET' | 'AMOUNT_USAGE_RESET'
  before_value: string
  delta_value: string
  after_value: string
  reason: string
  effective_at: string
  operator_id: string
  created_at: string
}

export interface ApplicationMemberView {
  id: string
  subject_id: string
  subject_name: string
  role: string
}

export function fetchApplicationMembers(
  applicationId: string,
  signal?: AbortSignal,
): Promise<ApplicationMemberView[]> {
  return request({ path: `/applications/${applicationId}/members`, signal })
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
  payload: {
    virtual_model_ids: string[]
    constraints: ApplicationModelConstraintPayload[]
    application_version: number
    reason: string
  },
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({ path: `/applications/${id}/models`, method: 'PUT', body: payload })
}

export function fetchApplicationQuotaAdjustments(
  id: string,
  signal?: AbortSignal,
): Promise<ApplicationQuotaAdjustment[]> {
  return request({ path: `/applications/${id}/quota/adjustments`, signal })
}

export function adjustApplicationQuota(
  id: string,
  payload: {
    dimension: 'TOKEN_LIMIT' | 'AMOUNT_LIMIT'
    delta: string
    reason: string
    idempotency_key: string
    quota_version: number
  },
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({
    path: `/applications/${id}/quota/adjustments`, method: 'POST', body: payload,
  })
}

export function resetApplicationQuotaUsage(
  id: string,
  payload: {
    dimension: 'TOKEN_USAGE' | 'AMOUNT_USAGE'
    reason: string
    confirmation_code: string
    idempotency_key: string
    quota_version: number
  },
): Promise<ManagementOperationResult<ApplicationDetail>> {
  return request({
    path: `/applications/${id}/quota/reset`, method: 'POST', body: payload,
  })
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
    virtual_model_ids: string[]
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

export function changeApplicationKeyStatus(
  applicationId: string,
  keyId: string,
  payload: { status: 'ACTIVE' | 'DISABLED'; version: number; reason: string },
): Promise<ManagementOperationResult<ApplicationKeyView>> {
  return request({
    path: `/applications/${applicationId}/keys/${keyId}/status`, method: 'POST', body: payload,
  })
}

export function revokeApplicationKey(
  applicationId: string,
  keyId: string,
  payload: { version: number; reason: string },
): Promise<ManagementOperationResult<ApplicationKeyView>> {
  return request({ path: `/applications/${applicationId}/keys/${keyId}/revoke`, method: 'POST', body: payload })
}
