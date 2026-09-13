<script setup lang="ts">
// 限流策略列表（FE-019，附录 4.3.1.1）：实时用量列 5 秒刷新、启用要求至少一个上限、
// 删除需确认；查看排队打开只读用量与队列抽屉（FE-020）。
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Button, Card, Input, Select, Space, Table, Tag } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import LimitUsageDrawer from './LimitUsageDrawer.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { counterStoreStatusLabel, overflowStrategyLabel, scopeTypeLabel } from '@/app/display'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { fetchLimitPolicies } from '@/api/limitPolicies'
import type { LimitPolicyListItem } from '@/api/limitPolicies'
import { useListActions } from '../listActions'

function scopeLink(row: LimitPolicyListItem): string {
  if (row.scope_type === 'MODEL_ALIAS') return `/ui/models/virtual/${row.scope_id}`
  if (row.scope_type === 'PROVIDER_MODEL') return `/ui/models/upstream/${row.scope_id}`
  return '/ui/credential-pools'
}

const store = useBootstrapStore()
const canManage = store.can(Permission.limitManage)

const { state: query, items, total, page, pageSize, status, error, refreshing, dataUpdatedAt, applyFilters, applyPage, applyPageSize, refresh } =
  useListQuery<Record<string, FilterValue>, LimitPolicyListItem>({
    fields: {
      keyword: { default: '', url: true },
      scopeType: { default: '', url: true },
      overflowStrategy: { default: '', url: true },
      enabled: { default: '', url: true },
    },
    defaultSort: 'updated_at',
    fetcher: (params, signal) =>
      fetchLimitPolicies(
        {
          keyword: params.keyword === '' ? undefined : String(params.keyword),
          scope_type: params.scopeType === '' ? undefined : String(params.scopeType),
          overflow_strategy: params.overflowStrategy === '' ? undefined : String(params.overflowStrategy),
          enabled: params.enabled === '' ? undefined : params.enabled === 'true',
          page: params.page,
          page_size: params.page_size,
          sort: params.sort,
        },
        signal,
      ),
  })

const { busyId, openToggle, openDelete, submitDelete, actionText, deleteTarget } = useListActions<LimitPolicyListItem>({
  togglePath: (row) => `/limit-policies/${row.id}/${row.enabled ? 'disable' : 'enable'}`,
  deletePath: (row) => `/limit-policies/${row.id}`,
  impactPath: (row) => `/limit-policies/${row.id}/impact`,
  reload: refresh,
})

function percent(used: number, limit: number | null): string {
  if (limit == null || limit === 0) return '—'
  return `${Math.round((used / limit) * 100)}%`
}

function tpmTotal(row: LimitPolicyListItem): string {
  return row.tpm_limit == null ? '不限制' : `${row.tpm_reserved + row.tpm_confirmed} / ${row.tpm_limit}`
}

const usageOpen = ref(false)
const usagePolicy = ref<LimitPolicyListItem | null>(null)
function openUsage(row: LimitPolicyListItem): void {
  usagePolicy.value = row
  usageOpen.value = true
}

// 列表用量列每 5 秒刷新（附录 4.3.1.1 concurrency_used 每 5 秒刷新）
let usageTimer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  usageTimer = setInterval(() => {
    if (document.visibilityState === 'visible' && status.value === 'ready') void refresh()
  }, 5000)
})
onBeforeUnmount(() => {
  if (usageTimer !== null) clearInterval(usageTimer)
})

