<script setup lang="ts">
import { computed, watch } from 'vue'
import { Button, Card, Progress, Tag } from 'ant-design-vue'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import DataTable, { type TableColumn } from '@/components/DataTable.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { fetchApplications, type ApplicationListItem } from '@/api/applications'
import { formatDateTime } from '@/app/display'
import { ApiError } from '@/api/errors'
import {
  amountUsage,
  integerUnits,
  integerText,
  applicationStatusLabels as statusLabel,
  applicationEnvironmentLabels as environmentLabel,
  applicationBudgetStatusLabels as budgetStatusLabel,
  successRateText,
} from './applicationValues'

const store = useBootstrapStore()
const canManage = computed(() => store.can(Permission.applicationManage))

const {
  state,
  page,
  pageSize,
  sort,
  applySort,
  dataUpdatedAt,
  items,
  total,
  status,
  refreshing,
  error,
  applyFilters,
  applyPage,
  applyPageSize,
  refresh,
} = useListQuery<Record<string, FilterValue>, ApplicationListItem>({
  fields: {
    keyword: { default: '', url: true },
    status: { default: '', url: true },
    environment: { default: '', url: true },
    owner_id: { default: '', url: true },
    // BE-P20-001：部门精确匹配与预算状态筛选，后端已支持（BACKEND_PLAN 应用列表补充契约）
    department: { default: '', url: true },
    budget_status: { default: '', url: true },
  },
  defaultSort: 'last_called_at desc',
  fetcher: (params, signal) => {
    if (!store.can(Permission.applicationView)) throw new ApiError(403, { code: 'ACCESS_DENIED', type: 'permission', message: '无权查看应用' }, 'local-permission')
    return fetchApplications(params, signal).catch(error => {
      if (!signal.aborted && error instanceof ApiError && [401, 403].includes(error.status)) { items.value = []; total.value = 0 }
      throw error
    })
  },
})

watch(() => [store.userId, store.permissions.join(','), store.applicationScope.join(',')], () => { items.value = []; total.value = 0; refresh() })

const hasFilters = computed(() => Object.values(state).some(value => value !== ''))

function ratio(used: string, reserved: string, limit: string | null): string {
  const u = integerUnits(used), r = integerUnits(reserved)
  if (u === null || r === null) return '数据异常'
  const consumed = u + r
  if (limit == null) return `${integerText(consumed)} / 不限`
  const cap = integerUnits(limit)
  if (cap === null) return '数据异常'
  const percent = cap === 0n ? 100 : Math.min(100, Math.round((Number(consumed) / Number(cap)) * 100))
  return `${integerText(consumed)} / ${integerText(cap)}（${percent}%）`
}

function requestCount(value: string): string {
  const parsed = integerUnits(value)
  return parsed === null ? '数据异常' : integerText(parsed)
}
function amount(row: ApplicationListItem): string {
  return amountUsage(row.amount_used, row.amount_reserved, row.amount_limit, row.currency)
}

const columns: TableColumn[] = [
  { key: 'application', label: '应用', width: '220px' },
  { key: 'owner', label: '负责人 / 部门', width: '180px' },
  { key: 'status', label: '状态', width: '100px' },
  { key: 'models', label: '模型 / 密钥', width: '120px' },
  { key: 'tokens', label: 'Token', width: '180px' },
  { key: 'amount', label: '金额', width: '160px' },
  { key: 'budget', label: '预算状态', width: '120px' },
  { key: 'requests24h', label: '24h 请求', width: '110px' },
  { key: 'success24h', label: '24h 成功率', width: '110px' },
  { key: 'rate', label: 'RPM / TPM', width: '140px' },
  { key: 'lastCalled', label: '最近调用', width: '150px' },
]
</script>

