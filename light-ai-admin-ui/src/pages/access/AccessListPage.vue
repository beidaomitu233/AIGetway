<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import DataTable, { type TableColumn } from '@/components/DataTable.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import TokenOnceDialog from '@/components/TokenOnceDialog.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { formatDateTime } from '@/app/display'
import {
  type AccessCredentialListItem,
  type AccessCredentialSecretResult,
  deleteAccessCredential,
  disableAccessCredential,
  enableAccessCredential,
  fetchAccessCredentials,
  rotateAccessCredential,
} from '@/api/accessCredentials'
import { useListQuery, type FilterValue } from '@/composables/useListQuery'
import { ApiError } from '@/api/errors'
import AccessFormDialog from '@/pages/access/AccessFormDialog.vue'

const store = useBootstrapStore()
const router = useRouter()

const canManage = computed(() => store.can(Permission.accessManage))
/** 页面只在 Standalone Mode 展示（附录 4.5.4.1）。 */
const isStandalone = computed(() => store.runtimeMode === 'STANDALONE_SERVER')

const accessStatusLabels: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '已停用',
  EXPIRED: '已过期',
}

const list = useListQuery<Record<string, FilterValue>, AccessCredentialListItem>({
  fields: {
    keyword: { default: '', url: true },
    application: { default: '', url: true },
    status: { default: '', url: true },
  },
  defaultSort: '-updated_at',
  fetcher: (params, signal) => fetchAccessCredentials(params, signal),
})

const statusOptions = Object.entries(accessStatusLabels).map(([value, label]) => ({ value, label }))

const columns: TableColumn[] = [
  { key: 'name', label: '名称' },
  { key: 'masked_token', label: 'Token（脱敏）' },
  { key: 'application', label: '应用' },
  { key: 'allowed_alias_count', label: 'Alias 范围' },
  { key: 'ip_rule_count', label: 'IP 限制' },
  { key: 'status', label: '状态' },
  { key: 'expires_at', label: '有效期至' },
  { key: 'rotation_generation', label: '代次' },
  { key: 'trace_count_24h', label: '24h 调用' },
  { key: 'actions', label: '操作' },
]

// —— 创建/编辑（FE-045）——
const formOpen = ref(false)
const editingId = ref<string | null>(null)

function openCreate(): void {
  editingId.value = null
  formOpen.value = true
}

function openEdit(row: AccessCredentialListItem): void {
  editingId.value = row.id
  formOpen.value = true
}

// —— Token 一次性展示（FE-046）——
const tokenDialogOpen = ref(false)
const tokenResult = ref<AccessCredentialSecretResult | null>(null)
const copyState = ref('')

function showToken(result: AccessCredentialSecretResult): void {
  tokenResult.value = result
  copyState.value = ''
  tokenDialogOpen.value = true
  void store.refreshDraftSummary()
}

async function copyToken(): Promise<void> {
  if (!tokenResult.value) return
  try {
    await window.navigator.clipboard.writeText(tokenResult.value.token_value)
    copyState.value = '已复制'
  } catch {
    copyState.value = '复制失败'
  }
  setTimeout(() => {
    copyState.value = ''
  }, 1500)
}

function closeTokenDialog(): void {
  // 关闭即清除内存中的明文（附录 4.5.4.3）
  tokenResult.value = null
  tokenDialogOpen.value = false
  list.refresh()
}

// —— 轮换（FE-046）——
const rotateOpen = ref(false)
const rotateTarget = ref<AccessCredentialListItem | null>(null)
const rotateReason = ref('')
const rotateLoading = ref(false)
const rotateError = ref('')

function requestRotate(row: AccessCredentialListItem): void {
  rotateTarget.value = row
  rotateReason.value = ''
  rotateError.value = ''
  rotateOpen.value = true
}

async function confirmRotate(): Promise<void> {
  const target = rotateTarget.value
  if (!target || rotateLoading.value) return
  rotateLoading.value = true
  rotateError.value = ''
  try {
    const result = await rotateAccessCredential(target.id, {
      version: target.version,
      reason: rotateReason.value,
    })
    rotateOpen.value = false
    showToken(result)
    list.refresh()
  } catch (e) {
    rotateError.value = e instanceof ApiError ? `${e.message}（${e.code}）` : '轮换失败'
  } finally {
    rotateLoading.value = false
  }
}

