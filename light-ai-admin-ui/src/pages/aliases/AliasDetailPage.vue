<script setup lang="ts">
// Model Alias 详情与候选路由页（FE-018，附录 4.2.8）。
// 候选按 priority 升序展示；优先级调整显式保存、任一版本冲突整批不变；
// 探测选择池内一个可用凭证；运行摘要 30 秒刷新，页面离开停止。
import { computed, onMounted, onUnmounted, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import CheckCommandDialog from '@/components/CheckCommandDialog.vue'
import CandidateFormDialog, { type ModelGroupOption } from './CandidateFormDialog.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { runtimeAvailabilityLabel } from '@/app/display'
import {
  checkCandidate,
  createCandidate,
  deleteCandidate,
  fetchCandidates,
  fetchModelAlias,
  fetchModelCredentialPools,
  reorderCandidates,
  updateCandidate,
} from '@/api/modelAliases'
import type { CredentialPoolOption, RouteCandidateDetail } from '@/api/modelAliases'
import { fetchProviderModels } from '@/api/providerModels'
import type { ProviderModelListItem } from '@/api/providerModels'
import { fetchCredentials, type ProviderCheckCommand, type ProviderCheckRecord } from '@/api/credentials'
import { ApiError, toErrorMessage } from '@/api/errors'

const route = useRoute()
const aliasId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))

const store = useBootstrapStore()
const canManage = store.can(Permission.aliasManage)
const canCheck = store.can(Permission.providerCheck)

const loading = ref(true)
const loadError = ref<unknown>(null)
const alias = shallowRef<Awaited<ReturnType<typeof fetchModelAlias>> | null>(null)
const candidates = shallowRef<RouteCandidateDetail[]>([])

const SORT_INTERVAL_MS = 30000
let summaryTimer: ReturnType<typeof setInterval> | null = null

async function load(): Promise<void> {
  try {
    alias.value = await fetchModelAlias(aliasId.value)
    candidates.value = await fetchCandidates(aliasId.value)
    loadError.value = null
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  summaryTimer = setInterval(() => {
    if (document.visibilityState === 'visible') void load()
  }, SORT_INTERVAL_MS)
})
onUnmounted(() => {
  if (summaryTimer !== null) clearInterval(summaryTimer)
})

const sortedCandidates = computed(() =>
  [...candidates.value].sort((a, b) => a.priority - b.priority || b.weight - a.weight),
)

// —— 优先级调整与批量重排 ——
const priorityEdits = ref<Record<string, number>>({})
const reorderSaving = ref(false)
const reorderMessage = ref('')

function editedPriority(row: RouteCandidateDetail): number {
  return priorityEdits.value[row.id] ?? row.priority
}
function onPriorityInput(row: RouteCandidateDetail, value: number): void {
  priorityEdits.value = { ...priorityEdits.value, [row.id]: value }
}
const reorderDirty = computed(() => Object.keys(priorityEdits.value).length > 0)

async function submitReorder(): Promise<void> {
  if (!reorderDirty.value || reorderSaving.value) return
  reorderSaving.value = true
  reorderMessage.value = ''
  try {
    const items = candidates.value.map((row) => ({
      id: row.id,
      priority: editedPriority(row),
      version: row.version,
    }))
    const updated = await reorderCandidates(aliasId.value, items)
    candidates.value = updated
    priorityEdits.value = {}
    reorderMessage.value = '排序已保存'
  } catch (e) {
    if (e instanceof ApiError && e.code === 'CONFIG_VERSION_CONFLICT') {
      // 整批不变：还原本地编辑并重新加载
      priorityEdits.value = {}
      await load()
      reorderMessage.value = '排序保存失败：候选已被其他管理员修改，整批未提交'
    } else {
      reorderMessage.value = toErrorMessage(e)
    }
  } finally {
    reorderSaving.value = false
  }
}

// —— 候选新增/编辑 ——
const formOpen = ref(false)
const formTarget = ref<RouteCandidateDetail | null>(null)
const formSubmitting = ref(false)
const formError = shallowRef<unknown>(null)
const modelGroups = shallowRef<ModelGroupOption[]>([])

async function loadModelGroups(): Promise<void> {
  try {
    const models = await fetchProviderModels({ page_size: 200, enabled: true })
    const items = models.items as ProviderModelListItem[]
    const groups = new Map<string, ModelGroupOption>()
    for (const item of items) {
      const group = groups.get(item.provider_name) ?? { providerName: item.provider_name, models: [] }
      group.models.push({
        id: item.id,
        label: `${item.display_name}（${item.model_id}）`,
        supportStream: item.support_stream ?? false,
        contextWindow: item.context_window,
      })
      groups.set(item.provider_name, group)
    }
    modelGroups.value = [...groups.values()]
  } catch {
    modelGroups.value = []
  }
}

async function openCreate(): Promise<void> {
  formTarget.value = null
  formError.value = null
  formOpen.value = true
  await loadModelGroups()
}

async function openEdit(row: RouteCandidateDetail): Promise<void> {
  formTarget.value = row
  formError.value = null
  formOpen.value = true
  await loadModelGroups()
}

