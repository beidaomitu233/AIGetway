<script setup lang="ts">
// 在线测试面板（FE-051/052/053）：同步与流式调用、StreamEvent 解析渲染、
// 取消与离页清理；正文只保存在当前页内存；只读身份禁用测试。
import { computed, onBeforeUnmount, reactive, ref, shallowRef } from 'vue'
import PageState from '@/components/PageState.vue'
import {
  openTestStream,
  testChat,
  type ApiTestResult,
  type ApiTestUsage,
  type ApiTestCost,
  type TestStreamEvent,
} from '@/api/developerAccess'
import type { UnifiedErrorPayload } from '@/api/errors'
import { isAbortError, toErrorMessage } from '@/api/errors'
import type { DeveloperAliasSummary } from '@/api/developerAccess'

const props = defineProps<{
  alias: DeveloperAliasSummary | null
  canTest: boolean
}>()

const form = reactive({
  systemMessage: '',
  userMessage: '',
  stream: true,
  temperature: '',
  topP: '',
  maxTokens: '',
})

const submitting = ref(false)
const errorText = ref('')
const errorCode = ref('')
const resultTraceId = ref('')
const resultTotalMs = ref<number | null>(null)
const syncResult = shallowRef<ApiTestResult | null>(null)

// 流式输出状态
const streamStarted = ref(false)
const streamText = ref('')
const streamProvider = ref('')
const streamProviderModel = ref('')
const streamUsage = shallowRef<ApiTestUsage | null>(null)
const streamCost = shallowRef<ApiTestCost | null>(null)
const streamFinishReason = ref('')
const lastSequence = ref(-1)

const userMessageInvalid = computed(() => form.userMessage.trim() === '')
const temperatureInvalid = computed(() => form.temperature !== '' && !/^-?\d+(\.\d{1,4})?$/.test(form.temperature))
const topPInvalid = computed(() => form.topP !== '' && (Number(form.topP) < 0 || Number(form.topP) > 1 || !/^\d+(\.\d{1,4})?$/.test(form.topP)))
const maxTokensInvalid = computed(() => form.maxTokens !== '' && (!/^\d+$/.test(form.maxTokens) || Number(form.maxTokens) < 1))
const formInvalid = computed(() => userMessageInvalid.value || temperatureInvalid.value || topPInvalid.value || maxTokensInvalid.value)

let controller: AbortController | null = null

function resetOutput(): void {
  errorText.value = ''
  errorCode.value = ''
  resultTraceId.value = ''
  resultTotalMs.value = null
  syncResult.value = null
  streamStarted.value = false
  streamText.value = ''
  streamProvider.value = ''
  streamProviderModel.value = ''
  streamUsage.value = null
  streamCost.value = null
  streamFinishReason.value = ''
  lastSequence.value = -1
}

function clearOutput(): void {
  resetOutput()
}

function buildCommand(stream: boolean) {
  return {
    model: props.alias!.alias,
    ...(form.systemMessage.trim() === '' ? {} : { system_message: form.systemMessage.trim() }),
    user_message: form.userMessage.trim(),
    stream,
    ...(form.temperature === '' ? {} : { temperature: form.temperature }),
    ...(form.topP === '' ? {} : { top_p: form.topP }),
    ...(form.maxTokens === '' ? {} : { max_tokens: Number(form.maxTokens) }),
  }
}

function showFailure(payload: UnifiedErrorPayload): void {
  errorText.value = payload.message
  errorCode.value = payload.code
}