// —— 启停删除（FE-047）——
const lifecycleOpen = ref(false)
const lifecycleOperation = ref<'DISABLE' | 'DELETE'>('DISABLE')
const lifecycleTarget = ref<AccessCredentialListItem | null>(null)
const lifecycleReason = ref('')
const lifecycleLoading = ref(false)
const lifecycleError = ref('')
const actionError = ref('')

function requestDisable(row: AccessCredentialListItem): void {
  lifecycleTarget.value = row
  lifecycleOperation.value = 'DISABLE'
  lifecycleReason.value = ''
  lifecycleError.value = ''
  lifecycleOpen.value = true
}

function requestDelete(row: AccessCredentialListItem): void {
  lifecycleTarget.value = row
  lifecycleOperation.value = 'DELETE'
  lifecycleReason.value = ''
  lifecycleError.value = ''
  lifecycleOpen.value = true
}

async function confirmLifecycle(): Promise<void> {
  const target = lifecycleTarget.value
  if (!target || lifecycleLoading.value) return
  lifecycleLoading.value = true
  lifecycleError.value = ''
  try {
    if (lifecycleOperation.value === 'DISABLE') {
      await disableAccessCredential(target.id, { version: target.version, reason: lifecycleReason.value })
    } else {
      await deleteAccessCredential(target.id, { version: target.version, reason: lifecycleReason.value })
    }
    lifecycleOpen.value = false
    list.refresh()
  } catch (e) {
    lifecycleError.value = e instanceof ApiError ? `${e.message}（${e.code}）` : '操作失败'
  } finally {
    lifecycleLoading.value = false
  }
}

async function enableRow(row: AccessCredentialListItem): Promise<void> {
  actionError.value = ''
  try {
    await enableAccessCredential(row.id, row.version)
    list.refresh()
  } catch (e) {
    actionError.value = e instanceof ApiError ? `${e.message}（${e.code}）` : '操作失败'
  }
}

function expiryText(row: AccessCredentialListItem): string {
  if (!row.expires_at) return '长期有效'
  const remaining = new Date(row.expires_at).getTime() - Date.now()
  const sevenDays = 7 * 24 * 3600_000
  if (remaining > 0 && remaining < sevenDays) {
    return `${formatDateTime(row.expires_at, store.timezone)}（剩余 ${Math.ceil(remaining / 24 / 3600_000)} 天）`
  }
  return formatDateTime(row.expires_at, store.timezone)
}

function aliasText(row: AccessCredentialListItem): string {
  return row.allowed_alias_count === 0 ? '全部已发布 Alias' : String(row.allowed_alias_count)
}

function ipText(row: AccessCredentialListItem): string {
  return row.ip_rule_count === 0 ? '不限制' : String(row.ip_rule_count)
}

function goToTraces(row: AccessCredentialListItem): void {
  void router.push({ name: 'trace-list', query: { application: row.application } })
}

