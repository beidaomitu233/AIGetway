<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import PageState from '@/components/PageState.vue'
import StatusText from '@/components/StatusText.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { formatDateTime } from '@/app/display'
import {
  type ConfigDraftState,
  type ConfigValidationResult,
  type PublishRecordDetail,
  type PublishRecordListItem,
  fetchDraftState,
  fetchPublishRecord,
  fetchPublishRecords,
  publishDraft,
  validateDraft,
} from '@/api/config'
import { ApiError } from '@/api/errors'

const PROGRESS_REFRESH_MS = 3000

const store = useBootstrapStore()

const canManage = computed(() => store.can(Permission.publishManage))

type Step = 'draft' | 'validate' | 'confirm' | 'progress'

const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const draftState = ref<ConfigDraftState | null>(null)
const step = ref<Step>('draft')

// —— 校验（FE-039）——
const validating = ref(false)
const validation = ref<ConfigValidationResult | null>(null)
const validateError = ref('')

async function runValidate(): Promise<void> {
  if (validating.value) return
  validating.value = true
  validateError.value = ''
  try {
    const result = await validateDraft({ draft_revision: draftState.value!.draft_revision })
    validation.value = result
    step.value = 'confirm'
    if (result.status !== 'PASSED') {
      step.value = 'validate'
    }
  } catch (e) {
    if (e instanceof ApiError) {
      validateError.value = `${e.message}（${e.code}）`
      if (e.code === 'CONFIG_DRAFT_CHANGED') {
        draftState.value = await fetchDraftState()
      }
    } else {
      validateError.value = '校验失败，请稍后重试'
    }
  } finally {
    validating.value = false
  }
}

const errorIssues = computed(() =>
  (validation.value?.issues ?? []).filter((issue) => issue.severity === 'ERROR'),
)
const warningIssues = computed(() =>
  (validation.value?.issues ?? []).filter((issue) => issue.severity === 'WARNING'),
)

/** 校验结果对当前草稿修订是否仍然有效（附录 4.5.2.1）。 */
const validationStale = computed(() => {
  if (!validation.value || !draftState.value) return false
  return validation.value.draft_revision !== draftState.value.draft_revision
})

// —— 确认与提交（FE-040）——
const acknowledged = ref<Set<string>>(new Set())
const publishNote = ref('')
const publishing = ref(false)
const publishError = ref('')

const allWarningsAcknowledged = computed(
  () => warningIssues.value.every((issue) => acknowledged.value.has(issue.code)),
)

const canSubmit = computed(
  () =>
    canManage.value &&
    validation.value !== null &&
    validation.value.status === 'PASSED' &&
    !validationStale.value &&
    allWarningsAcknowledged.value &&
    publishNote.value.trim() !== '' &&
    !publishing.value,
)

async function submitPublish(): Promise<void> {
  if (!canSubmit.value) return
  publishing.value = true
  publishError.value = ''
  try {
    const record = await publishDraft({
      validation_id: validation.value!.validation_id,
      draft_revision: validation.value!.draft_revision,
      acknowledged_warning_ids: warningIssues.value.map((issue) => issue.code),
      publish_note: publishNote.value.trim(),
    })
    publishRecord.value = record
    step.value = 'progress'
    startProgressPolling()
    void store.refreshDraftSummary()
  } catch (e) {
    if (e instanceof ApiError) {
      publishError.value = `${e.message}（${e.code}）`
      draftState.value = await fetchDraftState().catch(() => draftState.value)
    } else {
      // 网络超时不等于提交失败：提示在发布历史核对，避免重复提交
      publishError.value = '提交结果未知（网络超时），请在发布历史中核对后再操作'
      await loadHistory()
    }
  } finally {
    publishing.value = false
  }
}

// —— 实例进度（FE-041）——
const publishRecord = ref<PublishRecordDetail | null>(null)
let progressTimer: ReturnType<typeof setInterval> | null = null

const terminalStatuses = ['SUCCEEDED', 'FAILED']

function isTerminal(status: string): boolean {
  // PARTIAL_FAILED 保持轮询：实例可能后台收敛为 SUCCEEDED（附录 4.5.2.4）
  return terminalStatuses.includes(status)
}

function startProgressPolling(): void {
  stopProgressPolling()
  progressTimer = setInterval(async () => {
    if (document.visibilityState !== 'visible') return
    if (!publishRecord.value || isTerminal(publishRecord.value.status)) {
      stopProgressPolling()
      return
    }
    try {
      publishRecord.value = await fetchPublishRecord(publishRecord.value.id)
      void store.refreshDraftSummary()
    } catch {
      // 单次轮询失败保留当前进度
    }
  }, PROGRESS_REFRESH_MS)
}

function stopProgressPolling(): void {
  if (progressTimer !== null) {
    clearInterval(progressTimer)
    progressTimer = null
  }
}
onUnmounted(stopProgressPolling)

const instanceStatusLabels: Record<string, string> = {
  PENDING: '等待中',
  PREPARING: '准备中',
  READY: '已就绪',
  ACTIVATING: '激活中',
  LOADED: '已加载',
  FAILED: '失败',
  TIMED_OUT: '超时',
}

