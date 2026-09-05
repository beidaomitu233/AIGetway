<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import DataTable, { type TableColumn } from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import StatusText from '@/components/StatusText.vue'
import AppMultiSelect from '@/components/AppMultiSelect.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { useLifecycleActions } from '@/composables/useLifecycleActions'
import { connectionStatusLabels, formatDateTime } from '@/app/display'
import { Permission } from '@/app/permissions'
import {
  type ProviderListItem,
  disableProvider,
  enableProvider,
  deleteProvider,
  getProviderImpact,
  listProviders,
} from '@/api/providers'

const store = useBootstrapStore()
const router = useRouter()

const canManage = computed(() => store.can(Permission.providerManage))
const canCheck = computed(() => store.can(Permission.providerCheck))

const list = useListQuery<Record<string, FilterValue>, ProviderListItem>({
  fields: {
    keyword: { default: '', url: true },
    type: { default: [], url: true },
    connection_status: { default: [], url: true },
    enabled: { default: '', url: true },
    draft_changed: { default: '', url: true },
  },
  defaultSort: 'updated_at',
  fetcher: (params, signal) => listProviders(params, signal),
})

const keywordInput = computed<string>({
  get: () => (list.state.keyword as string) || '',
  set: (value) => {
    // 2—64 字符或清空时触发查询，中间输入不发起请求。
    if (value === '' || (value.length >= 2 && value.length <= 64)) {
      list.applyFilters({ keyword: value })
    }
  },
})

const typeFilter = computed<string[]>({
  get: () => (list.state.type as string[]) || [],
  set: (value) => list.applyFilters({ type: value }),
})

const typeOptions = computed(() =>
  store.adapters.map((adapter) => ({ value: adapter.provider_type, label: adapter.provider_type })),
)
const connectionStatusOptions = Object.entries(connectionStatusLabels).map(([value, label]) => ({
  value,
  label,
}))

const columns: TableColumn[] = [
  { key: 'name', label: '名称' },
  { key: 'type', label: '类型' },
  { key: 'base_url', label: '服务地址' },
  { key: 'connection_status', label: '连接状态' },
  { key: 'provider_model_count', label: '模型数' },
  { key: 'credential_pool_count', label: '凭证池数' },
  { key: 'last_check_at', label: '最近检测' },
  { key: 'enabled', label: '启用' },
  { key: 'draft_changed', label: '变更' },
  { key: 'actions', label: '操作' },
]

const lifecycle = useLifecycleActions({
  getImpact: getProviderImpact,
  enable: (id, version) => enableProvider(id, version),
  disable: (id, version, confirmed) => disableProvider(id, version, confirmed),
  remove: (id, version, confirmed) => deleteProvider(id, version, confirmed),
  onChanged: () => {
    list.refresh()
    void store.refreshDraftSummary()
  },
})

