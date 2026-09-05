<script setup lang="ts">
// 可靠性策略列表（FE-021，附录 4.3.2.1）：默认策略面板只读、恢复决策抽屉（FE-022）。
import { ref } from 'vue'
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
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        可靠性策略
      </h1>
      <div class="lai-page-actions">
        <button
          type="button"
          class="lai-btn"
          @click="defaultOpen = true"
        >
          系统默认策略
        </button>
        <RouterLink
          v-if="canManage"
          to="/ui/reliability-policies/new"
          class="lai-btn lai-btn-primary"
        >
          新建可靠性策略
        </RouterLink>
      </div>
    </div>

    <div class="lai-filter-bar">
      <input
        class="lai-input lai-filter-input"
        type="text"
        placeholder="名称或 Alias"
        :value="String(query.keyword ?? '')"
        @change="applyFilters({ keyword: ($event.target as HTMLInputElement).value })"
      >
      <select
        class="lai-input lai-filter-select"
        :value="String(query.fallbackEnabled ?? '')"
        @change="applyFilters({ fallbackEnabled: ($event.target as HTMLInputElement).value })"
      >
        <option value="">
          全部 Fallback
        </option>
        <option value="true">
          允许 Fallback
        </option>
        <option value="false">
          关闭 Fallback
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
      <div class="lai-table-wrap">
        <table class="lai-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>Alias</th>
              <th>连接超时</th>
              <th>首 Token 超时</th>
              <th>总超时</th>
              <th>重试</th>
              <th>换密钥</th>
              <th>Fallback</th>
              <th>熔断窗口/阈值</th>
              <th>OPEN 时长</th>
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
                  :to="`/ui/reliability-policies/${row.id}/edit`"
                  class="lai-link"
                >
                  {{ row.name }}
                </RouterLink>
              </td>
              <td>
                <RouterLink
                  :to="`/ui/model-aliases/${row.alias_id}`"
                  class="lai-link lai-cell-mono"
                >
                  {{ row.alias }}
                </RouterLink>
              </td>
              <td>{{ row.connect_timeout_ms }} ms</td>
              <td>{{ row.first_token_timeout_ms }} ms</td>
              <td>{{ row.total_timeout_ms }} ms</td>
              <td>{{ row.max_retries }}</td>
              <td>{{ row.max_credential_failovers }}</td>
              <td>{{ row.fallback_enabled ? row.max_fallbacks : 0 }}</td>
              <td>
                <span class="lai-cell-mono">{{ row.circuit_window_seconds }}s / {{ row.circuit_min_requests }} 次</span>
                <span class="lai-cell-sub">{{ ratePercent(row.circuit_failure_rate) }}</span>
              </td>
              <td>{{ row.circuit_open_seconds }}s</td>
              <td>{{ row.enabled ? '启用' : '停用' }}</td>
              <td>{{ row.draft_changed ? '待发布' : '' }}</td>
              <td class="lai-cell-actions">
                <button
                  v-if="canViewRecovery"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openRecovery(row)"
                >
                  恢复决策
                </button>
                <RouterLink
                  v-if="canManage"
                  :to="`/ui/reliability-policies/${row.id}/edit`"
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