const publishStatusLabels: Record<string, string> = {
  PREPARING: '准备中',
  ACTIVATING: '激活中',
  SUCCEEDED: '成功',
  PARTIAL_FAILED: '部分失败（等待收敛）',
  FAILED: '失败',
}

async function load(): Promise<void> {
  state.value = 'loading'
  loadError.value = null
  try {
    draftState.value = await fetchDraftState()
    state.value = 'ready'
  } catch (e) {
    loadError.value = e
    state.value = 'error'
  }
}
onMounted(load)

const validationExpired = computed(() => {
  if (!validation.value) return false
  return new Date(validation.value.expires_at).getTime() < Date.now()
})

const canEnterConfirm = computed(
  () =>
    validation.value !== null &&
    validation.value.status === 'PASSED' &&
    errorIssues.value.length === 0 &&
    !validationStale.value &&
    !validationExpired.value,
)

// —— 发布历史入口（FE-042）——
const recentRecords = ref<PublishRecordListItem[]>([])
const historyState = ref<'loading' | 'ready' | 'error'>('loading')
const historyError = ref<unknown>(null)

async function loadHistory(): Promise<void> {
  historyState.value = 'loading'
  try {
    const result = await fetchPublishRecords(
      { page: 1, page_size: 10, sort: '-published_at' },
      new AbortController().signal,
    )
    recentRecords.value = result.items
    historyState.value = 'ready'
  } catch (e) {
    historyError.value = e
    historyState.value = 'error'
  }
}
onMounted(loadHistory)

const copyState = ref('')
async function copyChecksum(): Promise<void> {
  if (!validation.value) return
  try {
    await window.navigator.clipboard.writeText(validation.value.content_checksum)
    copyState.value = '已复制'
  } catch {
    copyState.value = '复制失败'
  }
  setTimeout(() => {
    copyState.value = ''
  }, 1500)
}