function onToggleEnabled(row: ProviderListItem): void {
  if (row.enabled) {
    void lifecycle.requestDisable(row.id, row.version)
  } else {
    void lifecycle.enable(row.id, row.version)
  }
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        Provider
      </h1>
      <button
        v-if="canManage"
        type="button"
        class="lai-btn lai-btn-primary"
        @click="router.push({ name: 'provider-new' })"
      >
        新建 Provider
      </button>
    </div>

    <div class="lai-filter-bar">
      <input
        v-model="keywordInput"
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="名称或服务地址，输入 2—64 字符查询"
      >
      <AppMultiSelect
        v-model="typeFilter"
        :options="typeOptions"
        placeholder="全部类型"
      />
      <AppMultiSelect
        :model-value="(list.state.connection_status as string[]) || []"
        :options="connectionStatusOptions"
        placeholder="连接状态"
        @update:model-value="list.applyFilters({ connection_status: $event })"
      />
      <select
        class="lai-select"
        :value="list.state.enabled as string"
        aria-label="启用状态"
        @change="list.applyFilters({ enabled: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">
          全部
        </option>
        <option value="true">
          启用
        </option>
        <option value="false">
          停用
        </option>
      </select>
      <select
        class="lai-select"
        :value="list.state.draft_changed as string"
        aria-label="变更状态"
        @change="list.applyFilters({ draft_changed: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">
          全部
        </option>
        <option value="true">
          存在未发布变更
        </option>
        <option value="false">
          已发布一致
        </option>
      </select>
    </div>

    <p
      v-if="lifecycle.actionError"
      class="lai-form-message-error"
      role="alert"
    >
      {{ lifecycle.actionError }}
    </p>

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
        :row-key="(row: ProviderListItem) => row.id"
        :sort="list.sort.value"
        :loading="list.refreshing.value"
        @sort-change="list.applySort"
      >
        <template #name="{ row }">
          <RouterLink
            :to="{ name: 'provider-detail', params: { id: row.id } }"
            class="lai-link"
          >
            {{ row.name }}
          </RouterLink>
        </template>
        <template #base_url="{ row }">
          <span
            class="lai-ellipsis"
            :title="row.base_url"
          >{{ row.base_url }}</span>
        </template>
        <template #connection_status="{ row }">
          <StatusText
            :value="row.connection_status"
            :labels="connectionStatusLabels"
            placeholder="未检测"
          />
        </template>
        <template #provider_model_count="{ row }">
          <RouterLink
            :to="{ name: 'model-list', query: { provider_id: row.id } }"
            class="lai-link"
          >
            {{ row.provider_model_count }}
          </RouterLink>
        </template>
        <template #credential_pool_count="{ row }">
          <RouterLink
            :to="{ name: 'pool-list', query: { provider_id: row.id } }"
            class="lai-link"
          >
            {{ row.credential_pool_count }}
          </RouterLink>
        </template>
        <template #last_check_at="{ row }">
          {{ formatDateTime(row.last_check_at, store.timezone, '未检测') }}
        </template>
        <template #enabled="{ row }">
          {{ row.enabled ? '启用' : '停用' }}
        </template>
        <template #draft_changed="{ row }">
          <RouterLink
            v-if="row.draft_changed"
            to="/ui/config/drafts"
            class="lai-link"
          >
            待发布
          </RouterLink>
          <template v-else>
            —
          </template>
        </template>
        <template #actions="{ row }">
          <span class="lai-row-actions">
            <RouterLink
              :to="{ name: 'provider-detail', params: { id: row.id } }"
              class="lai-btn lai-btn-text"
            >
              查看
            </RouterLink>
            <button
              v-if="canManage"
              type="button"
              class="lai-btn lai-btn-text"
              @click="router.push({ name: 'provider-edit', params: { id: row.id } })"
            >
              编辑
            </button>
            <button
              v-if="canCheck"
              type="button"
              class="lai-btn lai-btn-text"
              @click="router.push({ name: 'provider-detail', params: { id: row.id }, query: { check: '1' } })"
            >
              检测
            </button>
            <button
              v-if="canManage && !lifecycle.isBusy(row.id)"
              type="button"
              class="lai-btn lai-btn-text"
              @click="onToggleEnabled(row)"
            >
              {{ row.enabled ? '停用' : '启用' }}
            </button>
            <button
              v-if="canManage"
              type="button"
              class="lai-btn lai-btn-text lai-row-danger"
              @click="lifecycle.requestDelete(row.id, row.version)"
            >
              删除
            </button>
          </span>
        </template>
      </DataTable>
      <Pagination
        :page="list.page.value"
        :page-size="list.pageSize.value"
        :total="list.total.value"
        @page-change="list.applyPage"
        @page-size-change="list.applyPageSize"
      />
    </template>

    <ConfirmDialog
      :open="lifecycle.dialog.open"
      :title="lifecycle.dialog.title"
      :message="lifecycle.dialog.message"
      :impact="lifecycle.dialog.impact?.references"
      :blockers="lifecycle.dialog.impact?.blockers"
      :error-text="lifecycle.dialog.errorText"
      :danger="lifecycle.dialog.operation === 'DELETE'"
      :loading="lifecycle.dialog.loading || lifecycle.dialog.submitting"
      @update:open="(value: boolean) => !value && lifecycle.closeDialog()"
      @confirm="lifecycle.confirmImpact()"
    />
  </section>
</template>
