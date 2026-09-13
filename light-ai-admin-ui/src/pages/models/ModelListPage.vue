<script setup lang="ts">
// 上游模型 列表页（FE-015，附录 4.2.5.1）：筛选同步 URL，行内启停删除带影响确认，
// 勾选 1—20 个同 渠道 模型发起批量检测（FE-016）。
import { computed, ref, shallowRef } from 'vue'
import { Button, Card, Input, Select, Space, Table, Tag } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { TableRowSelection } from 'ant-design-vue/es/table/interface'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import BatchCheckPanel from './BatchCheckPanel.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { connectionStatusLabel } from '@/app/display'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { fetchProviderModels, fetchProviderCredentials } from '@/api/providerModels'
import type { ProviderModelListItem } from '@/api/providerModels'
import { useListActions } from '../listActions'

const store = useBootstrapStore()
const canManage = computed(() => store.can(Permission.modelManage))
const canCheck = computed(() => store.can(Permission.providerCheck))

const { state: query, items, total, page, pageSize, status, error, refreshing, dataUpdatedAt, applyFilters, applyPage, applyPageSize, refresh } =
  useListQuery<Record<string, FilterValue>, ProviderModelListItem>({
    fields: {
      keyword: { default: '', url: true },
      connectionStatus: { default: '', url: true },
      supportStream: { default: '', url: true },
      enabled: { default: '', url: true },
    },
    defaultSort: 'updated_at',
    fetcher: (params, signal) =>
      fetchProviderModels(
        {
          keyword: params.keyword === '' ? undefined : String(params.keyword),
          connection_status: params.connectionStatus === '' ? undefined : String(params.connectionStatus),
          support_stream: params.supportStream === '' ? undefined : params.supportStream === 'true',
          enabled: params.enabled === '' ? undefined : params.enabled === 'true',
          page: params.page,
          page_size: params.page_size,
          sort: params.sort,
        },
        signal,
      ),
  })

const {
  busyId,
  disableTarget,
  deleteTarget,
  openToggle,
  openDelete,
  submitToggle,
  submitDelete,
  actionText,
} = useListActions<ProviderModelListItem>({
  togglePath: (row) => `/upstream-models/${row.id}/${row.enabled ? 'disable' : 'enable'}`,
  deletePath: (row) => `/upstream-models/${row.id}`,
  impactPath: (row) => `/upstream-models/${row.id}/impact`,
  reload: refresh,
})

function modelName(row: ProviderModelListItem): string {
  return `${row.display_name}（${row.model_id}）`
}

const selected = ref<ProviderModelListItem[]>([])
const selectedSameProvider = computed(
  () => new Set(selected.value.map((item) => item.channel_id)).size <= 1,
)
function clearSelection(): void {
  selected.value = []
}

const batchError = ref('')
const batchOpen = ref(false)
const batchProviderName = computed(() => selected.value[0]?.channel_name ?? '')
const credentialOptions = shallowRef<{ id: string; label: string }[]>([])

async function openBatchCheck(): Promise<void> {
  if (!canCheck.value || !selectedSameProvider.value || !selected.value.length) return
  const channelId = selected.value[0]!.channel_id
  batchOpen.value = true
  batchError.value = ''
  credentialOptions.value = []
  try {
    const rows = await fetchProviderCredentials(channelId)
    if (selected.value[0]?.channel_id === channelId) credentialOptions.value = rows.map((row) => ({ id: row.id, label: row.name }))
  } catch (error) { batchError.value = error instanceof Error ? error.message : '渠道 Key 查询失败' }
}

const connectionOptions = [
  { value: '', label: '全部连接状态' },
  { value: 'UNKNOWN', label: connectionStatusLabel('UNKNOWN') },
  { value: 'AVAILABLE', label: connectionStatusLabel('AVAILABLE') },
  { value: 'UNAVAILABLE', label: connectionStatusLabel('UNAVAILABLE') },
]

