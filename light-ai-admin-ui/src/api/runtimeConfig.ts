// 运行参数与保留影响（FE-043/044，附录 4.5.6.2）。
import { request } from './http'
import type { ManagementOperationResult } from './contracts'

export interface RuntimeConfigDetail {
  version: number
  timezone: string
  timezone_locked: boolean
  trace_retention_days: number
  usage_retention_days: number
  audit_retention_days: number
  diagnostic_sample_retention_days: number
  dashboard_refresh_seconds: number
  max_message_chars: number
  max_request_chars: number
  diagnostic_sampling_enabled: boolean
  diagnostic_sample_rate: string
  diagnostic_sample_max_chars: number
  client_ip_recording_enabled: boolean
  trusted_proxy_cidrs: string[]
  publish_instance_timeout_seconds: number
  instance_stale_seconds: number
  current_snapshot_no: number
  published_at: string | null
  draft_changed: boolean
  draft_revision: number
  last_modified_by_name: string
  updated_at: string
}

export interface RuntimeConfigUpdatePayload {
  version: number
  timezone: string
  trace_retention_days: number
  usage_retention_days: number
  audit_retention_days: number
  diagnostic_sample_retention_days: number
  dashboard_refresh_seconds: number
  max_message_chars: number
  max_request_chars: number
  diagnostic_sampling_enabled: boolean
  diagnostic_sample_rate: string
  diagnostic_sample_max_chars: number
  client_ip_recording_enabled: boolean
  trusted_proxy_cidrs: string[]
  publish_instance_timeout_seconds: number
  instance_stale_seconds: number
  /** 缩短保留期时必填（附录 4.5.3.3）。 */
  confirmed_impact_version?: string
}

export interface RetentionImpactResult {
  impact_version: string
  estimated_at: string
  expires_at: string
  target_values: {
    trace_retention_days: number
    usage_retention_days: number
    audit_retention_days: number
    diagnostic_sample_retention_days: number
  }
  counts: {
    trace: number
    usage: number
    audit: number
    sample: number
  }
  earliest_remaining_at: string
}

export function fetchRuntimeConfig(signal?: AbortSignal): Promise<RuntimeConfigDetail> {
  return request({ path: '/runtime-config', signal })
}

export function fetchRetentionImpact(payload: {
  trace_retention_days: number
  usage_retention_days: number
  audit_retention_days: number
  diagnostic_sample_retention_days: number
}): Promise<RetentionImpactResult> {
  return request({ path: '/runtime-config/retention-impact', method: 'POST', body: payload })
}

export function updateRuntimeConfig(
  payload: RuntimeConfigUpdatePayload,
): Promise<ManagementOperationResult> {
  return request({ path: '/runtime-config', method: 'PUT', body: payload })
}
