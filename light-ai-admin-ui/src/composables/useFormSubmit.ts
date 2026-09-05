import { computed, ref, shallowRef } from 'vue'
import { ApiError, TimeoutError, toErrorMessage } from '@/api/errors'

export interface SubmitOutcome {
  ok: boolean
  error?: ApiError | TimeoutError | Error
}

/**
 * 表单提交状态：提交中禁用防连点，字段错误按字段定位，
 * 版本冲突保留用户输入并标记 conflict，等待用户刷新对比后重试。
 */
export function useFormSubmit() {
  const submitting = ref(false)
  const fieldMessages = ref<Record<string, string>>({})
  const submitError = shallowRef<ApiError | TimeoutError | Error | null>(null)
  const conflictError = shallowRef<ApiError | null>(null)

  const errorText = computed(() => (submitError.value ? toErrorMessage(submitError.value) : ''))

  async function submit(action: () => Promise<void>): Promise<SubmitOutcome> {
    if (submitting.value) return { ok: false }
    submitting.value = true
    fieldMessages.value = {}
    submitError.value = null
    conflictError.value = null
    try {
      await action()
      return { ok: true }
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.isVersionConflict) {
          conflictError.value = e
        } else {
          submitError.value = e
        }
        const fields: Record<string, string> = {}
        for (const [field, message] of e.fieldMessages) {
          fields[field] = message
        }
        fieldMessages.value = fields
        return { ok: false, error: e }
      }
      const normalized =
        e instanceof TimeoutError || e instanceof Error ? e : new Error('网络请求失败')
      submitError.value = normalized
      return { ok: false, error: normalized }
    } finally {
      submitting.value = false
    }
  }

  function reset(): void {
    fieldMessages.value = {}
    submitError.value = null
    conflictError.value = null
  }

  return { submitting, fieldMessages, submitError, conflictError, errorText, submit, reset }
}
