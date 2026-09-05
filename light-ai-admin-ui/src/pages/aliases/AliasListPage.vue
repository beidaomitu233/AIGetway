<script setup lang="ts">
// Model Alias 列表页（FE-017，附录 4.2.7.1）。
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import type { ModelAliasListItem } from '@/api/modelAliases'
import { fetchModelAliases } from '@/api/modelAliases'
import { useListActions } from '../listActions'

const store = useBootstrapStore()
const canManage = store.can(Permission.aliasManage)

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
  togglePath: (row) => `/model-aliases/${row.id}/${row.enabled ? 'disable' : 'enable'}`,
  deletePath: (row) => `/model-aliases/${row.id}`,
  impactPath: (row) => `/model-aliases/${row.id}/impact`,
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

const aliasName = (row: ModelAliasListItem) => row.alias
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        模型别名
      </h1>
      <div
        v-if="canManage"
        class="lai-page-actions"
      >
        <RouterLink
          to="/ui/model-aliases/new"
          class="lai-btn lai-btn-primary"
        >
          新建 Model Alias
        </RouterLink>
      </div>
    </div>

    <div class="lai-filter-bar">
      <input
        class="lai-input lai-filter-input"
        type="text"
        placeholder="alias、名称或描述"
        :value="query.keyword"
        @change="applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      >
      <select
        class="lai-input lai-filter-select"
        :value="String(query.enabled ?? '')"
        @change="applyFilters({ enabled: ($event.target as HTMLInputElement).value })"
      >
        <option value="">
          全部启停
        </option>
        <option value="true">
          已启用
        </option>
        <option value="false">
          已停用
        </option>
      </select>
      <select
        class="lai-input lai-filter-select"
        :value="String(query.runtimeAvailability ?? '')"
        @change="applyFilters({ runtimeAvailability: ($event.target as HTMLInputElement).value })"
      >
        <option
          v-for="item in runtimeOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </option>
      </select>
      <select
        class="lai-input lai-filter-select"
        :value="String(query.supportStream ?? '')"
        @change="applyFilters({ supportStream: ($event.target as HTMLInputElement).value })"
      >
        <option value="">
          全部流式
        </option>
        <option value="true">
          支持流式
        </option>
        <option value="false">
          不支持流式
        </option>
      </select>
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
      message="没有匹配的模型别名"
    />
    <template v-else>
      <p
        v-if="actionText()"
        class="lai-form-message-error"
        role="alert"
      >
        {{ actionText() }}
      </p>
      <div class="lai-table-wrap">
        <table class="lai-table">
          <thead>
            <tr>
              <th>alias</th>
              <th>展示名称</th>
              <th>路由策略</th>
              <th>候选</th>
              <th>可用性</th>
              <th>流式</th>
              <th>24h 调用</th>
              <th>启停</th>
              <th>待发布</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in items"
              :key="row.id"
            >
              <td>
                <RouterLink
                  :to="`/ui/model-aliases/${row.id}`"
                  class="lai-link lai-cell-mono"
                >
                  {{ row.alias }}
                </RouterLink>
              </td>
              <td>{{ row.display_name }}</td>
              <td>{{ row.route_strategy }}</td>
              <td>
                <RouterLink
                  :to="`/ui/model-aliases/${row.id}`"
                  class="lai-link"
                >
                  {{ row.candidate_count }}
                </RouterLink>
              </td>
              <td>{{ runtimeText(row) }}</td>
              <td>{{ streamText(row) }}</td>
              <td>{{ row.request_count_24h }}</td>
              <td>{{ row.enabled ? '启用' : '停用' }}</td>
              <td>{{ row.draft_changed ? '待发布' : '' }}</td>
              <td>{{ row.updated_at }}</td>
              <td class="lai-cell-actions">
                <RouterLink
                  :to="`/ui/model-aliases/${row.id}`"
                  class="lai-btn lai-btn-text"
                >
                  查看
                </RouterLink>
                <RouterLink
                  v-if="canManage"
                  :to="`/ui/model-aliases/${row.id}/edit`"
                  class="lai-btn lai-btn-text"
                >
                  编辑
                </RouterLink>
                <template v-if="canManage">
                  <button
                    type="button"
                    class="lai-btn lai-btn-text"
                    @click="openToggle(row)"
                  >
                    {{ row.enabled ? '停用' : '启用' }}
                  </button>
                  <button
                    type="button"
                    class="lai-btn lai-btn-text"
                    @click="openDelete(row)"
                  >
                    删除
                  </button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
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
      title="停用模型别名"
      :message="`确认停用「${disableTarget.row ? aliasName(disableTarget.row as ModelAliasListItem) : ''}」？停用并发布后调用返回 MODEL_ALIAS_DISABLED。`"
      :impact="disableTarget.impact"
      danger
      :loading="disableTarget.loading"
      @confirm="submitToggle"
    />
    <ConfirmDialog
      v-model:open="deleteTarget.open"
      title="删除模型别名"
      :message="`确认删除「${deleteTarget.row ? aliasName(deleteTarget.row as ModelAliasListItem) : ''}」？存在治理策略或 Access Credential 引用时将被拒绝。`"
      :impact="deleteTarget.impact"
      danger
      :loading="deleteTarget.loading"
      @confirm="submitDelete"
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
