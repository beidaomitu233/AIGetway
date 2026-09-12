<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, isAbortError } from '@/api/errors'
import { amountUsage, decimalUnits, decimalText, positiveAmount, positiveInteger, validPeriod, applicationStatusLabels as statusLabel, applicationEnvironmentLabels as environmentLabel } from './applicationValues'
import ApplicationQuotaSummary from './ApplicationQuotaSummary.vue'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import ApplicationKeyPanel from './ApplicationKeyPanel.vue'
import { formatDateTime } from '@/app/display'
import { Permission } from '@/app/permissions'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useFormSubmit } from '@/composables/useFormSubmit'
import {
  changeApplicationStatus,
  adjustApplicationQuota,
  fetchApplication,
  fetchApplicationMembers,
  fetchApplicationQuotaAdjustments,
  resetApplicationQuotaUsage,
  updateApplicationModels,
  updateApplicationQuota,
  type ApplicationDetail,
  type ApplicationMemberView,
  type ApplicationModelPermission,
  type ApplicationQuotaAdjustment,
  type ApplicationStatus,
} from '@/api/applications'
import { fetchModelAliases, type ModelAliasListItem } from '@/api/modelAliases'

/** 应用级模型参数上限的表单状态；空值表示不施加该维度限制。 */
interface ModelConstraintForm {
  maxOutputTokens: string | number
  streamAllowed: '' | 'allow' | 'deny'
}

const route = useRoute()
const store = useBootstrapStore()
const id = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const detail = ref<ApplicationDetail | null>(null)
const loading = ref(true)
const refreshing = ref(false)
let contextVersion = 0
let loadSequence = 0
let modelsController: AbortController | null = null
let detailController: AbortController | null = null
let membersController: AbortController | null = null
let adjustmentsController: AbortController | null = null
const loadError = ref<unknown>(null)
const statusDialogOpen = ref(false)
const targetStatus = ref<ApplicationStatus>('DISABLED')
const statusReason = ref('')
const writable = computed(() => detail.value !== null && ['ACTIVE', 'DISABLED'].includes(detail.value.status))
const canManage = computed(() => store.can(Permission.applicationManage) && writable.value)
const canViewKeys = computed(() => store.can(Permission.applicationKeyView))
const canViewQuota = computed(() => store.can(Permission.applicationQuotaView))
const canManageQuota = computed(() => store.can(Permission.applicationQuotaManage) && writable.value)
const canManageModels = computed(() => store.can(Permission.applicationModelManage) && writable.value)

/** 应用详情页签（PRD 9.2.3）；当前页签写入 URL，返回列表后可还原。 */
type DetailTab = 'overview' | 'keys' | 'models' | 'quota' | 'calls' | 'usage' | 'members'

const router = useRouter()
const tabs: { key: DetailTab; label: string }[] = [
  { key: 'overview', label: '概览' },
  { key: 'keys', label: '接入密钥' },
  { key: 'models', label: '可用模型' },
  { key: 'quota', label: '额度与速率' },
  { key: 'calls', label: '调用记录' },
  { key: 'usage', label: '用量成本' },
  { key: 'members', label: '成员与审计' },
]
const visibleTabs = computed(() => tabs.filter(tab => {
  if (tab.key === 'keys') return canViewKeys.value
  if (tab.key === 'quota') return canViewQuota.value
  if (tab.key === 'models') return store.can(Permission.applicationModelView)
  if (tab.key === 'calls') return store.can(Permission.traceView)
  if (tab.key === 'usage') return store.can(Permission.usageView)
  return true
}))
const activeTab = ref<DetailTab>('overview')

const members = ref<ApplicationMemberView[]>([])
const membersLoading = ref(false)
const membersLoadError = ref<unknown>(null)
const memberRoleLabel: Record<string, string> = { OWNER: '负责人', VIEWER: '只读成员' }

/** 接入检查清单：未完成时给出最短接入路径，完成后只保留运行摘要。 */
const onboardingSteps = computed(() => [
  {
    label: '授权虚拟模型',
    done: (detail.value?.models.filter((item) => item.enabled).length ?? 0) > 0,
    hint: '在“可用模型”页签选择允许调用的虚拟模型',
  },
  {
    label: '签发应用密钥',
    done: (detail.value?.active_key_count ?? 0) > 0,
    hint: '在“接入密钥”页签创建密钥，原文只在创建成功时显示一次',
  },
  {
    label: '完成首次调用',
    done: detail.value?.last_called_at != null,
    hint: '使用应用密钥调用 /v1/chat/completions',
  },
])
const onboardingComplete = computed(() => detail.value?.last_called_at != null)

function selectTab(tab: DetailTab): void {
  if (!visibleTabs.value.some(item => item.key === tab)) return
  void router.replace({ query: { ...route.query, tab } })
}

function syncTabFromQuery(): void {
  const raw = typeof route.query.tab === 'string' ? route.query.tab : ''
  activeTab.value = visibleTabs.value.some((item) => item.key === raw) ? (raw as DetailTab) : 'overview'
  if (activeTab.value === 'members') void loadMembers()
}

async function loadMembers(): Promise<void> {
  if (!detail.value) return
  membersController?.abort()
  const controller = new AbortController()
  membersController = controller
  membersLoading.value = true
  membersLoadError.value = null
  try {
    const result = await fetchApplicationMembers(detail.value.id, controller.signal)
    if (!controller.signal.aborted) members.value = result
  } catch (error) {
    if (!controller.signal.aborted && !isAbortError(error)) membersLoadError.value = error
  } finally {
    if (!controller.signal.aborted) membersLoading.value = false
  }
}

const statusSubmission = useFormSubmit()
const quotaSubmission = useFormSubmit()
const modelSubmission = useFormSubmit()
const adjustmentSubmission = useFormSubmit()
const resetSubmission = useFormSubmit()
const quotaDialogOpen = ref(false)
const modelDialogOpen = ref(false)
const adjustmentDialogOpen = ref(false)
const resetDialogOpen = ref(false)
const adjustments = ref<ApplicationQuotaAdjustment[]>([])
const adjustmentsLoading = ref(false)
const adjustmentsLoadError = ref<unknown>(null)
const availableModels = ref<ModelAliasListItem[]>([])
const modelsLoading = ref(false)
const modelsLoadError = ref<unknown>(null)
const selectedModelIds = ref<string[]>([])
const modelReason = ref('')
const modelConstraints = ref<Record<string, ModelConstraintForm>>({})
const adjustmentForm = reactive({
  dimension: 'TOKEN_LIMIT' as 'TOKEN_LIMIT' | 'AMOUNT_LIMIT',
  delta: '',
  reason: '',
  idempotency_key: '',
})
const resetForm = reactive({
  dimension: 'TOKEN_USAGE' as 'TOKEN_USAGE' | 'AMOUNT_USAGE',
  reason: '',
  confirmation_code: '',
  idempotency_key: '',
})
const quotaForm = reactive({
  token_limited: true,
  token_limit: 1 as number | null,
  amount_limited: true,
  amount_limit: '1',
  currency: 'CNY',
  rpm_limited: true,
  rpm: 1 as number | null,
  tpm_limited: true,
  tpm: 1 as number | null,
  period_type: 'LIFECYCLE' as ApplicationDetail['quota']['period_type'],
  period_start: '',
  period_end: '',
  reason: '',
})

