<script setup lang="ts">
// 凭证池详情的 Credential 管理区域（FE-013/014）。
// 自包含数据流：列表查询、10 秒运行态刷新、增改、轮换、检测、启停、删除；
// 池详情页只需提供 poolId/providerId 与权限标记并挂载本组件。
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef } from 'vue'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import CheckCommandDialog from '@/components/CheckCommandDialog.vue'
import CredentialFormDialog from './CredentialFormDialog.vue'
import CredentialRotateDialog from './CredentialRotateDialog.vue'
import {
  checkCredential,
  createCredential,
  deleteCredential,
  disableCredential,
  enableCredential,
  fetchCredentials,
  rotateCredential,
  updateCredential,
  type CredentialListItem,
  type ProviderCheckRecord,
} from '@/api/credentials'
import { fetchProviderModels } from '@/api/providerModels'
import { ApiError, isAbortError, type ApiError as ApiErrorType } from '@/api/errors'
import { healthStatusLabel, secretSourceLabel } from '@/app/display'

const props = withDefaults(
  defineProps<{
    poolId: string
    providerId?: string
    canManage?: boolean
    canCheck?: boolean
  }>(),
  {
    providerId: '',
    canManage: false,
    canCheck: false,
  },
)

const listState = reactive({ healthStatus: '', enabled: '', page: 1, pageSize: 20 })
const items = shallowRef<CredentialListItem[]>([])
const total = ref(0)
const status = ref<'loading' | 'ready' | 'error'>('loading')
const refreshing = ref(false)
const error = shallowRef<ApiErrorType | Error | null>(null)

let seq = 0
let controller: AbortController | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null

async function load(): Promise<void> {
  const current = ++seq
  controller?.abort()
  controller = new AbortController()
  if (items.value.length > 0) refreshing.value = true
  try {
    const result = await fetchCredentials(
      props.poolId,
      {
        health_status: listState.healthStatus === '' ? undefined : listState.healthStatus,
        enabled: listState.enabled === '' ? undefined : listState.enabled === 'true',
        page: listState.page,
        page_size: listState.pageSize,
        sort: 'name',
      },
      controller.signal,
    )
    if (current !== seq) return
    items.value = result.items
    total.value = result.total
    error.value = null
    status.value = 'ready'
    refreshing.value = false
  } catch (e) {
    if (current !== seq || isAbortError(e)) return
    error.value = e instanceof Error ? e : new Error('网络请求失败')
    status.value = 'error'
    refreshing.value = false
  }
}

function applyFilters(): void {
  listState.page = 1
  void load()
}

onMounted(() => {
  void load()
  refreshTimer = setInterval(() => {
    if (document.visibilityState === 'visible') void load()
  }, 10000)
})
onUnmounted(() => {
  if (refreshTimer !== null) clearInterval(refreshTimer)
  controller?.abort()
})

const formOpen = ref(false)
const formTarget = ref<CredentialListItem | null>(null)
const formSubmitting = ref(false)
const formError = shallowRef<unknown>(null)

function openCreate(): void {
  formTarget.value = null
  formError.value = null
  formOpen.value = true
}

function openEdit(row: CredentialListItem): void {
  formTarget.value = row
  formError.value = null
  formOpen.value = true
}

async function submitForm(command: {
  mode: 'create' | 'update'
  name: string
  secret_source?: 'INLINE_ENCRYPTED' | 'EXTERNAL_REF' | undefined
  secret_value?: string | undefined
  secret_ref?: string | null | undefined
  weight: number
  rpm_limit: number | null
  tpm_limit: number | null
  concurrent_limit: number | null
  enabled: boolean
  version?: number | undefined
}): Promise<void> {
  formSubmitting.value = true
  formError.value = null
  try {
    if (command.mode === 'create') {
      await createCredential(props.poolId, {
        name: command.name,
        secret_source: command.secret_source!,
        secret_value: command.secret_value ?? undefined,
        secret_ref: command.secret_ref ?? undefined,
        weight: command.weight,
        rpm_limit: command.rpm_limit,
        tpm_limit: command.tpm_limit,
        concurrent_limit: command.concurrent_limit,
        enabled: command.enabled,
      })
    } else {
      await updateCredential(formTarget.value!.id, {
        name: command.name,
        secret_ref: command.secret_ref ?? null,
        weight: command.weight,
        rpm_limit: command.rpm_limit,
        tpm_limit: command.tpm_limit,
        concurrent_limit: command.concurrent_limit,
        enabled: command.enabled,
        version: command.version!,
      })
    }
    formOpen.value = false
    await load()
  } catch (e) {
    formError.value = e
  } finally {
    formSubmitting.value = false
  }
}

const rotateOpen = ref(false)
const rotateTarget = ref<CredentialListItem | null>(null)
const rotateSubmitting = ref(false)
const rotateError = shallowRef<unknown>(null)