onMounted(() => {
  if (!isStandalone.value) return
})
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        访问凭证
      </h1>
      <button
        v-if="canManage && isStandalone"
        type="button"
        class="lai-btn lai-btn-primary"
        @click="openCreate"
      >
        创建访问凭证
      </button>
    </div>

    <PageState
      v-if="!isStandalone"
      status="empty"
      message="当前运行模式不使用 Standalone Access Credential；SDK 与嵌入模式由宿主调用上下文鉴权。"
    />
    <template v-else>
      <div class="lai-filter-bar">
        <input
          class="lai-input lai-filter-keyword"
          type="text"
          placeholder="名称或应用"
          :value="list.state.keyword as string"
          @change="list.applyFilters({ keyword: ($event.target as HTMLInputElement).value.trim() })"
        >
        <input
          class="lai-input lai-filter-keyword"
          type="text"
          placeholder="应用标识"
          :value="list.state.application as string"
          @change="list.applyFilters({ application: ($event.target as HTMLInputElement).value.trim() })"
        >
        <select
          class="lai-select"
          :value="list.state.status as string"
          aria-label="状态"
          @change="list.applyFilters({ status: ($event.target as HTMLSelectElement).value })"
        >
          <option value="">
            全部状态
          </option>
          <option
            v-for="option in statusOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </div>

      <p
        v-if="actionError"
        class="lai-form-message-error"
        role="alert"
      >
        {{ actionError }}
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
          :row-key="(row: AccessCredentialListItem) => row.id"
          :sort="list.sort.value"
          :loading="list.refreshing.value"
          @sort-change="list.applySort"
        >
          <template #name="{ row }">
            <RouterLink
              :to="{ name: 'access-detail', params: { id: row.id } }"
              class="lai-link"
            >
              {{ row.name }}
            </RouterLink>
          </template>
          <template #masked_token="{ row }">
            <span class="lai-mono">{{ row.masked_token }}</span>
          </template>
          <template #allowed_alias_count="{ row }">
            {{ aliasText(row) }}
          </template>
          <template #ip_rule_count="{ row }">
            {{ ipText(row) }}
          </template>
          <template #status="{ row }">
            {{ accessStatusLabels[row.status] ?? row.status }}
          </template>
          <template #expires_at="{ row }">
            {{ expiryText(row) }}
          </template>
          <template #trace_count_24h="{ row }">
            <button
              type="button"
              class="lai-btn lai-btn-text"
              @click="goToTraces(row)"
            >
              {{ row.trace_count_24h }}
            </button>
          </template>
          <template #actions="{ row }">
            <span class="lai-row-actions">
              <RouterLink
                :to="{ name: 'access-detail', params: { id: row.id } }"
                class="lai-btn lai-btn-text"
              >
                查看
              </RouterLink>
              <button
                v-if="canManage"
                type="button"
                class="lai-btn lai-btn-text"
                @click="openEdit(row)"
              >
                编辑
              </button>
              <button
                v-if="canManage"
                type="button"
                class="lai-btn lai-btn-text"
                @click="requestRotate(row)"
              >
                轮换
              </button>
              <button
                v-if="canManage && row.status === 'ACTIVE'"
                type="button"
                class="lai-btn lai-btn-text"
                @click="requestDisable(row)"
              >
                停用
              </button>
              <button
                v-if="canManage && row.status === 'DISABLED'"
                type="button"
                class="lai-btn lai-btn-text"
                @click="enableRow(row)"
              >
                启用
              </button>
              <button
                v-if="canManage"
                type="button"
                class="lai-btn lai-btn-text lai-row-danger"
                @click="requestDelete(row)"
              >
                删除
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

      <AccessFormDialog
        :open="formOpen"
        :access-id="editingId"
        @update:open="formOpen = $event"
        @created="showToken"
        @saved="list.refresh()"
      />

      <ConfirmDialog
        :open="rotateOpen"
        title="轮换 Token"
        :message="`将为 ${rotateTarget?.name ?? ''} 生成新 Token，旧 Token 立即失效。`"
        danger
        require-reason
        :loading="rotateLoading"
        :error-text="rotateError"
        @update:open="(value: boolean) => (rotateOpen = value)"
        @confirm="
          (payload: { reason: string }) => {
            rotateReason = payload.reason
            confirmRotate()
          }
        "
      />

      <ConfirmDialog
        :open="lifecycleOpen"
        :title="lifecycleOperation === 'DISABLE' ? '停用访问凭证' : '删除访问凭证'"
        :message="
          lifecycleOperation === 'DISABLE'
            ? '停用后新请求立即返回鉴权失败。'
            : '删除后无法恢复，历史 Trace 使用名称快照。'
        "
        :danger="lifecycleOperation === 'DELETE'"
        require-reason
        :loading="lifecycleLoading"
        :error-text="lifecycleError"
        @update:open="(value: boolean) => (lifecycleOpen = value)"
        @confirm="
          (payload: { reason: string }) => {
            lifecycleReason = payload.reason
            confirmLifecycle()
          }
        "
      />

      <TokenOnceDialog
        :open="tokenDialogOpen"
        title="Token 仅本次显示"
        :token-value="tokenResult?.token_value ?? ''"
        :issued-at="tokenResult?.issued_at ?? null"
        :rotation-generation="tokenResult?.rotation_generation ?? 0"
        :timezone="store.timezone"
        :copy-state="copyState"
        @update:open="(value: boolean) => !value && closeTokenDialog()"
        @copied="copyToken"
      />
    </template>
  </section>
</template>
