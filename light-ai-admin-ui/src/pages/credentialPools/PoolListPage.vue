<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
import { poolStatusLabels, selectionStrategyLabels } from '@/app/display'
import { Permission } from '@/app/permissions'
import {
  type CredentialPoolListItem,
  deletePool,
  disablePool,
  enablePool,
  getPoolImpact,
  listPools,
} from '@/api/credentialPools'
import { listProviders } from '@/api/providers'

const store = useBootstrapStore()
const router = useRouter()

const canManage = computed(() => store.can(Permission.credentialManage))

const list = useListQuery<Record<string, FilterValue>, CredentialPoolListItem>({
  fields: {
    keyword: { default: '', url: true },
    provider_id: { default: [], url: true },
    status: { default: [], url: true },
    enabled: { default: '', url: true },
  },
  defaultSort: 'updated_at',
  fetcher: (params, signal) => listPools(params, signal),
})

const keywordInput = computed<string>({
  get: () => (list.state.keyword as string) || '',
  set: (value) => {
    if (value.length >= 2 || value === '') {
      list.applyFilters({ keyword: value })
    }
  },
})

const providerFilter = computed<string[]>({
  get: () => (list.state.provider_id as string[]) || [],
  set: (value) => list.applyFilters({ provider_id: value }),
})

const providerOptions = ref<Array<{ value: string; label: string }>>([])
onMounted(async () => {
  try {
    const result = await listProviders({ page: 1, page_size: 100 }, new AbortController().signal)
    providerOptions.value = result.items.map((provider) => ({
      value: provider.id,
      label: provider.name,
    }))
  } catch {
    providerOptions.value = []
  }
})

const statusOptions = Object.entries(poolStatusLabels).map(([value, label]) => ({ value, label }))

const columns: TableColumn[] = [
  { key: 'name', label: '名称' },
  { key: 'provider_name', label: 'Provider' },
  { key: 'selection_strategy', label: '选择策略' },
  { key: 'credential_total', label: '凭证总数' },
  { key: 'credential_available', label: '可用凭证' },
  { key: 'current_concurrency', label: '当前并发' },
  { key: 'rpm_used', label: 'RPM 已用' },
  { key: 'tpm_used', label: 'TPM 已用' },
  { key: 'status', label: '状态' },
  { key: 'draft_changed', label: '变更' },
  { key: 'actions', label: '操作' },
]

const lifecycle = useLifecycleActions({
  getImpact: getPoolImpact,
  enable: (id, version) => enablePool(id, version),
  disable: (id, version, confirmed) => disablePool(id, version, confirmed),
  remove: (id, version, confirmed) => deletePool(id, version, confirmed),
  onChanged: () => {
    list.refresh()
    void store.refreshDraftSummary()
  },
})

function onToggleEnabled(row: CredentialPoolListItem): void {
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
        凭证池
      </h1>
      <button
        v-if="canManage"
        type="button"
        class="lai-btn lai-btn-primary"
        @click="router.push({ name: 'pool-new' })"
      >
        新建凭证池
      </button>
    </div>

    <div class="lai-filter-bar">
      <input
        v-model="keywordInput"
        class="lai-input lai-filter-keyword"
        type="text"
        placeholder="凭证池名称，输入 2 字符以上查询"
      >
      <AppMultiSelect
        v-model="providerFilter"
        :options="providerOptions"
        placeholder="全部 Provider"
      />
      <AppMultiSelect
        :model-value="(list.state.status as string[]) || []"
        :options="statusOptions"
        placeholder="状态"
        @update:model-value="list.applyFilters({ status: $event })"
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
        :row-key="(row: CredentialPoolListItem) => row.id"
        :sort="list.sort.value"
        :loading="list.refreshing.value"
        @sort-change="list.applySort"
      >
        <template #name="{ row }">
          <RouterLink
            :to="{ name: 'pool-detail', params: { id: row.id } }"
            class="lai-link"
          >
            {{ row.name }}
          </RouterLink>
        </template>
        <template #provider_name="{ row }">
          <RouterLink
            :to="{ name: 'provider-detail', params: { id: row.provider_id } }"
            class="lai-link"
          >
            {{ row.provider_name }}
          </RouterLink>
        </template>
        <template #selection_strategy="{ row }">
          {{ selectionStrategyLabels[row.selection_strategy] ?? row.selection_strategy }}
        </template>
        <template #status="{ row }">
          <StatusText
            :value="row.status"
            :labels="poolStatusLabels"
          />
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
              :to="{ name: 'pool-detail', params: { id: row.id } }"
              class="lai-btn lai-btn-text"
            >
              查看
            </RouterLink>
            <button
              v-if="canManage"
              type="button"
              class="lai-btn lai-btn-text"
              @click="router.push({ name: 'pool-edit', params: { id: row.id } })"
            >
              编辑
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