const periodLabel: Record<string, string> = {
  LIFECYCLE: '应用生命周期', DAY: '每日', MONTH: '每月', CUSTOM: '自定义',
}
const adjustmentLabel: Record<ApplicationQuotaAdjustment['dimension'], string> = {
  TOKEN_LIMIT: 'Token 额度调整',
  AMOUNT_LIMIT: '金额预算调整',
  TOKEN_USAGE_RESET: 'Token 用量重置',
  AMOUNT_USAGE_RESET: '金额用量重置',
}

function usageText(used: number, reserved: number, limit: number | null): string {
  const consumed = used + reserved
  return limit == null ? `${consumed.toLocaleString()} / 不限` : `${consumed.toLocaleString()} / ${limit.toLocaleString()}`
}

function amountText(): string {
  if (!detail.value) return '—'
  const quota = detail.value.quota
  return amountUsage(quota.amount_used, quota.amount_reserved, quota.amount_limit, quota.currency)
}

function openStatusDialog(status: ApplicationStatus): void {
  if (!canManage.value) return
  targetStatus.value = status
  statusReason.value = ''
  statusSubmission.reset()
  statusDialogOpen.value = true
}

async function applyStatus(): Promise<void> {
  if (!canManage.value || !detail.value || !statusReason.value.trim() || statusReason.value.length > 500) return
  const context = contextVersion
  const result = await statusSubmission.submit(async () => {
    const response = await changeApplicationStatus(detail.value!.id, {
      status: targetStatus.value,
      version: detail.value!.version,
      reason: statusReason.value.trim(),
    })
    if (context === contextVersion && response.entity) detail.value = response.entity
  })
  if (result.ok && context === contextVersion) statusDialogOpen.value = false
}

