<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { ImpactReference } from '@/api/contracts'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    message: string
    /** 影响对象列表（ImpactAnalysis.references）。 */
    impact?: ImpactReference[]
    danger?: boolean
    /** 高风险操作要求填写原因。 */
    requireReason?: boolean
    /** 要求输入固定确认文本（如全部撤销 REVERT ALL）。 */
    requireConfirmText?: string
    confirmLabel?: string
    loading?: boolean
  }>(),
  {
    danger: false,
    requireReason: false,
    confirmLabel: '确认',
    loading: false,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [payload: { reason: string; confirmText: string }]
}>()

const reason = ref('')
const confirmText = ref('')
const reasonInput = ref<HTMLInputElement | null>(null)

watch(
  () => props.open,
  (open) => {
    if (open) {
      reason.value = ''
      confirmText.value = ''
      void nextTick(() => reasonInput.value?.focus())
    }
  },
)

const reasonInvalid = computed(() => props.requireReason && reason.value.trim() === '')
const confirmTextInvalid = computed(
  () => props.requireConfirmText !== undefined && confirmText.value !== props.requireConfirmText,
)
const confirmDisabled = computed(
  () => props.loading || reasonInvalid.value || confirmTextInvalid.value,
)

function cancel(): void {
  emit('update:open', false)
}

function confirm(): void {
  if (confirmDisabled.value) return
  emit('confirm', { reason: reason.value.trim(), confirmText: confirmText.value })
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="cancel"
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
      >
        <h2 class="lai-dialog-title">
          {{ title }}
        </h2>
        <p class="lai-dialog-message">
          {{ message }}
        </p>
        <ul
          v-if="impact && impact.length > 0"
          class="lai-dialog-impact"
        >
          <li
            v-for="item in impact"
            :key="`${item.entity_type}-${item.id}`"
          >
            {{ item.entity_type }} · {{ item.name }}（{{ item.relation }}）
          </li>
        </ul>
        <div
          v-if="requireReason"
          class="lai-dialog-field"
        >
          <label
            class="lai-form-label"
            for="lai-dialog-reason"
          >原因</label>
          <input
            id="lai-dialog-reason"
            ref="reasonInput"
            v-model="reason"
            class="lai-input"
            type="text"
            maxlength="500"
          >
        </div>
        <div
          v-if="requireConfirmText"
          class="lai-dialog-field"
        >
          <label
            class="lai-form-label"
            for="lai-dialog-confirm-text"
          >
            输入 {{ requireConfirmText }} 以确认
          </label>
          <input
            id="lai-dialog-confirm-text"
            v-model="confirmText"
            class="lai-input"
            type="text"
            autocomplete="off"
          >
        </div>
        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="loading"
            @click="cancel"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn"
            :class="{ 'lai-btn-danger': danger }"
            :disabled="confirmDisabled"
            @click="confirm"
          >
            {{ confirmLabel }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
