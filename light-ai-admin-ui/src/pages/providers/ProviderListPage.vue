<script setup lang="ts">
import { computed } from 'vue'
import { Button, Card, Input, Select } from 'ant-design-vue'
import { resourceHost } from '@/utils/resourceValidation'
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
    provider_type: { default: [], url: true },
    health: { default: [], url: true },
    status: { default: '', url: true },
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

const providerTypeFilter = computed<string[]>({
  get: () => (list.state.provider_type as string[]) || [],
  set: (value) => list.applyFilters({ provider_type: value }),
})

const typeOptions = computed(() =>
  store.adapters.map((adapter) => ({ value: adapter.provider_type, label: adapter.provider_type })),
)
const healthOptions = Object.entries(connectionStatusLabels).map(([value, label]) => ({
  value,
  label,
}))

const columns: TableColumn[] = [
  { key: 'name', label: '名称' },
  { key: 'provider_type', label: '类型' },
  { key: 'base_url', label: '服务地址' },
  { key: 'health', label: '健康状态' },
  { key: 'upstream_model_count', label: '模型数' },
  { key: 'credential_count', label: '渠道 Key' },
  { key: 'last_checked_at', label: '最近检测' },
  { key: 'status', label: '配置状态' },
  { key: 'actions', label: '操作' },
]

const lifecycle = useLifecycleActions({
  getImpact: getProviderImpact,
  enable: (id, version) => enableProvider(id, version),
  disable: (id, version, confirmed) => disableProvider(id, version, confirmed),
  remove: (id, version, confirmed) => deleteProvider(id, version, confirmed),
  onChanged: () => {
    list.refresh()
  },
})

function onToggleStatus(row: ProviderListItem): void {
  if (!canManage.value) return
  if (row.status === 'ACTIVE') {
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
        渠道
      </h1>
      <Button
        v-if="canManage"
        type="primary"
        html-type="button"
        @click="router.push({ name: 'provider-new' })"
      >
        新建 渠道
      </Button>
    </div>

    <Card :bordered="false" class="provider-filter-card">
      <Input
        v-model:value="keywordInput"
        class="lai-filter-input"
        type="text"
        placeholder="名称或服务地址，输入 2—64 字符查询"
      />
      <AppMultiSelect
        v-model="providerTypeFilter"
        :options="typeOptions"
        placeholder="全部类型"
      />
      <AppMultiSelect
        :model-value="(list.state.health as string[]) || []"
        :options="healthOptions"
        placeholder="健康状态"
        @update:model-value="list.applyFilters({ health: $event })"
      />
      <Select
        class="lai-filter-select"
        :value="(list.state.status as string) === '' ? undefined : (list.state.status as string)"
        aria-label="配置状态"
        :options="[
          { value: 'ACTIVE', label: '启用' },
          { value: 'DISABLED', label: '停用' },
        ]"
        placeholder="全部"
        allow-clear
        @change="(value) => list.applyFilters({ status: String(value ?? '') })"
      />
    </Card>

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
      <Card :bordered="false" class="provider-table-card">
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
            :title="resourceHost(row.base_url)"
          >{{ resourceHost(row.base_url) }}</span>
        </template>
        <template #health="{ row }">
          <StatusText
            :value="row.health"
            :labels="connectionStatusLabels"
            placeholder="未知"
          />
        </template>
        <template #upstream_model_count="{ row }">
          {{ row.upstream_model_count }}
        </template>
        <template #channel_keys="{ row }">
          <RouterLink
            :to="{ name: 'provider-detail', params: { id: row.id } }"
            class="lai-link"
          >
            管理 Key
          </RouterLink>
        </template>
        <template #last_checked_at="{ row }">
          {{ formatDateTime(row.last_checked_at, store.timezone, '未检测') }}
        </template>
        <template #status="{ row }">
          {{ row.status === 'ACTIVE' ? '启用' : row.status === 'DISABLED' ? '停用' : row.status }}
        </template>
        <template #actions="{ row }">
          <span class="lai-row-actions">
            <RouterLink
              :to="{ name: 'provider-detail', params: { id: row.id } }"
              class="lai-link"
            >
              查看
            </RouterLink>
            <Button
              v-if="canManage"
              type="link"
              @click="router.push({ name: 'provider-edit', params: { id: row.id } })"
            >
              编辑
            </Button>
            <Button
              v-if="canCheck"
              type="link"
              @click="router.push({ name: 'provider-detail', params: { id: row.id }, query: { check: '1' } })"
            >
              检测
            </Button>
            <Button
              v-if="canManage && !lifecycle.isBusy(row.id)"
              type="link"
              @click="onToggleStatus(row)"
            >
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </Button>
            <Button
              v-if="canManage"
              type="link"
              danger
              @click="lifecycle.requestDelete(row.id, row.version)"
            >
              删除
            </Button>
          </span>
        </template>
      </DataTable>
      </Card>
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

<style scoped>
.provider-filter-card,
.provider-table-card { margin-bottom: 16px; border: 1px solid var(--lai-border); box-shadow: 0 8px 24px rgba(37, 99, 235, .05); }
.provider-filter-card :deep(.ant-card-body) { padding: 14px 16px; }
.provider-table-card :deep(.ant-card-body) { padding: 0; }
</style>
