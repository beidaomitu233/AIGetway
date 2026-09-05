// 审计日志（FE-048，附录 4.5.6.4）。只读；敏感字段在服务端脱敏。
import { request, type QueryValue } from './http'
import type { PageResult } from './contracts'
import { getRuntimeConfig } from '@/app/runtimeConfig'

export interface AuditLogListItem {
  id: string
  created_at: string
  request_id: string
  operator_id: string
  operator_name: string
  operator_role: string
  operation: string
  operation_reason: string | null
  entity_type: string
  entity_id: string
  entity_name: string
  change_summary: string
  source_mode: string
  result: 'SUCCEEDED' | 'FAILED' | string
  error_code: string | null
  duration_ms: number
}

export interface AuditFieldChange {
  field_name: string
  before_value: string | null
  after_value: string | null
  sensitive: boolean
}

export interface AuditLogDetail extends AuditLogListItem {
  client_ip: string | null
  user_agent: string | null
  before_version: number | null
  after_version: number | null
  changed_fields: AuditFieldChange[]
  error_summary: string | null
}

export function fetchAuditLogs(
  query: Record<string, QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<AuditLogListItem>> {
  return request({ path: '/audit-logs', query, signal })
}

export function fetchAuditLog(id: string, signal?: AbortSignal): Promise<AuditLogDetail> {
  return request({ path: `/audit-logs/${id}`, signal })
}

export interface AuditExportError extends Error {
  code: string
}

/** 导出与列表同筛选；错误响应解析 error 信封（EXPORT_TOO_LARGE 等）。 */
export async function exportAuditLogs(
  query: Record<string, QueryValue>,
  signal: AbortSignal,
): Promise<void> {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      for (const item of value) params.append(key, String(item))
    } else {
      params.append(key, String(value))
    }
  }
  const url = `${getRuntimeConfig().adminApiBase}/audit-logs/export?${params.toString()}`
  const response = await fetch(url, { signal, credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = '导出失败'
    try {
      const payload = (await response.json()) as { error?: { code?: string; message?: string } }
      if (payload.error?.code) code = payload.error.code
      if (payload.error?.message) message = payload.error.message
    } catch {
      // 非 JSON 错误响应保持默认
    }
    const error = new Error(message) as AuditExportError
    error.code = code
    throw error
  }
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)/i)
  const filename = match ? decodeURIComponent(match[1]) : 'audit-logs.csv'
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}
