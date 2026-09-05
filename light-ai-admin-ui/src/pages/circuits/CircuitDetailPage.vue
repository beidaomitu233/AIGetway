<script setup lang="ts">
// 熔断详情（FE-023/024，附录 4.3.3.2）：状态 5 秒刷新、生效阈值、状态事件、
// 失败样本、近期探测与人工信息；人工操作使用最新 state_version 提交。
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import CircuitActionDialog, { type CircuitActionType } from './CircuitActionDialog.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { circuitStateLabel, checkStatusLabel, openSourceLabel, triggerTypeLabel } from '@/app/display'
import {
  fetchCircuit,
  fetchCircuitEvents,
  openCircuit,
  probeCircuit,
  recoverCircuit,
  type CircuitEvent,
  type CircuitStateDetail,
} from '@/api/circuits'
import type { ProviderCheckRecord } from '@/api/credentials'
import { ApiError, isAbortError } from '@/api/errors'

const route = useRoute()
const circuitId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))

const store = useBootstrapStore()
const canOperate = store.can(Permission.circuitOperate)

const loading = ref(true)
const loadError = ref<unknown>(null)
const detail = shallowRef<CircuitStateDetail | null>(null)
const events = shallowRef<CircuitEvent[]>([])
const eventsStatus = ref<'loading' | 'ready' | 'error'>('loading')

let seq = 0
let controller: AbortController | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null

async function load(): Promise<void> {
  const current = ++seq
  controller?.abort()
  controller = new AbortController()
  try {
    const [detailData, eventsData] = await Promise.all([
      fetchCircuit(circuitId.value, controller.signal),
      fetchCircuitEvents(circuitId.value, { page: 1, page_size: 50 }, controller.signal),
    ])
    if (current !== seq) return
    detail.value = detailData
    events.value = eventsData.items
    loadError.value = null
    eventsStatus.value = 'ready'
  } catch (e) {
    if (current !== seq || isAbortError(e)) return
    loadError.value = e
  } finally {
    if (current === seq) loading.value = false
  }
}

onMounted(() => {
  void load()
  refreshTimer = setInterval(() => {
    if (document.visibilityState === 'visible') void load()
  }, 5000)
})
onBeforeUnmount(() => {
  if (refreshTimer !== null) clearInterval(refreshTimer)
  controller?.abort()
})

// —— 人工操作（FE-024）——
const actionOpen = ref(false)
const actionType = ref<CircuitActionType | null>(null)
const actionSubmitting = ref(false)
const actionError = shallowRef<unknown>(null)
const conflictVersion = ref<number | null>(null)
const actionMessage = ref('')
const probeResult = shallowRef<ProviderCheckRecord | null>(null)

const stateVersion = computed(() => detail.value?.state_version ?? 0)

function startAction(type: CircuitActionType): void {
  actionType.value = type
  actionError.value = null
  conflictVersion.value = null
  actionOpen.value = true
}

async function submitAction(command: {
  action: CircuitActionType
  reason: string
  open_seconds?: number | undefined
  state_version: number
}): Promise<void> {
  actionSubmitting.value = true
  actionError.value = null
  try {
    if (command.action === 'MANUAL_OPEN') {
      detail.value = await openCircuit(circuitId.value, {
        action: 'MANUAL_OPEN',
        reason: command.reason,
        open_seconds: command.open_seconds,
        state_version: command.state_version,
      })
      actionMessage.value = '已提交人工打开，状态已更新'
    } else if (command.action === 'MANUAL_RECOVER') {
      detail.value = await recoverCircuit(circuitId.value, {
        action: 'MANUAL_RECOVER',
        reason: command.reason,
        state_version: command.state_version,
      })
      actionMessage.value = '已提交人工恢复，状态已更新'
    } else {
      probeResult.value = await probeCircuit(circuitId.value, {
        action: 'PROBE_NOW',
        state_version: command.state_version,
      })
      actionMessage.value = '探测已执行，结果如下'
    }
    actionOpen.value = false
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'CIRCUIT_STATE_CONFLICT') {
      conflictVersion.value = e.serverVersion ?? null
    } else {
      actionError.value = e
    }
  } finally {
    actionSubmitting.value = false
  }
}

function rateText(value: string): string {
  const rate = Number(value)
  return Number.isFinite(rate) ? `${(rate * 100).toFixed(2)}%` : value
}

