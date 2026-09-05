<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import TrendChart from '@/components/TrendChart.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import {
  type OverviewExceptionItem,
  type OverviewExceptionResult,
  type OverviewFilterOptions,
  type OverviewQuery,
  type OverviewSummary,
  type OverviewTrendPoint,
  fetchOverviewExceptions,
  fetchOverviewFilters,
  fetchOverviewSummary,
  fetchOverviewTrends,
} from '@/api/overview'
import { ApiError, isAbortError } from '@/api/errors'
import { formatDateTime } from '@/app/display'

const REFRESH_MS = 10000
const AUTO_REFRESH_ENABLED = true

const store = useBootstrapStore()
const router = useRouter()

type AreaStatus = 'loading' | 'ready' | 'error'

const filters = ref<OverviewFilterOptions | null>(null)
const filterState = ref<'loading' | 'ready' | 'error'>('loading')
const filterError = ref<unknown>(null)

const query = reactive<Required<Pick<OverviewQuery, 'start_at' | 'end_at'>> & {
  application: string
  alias_id: string
  provider_id: string
  currency: string
  granularity: 'HOUR' | 'DAY'
  metric: string
}>({
  start_at: '',
  end_at: '',
  application: '',
  alias_id: '',
  provider_id: '',
  currency: '',
  granularity: 'HOUR',
  metric: 'REQUEST_COUNT',
})

const rangePreset = ref('1h')
const rangePresets = [
  { value: '1h', label: '最近 1 小时', ms: 3600_000 },
  { value: '24h', label: '最近 24 小时', ms: 24 * 3600_000 },
  { value: '7d', label: '最近 7 天', ms: 7 * 24 * 3600_000 },
  { value: '30d', label: '最近 30 天', ms: 30 * 24 * 3600_000 },
]

function applyPreset(preset: string): void {
  rangePreset.value = preset
  const item = rangePresets.find((entry) => entry.value === preset)
  if (!item) return
  query.end_at = new Date().toISOString()
  query.start_at = new Date(Date.now() - item.ms).toISOString()
  query.granularity = item.ms <= 24 * 3600_000 ? 'HOUR' : 'DAY'
}

const summary = ref<OverviewSummary | null>(null)
const summaryStatus = ref<AreaStatus>('loading')
const summaryError = ref<unknown>(null)

const trend = ref<OverviewTrendPoint[]>([])
const trendStatus = ref<AreaStatus>('loading')
const trendError = ref<unknown>(null)
const trendUpdatedAt = ref('')

const exceptions = ref<OverviewExceptionResult | null>(null)
const exceptionStatus = ref<AreaStatus>('loading')
const exceptionError = ref<unknown>(null)
const exceptionFilter = ref('')

const refreshing = ref(false)
let refreshTimer: ReturnType<typeof setInterval> | null = null
let consecutiveFailures = 0
let controller: AbortController | null = null

function currentQuery(): OverviewQuery {
  const result: OverviewQuery = {
    start_at: query.start_at,
    end_at: query.end_at,
    granularity: query.granularity,
  }
  if (query.application !== '') result.application = query.application
  if (query.alias_id !== '') result.alias_id = query.alias_id
  if (query.provider_id !== '') result.provider_id = query.provider_id
  if (query.currency !== '') result.currency = query.currency
  return result
}

async function loadFilters(): Promise<void> {
  filterState.value = 'loading'
  try {
    filters.value = await fetchOverviewFilters()
    filterState.value = 'ready'
  } catch (e) {
    if (isAbortError(e)) return
    filterError.value = e
    filterState.value = 'error'
  }
}

