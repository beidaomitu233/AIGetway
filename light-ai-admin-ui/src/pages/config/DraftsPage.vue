<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ListPager from '@/components/ListPager.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { formatDateTime } from '@/app/display'
import {
  type ChangeType,
  type ConfigDraftState,
  type DraftChange,
  type DraftChangeSummary,
  fetchDraftChanges,
  fetchDraftState,
  fetchDraftSummary,
  revertAllDraftChanges,
  revertDraftChange,
} from '@/api/config'
import { ApiError } from '@/api/errors'

const store = useBootstrapStore()
const router = useRouter()

const canManage = computed(() => store.can(Permission.publishManage))

const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const draftState = ref<ConfigDraftState | null>(null)
const summary = ref<DraftChangeSummary | null>(null)

const changes = ref<DraftChange[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const changesLoading = ref(false)
const changesError = ref<unknown>(null)

const filters = reactive({
  keyword: '',
  entity_type: [] as string[],
  change_type: [] as ChangeType[],
})

const changeTypeLabels: Record<string, string> = {
  CREATE: '新增',
  UPDATE: '修改',
  ENABLE: '启用',
  DISABLE: '停用',
  DELETE: '删除',
}

const ENTITY_ORDER = [
  'provider',
  'credential_pool',
  'credential',
  'provider_model',
  'model_alias',
  'route_candidate',
  'limit_policy',
  'reliability_policy',
  'runtime_config',
]

const entityTypeLabels: Record<string, string> = {
  provider: 'Provider',
  credential_pool: '凭证池',
  credential: 'Credential',
  provider_model: '模型',
  model_alias: '模型别名',
  route_candidate: '候选路由',
  limit_policy: '限流策略',
  reliability_policy: '可靠性策略',
  runtime_config: '运行参数',
}

const isPublishing = computed(() => draftState.value?.status === 'PUBLISHING')

/** 分组：按实体依赖顺序，组内 modified_at desc（附录 4.5.1.1）。 */
const groupedChanges = computed(() => {
  const groups = new Map<string, DraftChange[]>()
  for (const change of changes.value) {
    const list = groups.get(change.entity_type) ?? []
    list.push(change)
    groups.set(change.entity_type, list)
  }
  return [...groups.entries()].sort(
    ([a], [b]) =>
      ENTITY_ORDER.indexOf(a) - ENTITY_ORDER.indexOf(b),
  )
})

async function loadDraftMeta(): Promise<void> {
  const [stateData, summaryData] = await Promise.all([
    fetchDraftState(),
    fetchDraftSummary(),
  ])
  draftState.value = stateData
  summary.value = summaryData
}

async function loadChanges(): Promise<void> {
  changesLoading.value = true
  changesError.value = null
  try {
    const result = await fetchDraftChanges(
      {
        keyword: filters.keyword || undefined,
        entity_type: filters.entity_type.length > 0 ? filters.entity_type : undefined,
        change_type: filters.change_type.length > 0 ? filters.change_type : undefined,
        page: page.value,
        page_size: pageSize.value,
      },
      new AbortController().signal,
    )
    changes.value = result.items
    total.value = result.total
  } catch (e) {
    changesError.value = e
  } finally {
    changesLoading.value = false
  }
}

async function loadAll(): Promise<void> {
  state.value = 'loading'
  loadError.value = null
  try {
    await loadDraftMeta()
    await loadChanges()
    state.value = 'ready'
  } catch (e) {
    loadError.value = e
    state.value = 'error'
  }
}
onMounted(loadAll)

function applyFilters(): void {
  page.value = 1
  void loadChanges()
}

function applyPage(next: number): void {
  page.value = next
  void loadChanges()
}

function applyPageSize(next: number): void {
  pageSize.value = next
  page.value = 1
  void loadChanges()
}

// —— 单项撤销（FE-038）——
const revertDialogOpen = ref(false)
const revertTarget = ref<DraftChange | null>(null)
const revertLoading = ref(false)
const revertError = ref('')
const revertReason = ref('')

function requestRevert(change: DraftChange): void {
  revertTarget.value = change
  revertReason.value = ''
  revertError.value = ''
  revertDialogOpen.value = true
}

async function confirmRevert(): Promise<void> {
  const target = revertTarget.value
  if (!target || revertLoading.value) return
  revertLoading.value = true
  revertError.value = ''
  try {
    await revertDraftChange(target.entity_type, target.entity_id, {
      version: target.entity_version,
      draft_revision: draftState.value!.draft_revision,
      reason: revertReason.value,
    })
    revertDialogOpen.value = false
    void store.refreshDraftSummary()
    await loadAll()
  } catch (e) {
    if (e instanceof ApiError) {
      revertError.value = `${e.message}（${e.code}）`
      // CONFIG_DRAFT_CHANGED：刷新最新修订号后允许重试
      if (e.code === 'CONFIG_DRAFT_CHANGED') {
        await loadDraftMeta().catch(() => undefined)
      }
    } else {
      revertError.value = '撤销失败，请稍后重试'
    }
  } finally {
    revertLoading.value = false
  }
}

// —— 全部撤销（FE-038）——
const revertAllOpen = ref(false)
const revertAllLoading = ref(false)
const revertAllError = ref('')
const revertAllReason = ref('')
const revertAllConfirmText = ref('')

async function confirmRevertAll(): Promise<void> {
  if (revertAllLoading.value) return
  revertAllLoading.value = true
  revertAllError.value = ''
  try {
    await revertAllDraftChanges({
      draft_revision: draftState.value!.draft_revision,
      confirmation_text: revertAllConfirmText.value,
      reason: revertAllReason.value,
    })
    revertAllOpen.value = false
    void store.refreshDraftSummary()
    await loadAll()
  } catch (e) {
    if (e instanceof ApiError) {
      revertAllError.value = `${e.message}（${e.code}）`
      if (e.code === 'CONFIG_DRAFT_CHANGED') {
        await loadDraftMeta().catch(() => undefined)
      }
    } else {
      revertAllError.value = '撤销失败，请稍后重试'
    }
  } finally {
    revertAllLoading.value = false
  }
}

// —— 差异展开 ——
const expandedIds = ref<Set<string>>(new Set())
function toggleExpand(id: string): void {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedIds.value = next
}

function summaryCountChips(): Array<{ key: string; label: string; type: ChangeType | '' }> {
  const s = summary.value
  if (!s) return []
  return [
    { key: 'total', label: `全部 ${s.total_count}`, type: '' as const },
    { key: 'create', label: `新增 ${s.create_count}`, type: 'CREATE' as const },
    { key: 'update', label: `修改 ${s.update_count}`, type: 'UPDATE' as const },
    { key: 'enable', label: `启用 ${s.enable_count}`, type: 'ENABLE' as const },
    { key: 'disable', label: `停用 ${s.disable_count}`, type: 'DISABLE' as const },
    { key: 'delete', label: `删除 ${s.delete_count}`, type: 'DELETE' as const },
  ]
}

function applyChangeType(type: ChangeType | ''): void {
  filters.change_type = type === '' ? [] : [type]
  applyFilters()
}

function entityRoute(change: DraftChange): { name: string; params: Record<string, string> } | null {
  switch (change.entity_type) {
    case 'provider':
      return { name: 'provider-detail', params: { id: change.entity_id } }
    case 'credential_pool':
      return { name: 'pool-detail', params: { id: change.entity_id } }
    case 'provider_model':
      return { name: 'model-detail', params: { id: change.entity_id } }
    case 'model_alias':
      return { name: 'alias-detail', params: { id: change.entity_id } }
    default:
      return null
  }
}

const summaryChipActive = (type: ChangeType | ''): boolean =>
  type === '' ? filters.change_type.length === 0 : filters.change_type.length === 1 && filters.change_type[0] === type
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        待发布变更
      </h1>
      <div class="lai-row-actions">
        <button
          v-if="canManage && (summary?.total_count ?? 0) > 0 && !isPublishing"
          type="button"
          class="lai-btn"
          @click="revertAllOpen = true"
        >
          全部撤销
        </button>
        <button
          v-if="canManage && (summary?.total_count ?? 0) > 0 && !isPublishing"
          type="button"
          class="lai-btn lai-btn-primary"
          @click="router.push({ name: 'publish' })"
        >
          校验并发布
        </button>
      </div>
    </div>

    <PageState
      v-if="state === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="state === 'error'"
      status="error"
      :error="loadError"
      @retry="loadAll"
    />
    <template v-else-if="draftState && summary">
      <div class="lai-card">
        <h2 class="lai-card-title">
          草稿状态
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">基线快照</span>#{{ draftState.base_snapshot_no }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">草稿修订号</span>{{ draftState.draft_revision }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">变更数</span>{{ draftState.change_count }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">状态</span>
            {{ draftState.status === 'PUBLISHING' ? '发布中（只读）' : '可编辑' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">首次修改</span>{{ formatDateTime(draftState.first_modified_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">最近修改</span>{{ formatDateTime(draftState.last_modified_at, store.timezone) }}
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          变更摘要
        </h2>
        <div class="lai-filter-bar">
          <button
            v-for="chip in summaryCountChips()"
            :key="chip.key"
            type="button"
            class="lai-btn"
            :class="{ 'lai-btn-primary': summaryChipActive(chip.type) }"
            @click="applyChangeType(chip.type)"
          >
            {{ chip.label }}
          </button>
        </div>

        <div class="lai-filter-bar">
          <input
            v-model="filters.keyword"
            class="lai-input lai-filter-keyword"
            type="text"
            placeholder="对象名称或 ID"
            @change="applyFilters"
          >
          <select
            v-model="filters.entity_type"
            class="lai-select"
            multiple
            aria-label="实体类型"
            @change="applyFilters"
          >
            <option
              v-for="(label, type) in entityTypeLabels"
              :key="type"
              :value="type"
            >
              {{ label }}
            </option>
          </select>
        </div>

        <p
          v-if="isPublishing"
          class="lai-card-hint"
          role="status"
        >
          发布进行中，配置编辑与撤销已暂停。
        </p>

        <PageState
          v-if="changesLoading && changes.length === 0"
          status="loading"
        />
        <PageState
          v-else-if="changesError && changes.length === 0"
          status="error"
          :error="changesError"
          @retry="loadChanges"
        />
        <template v-else>
          <div
            v-for="[entityType, items] in groupedChanges"
            :key="entityType"
            class="lai-draft-group"
          >
            <h3 class="lai-subsection-title">
              {{ entityTypeLabels[entityType] ?? entityType }}（{{ items.length }}）
            </h3>
            <div
              v-for="change in items"
              :key="change.id"
              class="lai-draft-item"
            >
              <div class="lai-draft-item-header">
                <span class="lai-draft-change-type">{{ changeTypeLabels[change.change_type] ?? change.change_type }}</span>
                <RouterLink
                  v-if="entityRoute(change) && change.change_type !== 'DELETE'"
                  :to="{
                    name: entityRoute(change)!.name,
                    params: entityRoute(change)!.params,
                  }"
                  class="lai-link"
                >
                  {{ change.entity_name }}
                </RouterLink>
                <span
                  v-else
                  class="lai-related-name"
                >{{ change.entity_name }}</span>
                <span class="lai-related-meta lai-mono">{{ change.entity_id }}</span>
                <span class="lai-related-meta">
                  {{ change.modified_by_name }} · {{ formatDateTime(change.modified_at, store.timezone) }}
                </span>
                <span class="lai-row-actions">
                  <button
                    type="button"
                    class="lai-btn lai-btn-text"
                    @click="toggleExpand(change.id)"
                  >
                    {{ expandedIds.has(change.id) ? '收起差异' : '查看差异' }}
                  </button>
                  <button
                    v-if="canManage && !isPublishing"
                    type="button"
                    class="lai-btn lai-btn-text"
                    :disabled="!change.revertable"
                    :title="change.revertable ? '' : `撤销被阻塞：${change.revert_blockers.join('；')}`"
                    @click="requestRevert(change)"
                  >
                    撤销
                  </button>
                </span>
              </div>
              <p
                v-if="!change.revertable"
                class="lai-form-message-error"
              >
                不可撤销：{{ change.revert_blockers.join('；') }}
              </p>
              <table
                v-if="expandedIds.has(change.id)"
                class="lai-table lai-diff-table"
              >
                <thead>
                  <tr>
                    <th>字段</th>
                    <th>变更前</th>
                    <th>变更后</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="field in change.changed_fields"
                    :key="field.field"
                  >
                    <td
                      v-if="field.sensitive"
                      colspan="3"
                      class="lai-related-meta"
                    >
                      敏感字段已变更
                    </td>
                    <template v-else>
                      <td>{{ field.field }}</td>
                      <td class="lai-mono">
                        {{ field.before_value ?? '（空）' }}
                      </td>
                      <td class="lai-mono">
                        {{ field.after_value ?? '（空）' }}
                      </td>
                    </template>
                  </tr>
                </tbody>
              </table>
              <ul
                v-if="expandedIds.has(change.id) && change.dependency_summary.length > 0"
                class="lai-related-list"
              >
                <li
                  v-for="dep in change.dependency_summary"
                  :key="`${dep.entity_type}-${dep.entity_id}`"
                >
                  <span class="lai-related-meta">
                    关联：{{ entityTypeLabels[dep.entity_type] ?? dep.entity_type }} ·
                    {{ dep.entity_name }}
                  </span>
                </li>
              </ul>
            </div>
          </div>
          <p
            v-if="changes.length === 0 && !changesLoading"
            class="lai-related-empty"
          >
            暂无匹配变更
          </p>
          <ListPager
            :page="page"
            :page-size="pageSize"
            :total="total"
            @update:page="applyPage"
            @update:page-size="applyPageSize"
          />
        </template>
      </div>
    </template>

    <ConfirmDialog
      :open="revertDialogOpen"
      title="撤销单项变更"
      :message="`将撤销 ${revertTarget?.entity_name ?? ''} 的${changeTypeLabels[revertTarget?.change_type ?? ''] ?? ''}变更，恢复到活动快照值。`"
      :danger="revertTarget?.change_type === 'DELETE'"
      require-reason
      :loading="revertLoading"
      :error-text="revertError"
      @update:open="(value: boolean) => (revertDialogOpen = value)"
      @confirm="(payload: { reason: string }) => { revertReason = payload.reason; confirmRevert() }"
    />

    <ConfirmDialog
      :open="revertAllOpen"
      title="全部撤销"
      message="将把全部未发布配置恢复到基线快照，操作不可部分回滚。"
      danger
      require-reason
      require-confirm-text="REVERT ALL"
      :loading="revertAllLoading"
      @update:open="(value: boolean) => (revertAllOpen = value)"
      @confirm="
        (payload: { reason: string; confirmText: string }) => {
          revertAllReason = payload.reason
          revertAllConfirmText = payload.confirmText
          confirmRevertAll()
        }
      "
    />
    <p
      v-if="revertAllError && !revertAllOpen"
      class="lai-form-message-error"
      role="alert"
    >
      {{ revertAllError }}
    </p>
  </section>
</template>
