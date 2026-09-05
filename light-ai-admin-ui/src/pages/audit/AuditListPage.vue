<script setup lang="ts">
import { computed, ref } from 'vue'
import PageState from '@/components/PageState.vue'
import DataTable, { type TableColumn } from '@/components/DataTable.vue'
import ListPager from '@/components/ListPager.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { Permission } from '@/app/permissions'
import { formatDateTime } from '@/app/display'
import {
  type AuditLogDetail,
  type AuditLogListItem,
  exportAuditLogs,
  fetchAuditLog,
  fetchAuditLogs,
} from '@/api/auditLogs'
import { isAbortError } from '@/api/errors'

const store = useBootstrapStore()

const canExport = computed(() => store.can(Permission.auditExport))
const canViewClientIp = computed(() => store.roles.includes('SYSTEM_ADMIN'))

const resultOptions = [
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
]

const list = useListQuery<Record<string, FilterValue>, AuditLogListItem>({
  fields: {
    audit_id: { default: '', url: true },
    request_id: { default: '', url: true },
    operator: { default: '', url: true },
    operation: { default: '', url: true },
    entity_keyword: { default: '', url: true },
    result: { default: '', url: true },
    error_code: { default: '', url: true },
    client_ip: { default: '', url: false },
  },
  defaultSort: '-created_at',
  fetcher: (params, signal) => fetchAuditLogs(params, signal),
})


const columns: TableColumn[] = [
  { key: 'created_at', label: '时间' },
  { key: 'operator_name', label: '操作人' },
  { key: 'operation', label: '操作' },
  { key: 'entity_name', label: '对象' },
  { key: 'change_summary', label: '变更字段' },
  { key: 'source_mode', label: '来源' },
  { key: 'result', label: '结果' },
  { key: 'duration_ms', label: '耗时' },
  { key: 'actions', label: '操作' },
]

const copyState = ref('')
async function copyText(value: string): Promise<void> {
  try {
    await window.navigator.clipboard.writeText(value)
    copyState.value = '已复制'
  } catch {
    copyState.value = '复制失败'
  }
  setTimeout(() => {
    copyState.value = ''
  }, 1500)
}

// —— 详情抽屉 ——
const detail = ref<AuditLogDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

