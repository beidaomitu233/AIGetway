<script setup lang="ts">
// 虚拟模型 列表页（FE-017，附录 4.2.7.1）。
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { computed } from 'vue'
import { Button, Card, Input, Select, Space, Table, Tag } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import type { ModelAliasListItem } from '@/api/modelAliases'
import { fetchModelAliases } from '@/api/modelAliases'
import { useListActions } from '../listActions'

const store = useBootstrapStore()
const canManage = computed(() => store.can(Permission.aliasManage))

const { state: query, items, total, page, pageSize, status, error, refreshing, dataUpdatedAt, applyFilters, applyPage, applyPageSize, refresh } =
  useListQuery<Record<string, FilterValue>, ModelAliasListItem>({
    fields: {
      keyword: { default: '', url: true },
      enabled: { default: '', url: true },
      runtimeAvailability: { default: '', url: true },
      supportStream: { default: '', url: true },
    },
    defaultSort: 'updated_at',
    fetcher: (params, signal) =>
      fetchModelAliases(
        {
          keyword: params.keyword === '' ? undefined : String(params.keyword),
          enabled: params.enabled === '' ? undefined : params.enabled === 'true',
          runtime_availability: params.runtimeAvailability === '' ? undefined : String(params.runtimeAvailability),
          support_stream: params.supportStream === '' ? undefined : params.supportStream === 'true',
          page: params.page,
          page_size: params.page_size,
          sort: params.sort,
        },
        signal,
      ),
  })

const {
  disableTarget,
  deleteTarget,
  openToggle,
  openDelete,
  submitToggle,
  submitDelete,
  actionText,
} = useListActions<ModelAliasListItem>({
  togglePath: (row) => `/virtual-models/${row.id}/${row.enabled ? 'disable' : 'enable'}`,
  deletePath: (row) => `/virtual-models/${row.id}`,
  impactPath: (row) => `/virtual-models/${row.id}/impact`,
  reload: refresh,
})

function streamText(row: ModelAliasListItem): string {
  if (row.candidate_count === 0) return '—'
  if (row.stream_candidate_count === row.candidate_count) return '支持'
  if (row.stream_candidate_count === 0) return '不支持'
  return '部分支持'
}

function runtimeText(row: ModelAliasListItem): string {
  return row.available_candidate_count > 0 ? `可调用（${row.available_candidate_count}/${row.candidate_count}）` : '无可用候选'
}

const runtimeOptions = [
  { value: '', label: '全部可用性' },
  { value: 'AVAILABLE', label: '可调用' },
  { value: 'UNAVAILABLE', label: '无可用候选' },
]

const enabledOptions = [
  { value: '', label: '全部启停' },
  { value: 'true', label: '已启用' },
  { value: 'false', label: '已停用' },
]

const supportStreamOptions = [
  { value: '', label: '全部流式' },
  { value: 'true', label: '支持流式' },
  { value: 'false', label: '不支持流式' },
]

const tableColumns: ColumnsType<ModelAliasListItem> = [
  { key: 'alias', title: 'alias', width: 180 },
  { key: 'display_name', title: '展示名称', width: 150 },
  { key: 'route_strategy', title: '路由策略', width: 150 },
  { key: 'candidate_count', title: '候选', width: 80 },
  { key: 'runtime', title: '可用性', width: 150 },
  { key: 'stream', title: '流式', width: 90 },
  { key: 'request_count_24h', title: '24h 调用', width: 110 },
  { key: 'enabled', title: '启停', width: 90 },
  { key: 'draft_changed', title: '待发布', width: 90 },
  { key: 'updated_at', title: '更新时间', width: 170 },
  { key: 'actions', title: '操作', fixed: 'right' as const, width: 220 },
]

