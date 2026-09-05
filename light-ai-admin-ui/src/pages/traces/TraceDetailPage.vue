<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import StatusText from '@/components/StatusText.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import {
  attemptTypeLabels,
  formatDateTime,
  formatDuration,
  sourceModeLabels,
  traceStatusLabels,
} from '@/app/display'
import { Permission } from '@/app/permissions'
import {
  type AttemptItem,
  type TimelineItem,
  type TraceDetail,
  fetchTrace,
} from '@/api/traces'
import { ApiError, isAbortError } from '@/api/errors'

const DETAIL_REFRESH_MS = 5000

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()

const traceId = computed(() => route.params.traceId as string)
const canDiagnostics = computed(() => store.can(Permission.traceDiagnostics))

const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const detail = ref<TraceDetail | null>(null)

const diagnosticsRequested = ref(false)
const diagnosticsError = ref('')
const diagnosticsDenied = ref(false)

const copyState = ref('')
async function copyText(value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value)
    copyState.value = '已复制'
  } catch {
    copyState.value = '复制失败'
  }
  setTimeout(() => {
    copyState.value = ''
  }, 1500)
}

let controller: AbortController | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null

async function load(): Promise<void> {
  controller?.abort()
  controller = new AbortController()
  state.value = 'loading'
  loadError.value = null
  try {
    detail.value = await fetchTrace(traceId.value, {
      includeDiagnostics: diagnosticsRequested.value,
      signal: controller.signal,
    })
    state.value = 'ready'
    if (diagnosticsRequested.value) diagnosticsError.value = ''
  } catch (e) {
    if (isAbortError(e)) return
    if (diagnosticsRequested.value && e instanceof ApiError && e.status === 403) {
      diagnosticsDenied.value = true
      diagnosticsError.value = '当前身份没有诊断样本读取权限'
      diagnosticsRequested.value = false
      return
    }
    loadError.value = e
    state.value = 'error'
  }
}

function isRunning(detail: TraceDetail | null): boolean {
  return detail?.trace.status === 'RUNNING' || detail?.trace.status === 'QUEUED'
}

onMounted(() => {
  void load()
  refreshTimer = setInterval(() => {
    if (document.visibilityState !== 'visible') return
    if (isRunning(detail.value)) void load()
  }, DETAIL_REFRESH_MS)
})
onUnmounted(() => {
  if (refreshTimer !== null) clearInterval(refreshTimer)
  controller?.abort()
})
watch(traceId, () => {
  detail.value = null
  diagnosticsRequested.value = false
  diagnosticsDenied.value = false
  void load()
})

async function loadDiagnostics(): Promise<void> {
  diagnosticsRequested.value = true
  diagnosticsDenied.value = false
  await load()
}

const timeline = computed<TimelineItem[]>(() => detail.value?.timeline ?? [])

const timelineTypeLabels: Record<string, string> = {
  TRACE_CREATED: '创建 Trace',
  QUEUE_ENTERED: '进入排队',
  QUEUE_ACQUIRED: '取得容量',
  QUEUE_ENDED: '排队结束',
  ROUTE_DECISION: '路由判定',
  ATTEMPT_STARTED: '尝试开始',
  ATTEMPT_FIRST_TOKEN: '首 Token',
  ATTEMPT_ENDED: '尝试结束',
  RECOVERY_DECIDED: '恢复决策',
  CIRCUIT_CHANGED: '熔断迁移',
  TRACE_ENDED: 'Trace 结束',
}

/** 点击时间线节点：Attempt 打开抽屉；Recovery 高亮来源与目标；FAIL 只高亮来源。 */
const openAttemptId = ref('')
const highlightAttemptIds = ref<Set<string>>(new Set())

const selectedAttempt = computed<AttemptItem | null>(
  () => detail.value?.attempts.find((attempt) => attempt.id === openAttemptId.value) ?? null,
)

function onTimelineClick(item: TimelineItem): void {
  if (item.type.startsWith('ATTEMPT')) {
    if (item.attempt_id) openAttemptId.value = item.attempt_id
    return
  }
  if (item.type === 'RECOVERY_DECIDED') {
    const decision = detail.value?.recovery_decisions.find((r) => r.id === item.source_id)
    if (!decision) return
    const ids = new Set<string>([decision.source_attempt_id])
    if (decision.action !== 'FAIL') {
      const target = detail.value?.attempts.find(
        (attempt) => attempt.route_candidate_id === decision.target_route_candidate_id,
      )
      if (target) ids.add(target.id)
    }
    highlightAttemptIds.value = ids
    return
  }
}

