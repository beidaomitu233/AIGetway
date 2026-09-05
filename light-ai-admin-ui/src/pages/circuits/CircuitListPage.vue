<script setup lang="ts">
// 熔断状态列表（FE-023，附录 4.3.3.1）：OPEN/HALF_OPEN 排序靠前（服务端默认排序），
// 凭证列仅管理员/运维展示（响应不含 credential_id 时显示“受限凭证”）。
import { useRouter } from 'vue-router'
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

function stateClass(row: CircuitStateListItem): string {
  return row.state === 'OPEN' ? 'lai-state-open' : row.state === 'HALF_OPEN' ? 'lai-state-half' : 'lai-state-closed'
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
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        熔断状态
      </h1>
    </div>

    <div class="lai-filter-bar">
      <select
        class="lai-input lai-filter-select"
        :value="String(query.state ?? '')"
        @change="applyFilters({ state: ($event.target as HTMLInputElement).value })"
      >
        <option
          v-for="item in stateOptions"
          :key="item.value"
          :value="item.value"
        >
          {{ item.label }}
        </option>
      </select>
      <select
        class="lai-input lai-filter-select"
        :value="String(query.openSource ?? '')"
        @change="applyFilters({ openSource: ($event.target as HTMLInputElement).value })"
      >
        <option value="">
          全部来源
        </option>
        <option value="AUTO">
          {{ openSourceLabel('AUTO') }}
        </option>
        <option value="MANUAL">
          {{ openSourceLabel('MANUAL') }}
        </option>
      </select>
      <label class="lai-switch">
        <input
          type="checkbox"
          :checked="query.hasRecentFailure === 'true'"
          @change="applyFilters({ hasRecentFailure: ($event.target as HTMLInputElement).checked ? 'true' : '' })"
        >
        仅显示当前窗口有失败
      </label>
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
      <div class="lai-table-wrap">
        <table class="lai-table">
          <thead>
            <tr>
              <th>Provider</th>
              <th>模型</th>
              <th>Credential</th>
              <th>状态</th>
              <th>来源</th>
              <th>窗口样本</th>
              <th>失败率</th>
              <th>打开时间</th>
              <th>下次探测</th>
              <th>最近错误</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in items"
              :key="row.id"
            >
              <td>{{ row.provider_name }}</td>
              <td>
                <RouterLink
                  :to="`/ui/provider-models/${row.provider_model_id}`"
                  class="lai-link"
                >
                  {{ row.provider_model_name }}
                </RouterLink>
              </td>
              <td class="lai-cell-mono">
                {{ credentialText(row) }}
              </td>
              <td>
                <span :class="stateClass(row)">{{ circuitStateLabel(row.state) }}</span>
                <span
                  v-if="row.state === 'HALF_OPEN'"
                  class="lai-cell-sub"
                >探测中 {{ row.half_open_in_flight }}（成功 {{ row.half_open_success_count }}）</span>
              </td>
              <td>{{ row.open_source ? openSourceLabel(row.open_source) : '' }}</td>
              <td>{{ row.sample_count }}（失败 {{ row.failure_count }}）</td>
              <td>
                {{ rateText(row) }}
                <span
                  v-if="row.sample_count < 1"
                  class="lai-cell-sub"
                >样本不足</span>
              </td>
              <td>{{ row.opened_at ?? '' }}</td>
              <td>{{ row.next_probe_at ?? '' }}</td>
              <td>
                <button
                  v-if="row.last_error_code"
                  type="button"
                  class="lai-btn lai-btn-text lai-cell-mono"
                  @click="goTraces(row)"
                >
                  {{ row.last_error_code }}
                </button>
                <span v-else>—</span>
              </td>
              <td class="lai-cell-actions">
                <RouterLink
                  :to="`/ui/circuits/${row.id}`"
                  class="lai-btn lai-btn-text"
                >
                  查看详情
                </RouterLink>
                <RouterLink
                  v-if="canOperate"
                  :to="`/ui/circuits/${row.id}`"
                  class="lai-btn lai-btn-text"
                >
                  操作
                </RouterLink>
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
