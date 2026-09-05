<script setup lang="ts">
// 批量检测面板（FE-016，附录 4.2.5.3）：1—20 个同 Provider 模型指定凭证逐个检测，
// 展示逐项进度；取消只阻止未开始项；组件卸载停止轮询。
import { computed, onUnmounted, ref, shallowRef } from 'vue'
import {
  cancelBatchCheckJob,
  fetchBatchCheckJob,
  startBatchCheck,
  type BatchCheckJobDetail,
  type BatchCheckJobStatus,
  type CheckMode,
} from '@/api/providerModels'
import type { CheckOption } from '@/components/CheckCommandDialog.vue'
import { batchItemStatusLabel, batchJobStatusLabel } from '@/app/display'
import { toErrorMessage } from '@/api/errors'

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 已选模型（同 Provider，1—20 个）。 */
    models: { id: string; label: string }[]
    credentialOptions: CheckOption[]
    providerName?: string
  }>(),
  {
    providerName: '',
  },
)

const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const POLL_INTERVAL_MS = 2000

const credentialId = ref('')
const mode = ref<CheckMode>('MINIMAL_CHAT')
const timeoutMs = ref(10000)
const submitting = ref(false)
const startError = ref('')
const pollError = ref('')
const jobDetail = shallowRef<BatchCheckJobDetail | null>(null)

let pollTimer: ReturnType<typeof setInterval> | null = null

const terminalStatuses: BatchCheckJobStatus[] = ['SUCCEEDED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED']
const jobFinished = computed(() => jobDetail.value !== null && terminalStatuses.includes(jobDetail.value.job.status))
const progressPercent = computed(() => {
  const job = jobDetail.value?.job
  if (!job || job.total_count === 0) return 0
  return Math.round((job.completed_count / job.total_count) * 100)
})
const canStart = computed(
  () => props.models.length >= 1 && props.models.length <= 20 && credentialId.value !== '' && !submitting.value,
)

function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function schedulePoll(jobId: string): void {
  stopPolling()
  pollTimer = setInterval(() => {
    void pollOnce(jobId)
  }, POLL_INTERVAL_MS)
}

async function pollOnce(jobId: string): Promise<void> {
  try {
    const detail = await fetchBatchCheckJob(jobId)
    jobDetail.value = detail
    pollError.value = ''
    if (terminalStatuses.includes(detail.job.status)) stopPolling()
  } catch (e) {
    pollError.value = toErrorMessage(e)
  }
}

async function start(): Promise<void> {
  if (!canStart.value) return
  submitting.value = true
  startError.value = ''
  try {
    const job = await startBatchCheck({
      provider_model_ids: props.models.map((item) => item.id),
      credential_id: credentialId.value,
      mode: mode.value,
      timeout_ms: timeoutMs.value,
    })
    jobDetail.value = { job, items: [] }
    schedulePoll(job.id)
  } catch (e) {
    startError.value = toErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

async function cancelJob(): Promise<void> {
  if (!jobDetail.value || jobFinished.value) return
  try {
    await cancelBatchCheckJob(jobDetail.value.job.id)
    await pollOnce(jobDetail.value.job.id)
  } catch (e) {
    pollError.value = toErrorMessage(e)
  }
}

function close(): void {
  emit('update:open', false)
}

onUnmounted(() => {
  // 离开页面停止轮询
  stopPolling()
})

defineExpose({ stopPolling, jobFinished, pollOnce })
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="close"
    >
      <div
        class="lai-dialog lai-batch"
        role="dialog"
        aria-modal="true"
        aria-label="批量检测"
      >
        <h2 class="lai-dialog-title">
          批量检测
        </h2>
        <p class="lai-dialog-message">
          对 {{ models.length }} 个模型{{ providerName ? `（${providerName}）` : '' }}逐个执行检测，单项失败不影响其余项。
        </p>

        <template v-if="!jobDetail">
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-batch-credential"
            >检测凭证<span
              class="lai-required"
              aria-hidden="true"
            >*</span></label>
            <select
              id="lai-batch-credential"
              v-model="credentialId"
              class="lai-input lai-batch-select"
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
          <div class="lai-batch-row">
            <div class="lai-form-field">
              <label
                class="lai-form-label"
                for="lai-batch-mode"
              >检测模式</label>
              <select
                id="lai-batch-mode"
                v-model="mode"
                class="lai-input lai-batch-select"
              >
                <option value="MINIMAL_CHAT">
                  最小对话
                </option>
                <option value="CONNECTION_ONLY">
                  仅连接
                </option>
              </select>
            </div>
            <div class="lai-form-field">
              <label
                class="lai-form-label"
                for="lai-batch-timeout"
              >超时（毫秒）</label>
              <input
                id="lai-batch-timeout"
                v-model.number="timeoutMs"
                class="lai-input"
                type="number"
                min="100"
                max="60000"
              >
            </div>
          </div>
          <p
            v-if="startError"
            class="lai-form-message-error"
            role="alert"
          >
            {{ startError }}
          </p>
          <div class="lai-dialog-actions">
            <button
              type="button"
              class="lai-btn"
              @click="close"
            >
              取消
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-primary"
              :disabled="!canStart"
              @click="start"
            >
              {{ submitting ? '提交中…' : '开始检测' }}
            </button>
          </div>
        </template>

        <template v-else>
          <div class="lai-batch-progress">
            <span>{{ batchJobStatusLabel(jobDetail.job.status) }}</span>
            <span>{{ jobDetail.job.completed_count }} / {{ jobDetail.job.total_count }}（{{ progressPercent }}%）</span>
            <button
              v-if="!jobFinished"
              type="button"
              class="lai-btn lai-btn-text"
              @click="cancelJob"
            >
              取消任务
            </button>
          </div>
          <p
            v-if="pollError"
            class="lai-form-message-error"
            role="alert"
          >
            {{ pollError }}
          </p>
          <ul class="lai-batch-items">
            <li
              v-for="item in jobDetail.items"
              :key="item.id"
            >
              <span class="lai-batch-item-name">{{ item.provider_model_name }}</span>
              <span class="lai-batch-item-status">
                {{ batchItemStatusLabel(item.status) }}
                <template v-if="item.error_code">（{{ item.error_code }}）</template>
              </span>
            </li>
            <li
              v-if="jobDetail.items.length === 0"
              class="lai-batch-item-name"
            >
              等待明细…
            </li>
          </ul>
          <div class="lai-dialog-actions">
            <button
              type="button"
              class="lai-btn lai-btn-primary"
              @click="close"
            >
              关闭
            </button>
          </div>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lai-batch {
  width: 640px;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 96px);
  overflow: auto;
}
.lai-batch-select {
  width: 100%;
  padding: 6px 8px;
}
.lai-batch-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.lai-batch-progress {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 13px;
  margin: 8px 0;
}
.lai-batch-items {
  margin: 8px 0;
  padding: 0;
  list-style: none;
  max-height: 280px;
  overflow: auto;
  border: 1px solid #d8dee4;
  border-radius: 6px;
}
.lai-batch-items li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 12px;
  font-size: 13px;
  border-bottom: 1px solid #eaeef2;
}
.lai-batch-item-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-batch-item-status {
  color: #57606a;
}
</style>
