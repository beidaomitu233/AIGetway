import { request } from './http'
import type { PageResult } from './contracts'

/** Provider Model 列表项：Provider 详情页关联模型区域使用；完整模块在 FE-P03 交付。 */
export interface ProviderModelListItemLite {
  id: string
  display_name: string
  model_id: string
  connection_status: string
  enabled: boolean
  draft_changed: boolean
}

export function listProviderModels(
  query: Record<string, import('./http').QueryValue>,
  signal: AbortSignal,
): Promise<PageResult<ProviderModelListItemLite>> {
  return request({ path: '/provider-models', query, signal })
}