const thresholdRows = computed(() => {
  const snapshot = detail.value?.policy_snapshot
  if (!snapshot) return []
  return [
    { label: '策略 ID', value: snapshot.policy_id ?? '默认策略' },
    { label: '快照号', value: snapshot.snapshot_no == null ? '—' : `#${snapshot.snapshot_no}` },
    { label: '统计窗口', value: `${snapshot.circuit_window_seconds}s` },
    { label: '最小请求数', value: snapshot.circuit_min_requests },
    { label: '失败率阈值', value: rateText(snapshot.circuit_failure_rate) },
    { label: 'OPEN 时长', value: `${snapshot.circuit_open_seconds}s` },
    { label: '半开探测数', value: snapshot.circuit_half_open_probes },
    { label: '半开成功数', value: snapshot.circuit_half_open_successes },
  ]
})
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        熔断详情
      </h1>
      <div
        v-if="canOperate"
        class="lai-page-actions"
      >
        <button
          type="button"
          class="lai-btn"
          :disabled="detail?.state === 'OPEN'"
          @click="startAction('PROBE_NOW')"
        >
          立即探测
        </button>
        <button
          type="button"
          class="lai-btn"
          :disabled="detail?.state === 'CLOSED'"
          @click="startAction('MANUAL_RECOVER')"
        >
          人工恢复
        </button>
        <button
          type="button"
          class="lai-btn lai-btn-danger"
          @click="startAction('MANUAL_OPEN')"
        >
          人工打开
        </button>
      </div>
    </div>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
      @retry="load"
    />
    <template v-else-if="detail">
      <p
        v-if="actionMessage"
        class="lai-action-message"
      >
        {{ actionMessage }}
      </p>
      <p
        v-if="detail.pending_command"
        class="lai-pending-note"
        role="status"
      >
        存在待收敛命令（{{ detail.pending_command.action }}，{{ detail.pending_command.created_at }}），状态尚未确认应用。
      </p>

      <div class="lai-detail-grid">
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            当前状态
          </h2>
          <dl class="lai-dl">
            <dt>状态</dt>
            <dd>
              <span class="lai-state-text">{{ circuitStateLabel(detail.state) }}</span>
              <span class="lai-cell-mono lai-cell-sub">state_version {{ detail.state_version }}</span>
            </dd>
            <dt>Provider / 模型</dt>
            <dd>{{ detail.provider_name }} / {{ detail.provider_model_name }}</dd>
            <dt>Credential</dt>
            <dd class="lai-cell-mono">
              {{ detail.credential_name ? `${detail.credential_name}（${detail.credential_masked_value ?? ''}）` : '受限凭证' }}
            </dd>
            <dt>窗口样本</dt>
            <dd>{{ detail.sample_count }}（失败 {{ detail.failure_count }}，失败率 {{ rateText(detail.failure_rate) }}）</dd>
            <dt v-if="detail.state === 'HALF_OPEN'">
              探测名额
            </dt>
            <dd v-if="detail.state === 'HALF_OPEN'">
              进行中 {{ detail.half_open_in_flight }}（成功 {{ detail.half_open_success_count }}）
            </dd>
            <dt>打开时间</dt>
            <dd>{{ detail.opened_at ?? '—' }}</dd>
            <dt>下次探测</dt>
            <dd>{{ detail.next_probe_at ?? '—' }}</dd>
          </dl>
        </div>

        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            生效阈值
          </h2>
          <dl class="lai-dl">
            <template
              v-for="row in thresholdRows"
              :key="row.label"
            >
              <dt>{{ row.label }}</dt>
              <dd class="lai-cell-mono">
                {{ row.value }}
              </dd>
            </template>
          </dl>
          <template v-if="detail.open_source">
            <h3 class="lai-sub-title">
              人工信息
            </h3>
            <dl class="lai-dl">
              <dt>打开来源</dt>
              <dd>{{ openSourceLabel(detail.open_source) }}</dd>
              <dt>操作原因</dt>
              <dd>{{ detail.manual_reason ?? '—' }}</dd>
              <dt>预计恢复</dt>
              <dd>{{ detail.manual_open_until ?? '—' }}</dd>
              <dt>操作人</dt>
              <dd>{{ detail.operator ?? '—' }}</dd>
            </dl>
          </template>
        </div>
      </div>

      <div class="lai-detail-card">
        <h2 class="lai-section-title">
          状态事件（最近 {{ events.length }} 条）
        </h2>
        <PageState
          v-if="eventsStatus === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="events.length === 0"
          status="empty"
          message="暂无状态事件"
        />
        <table
          v-else
          class="lai-table"
        >
          <thead>
            <tr>
              <th>发生时间</th>
              <th>变更</th>
              <th>触发来源</th>
              <th>错误码</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in events"
              :key="item.id"
            >
              <td>{{ item.occurred_at }}</td>
              <td class="lai-cell-mono">
                {{ circuitStateLabel(item.from_state) }} → {{ circuitStateLabel(item.to_state) }}
              </td>
              <td>{{ triggerTypeLabel(item.trigger_type) }}</td>
              <td class="lai-cell-mono">
                {{ item.error_code ?? '—' }}
              </td>
              <td>{{ item.reason ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="lai-detail-grid">
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            当前窗口失败样本
          </h2>
          <PageState
            v-if="detail.window_samples.length === 0"
            status="empty"
            message="当前窗口无失败样本"
          />
          <table
            v-else
            class="lai-table"
          >
            <thead>
              <tr>
                <th>trace_id</th>
                <th>error_code</th>
                <th>耗时</th>
                <th>结束时间</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in detail.window_samples"
                :key="item.attempt_id"
              >
                <td class="lai-cell-mono">
                  <RouterLink
                    :to="`/ui/traces/${item.trace_id}`"
                    class="lai-link"
                  >
                    {{ item.trace_id }}
                  </RouterLink>
                </td>
                <td class="lai-cell-mono">
                  {{ item.error_code ?? '—' }}
                </td>
                <td>{{ item.total_ms }} ms</td>
                <td>{{ item.ended_at }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            近期探测
          </h2>
          <PageState
            v-if="detail.recent_probes.length === 0 && !probeResult"
            status="empty"
            message="暂无探测记录"
          />
          <table
            v-if="probeResult"
            class="lai-table"
          >
            <thead>
              <tr><th>最近人工探测</th><th>结果</th><th>耗时</th><th>错误</th></tr>
            </thead>
            <tbody>
              <tr>
                <td>{{ probeResult.started_at }}</td>
                <td>{{ checkStatusLabel(probeResult.status) }}</td>
                <td>{{ probeResult.total_ms }} ms</td>
                <td class="lai-cell-mono">
                  {{ probeResult.error_code ?? probeResult.error_summary ?? '—' }}
                </td>
              </tr>
            </tbody>
          </table>
          <table
            v-else-if="detail.recent_probes.length > 0"
            class="lai-table"
          >
            <thead>
              <tr><th>时间</th><th>类型</th><th>结果</th><th>耗时</th><th>错误</th></tr>
            </thead>
            <tbody>
              <tr
                v-for="item in detail.recent_probes"
                :key="item.id"
              >
                <td>{{ item.started_at }}</td>
                <td>{{ item.kind === 'MANUAL_PROBE' ? '人工探测' : '业务探测' }}</td>
                <td>{{ checkStatusLabel(item.status) }}</td>
                <td>{{ item.total_ms }} ms</td>
                <td class="lai-cell-mono">
                  {{ item.error_code ?? '—' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <CircuitActionDialog
      v-model:open="actionOpen"
      :action="actionType"
      :circuit="detail"
      :state-version="stateVersion"
      :submitting="actionSubmitting"
      :error="actionError"
      :conflict-version="conflictVersion"
      @confirm="submitAction"
    />
  </section>
</template>

<style scoped>
.lai-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lai-page-actions {
  display: flex;
  gap: 8px;
}
.lai-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 12px;
  margin: 12px 0;
}
.lai-detail-card {
  border: 1px solid #d8dee4;
  border-radius: 6px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
.lai-section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 8px;
}
.lai-sub-title {
  font-size: 13px;
  font-weight: 600;
  margin: 12px 0 4px;
}
.lai-dl {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 4px 12px;
  font-size: 13px;
  margin: 0;
}
.lai-dl dt {
  color: #57606a;
}
.lai-dl dd {
  margin: 0;
}
.lai-state-text {
  font-weight: 600;
}
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-cell-sub {
  display: block;
  font-size: 12px;
  color: #57606a;
}
.lai-link {
  color: #0969da;
}
.lai-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.lai-table th,
.lai-table td {
  text-align: left;
  padding: 6px 10px;
  border-bottom: 1px solid #d8dee4;
  white-space: nowrap;
}
.lai-table th {
  color: #57606a;
  background: #f6f8fa;
}
.lai-action-message {
  padding: 8px 12px;
  background: #f0fff4;
  border: 1px solid #1a7f37;
  border-radius: 6px;
  font-size: 13px;
  color: #1a7f37;
}
.lai-pending-note {
  padding: 8px 12px;
  background: #fff8c5;
  border: 1px solid #9a6700;
  border-radius: 6px;
  font-size: 13px;
  color: #9a6700;
}
</style>
