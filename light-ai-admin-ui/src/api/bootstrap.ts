import { request } from './http'

export type RuntimeMode = 'LOCAL_RUNTIME' | 'EMBEDDED' | 'STANDALONE_SERVER'

/** GET /admin/bootstrap 响应（BACKEND_PLAN 2.1，C-011）。 */
export interface BootstrapPayload {
  user: { id: string; display_name: string }
  roles: string[]
  permissions: string[]
  application_scope: string[]
  allowed_alias_ids: string[]
  runtime_mode: RuntimeMode | string
  ui_base_path: string
  admin_api_base_path: string
  timezone: string
  current_snapshot_no: number
  draft_revision: number
  draft_change_count: number
  csrf_token?: string
}

export function fetchBootstrap(): Promise<BootstrapPayload> {
  return request<BootstrapPayload>({ path: '/bootstrap' })
}
