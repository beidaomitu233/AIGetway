import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { type BootstrapPayload, fetchBootstrap } from '@/api/bootstrap'
import { ApiError, TimeoutError } from '@/api/errors'
import { registerCsrfToken } from '@/api/http'
import { applyServerBasePaths } from '@/app/runtimeConfig'

export type BootstrapStatus = 'idle' | 'loading' | 'ready' | 'error' | 'forbidden'

/**
 * 全局会话缓存：身份、权限、运行模式与基路径。
 * 页面查询数据不进入本 store，按模块保存在各自 composable 中。
 */
export const useBootstrapStore = defineStore('bootstrap', () => {
  const status = ref<BootstrapStatus>('idle')
  const error = shallowRef<ApiError | TimeoutError | Error | null>(null)
  const userId = ref('')
  const displayName = ref('')
  const roles = ref<string[]>([])
  const permissions = ref<string[]>([])
  const applicationScope = ref<string[]>([])
  const allowedAliasIds = ref<string[]>([])
  const runtimeMode = ref('')
  const timezone = ref('')
  const currentSnapshotNo = ref<number | null>(null)
  const draftRevision = ref<number | null>(null)
  const draftChangeCount = ref(0)

  const isAuthenticated = computed(() => status.value === 'ready')
  const isDeveloperScoped = computed(
    () => applicationScope.value.length > 0 && roles.value.includes('DEVELOPER'),
  )

  let loadPromise: Promise<void> | null = null

  async function load(): Promise<void> {
    if (status.value === 'ready' || status.value === 'forbidden') return
    if (loadPromise) return loadPromise
    status.value = 'loading'
    error.value = null
    loadPromise = (async () => {
      try {
        const data = await fetchBootstrap()
        apply(data)
        applyServerBasePaths(data.ui_base_path, data.admin_api_base_path)
        registerCsrfToken(data.csrf_token)
        status.value = 'ready'
      } catch (e) {
        if (e instanceof ApiError && e.isAccessDenied && e.status === 403) {
          reset()
          status.value = 'forbidden'
        } else {
          error.value = e instanceof Error ? e : new Error('未知错误')
          status.value = 'error'
        }
      } finally {
        loadPromise = null
      }
    })()
    return loadPromise
  }

  function apply(data: BootstrapPayload): void {
    userId.value = data.user.id
    displayName.value = data.user.display_name
    roles.value = [...data.roles]
    permissions.value = [...data.permissions]
    applicationScope.value = [...data.application_scope]
    allowedAliasIds.value = [...data.allowed_alias_ids]
    runtimeMode.value = data.runtime_mode
    timezone.value = data.timezone
    currentSnapshotNo.value = data.current_snapshot_no
    draftRevision.value = data.draft_revision
    draftChangeCount.value = data.draft_change_count
  }

  function can(permission: string): boolean {
    return permissions.value.includes(permission)
  }

  /** 切换身份或会话失效时清空所有缓存。 */
  function invalidate(): void {
    reset()
    registerCsrfToken(undefined)
    status.value = 'idle'
  }

  function reset(): void {
    userId.value = ''
    displayName.value = ''
    roles.value = []
    permissions.value = []
    applicationScope.value = []
    allowedAliasIds.value = []
    runtimeMode.value = ''
    timezone.value = ''
    currentSnapshotNo.value = null
    draftRevision.value = null
    draftChangeCount.value = 0
    error.value = null
  }

  return {
    status,
    error,
    userId,
    displayName,
    roles,
    permissions,
    applicationScope,
    allowedAliasIds,
    runtimeMode,
    timezone,
    currentSnapshotNo,
    draftRevision,
    draftChangeCount,
    isAuthenticated,
    isDeveloperScoped,
    load,
    can,
    invalidate,
  }
})
