<script setup lang="ts">
// 熔断人工操作弹窗（FE-024，附录 4.3.3.3）：
// MANUAL_OPEN 需原因+时长、MANUAL_RECOVER 需原因、PROBE_NOW 仅确认；
// 提交携带页面最后读取的 state_version，CIRCUIT_STATE_CONFLICT 时提示刷新重确认。
import { computed, ref, watch } from 'vue'
import { toErrorMessage } from '@/api/errors'
import type { CircuitStateDetail } from '@/api/circuits'

export type CircuitActionType = 'MANUAL_OPEN' | 'MANUAL_RECOVER' | 'PROBE_NOW'

const props = withDefaults(
  defineProps<{
    open: boolean
    action: CircuitActionType | null
    circuit: CircuitStateDetail | null
    stateVersion: number
    submitting?: boolean
    error?: unknown
    /** CAS 冲突时服务端回传的最新状态版本。 */
    conflictVersion?: number | null
  }>(),
  {
    submitting: false,
    conflictVersion: null,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [command: { action: CircuitActionType; reason: string; open_seconds?: number | undefined; state_version: number }]
}>()

const reason = ref('')
const openSeconds = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) {
      reason.value = ''
      openSeconds.value = ''
    }
  },
)

const reasonInvalid = computed(() => props.action !== 'PROBE_NOW' && reason.value.trim().length < 1)
const openSecondsInvalid = computed(() => {
  if (props.action !== 'MANUAL_OPEN' || openSeconds.value === '') return false
  return !/^\d+$/.test(openSeconds.value) || Number(openSeconds.value) < 1 || Number(openSeconds.value) > 3600
})
const confirmDisabled = computed(() => props.submitting || reasonInvalid.value || openSecondsInvalid.value)

const actionTitles: Record<CircuitActionType, string> = {
  MANUAL_OPEN: '人工打开熔断',
  MANUAL_RECOVER: '人工恢复熔断',
  PROBE_NOW: '立即探测',
}

const actionMessages: Record<CircuitActionType, string> = {
  MANUAL_OPEN: `将立即打开「${props.circuit?.provider_model_name ?? ''}」的熔断并进入 OPEN，不影响进行中的调用。`,
  MANUAL_RECOVER: `将直接恢复「${props.circuit?.provider_model_name ?? ''}」为 CLOSED 并清空当前窗口统计。`,
  PROBE_NOW: `将对「${props.circuit?.provider_model_name ?? ''}」执行一次最小探测，仍受探测并发限制。`,
}

function confirm(): void {
  if (confirmDisabled.value || !props.action) return
  emit('confirm', {
    action: props.action,
    reason: reason.value.trim(),
    open_seconds:
      props.action === 'MANUAL_OPEN' && openSeconds.value !== '' ? Number(openSeconds.value) : undefined,
    state_version: props.stateVersion,
  })
}

function close(): void {
  emit('update:open', false)
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open && action"
      class="lai-dialog-overlay"
      @keydown.esc="close"
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="actionTitles[action]"
      >
        <h2 class="lai-dialog-title">
          {{ actionTitles[action] }}
        </h2>
        <p class="lai-dialog-message">
          {{ actionMessages[action] }}
        </p>

        <div
          v-if="action !== 'PROBE_NOW'"
          class="lai-form-field"
        >
          <label
            class="lai-form-label"
            for="lai-circuit-reason"
          >原因<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <textarea
            id="lai-circuit-reason"
            v-model="reason"
            class="lai-input lai-textarea"
            rows="3"
            maxlength="500"
          />
        </div>

        <div
          v-if="action === 'MANUAL_OPEN'"
          class="lai-form-field"
        >
          <label
            class="lai-form-label"
            for="lai-circuit-open-seconds"
            hint
          >预计恢复时长（秒，1—3600，空为策略默认）</label>
          <input
            id="lai-circuit-open-seconds"
            v-model="openSeconds"
            class="lai-input"
            type="text"
            inputmode="numeric"
          >
          <p
            v-if="openSecondsInvalid"
            class="lai-form-message-error"
          >
            范围为 1—3600
          </p>
        </div>

        <p
          v-if="conflictVersion != null"
          class="lai-form-message-error"
          role="alert"
        >
          熔断状态已被其他操作更新（最新版本 {{ conflictVersion }}）。请关闭后刷新详情重新确认。
        </p>
        <p
          v-else-if="error"
          class="lai-form-message-error"
          role="alert"
        >
          {{ toErrorMessage(error) }}
        </p>

        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="submitting"
            @click="close"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn"
            :class="{ 'lai-btn-danger': action === 'MANUAL_OPEN' }"
            :disabled="confirmDisabled"
            @click="confirm"
          >
            {{ submitting ? '提交中…' : '确认' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lai-textarea {
  resize: vertical;
}
</style>