const attemptReservations = (attemptId: string) =>
  (detail.value?.capacity_reservations ?? []).filter(
    (reservation) => reservation.attempt_id === attemptId,
  )

const failedAttemptIds = computed(
  () =>
    new Set(
      (detail.value?.attempts ?? [])
        .filter((attempt) => attempt.status === 'FAILED' || attempt.status === 'CANCELLED')
        .map((attempt) => attempt.id),
    ),
)

const totalCostText = computed(() => {
  const trace = detail.value?.trace
  if (!trace || trace.total_cost === null) return '—'
  return `${trace.total_cost} ${trace.currency ?? ''}`
})

const sampleSectionVisible = computed(
  () => canDiagnostics.value && detail.value?.request_summary.content_sample_status === 'AVAILABLE',
)
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        Trace 详情
      </h1>
      <div class="lai-row-actions">
        <button
          type="button"
          class="lai-btn"
          @click="router.back()"
        >
          返回列表
        </button>
        <button
          v-if="detail"
          type="button"
          class="lai-btn"
          @click="copyText(traceId)"
        >
          复制 Trace ID
        </button>
        <span
          v-if="copyState"
          class="lai-related-meta"
        >{{ copyState }}</span>
      </div>
    </div>

    <PageState
      v-if="state === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="state === 'error'"
      status="error"
      :error="loadError"
      @retry="load"
    />
    <template v-else-if="detail">
      <div class="lai-card">
        <h2 class="lai-card-title">
          Trace 摘要
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">状态</span>
            <StatusText
              :value="detail.trace.status"
              :labels="traceStatusLabels"
            />
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">开始时间</span>{{ formatDateTime(detail.trace.started_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">结束时间</span>{{ formatDateTime(detail.trace.ended_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">总耗时</span>{{ formatDuration(detail.trace.total_ms) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">应用</span>{{ detail.trace.application }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">Alias 快照</span>{{ detail.trace.alias }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">配置快照</span>#{{ detail.trace.config_snapshot_no }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">来源</span>
            {{ sourceModeLabels[detail.trace.source_mode] ?? detail.trace.source_mode }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">调用方式</span>{{ detail.trace.requested_stream ? '流式' : '同步' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">尝试次数</span>{{ detail.trace.attempt_count }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">重试 / 凭证切换 / 候选切换</span>
            {{ detail.trace.retry_count }} / {{ detail.trace.credential_failover_count }} /
            {{ detail.trace.fallback_count }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">排队耗时</span>{{ formatDuration(detail.trace.queued_ms) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">首 Token</span>{{ formatDuration(detail.trace.first_token_ms) }}
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          请求摘要
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">访问凭证</span>{{ detail.request_summary.access_credential_name ?? '—' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">业务用户</span>{{ detail.request_summary.request_user ?? '—' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">消息数（system/user/assistant）</span>
            {{ detail.request_summary.system_message_count }} /
            {{ detail.request_summary.user_message_count }} /
            {{ detail.request_summary.assistant_message_count }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">输入字符数</span>{{ detail.request_summary.input_char_count }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">temperature</span>{{ detail.request_summary.temperature ?? '未设置' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">top_p</span>{{ detail.request_summary.top_p ?? '未设置' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">max_tokens</span>{{ detail.request_summary.max_tokens ?? '未设置' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">stop 数量</span>{{ detail.request_summary.stop_count ?? 0 }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">provider_options</span>
            {{ detail.request_summary.provider_option_keys.length > 0 ? detail.request_summary.provider_option_keys.join('、') : '无' }}
          </div>
        </div>
        <template v-if="sampleSectionVisible">
          <h3 class="lai-subsection-title">
            诊断样本
          </h3>
          <p
            v-if="diagnosticsDenied"
            class="lai-form-message-error"
            role="alert"
          >
            {{ diagnosticsError }}
          </p>
          <pre
            v-else-if="detail.request_summary.sampled_messages"
            class="lai-sample"
          >{{ detail.request_summary.sampled_messages }}</pre>
          <button
            v-else-if="!diagnosticsRequested"
            type="button"
            class="lai-btn"
            @click="loadDiagnostics"
          >
            按需读取诊断样本（将记录审计）
          </button>
          <p
            v-else
            class="lai-related-meta"
          >
            样本加载中…
          </p>
        </template>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          统一时间线
        </h2>
        <p class="lai-card-hint">
          按服务端顺序渲染，时间相同按固定优先级排序；点击尝试节点查看明细。
        </p>
        <ul class="lai-timeline">
          <li
            v-for="item in timeline"
            :key="`${item.type}-${item.source_id}-${item.sequence}`"
            class="lai-timeline-item"
            :class="{
              'lai-timeline-highlight': item.attempt_id && highlightAttemptIds.has(item.attempt_id),
              'lai-timeline-failed': item.attempt_id && failedAttemptIds.has(item.attempt_id),
            }"
          >
            <button
              type="button"
              class="lai-timeline-node"
              @click="onTimelineClick(item)"
            >
              <span class="lai-timeline-time">{{ formatDateTime(item.occurred_at, store.timezone) }}</span>
              <span class="lai-timeline-type">{{ timelineTypeLabels[item.type] ?? item.type }}</span>
              <span
                v-if="item.reason_code"
                class="lai-related-meta"
              >{{ item.reason_code }}</span>
            </button>
          </li>
        </ul>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          Attempt 明细
        </h2>
        <table class="lai-table">
          <thead>
            <tr>
              <th>#</th>
              <th>类型</th>
              <th>状态</th>
              <th>Provider</th>
              <th>模型</th>
              <th>凭证</th>
              <th>耗时</th>
              <th>Token</th>
              <th>费用</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="attempt in detail.attempts"
              :key="attempt.id"
              :class="{ 'lai-tr-highlight': highlightAttemptIds.has(attempt.id) }"
            >
              <td>{{ attempt.sequence }}</td>
              <td>{{ attemptTypeLabels[attempt.attempt_type] ?? attempt.attempt_type }}</td>
              <td>{{ attempt.status === 'SUCCEEDED' ? '成功' : attempt.status === 'FAILED' ? '失败' : attempt.status === 'CANCELLED' ? '已取消' : attempt.status }}</td>
              <td>{{ attempt.provider_name_snapshot }}</td>
              <td>{{ attempt.provider_model_name_snapshot }}</td>
              <td>{{ attempt.credential_name_snapshot ?? '—' }}</td>
              <td>{{ formatDuration(attempt.total_ms) }}</td>
              <td>
                {{ attempt.total_tokens ?? '—' }}<span
                  v-if="attempt.usage_source"
                  class="lai-related-meta"
                >（{{ attempt.usage_source }}）</span>
              </td>
              <td>{{ attempt.cost !== null ? `${attempt.cost} ${attempt.currency ?? ''}` : '—' }}</td>
              <td>{{ attempt.error_code ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          Usage 与 Cost
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">运行总输入 Token</span>{{ detail.trace.input_tokens ?? '—' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">运行总输出 Token</span>{{ detail.trace.output_tokens ?? '—' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">运行总 Token</span>{{ detail.trace.total_tokens ?? '—' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">运行总费用</span>{{ totalCostText }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">响应用量来源</span>{{ detail.trace.usage_source ?? '—' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">响应 Token（最终成功 Attempt）</span>
            {{ detail.trace.response_total_tokens ?? '—' }}
          </div>
        </div>
        <p
          v-if="detail.trace.total_tokens !== null && detail.trace.response_total_tokens !== null && detail.trace.total_tokens > detail.trace.response_total_tokens"
          class="lai-card-hint"
        >
          运行总消耗大于响应用量：前序失败 Attempt 已计入总消耗（见上方 Attempt 明细）。
        </p>
      </div>

      <div
        v-if="detail.trace.status !== 'SUCCEEDED'"
        class="lai-card lai-error-card"
      >
        <h2 class="lai-card-title">
          最终错误
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">状态</span>
            <StatusText
              :value="detail.trace.status"
              :labels="traceStatusLabels"
            />
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">错误码</span>{{ detail.trace.error_code ?? '—' }}
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          保留信息
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">明细保留至</span>{{ formatDateTime(detail.detail_expires_at, store.timezone, '已过期') }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">诊断样本状态</span>{{ detail.request_summary.content_sample_status }}
          </div>
        </div>
      </div>
    </template>

    <Teleport to="body">
      <div
        v-if="selectedAttempt"
        class="lai-drawer-overlay"
        @click.self="openAttemptId = ''"
      >
        <aside
          class="lai-drawer"
          role="dialog"
          aria-label="Attempt 明细"
        >
          <div class="lai-drawer-header">
            <h2 class="lai-card-title">
              Attempt #{{ selectedAttempt.sequence }}
            </h2>
            <button
              type="button"
              class="lai-btn"
              @click="openAttemptId = ''"
            >
              关闭
            </button>
          </div>
          <div class="lai-drawer-body">
            <div class="lai-summary-grid">
              <div class="lai-summary-item">
                <span class="lai-summary-label">路径</span>
                {{ selectedAttempt.provider_name_snapshot }} /
                {{ selectedAttempt.provider_model_name_snapshot }} /
                {{ selectedAttempt.credential_name_snapshot ?? '—' }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">状态</span>{{ selectedAttempt.status }}
              </div>
            </div>
            <h3 class="lai-subsection-title">
              阶段耗时
            </h3>
            <div class="lai-summary-grid">
              <div class="lai-summary-item">
                <span class="lai-summary-label">派发</span>{{ formatDuration(selectedAttempt.dispatch_ms) }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">响应头</span>{{ formatDuration(selectedAttempt.response_header_ms) }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">首 Token</span>{{ formatDuration(selectedAttempt.first_token_ms) }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">总耗时</span>{{ formatDuration(selectedAttempt.total_ms) }}
              </div>
            </div>
            <h3 class="lai-subsection-title">
              外部响应
            </h3>
            <div class="lai-summary-grid">
              <div class="lai-summary-item">
                <span class="lai-summary-label">HTTP 状态</span>{{ selectedAttempt.http_status ?? '—' }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">endpoint</span>{{ selectedAttempt.endpoint_host ?? '—' }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">Provider Request ID</span>
                {{ selectedAttempt.provider_request_id ?? '—' }}
                <button
                  v-if="selectedAttempt.provider_request_id"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="copyText(selectedAttempt.provider_request_id!)"
                >
                  复制
                </button>
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">finish_reason</span>{{ selectedAttempt.finish_reason ?? '—' }}
              </div>
            </div>
            <h3 class="lai-subsection-title">
              Usage 与 Cost
            </h3>
            <div class="lai-summary-grid">
              <div class="lai-summary-item">
                <span class="lai-summary-label">来源</span>{{ selectedAttempt.usage_source ?? '—' }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">输入 / 输出 Token</span>
                {{ selectedAttempt.input_tokens ?? '—' }} / {{ selectedAttempt.output_tokens ?? '—' }}
              </div>
              <div class="lai-summary-item">
                <span class="lai-summary-label">费用</span>
                {{ selectedAttempt.cost !== null ? `${selectedAttempt.cost} ${selectedAttempt.currency ?? ''}` : '—' }}
              </div>
            </div>
            <h3 class="lai-subsection-title">
              容量预占
            </h3>
            <ul class="lai-related-list">
              <li
                v-for="reservation in attemptReservations(selectedAttempt.id)"
                :key="reservation.id"
              >
                {{ reservation.status }} · 预占 {{ reservation.reserved_tokens ?? '—' }} · 实际
                {{ reservation.actual_tokens ?? '—' }}
                <span
                  v-if="reservation.release_reason"
                  class="lai-related-meta"
                >
                  （{{ reservation.release_reason }}）
                </span>
              </li>
              <li
                v-if="attemptReservations(selectedAttempt.id).length === 0"
                class="lai-related-meta"
              >
                无预占记录
              </li>
            </ul>
            <template v-if="selectedAttempt.error_code">
              <h3 class="lai-subsection-title">
                错误
              </h3>
              <div class="lai-summary-grid">
                <div class="lai-summary-item">
                  <span class="lai-summary-label">错误码</span>{{ selectedAttempt.error_code }}
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">分类 / 阶段</span>
                  {{ selectedAttempt.error_category ?? '—' }} / {{ selectedAttempt.error_stage ?? '—' }}
                </div>
                <div class="lai-summary-item lai-summary-wide">
                  <span class="lai-summary-label">摘要</span>{{ selectedAttempt.error_summary ?? '—' }}
                </div>
              </div>
            </template>
          </div>
        </aside>
      </div>
    </Teleport>
  </section>
</template>
