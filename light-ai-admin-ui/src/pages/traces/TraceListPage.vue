<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import PageState from '@/components/PageState.vue'
import DataTable, { type TableColumn } from '@/components/DataTable.vue'
import ListPager from '@/components/ListPager.vue'
import StatusText from '@/components/StatusText.vue'
import AppMultiSelect from '@/components/AppMultiSelect.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import {
  formatDateTime,
  formatDuration,
  sourceModeLabels,
  traceStatusLabels,
} from '@/app/display'
import { Permission } from '@/app/permissions'
import {
  type TraceListItem,
  type TraceListQueryParams,
  exportTraces,
  fetchTraces,
} from '@/api/traces'
import { isAbortError } from '@/api/errors'

const REFRESH_MS = 10000

const store = useBootstrapStore()

const canExport = computed(
  () => store.can(Permission.traceExport) || store.can(Permission.usageExport),
)
// 敏感筛选仅系统管理员与运维人员可见（附录 4.4.1.1）。
const canUseSensitiveFilters = computed(
  () => store.can(Permission.traceDiagnostics) || store.can(Permission.auditExport),
)

const statusOptions = Object.entries(traceStatusLabels).map(([value, label]) => ({ value, label }))
const sourceModeOptions = Object.entries(sourceModeLabels).map(([value, label]) => ({
  value,
  label,
}))
const usageSourceOptions = ['ACTUAL', 'ESTIMATED', 'MIXED'].map((value) => ({ value, label: value }))
const sortOptions = [
  { value: 'started_at', label: '开始时间' },
  { value: 'total_ms', label: '总耗时' },
  { value: 'total_tokens', label: '总 Token' },
  { value: 'total_cost', label: '总费用' },
]

const list = useListQuery<Record<string, FilterValue>, TraceListItem>({
  fields: {
    trace_id: { default: '', url: true },
    application: { default: '', url: true },
    alias_id: { default: '', url: true },
    provider_id: { default: '', url: true },
    provider_model_id: { default: '', url: true },
    status: { default: [], url: true },
    error_code: { default: '', url: true },
    source_mode: { default: [], url: true },
    requested_stream: { default: '', url: true },
    usage_source: { default: '', url: true },
    has_retry: { default: '', url: true },
    min_total_ms: { default: '', url: true },
    max_total_ms: { default: '', url: true },
    anomalous_running: { default: '', url: true },
    client_ip: { default: '', url: false },
    start_at: { default: '', url: true },
    end_at: { default: '', url: true },
  },
  defaultSort: '-started_at',
  defaultPageSize: 20,
  fetcher: (params, signal) => fetchTraces(params as TraceListQueryParams, signal),
})

/** trace_id 精确模式：停用其他业务筛选，忽略分页（附录 4.4.1.2）。 */
const preciseMode = computed(() => (list.state.trace_id as string) !== '')

const basicStatusFilter = computed<string[]>({
  get: () => (list.state.status as string[]) || [],
  set: (value) => list.applyFilters({ status: value }),
})

function hasRunning(rows: TraceListItem[]): boolean {
  return rows.some((row) => row.status === 'RUNNING' || row.status === 'QUEUED')
}

let refreshTimer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  refreshTimer = setInterval(() => {
    if (document.visibilityState !== 'visible') return
    if (hasRunning(list.items.value)) list.refresh()
  }, REFRESH_MS)
})
onUnmounted(() => {
  if (refreshTimer !== null) clearInterval(refreshTimer)
})

const columns: TableColumn[] = [
  { key: 'started_at', label: '开始时间', sortValue: 'started_at' },
  { key: 'trace_id', label: 'Trace ID' },
  { key: 'source_mode', label: '来源' },
  { key: 'application', label: '应用' },
  { key: 'alias', label: 'Alias' },
  { key: 'final_provider_name', label: 'Provider' },
  { key: 'final_provider_model_name', label: '模型' },
  { key: 'requested_stream', label: '方式' },
  { key: 'status', label: '状态' },
  { key: 'attempt_count', label: '尝试' },
  { key: 'total_ms', label: '耗时', sortValue: 'total_ms' },
  { key: 'total_tokens', label: 'Token', sortValue: 'total_tokens' },
  { key: 'total_cost', label: '费用', sortValue: 'total_cost' },
  { key: 'error_code', label: '错误码' },
  { key: 'actions', label: '操作' },
]

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

