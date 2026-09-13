<script setup lang="ts">
// 熔断状态列表（FE-023，附录 4.3.3.1）：OPEN/HALF_OPEN 排序靠前（服务端默认排序），
// 凭证列仅管理员/运维展示（响应不含 credential_id 时显示“受限凭证”）。
import { useRouter } from 'vue-router'
import { Button, Card, Checkbox, Select, Space, Table, Tag } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { circuitStateLabel, openSourceLabel } from '@/app/display'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { fetchCircuits } from '@/api/circuits'
import type { CircuitStateListItem } from '@/api/circuits'

const router = useRouter()
const store = useBootstrapStore()
const canOperate = store.can(Permission.circuitOperate)
const canSeeCredential = store.roles.includes('SYSTEM_ADMIN') || store.roles.includes('OPERATOR')

const { state: query, items, total, page, pageSize, status, error, refreshing, dataUpdatedAt, applyFilters, applyPage, applyPageSize, refresh } =
  useListQuery<Record<string, FilterValue>, CircuitStateListItem>({
    fields: {
      state: { default: '', url: true },
      openSource: { default: '', url: true },
      hasRecentFailure: { default: '', url: true },
    },
    // 服务端默认：state 优先级 OPEN、HALF_OPEN、CLOSED 后按 updated_at desc（附录 4.3.5.5）
    defaultSort: 'state_priority',
    fetcher: (params, signal) =>
      fetchCircuits(
        {
          state: params.state === '' ? undefined : String(params.state),
          open_source: params.openSource === '' ? undefined : String(params.openSource),
          has_recent_failure: params.hasRecentFailure === '' ? undefined : params.hasRecentFailure === 'true',
          page: params.page,
          page_size: params.page_size,
          sort: params.sort,
        },
        signal,
      ),
  })

function rateText(row: CircuitStateListItem): string {
  const rate = Number(row.failure_rate)
  if (!Number.isFinite(rate)) return row.failure_rate
  return `${(rate * 100).toFixed(2)}%`
}

function credentialText(row: CircuitStateListItem): string {
  if (!canSeeCredential) return '受限凭证'
  return row.credential_name ? `${row.credential_name}（${row.credential_masked_value ?? ''}）` : '—'
}

function goTraces(row: CircuitStateListItem): void {
  void router.push({ path: '/ui/traces', query: { provider_model_id: row.provider_model_id, error_code: row.last_error_code ?? undefined } })
}

const stateOptions = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: circuitStateLabel('OPEN') },
  { value: 'HALF_OPEN', label: circuitStateLabel('HALF_OPEN') },
  { value: 'CLOSED', label: circuitStateLabel('CLOSED') },
]
const sourceOptions = [
  { value: '', label: '全部来源' },
  { value: 'AUTO', label: openSourceLabel('AUTO') },
  { value: 'MANUAL', label: openSourceLabel('MANUAL') },
]
const tableColumns: ColumnsType<CircuitStateListItem> = [
  { key: 'provider', title: 'Provider', width: 150 },
  { key: 'model', title: '模型', width: 180 },
  { key: 'credential', title: 'Credential', width: 200 },
  { key: 'state', title: '状态', width: 160 },
  { key: 'source', title: '来源', width: 100 },
  { key: 'sample', title: '窗口样本', width: 130 },
  { key: 'failure', title: '失败率', width: 100 },
  { key: 'opened', title: '打开时间', width: 170 },
  { key: 'probe', title: '下次探测', width: 170 },
  { key: 'error', title: '最近错误', width: 130 },
  { key: 'actions', title: '操作', fixed: 'right' as const, width: 170 },
]
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        熔断状态
      </h1>
    </div>

    <div class="lai-filter-bar">
      <Select
        class="lai-filter-select"
        :value="String(query.state ?? '')"
        :options="stateOptions"
        @change="(value) => applyFilters({ state: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.openSource ?? '')"
        :options="sourceOptions"
        @change="(value) => applyFilters({ openSource: String(value ?? '') })"
      />
      <Checkbox
        :checked="query.hasRecentFailure === 'true'"
        class="lai-switch"
        @change="({ target }) => applyFilters({ hasRecentFailure: target.checked ? 'true' : '' })"
      >
        仅显示当前窗口有失败
      </Checkbox>
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
      message="没有匹配的熔断记录"
    />
    <template v-else>
      <Card :bordered="false" class="circuit-table-card">
        <Table :columns="tableColumns" :data-source="items" :row-key="(row: CircuitStateListItem) => row.id" :pagination="false" :loading="refreshing" :scroll="{ x: 1500 }" size="middle">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'provider'">{{ record.provider_name }}</template>
            <template v-else-if="column.key === 'model'"><RouterLink :to="`/ui/models/upstream/${record.provider_model_id}`" class="lai-link">{{ record.provider_model_name }}</RouterLink></template>
            <template v-else-if="column.key === 'credential'"><span class="lai-cell-mono">{{ credentialText(record as CircuitStateListItem) }}</span></template>
            <template v-else-if="column.key === 'state'"><Tag :color="record.state === 'OPEN' ? 'red' : record.state === 'HALF_OPEN' ? 'orange' : 'green'">{{ circuitStateLabel(record.state) }}</Tag><span v-if="record.state === 'HALF_OPEN'" class="lai-cell-sub">探测中 {{ record.half_open_in_flight }}（成功 {{ record.half_open_success_count }}）</span></template>
            <template v-else-if="column.key === 'source'">{{ record.open_source ? openSourceLabel(record.open_source) : '—' }}</template>
            <template v-else-if="column.key === 'sample'">{{ record.sample_count }}（失败 {{ record.failure_count }}）</template>
            <template v-else-if="column.key === 'failure'">{{ rateText(record as CircuitStateListItem) }}<span v-if="record.sample_count < 1" class="lai-cell-sub">样本不足</span></template>
            <template v-else-if="column.key === 'opened'">{{ record.opened_at ?? '—' }}</template>
            <template v-else-if="column.key === 'probe'">{{ record.next_probe_at ?? '—' }}</template>
            <template v-else-if="column.key === 'error'"><Button v-if="record.last_error_code" type="link" size="small" class="lai-cell-mono" @click="goTraces(record as CircuitStateListItem)">{{ record.last_error_code }}</Button><span v-else>—</span></template>
            <template v-else-if="column.key === 'actions'"><Space size="small"><RouterLink :to="`/ui/circuits/${record.id}`">查看详情</RouterLink><RouterLink v-if="canOperate" :to="`/ui/circuits/${record.id}`">操作</RouterLink></Space></template>
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
  </section>
</template>

<style scoped>
.lai-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lai-filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.lai-filter-select {
  width: auto;
  padding: 5px 8px;
}
.lai-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
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
.lai-state-open {
  color: #cf222e;
  font-weight: 600;
}
.lai-state-half {
  color: #9a6700;
  font-weight: 600;
}
.lai-state-closed {
  color: #1a7f37;
}
.lai-updated-at {
  font-size: 12px;
  color: #57606a;
}
</style>