<template>
  <section class="lai-page application-page">
    <div class="page-header">
      <div>
        <h1 class="lai-page-title">
          应用
        </h1>
        <p class="page-subtitle">
          管理企业系统的接入凭证、模型权限、额度与速率。
        </p>
      </div>
      <RouterLink
        v-if="canManage"
        to="/ui/applications/new"
        class="application-create-link"
      >
        <Button type="primary">新建应用</Button>
      </RouterLink>
    </div>

    <div class="application-summary-grid">
      <Card size="small"><span class="summary-label">当前页应用</span><strong>{{ items.length }}</strong></Card>
      <Card size="small"><span class="summary-label">启用中</span><strong>{{ items.filter(item => item.status === 'ACTIVE').length }}</strong></Card>
      <Card size="small"><span class="summary-label">已配置模型</span><strong>{{ items.reduce((sum, item) => sum + item.model_count, 0) }}</strong></Card>
      <Card size="small"><span class="summary-label">已用额度</span><strong>{{ items.length ? amount(items[0]!) : '—' }}</strong></Card>
    </div>

    <div class="lai-filter-bar application-filters">
      <input
        class="lai-input filter-keyword"
        type="search"
        placeholder="搜索应用名称或编码"
        :value="state.keyword"
        @change="applyFilters({ keyword: ($event.target as HTMLInputElement).value.trim() })"
      >
      <select
        class="lai-select"
        :value="state.status"
        @change="applyFilters({ status: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">
          全部状态
        </option>
        <option value="ACTIVE">
          启用
        </option>
        <option value="DISABLED">
          已停用
        </option>
        <option value="ARCHIVED">
          已归档
        </option>
      </select>
      <select
        class="lai-select"
        :value="state.environment"
        @change="applyFilters({ environment: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">
          全部环境
        </option>
        <option value="DEV">
          开发
        </option>
        <option value="TEST">
          测试
        </option>
        <option value="STAGING">
          预发布
        </option>
        <option value="PROD">
          生产
        </option>
      </select>
      <input
        class="lai-input"
        aria-label="负责人账号筛选"
        placeholder="负责人账号"
        :value="state.owner_id"
        @change="applyFilters({ owner_id: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input"
        aria-label="部门筛选"
        placeholder="所属部门"
        :value="state.department"
        @change="applyFilters({ department: ($event.target as HTMLInputElement).value.trim() })"
      >
      <select
        class="lai-select"
        aria-label="预算状态筛选"
        :value="state.budget_status"
        @change="applyFilters({ budget_status: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">
          全部预算状态
        </option>
        <option value="NORMAL">
          额度正常
        </option>
        <option value="EXHAUSTED">
          额度已耗尽
        </option>
        <option value="UNLIMITED">
          未设额度上限
        </option>
      </select>
      <select
        class="lai-select"
        aria-label="排序"
        :value="sort"
        @change="applySort(($event.target as HTMLSelectElement).value)"
      >
        <option value="updated_at desc">
          最近更新
        </option><option value="name asc">
          应用名称升序
        </option><option value="last_called_at desc">
          最近调用
        </option>
      </select>
      <button
        type="button"
        class="lai-btn"
        :disabled="refreshing"
        @click="refresh"
      >
        {{ refreshing ? '刷新中…' : '刷新' }}
      </button>
    </div>

    <p
      v-if="dataUpdatedAt"
      class="cell-muted"
    >
      数据更新于 {{ formatDateTime(dataUpdatedAt, store.timezone) }}
    </p>
    <PageState
      v-if="status === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="status === 'error' && items.length === 0"
      status="error"
      :error="error"
      @retry="refresh"
    />
    <PageState
      v-else-if="items.length === 0"
      status="empty"
      :message="hasFilters ? '没有符合筛选条件的应用，请调整筛选。' : '尚未创建应用，创建后即可签发接入密钥并授权模型。'"
    />
    <template v-else>
      <PageState
        v-if="status === 'error'"
        status="error"
        :error="error"
        @retry="refresh"
      />
      <Card :bordered="false" class="application-table-card">
        <DataTable
          :columns="columns"
          :rows="items"
          :row-key="(row: ApplicationListItem) => row.id"
          :sort="sort"
          :loading="refreshing"
          @sort-change="applySort"
        >
          <template #application="{ row }">
            <RouterLink :to="`/ui/applications/${row.id}`" class="lai-link application-name">{{ row.name }}</RouterLink>
            <div class="cell-muted"><span class="lai-cell-mono">{{ row.code }}</span> · {{ environmentLabel[row.environment] || row.environment }}</div>
          </template>
          <template #owner="{ row }"><div>{{ row.owner_name }}</div><div class="cell-muted">{{ row.department || '—' }}</div></template>
          <template #status="{ row }"><Tag :color="row.status === 'ACTIVE' ? 'green' : row.status === 'DISABLED' ? 'orange' : 'default'">{{ statusLabel[row.status] || row.status }}</Tag></template>
          <template #models="{ row }">{{ row.model_count }} / {{ row.active_key_count }}</template>
          <template #tokens="{ row }"><span>{{ ratio(row.tokens_used, row.tokens_reserved, row.token_limit) }}</span><Progress v-if="row.token_limit" :percent="Math.min(100, Math.round((Number(row.tokens_used) + Number(row.tokens_reserved)) / Math.max(1, Number(row.token_limit)) * 100))" size="small" :show-info="false" /></template>
          <template #amount="{ row }">{{ amount(row) }}</template>
          <template #budget="{ row }">预算：{{ budgetStatusLabel[row.budget_status] || row.budget_status }}</template>
          <template #requests24h="{ row }">{{ requestCount(row.requests_24h) }} 次</template>
          <template #success24h="{ row }">成功率 {{ successRateText(row.success_rate_24h) }}</template>
          <template #rate="{ row }">{{ row.rpm ?? '不限' }} / {{ row.tpm == null ? '不限' : row.tpm.toLocaleString() }}</template>
          <template #lastCalled="{ row }">{{ row.last_called_at ? formatDateTime(row.last_called_at, store.timezone) : '尚未调用' }}</template>
        </DataTable>
      </Card>
      <ListPager
        :page="page"
        :page-size="pageSize"
        :total="total"
        :disabled="refreshing"
        @update:page="applyPage"
        @update:page-size="applyPageSize"
      />
    </template>
  </section>
</template>

<style scoped>
.page-header .lai-page-title { margin-bottom: 0; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.application-create-link { display: inline-flex; text-decoration: none; }
.page-subtitle { margin: 6px 0 0; color: #667085; font-size: 14px; }
.application-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin: 20px 0; }
.application-summary-grid :deep(.ant-card-body) { display: grid; gap: 6px; }
.application-summary-grid strong { color: var(--lai-color-text); font-size: 20px; }
.summary-label { color: var(--lai-color-text-secondary); font-size: 12px; }
.application-filters { flex-wrap: wrap; display: flex; gap: 10px; align-items: center; margin: 24px 0 14px; }
.filter-keyword { width: 280px; }
.application-table { min-width: 1120px; }
.application-table-card { border: 1px solid var(--lai-color-border); border-radius: var(--lai-radius-card); box-shadow: var(--lai-shadow-card); }
.application-name { font-weight: 600; color: #172033; }
.cell-muted { margin-top: 4px; color: #667085; font-size: 12px; }
.status { display: inline-flex; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
.status-active { color: #166534; background: #f0fdf4; }
.status-disabled { color: #92400e; background: #fffbeb; }
.status-archived { color: #475467; background: #f2f4f7; }
@media (max-width: 900px) { .application-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
