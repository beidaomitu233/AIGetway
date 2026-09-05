<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import TrendChart from '@/components/TrendChart.vue'
import ListPager from '@/components/ListPager.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import {
  type UsageGroupResult,
  type UsageGroupRow,
  type UsageQuery,
  type UsageSummaryResult,
  type UsageTrendPoint,
  exportUsage,
  fetchUsageGroups,
  fetchUsageSummary,
  fetchUsageTrends,
} from '@/api/usage'
import { ApiError, isAbortError } from '@/api/errors'
import { formatDateTime } from '@/app/display'

const REFRESH_MS = 10000

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()

const canExport = computed(() => store.can('usage.export'))
// Credential 维度筛选仅管理员与运维可见（附录 4.4.3.1）。
const canUseCredentialFilters = computed(
  () => store.can('credential.view') || store.can('trace.diagnostics'),
)

type AreaStatus = 'loading' | 'ready' | 'error'

const query = reactive<UsageQuery>({
  start_at: '',
  end_at: '',
  granularity: 'DAY',
  group_by: 'ALIAS',
  group_sort: '-TOTAL_COST',
  group_page: 1,
  group_page_size: 20,
  trend_metric: 'REQUEST_COUNT',
})
const rangePreset = ref('7d')
const rangePresets = [
  { value: '24h', label: '最近 24 小时', ms: 24 * 3600_000, granularity: 'HOUR' as const },
  { value: '7d', label: '最近 7 天', ms: 7 * 24 * 3600_000, granularity: 'DAY' as const },
  { value: '30d', label: '最近 30 天', ms: 30 * 24 * 3600_000, granularity: 'DAY' as const },
]

function applyPreset(preset: string): void {
  rangePreset.value = preset
  const item = rangePresets.find((entry) => entry.value === preset)
  if (!item) return
  query.end_at = new Date().toISOString()
  query.start_at = new Date(Date.now() - item.ms).toISOString()
  query.granularity = item.granularity
}

const summary = ref<UsageSummaryResult | null>(null)
const summaryStatus = ref<AreaStatus>('loading')
const summaryError = ref<unknown>(null)

const trend = ref<UsageTrendPoint[]>([])
const trendStatus = ref<AreaStatus>('loading')
const trendError = ref<unknown>(null)

const groups = ref<UsageGroupResult | null>(null)
const groupStatus = ref<AreaStatus>('loading')
const groupError = ref<unknown>(null)

const sharedUpdatedAt = ref('')
const fingerprintMismatch = ref(false)
const refreshing = ref(false)
let controller: AbortController | null = null

/** 三接口同参并发；fingerprint 不一致的响应整组丢弃（附录 4.4.4.2）。 */
async function loadAll(): Promise<void> {
  controller?.abort()
  controller = new AbortController()
  refreshing.value = true
  const signal = controller.signal
  fingerprintMismatch.value = false

  const requestQuery: UsageQuery = { ...query }
  if (typeof route.query.currency === 'string' && route.query.currency !== '') {
    requestQuery.currency = route.query.currency
  }

  const [summaryResult, trendResult, groupResult] = await Promise.allSettled([
    fetchUsageSummary(requestQuery, signal),
    fetchUsageTrends(requestQuery, signal),
    fetchUsageGroups(requestQuery, signal),
  ])

  if (isAbortErrorFromAny([summaryResult, trendResult, groupResult])) {
    refreshing.value = false
    return
  }

  const fulfilled: Array<{ query_fingerprint: string; data_updated_at: string }> = []
  for (const result of [summaryResult, trendResult, groupResult]) {
    if (result.status === 'fulfilled') {
      fulfilled.push({
        query_fingerprint: result.value.query_fingerprint,
        data_updated_at: result.value.data_updated_at,
      })
    }
  }
  const fingerprints = new Set(fulfilled.map((result) => result.query_fingerprint))
  if (fulfilled.length >= 2 && fingerprints.size > 1) {
    fingerprintMismatch.value = true
    refreshing.value = false
    return
  }

  const updatedAtTimes = fulfilled
    .map((result) => new Date(result.data_updated_at).getTime())
    .filter((time) => !Number.isNaN(time))
  sharedUpdatedAt.value =
    updatedAtTimes.length > 0 ? new Date(Math.min(...updatedAtTimes)).toISOString() : ''

  if (summaryResult.status === 'fulfilled') {
    summary.value = summaryResult.value
    summaryStatus.value = 'ready'
    summaryError.value = null
  } else if (!isAbortError(summaryResult.reason)) {
    summaryError.value = summaryResult.reason
    summaryStatus.value = 'error'
  }

  if (trendResult.status === 'fulfilled') {
    trend.value = trendResult.value.points
    trendStatus.value = 'ready'
    trendError.value = null
  } else if (!isAbortError(trendResult.reason)) {
    trendError.value = trendResult.reason
    trendStatus.value = 'error'
  }

  if (groupResult.status === 'fulfilled') {
    groups.value = groupResult.value
    groupStatus.value = 'ready'
    groupError.value = null
  } else if (!isAbortError(groupResult.reason)) {
    groupError.value = groupResult.reason
    groupStatus.value = 'error'
  }
  refreshing.value = false
}