function openRotate(row: CredentialListItem): void {
  rotateTarget.value = row
  rotateError.value = null
  rotateOpen.value = true
}

async function submitRotate(command: { secret_value: string; secret_value_confirm: string; version: number }): Promise<void> {
  rotateSubmitting.value = true
  rotateError.value = null
  try {
    await rotateCredential(rotateTarget.value!.id, command)
    rotateOpen.value = false
    await load()
  } catch (e) {
    rotateError.value = e
  } finally {
    rotateSubmitting.value = false
  }
}

const checkOpen = ref(false)
const checkTarget = ref<CredentialListItem | null>(null)
const checkSubmitting = ref(false)
const checkError = shallowRef<unknown>(null)
const checkResult = shallowRef<ProviderCheckRecord | null>(null)
const modelOptions = ref<{ id: string; label: string }[]>([])

async function openCheck(row: CredentialListItem): Promise<void> {
  checkTarget.value = row
  checkError.value = null
  checkResult.value = null
  modelOptions.value = []
  checkOpen.value = true
  if (props.providerId) {
    try {
      const models = await fetchProviderModels({ provider_id: props.providerId, page_size: 100 })
      modelOptions.value = models.items.map((item) => ({
        id: item.id,
        label: `${item.display_name}（${item.model_id}）`,
      }))
    } catch {
      // 模型选项加载失败时保持空列表，用户可改用仅连接模式或稍后重试
    }
  }
}

async function submitCheck(command: {
  provider_model_id?: string | undefined
  credential_id?: string | undefined
  mode: 'MINIMAL_CHAT' | 'CONNECTION_ONLY'
  timeout_ms: number
}): Promise<void> {
  checkSubmitting.value = true
  checkError.value = null
  try {
    checkResult.value = await checkCredential(checkTarget.value!.id, command)
    await load()
  } catch (e) {
    checkError.value = e
  } finally {
    checkSubmitting.value = false
  }
}

const actionBusy = ref('')
const actionError = shallowRef<ApiErrorType | null>(null)

async function toggleEnabled(row: CredentialListItem): Promise<void> {
  actionBusy.value = row.id
  actionError.value = null
  try {
    if (row.enabled) {
      await disableCredential(row.id, row.version)
    } else {
      await enableCredential(row.id, row.version)
    }
    await load()
  } catch (e) {
    if (e instanceof ApiError) actionError.value = e
  } finally {
    actionBusy.value = ''
  }
}

const deleteOpen = ref(false)
const deleteTarget = ref<CredentialListItem | null>(null)

async function submitDelete(): Promise<void> {
  if (!deleteTarget.value) return
  actionBusy.value = deleteTarget.value.id
  actionError.value = null
  try {
    await deleteCredential(deleteTarget.value.id, deleteTarget.value.version)
    deleteOpen.value = false
    await load()
  } catch (e) {
    if (e instanceof ApiError) {
      actionError.value = e
      deleteOpen.value = false
    }
  } finally {
    actionBusy.value = ''
  }
}

const healthFilterOptions: { value: string; label: string }[] = [
  { value: '', label: '全部健康状态' },
  { value: 'HEALTHY', label: healthStatusLabel('HEALTHY') },
  { value: 'UNKNOWN', label: healthStatusLabel('UNKNOWN') },
  { value: 'RATE_LIMITED', label: healthStatusLabel('RATE_LIMITED') },
  { value: 'INVALID', label: healthStatusLabel('INVALID') },
  { value: 'UNAVAILABLE', label: healthStatusLabel('UNAVAILABLE') },
  { value: 'DISABLED', label: healthStatusLabel('DISABLED') },
]

const lastErrorText = computed(() => {
  const err = actionError.value
  if (err instanceof ApiError && (err.code === 'OBJECT_IN_USE' || err.code === 'CAPACITY_IN_USE')) {
    return err.code === 'CAPACITY_IN_USE'
      ? '该凭证正在被运行中的调用占用，暂时无法删除'
      : '该凭证仍被引用，无法执行该操作'
  }
  return err ? err.message : ''
})

function formatTime(value: string | null, emptyText: string): string {
  return value ?? emptyText
}
</script>

