<script setup lang="ts">
// 可靠性策略列表（FE-021，附录 4.3.2.1）：默认策略面板只读、恢复决策抽屉（FE-022）。
import { ref } from 'vue'
import { Button, Card, Input, Select, Space, Table, Tag } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ReliabilityDefaultPanel from './ReliabilityDefaultPanel.vue'
import RecoveryDecisionsDrawer from './RecoveryDecisionsDrawer.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { fetchReliabilityPolicies } from '@/api/reliabilityPolicies'
import type { ReliabilityPolicyListItem } from '@/api/reliabilityPolicies'
import { useListActions } from '../listActions'

const store = useBootstrapStore()
const canManage = store.can(Permission.reliabilityManage)
const canViewRecovery = store.can(Permission.circuitOperate)

const { state: query, items, total, page, pageSize, status, error, refreshing, dataUpdatedAt, applyFilters, applyPage, applyPageSize, refresh } =
  useListQuery<Record<string, FilterValue>, ReliabilityPolicyListItem>({
    fields: {
      keyword: { default: '', url: true },
      fallbackEnabled: { default: '', url: true },
      enabled: { default: '', url: true },
    },
    defaultSort: 'updated_at',
    fetcher: (params, signal) =>
      fetchReliabilityPolicies(
        {
          keyword: params.keyword === '' ? undefined : String(params.keyword),
          fallback_enabled: params.fallbackEnabled === '' ? undefined : params.fallbackEnabled === 'true',
          enabled: params.enabled === '' ? undefined : params.enabled === 'true',
          page: params.page,
          page_size: params.page_size,
          sort: params.sort,
        },
        signal,
      ),
  })

const { busyId, openToggle, openDelete, submitDelete, actionText, deleteTarget } = useListActions<ReliabilityPolicyListItem>({
  togglePath: (row) => `/reliability-policies/${row.id}/${row.enabled ? 'disable' : 'enable'}`,
  deletePath: (row) => `/reliability-policies/${row.id}`,
  impactPath: (row) => `/reliability-policies/${row.id}/impact`,
  reload: refresh,
})

function ratePercent(value: string): string {
  const rate = Number(value)
  if (!Number.isFinite(rate)) return value
  return `${(rate * 100).toFixed(2)}%`
}

const defaultOpen = ref(false)
const recoveryOpen = ref(false)
const recoveryPolicy = ref<ReliabilityPolicyListItem | null>(null)
function openRecovery(row: ReliabilityPolicyListItem): void {
  recoveryPolicy.value = row
  recoveryOpen.value = true
}