function isAbortErrorFromAny(results: Array<PromiseSettledResult<unknown>>): boolean {
  return results.some(
    (result) => result.status === 'rejected' && isAbortError(result.reason),
  )
}

let refreshTimer: ReturnType<typeof setInterval> | null = null
onMounted(async () => {
  applyPreset('7d')
  await loadAll()
  refreshTimer = setInterval(() => {
    if (document.visibilityState !== 'visible') return
    void loadAll()
  }, REFRESH_MS)
})
onUnmounted(() => {
  if (refreshTimer !== null) clearInterval(refreshTimer)
  controller?.abort()
})

function onFilterChange(): void {
  query.group_page = 1
  void loadAll()
}

function onPresetChange(event: Event): void {
  applyPreset((event.target as HTMLSelectElement).value)
  onFilterChange()
}

const metricOptions = [
  { value: 'REQUEST_COUNT', label: '请求量' },
  { value: 'SUCCESS_RATE', label: '成功率' },
  { value: 'ATTEMPT_COUNT', label: '尝试数' },
  { value: 'TOKEN', label: 'Token' },
  { value: 'COST', label: '费用' },
  { value: 'RETRY', label: '重试' },
  { value: 'CREDENTIAL_FAILOVER', label: '凭证切换' },
  { value: 'FALLBACK', label: '候选切换' },
]

const groupByOptions = [
  { value: 'ALIAS', label: 'Alias' },
  { value: 'APPLICATION', label: '应用' },
  { value: 'PROVIDER', label: 'Provider' },
  { value: 'PROVIDER_MODEL', label: '模型' },
  { value: 'CREDENTIAL_POOL', label: '凭证池', adminOnly: true },
  { value: 'CREDENTIAL', label: '凭证', adminOnly: true },
  { value: 'TRACE_STATUS', label: 'Trace 状态' },
  { value: 'ERROR_CODE', label: '错误码' },
  { value: 'USAGE_SOURCE', label: 'Usage 来源' },
]

const visibleGroupByOptions = computed(() =>
  groupByOptions.filter((option) => !option.adminOnly || canUseCredentialFilters.value),
)

const trendSeries = computed(() => {
  const points = trend.value
  const pick = (fn: (point: UsageTrendPoint) => number | null): Array<number | null> =>
    points.map(fn)
  switch (query.trend_metric) {
    case 'SUCCESS_RATE':
      return [{ label: '成功率 %', color: '#165dff', values: pick((p) => p.success_rate), unit: 'percent' as const }]
    case 'ATTEMPT_COUNT':
      return [{ label: '尝试数', color: '#165dff', values: pick((p) => p.attempt_count), unit: 'count' as const }]
    case 'TOKEN':
      return [
        { label: '实际', color: '#165dff', values: pick((p) => p.actual_tokens), unit: 'count' as const },
        { label: '估算', color: '#9f9ff0', values: pick((p) => p.estimated_tokens), unit: 'count' as const },
      ]
    case 'COST': {
      const currencies = new Set<string>()
      for (const point of points) {
        for (const cost of point.costs) currencies.add(cost.currency)
      }
      return [...currencies].map((currency, index) => ({
        label: `费用 ${currency}`,
        color: ['#00b42a', '#ff7d00', '#9f9ff0'][index % 3]!,
        values: pick((p) => {
          const amount = p.costs.find((c) => c.currency === currency)?.total_cost
          return amount === undefined || amount === null ? null : Number(amount)
        }),
        unit: 'cost' as const,
      }))
    }
    case 'RETRY':
      return [{ label: '重试', color: '#ff7d00', values: pick((p) => p.retry_count), unit: 'count' as const }]
    case 'CREDENTIAL_FAILOVER':
      return [{ label: '凭证切换', color: '#00b42a', values: pick((p) => p.credential_failover_count), unit: 'count' as const }]
    case 'FALLBACK':
      return [{ label: '候选切换', color: '#9f9ff0', values: pick((p) => p.fallback_count), unit: 'count' as const }]
    default:
      return [{ label: '请求量', color: '#165dff', values: pick((p) => p.request_count), unit: 'count' as const }]
  }
})