async function submit(): Promise<void> {
  if (!props.alias || formInvalid.value || submitting.value || !props.canTest) return
  controller?.abort()
  controller = new AbortController()
  submitting.value = true
  resetOutput()
  const useStream = form.stream
  try {
    if (!useStream) {
      const result = await testChat(buildCommand(false), controller.signal)
      syncResult.value = result
      resultTraceId.value = result.trace_id
      resultTotalMs.value = result.total_ms
    } else {
      streamStarted.value = true
      await openTestStream(
        buildCommand(true),
        {
          onEvent: (event: TestStreamEvent) => {
            if (event.sequence <= lastSequence.value) return
            lastSequence.value = event.sequence
            if (event.event === 'START') {
              streamProvider.value = event.provider
              streamProviderModel.value = event.provider_model
            } else if (event.event === 'DELTA' && typeof event.delta === 'string') {
              streamText.value += event.delta
            } else if (event.event === 'USAGE') {
              streamUsage.value = event.usage ?? null
              streamCost.value = event.cost ?? null
            } else if (event.event === 'DONE') {
              streamFinishReason.value = event.finish_reason ?? ''
              resultTotalMs.value = event.total_ms ?? null
            }
            resultTraceId.value = event.trace_id
          },
          onError: (payload) => {
            // 流内错误：保留已收文本，不显示成功
            showFailure(payload)
            resultTraceId.value = payload.trace_id ?? resultTraceId.value
          },
        },
        controller.signal,
      )
    }
  } catch (e) {
    // AbortError 由取消流程处理；其余同步请求错误在此展示
    const message = toErrorMessage(e)
    if (message !== '' && !isAbortError(e)) {
      errorText.value = message
      errorCode.value = ''
    }
  } finally {
    submitting.value = false
  }
}

function cancel(): void {
  controller?.abort()
  controller = null
  submitting.value = false
}

onBeforeUnmount(() => {
  // 离页清理：中止在途测试请求，正文随页面销毁（FE-053）
  controller?.abort()
  controller = null
})

defineExpose({ clearOutput })
</script>

<template>
  <section class="lai-test">
    <h2 class="lai-section-title">
      在线测试
    </h2>
    <p
      v-if="!canTest"
      class="lai-test-readonly"
    >
      当前角色无测试权限，仅可查看协议字段与示例。
    </p>

    <form
      class="lai-test-form"
      @submit.prevent="submit"
    >
      <div class="lai-test-row">
        <label
          class="lai-test-label"
          for="lai-test-model"
        >model</label>
        <input
          id="lai-test-model"
          class="lai-input"
          type="text"
          :value="alias?.alias ?? ''"
          disabled
        >
      </div>
      <div class="lai-test-row">
        <label
          class="lai-test-label"
          for="lai-test-system"
        >system_message（可选）</label>
        <textarea
          id="lai-test-system"
          v-model="form.systemMessage"
          class="lai-input lai-test-area"
          rows="2"
          maxlength="2000"
          :disabled="!canTest"
        />
      </div>
      <div class="lai-test-row">
        <label
          class="lai-test-label"
          for="lai-test-user"
        >user_message<span
          class="lai-required"
          aria-hidden="true"
        >*</span></label>
        <textarea
          id="lai-test-user"
          v-model="form.userMessage"
          class="lai-input lai-test-area"
          rows="3"
          maxlength="4000"
          :disabled="!canTest"
        />
        <p
          v-if="userMessageInvalid"
          class="lai-form-message-error"
        >
          user_message 必填
        </p>
      </div>
      <div class="lai-test-row lai-test-inline">
        <label class="lai-test-switch">
          <input
            v-model="form.stream"
            type="checkbox"
            :disabled="!canTest"
          >
          流式
        </label>
        <label class="lai-test-inline-item">
          temperature
          <input
            v-model="form.temperature"
            class="lai-input lai-test-num"
            type="text"
            inputmode="decimal"
            :disabled="!canTest"
          >
        </label>
        <label class="lai-test-inline-item">
          top_p
          <input
            v-model="form.topP"
            class="lai-input lai-test-num"
            type="text"
            inputmode="decimal"
            :disabled="!canTest"
          >
          <span
            v-if="topPInvalid"
            class="lai-form-message-error"
          >0—1</span>
        </label>
        <label class="lai-test-inline-item">
          max_tokens
          <input
            v-model="form.maxTokens"
            class="lai-input lai-test-num"
            type="text"
            inputmode="numeric"
            :disabled="!canTest"
          >
        </label>
      </div>

      <div class="lai-test-actions">
        <button
          type="submit"
          class="lai-btn lai-btn-primary"
          :disabled="!canTest || submitting || formInvalid || !alias"
        >
          {{ submitting ? '测试中…' : form.stream ? '发起流式测试' : '发起同步测试' }}
        </button>
        <button
          v-if="submitting"
          type="button"
          class="lai-btn"
          @click="cancel"
        >
          取消测试
        </button>
        <button
          type="button"
          class="lai-btn lai-btn-text"
          :disabled="submitting"
          @click="clearOutput"
        >
          清空输出
        </button>
      </div>
    </form>

    <PageState
      v-if="!syncResult && !streamStarted && !errorText"
      status="empty"
      message="尚未发起测试"
    />
    <template v-else>
      <p
        v-if="errorText"
        class="lai-form-message-error"
        role="alert"
      >
        {{ errorText }}
        <span
          v-if="errorCode"
          class="lai-cell-mono"
        >（{{ errorCode }}）</span>
      </p>

      <div
        v-if="syncResult"
        class="lai-test-result"
      >
        <p class="lai-test-meta">
          trace_id：
          <RouterLink
            :to="`/ui/traces/${syncResult.trace_id}`"
            class="lai-link lai-cell-mono"
          >
            {{ syncResult.trace_id }}
          </RouterLink>
          · 总耗时 {{ syncResult.total_ms }} ms
        </p>
        <pre
          class="lai-sample-code"
          data-testid="sync-output"
        >{{ syncResult.response.choices[0]?.message.content ?? '' }}</pre>
        <p class="lai-test-meta">
          Usage：输入 {{ syncResult.response.usage.prompt_tokens }} / 输出 {{ syncResult.response.usage.completion_tokens }} / 总计 {{ syncResult.response.usage.total_tokens }}
          <template v-if="syncResult.response.light_ai.cost">
            · 费用 {{ syncResult.response.light_ai.cost.amount }} {{ syncResult.response.light_ai.cost.currency }}
          </template>
          · finish_reason：{{ syncResult.response.choices[0]?.finish_reason ?? '—' }}
        </p>
      </div>

      <div
        v-if="streamStarted"
        class="lai-test-result"
      >
        <p class="lai-test-meta">
          <template v-if="streamProvider">
            Provider：{{ streamProvider }} / {{ streamProviderModel }} ·
          </template>
          <template v-if="resultTraceId">
            trace_id：
            <RouterLink
              :to="`/ui/traces/${resultTraceId}`"
              class="lai-link lai-cell-mono"
            >
              {{ resultTraceId }}
            </RouterLink>
          </template>
        </p>
        <pre
          class="lai-sample-code"
          data-testid="stream-output"
        >{{ streamText }}<span