async function loadAll(): Promise<void> {
  controller?.abort()
  controller = new AbortController()
  refreshing.value = true
  const signal = controller.signal
  const base = currentQuery()

  const summaryPromise = fetchOverviewSummary(base, signal)
    .then((data) => {
      summary.value = data
      summaryStatus.value = 'ready'
      summaryError.value = null
    })
    .catch((e) => {
      if (isAbortError(e)) return
      summaryError.value = e
      summaryStatus.value = 'error'
    })

  const trendPromise = fetchOverviewTrends(base, signal)
    .then((data) => {
      // 每轮整组替换，不把新点累加到旧数组（附录 4.1.1.3）。
      trend.value = data.points
      trendUpdatedAt.value = data.data_updated_at
      trendStatus.value = 'ready'
      trendError.value = null
    })
    .catch((e) => {
      if (isAbortError(e)) return
      trendError.value = e
      trendStatus.value = 'error'
    })

  const exceptionPromise = fetchOverviewExceptions(base, signal)
    .then((data) => {
      exceptions.value = data
      exceptionStatus.value = 'ready'
      exceptionError.value = null
    })
    .catch((e) => {
      if (isAbortError(e)) return
      exceptionError.value = e
      exceptionStatus.value = 'error'
    })

  await Promise.all([summaryPromise, trendPromise, exceptionPromise])
  refreshing.value = false

  const anyDenied = [summaryError.value, trendError.value, exceptionError.value].some(
    (error) => error instanceof ApiError && error.isAccessDenied,
  )
  if (anyDenied) {
    await router.push({ name: 'forbidden' })
    return
  }
  const allFailed = summaryStatus.value === 'error' && trendStatus.value === 'error' && exceptionStatus.value === 'error'
  consecutiveFailures = allFailed ? consecutiveFailures + 1 : 0
}

function refresh(): void {
  void loadAll()
}

function onFilterChange(): void {
  consecutiveFailures = 0
  refresh()
}

function onPresetChange(event: Event): void {
  applyPreset((event.target as HTMLSelectElement).value)
  onFilterChange()
}

function manualGranularity(event: Event): void {
  query.granularity = (event.target as HTMLSelectElement).value as 'HOUR' | 'DAY'
  onFilterChange()
}

onMounted(async () => {
  applyPreset('1h')
  await loadFilters()
  await loadAll()
  refreshTimer = setInterval(() => {
    if (document.visibilityState !== 'visible') return
    if (!AUTO_REFRESH_ENABLED) return
    // 连续三次失败暂停自动刷新，手动刷新成功后恢复
    if (consecutiveFailures >= 3) return
    refresh()
  }, REFRESH_MS)
})
onUnmounted(() => {
  if (refreshTimer !== null) clearInterval(refreshTimer)
  controller?.abort()
})

const metricOptions = [
  { value: 'REQUEST_COUNT', label: '请求量' },
  { value: 'SUCCESS_RATE', label: '成功率' },
  { value: 'AVERAGE_TOTAL_MS', label: '平均耗时' },
  { value: 'P95_FIRST_TOKEN_MS', label: '首 Token P95' },
  { value: 'TOTAL_TOKENS', label: 'Token' },
  { value: 'COST', label: '费用' },
  { value: 'RETRY_COUNT', label: '重试' },
  { value: 'FALLBACK_COUNT', label: '候选切换' },
]

const trendSeries = computed(() => {
  const points = trend.value
  const pick = (fn: (point: OverviewTrendPoint) => number | null): Array<number | null> =>
    points.map(fn)
  switch (query.metric) {
    case 'SUCCESS_RATE':
      return [{ label: '成功率 %', color: '#165dff', values: pick((p) => p.success_rate), unit: 'percent' as const }]
    case 'AVERAGE_TOTAL_MS':
      return [{ label: '平均耗时 ms', color: '#165dff', values: pick((p) => p.average_total_ms), unit: 'ms' as const }]
    case 'P95_FIRST_TOKEN_MS':
      return [{ label: '首 Token P95 ms', color: '#165dff', values: pick((p) => p.p95_first_token_ms), unit: 'ms' as const }]
    case 'TOTAL_TOKENS':
      return [
        { label: '实际', color: '#165dff', values: pick((p) => p.actual_tokens), unit: 'count' as const },
        { label: '估算', color: '#9f9ff0', values: pick((p) => p.estimated_tokens), unit: 'count' as const },
      ]
    case 'COST': {
      const currencies = new Set<string>()
      for (const point of points) {
        for (const cost of point.costs) currencies.add(cost.currency)
      }
      if (query.currency !== '') {
        return [{ label: `费用 ${query.currency}`, color: '#00b42a', values: pick((p) => p.costs.find((c) => c.currency === query.currency)?.amount ? Number(p.costs.find((c) => c.currency === query.currency)!.amount) : null), unit: 'cost' as const }]
      }
      return [...currencies].map((currency, index) => ({
        label: `费用 ${currency}`,
        color: ['#00b42a', '#ff7d00', '#9f9ff0'][index % 3]!,
        values: pick((p) => {
          const amount = p.costs.find((c) => c.currency === currency)?.amount
          return amount === undefined ? null : Number(amount)
        }),
        unit: 'cost' as const,
      }))
    }
    case 'RETRY_COUNT':
      return [{ label: '重试', color: '#ff7d00', values: pick((p) => p.retry_count), unit: 'count' as const }]
    case 'FALLBACK_COUNT':
      return [{ label: '候选切换', color: '#9f9ff0', values: pick((p) => p.fallback_count), unit: 'count' as const }]
    default:
      return [{ label: '请求量', color: '#165dff', values: pick((p) => p.request_count), unit: 'count' as const }]
  }
})