function onTrendBucketClick(point: UsageTrendPoint): void {
  void router.push({
    name: 'trace-list',
    query: { start_at: point.bucket_start, end_at: point.bucket_end },
  })
}

function groupSortValue(column: string): string {
  return `-${column}`
}

function applyGroupSort(column: string): void {
  query.group_sort = groupSortValue(column)
  query.group_page = 1
  void loadAll()
}

function applyGroupPage(page: number): void {
  query.group_page = page
  void loadAll()
}

function applyGroupPageSize(size: number): void {
  query.group_page_size = size
  query.group_page = 1
  void loadAll()
}

function groupRowTarget(row: UsageGroupRow): { name: string; query: Record<string, string> } | null {
  const base = {
    start_at: String(query.start_at),
    end_at: String(query.end_at),
  }
  switch (row.dimension_type) {
    case 'APPLICATION':
      return { name: 'trace-list', query: { ...base, application: row.dimension_name } }
    case 'ALIAS':
      return row.dimension_id
        ? { name: 'trace-list', query: { ...base, alias_id: row.dimension_id } }
        : null
    case 'PROVIDER':
      return row.dimension_id
        ? { name: 'trace-list', query: { ...base, provider_id: row.dimension_id } }
        : null
    case 'PROVIDER_MODEL':
      return row.dimension_id
        ? { name: 'trace-list', query: { ...base, provider_model_id: row.dimension_id } }
        : null
    case 'TRACE_STATUS':
      return { name: 'trace-list', query: { ...base, status: row.dimension_name } }
    case 'ERROR_CODE':
      return row.dimension_name === '未设置'
        ? null
        : { name: 'trace-list', query: { ...base, error_code: row.dimension_name } }
    default:
      // 无法映射的聚合维度不生成错误筛选
      return null
  }
}

const exportState = ref<'idle' | 'running'>('idle')
const exportError = ref('')
let exportController: AbortController | null = null