const aliasName = (row: ModelAliasListItem) => row.alias
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        虚拟模型
      </h1>
      <div
        v-if="canManage"
        class="lai-page-actions"
      >
        <RouterLink
          to="/ui/models/virtual/new"
          class="application-create-link"
        >
          <Button type="primary">新建虚拟模型</Button>
        </RouterLink>
      </div>
    </div>

    <Card :bordered="false" class="alias-filter-card">
    <div class="lai-filter-bar">
      <Input
        class="lai-filter-input"
        placeholder="alias、名称或描述"
        :value="String(query.keyword ?? '')"
        @change="($event) => applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.enabled ?? '')"
        :options="enabledOptions"
        @change="(value) => applyFilters({ enabled: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.runtimeAvailability ?? '')"
        :options="runtimeOptions"
        @change="(value) => applyFilters({ runtimeAvailability: String(value ?? '') })"
      />
      <Select
        class="lai-filter-select"
        :value="String(query.supportStream ?? '')"
        :options="supportStreamOptions"
        @change="(value) => applyFilters({ supportStream: String(value ?? '') })"
      />
      <span
        v-if="refreshing"
        class="lai-refreshing"
      >刷新中…</span>
    </div>
    </Card>

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
      message="没有匹配的虚拟模型"
    />
    <template v-else>
      <p
        v-if="actionText()"
        class="lai-form-message-error"
        role="alert"
      >
        {{ actionText() }}
      </p>
      <Card :bordered="false" class="alias-table-card">
        <Table
          :columns="tableColumns"
          :data-source="items"
          :row-key="(row: ModelAliasListItem) => row.id"
          :pagination="false"
          :loading="refreshing"
          :scroll="{ x: 1400 }"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'alias'">
              <RouterLink :to="`/ui/models/virtual/${record.id}`" class="lai-link lai-cell-mono">{{ record.alias }}</RouterLink>
            </template>
            <template v-else-if="column.key === 'candidate_count'">
              <RouterLink :to="`/ui/models/virtual/${record.id}`" class="lai-link">{{ record.candidate_count }}</RouterLink>
            </template>
            <template v-else-if="column.key === 'runtime'">{{ runtimeText(record as ModelAliasListItem) }}</template>
            <template v-else-if="column.key === 'stream'">{{ streamText(record as ModelAliasListItem) }}</template>
            <template v-else-if="column.key === 'enabled'"><Tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</Tag></template>
            <template v-else-if="column.key === 'draft_changed'"><Tag v-if="record.draft_changed" color="orange">待发布</Tag><span v-else>—</span></template>
            <template v-else-if="column.key === 'actions'">
              <Space size="small">
                <RouterLink :to="`/ui/models/virtual/${record.id}`">查看</RouterLink>
                <RouterLink v-if="canManage" :to="`/ui/models/virtual/${record.id}/edit`">编辑</RouterLink>
                <Button v-if="canManage" type="link" size="small" @click="openToggle(record as ModelAliasListItem)">{{ record.enabled ? '停用' : '启用' }}</Button>
                <Button v-if="canManage" type="link" danger size="small" @click="openDelete(record as ModelAliasListItem)">删除</Button>
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
      title="停用虚拟模型"
      :message="`确认停用「${disableTarget.row ? aliasName(disableTarget.row as ModelAliasListItem) : ''}」？停用并发布后调用返回 MODEL_ALIAS_DISABLED。`"
      :impact="disableTarget.impact"
      danger
      :loading="disableTarget.loading"
      @confirm="submitToggle"
    />
    <ConfirmDialog
      v-model:open="deleteTarget.open"
      title="删除虚拟模型"
      :message="`确认删除「${deleteTarget.row ? aliasName(deleteTarget.row as ModelAliasListItem) : ''}」？存在治理策略或 Access Credential 引用时将被拒绝。`"
      :impact="deleteTarget.impact"
      danger
      :loading="deleteTarget.loading"
      @confirm="submitDelete"
    />
  </section>
</template>

<style scoped>
.alias-filter-card,
.alias-table-card { margin-bottom: 16px; border: 1px solid var(--lai-border); box-shadow: 0 8px 24px rgba(37, 99, 235, .05); }
.alias-filter-card :deep(.ant-card-body) { padding: 14px 16px; }
.alias-table-card :deep(.ant-card-body) { padding: 0; }
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
  font-weight: 600;
  background: #f6f8fa;
}
.lai-link {
  color: #0969da;
}
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
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
