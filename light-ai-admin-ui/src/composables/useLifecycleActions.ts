import { reactive, ref } from 'vue'
import { ApiError } from '@/api/errors'
import type { ImpactAnalysis } from '@/api/contracts'

interface LifecycleActionsOptions {
  getImpact: (id: string, operation: 'DISABLE' | 'DELETE') => Promise<ImpactAnalysis>
  enable: (id: string, version: number) => Promise<unknown>
  disable: (id: string, version: number, confirmedImpactVersion: string) => Promise<unknown>
  remove: (id: string, version: number, confirmedImpactVersion: string) => Promise<unknown>
  /** 操作成功后由页面刷新数据与草稿计数。 */
  onChanged: () => void
}

interface ImpactDialogState {
  open: boolean
  operation: 'DISABLE' | 'DELETE'
  id: string
  version: number
  title: string
  message: string
  impact: ImpactAnalysis | null
  loading: boolean
  submitting: boolean
  /** 影响分析或提交失败的提示；重试时清空。 */
  errorText: string
}

/**
 * 启用/停用/删除命令编排：
 * 启用直接带 version 提交；停用与删除先读取 ImpactAnalysis，
 * 确认后回传 confirmed_impact_version；影响过期时重新拉取再确认。
 */
export function useLifecycleActions(options: LifecycleActionsOptions) {
  const dialog = reactive<ImpactDialogState>({
    open: false,
    operation: 'DISABLE',
    id: '',
    version: 0,
    title: '',
    message: '',
    impact: null,
    loading: false,
    submitting: false,
    errorText: '',
  })
  const actionError = ref('')
  /** 正在执行命令的对象 id；用于禁用对应行操作。 */
  const busyIds = ref<Set<string>>(new Set())

  async function loadImpact(id: string, operation: 'DISABLE' | 'DELETE', version: number): Promise<void> {
    dialog.open = true
    dialog.operation = operation
    dialog.id = id
    dialog.version = version
    dialog.title = operation === 'DISABLE' ? '停用确认' : '删除确认'
    dialog.message =
      operation === 'DISABLE'
        ? '停用将写入草稿，发布后该对象不再参与新请求。'
        : '删除将写入草稿或直接移除未发布对象，操作不可撤销。'
    dialog.loading = true
    dialog.errorText = ''
    dialog.impact = null
    try {
      dialog.impact = await options.getImpact(id, operation)
    } catch (e) {
      if (e instanceof ApiError) {
        dialog.errorText = `${e.message}（${e.code}）`
      } else {
        dialog.errorText = '影响分析失败，请稍后重试'
      }
    } finally {
      dialog.loading = false
    }
  }

  async function runCommand(run: () => Promise<unknown>, id: string): Promise<boolean> {
    busyIds.value = new Set([...busyIds.value, id])
    try {
      await run()
      dialog.open = false
      options.onChanged()
      return true
    } catch (e) {
      if (e instanceof ApiError && e.code === 'IMPACT_ANALYSIS_EXPIRED') {
        dialog.submitting = false
        await loadImpact(dialog.id, dialog.operation, dialog.version)
        dialog.errorText = '引用关系已变化，需要重新确认影响'
        return false
      }
      if (e instanceof ApiError) {
        if (dialog.open) {
          dialog.errorText = `${e.message}（${e.code}）`
        } else {
          actionError.value = `${e.message}（${e.code}）`
        }
      } else {
        if (dialog.open) {
          dialog.errorText = '操作失败，请稍后重试'
        } else {
          actionError.value = '操作失败，请稍后重试'
        }
      }
      return false
    } finally {
      const next = new Set(busyIds.value)
      next.delete(id)
      busyIds.value = next
    }
  }

  async function enable(id: string, version: number): Promise<void> {
    actionError.value = ''
    await runCommand(() => options.enable(id, version), id)
  }

  async function confirmImpact(): Promise<void> {
    if (!dialog.impact || dialog.submitting) return
    dialog.submitting = true
    const run =
      dialog.operation === 'DISABLE'
        ? () => options.disable(dialog.id, dialog.version, dialog.impact!.impact_version)
        : () => options.remove(dialog.id, dialog.version, dialog.impact!.impact_version)
    await runCommand(run, dialog.id)
    dialog.submitting = false
  }

  function closeDialog(): void {
    if (dialog.submitting) return
    dialog.open = false
  }

  function isBusy(id: string): boolean {
    return busyIds.value.has(id)
  }

  return {
    dialog,
    actionError,
    busyIds,
    isBusy,
    enable,
    requestDisable: (id: string, version: number) => loadImpact(id, 'DISABLE', version),
    requestDelete: (id: string, version: number) => loadImpact(id, 'DELETE', version),
    confirmImpact,
    closeDialog,
  }
}
