<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import FormField from '@/components/FormField.vue'
import type { ProviderCheckCommand, ProviderCheckRecord } from '@/api/providers'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    /** 可选模型与凭证（CONNECTION_ONLY 两者均可空）。 */
    target: { models: Array<{ id: string; label: string }>; credentials: Array<{ id: string; label: string }> }
    loading?: boolean
    /** 页面提交检测命令后的结果或失败摘要。 */
    result?: ProviderCheckRecord | null
    errorText?: string
  }>(),
  {
    loading: false,
    result: null,
    errorText: '',
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  submit: [command: ProviderCheckCommand]
}>()

const mode = ref<ProviderCheckCommand['mode']>('CONNECTION_ONLY')
const modelId = ref('')
const credentialId = ref('')
const timeoutMs = ref<number>(10000)

watch(
  () => props.open,
  (open) => {
    if (open) {
      mode.value = props.target.models.length === 0 ? 'CONNECTION_ONLY' : 'MINIMAL_CHAT'
      modelId.value = ''
      credentialId.value = ''
      timeoutMs.value = 10000
    }
  },
)

const timeoutInvalid = computed(() => {
  const value = timeoutMs.value
  return !Number.isInteger(value) || value < 100 || value > 60000
})
const modelInvalid = computed(() => mode.value === 'MINIMAL_CHAT' && modelId.value === '')
const submitDisabled = computed(() => props.loading || timeoutInvalid.value || modelInvalid.value)

function submit(): void {
  if (submitDisabled.value) return
  emit('submit', {
    mode: mode.value,
    timeout_ms: timeoutMs.value,
    ...(mode.value === 'MINIMAL_CHAT' ? { provider_model_id: modelId.value } : {}),
    ...(credentialId.value !== '' ? { credential_id: credentialId.value } : {}),
  })
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="emit('update:open', false)"
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
        <template v-if="!result">
          <FormField
            label="检测方式"
            for-id="lai-check-mode"
          >
            <select
              id="lai-check-mode"
              v-model="mode"
              class="lai-select"
            >
              <option value="CONNECTION_ONLY">
                仅连接检测
              </option>
              <option value="MINIMAL_CHAT">
                最小对话检测
              </option>
            </select>
          </FormField>
          <FormField
            v-if="mode === 'MINIMAL_CHAT'"
            label="模型"
            for-id="lai-check-model"
            required
          >
            <select
              id="lai-check-model"
              v-model="modelId"
              class="lai-select"
            >
              <option value="">
                请选择
              </option>
              <option
                v-for="model in target.models"
                :key="model.id"
                :value="model.id"
              >
                {{ model.label }}
              </option>
            </select>
          </FormField>
          <FormField
            label="凭证"
            for-id="lai-check-credential"
            hint="不选择时由目标池自动选择"
          >
            <select
              id="lai-check-credential"
              v-model="credentialId"
              class="lai-select"
            >
              <option value="">
                自动选择
              </option>
              <option
                v-for="credential in target.credentials"
                :key="credential.id"
                :value="credential.id"
              >
                {{ credential.label }}
              </option>
            </select>
          </FormField>
          <FormField
            label="超时（毫秒）"
            for-id="lai-check-timeout"
            required
            :error="timeoutInvalid ? '100—60000 之间的整数' : ''"
          >
            <input
              id="lai-check-timeout"
              v-model.number="timeoutMs"
              class="lai-input"
              type="number"
              min="100"
              max="60000"
            >
          </FormField>
          <p
            v-if="errorText"
            class="lai-form-message-error"
            role="alert"
          >
            {{ errorText }}
          </p>
          <div class="lai-dialog-actions">
            <button
              type="button"
              class="lai-btn"
              :disabled="props.loading"
              @click="emit('update:open', false)"
            >
              取消
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-primary"
              :disabled="submitDisabled"
              @click="submit"
            >
              开始检测
            </button>
          </div>
        </template>
        <template v-else>
          <p class="lai-dialog-message">
            检测结果：
            <strong :class="result.status === 'SUCCEEDED' ? 'lai-check-ok' : 'lai-check-fail'">
              {{ result.status === 'SUCCEEDED' ? '成功' : '失败' }}
            </strong>
          </p>
          <p class="lai-dialog-message">
            耗时：{{ result.total_ms ?? '—' }} ms
          </p>
          <p
            v-if="result.provider_request_id"
            class="lai-dialog-message"
          >
            Provider Request ID：{{ result.provider_request_id }}
          </p>
          <p
            v-if="result.error_code"
            class="lai-dialog-message lai-check-fail"
          >
            {{ result.error_code }} · {{ result.error_summary ?? '' }}
          </p>
          <div class="lai-dialog-actions">
            <button
              type="button"
              class="lai-btn lai-btn-primary"
              @click="emit('update:open', false)"
            >
              关闭
            </button>
          </div>
        </template>
      </div>
    </div>
  </Teleport>
</template>