const exportState = ref<'idle' | 'running' | 'error'>('idle')
const exportError = ref('')
let exportController: AbortController | null = null

async function onExport(): Promise<void> {
  if (exportState.value === 'running') return
  exportController?.abort()
  exportController = new AbortController()
  exportState.value = 'running'
  exportError.value = ''
  try {
    await exportTraces(listQueryForExport(), exportController.signal)
    exportState.value = 'idle'
  } catch (e) {
    if (isAbortError(e)) {
      exportState.value = 'idle'
      return
    }
    exportError.value = e instanceof Error ? `${e.message}（${(e as { code?: string }).code ?? ''}）` : '导出失败'
    exportState.value = 'error'
  }
}

function listQueryForExport(): TraceListQueryParams {
  const state = list.state as Record<string, FilterValue>
  const query: TraceListQueryParams = {
    sort: list.sort.value,
  }
  for (const key of [
    'trace_id',
    'start_at',
    'end_at',
    'requested_stream',
    'usage_source',
    'has_retry',
    'min_total_ms',
    'max_total_ms',
    'anomalous_running',
    'client_ip',
  ]) {
    if (state[key] !== '' && state[key] !== null && state[key] !== undefined) {
      ;(query as Record<string, unknown>)[key] = state[key]
    }
  }
  for (const key of [
    'application',
    'alias_id',
    'provider_id',
    'provider_model_id',
    'status',
    'error_code',
    'source_mode',
  ]) {
    const value = state[key]
    if (Array.isArray(value) && value.length > 0) {
      ;(query as Record<string, unknown>)[key] = value
    } else if (typeof value === 'string' && value !== '') {
      ;(query as Record<string, unknown>)[key] = [value]
    }
  }
  return query
}

function cancelExport(): void {
  exportController?.abort()
}

function onModelError(row: TraceListItem): void {
  list.applyFilters({ error_code: row.error_code ?? '' })
}

function filterByError(code: string): void {
  list.applyFilters({ error_code: code })
}

defineExpose({ filterByError, list })
</script>