async function openDetail(row: AuditLogListItem): Promise<void> {
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await fetchAuditLog(row.id)
  } catch (e) {
    detailError.value = e instanceof Error ? e.message : '详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

function closeDetail(): void {
  detail.value = null
}

function filterByError(code: string): void {
  list.applyFilters({ error_code: code })
}

// —— 导出 ——
const exportState = ref<'idle' | 'running'>('idle')
const exportError = ref('')
let exportController: AbortController | null = null

async function onExport(): Promise<void> {
  if (exportState.value === 'running') return
  exportController?.abort()
  exportController = new AbortController()
  exportState.value = 'running'
  exportError.value = ''
  try {
    await exportAuditLogs(
      {
        audit_id: (list.state.audit_id as string) || undefined,
        request_id: (list.state.request_id as string) || undefined,
        operator: (list.state.operator as string) || undefined,
        operation: (list.state.operation as string) || undefined,
        result: (list.state.result as string) || undefined,
        error_code: (list.state.error_code as string) || undefined,
      },
      exportController.signal,
    )
    exportState.value = 'idle'
  } catch (e) {
    if (isAbortError(e)) {
      exportState.value = 'idle'
      return
    }
    exportError.value = e instanceof Error ? `${e.message}（${(e as { code?: string }).code ?? ''}）` : '导出失败'
    exportState.value = 'idle'
  }
}

defineExpose({ filterByError })
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        审计日志
      </h1>
      <div class="lai-row-actions">
        <span
          v-if="copyState"
          class="lai-related-meta"
        >{{ copyState }}</span>
        <button
          v-if="canExport"
          type="button"
          class="lai-btn"
          :disabled="exportState === 'running'"
          @click="onExport"
        >
          {{ exportState === 'running' ? '导出中…' : '导出 CSV' }}
        </button>
      </div>
    </div>

    <div class="lai-filter-bar">
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="审计 ID 精确查询"
        :value="list.state.audit_id as string"
        @change="list.applyFilters({ audit_id: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="request_id 精确查询"
        :value="list.state.request_id as string"
        @change="list.applyFilters({ request_id: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="操作人"
        :value="list.state.operator as string"
        @change="list.applyFilters({ operator: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="操作类型，如 CREATE / PUBLISH"
        :value="list.state.operation as string"
        @change="list.applyFilters({ operation: ($event.target as HTMLInputElement).value.trim() })"
      >
      <input
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="对象 ID 或名称"
        :value="list.state.entity_keyword as string"
        @change="list.applyFilters({ entity_keyword: ($event.target as HTMLInputElement).value.trim() })"
      >
      <select
        class="lai-select"
        :value="list.state.result as string"
        aria-label="结果"
        @change="list.applyFilters({ result: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">
          全部结果
        </option>
        <option
          v-for="option in resultOptions"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </option>
      </select>
      <input
        v-if="canViewClientIp"
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="来源 IP（仅本页生效）"
        :value="list.state.client_ip as string"
        @change="list.applyFilters({ client_ip: ($event.target as HTMLInputElement).value.trim() })"
      >
    </div>

    <PageState
      v-if="list.status.value === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="list.status.value === 'error'"
      status="error"
      :error="list.error.value"
      @retry="list.refresh()"
    />
    <template v-else>
      <DataTable
        :columns="columns"
        :rows="list.items.value"
        :row-key="(row: AuditLogListItem) => row.id"
        :sort="list.sort.value"
        :loading="list.refreshing.value"
        @sort-change="list.applySort"
      >
        <template #created_at="{ row }">
          {{ formatDateTime(row.created_at, store.timezone) }}
        </template>
        <template #operator_name="{ row }">
          {{ row.operator_name }}
          <span class="lai-related-meta">{{ row.operator_role }}</span>
        </template>
        <template #operation="{ row }">
          {{ row.operation }}
          <span
            v-if="row.operation_reason"
            class="lai-related-meta"
          >（含原因）</span>
        </template>
        <template #entity_name="{ row }">
          {{ row.entity_name }}
          <span class="lai-related-meta">{{ row.entity_type }}</span>
        </template>
        <template #source_mode="{ row }">
          {{ row.source_mode }}
        </template>
        <template #result="{ row }">
          <span :class="{ 'lai-check-fail': row.result === 'FAILED' }">
            {{ row.result === 'SUCCEEDED' ? '成功' : '失败' }}
          </span>
          <button
            v-if="row.error_code"
            type="button"
            class="lai-btn lai-btn-text"
            @click="filterByError(row.error_code!)"
          >
            {{ row.error_code }}
          </button>
        </template>
        <template #actions="{ row }">
          <span class="lai-row-actions">
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="openDetail(row)"
            >
              详情
            </button>
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="copyText(row.request_id)"
            >
              复制请求ID
            </button>
          </span>
        </template>
      </DataTable>
      <ListPager
        :page="list.page.value"
        :page-size="list.pageSize.value"
        :total="list.total.value"
        @update:page="list.applyPage"
        @update:page-size="list.applyPageSize"
      />
    </template>

    <Teleport to="body">
      <div
        v-if="detail"
        class="lai-drawer-overlay"
        @click.self="closeDetail"
      >
        <aside
          class="lai-drawer"
          role="dialog"
          aria-label="审计详情"
        >
          <div class="lai-drawer-header">
            <h2 class="lai-card-title">
              审计详情
            </h2>
            <button
              type="button"
              class="lai-btn"
              @click="closeDetail"
            >
              关闭
            </button>
          </div>
          <div class="lai-drawer-body">
            <p
              v-if="detailError"
              class="lai-form-message-error"
              role="alert"
            >
              {{ detailError }}
            </p>
            <template v-if="detail">
              <div class="lai-summary-grid">
                <div class="lai-summary-item lai-summary-wide">
                  <span class="lai-summary-label">request_id</span>
                  <span class="lai-mono">{{ detail.request_id }}</span>
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">操作人</span>
                  {{ detail.operator_name }}（{{ detail.operator_role }}）
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">来源</span>{{ detail.source_mode }}
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">client_ip</span>{{ detail.client_ip ?? '—' }}
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">结果</span>
                  {{ detail.result }}
                  <template v-if="detail.error_code">
                    （{{ detail.error_code }}）
                  </template>
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">对象版本</span>
                  {{ detail.before_version ?? '—' }} → {{ detail.after_version ?? '—' }}
                </div>
                <div class="lai-summary-item lai-summary-wide">
                  <span class="lai-summary-label">操作原因</span>{{ detail.operation_reason ?? '—' }}
                </div>
                <div class="lai-summary-item lai-summary-wide">
                  <span class="lai-summary-label">错误摘要</span>{{ detail.error_summary ?? '—' }}
                </div>
              </div>
              <h3 class="lai-subsection-title">
                变更字段
              </h3>
              <table
                v-if="detail.changed_fields.length > 0"
                class="lai-table"
              >
                <thead>
                  <tr>
                    <th>字段</th>
                    <th>变更前</th>
                    <th>变更后</th>
                  </tr>
                </thead>
                <tbody>
                  <template
                    v-for="field in detail.changed_fields"
                    :key="field.field_name"
                  >
                    <tr v-if="field.sensitive">
                      <td
                        colspan="3"
                        class="lai-related-meta"
                      >
                        sensitive=true（已脱敏）
                      </td>
                    </tr>
                    <tr v-else>
                      <td>{{ field.field_name }}</td>
                      <td class="lai-mono">
                        {{ field.before_value ?? '（空）' }}
                      </td>
                      <td class="lai-mono">
                        {{ field.after_value ?? '（空）' }}
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
              <p
                v-else
                class="lai-related-empty"
              >
                无字段变更
              </p>
            </template>
          </div>
        </aside>
      </div>
    </Teleport>
  </section>
</template>