async function loadPools(modelId: string): Promise<CredentialPoolOption[]> {
  return fetchModelCredentialPools(modelId)
}

async function submitForm(command: {
  provider_model_id: string
  credential_pool_id: string
  priority: number
  weight: number
  enabled: boolean
  version?: number | undefined
}): Promise<void> {
  formSubmitting.value = true
  formError.value = null
  try {
    if (formTarget.value) {
      await updateCandidate(formTarget.value.id, { ...command, version: command.version! })
    } else {
      await createCandidate(aliasId.value, command)
    }
    formOpen.value = false
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'DUPLICATE_ROUTE_CANDIDATE') {
      formError.value = new Error('相同的模型与凭证池组合已存在')
    } else {
      formError.value = e
    }
  } finally {
    formSubmitting.value = false
  }
}

// —— 候选启停/删除 ——
const busyId = ref('')
const actionMessage = ref('')
const deleteTarget = ref<RouteCandidateDetail | null>(null)
const deleteOpen = ref(false)

async function toggleCandidate(row: RouteCandidateDetail): Promise<void> {
  busyId.value = row.id
  actionMessage.value = ''
  try {
    await updateCandidate(row.id, {
      provider_model_id: row.provider_model_id,
      credential_pool_id: row.credential_pool_id,
      priority: row.priority,
      weight: row.weight,
      enabled: !row.enabled,
      version: row.version,
    })
    await load()
  } catch (e) {
    actionMessage.value = toErrorMessage(e)
  } finally {
    busyId.value = ''
  }
}

async function submitDelete(): Promise<void> {
  if (!deleteTarget.value) return
  busyId.value = deleteTarget.value.id
  try {
    await deleteCandidate(deleteTarget.value.id, deleteTarget.value.version)
    deleteOpen.value = false
    await load()
  } catch (e) {
    actionMessage.value = toErrorMessage(e)
    deleteOpen.value = false
  } finally {
    busyId.value = ''
  }
}

// —— 探测 ——
const checkOpen = ref(false)
const checkTarget = ref<RouteCandidateDetail | null>(null)
const checkSubmitting = ref(false)
const checkError = shallowRef<unknown>(null)
const checkResult = shallowRef<ProviderCheckRecord | null>(null)
const checkCredentialOptions = ref<{ id: string; label: string }[]>([])

async function openProbe(row: RouteCandidateDetail): Promise<void> {
  checkTarget.value = row
  checkError.value = null
  checkResult.value = null
  checkCredentialOptions.value = []
  checkOpen.value = true
  try {
    const credentials = await fetchCredentials(row.credential_pool_id, { enabled: true, page_size: 100 })
    checkCredentialOptions.value = credentials.items.map((item) => ({ id: item.id, label: item.name }))
  } catch {
    // 选项加载失败时保持空列表
  }
}

