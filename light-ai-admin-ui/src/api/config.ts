// 草稿、校验与发布（FE-037~042，附录 4.5.6.1）。
// 敏感字段在服务端脱敏，前端只渲染 sensitive=true 的占位提示。
import { request, type QueryValue } from './http'
import type { PageResult } from './contracts'

export type DraftStatus = 'EDITABLE' | 'PUBLISHING' | string

export interface ConfigDraftState {
  base_snapshot_no: number
  draft_revision: number
  change_count: number
  status: DraftStatus
  first_modified_at: string | null
  last_modified_at: string | null
}

export interface DraftChangeSummaryCounts {
  total_count: number
  create_count: number
  update_count: number
  enable_count: number
  disable_count: number
  delete_count: number
}

export type DraftChangeSummary = DraftChangeSummaryCounts & {
  by_entity_type: Record<string, number>
}

export type ChangeType = 'CREATE' | 'UPDATE' | 'ENABLE' | 'DISABLE' | 'DELETE'

export interface FieldChange {
  field: string
  before_value: string | null
  after_value: string | null
  sensitive: boolean
}

export interface DraftChange {
  id: string
  entity_type: string
  entity_id: string
  entity_name: string
  change_type: ChangeType | string
  changed_fields: FieldChange[]
  dependency_summary: Array<{ entity_type: string; entity_id: string; entity_name: string }>
  revertable: boolean
  revert_blockers: string[]
  modified_by: string
  modified_by_name: string
  modified_at: string
  entity_version: number
}

export interface ConfigDraftChangesQuery extends Record<string, QueryValue> {
  keyword?: string
  entity_type?: string[]
  change_type?: string[]
  modified_by?: string[]
  page?: number
  page_size?: number
}

export type ValidationSeverity = 'ERROR' | 'WARNING'

export interface ConfigValidationIssue {
  code: string
  severity: ValidationSeverity | string
  entity_type: string
  entity_id: string | null
  entity_name: string
  field_path: string | null
  message: string
  suggestion: string | null
  related_entity_ids: string[]
}

export interface ConfigValidationResult {
  validation_id: string
  status: 'PASSED' | 'FAILED' | string
  base_snapshot_no: number
  target_snapshot_no: number
  draft_revision: number
  content_checksum: string
  validated_at: string
  expires_at: string
  change_summary: string
  affected_alias_ids: string[]
  issues: ConfigValidationIssue[]
}

export type PublishInstanceStatus =
  | 'PENDING'
  | 'PREPARING'
  | 'READY'
  | 'ACTIVATING'
  | 'LOADED'
  | 'FAILED'
  | 'TIMED_OUT'

export interface PublishInstanceResult {
  instance_id: string
  runtime_mode: string
  runtime_version: string
  supported_schema_versions: string[]
  loaded_adapter_types: string[]
  from_snapshot_no: number
  target_snapshot_no: number
  status: PublishInstanceStatus | string
  retry_count: number
  load_duration_ms: number | null
  error_code: string | null
  error_summary: string | null
  updated_at: string
}

export type PublishRecordStatus =
  | 'PREPARING'
  | 'ACTIVATING'
  | 'SUCCEEDED'
  | 'PARTIAL_FAILED'
  | 'FAILED'

export interface PublishRecordListItem {
  id: string
  snapshot_no: number
  from_snapshot_no: number
  status: PublishRecordStatus | string
  published_by_name: string
  publish_note: string
  published_at: string
  completed_at: string | null
  duration_ms: number | null
}

export interface PublishRecordDetail extends PublishRecordListItem {
  target_snapshot_no: number
  draft_revision: number
  content_checksum: string
  change_summary: string
  affected_alias_ids: string[]
  acknowledged_warning_ids: string[]
  instance_results: PublishInstanceResult[]
  first_round_completed_at: string | null
  converged_at: string | null
}

export interface RuntimeInstance {
  instance_id: string
  runtime_mode: string
  runtime_version: string
  application: string | null
  zone: string | null
  status: 'ONLINE' | 'DRAINING' | 'STALE' | 'OFFLINE' | string
  accepting_requests: boolean
  active_snapshot_no: number
  supported_schema_versions: string[]
  loaded_adapter_types: string[]
  last_heartbeat_at: string | null
}

export interface ConfigSnapshotSummary {
  snapshot_no: number
  status: string
  created_at: string
  activated_at: string | null
  content_checksum: string
  config_counts: Record<string, number>
}

export function fetchDraftState(signal?: AbortSignal): Promise<ConfigDraftState> {
  return request({ path: '/config/draft-state', signal })
}

export function fetchDraftSummary(signal?: AbortSignal): Promise<DraftChangeSummary> {
  return request({ path: '/config/draft-changes/summary', signal })
}

export function fetchDraftChanges(
  query: ConfigDraftChangesQuery,
  signal: AbortSignal,
): Promise<PageResult<DraftChange>> {
  return request({ path: '/config/draft-changes', query, signal })
}

export function revertDraftChange(
  entityType: string,
  entityId: string,
  command: { version: number; draft_revision: number; reason: string },
): Promise<ConfigDraftState> {
  return request({
    path: `/config/draft-changes/${entityType}/${entityId}/revert`,
    method: 'POST',
    body: command,
  })
}

export function revertAllDraftChanges(command: {
  draft_revision: number
  confirmation_text: string
  reason: string
}): Promise<ConfigDraftState> {
  return request({ path: '/config/draft-changes/revert-all', method: 'POST', body: command })
}

export function validateDraft(command: {
  draft_revision: number
}): Promise<ConfigValidationResult> {
  return request({ path: '/config/validate', method: 'POST', body: command })
}

export function publishDraft(command: {
  validation_id: string
  draft_revision: number
  acknowledged_warning_ids: string[]
  publish_note: string
}): Promise<PublishRecordDetail> {
  return request({ path: '/config/publish', method: 'POST', body: command })
}

export function fetchPublishRecords(
  query: Record<string, QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<PublishRecordListItem>> {
  return request({ path: '/config/publish-records', query, signal })
}

export function fetchPublishRecord(id: string, signal?: AbortSignal): Promise<PublishRecordDetail> {
  return request({ path: `/config/publish-records/${id}`, signal })
}

export function fetchSnapshotSummary(
  snapshotNo: number,
  signal?: AbortSignal,
): Promise<ConfigSnapshotSummary> {
  return request({ path: `/config/snapshots/${snapshotNo}/summary`, signal })
}

export function fetchRuntimeInstances(
  query: Record<string, QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<RuntimeInstance>> {
  return request({ path: '/runtime-instances', query, signal })
}