async function onExport(): Promise<void> {
  if (exportState.value === 'running') return
  exportController?.abort()
  exportController = new AbortController()
  exportState.value = 'running'
  exportError.value = ''
  try {
    await exportUsage({ ...query }, exportController.signal)
    exportState.value = 'idle'
  } catch (e) {
    if (isAbortError(e)) {
      exportState.value = 'idle'
      return
    }
    exportError.value = e instanceof Error ? `${e.message}（${(e as { code?: string }).code ?? ''}）` : '导出失败'
    exportState.value = 'idle'
  }
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}（${error.code}）`
  return '请稍后重试'
}

function formatRate(rate: number | null): string {
  return rate === null ? '—' : `${(rate * 100).toFixed(2)}%`
}

function formatShare(value: number | null): string {
  return value === null ? '—' : `${(value * 100).toFixed(1)}%`
}

const costDelayActive = computed(() => {
  if (sharedUpdatedAt.value === '') return false
  const updatedAt = new Date(sharedUpdatedAt.value).getTime()
  return Date.now() - updatedAt > 2 * REFRESH_MS
})
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        Usage 与 Cost
      </h1>
      <div class="lai-row-actions">
        <button
          v-if="canExport"
          type="button"
          class="lai-btn"
          :disabled="exportState === 'running'"
          @click="onExport"
        >
          {{ exportState === 'running' ? '导出中…' : '导出 CSV' }}
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
    <p
      v-if="fingerprintMismatch"
      class="lai-form-message-error"
      role="alert"
    >
      查询一致性校验未通过，请重新查询
    </p>

    <div class="lai-filter-bar">
      <select
        class="lai-select"
        :value="rangePreset"
        aria-label="时间范围"
        @change="onPresetChange"
      >
        <option
          v-for="preset in rangePresets"
          :key="preset.value"
          :value="preset.value"
        >
          {{ preset.label }}
        </option>
      </select>
      <select
        class="lai-select"
        :value="query.granularity"
        aria-label="粒度"
        @change="query.granularity = ($event.target as HTMLSelectElement).value as 'HOUR' | 'DAY'; onFilterChange()"
      >
        <option value="HOUR">
          按小时
        </option>
        <option value="DAY">
          按天
        </option>
      </select>
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="币种（留空分币种展示）"
        :value="typeof route.query.currency === 'string' ? route.query.currency : ''"
        @change="router.replace({ query: { ...route.query, currency: ($event.target as HTMLInputElement).value || undefined } }); onFilterChange()"
      >
    </div>

    <div class="lai-card">
      <h2 class="lai-card-title">
        摘要
      </h2>
      <PageState
        v-if="summaryStatus === 'loading'"
        status="loading"
      />
      <PageState
        v-else-if="summaryStatus === 'error' && !summary"
        status="error"
        :error="summaryError"
        @retry="loadAll"
      />
      <template v-else-if="summary">
        <p
          v-if="summaryStatus === 'error'"
          class="lai-form-message-error"
          role="alert"
        >
          刷新失败，以下为上次数据：{{ errorText(summaryError) }}
        </p>
        <div class="lai-summary-grid">
          <div class="lai-metric-card">
            <span class="lai-summary-label">请求数</span>
            <strong class="lai-metric-value">{{ summary.request_count }}</strong>
          </div>
          <div class="lai-metric-card">
            <span class="lai-summary-label">成功率</span>
            <strong class="lai-metric-value">{{ formatRate(summary.success_rate) }}</strong>
          </div>
          <div class="lai-metric-card">
            <span class="lai-summary-label">尝试数</span>
            <strong class="lai-metric-value">{{ summary.attempt_count }}</strong>
          </div>
          <div class="lai-metric-card">
            <span class="lai-summary-label">总 Token</span>
            <strong class="lai-metric-value">{{ summary.total_tokens }}</strong>
          </div>
          <div class="lai-metric-card">
            <span class="lai-summary-label">实际 / 估算 Token</span>
            <strong class="lai-metric-value">{{ summary.actual_tokens }} / {{ summary.estimated_tokens }}</strong>
          </div>
          <div
            v-for="cost in summary.costs"
            :key="cost.currency"
            class="lai-metric-card"
          >
            <span class="lai-summary-label">费用（{{ cost.currency }}）</span>
            <strong class="lai-metric-value">{{ cost.total_cost }}</strong>
            <span class="lai-related-meta">
              输入 {{ cost.input_cost }} / 输出 {{ cost.output_cost }}
            </span>
          </div>
        </div>
        <div class="lai-summary-grid lai-status-row">
          <span class="lai-btn lai-btn-text">成功 {{ summary.success_count }}</span>
          <span class="lai-btn lai-btn-text">失败 {{ summary.failure_count }}</span>
          <span class="lai-btn lai-btn-text">取消 {{ summary.cancelled_count }}</span>
          <span class="lai-btn lai-btn-text">排队 {{ summary.queued_count }}</span>
          <span class="lai-btn lai-btn-text">流式 {{ summary.stream_count }}</span>
          <span class="lai-btn lai-btn-text">流中断 {{ summary.stream_interrupted_count }}</span>
          <span class="lai-btn lai-btn-text">重试 {{ summary.retry_count }}</span>
          <span class="lai-btn lai-btn-text">凭证切换 {{ summary.credential_failover_count }}</span>
          <span class="lai-btn lai-btn-text">候选切换 {{ summary.fallback_count }}</span>
        </div>
        <p
          v-if="costDelayActive"
          class="lai-related-meta"
          role="status"
        >
          数据聚合延迟
        </p>
        <p class="lai-related-meta">
          数据更新时间：{{ formatDateTime(sharedUpdatedAt, store.timezone) }}
        </p>
      </template>
    </div>

    <div class="lai-card">
      <div class="lai-chart-header">
        <h2 class="lai-card-title">
          趋势
        </h2>
        <select
          v-model="query.trend_metric"
          class="lai-select"
          aria-label="趋势指标"
          @change="onFilterChange"
        >
          <option
            v-for="option in metricOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </div>
      <PageState
        v-if="trendStatus === 'loading'"
        status="loading"
      />
      <PageState
        v-else-if="trendStatus === 'error' && trend.length === 0"
        status="error"
        :error="trendError"
        @retry="loadAll"
      />
      <template v-else>
        <p
          v-if="trendStatus === 'error'"
          class="lai-form-message-error"
          role="alert"
        >
          刷新失败，以下为上次数据：{{ errorText(trendError) }}
        </p>
        <TrendChart
          :buckets="trend"
          :series="trendSeries"
          empty-text="当前范围无聚合数据"
          @bucket-click="onTrendBucketClick"
        />
        <p class="lai-related-meta">
          点击数据点进入该时间桶的 Trace 列表
        </p>
      </template>
    </div>

    <div class="lai-card">
      <div class="lai-chart-header">
        <h2 class="lai-card-title">
          分组明细
        </h2>
        <select
          v-model="query.group_by"
          class="lai-select"
          aria-label="分组维度"
          @change="onFilterChange"
        >
          <option
            v-for="option in visibleGroupByOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </div>
      <PageState
        v-if="groupStatus === 'loading'"
        status="loading"
      />
      <PageState
        v-else-if="groupStatus === 'error' && !groups"
        status="error"
        :error="groupError"
        @retry="loadAll"
      />
      <template v-else-if="groups">
        <p
          v-if="groupStatus === 'error'"
          class="lai-form-message-error"
          role="alert"
        >
          刷新失败，以下为上次数据：{{ errorText(groupError) }}
        </p>
        <div class="lai-table-wrap">
          <table class="lai-table">
            <thead>
              <tr>
                <th>维度</th>
                <th>币种</th>
                <th
                  class="lai-th-sortable"
                  @click="applyGroupSort('REQUEST_COUNT')"
                >
                  请求数
                </th>
                <th>成功率</th>
                <th
                  class="lai-th-sortable"
                  @click="applyGroupSort('ATTEMPT_COUNT')"
                >
                  尝试数
                </th>
                <th
                  class="lai-th-sortable"
                  @click="applyGroupSort('TOTAL_TOKENS')"
                >
                  Token
                </th>
                <th
                  class="lai-th-sortable"
                  @click="applyGroupSort('TOTAL_COST')"
                >
                  费用
                </th>
                <th>请求占比</th>
                <th>Token 占比</th>
                <th>费用占比</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in groups.rows"
                :key="`${row.dimension_type}-${row.dimension_id}-${row.currency}`"
              >
                <td>{{ row.dimension_name }}</td>
                <td>{{ row.currency }}</td>
                <td>{{ row.request_count }}</td>
                <td>{{ formatRate(row.success_rate) }}</td>
                <td>{{ row.attempt_count }}</td>
                <td>{{ row.total_tokens }}<span class="lai-related-meta">（实 {{ row.actual_tokens }} / 估 {{ row.estimated_tokens }}）</span></td>
                <td>{{ row.total_cost }}</td>
                <td>{{ formatShare(row.request_share) }}</td>
                <td>{{ formatShare(row.token_share) }}</td>
                <td>{{ formatShare(row.cost_share) }}</td>
                <td>
                  <RouterLink
                    v-if="groupRowTarget(row)"
                    :to="{ name: groupRowTarget(row)!.name, query: groupRowTarget(row)!.query }"
                    class="lai-btn lai-btn-text"
                  >
                    Trace
                  </RouterLink>
                  <template v-else>
                    —
                  </template>
                </td>
              </tr>
              <tr v-if="groups.rows.length === 0">
                <td
                  colspan="11"
                  class="lai-table-empty"
                >
                  暂无数据
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <ListPager
          :page="groups.page"
          :page-size="groups.page_size"
          :total="groups.total"
          @update:page="applyGroupPage"
          @update:page-size="applyGroupPageSize"
        />
      </template>
    </div>
  </section>
</template>