v-if="submitting"
                                                               class="lai-cursor"
        >▌</span></pre>
        <p
          v-if="streamUsage"
          class="lai-test-meta"
        >
          Usage：输入 {{ streamUsage.prompt_tokens }} / 输出 {{ streamUsage.completion_tokens }} / 总计 {{ streamUsage.total_tokens }}
          <template v-if="streamCost">
            · 费用 {{ streamCost.amount }} {{ streamCost.currency }}<template v-if="streamCost.estimated">
              （估算）
            </template>
          </template>
        </p>
        <p
          v-if="!submitting && streamFinishReason"
          class="lai-test-meta"
        >
          finish_reason：{{ streamFinishReason }}<template v-if="resultTotalMs != null">
            · 总耗时 {{ resultTotalMs }} ms
          </template>
        </p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.lai-section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 8px;
}
.lai-test-readonly {
  font-size: 13px;
  color: #57606a;
}
.lai-test-row {
  margin-bottom: 8px;
}
.lai-test-label {
  display: block;
  font-size: 13px;
  margin-bottom: 4px;
}
.lai-test-area {
  width: 100%;
  resize: vertical;
}
.lai-test-inline {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.lai-test-inline-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.lai-test-num {
  width: 90px;
  padding: 4px 8px;
}
.lai-test-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.lai-test-actions {
  display: flex;
  gap: 8px;
  margin: 12px 0;
}
.lai-test-result {
  border-top: 1px solid #d8dee4;
  padding-top: 8px;
}
.lai-test-meta {
  font-size: 12.5px;
  color: #57606a;
  margin: 6px 0;
}
.lai-sample-code {
  margin: 0;
  padding: 12px 16px;
  background: #0d1117;
  color: #e6edf3;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
}
.lai-cursor {
  animation: lai-blink 1s step-end infinite;
}
@keyframes lai-blink {
  50% {
    opacity: 0;
  }
}
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-link {
  color: #0969da;
}
</style>