<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        Trace
      </h1>
      <div class="lai-row-actions">
        <span
          v-if="copyState"
          class="lai-related-meta"
        >{{ copyState }}</span>
        <button
          v-if="canExport"
          type="button"
          class="lai-btn"
          :disabled="exportState === 'running'"
          @click="onExport"
        >
          {{ exportState === 'running' ? '导出中…' : '导出 CSV' }}
        </button>
        <button
          v-if="exportState === 'running'"
          type="button"
          class="lai-btn"
          @click="cancelExport"
        >
          取消导出
        </button>
      </div>
    </div>

    <p
      v-if="exportError"
      class="lai-form-message-error"
      role="alert"
    >
      {{ exportError }}
    </p>

    <div class="lai-filter-bar">
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="Trace ID 精确查询（1—128 字符）"
        maxlength="128"
        :value="list.state.trace_id as string"
        @change="list.applyFilters({ trace_id: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="应用"
        :disabled="preciseMode"
        :value="list.state.application as string"
        @change="list.applyFilters({ application: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="Alias ID"
        :disabled="preciseMode"
        :value="list.state.alias_id as string"
        @change="list.applyFilters({ alias_id: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="Provider ID"
        :disabled="preciseMode"
        :value="list.state.provider_id as string"
        @change="list.applyFilters({ provider_id: ($event.target as HTMLInputElement).value.trim() })"
      >
      <AppMultiSelect
        :model-value="basicStatusFilter"
        :options="statusOptions"
        placeholder="状态"
        :disabled="preciseMode"
        @update:model-value="list.applyFilters({ status: $event })"
      />
      <select
        class="lai-select"
        :value="list.state.requested_stream as string"
        aria-label="调用方式"
        :disabled="preciseMode"
        @change="list.applyFilters({ requested_stream: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">
          全部方式
        </option>
        <option value="true">
          流式
        </option>
        <option value="false">
          同步
        </option>
      </select>
      <select
        class="lai-select"
        :value="list.sort.value"
        aria-label="排序"
        :disabled="preciseMode"
        @change="list.applySort(($event.target as HTMLSelectElement).value)"
      >
        <option
          v-for="option in sortOptions"
          :key="option.value"
          :value="`-${option.value}`"
        >
          {{ option.label }}（倒序）
        </option>
        <option
          v-for="option in sortOptions"
          :key="`asc-${option.value}`"
          :value="option.value"
        >
          {{ option.label }}（正序）
        </option>
      </select>
    </div>

    <details
      v-if="!preciseMode"
      class="lai-advanced-filters"
    >
      <summary>高级筛选</summary>
      <div class="lai-filter-bar">
        <AppMultiSelect
          :model-value="(list.state.source_mode as string[]) || []"
          :options="sourceModeOptions"
          placeholder="来源模式"
          @update:model-value="list.applyFilters({ source_mode: $event })"
        />
        <input
          class="lai-input lai-filter-keyword"
          type="text"
          placeholder="错误码"
          :value="list.state.error_code as string"
          @change="list.applyFilters({ error_code: ($event.target as HTMLInputElement).value.trim() })"
        >
        <select
          class="lai-select"
          :value="list.state.usage_source as string"
          aria-label="Usage 来源"
          @change="list.applyFilters({ usage_source: ($event.target as HTMLSelectElement).value })"
        >
          <option value="">
            全部来源
          </option>
          <option
            v-for="option in usageSourceOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
        <select
          class="lai-select"
          :value="list.state.has_retry as string"
          aria-label="存在重试"
          @change="list.applyFilters({ has_retry: ($event.target as HTMLSelectElement).value })"
        >
          <option value="">
            重试不限
          </option>
          <option value="true">
            有重试
          </option>
          <option value="false">
            无重试
          </option>
        </select>
        <input
          class="lai-input lai-filter-number"
          type="number"
          min="0"
          max="600000"
          placeholder="最小耗时 ms"
          :value="list.state.min_total_ms as string"
          @change="list.applyFilters({ min_total_ms: ($event.target as HTMLInputElement).value })"
        >
        <input
          class="lai-input lai-filter-number"
          type="number"
          min="0"
          max="600000"
          placeholder="最大耗时 ms"
          :value="list.state.max_total_ms as string"
          @change="list.applyFilters({ max_total_ms: ($event.target as HTMLInputElement).value })"
        >
        <select
          class="lai-select"
          :value="list.state.anomalous_running as string"
          aria-label="运行异常"
          @change="list.applyFilters({ anomalous_running: ($event.target as HTMLSelectElement).value })"
        >
          <option value="">
            异常不限
          </option>
          <option value="true">
            仅运行异常
          </option>
        </select>
        <input
          v-if="canUseSensitiveFilters"
          class="lai-input lai-filter-keyword"
          type="text"
          placeholder="来源 IP（敏感，仅本页生效）"
          :value="list.state.client_ip as string"
          @change="list.applyFilters({ client_ip: ($event.target as HTMLInputElement).value.trim() })"
        >
      </div>
    </details>

    <p
      v-if="preciseMode"
      class="lai-card-hint"
    >
      精确查询模式：已停用其他筛选与分页，结果最多一条。
    </p>

    <PageState
      v-if="list.status.value === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="list.status.value === 'error'"
      status="error"
      :error="list.error.value"
      @retry="list.refresh()"
    />
    <template v-else>
      <DataTable
        :columns="columns"
        :rows="list.items.value"
        :row-key="(row: TraceListItem) => row.trace_id"
        :sort="list.sort.value"
        :loading="list.refreshing.value"
        @sort-change="list.applySort"
      >
        <template #started_at="{ row }">
          {{ formatDateTime(row.started_at, store.timezone) }}
        </template>
        <template #trace_id="{ row }">
          <RouterLink
            :to="{ name: 'trace-detail', params: { traceId: row.trace_id } }"
            class="lai-link lai-mono"
          >
            {{ row.trace_id.slice(0, 18) }}{{ row.trace_id.length > 18 ? '…' : '' }}
          </RouterLink>
          <button
            type="button"
            class="lai-btn lai-btn-text"
            @click="copyText(row.trace_id)"
          >
            复制
          </button>
        </template>
        <template #source_mode="{ row }">
          {{ sourceModeLabels[row.source_mode] ?? row.source_mode }}
          <span
            v-if="row.access_credential_name"
            class="lai-related-meta"
          >
            （{{ row.access_credential_name }}）
          </span>
        </template>
        <template #application="{ row }">
          {{ row.application }}
          <div
            v-if="row.project || row.tenant"
            class="lai-related-meta"
          >
            {{ [row.project, row.tenant].filter(Boolean).join(' / ') }}
          </div>
        </template>
        <template #alias="{ row }">
          <RouterLink
            v-if="row.alias_id"
            :to="{ name: 'alias-detail', params: { id: row.alias_id } }"
            class="lai-link"
          >
            {{ row.alias }}
          </RouterLink>
          <template v-else>
            {{ row.alias }}
          </template>
        </template>
        <template #final_provider_model_name="{ row }">
          <RouterLink
            v-if="row.final_provider_model_id"
            :to="{ name: 'model-detail', params: { id: row.final_provider_model_id } }"
            class="lai-link"
          >
            {{ row.final_provider_model_name }}
          </RouterLink>
          <template v-else>
            —
          </template>
        </template>
        <template #requested_stream="{ row }">
          {{ row.requested_stream ? '流式' : '同步' }}
        </template>
        <template #status="{ row }">
          <StatusText
            :value="row.status"
            :labels="traceStatusLabels"
          />
          <span
            v-if="row.anomalous_running"
            class="lai-related-meta lai-check-fail"
          >运行异常</span>
        </template>
        <template #attempt_count="{ row }">
          {{ row.attempt_count }}
          <span
            v-if="row.attempt_count > 1"
            class="lai-related-meta"
          >
            （重试 {{ row.retry_count }} / 切换 {{ row.credential_failover_count }} /
            候选 {{ row.fallback_count }}）
          </span>
        </template>
        <template #total_ms="{ row }">
          <template v-if="row.status === 'RUNNING' || row.status === 'QUEUED'">
            <span class="lai-related-meta">运行中</span>
          </template>
          <template v-else>
            {{ formatDuration(row.total_ms) }}
          </template>
        </template>
        <template #total_tokens="{ row }">
          {{ row.total_tokens ?? '—' }}
          <span
            v-if="row.usage_source"
            class="lai-related-meta"
          >{{ row.usage_source }}</span>
        </template>
        <template #total_cost="{ row }">
          <template v-if="row.total_cost !== null">
            {{ row.total_cost }} {{ row.currency }}
          </template>
          <template v-else>
            —
          </template>
        </template>
        <template #error_code="{ row }">
          <button
            v-if="row.error_code"
            type="button"
            class="lai-btn lai-btn-text"
            @click="onModelError(row)"
          >
            {{ row.error_code }}
          </button>
          <template v-else>
            —
          </template>
        </template>
        <template #actions="{ row }">
          <span class="lai-row-actions">
            <RouterLink
              :to="{ name: 'trace-detail', params: { traceId: row.trace_id } }"
              class="lai-btn lai-btn-text"
            >
              详情
            </RouterLink>
          </span>
        </template>
      </DataTable>
      <ListPager
        v-if="!preciseMode"
        :page="list.page.value"
        :page-size="list.pageSize.value"
        :total="list.total.value"
        @update:page="list.applyPage"
        @update:page-size="list.applyPageSize"
      />
    </template>
  </section>
</template>