<template>
  <section class="lai-cred-panel">
    <div class="lai-cred-toolbar">
      <h2 class="lai-section-title">
        Credential
      </h2>
      <div class="lai-cred-filters">
        <select
          v-model="listState.healthStatus"
          class="lai-input lai-select"
          @change="applyFilters"
        >
          <option
            v-for="item in healthFilterOptions"
            :key="item.value"
            :value="item.value"
          >
            {{ item.label }}
          </option>
        </select>
        <select
          v-model="listState.enabled"
          class="lai-input lai-select"
          @change="applyFilters"
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
        <button
          v-if="canManage"
          type="button"
          class="lai-btn lai-btn-primary"
          @click="openCreate"
        >
          新增 Credential
        </button>
      </div>
    </div>

    <p
      v-if="lastErrorText"
      class="lai-form-message-error"
      role="alert"
    >
      {{ lastErrorText }}
    </p>

    <PageState
      v-if="status === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="status === 'error'"
      status="error"
      :error="error"
      @retry="load()"
    />
    <PageState
      v-else-if="items.length === 0"
      status="empty"
      :message="listState.healthStatus || listState.enabled ? '没有匹配的 Credential' : '尚未配置 Credential'"
    />
    <template v-else>
      <div class="lai-table-wrap">
        <table class="lai-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>掩码</th>
              <th>来源</th>
              <th>权重</th>
              <th>RPM</th>
              <th>TPM</th>
              <th>并发上限</th>
              <th>当前并发</th>
              <th>健康状态</th>
              <th>限流复位</th>
              <th>最近成功</th>
              <th>最近检测</th>
              <th>启停</th>
              <th>待发布</th>
              <th v-if="canManage || canCheck">
                操作
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in items"
              :key="row.id"
            >
              <td class="lai-cell-strong">
                {{ row.name }}
              </td>
              <td class="lai-cell-mono">
                {{ row.masked_value }}
              </td>
              <td>{{ secretSourceLabel(row.secret_source) }}</td>
              <td>{{ row.weight }}</td>
              <td>{{ row.rpm_limit ?? '不限制' }}</td>
              <td>{{ row.tpm_limit ?? '不限制' }}</td>
              <td>{{ row.concurrent_limit ?? '不限制' }}</td>
              <td>{{ row.current_concurrency }}</td>
              <td>{{ healthStatusLabel(row.health_status) }}</td>
              <td>{{ row.health_status === 'RATE_LIMITED' ? formatTime(row.rate_limit_reset_at, '—') : '' }}</td>
              <td>{{ formatTime(row.last_success_at, '暂无') }}</td>
              <td>{{ formatTime(row.last_check_at, '未检测') }}</td>
              <td>{{ row.enabled ? '启用' : '停用' }}</td>
              <td>{{ row.draft_changed ? '待发布' : '' }}</td>
              <td
                v-if="canManage || canCheck"
                class="lai-cell-actions"
              >
                <button
                  v-if="canManage"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openEdit(row)"
                >
                  编辑
                </button>
                <button
                  v-if="canManage && row.secret_source === 'INLINE_ENCRYPTED'"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openRotate(row)"
                >
                  轮换密钥
                </button>
                <button
                  v-if="canCheck"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openCheck(row)"
                >
                  检测
                </button>
                <button
                  v-if="canManage"
                  type="button"
                  class="lai-btn lai-btn-text"
                  :disabled="actionBusy === row.id"
                  @click="toggleEnabled(row)"
                >
                  {{ row.enabled ? '停用' : '启用' }}
                </button>
                <button
                  v-if="canManage"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="deleteTarget = row; deleteOpen = true"
                >
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <ListPager
        :page="listState.page"
        :page-size="listState.pageSize"
        :total="total"
        :disabled="refreshing"
        @update:page="listState.page = $event; load()"
        @update:page-size="listState.pageSize = $event; applyFilters()"
      />
    </template>

    <CredentialFormDialog
      v-model:open="formOpen"
      :credential="formTarget"
      :submitting="formSubmitting"
      :error="formError"
      @confirm="submitForm"
    />
    <CredentialRotateDialog
      v-model:open="rotateOpen"
      :credential-name="rotateTarget?.name ?? ''"
      :version="rotateTarget?.version ?? 0"
      :submitting="rotateSubmitting"
      :error="rotateError"
      @confirm="submitRotate"
    />
    <CheckCommandDialog
      v-model:open="checkOpen"
      title="检测 Credential"
      :target-label="`目标：${checkTarget?.name ?? ''}`"
      :model-options="modelOptions"
      :require-model="modelOptions.length > 0"
      :submitting="checkSubmitting"
      :result="checkResult"
      :error="checkError"
      @confirm="submitCheck"
    />
    <ConfirmDialog
      v-model:open="deleteOpen"
      title="删除 Credential"
      :message="`确认删除 Credential「${deleteTarget?.name ?? ''}」？删除未发布对象或记录删除草稿，发布后生效。`"
      danger
      :loading="actionBusy !== ''"
      @confirm="submitDelete"
    />
  </section>
</template>

<style scoped>
.lai-cred-panel {
  margin-top: 16px;
}
.lai-cred-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.lai-section-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0;
}
.lai-cred-filters {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lai-select {
  width: auto;
  padding: 4px 8px;
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
.lai-cell-strong {
  font-weight: 600;
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
</style>
