<script setup lang="ts">
// Credential 轮换弹窗（FE-014）：新密钥只写入，旧值不回显；
// 轮换即时生效，运行中的 Attempt 保留已取得的内存值。
import { ref, watch } from 'vue'
import SecretInput from '@/components/SecretInput.vue'
import { toErrorMessage } from '@/api/errors'

const props = withDefaults(
  defineProps<{
    open: boolean
    credentialName: string
    version: number
    submitting?: boolean
    error?: unknown
  }>(),
  {
    submitting: false,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [command: { secret_value: string; secret_value_confirm: string; version: number }]
}>()

const secretValue = ref('')
const secretValueConfirm = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) {
      secretValue.value = ''
      secretValueConfirm.value = ''
    }
  },
  { immediate: true },
)

function invalid(): boolean {
  return (
    secretValue.value.length < 1 ||
    secretValue.value.length > 4096 ||
    secretValue.value !== secretValueConfirm.value
  )
}

function confirm(): void {
  if (props.submitting || invalid()) return
  emit('confirm', {
    secret_value: secretValue.value,
    secret_value_confirm: secretValueConfirm.value,
    version: props.version,
  })
}

function close(): void {
  emit('update:open', false)
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="close"
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="轮换密钥"
      >
        <h2 class="lai-dialog-title">
          轮换密钥
        </h2>
        <p class="lai-dialog-message">
          正在为「{{ credentialName }}」轮换密钥。保存成功后旧密钥立即失效，运行中的调用不受影响。
        </p>

        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="lai-rotate-secret"
          >新密钥<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <SecretInput
            id="lai-rotate-secret"
            v-model="secretValue"
            placeholder="1—4096 字符"
          />
        </div>
        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="lai-rotate-confirm"
          >确认新密钥<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <SecretInput
            id="lai-rotate-confirm"
            v-model="secretValueConfirm"
            placeholder="再次输入新密钥"
          />
          <p
            v-if="secretValueConfirm !== '' && secretValue !== secretValueConfirm"
            class="lai-form-message-error"
          >
            两次输入的密钥不一致
          </p>
        </div>

        <p
          v-if="error"
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
            class="lai-btn lai-btn-primary"
            :disabled="submitting || invalid()"
            @click="confirm"
          >
            {{ submitting ? '轮换中…' : '确认轮换' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