function severityClass(severity: string): string {
  return severity === 'ERROR' ? 'lai-check-fail' : 'lai-related-meta'
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        配置发布
      </h1>
      <div class="lai-row-actions">
        <RouterLink
          :to="{ name: 'drafts' }"
          class="lai-btn"
        >
          返回待发布变更
        </RouterLink>
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
    <template v-else-if="draftState">
      <p
        v-if="draftState.change_count === 0"
        class="lai-card-hint"
        role="status"
      >
        当前没有待发布变更，可查看下方发布历史。
      </p>
      <p
        v-else-if="draftState.status === 'PUBLISHING'"
        class="lai-card-hint"
        role="status"
      >
        发布进行中，草稿已锁定。
      </p>

      <div
        v-if="step !== 'progress'"
        class="lai-card"
      >
        <h2 class="lai-card-title">
          第 1 步 · 校验
        </h2>
        <p class="lai-card-hint">
          校验锁定当前草稿修订号 #{{ draftState.draft_revision }}；草稿随后发生变化时校验结果立即失效。
        </p>
        <button
          v-if="canManage && draftState.change_count > 0 && draftState.status === 'EDITABLE'"
          type="button"
          class="lai-btn lai-btn-primary"
          :disabled="validating"
          @click="runValidate"
        >
          {{ validating ? '校验中…' : '开始校验' }}
        </button>
        <p
          v-if="validateError"
          class="lai-form-message-error"
          role="alert"
        >
          {{ validateError }}
        </p>

        <template v-if="validation">
          <p
            v-if="validationStale"
            class="lai-form-message-error"
            role="alert"
          >
            草稿已变化（当前修订 #{{ draftState.draft_revision }}，校验基于
            #{{ validation.draft_revision }}），请重新校验。
          </p>
          <p
            v-else-if="validationExpired"
            class="lai-form-message-error"
            role="alert"
          >
            校验已过期，请重新校验。
          </p>
          <div class="lai-summary-grid">
            <div class="lai-summary-item">
              <span class="lai-summary-label">结果</span>
              <StatusText
                :value="validation.status"
                :labels="{ PASSED: '通过', FAILED: '未通过' }"
              />
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">目标快照</span>#{{ validation.target_snapshot_no }}
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">错误 / 警告</span>
              {{ errorIssues.length }} / {{ warningIssues.length }}
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">内容摘要</span>
              <span class="lai-mono">{{ validation.content_checksum.slice(0, 16) }}…</span>
              <button
                type="button"
                class="lai-btn lai-btn-text"
                @click="copyChecksum"
              >
                {{ copyState || '复制' }}
              </button>
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">有效期至</span>{{ formatDateTime(validation.expires_at, store.timezone) }}
            </div>
            <div class="lai-summary-item lai-summary-wide">
              <span class="lai-summary-label">影响 Alias</span>
              {{ validation.affected_alias_ids.length > 0 ? validation.affected_alias_ids.join('、') : '无' }}
            </div>
          </div>
          <table
            v-if="validation.issues.length > 0"
            class="lai-table"
          >
            <thead>
              <tr>
                <th>级别</th>
                <th>对象</th>
                <th>字段</th>
                <th>说明</th>
                <th>建议</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(issue, index) in validation.issues"
                :key="index"
              >
                <td :class="severityClass(issue.severity)">
                  {{ issue.severity }}
                </td>
                <td>
                  {{ issue.entity_name }}
                  <span class="lai-related-meta">{{ issue.code }}</span>
                </td>
                <td>{{ issue.field_path ?? '—' }}</td>
                <td>{{ issue.message }}</td>
                <td>{{ issue.suggestion ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>

      <div
        v-if="step === 'confirm' && canEnterConfirm"
        class="lai-card"
      >
        <h2 class="lai-card-title">
          第 2 步 · 确认警告并提交
        </h2>
        <p class="lai-card-hint">
          逐条确认全部 WARNING 后填写发布说明提交；提交成功后页面进入只读进度。
        </p>
        <ul class="lai-related-list">
          <li
            v-for="issue in warningIssues"
            :key="issue.code"
            class="lai-warning-row"
          >
            <label class="lai-warning-label">
              <input
                v-model="acknowledged"
                type="checkbox"
                class="lai-checkbox"
                :value="issue.code"
              >
              <span>
                {{ issue.code }} · {{ issue.entity_name }} · {{ issue.message }}
              </span>
            </label>
          </li>
          <li
            v-if="warningIssues.length === 0"
            class="lai-related-meta"
          >
            无警告
          </li>
        </ul>
        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="publish-note"
          >发布说明</label>
          <input
            id="publish-note"
            v-model="publishNote"
            class="lai-input"
            type="text"
            maxlength="500"
          >
        </div>
        <p
          v-if="publishError"
          class="lai-form-message-error"
          role="alert"
        >
          {{ publishError }}
        </p>
        <button
          type="button"
          class="lai-btn lai-btn-primary"
          :disabled="!canSubmit"
          @click="submitPublish"
        >
          {{ publishing ? '提交中…' : '提交发布' }}
        </button>
      </div>

      <div
        v-if="step === 'progress' && publishRecord"
        class="lai-card"
      >
        <h2 class="lai-card-title">
          第 3 步 · 实例准备与激活
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">发布状态</span>
            <StatusText
              :value="publishRecord.status"
              :labels="publishStatusLabels"
            />
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">目标快照</span>#{{ publishRecord.target_snapshot_no }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">发布时间</span>{{ formatDateTime(publishRecord.published_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">完成时间</span>{{ formatDateTime(publishRecord.completed_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">收敛时间</span>{{ formatDateTime(publishRecord.converged_at, store.timezone) }}
          </div>
        </div>
        <table class="lai-table">
          <thead>
            <tr>
              <th>实例</th>
              <th>版本</th>
              <th>状态</th>
              <th>快照</th>
              <th>耗时</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="instance in publishRecord.instance_results"
              :key="instance.instance_id"
            >
              <td>
                {{ instance.instance_id }}
                <span class="lai-related-meta">{{ instance.runtime_mode }}</span>
              </td>
              <td>{{ instance.runtime_version }}</td>
              <td>
                <StatusText
                  :value="instance.status"
                  :labels="instanceStatusLabels"
                />
              </td>
              <td>#{{ instance.from_snapshot_no }} → #{{ instance.target_snapshot_no }}</td>
              <td>{{ instance.load_duration_ms === null ? '—' : `${instance.load_duration_ms} ms` }}</td>
              <td>
                <template v-if="instance.error_code">
                  {{ instance.error_code }} · {{ instance.error_summary ?? '' }}
                </template>
                <template v-else>
                  —
                </template>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="lai-card-hint">
          关闭页面不会取消发布；实例后台收敛后发布结果会更新。
        </p>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          发布历史
        </h2>
        <PageState
          v-if="historyState === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="historyState === 'error'"
          status="error"
          :error="historyError"
          @retry="loadHistory"
        />
        <template v-else>
          <table class="lai-table">
            <thead>
              <tr>
                <th>快照</th>
                <th>状态</th>
                <th>发布人</th>
                <th>发布时间</th>
                <th>耗时</th>
                <th>说明</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="record in recentRecords"
                :key="record.id"
              >
                <td>#{{ record.snapshot_no }}</td>
                <td>
                  <StatusText
                    :value="record.status"
                    :labels="publishStatusLabels"
                  />
                </td>
                <td>{{ record.published_by_name }}</td>
                <td>{{ formatDateTime(record.published_at, store.timezone) }}</td>
                <td>{{ record.duration_ms === null ? '—' : `${record.duration_ms} ms` }}</td>
                <td>{{ record.publish_note || '—' }}</td>
                <td>
                  <RouterLink
                    :to="{ name: 'publish-record', params: { id: record.id } }"
                    class="lai-btn lai-btn-text"
                  >
                    详情
                  </RouterLink>
                </td>
              </tr>
              <tr v-if="recentRecords.length === 0">
                <td
                  colspan="7"
                  class="lai-table-empty"
                >
                  暂无发布记录
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>
    </template>
  </section>
</template>