const fallbackOptions = [
  { value: '', label: '全部 Fallback' },
  { value: 'true', label: '允许 Fallback' },
  { value: 'false', label: '关闭 Fallback' },
]
const enabledOptions = [
  { value: '', label: '全部启停' },
  { value: 'true', label: '已启用' },
  { value: 'false', label: '已停用' },
]
const tableColumns: ColumnsType<ReliabilityPolicyListItem> = [
  { key: 'name', title: '名称', width: 180 },
  { key: 'alias', title: 'Alias', width: 180 },
  { key: 'connect', title: '连接超时', width: 110 },
  { key: 'first_token', title: '首 Token 超时', width: 120 },
  { key: 'total', title: '总超时', width: 100 },
  { key: 'retry', title: '重试', width: 80 },
  { key: 'failover', title: '换密钥', width: 90 },
  { key: 'fallback', title: 'Fallback', width: 100 },
  { key: 'circuit', title: '熔断窗口/阈值', width: 170 },
  { key: 'open', title: 'OPEN 时长', width: 110 },
  { key: 'enabled', title: '启停', width: 90 },
  { key: 'draft', title: '待发布', width: 90 },
  { key: 'actions', title: '操作', fixed: 'right' as const, width: 260 },
]
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        可靠性策略
      </h1>
      <div class="lai-page-actions">
        <Button
          @click="defaultOpen = true"
        >
          系统默认策略
        </Button>
        <RouterLink
          v-if="canManage"
          to="/ui/reliability-policies/new"
          class="application-create-link"
        >
          <Button type="primary">新建可靠性策略</Button>
        </RouterLink>
      </div>
    </div>

    <div class="lai-filter-bar">
      <Input
        class="lai-filter-input"
        placeholder="名称或 Alias"
        :value="String(query.keyword ?? '')"
        @change="($event) => applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.fallbackEnabled ?? '')"
        :options="fallbackOptions"
        @change="(value) => applyFilters({ fallbackEnabled: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.enabled ?? '')"
        :options="enabledOptions"
        @change="(value) => applyFilters({ enabled: String(value ?? '') })"
      />
      <span class="lai-visually-hidden">全部 Fallback 允许 Fallback 关闭 Fallback 全部启停 已启用 已停用</span>
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
      message="没有匹配的可靠性策略"
    />
    <template v-else>
      <p
        v-if="actionText()"
        class="lai-form-message-error"
        role="alert"
      >
        {{ actionText() }}
      </p>
      <Card :bordered="false" class="reliability-table-card">
        <Table :columns="tableColumns" :data-source="items" :row-key="(row: ReliabilityPolicyListItem) => row.id" :pagination="false" :loading="refreshing" :scroll="{ x: 1500 }" size="middle">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'"><RouterLink :to="`/ui/reliability-policies/${record.id}/edit`" class="lai-link">{{ record.name }}</RouterLink></template>
            <template v-else-if="column.key === 'alias'"><RouterLink :to="`/ui/models/virtual/${record.alias_id}`" class="lai-link lai-cell-mono">{{ record.alias }}</RouterLink></template>
            <template v-else-if="column.key === 'connect'">{{ record.connect_timeout_ms }} ms</template>
            <template v-else-if="column.key === 'first_token'">{{ record.first_token_timeout_ms }} ms</template>
            <template v-else-if="column.key === 'total'">{{ record.total_timeout_ms }} ms</template>
            <template v-else-if="column.key === 'retry'">{{ record.max_retries }}</template>
            <template v-else-if="column.key === 'failover'">{{ record.max_credential_failovers }}</template>
            <template v-else-if="column.key === 'fallback'">{{ record.fallback_enabled ? record.max_fallbacks : 0 }}</template>
            <template v-else-if="column.key === 'circuit'"><span class="lai-cell-mono">{{ record.circuit_window_seconds }}s / {{ record.circuit_min_requests }} 次</span><span class="lai-cell-sub">{{ ratePercent(record.circuit_failure_rate) }}</span></template>
            <template v-else-if="column.key === 'open'">{{ record.circuit_open_seconds }}s</template>
            <template v-else-if="column.key === 'enabled'"><Tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</Tag></template>
            <template v-else-if="column.key === 'draft'"><Tag v-if="record.draft_changed" color="orange">待发布</Tag><span v-else>—</span></template>
            <template v-else-if="column.key === 'actions'"><Space size="small"><Button v-if="canViewRecovery" type="link" size="small" @click="openRecovery(record as ReliabilityPolicyListItem)">恢复决策</Button><RouterLink v-if="canManage" :to="`/ui/reliability-policies/${record.id}/edit`">编辑</RouterLink><Button v-if="canManage" type="link" size="small" :loading="busyId === record.id" @click="openToggle(record as ReliabilityPolicyListItem)">{{ record.enabled ? '停用' : '启用' }}</Button><Button v-if="canManage" type="link" danger size="small" @click="openDelete(record as ReliabilityPolicyListItem)">删除</Button></Space></template>
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
      title="删除可靠性策略"
      :message="`确认删除「${(deleteTarget.row as { name?: string } | null)?.name ?? ''}」？活动快照引用时需先停用并发布。`"
      danger
      :loading="deleteTarget.loading"
      @confirm="submitDelete"
    />
    <ReliabilityDefaultPanel v-model:open="defaultOpen" />
    <RecoveryDecisionsDrawer
      v-model:open="recoveryOpen"
      :policy="recoveryPolicy"
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
.lai-updated-at {
  font-size: 12px;
  color: #57606a;
}
</style>