function asLocalDateTime(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function asOffsetDateTime(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

function openQuotaDialog(): void {
  if (!canManageQuota.value || !detail.value) return
  quotaSubmission.reset()
  const quota = detail.value.quota
  quotaForm.token_limited = quota.token_limit != null
  quotaForm.token_limit = quota.token_limit ?? 1
  quotaForm.amount_limited = quota.amount_limit != null
  quotaForm.amount_limit = quota.amount_limit ?? '1'
  quotaForm.currency = quota.currency
  quotaForm.rpm_limited = quota.rpm != null
  quotaForm.rpm = quota.rpm ?? 1
  quotaForm.tpm_limited = quota.tpm != null
  quotaForm.tpm = quota.tpm ?? 1
  quotaForm.period_type = quota.period_type
  quotaForm.period_start = asLocalDateTime(quota.period_start)
  quotaForm.period_end = asLocalDateTime(quota.period_end)
  quotaForm.reason = ''
  quotaDialogOpen.value = true
}

const quotaInvalid = computed(() => Boolean(
  !quotaForm.reason.trim()
  || (quotaForm.token_limited && !positiveInteger(quotaForm.token_limit))
  || (quotaForm.amount_limited && !positiveAmount(quotaForm.amount_limit))
  || !/^[A-Za-z]{3}$/.test(quotaForm.currency)
  || (quotaForm.rpm_limited && !positiveInteger(quotaForm.rpm))
  || (quotaForm.tpm_limited && !positiveInteger(quotaForm.tpm))
  || !validPeriod(quotaForm.period_type, quotaForm.period_start, quotaForm.period_end)
))

const quotaStopsAdmission = computed(() => {
  if (!detail.value) return false
  const quota = detail.value.quota
  const amount = decimalUnits(quotaForm.amount_limit)
  const used = decimalUnits(quota.amount_used), reserved = decimalUnits(quota.amount_reserved)
  return (quotaForm.token_limited && positiveInteger(quotaForm.token_limit) && quotaForm.token_limit < quota.tokens_used + quota.tokens_reserved)
    || (quotaForm.amount_limited && amount !== null && used !== null && reserved !== null && amount < used + reserved)
})
const adjustmentPreview = computed(() => {
  if (!detail.value || adjustmentInvalid.value) return null
  const quota = detail.value.quota
  const delta = decimalUnits(adjustmentForm.delta)
  const current = adjustmentForm.dimension === 'TOKEN_LIMIT' ? (quota.token_limit === null ? null : decimalUnits(String(quota.token_limit))) : (quota.amount_limit === null ? null : decimalUnits(quota.amount_limit))
  if (delta === null || current === null) return null
  const after = current + delta
  const consumed = adjustmentForm.dimension === 'TOKEN_LIMIT' ? decimalUnits(String(quota.tokens_used + quota.tokens_reserved)) : (decimalUnits(quota.amount_used) ?? 0n) + (decimalUnits(quota.amount_reserved) ?? 0n)
  return { after: decimalText(after), stops: consumed !== null && after < consumed }
})
async function saveQuota(): Promise<void> {
  if (!canManageQuota.value || !detail.value || quotaInvalid.value) return
  const context = contextVersion
  const result = await quotaSubmission.submit(async () => {
    const response = await updateApplicationQuota(detail.value!.id, {
      token_limit: quotaForm.token_limited ? quotaForm.token_limit : null,
      amount_limit: quotaForm.amount_limited ? quotaForm.amount_limit.trim() : null,
      currency: quotaForm.currency.trim().toUpperCase(),
      rpm: quotaForm.rpm_limited ? quotaForm.rpm : null,
      tpm: quotaForm.tpm_limited ? quotaForm.tpm : null,
      period_type: quotaForm.period_type,
      period_start: quotaForm.period_type === 'CUSTOM' ? asOffsetDateTime(quotaForm.period_start) : null,
      period_end: quotaForm.period_type === 'CUSTOM' ? asOffsetDateTime(quotaForm.period_end) : null,
      version: detail.value!.quota.version,
      reason: quotaForm.reason.trim(),
    })
    if (context === contextVersion && response.entity) detail.value = response.entity
  })
  if (result.ok && context === contextVersion) quotaDialogOpen.value = false
}

function openAdjustmentDialog(): void {
  if (!canManageQuota.value) return
  adjustmentSubmission.reset()
  adjustmentForm.dimension = 'TOKEN_LIMIT'
  adjustmentForm.delta = ''
  adjustmentForm.reason = ''
  adjustmentForm.idempotency_key = crypto.randomUUID()
  adjustmentDialogOpen.value = true
}

const adjustmentInvalid = computed(() => {
  if (!adjustmentForm.delta.trim() || !adjustmentForm.reason.trim()) return true
  const delta = decimalUnits(adjustmentForm.delta.trim())
  if (delta === null || delta === 0n) return true
  return adjustmentForm.dimension === 'TOKEN_LIMIT' && !/^-?\d+$/.test(adjustmentForm.delta.trim())
})

async function saveAdjustment(): Promise<void> {
  if (!canManageQuota.value || !detail.value || adjustmentInvalid.value) return
  const context = contextVersion
  const result = await adjustmentSubmission.submit(async () => {
    const response = await adjustApplicationQuota(detail.value!.id, {
      dimension: adjustmentForm.dimension,
      delta: adjustmentForm.delta.trim(),
      reason: adjustmentForm.reason.trim(),
      idempotency_key: adjustmentForm.idempotency_key,
      quota_version: detail.value!.quota.version,
    })
    if (context === contextVersion && response.entity) detail.value = response.entity
  })
  if (result.ok && context === contextVersion) {
    adjustmentDialogOpen.value = false
    await loadAdjustments()
  }
}

function openResetDialog(): void {
  if (!canManageQuota.value || !detail.value) return
  resetSubmission.reset()
  resetForm.dimension = detail.value.quota.tokens_used > 0 ? 'TOKEN_USAGE' : 'AMOUNT_USAGE'
  resetForm.reason = ''
  resetForm.confirmation_code = ''
  resetForm.idempotency_key = crypto.randomUUID()
  resetDialogOpen.value = true
}

const resetInvalid = computed(() => !detail.value
  || !resetForm.reason.trim()
  || resetForm.confirmation_code !== detail.value.code
  || (resetForm.dimension === 'TOKEN_USAGE'
    ? detail.value.quota.tokens_used <= 0
    : !positiveAmount(detail.value.quota.amount_used)))

async function saveReset(): Promise<void> {
  if (!canManageQuota.value || !detail.value || resetInvalid.value) return
  const context = contextVersion
  const result = await resetSubmission.submit(async () => {
    const response = await resetApplicationQuotaUsage(detail.value!.id, {
      dimension: resetForm.dimension,
      reason: resetForm.reason.trim(),
      confirmation_code: resetForm.confirmation_code,
      idempotency_key: resetForm.idempotency_key,
      quota_version: detail.value!.quota.version,
    })
    if (context === contextVersion && response.entity) detail.value = response.entity
  })
  if (result.ok && context === contextVersion) {
    resetDialogOpen.value = false
    await loadAdjustments()
  }
}

async function loadAdjustments(): Promise<void> {
  if (!canViewQuota.value || !detail.value) return
  adjustmentsController?.abort()
  const controller = new AbortController()
  adjustmentsController = controller
  adjustmentsLoading.value = true
  adjustmentsLoadError.value = null
  try {
    const result = await fetchApplicationQuotaAdjustments(id.value, controller.signal)
    if (!controller.signal.aborted) adjustments.value = result
  } catch (error) {
    if (!controller.signal.aborted && !isAbortError(error)) adjustmentsLoadError.value = error
  } finally {
    if (!controller.signal.aborted) adjustmentsLoading.value = false
  }
}

/** 已授权模型的应用级参数上限摘要；未配置或无限制时返回空串。 */
function modelConstraintText(model: ApplicationModelPermission): string {
  if (!model.enabled) return ''
  const parts: string[] = []
  if (model.max_output_tokens !== null) {
    parts.push(`最大输出 ${model.max_output_tokens.toLocaleString()} Token`)
  }
  if (model.stream_allowed !== null) {
    parts.push(model.stream_allowed ? '允许流式' : '禁止流式')
  }
  return parts.join(' · ')
}

async function openModelDialog(): Promise<void> {
  if (!canManageModels.value || !detail.value) return
  modelSubmission.reset()
  selectedModelIds.value = detail.value.models
    .filter((item) => item.enabled)
    .map((item) => item.virtual_model_id)
  modelConstraints.value = {}
  for (const item of detail.value.models) {
    if (!item.enabled) continue
    modelConstraints.value[item.virtual_model_id] = {
      maxOutputTokens: item.max_output_tokens === null ? '' : String(item.max_output_tokens),
      streamAllowed: item.stream_allowed === null ? '' : item.stream_allowed ? 'allow' : 'deny',
    }
  }
  modelReason.value = ''
  modelDialogOpen.value = true
  if (availableModels.value.length) {
    ensureConstraintForms()
    return
  }
  modelsController?.abort()
  const controller = new AbortController()
  modelsController = controller
  modelsLoading.value = true
  modelsLoadError.value = null
  try {
    const page = await fetchModelAliases({ enabled: true, page: 1, page_size: 100, sort: 'alias' }, controller.signal)
    if (controller.signal.aborted) return
    availableModels.value = page.items
    ensureConstraintForms()
  } catch (error) {
    if (!controller.signal.aborted && !isAbortError(error)) modelsLoadError.value = error
  } finally {
    if (!controller.signal.aborted) modelsLoading.value = false
  }
}

function ensureConstraintForm(modelId: string): void {
  if (!modelConstraints.value[modelId]) {
    modelConstraints.value[modelId] = { maxOutputTokens: '', streamAllowed: '' }
  }
}

function ensureConstraintForms(): void {
  for (const model of availableModels.value) {
    ensureConstraintForm(model.id)
  }
}

/** 留空返回 null 表示不限；非法值返回 NaN 供校验拦截。 */
function parseMaxOutputTokens(form?: ModelConstraintForm): number | null {
  const raw = form?.maxOutputTokens
  if (raw === undefined || raw === null || raw === '') return null
  const parsed = typeof raw === 'number' ? raw : Number(String(raw).trim())
  return Number.isSafeInteger(parsed) && parsed >= 1 ? parsed : Number.NaN
}

/** 最大输出 Token 只接受大于 0 的整数；留空表示不限制。 */
const modelConstraintInvalid = computed(() =>
  selectedModelIds.value.some((modelId) =>
    Number.isNaN(parseMaxOutputTokens(modelConstraints.value[modelId])),
  ),
)

function modelConstraintPayload(): {
  virtual_model_id: string
  max_output_tokens: number | null
  stream_allowed: boolean | null
}[] {
  const payload: {
    virtual_model_id: string
    max_output_tokens: number | null
    stream_allowed: boolean | null
  }[] = []
  for (const modelId of selectedModelIds.value) {
    const form = modelConstraints.value[modelId]
    const parsedMax = parseMaxOutputTokens(form)
    const maxOutputTokens = parsedMax === null || Number.isNaN(parsedMax) ? null : parsedMax
    const streamAllowed = form?.streamAllowed === 'allow'
      ? true
      : form?.streamAllowed === 'deny' ? false : null
    if (maxOutputTokens === null && streamAllowed === null) continue
    payload.push({
      virtual_model_id: modelId,
      max_output_tokens: maxOutputTokens,
      stream_allowed: streamAllowed,
    })
  }
  return payload
}

async function saveModels(): Promise<void> {
  if (!canManageModels.value || !detail.value || modelsLoading.value || modelsLoadError.value || !modelReason.value.trim() || modelConstraintInvalid.value) return
  const context = contextVersion
  const result = await modelSubmission.submit(async () => {
    const response = await updateApplicationModels(detail.value!.id, {
      virtual_model_ids: selectedModelIds.value,
      constraints: modelConstraintPayload(),
      application_version: detail.value!.version,
      reason: modelReason.value.trim(),
    })
    if (context === contextVersion && response.entity) detail.value = response.entity
  })
  if (result.ok && context === contextVersion) modelDialogOpen.value = false
}

async function load(): Promise<void> {
  const sequence = ++loadSequence
  detailController?.abort()
  detailController = new AbortController()
  loading.value = detail.value === null
  refreshing.value = detail.value !== null
  loadError.value = null
  try {
    if (!store.can(Permission.applicationView)) throw new ApiError(403, { code: 'ACCESS_DENIED', type: 'permission', message: '无权查看应用' }, 'local-permission')
    const result = await fetchApplication(id.value, detailController.signal)
    if (sequence !== loadSequence) return
    detail.value = result
    void loadAdjustments()
    syncTabFromQuery()
  } catch (error) {
    if (sequence !== loadSequence || isAbortError(error)) return
    if (error instanceof ApiError && [401, 403, 404].includes(error.status)) detail.value = null
    loadError.value = error
  } finally {
    if (sequence === loadSequence) { loading.value = false; refreshing.value = false }
  }
}
function clearContext(): void {
  ++contextVersion
  ++loadSequence
  modelsController?.abort()
  detailController?.abort(); membersController?.abort(); adjustmentsController?.abort()
  detail.value = null; members.value = []; adjustments.value = []; availableModels.value = []
  statusDialogOpen.value = false; quotaDialogOpen.value = false; modelDialogOpen.value = false
  adjustmentDialogOpen.value = false; resetDialogOpen.value = false
}
watch(() => [id.value, store.userId, store.permissions.join(',')], () => { clearContext(); void load() }, { immediate: true })
watch(() => route.query.tab, syncTabFromQuery)
onScopeDispose(clearContext)
</script>

<template>
  <section class="lai-page">
    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="!detail"
      status="error"
      :error="loadError"
      @retry="load"
    />

    <template v-else>
      <PageState
        v-if="loadError"
        status="error"
        :error="loadError"
        @retry="load"
      />
      <p
        v-if="refreshing"
        role="status"
      >
        刷新中…
      </p>
      <div class="detail-header">
        <div>
          <div class="title-line">
            <h1 class="lai-page-title">
              {{ detail.name }}
            </h1>
            <span
              class="status"
              :class="`status-${detail.status.toLowerCase()}`"
            >{{ statusLabel[detail.status] || detail.status }}</span>
          </div>
          <p><span class="lai-cell-mono">{{ detail.code }}</span> · {{ environmentLabel[detail.environment] || detail.environment }} · {{ detail.owner_name }}</p>
        </div>
        <div
          v-if="canManage"
          class="header-actions"
        >
          <RouterLink
            v-if="detail.status !== 'ARCHIVED'"
            :to="`/ui/applications/${detail.id}/settings`"
            class="lai-btn"
          >
            编辑
          </RouterLink>
          <button
            v-if="detail.status === 'ACTIVE'"
            class="lai-btn"
            type="button"
            @click="openStatusDialog('DISABLED')"
          >
            停用
          </button>
          <button
            v-else-if="detail.status === 'DISABLED'"
            class="lai-btn lai-btn-primary"
            type="button"
            @click="openStatusDialog('ACTIVE')"
          >
            启用
          </button>
          <button
            v-if="detail.status === 'DISABLED'"
            class="lai-btn"
            type="button"
            @click="openStatusDialog('ARCHIVED')"
          >
            归档
          </button>
        </div>
      </div>

      <div
        v-if="detail.status === 'DISABLED'"
        class="notice warning"
      >
        应用已停用。所有应用密钥应停止新调用，历史调用与费用记录继续保留。
      </div>
      <div
        v-else-if="detail.status === 'ARCHIVED'"
        class="notice"
      >
        应用已归档且不可恢复编辑，历史治理与调用快照仍保留。
      </div>

      <nav
        class="detail-tabs"
        role="tablist"
        aria-label="应用详情页签"
      >
        <button
          v-for="tab in visibleTabs"
          :key="tab.key"
          type="button"
          role="tab"
          class="detail-tab"
          :class="{ 'is-active': activeTab === tab.key }"
          :aria-selected="activeTab === tab.key"
          @click="selectTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </nav>

      <div
        v-show="activeTab === 'overview'"
        role="tabpanel"
        aria-label="概览"
      >
        <div class="metric-grid">
          <div class="metric">
            <span>活跃密钥</span><strong>{{ detail.active_key_count }}</strong><small>仅统计未撤销且有效的应用密钥</small>
          </div>
          <div class="metric">
            <span>授权模型</span><strong>{{ detail.models.filter((item) => item.enabled).length }}</strong><small>调用仅允许使用已授权虚拟模型</small>
          </div>
          <div
            v-if="canViewQuota"
            class="metric"
          >
            <span>Token 使用</span><strong>{{ usageText(detail.quota.tokens_used, detail.quota.tokens_reserved, detail.quota.token_limit) }}</strong><small>已用与预占合并展示</small>
          </div>
          <div
            v-if="canViewQuota"
            class="metric"
          >
            <span>金额使用</span><strong>{{ amountText() }}</strong><small>按价格快照归属到本应用</small>
          </div>
        </div>

        <div class="workspace-grid">
          <div class="main-column">
            <div class="lai-card">
              <div class="card-heading">
                <h2 class="lai-card-title">
                  接入信息
                </h2>
                <RouterLink
                  :to="`/ui/applications/${detail.id}/integration`"
                  class="lai-btn lai-btn-small"
                >
                  开发接入
                </RouterLink>
              </div>
              <div class="lai-summary-grid">
                <div class="lai-summary-item">
                  <span class="lai-summary-label">应用编码</span><span class="lai-cell-mono">{{ detail.code }}</span>
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">API 地址</span><span class="lai-cell-mono">/v1/chat/completions</span>
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">最近调用</span>{{ formatDateTime(detail.last_called_at, store.timezone, '尚未调用') }}
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">活跃应用密钥</span>{{ detail.active_key_count }}
                </div>
              </div>
              <p class="card-note">
                OpenAI 兼容协议。业务系统只持有平台签发的应用密钥，不接触供应商 Key。应用密钥原文只应在创建或轮换成功时显示一次。
              </p>
            </div>

            <div class="lai-card">
              <div class="card-heading">
                <h2 class="lai-card-title">
                  {{ onboardingComplete ? '运行摘要' : '接入检查清单' }}
                </h2>
                <button
                  v-if="onboardingComplete"
                  type="button"
                  class="lai-btn lai-btn-small"
                  :disabled="loading"
                  @click="load"
                >
                  刷新
                </button>
              </div>
              <div
                v-if="onboardingComplete"
                class="lai-summary-grid"
              >
                <div class="lai-summary-item">
                  <span class="lai-summary-label">最近调用</span>{{ formatDateTime(detail.last_called_at, store.timezone, '尚未调用') }}
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">可用模型</span>{{ detail.models.filter((item) => item.enabled).length }} 个
                </div>
                <div class="lai-summary-item">
                  <span class="lai-summary-label">活跃密钥</span>{{ detail.active_key_count }} 个
                </div>
                <div
                  v-if="canViewQuota"
                  class="lai-summary-item"
                >
                  <span class="lai-summary-label">已用 Token</span>{{ detail.quota.tokens_used.toLocaleString() }}
                </div>
              </div>
              <ul
                v-else
                class="onboarding-list"
              >
                <li
                  v-for="step in onboardingSteps"
                  :key="step.label"
                  :class="{ done: step.done }"
                >
                  <strong>{{ step.done ? '已完成' : '待完成' }} · {{ step.label }}</strong>
                  <span>{{ step.hint }}</span>
                </li>
              </ul>
            </div>
          </div>

          <aside>
            <div class="lai-card">
              <h2 class="lai-card-title">
                基本信息
              </h2>
              <dl class="property-list">
                <div><dt>负责人</dt><dd>{{ detail.owner_name }}（{{ detail.owner_id }}）</dd></div>
                <div><dt>所属部门</dt><dd>{{ detail.department || '—' }}</dd></div>
                <div><dt>环境</dt><dd>{{ environmentLabel[detail.environment] || detail.environment }}</dd></div>
                <div><dt>创建时间</dt><dd>{{ formatDateTime(detail.created_at, store.timezone) }}</dd></div>
                <div><dt>更新时间</dt><dd>{{ formatDateTime(detail.updated_at, store.timezone) }}</dd></div>
                <div v-if="detail.description">
                  <dt>说明</dt><dd>{{ detail.description }}</dd>
                </div>
              </dl>
            </div>
            <div
              v-if="canViewQuota"
              class="lai-card"
            >
              <div class="card-heading">
                <h2 class="lai-card-title">
                  额度概览
                </h2>
              </div>
              <dl class="property-list">
                <div><dt>Token 额度</dt><dd>{{ usageText(detail.quota.tokens_used, detail.quota.tokens_reserved, detail.quota.token_limit) }}</dd></div>
                <div><dt>金额预算</dt><dd>{{ amountText() }}</dd></div>
                <div><dt>RPM / TPM</dt><dd>{{ detail.quota.rpm ?? '不限' }} / {{ detail.quota.tpm == null ? '不限' : detail.quota.tpm.toLocaleString() }}</dd></div>
              </dl>
            </div>
          </aside>
        </div>
      </div>

      <div
        v-show="activeTab === 'keys'"
        role="tabpanel"
        aria-label="接入密钥"
      >
        <ApplicationKeyPanel
          v-if="canViewKeys"
          :key="detail.id"
          :application-rpm="detail.quota.rpm"
          :application-tpm="detail.quota.tpm"
          :application-id="detail.id"
          :application-active="detail.status === 'ACTIVE'"
          :application-models="detail.models.filter((item) => item.enabled)"
          @changed="load"
        />
      </div>

      <div
        v-if="store.can(Permission.applicationModelView)"
        v-show="activeTab === 'models'"
        role="tabpanel"
        aria-label="可用模型"
      >
        <div class="lai-card">
          <div class="card-heading">
            <h2 class="lai-card-title">
              可用虚拟模型
            </h2>
            <button
              v-if="canManageModels && detail.status !== 'ARCHIVED'"
              type="button"
              class="lai-btn lai-btn-small"
              @click="openModelDialog"
            >
              管理授权
            </button>
            <span v-else>{{ detail.models.filter((item) => item.enabled).length }} 个</span>
          </div>
          <div
            v-if="detail.models.length"
            class="model-list"
          >
            <div
              v-for="model in detail.models"
              :key="model.id"
              class="model-row"
            >
              <div><strong>{{ model.virtual_model_code || model.virtual_model_id }}</strong><small>请求 model 字段</small></div>
              <div class="model-row-meta">
                <span v-if="modelConstraintText(model)">{{ modelConstraintText(model) }}</span>
                <span :class="model.enabled ? 'enabled-text' : 'disabled-text'">{{ model.enabled ? '已授权' : '已停用' }}</span>
              </div>
            </div>
          </div>
          <p
            v-else
            class="empty-inline"
          >
            尚未授权虚拟模型，应用当前无法完成模型调用。
          </p>
          <p class="card-note">
            应用级参数上限只能收紧，不能突破虚拟模型与上游候选的能力边界；越界的显式参数在路由前被拒绝。
          </p>
        </div>
      </div>

      <div
        v-if="canViewQuota"
        v-show="activeTab === 'quota'"
        role="tabpanel"
        aria-label="额度与速率"
      >
        <div class="lai-card">
          <div class="card-heading">
            <h2 class="lai-card-title">
              额度与速率
            </h2>
            <div
              v-if="canManageQuota && detail.status !== 'ARCHIVED'"
              class="compact-actions"
            >
              <button
                type="button"
                class="lai-btn lai-btn-small"
                @click="openAdjustmentDialog"
              >
                人工增减
              </button>
              <button
                type="button"
                class="lai-btn lai-btn-small"
                :disabled="detail.quota.tokens_used <= 0 && !positiveAmount(detail.quota.amount_used)"
                @click="openResetDialog"
              >
                重置用量
              </button>
              <button
                type="button"
                class="lai-btn lai-btn-small"
                @click="openQuotaDialog"
              >
                编辑策略
              </button>
            </div>
          </div>
          <dl class="property-list">
            <div><dt>Token 额度</dt><dd>{{ usageText(detail.quota.tokens_used, detail.quota.tokens_reserved, detail.quota.token_limit) }}</dd></div>
            <div><dt>金额预算</dt><dd>{{ amountText() }}</dd></div>
            <div><dt>RPM</dt><dd>{{ detail.quota.rpm ?? '不限' }}</dd></div>
            <div><dt>TPM</dt><dd>{{ detail.quota.tpm == null ? '不限' : detail.quota.tpm.toLocaleString() }}</dd></div>
            <div><dt>结算周期</dt><dd>{{ periodLabel[detail.quota.period_type] }}</dd></div>
          </dl>
          <ApplicationQuotaSummary
            :quota="detail.quota"
            :timezone="store.timezone"
          />
          <div
            v-if="canViewQuota"
            class="adjustment-history"
          >
            <div class="history-heading">
              <h3>最近额度流水</h3>
              <button
                type="button"
                class="lai-btn lai-btn-text"
                :disabled="adjustmentsLoading"
                @click="loadAdjustments"
              >
                刷新
              </button>
            </div>
            <p
              v-if="adjustmentsLoading"
              class="history-state"
            >
              正在加载…
            </p>
            <PageState
              v-else-if="adjustmentsLoadError"
              status="error"
              :error="adjustmentsLoadError"
              @retry="loadAdjustments"
            />
            <ul
              v-else-if="adjustments.length"
              class="adjustment-list"
            >
              <li
                v-for="item in adjustments"
                :key="item.id"
              >
                <div><strong>{{ adjustmentLabel[item.dimension] }}</strong><time>{{ formatDateTime(item.effective_at, store.timezone) }} · {{ item.operator_id }}</time></div>
                <p><span class="lai-cell-mono">{{ item.before_value }} → {{ item.after_value }}</span><span>{{ item.reason }}</span></p>
              </li>
            </ul>
            <p
              v-else
              class="history-state"
            >
              暂无额度调整或重置记录。
            </p>
          </div>
        </div>
      </div>

      <div
        v-show="activeTab === 'calls'"
        role="tabpanel"
        aria-label="调用记录"
      >
        <div class="lai-card">
          <div class="card-heading">
            <h2 class="lai-card-title">
              调用记录
            </h2>
            <RouterLink
              :to="{ path: '/ui/traces', query: { application: detail.code } }"
              class="lai-btn lai-btn-small"
            >
              查看全部
            </RouterLink>
          </div>
          <dl class="property-list">
            <div><dt>最近调用</dt><dd>{{ formatDateTime(detail.last_called_at, store.timezone, '尚未调用') }}</dd></div>
            <div>
              <dt>应用编码</dt><dd class="lai-cell-mono">
                {{ detail.code }}
              </dd>
            </div>
            <div><dt>活跃密钥</dt><dd>{{ detail.active_key_count }} 个</dd></div>
          </dl>
          <p class="card-note">
            调用记录按 request_id 展示准入、路由、每次 Attempt、恢复动作与结算结果；应用负责人只能查看本应用。
          </p>
        </div>
      </div>

      <div
        v-show="activeTab === 'usage'"
        role="tabpanel"
        aria-label="用量成本"
      >
        <div class="lai-card">
          <div class="card-heading">
            <h2 class="lai-card-title">
              用量与成本
            </h2>
            <RouterLink
              :to="{ path: '/ui/usage', query: { application: detail.code } }"
              class="lai-btn lai-btn-small"
            >
              查看全部
            </RouterLink>
          </div>
          <dl class="property-list">
            <div><dt>Token 使用</dt><dd>{{ usageText(detail.quota.tokens_used, detail.quota.tokens_reserved, detail.quota.token_limit) }}</dd></div>
            <div><dt>金额使用</dt><dd>{{ amountText() }}</dd></div>
            <div><dt>结算周期</dt><dd>{{ periodLabel[detail.quota.period_type] }}</dd></div>
          </dl>
          <p class="card-note">
            成本按请求发生时的价格快照归属到本应用；供应商未返回 Usage 时按估算标记，不与实际值混淆。
          </p>
        </div>
      </div>

      <div
        v-show="activeTab === 'members'"
        role="tabpanel"
        aria-label="成员与审计"
      >
        <div class="lai-card">
          <div class="card-heading">
            <h2 class="lai-card-title">
              应用成员
            </h2>
            <button
              type="button"
              class="lai-btn lai-btn-small"
              :disabled="membersLoading"
              @click="loadMembers"
            >
              刷新
            </button>
          </div>
          <p
            v-if="membersLoading"
            class="history-state"
          >
            正在加载…
          </p>
          <div v-else-if="membersLoadError">
            <p>成员加载失败，请重试。</p><PageState
              status="error"
              :error="membersLoadError"
              @retry="loadMembers"
            />
          </div>
          <div
            v-else-if="members.length"
            class="model-list"
          >
            <div
              v-for="member in members"
              :key="member.id"
              class="model-row"
            >
              <div><strong>{{ member.subject_name }}</strong><small>{{ member.subject_id }}</small></div>
              <span class="member-role">{{ memberRoleLabel[member.role] || member.role }}</span>
            </div>
          </div>
          <p
            v-else
            class="empty-inline"
          >
            该应用暂无成员记录。
          </p>
          <p class="card-note">
            成员来源于企业身份系统。成员维护方式仍在产品待确认范围内，当前平台只提供查看。
          </p>
        </div>
        <div class="lai-card">
          <div class="card-heading">
            <h2 class="lai-card-title">
              应用审计
            </h2>
            <RouterLink
              v-if="store.can(Permission.auditView)"
              :to="{ path: '/ui/audit-logs', query: { entity_keyword: detail.id } }"
              class="lai-btn lai-btn-small"
            >
              查看审计
            </RouterLink>
          </div>
          <p class="card-note">
            密钥创建、轮换、撤销、模型授权、额度调整、状态变更与成员变更均写入审计，日志不包含密钥原文。
          </p>
        </div>
      </div>
    </template>

    <div
      v-if="quotaDialogOpen && detail"
      class="lai-dialog-overlay"
      @click.self="quotaDialogOpen = false"
    >
      <div
        class="lai-dialog governance-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="application-quota-title"
      >
        <h2
          id="application-quota-title"
          class="lai-dialog-title"
        >
          调整额度与速率
        </h2>
        <p class="lai-dialog-message">
          降低上限到已用与预占以下会拒绝后续新请求；当前周期已有用量时请核对币种与周期。
        </p>
        <p
          v-if="quotaStopsAdmission"
          class="warning"
          role="alert"
        >
          保存后立即停止新请求：新上限低于已用与预占之和。
        </p>
        <p>生效后上限：Token {{ quotaForm.token_limited ? quotaForm.token_limit : '不限' }}；金额 {{ quotaForm.amount_limited ? quotaForm.amount_limit : '不限' }} {{ quotaForm.currency }}；RPM {{ quotaForm.rpm_limited ? quotaForm.rpm : '不限' }}；TPM {{ quotaForm.tpm_limited ? quotaForm.tpm : '不限' }}</p>
        <div class="governance-grid">
          <FormField
            label="Token 额度"
            :error="quotaSubmission.fieldMessages.value.token_limit"
          >
            <div class="limit-control">
              <label><input
                v-model="quotaForm.token_limited"
                type="checkbox"
              > 限制</label><input
                v-model.number="quotaForm.token_limit"
                class="lai-input"
                type="number"
                min="1"
                :disabled="!quotaForm.token_limited"
              >
            </div>
          </FormField>
          <FormField
            label="金额预算"
            :error="quotaSubmission.fieldMessages.value.amount_limit"
          >
            <div class="amount-control">
              <label><input
                v-model="quotaForm.amount_limited"
                type="checkbox"
              > 限制</label><input
                v-model="quotaForm.amount_limit"
                class="lai-input"
                inputmode="decimal"
                :disabled="!quotaForm.amount_limited"
              ><input
                v-model="quotaForm.currency"
                class="lai-input currency"
                maxlength="3"
                aria-label="币种"
              >
            </div>
          </FormField>
          <FormField
            label="RPM"
            hint="每分钟最大请求数"
            :error="quotaSubmission.fieldMessages.value.rpm"
          >
            <div class="limit-control">
              <label><input
                v-model="quotaForm.rpm_limited"
                type="checkbox"
              > 限制</label><input
                v-model.number="quotaForm.rpm"
                class="lai-input"
                type="number"
                min="1"
                :disabled="!quotaForm.rpm_limited"
              >
            </div>
          </FormField>
          <FormField
            label="TPM"
            hint="每分钟最大 Token 数"
            :error="quotaSubmission.fieldMessages.value.tpm"
          >
            <div class="limit-control">
              <label><input
                v-model="quotaForm.tpm_limited"
                type="checkbox"
              > 限制</label><input
                v-model.number="quotaForm.tpm"
                class="lai-input"
                type="number"
                min="1"
                :disabled="!quotaForm.tpm_limited"
              >
            </div>
          </FormField>
          <FormField
            label="额度周期"
            required
            :error="quotaSubmission.fieldMessages.value.period_type"
          >
            <select
              v-model="quotaForm.period_type"
              class="lai-select full-control"
            >
              <option value="LIFECYCLE">
                应用生命周期
              </option><option value="DAY">
                每日
              </option>
              <option value="MONTH">
                每月
              </option><option value="CUSTOM">
                自定义
              </option>
            </select>
          </FormField>
          <template v-if="quotaForm.period_type === 'CUSTOM'">
            <FormField
              label="开始时间"
              required
            >
              <input
                v-model="quotaForm.period_start"
                class="lai-input"
                type="datetime-local"
              >
            </FormField>
            <FormField
              label="结束时间"
              required
              :error="quotaSubmission.fieldMessages.value.period_end"
            >
              <input
                v-model="quotaForm.period_end"
                class="lai-input"
                type="datetime-local"
              >
            </FormField>
          </template>
          <FormField
            class="wide-field"
            label="调整原因"
            required
            :error="quotaSubmission.fieldMessages.value.reason"
          >
            <textarea
              v-model="quotaForm.reason"
              class="lai-input status-reason"
              maxlength="500"
              rows="3"
              placeholder="必填，将写入审计记录"
            />
          </FormField>
        </div>
        <p
          v-if="quotaSubmission.conflictError.value"
          class="lai-form-message-error"
        >
          额度策略已变化，请关闭弹窗并刷新后重试。
        </p>
        <p
          v-else-if="quotaSubmission.errorText.value"
          class="lai-form-message-error"
        >
          {{ quotaSubmission.errorText.value }}
        </p>
        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="quotaSubmission.submitting.value"
            @click="quotaDialogOpen = false"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="quotaSubmission.submitting.value || quotaInvalid"
            @click="saveQuota"
          >
            {{ quotaSubmission.submitting.value ? '保存中…' : '保存调整' }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="adjustmentDialogOpen && detail"
      class="lai-dialog-overlay"
      @click.self="adjustmentDialogOpen = false"
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="application-adjustment-title"
      >
        <h2
          id="application-adjustment-title"
          class="lai-dialog-title"
        >
          人工增减额度
        </h2>
        <p class="lai-dialog-message">
          正数增加上限，负数扣减上限；调整后不能低于已用与预占。重复提交由幂等键保护。
        </p>
        <label class="lai-dialog-field">
          <span>调整维度</span>
          <select
            v-model="adjustmentForm.dimension"
            class="lai-select full-control"
          >
            <option value="TOKEN_LIMIT">Token 额度</option>
            <option value="AMOUNT_LIMIT">金额预算</option>
          </select>
        </label>
        <label class="lai-dialog-field">
          <span>增减值</span>
          <input
            v-model="adjustmentForm.delta"
            class="lai-input"
            inputmode="decimal"
            placeholder="例如 50000 或 -100"
          >
        </label>
        <label class="lai-dialog-field">
          <span>调整原因</span>
          <textarea
            v-model="adjustmentForm.reason"
            class="lai-input status-reason"
            maxlength="500"
            rows="3"
            placeholder="必填，将写入额度流水与审计记录"
          />
        </label>
        <p v-if="adjustmentPreview">
          调整后上限：{{ adjustmentPreview.after }}
        </p>
        <p
          v-if="adjustmentPreview?.stops"
          class="warning"
          role="alert"
        >
          保存后立即停止新请求：调整后的上限低于已用与预占之和。
        </p>
        <p
          v-if="adjustmentSubmission.conflictError.value"
          class="lai-form-message-error"
        >
          额度版本或幂等键发生冲突，请刷新后重试。
        </p>
        <p
          v-else-if="adjustmentSubmission.errorText.value"
          class="lai-form-message-error"
        >
          {{ adjustmentSubmission.errorText.value }}
        </p>
        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="adjustmentSubmission.submitting.value"
            @click="adjustmentDialogOpen = false"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="adjustmentSubmission.submitting.value || adjustmentInvalid"
            @click="saveAdjustment"
          >
            {{ adjustmentSubmission.submitting.value ? '提交中…' : '确认调整' }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="resetDialogOpen && detail"
      class="lai-dialog-overlay"
      @click.self="resetDialogOpen = false"
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="application-reset-title"
      >
        <h2
          id="application-reset-title"
          class="lai-dialog-title"
        >
          重置应用用量
        </h2>
        <p class="warning">
          这是高风险操作。只清零所选维度的当前已用量，预占、历史调用和用量账本不会删除；保存后应用可重新消耗相应预算。
        </p>
        <label class="lai-dialog-field">
          <span>重置维度</span>
          <select
            v-model="resetForm.dimension"
            class="lai-select full-control"
          >
            <option
              value="TOKEN_USAGE"
              :disabled="detail.quota.tokens_used <= 0"
            >Token 已用量（当前 {{ detail.quota.tokens_used.toLocaleString() }}）</option>
            <option
              value="AMOUNT_USAGE"
              :disabled="!positiveAmount(detail.quota.amount_used)"
            >金额已用量（当前 {{ detail.quota.amount_used }} {{ detail.quota.currency }}）</option>
          </select>
        </label>
        <label class="lai-dialog-field">
          <span>重置原因</span>
          <textarea
            v-model="resetForm.reason"
            class="lai-input status-reason"
            maxlength="500"
            rows="3"
            placeholder="必填，将写入额度流水与审计记录"
          />
        </label>
        <label class="lai-dialog-field">
          <span>输入应用编码 <code>{{ detail.code }}</code> 确认</span>
          <input
            v-model="resetForm.confirmation_code"
            class="lai-input lai-cell-mono"
            autocomplete="off"
            :placeholder="detail.code"
          >
        </label>
        <p
          v-if="resetSubmission.conflictError.value"
          class="lai-form-message-error"
        >
          额度版本或幂等键发生冲突，请刷新后重试。
        </p>
        <p
          v-else-if="resetSubmission.errorText.value"
          class="lai-form-message-error"
        >
          {{ resetSubmission.errorText.value }}
        </p>
        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="resetSubmission.submitting.value"
            @click="resetDialogOpen = false"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="resetSubmission.submitting.value || resetInvalid"
            @click="saveReset"
          >
            {{ resetSubmission.submitting.value ? '重置中…' : '确认重置' }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="modelDialogOpen && detail"
      class="lai-dialog-overlay"
      @click.self="modelDialogOpen = false"
    >
      <div
        class="lai-dialog governance-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="application-model-title"
      >
        <h2
          id="application-model-title"
          class="lai-dialog-title"
        >
          管理模型授权
        </h2>
        <p class="lai-dialog-message">
          未授权的模型会在调用进入路由前被拒绝。取消授权不会改写历史调用记录。
        </p>
        <PageState
          v-if="modelsLoading"
          status="loading"
        />
        <PageState
          v-else-if="modelsLoadError"
          status="error"
          :error="modelsLoadError"
          @retry="openModelDialog"
        />
        <div
          v-else-if="availableModels.length"
          class="model-options"
        >
          <div
            v-for="model in availableModels"
            :key="model.id"
            class="model-option-group"
          >
            <label class="model-option">
              <input
                v-model="selectedModelIds"
                type="checkbox"
                :value="model.id"
                @change="ensureConstraintForm(model.id)"
              >
              <span><strong>{{ model.display_name }}</strong><small>{{ model.alias }}</small></span>
            </label>
            <div
              v-if="selectedModelIds.includes(model.id)"
              class="model-constraint"
            >
              <label class="model-constraint-field">
                <span>最大输出 Token</span>
                <input
                  v-model="modelConstraints[model.id].maxOutputTokens"
                  class="lai-input"
                  type="number"
                  min="1"
                  step="1"
                  placeholder="留空表示不限"
                >
              </label>
              <label class="model-constraint-field">
                <span>流式调用</span>
                <select
                  v-model="modelConstraints[model.id].streamAllowed"
                  class="lai-input"
                >
                  <option value="">继承（不限）</option>
                  <option value="allow">允许</option>
                  <option value="deny">禁止</option>
                </select>
              </label>
            </div>
          </div>
          <p
            v-if="modelConstraintInvalid"
            class="lai-form-message-error"
          >
            最大输出 Token 必须是大于 0 的整数；留空表示不限制。
          </p>
        </div>
        <p
          v-else
          class="empty-inline"
        >
          当前没有已启用的虚拟模型。保存后应用将没有可调用模型。
        </p>
        <label class="lai-dialog-field"><span>变更原因</span><textarea
          v-model="modelReason"
          class="lai-input status-reason"
          maxlength="500"
          rows="3"
          placeholder="必填，将写入审计记录"
        /></label>
        <p
          v-if="modelSubmission.conflictError.value"
          class="lai-form-message-error"
        >
          应用授权版本已变化，请关闭弹窗并刷新后重试。
        </p>
        <p
          v-else-if="modelSubmission.errorText.value"
          class="lai-form-message-error"
        >
          {{ modelSubmission.errorText.value }}
        </p>
        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="modelSubmission.submitting.value"
            @click="modelDialogOpen = false"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="modelSubmission.submitting.value || !modelReason.trim() || modelsLoading || modelConstraintInvalid"
            @click="saveModels"
          >
            {{ modelSubmission.submitting.value ? '保存中…' : '保存授权' }}
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="statusDialogOpen && detail"
      class="lai-dialog-overlay"
      @click.self="statusDialogOpen = false"
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="application-status-title"
      >
        <h2
          id="application-status-title"
          class="lai-dialog-title"
        >
          {{ statusLabel[targetStatus] }}应用
        </h2>
        <p class="lai-dialog-message">
          {{ targetStatus === 'DISABLED' ? '停用后应立即拒绝该应用的新调用。' : targetStatus === 'ARCHIVED' ? '归档是终态，必须先停用应用。' : '启用后应用可按密钥、模型权限与额度策略接入。' }}
        </p>
        <label class="lai-dialog-field"><span>变更原因</span><textarea
          v-model="statusReason"
          class="lai-input status-reason"
          maxlength="500"
          rows="3"
          placeholder="必填，将写入审计记录"
        /></label>
        <p
          v-if="statusSubmission.conflictError.value"
          class="lai-form-message-error"
        >
          应用版本已变化，请刷新后再操作。
        </p>
        <p
          v-else-if="statusSubmission.errorText.value"
          class="lai-form-message-error"
        >
          {{ statusSubmission.errorText.value }}
        </p>
        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="statusSubmission.submitting.value"
            @click="statusDialogOpen = false"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="statusSubmission.submitting.value || !statusReason.trim()"
            @click="applyStatus"
          >
            {{ statusSubmission.submitting.value ? '处理中…' : '确认' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.detail-header, .title-line, .header-actions, .card-heading { display: flex; align-items: center; }
.detail-header { justify-content: space-between; gap: 20px; margin-bottom: 20px; }
.detail-header p { color: #667085; }
.title-line { gap: 10px; }
.title-line .lai-page-title { margin: 0; }
.header-actions { gap: 8px; }
.status { display: inline-flex; padding: 3px 9px; border-radius: 999px; font-size: 12px; }
.status-active { color: #166534; background: #f0fdf4; }.status-disabled { color: #92400e; background: #fffbeb; }.status-archived { color: #475467; background: #f2f4f7; }
.notice { padding: 10px 14px; margin-bottom: 16px; color: #475467; background: #f2f4f7; border: 1px solid #e6eaf0; border-radius: 6px; }.notice.warning { color: #92400e; background: #fffbeb; border-color: #fde68a; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.metric { display: flex; flex-direction: column; min-height: 108px; padding: 16px; background: #fff; border: 1px solid #e6eaf0; border-radius: 6px; }
.metric span, .metric small, .card-note, .empty-inline { color: #667085; }.metric strong { margin: 10px 0 5px; font-size: 20px; color: #172033; }.metric small { font-size: 12px; }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 16px; }.main-column { min-width: 0; }
.card-heading { justify-content: space-between; margin-bottom: 12px; }.card-heading .lai-card-title { margin: 0; }.card-heading > span { color: #667085; font-size: 12px; }
.card-note { margin-top: 14px; padding-top: 12px; border-top: 1px solid #e6eaf0; font-size: 13px; }
.model-list { border-top: 1px solid #e6eaf0; }.model-row { display: flex; align-items: center; justify-content: space-between; padding: 11px 0; border-bottom: 1px solid #e6eaf0; }.model-row div { display: flex; flex-direction: column; gap: 3px; }.model-row small { color: #667085; }.model-row-meta { align-items: flex-end; gap: 5px; }.model-row-meta span { font-size: 12px; color: #667085; }.enabled-text { color: #166534; }.disabled-text { color: #667085; }
.model-option-group { border-bottom: 1px solid #e6eaf0; }
.model-option-group .model-option { border-bottom: 0; }
.model-constraint { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; padding: 0 12px 12px; }
.model-constraint-field { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: #667085; }
.shortcut-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.shortcut { display: flex; flex-direction: column; gap: 6px; padding: 14px; color: #172033; border: 1px solid #e6eaf0; border-radius: 6px; }.shortcut:hover { border-color: #2563eb; }.shortcut span { color: #667085; font-size: 13px; }
.property-list { margin: 0; }.property-list div { padding: 10px 0; border-bottom: 1px solid #e6eaf0; }.property-list div:last-child { border: 0; }.property-list dt { margin-bottom: 3px; color: #667085; font-size: 12px; }.property-list dd { margin: 0; overflow-wrap: anywhere; }
.status-reason { width: 100%; max-width: none; height: auto; margin-top: 6px; padding: 8px 10px; resize: vertical; }
.lai-btn-small { min-height: 30px; padding: 4px 10px; font-size: 12px; }
.compact-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.detail-tabs { display: flex; flex-wrap: wrap; gap: 4px; margin: 0 0 16px; border-bottom: 1px solid #e6eaf0; }
.detail-tab { padding: 8px 12px; font-size: 13px; color: #667085; background: none; border: 0; border-bottom: 2px solid transparent; cursor: pointer; }
.detail-tab:hover { color: #172033; }
.detail-tab.is-active { color: #2563eb; border-bottom-color: #2563eb; font-weight: 500; }
.onboarding-list { padding: 0; margin: 0; list-style: none; }
.onboarding-list li { display: flex; flex-direction: column; gap: 4px; padding: 11px 0; border-bottom: 1px solid #e6eaf0; }
.onboarding-list li:last-child { border-bottom: 0; }
.onboarding-list li strong { font-size: 13px; color: #b45309; }
.onboarding-list li.done strong { color: #166534; }
.onboarding-list li span { color: #667085; font-size: 12px; }
.member-role { font-size: 12px; color: #667085; }
.adjustment-history { padding-top: 14px; margin-top: 14px; border-top: 1px solid #e6eaf0; }
.history-heading, .adjustment-list li > div, .adjustment-list li p { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.history-heading h3 { margin: 0; font-size: 13px; color: #344054; }
.history-heading .lai-btn { min-height: 28px; padding: 2px 6px; }
.history-state { margin: 10px 0 0; color: #667085; font-size: 12px; }
.error-text { color: #b42318; }
.adjustment-list { padding: 0; margin: 8px 0 0; list-style: none; }
.adjustment-list li { padding: 9px 0; border-top: 1px solid #f0f2f5; }
.adjustment-list li strong { font-size: 12px; color: #344054; }
.adjustment-list time, .adjustment-list li p span:last-child { color: #667085; font-size: 11px; }
.adjustment-list li p { align-items: flex-start; margin: 5px 0 0; }
.adjustment-list li p span:last-child { max-width: 150px; text-align: right; overflow-wrap: anywhere; }
.warning { padding: 10px; color: #92400e; background: #fffbeb; border: 1px solid #fde68a; border-radius: 6px; font-size: 13px; }
.governance-dialog { width: min(720px, calc(100vw - 32px)); max-width: 720px; }
.governance-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; margin-top: 12px; }
.wide-field { grid-column: 1 / -1; }
.full-control, .governance-grid .lai-input { width: 100%; max-width: none; }
.limit-control, .amount-control { display: grid; grid-template-columns: 64px 1fr; align-items: center; gap: 8px; }
.amount-control { grid-template-columns: 64px 1fr 68px; }
.model-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); max-height: 300px; margin: 14px 0; overflow: auto; border: 1px solid #e6eaf0; border-radius: 6px; }
.model-option { display: flex; align-items: flex-start; gap: 9px; padding: 12px; border-bottom: 1px solid #e6eaf0; }
.model-option:nth-child(odd) { border-right: 1px solid #e6eaf0; }
.model-option span { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.model-option small { color: #667085; overflow-wrap: anywhere; }
@media (max-width: 1100px) { .metric-grid { grid-template-columns: repeat(2, 1fr); } .workspace-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .detail-header { align-items: flex-start; flex-direction: column; } .metric-grid, .shortcut-grid, .governance-grid, .model-options { grid-template-columns: 1fr; } .model-option:nth-child(odd) { border-right: 0; } }
</style>