const supportStreamOptions = [
  { value: '', label: '全部流式' },
  { value: 'true', label: '支持流式' },
  { value: 'false', label: '不支持流式' },
]

const enabledOptions = [
  { value: '', label: '全部启停' },
  { value: 'true', label: '已启用' },
  { value: 'false', label: '已停用' },
]

const tableColumns: ColumnsType<ProviderModelListItem> = [
  { key: 'model', title: '模型', width: 220 },
  { key: 'channel', title: '渠道', width: 160 },
  { key: 'context', title: '上下文', width: 110 },
  { key: 'max_output', title: '最大输出', width: 110 },
  { key: 'stream', title: '流式', width: 90 },
  { key: 'price', title: '价格（输入/输出）', width: 190 },
  { key: 'connection', title: '连接状态', width: 150 },
  { key: 'candidate', title: '候选', width: 80 },
  { key: 'enabled', title: '启停', width: 90 },
  { key: 'draft', title: '待发布', width: 90 },
  { key: 'actions', title: '操作', fixed: 'right' as const, width: 220 },
]

function onSelectionChange(_keys: Array<string | number>, rows: ProviderModelListItem[]): void {
  selected.value = rows
}

const rowSelection = computed<TableRowSelection<ProviderModelListItem> | undefined>(() => {
  if (!canCheck.value) return undefined
  return {
    selectedRowKeys: selected.value.map((row) => row.id),
    onChange: onSelectionChange,
  }
})
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        模型
      </h1>
      <div
        v-if="canManage"
        class="lai-page-actions"
      >
        <RouterLink
          to="/ui/models/upstream/import"
          class="application-create-link"
        >
          <Button>导入模型</Button>
        </RouterLink>
        <RouterLink
          to="/ui/models/upstream/new"
          class="application-create-link"
        >
          <Button type="primary">新建模型</Button>
        </RouterLink>
      </div>
    </div>

    <Card :bordered="false" class="model-filter-card">
    <div class="lai-filter-bar">
      <Input
        class="lai-filter-input"
        placeholder="名称或模型标识"
        :value="String(query.keyword ?? '')"
        @change="($event) => applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.connectionStatus ?? '')"
        :options="connectionOptions"
        @change="(value) => applyFilters({ connectionStatus: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.supportStream ?? '')"
        :options="supportStreamOptions"
        @change="(value) => applyFilters({ supportStream: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.enabled ?? '')"
        :options="enabledOptions"
        @change="(value) => applyFilters({ enabled: String(value ?? '') })"
      />
      <span
        v-if="refreshing"
        class="lai-refreshing"
      >刷新中…</span>
    </div>
    </Card>

    <div
      v-if="canCheck && selected.length > 0"
      class="lai-selection-bar"
    >
      <span>
        已选 {{ selected.length }} 个模型
        <template v-if="!selectedSameProvider">（必须为同一 渠道）</template>
        <template v-else-if="selected.length > 20">（最多 20 个）</template>
      </span>
      <Button
        v-if="selectedSameProvider && selected.length <= 20"
        type="primary"
        @click="openBatchCheck"
      >
        批量检测
      </Button>
      <Button
        type="link"
        @click="clearSelection"
      >
        清除选择
      </Button>
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
      message="没有匹配的模型"
    />
    <template v-else>
      <p
        v-if="actionText()"
        class="lai-form-message-error"
        role="alert"
      >
        {{ actionText() }}
      </p>
      <Card :bordered="false" class="model-table-card">
        <Table
          :columns="tableColumns"
          :data-source="items"
          :row-key="(row: ProviderModelListItem) => row.id"
          :row-selection="rowSelection"
          :pagination="false"
          :loading="refreshing"
          :scroll="{ x: 1500 }"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'model'">
              <RouterLink :to="`/ui/models/upstream/${record.id}`" class="lai-link">{{ record.display_name }}</RouterLink>
              <span class="lai-cell-mono lai-cell-sub">{{ record.model_id }}</span>
            </template>
            <template v-else-if="column.key === 'context'">{{ record.context_window?.toLocaleString('zh-CN') ?? '待补充' }}</template>
            <template v-else-if="column.key === 'max_output'">{{ record.max_output_tokens?.toLocaleString('zh-CN') ?? '待补充' }}</template>
            <template v-else-if="column.key === 'stream'">{{ record.support_stream == null ? '待补充' : record.support_stream ? '支持' : '不支持' }}</template>
            <template v-else-if="column.key === 'price'"><span class="lai-cell-mono">{{ record.input_price }} / {{ record.output_price }}</span><span class="lai-cell-sub">每 {{ record.price_unit }} tokens · {{ record.currency }}</span></template>
            <template v-else-if="column.key === 'connection'"><Tag :color="record.connection_status === 'AVAILABLE' ? 'green' : record.connection_status === 'UNAVAILABLE' ? 'red' : 'default'">{{ connectionStatusLabel(record.connection_status) }}</Tag><span class="lai-cell-sub">{{ record.last_check_at ?? '未检测' }}</span></template>
            <template v-else-if="column.key === 'enabled'"><Tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</Tag></template>
            <template v-else-if="column.key === 'draft'"><Tag v-if="record.draft_changed" color="orange">待发布</Tag><span v-else>—</span></template>
            <template v-else-if="column.key === 'actions'">
              <Space size="small">
                <RouterLink :to="`/ui/models/upstream/${record.id}`">查看</RouterLink>
                <RouterLink v-if="canManage" :to="`/ui/models/upstream/${record.id}/edit`">编辑</RouterLink>
                <Button v-if="canManage" type="link" size="small" :loading="busyId === record.id" @click="openToggle(record as ProviderModelListItem)">{{ record.enabled ? '停用' : '启用' }}</Button>
                <Button v-if="canManage" type="link" danger size="small" @click="openDelete(record as ProviderModelListItem)">删除</Button>
              </Space>
            </template>
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
      v-model:open="disableTarget.open"
      title="停用模型"
      :message="`确认停用模型「${disableTarget.row ? modelName(disableTarget.row as ProviderModelListItem) : ''}」？停用并发布后，引用它的候选不再进入新请求。`"
      :impact="disableTarget.impact"
      danger
      :loading="disableTarget.loading"
      @confirm="submitToggle"
    />
    <ConfirmDialog
      v-model:open="deleteTarget.open"
      title="删除模型"
      :message="`确认删除模型「${deleteTarget.row ? modelName(deleteTarget.row as ProviderModelListItem) : ''}」？存在候选引用时将被拒绝。`"
      :impact="deleteTarget.impact"
      danger
      :loading="deleteTarget.loading"
      @confirm="submitDelete"
    />
    <p
      v-if="batchError"
      role="alert"
    >
      {{ batchError }}
    </p>
    <BatchCheckPanel
      v-model:open="batchOpen"
      :channel-id="selected[0]?.channel_id ?? ''"
      :models="selected.map((item) => ({ id: item.id, label: modelName(item) }))"
      :credential-options="credentialOptions"
      :provider-name="batchProviderName"
    />
  </section>
</template>

<style scoped>
.model-filter-card,
.model-table-card { margin-bottom: 16px; border: 1px solid var(--lai-border); box-shadow: 0 8px 24px rgba(37, 99, 235, .05); }
.model-filter-card :deep(.ant-card-body) { padding: 14px 16px; }
.model-table-card :deep(.ant-card-body) { padding: 0; }
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
.lai-selection-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 8px;
  background: #f6f8fa;
  border-radius: 6px;
  font-size: 13px;
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
  font-weight: 600;
  background: #f6f8fa;
}
.lai-col-check {
  width: 32px;
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
.lai-visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
