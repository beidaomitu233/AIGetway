<script setup lang="ts">
// 限流策略列表（FE-019，附录 4.3.1.1）：实时用量列 5 秒刷新、启用要求至少一个上限、
// 删除需确认；查看排队打开只读用量与队列抽屉（FE-020）。
import { onBeforeUnmount, onMounted, ref } from 'vue'
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
  if (row.scope_type === 'MODEL_ALIAS') return `/ui/model-aliases/${row.scope_id}`
  if (row.scope_type === 'PROVIDER_MODEL') return `/ui/provider-models/${row.scope_id}`
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
          class="lai-btn lai-btn-primary"
        >
          新建限流策略
        </RouterLink>
      </div>
    </div>

    <div class="lai-filter-bar">
      <input
        class="lai-input lai-filter-input"
        type="text"
        placeholder="名称或作用对象"
        :value="String(query.keyword ?? '')"
        @change="applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      >
      <select
        class="lai-input lai-filter-select"
        :value="String(query.scopeType ?? '')"
        @change="applyFilters({ scopeType: ($event.target as HTMLInputElement).value })"
      >
        <option
          v-for="item in scopeOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </option>
      </select>
      <select
        class="lai-input lai-filter-select"
        :value="String(query.overflowStrategy ?? '')"
        @change="applyFilters({ overflowStrategy: ($event.target as HTMLInputElement).value })"
      >
        <option
          v-for="item in overflowOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
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
      <div class="lai-table-wrap">
        <table class="lai-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>范围</th>
              <th>作用对象</th>
              <th>RPM</th>
              <th>TPM（预占+确认）</th>
              <th>并发</th>
              <th>溢出策略</th>
              <th>窗口复位</th>
              <th>计数存储</th>
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
              <td>
                <RouterLink
                  :to="`/ui/limit-policies/${row.id}/edit`"
                  class="lai-link"
                >
                  {{ row.name }}
                </RouterLink>
              </td>
              <td>{{ scopeTypeLabel(row.scope_type) }}</td>
              <td>
                <RouterLink
                  :to="scopeLink(row)"
                  class="lai-link"
                >
                  {{ row.scope_name }}
                </RouterLink>
              </td>
              <td>
                <span class="lai-cell-mono">{{ row.rpm_limit == null ? '不限制' : `${row.rpm_used} / ${row.rpm_limit}` }}</span>
                <span class="lai-cell-sub">{{ row.rpm_limit == null ? '' : percent(row.rpm_used, row.rpm_limit) }}</span>
              </td>
              <td>
                <span class="lai-cell-mono">{{ tpmTotal(row) }}</span>
                <span class="lai-cell-sub">{{ row.tpm_limit == null ? '' : percent(row.tpm_reserved + row.tpm_confirmed, row.tpm_limit) }}</span>
              </td>
              <td>
                <span class="lai-cell-mono">{{ row.concurrent_limit == null ? '不限制' : `${row.concurrency_used} / ${row.concurrent_limit}` }}</span>
                <span class="lai-cell-sub">{{ row.concurrent_limit == null ? '' : percent(row.concurrency_used, row.concurrent_limit) }}</span>
              </td>
              <td>
                {{ overflowStrategyLabel(row.overflow_strategy) }}
                <span
                  v-if="row.overflow_strategy === 'QUEUE'"
                  class="lai-cell-sub"
                >排队 {{ row.queue_length }} / {{ row.queue_max_size ?? '—' }}</span>
              </td>
              <td>{{ row.window_end ? '窗口中' : '—' }}</td>
              <td>
                <span :class="{ 'lai-store-warn': row.counter_store_status !== 'OK' }">
                  {{ counterStoreStatusLabel(row.counter_store_status) }}
                </span>
              </td>
              <td>{{ row.enabled ? '启用' : '停用' }}</td>
              <td>{{ row.draft_changed ? '待发布' : '' }}</td>
              <td class="lai-cell-actions">
                <button
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openUsage(row)"
                >
                  查看排队
                </button>
                <RouterLink
                  :to="`/ui/limit-policies/${row.id}/edit`"
                  class="lai-btn lai-btn-text"
                >
                  编辑
                </RouterLink>
                <template v-if="canManage">
                  <button
                    type="button"
                    class="lai-btn lai-btn-text"
                    :disabled="busyId === row.id"
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
