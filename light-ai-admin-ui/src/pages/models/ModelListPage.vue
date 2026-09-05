<script setup lang="ts">
// Provider Model 列表页（FE-015，附录 4.2.5.1）：筛选同步 URL，行内启停删除带影响确认，
// 勾选 1—20 个同 Provider 模型发起批量检测（FE-016）。
import { computed, ref, shallowRef } from 'vue'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import BatchCheckPanel from './BatchCheckPanel.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { connectionStatusLabel } from '@/app/display'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { fetchProviderModels } from '@/api/providerModels'
import type { ProviderModelListItem } from '@/api/providerModels'
import { useListActions } from '../listActions'

const store = useBootstrapStore()
const canManage = store.can(Permission.modelManage)
const canCheck = store.can(Permission.providerCheck)

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
  togglePath: (row) => `/provider-models/${row.id}/${row.enabled ? 'disable' : 'enable'}`,
  deletePath: (row) => `/provider-models/${row.id}`,
  impactPath: (row) => `/provider-models/${row.id}/impact`,
  reload: refresh,
})

function modelName(row: ProviderModelListItem): string {
  return `${row.display_name}（${row.model_id}）`
}

const selected = ref<ProviderModelListItem[]>([])
const selectedSameProvider = computed(
  () => new Set(selected.value.map((item) => item.provider_id)).size <= 1,
)
function onToggleSelect(row: ProviderModelListItem, checked: boolean): void {
  selected.value = checked
    ? [...selected.value, row]
    : selected.value.filter((item) => item.id !== row.id)
}
function clearSelection(): void {
  selected.value = []
}

const batchOpen = ref(false)
const batchProviderName = computed(() => selected.value[0]?.provider_name ?? '')
const credentialOptions = shallowRef<{ id: string; label: string }[]>([])

async function openBatchCheck(): Promise<void> {
  batchOpen.value = true
  // 批量检测需要同 Provider 凭证；选项加载失败时面板内可重开
  credentialOptions.value = []
}

const connectionOptions = [
  { value: '', label: '全部连接状态' },
  { value: 'UNKNOWN', label: connectionStatusLabel('UNKNOWN') },
  { value: 'AVAILABLE', label: connectionStatusLabel('AVAILABLE') },
  { value: 'UNAVAILABLE', label: connectionStatusLabel('UNAVAILABLE') },
]
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
          to="/ui/provider-models/import"
          class="lai-btn"
        >
          导入模型
        </RouterLink>
        <RouterLink
          to="/ui/provider-models/new"
          class="lai-btn lai-btn-primary"
        >
          新建模型
        </RouterLink>
      </div>
    </div>

    <div class="lai-filter-bar">
      <input
        class="lai-input lai-filter-input"
        type="text"
        placeholder="名称或模型标识"
        :value="query.keyword"
        @change="applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      >
      <select
        class="lai-input lai-filter-select"
        :value="String(query.connectionStatus ?? '')"
        @change="applyFilters({ connectionStatus: ($event.target as HTMLInputElement).value })"
      >
        <option
          v-for="item in connectionOptions"
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
      <span
        v-if="refreshing"
        class="lai-refreshing"
      >刷新中…</span>
    </div>

    <div
      v-if="canCheck && selected.length > 0"
      class="lai-selection-bar"
    >
      <span>
        已选 {{ selected.length }} 个模型
        <template v-if="!selectedSameProvider">（必须为同一 Provider）</template>
        <template v-else-if="selected.length > 20">（最多 20 个）</template>
      </span>
      <button
        v-if="selectedSameProvider && selected.length <= 20"
        type="button"
        class="lai-btn"
        @click="openBatchCheck"
      >
        批量检测
      </button>
      <button
        type="button"
        class="lai-btn lai-btn-text"
        @click="clearSelection"
      >
        清除选择
      </button>
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
      <div class="lai-table-wrap">
        <table class="lai-table">
          <thead>
            <tr>
              <th
                v-if="canCheck"
                class="lai-col-check"
              >
                <span class="lai-visually-hidden">选择</span>
              </th>
              <th>模型</th>
              <th>Provider</th>
              <th>上下文</th>
              <th>最大输出</th>
              <th>流式</th>
              <th>价格（输入/输出）</th>
              <th>连接状态</th>
              <th>候选</th>
              <th>启停</th>
              <th>待发布</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in items"
              :key="row.id"
            >
              <td v-if="canCheck">
                <input
                  type="checkbox"
                  :checked="selected.some((item) => item.id === row.id)"
                  :aria-label="`选择 ${row.display_name}`"
                  @change="onToggleSelect(row, ($event.target as HTMLInputElement).checked)"
                >
              </td>
              <td>
                <RouterLink
                  :to="`/ui/provider-models/${row.id}`"
                  class="lai-link"
                >
                  {{ row.display_name }}
                </RouterLink>
                <span class="lai-cell-mono lai-cell-sub">{{ row.model_id }}</span>
              </td>
              <td>{{ row.provider_name }}</td>
              <td>{{ row.context_window?.toLocaleString('zh-CN') ?? '待补充' }}</td>
              <td>{{ row.max_output_tokens?.toLocaleString('zh-CN') ?? '待补充' }}</td>
              <td>{{ row.support_stream == null ? '待补充' : row.support_stream ? '支持' : '不支持' }}</td>
              <td>
                <span class="lai-cell-mono">{{ row.input_price }} / {{ row.output_price }}</span>
                <span class="lai-cell-sub">每 {{ row.price_unit }} tokens · {{ row.currency }}</span>
              </td>
              <td>
                {{ connectionStatusLabel(row.connection_status) }}
                <span class="lai-cell-sub">{{ row.last_check_at ?? '未检测' }}</span>
              </td>
              <td>{{ row.route_candidate_count }}</td>
              <td>{{ row.enabled ? '启用' : '停用' }}</td>
              <td>{{ row.draft_changed ? '待发布' : '' }}</td>
              <td class="lai-cell-actions">
                <RouterLink
                  :to="`/ui/provider-models/${row.id}`"
                  class="lai-btn lai-btn-text"
                >
                  查看
                </RouterLink>
                <RouterLink
                  v-if="canManage"
                  :to="`/ui/provider-models/${row.id}/edit`"
                  class="lai-btn lai-btn-text"
                >
                  编辑
                </RouterLink>
                <button
                  v-if="canManage"
                  type="button"
                  class="lai-btn lai-btn-text"
                  :disabled="busyId === row.id"
                  @click="openToggle(row)"
                >
                  {{ row.enabled ? '停用' : '启用' }}
                </button>
                <button
                  v-if="canManage"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openDelete(row)"
                >
                  删除
                </button>
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
    <BatchCheckPanel
      v-model:open="batchOpen"
      :models="selected.map((item) => ({ id: item.id, label: modelName(item) }))"
      :credential-options="credentialOptions"
      :provider-name="batchProviderName"
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
