<script setup lang="ts">
// 检测命令弹窗（FE-009/013/014/015/018 共用形态）：收集 ProviderCheckCommand 输入，
// 由调用方执行请求并通过 result/error 回传展示；本组件不直接调用 API。
import { computed, ref, watch } from 'vue'
import type { CheckMode, ProviderCheckRecord } from '@/api/credentials'
import { checkStatusLabel, checkModeLabel } from '@/app/display'
import { toErrorMessage } from '@/api/errors'

export interface CheckOption {
  id: string
  label: string
}

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    /** 目标说明（如凭证名称或模型名称）。 */
    targetLabel: string
    /** 模型选项；检测目标已是模型时留空。 */
    modelOptions?: CheckOption[]
    /** 凭证选项；检测目标已是凭证时留空。 */
    credentialOptions?: CheckOption[]
    /** 是否必选一个模型（凭证/候选检测需要）。 */
    requireModel?: boolean
    /** 是否必选一个凭证（模型/候选检测需要）。 */
    requireCredential?: boolean
    submitting?: boolean
    result?: ProviderCheckRecord | null
    error?: unknown
  }>(),
  {
    modelOptions: () => [],
    credentialOptions: () => [],
    requireModel: false,
    requireCredential: false,
    submitting: false,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [command: { provider_model_id?: string | undefined; credential_id?: string | undefined; mode: CheckMode; timeout_ms: number }]
}>()

const DEFAULT_TIMEOUT_MS = 10000
const TIMEOUT_MIN = 100
const TIMEOUT_MAX = 60000

const providerModelId = ref('')
const credentialId = ref('')
const mode = ref<CheckMode>('MINIMAL_CHAT')
const timeoutMs = ref<number>(DEFAULT_TIMEOUT_MS)

watch(
  () => props.open,
  (open) => {
    if (open) {
      providerModelId.value = props.requireModel && props.modelOptions.length === 1 ? props.modelOptions[0]!.id : ''
      credentialId.value = props.requireCredential && props.credentialOptions.length === 1 ? props.credentialOptions[0]!.id : ''
      mode.value = 'MINIMAL_CHAT'
      timeoutMs.value = DEFAULT_TIMEOUT_MS
    }
  },
  { immediate: true },
)

const modelInvalid = computed(() => props.requireModel && providerModelId.value === '')
const credentialInvalid = computed(() => props.requireCredential && credentialId.value === '')
const timeoutInvalid = computed(
  () =>
    !Number.isInteger(timeoutMs.value) ||
    timeoutMs.value < TIMEOUT_MIN ||
    timeoutMs.value > TIMEOUT_MAX,
)
const confirmDisabled = computed(
  () => props.submitting || modelInvalid.value || credentialInvalid.value || timeoutInvalid.value,
)

function confirm(): void {
  if (confirmDisabled.value) return
  emit('confirm', {
    provider_model_id: props.requireModel ? providerModelId.value : undefined,
    credential_id: props.requireCredential ? credentialId.value : undefined,
    mode: mode.value,
    timeout_ms: timeoutMs.value,
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
        :aria-label="title"
      >
        <h2 class="lai-dialog-title">
          {{ title }}
        </h2>
        <p class="lai-dialog-message">
          {{ targetLabel }}
        </p>

        <div
          v-if="requireModel"
          class="lai-dialog-field"
        >
          <label
            class="lai-form-label"
            for="lai-check-model"
          >检测模型</label>
          <select
            id="lai-check-model"
            v-model="providerModelId"
            class="lai-input lai-select"
          >
            <option
              value=""
              disabled
            >
              请选择模型
            </option>
            <option
              v-for="item in modelOptions"
              :key="item.id"
              :value="item.id"
            >
              {{ item.label }}
            </option>
          </select>
        </div>

        <div
          v-if="requireCredential"
          class="lai-dialog-field"
        >
          <label
            class="lai-form-label"
            for="lai-check-credential"
          >检测凭证</label>
          <select
            id="lai-check-credential"
            v-model="credentialId"
            class="lai-input lai-select"
          >
            <option
              value=""
              disabled
            >
              请选择凭证
            </option>
            <option
              v-for="item in credentialOptions"
              :key="item.id"
              :value="item.id"
            >
              {{ item.label }}
            </option>
          </select>
        </div>

        <div class="lai-dialog-field">
          <label
            class="lai-form-label"
            for="lai-check-mode"
          >检测模式</label>
          <select
            id="lai-check-mode"
            v-model="mode"
            class="lai-input lai-select"
          >
            <option value="MINIMAL_CHAT">
              {{ checkModeLabel('MINIMAL_CHAT') }}
            </option>
            <option value="CONNECTION_ONLY">
              {{ checkModeLabel('CONNECTION_ONLY') }}
            </option>
          </select>
        </div>

        <div class="lai-dialog-field">
          <label
            class="lai-form-label"
            for="lai-check-timeout"
          >超时（毫秒，100—60000）</label>
          <input
            id="lai-check-timeout"
            v-model.number="timeoutMs"
            class="lai-input"
            type="number"
            min="100"
            max="60000"
          >
        </div>

        <p
          v-if="error"
          class="lai-form-message-error"
          role="alert"
        >
          {{ toErrorMessage(error) }}
        </p>

        <div
          v-if="result"
          class="lai-check-result"
          :class="{ 'lai-check-result-failed': result.status === 'FAILED' }"
        >
          <p>检测结果：{{ checkStatusLabel(result.status) }}</p>
          <p>耗时：{{ result.total_ms }} ms</p>
          <p v-if="result.usage">
            Token：输入 {{ result.usage.prompt_tokens }} / 输出 {{ result.usage.completion_tokens }}
          </p>
          <p v-if="result.provider_request_id">
            Provider 请求 ID：{{ result.provider_request_id }}
          </p>
          <p v-if="result.error_code">
            失败码：{{ result.error_code }}
          </p>
          <p v-if="result.error_summary">
            失败摘要：{{ result.error_summary }}
          </p>
        </div>

        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="submitting"
            @click="close"
          >
            关闭
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="confirmDisabled"
            @click="confirm"
          >
            {{ submitting ? '检测中…' : '开始检测' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lai-select {
  width: 100%;
  padding: 6px 8px;
}
.lai-check-result {
  margin-top: 8px;
  padding: 8px 12px;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.8;
}
.lai-check-result p {
  margin: 0;
}
.lai-check-result-failed {
  border-color: #cf222e;
  color: #cf222e;
}
</style>
