// 列表页启停/删除的通用影响确认流程（FE-015/017 共用）：
// 停用与删除先读取 ImpactAnalysis，用户确认后携带 confirmed_impact_version 提交；
// 影响过期时重新拉取并要求再次确认；OBJECT_IN_USE 展示阻塞原因后保留对象。
import { reactive, shallowRef } from 'vue'
import type { ImpactReference, ManagementOperationResult } from '@/api/contracts'
import { fetchEntityImpact } from '@/api/providerModels'
import { request } from '@/api/http'
import { ApiError } from '@/api/errors'

export interface ListActionRow {
  id: string
  version: number
  enabled: boolean
}

interface ImpactPayload {
  impact_version: string
  references: ImpactReference[]
  can_delete: boolean
  blockers: string[]
}

interface TargetState {
  open: boolean
  row: ListActionRow | null
  impact: ImpactReference[]
  loading: boolean
}

export function useListActions<T extends ListActionRow>(options: {
  /** 启停命令路径（POST；body 携带 version，停用另带 confirmed_impact_version）。 */
  togglePath: (row: T) => string
  /** 删除命令路径（DELETE；body 携带 version 与 confirmed_impact_version）。 */
  deletePath: (row: T) => string
  /** 影响分析路径（GET；query operation=DISABLE|DELETE）。 */
  impactPath: (row: T) => string
  reload: () => Promise<void> | void
}) {
  const actionError = shallowRef<ApiError | null>(null)
  const busyId = shallowRef('')
  const disableImpactVersion = shallowRef('')
  const deleteImpactVersion = shallowRef('')

  const disableTarget = reactive<TargetState>({ open: false, row: null, impact: [], loading: false })
  const deleteTarget = reactive<TargetState>({ open: false, row: null, impact: [], loading: false })

  async function loadImpact(target: TargetState, path: string, operation: string): Promise<boolean> {
    target.loading = true
    try {
      const impact = await fetchEntityImpact<ImpactPayload>(path, operation)
      target.impact = impact.references
      if (operation === 'DISABLE') {
        disableImpactVersion.value = impact.impact_version
      } else {
        deleteImpactVersion.value = impact.impact_version
      }
      target.open = true
      actionError.value = null
      return true
    } catch (e) {
      if (e instanceof ApiError) actionError.value = e
      return false
    } finally {
      target.loading = false
    }
  }

  async function openToggle(row: T): Promise<void> {
    actionError.value = null
    if (!row.enabled) {
      busyId.value = row.id
      try {
        await request<ManagementOperationResult>({
          path: options.togglePath(row),
          method: 'POST',
          body: { version: row.version },
        })
        await options.reload()
      } catch (e) {
        if (e instanceof ApiError) actionError.value = e
      } finally {
        busyId.value = ''
      }
      return
    }
    if (await loadImpact(disableTarget, options.impactPath(row as T), 'DISABLE')) {
      disableTarget.row = row
    }
  }

  async function openDelete(row: T): Promise<void> {
    actionError.value = null
    if (await loadImpact(deleteTarget, options.impactPath(row as T), 'DELETE')) {
      deleteTarget.row = row
    }
  }

  async function submitToggle(_payload: { reason: string; confirmText: string }): Promise<void> {
    const row = disableTarget.row
    if (!row) return
    disableTarget.loading = true
    try {
      await request<ManagementOperationResult>({
        path: options.togglePath(row as T),
        method: 'POST',
        body: {
          version: row.version,
          confirmed_impact_version: disableImpactVersion.value,
        },
      })
      disableTarget.open = false
      await options.reload()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'IMPACT_ANALYSIS_EXPIRED') {
        actionError.value = e
        await loadImpact(disableTarget, options.impactPath(row as T), 'DISABLE')
        disableTarget.row = row
      } else if (e instanceof ApiError) {
        actionError.value = e
        disableTarget.open = false
      }
    } finally {
      disableTarget.loading = false
    }
  }

  async function submitDelete(_payload: { reason: string; confirmText: string }): Promise<void> {
    const row = deleteTarget.row
    if (!row) return
    deleteTarget.loading = true
    try {
      await request<ManagementOperationResult>({
        path: options.deletePath(row as T),
        method: 'DELETE',
        body: {
          version: row.version,
          confirmed_impact_version: deleteImpactVersion.value,
        },
      })
      deleteTarget.open = false
      await options.reload()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'IMPACT_ANALYSIS_EXPIRED') {
        actionError.value = e
        await loadImpact(deleteTarget, options.impactPath(row as T), 'DELETE')
        deleteTarget.row = row
      } else if (e instanceof ApiError) {
        actionError.value = e
        deleteTarget.open = false
      }
    } finally {
      deleteTarget.loading = false
    }
  }

  function actionText(): string {
    const err = actionError.value
    if (!err) return ''
    if (err.code === 'OBJECT_IN_USE') return '对象仍被引用，无法删除；请先处理相关引用'
    if (err.code === 'IMPACT_ANALYSIS_EXPIRED') return '影响分析已过期，请重新确认'
    return err.message
  }

  return {
    actionError,
    busyId,
    disableTarget,
    deleteTarget,
    openToggle,
    openDelete,
    submitToggle,
    submitDelete,
    actionText,
  }
}
