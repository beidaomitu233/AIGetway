/** 管理端公共 DTO（BACKEND_PLAN 第 2 节）。 */

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  page_size: number
  sort: string
  query_started_at: string
  data_updated_at: string
}

/** 写操作返回：创建/更新为 id、version 与非敏感 entity，删除 entity 为 null。 */
export interface ManagementOperationResult<T = unknown> {
  id: string
  version: number
  entity: T | null
  draft_changed: boolean
  draft_revision: number | null
  request_id: string
}

/** 引用影响分析（ImpactAnalysis）。 */
export interface ImpactAnalysis {
  impact_version: string
  entity_type: string
  entity_id: string
  references: ImpactReference[]
  affected_alias_ids: string[]
  can_delete: boolean
  blockers: string[]
}

export interface ImpactReference {
  entity_type: string
  id: string
  name: string
  relation: string
}