async function submitProbe(command: ProviderCheckCommand): Promise<void> {
  if (!checkTarget.value) return
  checkSubmitting.value = true
  checkError.value = null
  try {
    checkResult.value = await checkCandidate(checkTarget.value.id, {
      ...command,
      provider_model_id: checkTarget.value.provider_model_id,
    })
  } catch (e) {
    checkError.value = e
  } finally {
    checkSubmitting.value = false
  }
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        模型别名详情
      </h1>
      <div
        v-if="canManage"
        class="lai-page-actions"
      >
        <RouterLink
          :to="`/ui/model-aliases/${aliasId}/edit`"
          class="lai-btn"
        >
          编辑
        </RouterLink>
        <button
          type="button"
          class="lai-btn lai-btn-primary"
          @click="openCreate"
        >
          新增候选
        </button>
      </div>
    </div>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
      @retry="load"
    />
    <template v-else-if="alias">
      <div class="lai-detail-grid">
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            基础信息
          </h2>
          <dl class="lai-dl">
            <dt>alias</dt><dd class="lai-cell-mono">
              {{ alias.alias }}
            </dd>
            <dt>展示名称</dt><dd>{{ alias.display_name }}</dd>
            <dt>描述</dt><dd>{{ alias.description ?? '—' }}</dd>
            <dt>路由策略</dt><dd>{{ alias.route_strategy }}</dd>
            <dt>启停</dt><dd>{{ alias.enabled ? '启用' : '停用' }}</dd>
            <dt>待发布</dt><dd>{{ alias.draft_changed ? '待发布' : '—' }}</dd>
          </dl>
        </div>
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            能力与运行
          </h2>
          <dl class="lai-dl">
            <dt>候选</dt><dd>{{ alias.candidate_count }} 个（可调用 {{ alias.available_candidate_count }}）</dd>
            <dt>流式支持</dt><dd>{{ alias.stream_candidate_count }} / {{ alias.candidate_count }}</dd>
            <dt>24h 调用</dt><dd>{{ alias.request_count_24h }}</dd>
            <dt>成功率（24h）</dt><dd>{{ alias.success_rate_24h == null ? '—' : `${alias.success_rate_24h}%` }}</dd>
            <dt>P95 耗时（24h）</dt><dd>{{ alias.p95_total_ms_24h == null ? '—' : `${alias.p95_total_ms_24h} ms` }}</dd>
            <dt>当前快照</dt><dd>#{{ alias.current_snapshot_no ?? '—' }}</dd>
          </dl>
        </div>
      </div>

      <div class="lai-detail-card">
        <div class="lai-candidates-header">
          <h2 class="lai-section-title">
            候选路由
          </h2>
          <div
            v-if="canManage"
            class="lai-candidates-actions"
          >
            <span
              v-if="reorderMessage"
              class="lai-reorder-message"
            >{{ reorderMessage }}</span>
            <button
              type="button"
              class="lai-btn lai-btn-primary"
              :disabled="!reorderDirty || reorderSaving"
              @click="submitReorder"
            >
              {{ reorderSaving ? '保存中…' : '保存排序' }}
            </button>
          </div>
        </div>

        <PageState
          v-if="sortedCandidates.length === 0"
          status="empty"
          message="尚未配置候选"
        />
        <div
          v-else
          class="lai-table-wrap"
        >
          <table class="lai-table">
            <thead>
              <tr>
                <th>priority</th>
                <th>weight</th>
                <th>Provider</th>
                <th>模型</th>
                <th>凭证池</th>
                <th>流式</th>
                <th>当前并发</th>
                <th>运行状态</th>
                <th>排除原因</th>
                <th>启停</th>
                <th>待发布</th>
                <th v-if="canManage || canCheck">
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in sortedCandidates"
                :key="row.id"
              >
                <td>
                  <input
                    v-if="canManage"
                    class="lai-input lai-priority-input"
                    type="number"
                    min="1"
                    max="100"
                    :value="editedPriority(row)"
                    :aria-label="`调整 ${row.provider_model_display_name} 优先级`"
                    @change="onPriorityInput(row, Number(($event.target as HTMLInputElement).value))"
                  >
                  <template v-else>
                    {{ row.priority }}
                  </template>
                </td>
                <td>{{ row.weight }}</td>
                <td>{{ row.provider_name }}</td>
                <td>
                  <RouterLink
                    :to="`/ui/provider-models/${row.provider_model_id}`"
                    class="lai-link"
                  >
                    {{ row.provider_model_display_name }}
                  </RouterLink>
                  <span class="lai-cell-sub lai-cell-mono">{{ row.provider_model_id_label }}</span>
                </td>
                <td>{{ row.credential_pool_name }}</td>
                <td>{{ row.support_stream ? '支持' : '不支持' }}</td>
                <td>{{ row.current_concurrency }}</td>
                <td>{{ runtimeAvailabilityLabel(row.runtime_status) }}</td>
                <td>{{ row.excluded_reason ?? '—' }}</td>
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
                    v-if="canCheck"
                    type="button"
                    class="lai-btn lai-btn-text"
                    @click="openProbe(row)"
                  >
                    探测
                  </button>
                  <button
                    v-if="canManage"
                    type="button"
                    class="lai-btn lai-btn-text"
                    :disabled="busyId === row.id"
                    @click="toggleCandidate(row)"
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
        <p
          v-if="actionMessage"
          class="lai-form-message-error"
          role="alert"
        >
          {{ actionMessage }}
        </p>
      </div>
    </template>

    <CandidateFormDialog
      v-model:open="formOpen"
      :alias-id="aliasId"
      :candidate="formTarget"
      :model-groups="modelGroups"
      :submitting="formSubmitting"
      :error="formError"
      :load-pools="loadPools"
      @confirm="submitForm"
    />
    <CheckCommandDialog
      v-model:open="checkOpen"
      title="探测候选"
      :target-label="`目标：${checkTarget?.provider_model_display_name ?? ''} → ${checkTarget?.credential_pool_name ?? ''}`"
      :credential-options="checkCredentialOptions"
      require-credential
      :submitting="checkSubmitting"
      :result="checkResult"
      :error="checkError"
      @confirm="submitProbe"
    />
    <ConfirmDialog
      v-model:open="deleteOpen"
      title="删除候选"
      :message="`确认删除候选「${deleteTarget?.provider_model_display_name ?? ''} → ${deleteTarget?.credential_pool_name ?? ''}」？`"
      danger
      :loading="busyId !== ''"
      @confirm="submitDelete"
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
.lai-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 12px;
  margin: 12px 0;
}
.lai-detail-card {
  border: 1px solid #d8dee4;
  border-radius: 6px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
.lai-section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 8px;
}
.lai-dl {
  display: grid;
  grid-template-columns: 160px 1fr;
  gap: 4px 12px;
  font-size: 13px;
  margin: 0;
}
.lai-dl dt {
  color: #57606a;
}
.lai-dl dd {
  margin: 0;
}
.lai-candidates-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lai-candidates-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.lai-reorder-message {
  font-size: 12px;
  color: #57606a;
}
.lai-priority-input {
  width: 72px;
  padding: 4px 6px;
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
  padding: 6px 10px;
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
</style>