const costDelayActive = computed(() => {
  if (!summary.value) return false
  const updatedAt = new Date(summary.value.data_updated_at).getTime()
  if (Number.isNaN(updatedAt)) return false
  return Date.now() - updatedAt > 2 * REFRESH_MS
})

function errorText(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}（${error.code}）`
  return '请稍后重试'
}

function formatRate(rate: number | null): string {
  return rate === null ? '—' : `${(rate * 100).toFixed(2)}%`
}

function goToTraces(extra: Record<string, string> = {}): void {
  void router.push({
    name: 'trace-list',
    query: {
      start_at: query.start_at,
      end_at: query.end_at,
      ...(query.application !== '' ? { application: query.application } : {}),
      ...(query.alias_id !== '' ? { alias_id: query.alias_id } : {}),
      ...(query.provider_id !== '' ? { provider_id: query.provider_id } : {}),
      ...extra,
    },
  })
}

function goToUsage(currency?: string): void {
  void router.push({
    name: 'usage',
    query: {
      start_at: query.start_at,
      end_at: query.end_at,
      ...(query.application !== '' ? { application: query.application } : {}),
      ...(query.alias_id !== '' ? { alias_id: query.alias_id } : {}),
      ...(query.provider_id !== '' ? { provider_id: query.provider_id } : {}),
      ...(currency !== undefined && currency !== '' ? { currency } : {}),
    },
  })
}

function onTrendBucketClick(point: OverviewTrendPoint): void {
  const start = point.bucket_start
  const end = point.bucket_end
  const traceMetrics = ['REQUEST_COUNT', 'SUCCESS_RATE', 'AVERAGE_TOTAL_MS', 'P95_FIRST_TOKEN_MS', 'RETRY_COUNT', 'FALLBACK_COUNT']
  if (traceMetrics.includes(query.metric)) {
    const extra: Record<string, string> = {}
    if (query.metric === 'RETRY_COUNT') extra.has_retry = 'true'
    if (query.metric === 'FALLBACK_COUNT') extra.has_fallback = 'true'
    void router.push({
      name: 'trace-list',
      query: { start_at: start, end_at: end, ...extra },
    })
    return
  }
  void router.push({
    name: 'usage',
    query: { start_at: start, end_at: end },
  })
}

const exceptionChips = computed(() => {
  const summaryData = exceptions.value?.summary
  if (!summaryData) return []
  return [
    { key: 'CIRCUIT_OPEN', label: `OPEN 熔断 ${summaryData.open_circuit_count}`, filter: 'CIRCUIT_OPEN' },
    { key: 'CIRCUIT_HALF_OPEN', label: `HALF_OPEN 熔断 ${summaryData.half_open_circuit_count}`, filter: 'CIRCUIT_HALF_OPEN' },
    { key: 'CANDIDATE', label: `不可用候选 ${summaryData.unavailable_candidate_count}`, filter: 'CANDIDATE' },
    ...(summaryData.invalid_credential_count !== null
      ? [{ key: 'CREDENTIAL', label: `无效凭证 ${summaryData.invalid_credential_count}`, filter: 'CREDENTIAL' }]
      : []),
    { key: 'TRACE', label: `近期失败 Trace ${summaryData.recent_failure_trace_count}`, filter: 'TRACE' },
  ]
})

const filteredExceptions = computed<OverviewExceptionItem[]>(() => {
  const items = exceptions.value?.items ?? []
  if (exceptionFilter.value === '') return items.slice(0, 20)
  if (exceptionFilter.value === 'CIRCUIT_OPEN') {
    return items.filter((item) => item.item_type === 'CIRCUIT' && item.status === 'OPEN')
  }
  if (exceptionFilter.value === 'CIRCUIT_HALF_OPEN') {
    return items.filter((item) => item.item_type === 'CIRCUIT' && item.status === 'HALF_OPEN')
  }
  return items.filter((item) => item.item_type === exceptionFilter.value)
})

function exceptionTarget(item: OverviewExceptionItem): { name: string; params: Record<string, string> } | null {
  switch (item.item_type) {
    case 'CIRCUIT':
      return { name: 'circuit-detail', params: { id: item.object_id } }
    case 'CANDIDATE':
      return { name: 'alias-detail', params: { id: item.object_id } }
    case 'CREDENTIAL':
      return { name: 'pool-detail', params: { id: item.object_id } }
    case 'TRACE':
      return { name: 'trace-detail', params: { traceId: item.object_id } }
    default:
      return null
  }
}

const itemTypeLabels: Record<string, string> = {
  CIRCUIT: '熔断',
  CANDIDATE: '候选',
  CREDENTIAL: '凭证',
  TRACE: 'Trace',
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        运行概览
      </h1>
      <div class="lai-row-actions">
        <label class="lai-related-meta">
          <input
            type="checkbox"
            class="lai-checkbox"
            checked
            disabled
            title="按 dashboard_refresh_seconds 自动刷新，连续失败三次暂停"
          >
          自动刷新
        </label>
        <button
          type="button"
          class="lai-btn"
          :disabled="refreshing"
          @click="refresh"
        >
          手动刷新
        </button>
      </div>
    </div>

    <PageState
      v-if="filterState === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="filterState === 'error'"
      status="error"
      :error="filterError"
      @retry="loadFilters"
    />
    <template v-else>
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
          v-model="query.application"
          class="lai-select"
          aria-label="应用"
          @change="onFilterChange"
        >
          <option value="">
            全部应用
          </option>
          <option
            v-for="app in filters!.applications"
            :key="app"
            :value="app"
          >
            {{ app }}
          </option>
        </select>
        <select
          v-model="query.alias_id"
          class="lai-select"
          aria-label="Alias"
          @change="onFilterChange"
        >
          <option value="">
            全部 Alias
          </option>
          <option
            v-for="alias in filters!.aliases"
            :key="alias.id"
            :value="alias.id"
          >
            {{ alias.name }}
          </option>
        </select>
        <select
          v-model="query.provider_id"
          class="lai-select"
          aria-label="Provider"
          @change="onFilterChange"
        >
          <option value="">
            全部 Provider
          </option>
          <option
            v-for="provider in filters!.providers"
            :key="provider.id"
            :value="provider.id"
          >
            {{ provider.name }}
          </option>
        </select>
        <select
          v-model="query.currency"
          class="lai-select"
          aria-label="费用币种"
          @change="onFilterChange"
        >
          <option value="">
            全部币种
          </option>
          <option
            v-for="currency in filters!.currencies"
            :key="currency"
            :value="currency"
          >
            {{ currency }}
          </option>
        </select>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          运行摘要
        </h2>
        <PageState
          v-if="summaryStatus === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="summaryStatus === 'error' && !summary"
          status="error"
          :error="summaryError"
          @retry="refresh"
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
            <button
              type="button"
              class="lai-metric-card"
              @click="goToTraces()"
            >
              <span class="lai-summary-label">请求数</span>
              <strong class="lai-metric-value">{{ summary.request_count }}</strong>
            </button>
            <div class="lai-metric-card">
              <span class="lai-summary-label">成功率</span>
              <strong class="lai-metric-value">{{ formatRate(summary.success_rate) }}</strong>
            </div>
            <div class="lai-metric-card">
              <span class="lai-summary-label">平均耗时</span>
              <strong class="lai-metric-value">
                {{ summary.average_total_ms === null ? '—' : `${summary.average_total_ms} ms` }}
              </strong>
            </div>
            <div class="lai-metric-card">
              <span class="lai-summary-label">首 Token P95</span>
              <strong class="lai-metric-value">
                {{ summary.p95_first_token_ms === null ? '—' : `${summary.p95_first_token_ms} ms` }}
              </strong>
            </div>
            <div class="lai-metric-card">
              <span class="lai-summary-label">总 Token</span>
              <strong class="lai-metric-value">{{ summary.total_tokens ?? '—' }}</strong>
              <span class="lai-related-meta">
                实际 {{ summary.actual_tokens ?? '—' }} / 估算 {{ summary.estimated_tokens ?? '—' }}
              </span>
            </div>
            <button
              v-for="cost in summary.costs"
              :key="cost.currency"
              type="button"
              class="lai-metric-card"
              @click="goToUsage(cost.currency)"
            >
              <span class="lai-summary-label">费用（{{ cost.currency }}）</span>
              <strong class="lai-metric-value">{{ cost.amount }}</strong>
            </button>
          </div>
          <div class="lai-summary-grid lai-status-row">
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ status: 'SUCCEEDED' })"
            >
              成功 {{ summary.success_count }}
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ status: 'FAILED' })"
            >
              失败 {{ summary.failure_count }}
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ status: 'STREAM_INTERRUPTED' })"
            >
              流中断 {{ summary.stream_interrupted_count }}
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ status: 'CANCELLED' })"
            >
              取消 {{ summary.cancelled_count }}
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ status: 'RUNNING' })"
            >
              运行/排队 {{ summary.active_count }}
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ has_retry: 'true' })"
            >
              重试 {{ summary.retry_count }}
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ has_credential_failover: 'true' })"
            >
              凭证切换 {{ summary.credential_failover_count }}
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces({ has_fallback: 'true' })"
            >
              候选切换 {{ summary.fallback_count }}
            </button>
          </div>
          <p
            v-if="costDelayActive"
            class="lai-related-meta"
            role="status"
          >
            数据聚合延迟
          </p>
          <p class="lai-related-meta">
            数据更新时间：{{ formatDateTime(summary.data_updated_at, store.timezone) }}
          </p>
        </template>
      </div>

      <div class="lai-card">
        <div class="lai-chart-header">
          <h2 class="lai-card-title">
            趋势分析
          </h2>
          <div class="lai-row-actions">
            <select
              v-model="query.metric"
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
            <select
              :value="query.granularity"
              class="lai-select"
              aria-label="粒度"
              @change="manualGranularity"
            >
              <option value="HOUR">
                按小时
              </option>
              <option value="DAY">
                按天
              </option>
            </select>
          </div>
        </div>
        <PageState
          v-if="trendStatus === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="trendStatus === 'error' && trend.length === 0"
          status="error"
          :error="trendError"
          @retry="refresh"
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
            empty-text="当前范围无调用"
            @bucket-click="onTrendBucketClick"
          />
          <div class="lai-chart-legend">
            <span
              v-for="series in trendSeries"
              :key="series.label"
              class="lai-legend-item"
            >
              <span
                class="lai-legend-dot"
                :style="{ background: series.color }"
              />
              {{ series.label }}
            </span>
            <span class="lai-related-meta">
              更新时间：{{ formatDateTime(trendUpdatedAt, store.timezone) }}
            </span>
          </div>
        </template>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          异常定位
        </h2>
        <PageState
          v-if="exceptionStatus === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="exceptionStatus === 'error' && !exceptions"
          status="error"
          :error="exceptionError"
          @retry="refresh"
        />
        <template v-else>
          <p
            v-if="exceptionStatus === 'error'"
            class="lai-form-message-error"
            role="alert"
          >
            刷新失败，以下为上次数据：{{ errorText(exceptionError) }}
          </p>
          <div class="lai-filter-bar">
            <button
              v-for="chip in exceptionChips"
              :key="chip.key"
              type="button"
              class="lai-btn"
              :class="{ 'lai-btn-primary': exceptionFilter === chip.filter }"
              @click="exceptionFilter = exceptionFilter === chip.filter ? '' : chip.filter"
            >
              {{ chip.label }}
            </button>
          </div>
          <table class="lai-table">
            <thead>
              <tr>
                <th>类型</th>
                <th>对象</th>
                <th>状态</th>
                <th>错误 / 原因</th>
                <th>次数</th>
                <th>最近发生</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in filteredExceptions"
                :key="`${item.item_type}-${item.object_id}`"
              >
                <td>{{ itemTypeLabels[item.item_type] ?? item.item_type }}</td>
                <td>{{ item.object_name }}</td>
                <td>{{ item.status }}</td>
                <td>
                  <template v-if="item.error_code">
                    {{ item.error_code }} · {{ item.error_summary ?? '' }}
                  </template>
                  <template v-else>
                    —
                  </template>
                </td>
                <td>{{ item.occurrence_count }}</td>
                <td>{{ formatDateTime(item.latest_at, store.timezone) }}</td>
                <td>
                  <RouterLink
                    v-if="exceptionTarget(item)"
                    :to="{ name: exceptionTarget(item)!.name, params: exceptionTarget(item)!.params }"
                    class="lai-btn lai-btn-text"
                  >
                    查看
                  </RouterLink>
                  <template v-else>
                    —
                  </template>
                </td>
              </tr>
              <tr v-if="filteredExceptions.length === 0">
                <td
                  colspan="7"
                  class="lai-table-empty"
                >
                  无异常
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </div>
    </template>
  </section>
</template>