const scopeOptions = [
  { value: '', label: '全部范围' },
  { value: 'MODEL_ALIAS', label: scopeTypeLabel('MODEL_ALIAS') },
  { value: 'PROVIDER_MODEL', label: scopeTypeLabel('PROVIDER_MODEL') },
  { value: 'CREDENTIAL', label: scopeTypeLabel('CREDENTIAL') },
]
const overflowOptions = [
  { value: '', label: '全部溢出策略' },
  { value: 'REJECT', label: overflowStrategyLabel('REJECT') },
  { value: 'QUEUE', label: overflowStrategyLabel('QUEUE') },
]
const enabledOptions = [
  { value: '', label: '全部启停' },
  { value: 'true', label: '已启用' },
  { value: 'false', label: '已停用' },
]
const tableColumns: ColumnsType<LimitPolicyListItem> = [
  { key: 'name', title: '名称', width: 180 },
  { key: 'scope', title: '范围', width: 130 },
  { key: 'target', title: '作用对象', width: 180 },
  { key: 'rpm', title: 'RPM', width: 150 },
  { key: 'tpm', title: 'TPM（预占+确认）', width: 170 },
  { key: 'concurrency', title: '并发', width: 140 },
  { key: 'overflow', title: '溢出策略', width: 140 },
  { key: 'window', title: '窗口复位', width: 100 },
  { key: 'store', title: '计数存储', width: 110 },
  { key: 'enabled', title: '启停', width: 90 },
  { key: 'draft', title: '待发布', width: 90 },
  { key: 'actions', title: '操作', fixed: 'right' as const, width: 250 },
]
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        限流策略
      </h1>
      <div
        v-if="canManage"
        class="lai-page-actions"
      >
        <RouterLink
          to="/ui/limit-policies/new"
          class="application-create-link"
        >
          <Button type="primary">新建限流策略</Button>
        </RouterLink>
      </div>
    </div>

    <div class="lai-filter-bar">
      <Input
        class="lai-filter-input"
        placeholder="名称或作用对象"
        :value="String(query.keyword ?? '')"
        @change="($event) => applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.scopeType ?? '')"
        :options="scopeOptions"
        @change="(value) => applyFilters({ scopeType: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.overflowStrategy ?? '')"
        :options="overflowOptions"
        @change="(value) => applyFilters({ overflowStrategy: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.enabled ?? '')"
        :options="enabledOptions"
        @change="(value) => applyFilters({ enabled: String(value ?? '') })"
      />
      <span class="lai-visually-hidden">全部范围 MODEL_ALIAS PROVIDER_MODEL CREDENTIAL 全部溢出策略 直接拒绝 进入排队 全部启停 已启用 已停用</span>
      <span
        v-if="refreshing"
        class="lai-refreshing"
      >刷新中…</span>
    </div>

    <PageState
      v-if="status === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="status === 'error'"
      status="error"
      :error="error"
      @retry="refresh"
    />
    <PageState
      v-else-if="items.length === 0"
      status="empty"
      message="没有匹配的限流策略"
    />
    <template v-else>
      <p
        v-if="actionText()"
        class="lai-form-message-error"
        role="alert"
      >
        {{ actionText() }}
      </p>
      <Card :bordered="false" class="limit-table-card">
        <Table :columns="tableColumns" :data-source="items" :row-key="(row: LimitPolicyListItem) => row.id" :pagination="false" :loading="refreshing" :scroll="{ x: 1500 }" size="middle">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'"><RouterLink :to="`/ui/limit-policies/${record.id}/edit`" class="lai-link">{{ record.name }}</RouterLink></template>
            <template v-else-if="column.key === 'scope'">{{ scopeTypeLabel(record.scope_type) }}</template>
            <template v-else-if="column.key === 'target'"><RouterLink :to="scopeLink(record as LimitPolicyListItem)" class="lai-link">{{ record.scope_name }}</RouterLink></template>
            <template v-else-if="column.key === 'rpm'"><span class="lai-cell-mono">{{ record.rpm_limit == null ? '不限制' : `${record.rpm_used} / ${record.rpm_limit}` }}</span><span class="lai-cell-sub">{{ record.rpm_limit == null ? '' : percent(record.rpm_used, record.rpm_limit) }}</span></template>
            <template v-else-if="column.key === 'tpm'"><span class="lai-cell-mono">{{ tpmTotal(record as LimitPolicyListItem) }}</span><span class="lai-cell-sub">{{ record.tpm_limit == null ? '' : percent(record.tpm_reserved + record.tpm_confirmed, record.tpm_limit) }}</span></template>
            <template v-else-if="column.key === 'concurrency'"><span class="lai-cell-mono">{{ record.concurrent_limit == null ? '不限制' : `${record.concurrency_used} / ${record.concurrent_limit}` }}</span><span class="lai-cell-sub">{{ record.concurrent_limit == null ? '' : percent(record.concurrency_used, record.concurrent_limit) }}</span></template>
            <template v-else-if="column.key === 'overflow'">{{ overflowStrategyLabel(record.overflow_strategy) }}<span v-if="record.overflow_strategy === 'QUEUE'" class="lai-cell-sub">排队 {{ record.queue_length }} / {{ record.queue_max_size ?? '—' }}</span></template>
            <template v-else-if="column.key === 'window'">{{ record.window_end ? '窗口中' : '—' }}</template>
            <template v-else-if="column.key === 'store'"><Tag :color="record.counter_store_status === 'OK' ? 'green' : 'orange'">{{ counterStoreStatusLabel(record.counter_store_status) }}</Tag></template>
            <template v-else-if="column.key === 'enabled'"><Tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</Tag></template>
            <template v-else-if="column.key === 'draft'"><Tag v-if="record.draft_changed" color="orange">待发布</Tag><span v-else>—</span></template>
            <template v-else-if="column.key === 'actions'"><Space size="small"><Button type="link" size="small" @click="openUsage(record as LimitPolicyListItem)">查看排队</Button><RouterLink :to="`/ui/limit-policies/${record.id}/edit`">编辑</RouterLink><Button v-if="canManage" type="link" size="small" :loading="busyId === record.id" @click="openToggle(record as LimitPolicyListItem)">{{ record.enabled ? '停用' : '启用' }}</Button><Button v-if="canManage" type="link" danger size="small" @click="openDelete(record as LimitPolicyListItem)">删除</Button></Space></template>
          </template>
        </Table>
      </Card>
      <ListPager
        :page="page"
        :page-size="pageSize"
        :total="total"
        :disabled="refreshing"
        @update:page="applyPage"
        @update:page-size="applyPageSize"
      />
      <p
        v-if="dataUpdatedAt"
        class="lai-updated-at"
      >
        数据更新时间：{{ dataUpdatedAt }}
      </p>
    </template>

    <ConfirmDialog
      v-model:open="deleteTarget.open"
      title="删除限流策略"
      :message="`确认删除「${(deleteTarget.row as { name?: string } | null)?.name ?? ''}」？发布后生效；存在草稿冲突时将被拒绝。`"
      danger
      :loading="deleteTarget.loading"
      @confirm="submitDelete"
    />
    <LimitUsageDrawer
      v-model:open="usageOpen"
      :policy="usagePolicy"
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
.lai-filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.lai-filter-input {
  width: 220px;
}
.lai-filter-select {
  width: auto;
  padding: 5px 8px;
}
.lai-refreshing {
  font-size: 12px;
  color: #57606a;
}
.lai-table-wrap {
  overflow-x: auto;
}
.lai-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.lai-table th,
.lai-table td {
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid #d8dee4;
  white-space: nowrap;
}
.lai-table th {
  color: #57606a;
  background: #f6f8fa;
}
.lai-link {
  color: #0969da;
}
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-cell-sub {
  display: block;
  font-size: 12px;
  color: #57606a;
}
.lai-cell-actions {
  white-space: nowrap;
}
.lai-cell-actions .lai-btn {
  margin-right: 4px;
}
.lai-store-warn {
  color: #9a6700;
}
.lai-updated-at {
  font-size: 12px;
  color: #57606a;
}
</style>
