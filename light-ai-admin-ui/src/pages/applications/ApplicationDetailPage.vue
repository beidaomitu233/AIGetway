<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
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
  fetchApplicationQuotaAdjustments,
  resetApplicationQuotaUsage,
  updateApplicationModels,
  updateApplicationQuota,
  type ApplicationDetail,
  type ApplicationQuotaAdjustment,
  type ApplicationStatus,
} from '@/api/applications'
import { fetchModelAliases, type ModelAliasListItem } from '@/api/modelAliases'

const route = useRoute()
const store = useBootstrapStore()
const id = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const detail = ref<ApplicationDetail | null>(null)
const loading = ref(true)
const loadError = ref<unknown>(null)
const statusDialogOpen = ref(false)
const targetStatus = ref<ApplicationStatus>('DISABLED')
const statusReason = ref('')
const canManage = computed(() => store.can(Permission.applicationManage))
const canViewQuota = computed(() => store.can(Permission.applicationQuotaView))
const canManageQuota = computed(() => store.can(Permission.applicationQuotaManage))
const canManageModels = computed(() => store.can(Permission.applicationModelManage))
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

const statusLabel: Record<ApplicationStatus, string> = {
  ACTIVE: '启用',
  DISABLED: '已停用',
  ARCHIVED: '已归档',
}
const environmentLabel: Record<string, string> = {
  DEV: '开发', TEST: '测试', STAGING: '预发布', PROD: '生产',
}
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
  const used = Number(quota.amount_used) + Number(quota.amount_reserved)
  return quota.amount_limit == null
    ? `${used.toFixed(2)} ${quota.currency} / 不限`
    : `${used.toFixed(2)} / ${Number(quota.amount_limit).toFixed(2)} ${quota.currency}`
}

function openStatusDialog(status: ApplicationStatus): void {
  targetStatus.value = status
  statusReason.value = ''
  statusSubmission.reset()
  statusDialogOpen.value = true
}

async function applyStatus(): Promise<void> {
  if (!detail.value || !statusReason.value.trim()) return
  const result = await statusSubmission.submit(async () => {
    const response = await changeApplicationStatus(detail.value!.id, {
      status: targetStatus.value,
      version: detail.value!.version,
      reason: statusReason.value.trim(),
    })
    if (response.entity) detail.value = response.entity
  })
  if (result.ok) statusDialogOpen.value = false
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
  if (!detail.value) return
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
  || (quotaForm.token_limited && (!quotaForm.token_limit || quotaForm.token_limit <= 0))
  || (quotaForm.amount_limited && (!quotaForm.amount_limit || Number(quotaForm.amount_limit) <= 0))
  || !/^[A-Za-z]{3}$/.test(quotaForm.currency)
  || (quotaForm.rpm_limited && (!quotaForm.rpm || quotaForm.rpm <= 0))
  || (quotaForm.tpm_limited && (!quotaForm.tpm || quotaForm.tpm <= 0))
  || (quotaForm.period_type === 'CUSTOM'
    && (!quotaForm.period_start || !quotaForm.period_end
      || quotaForm.period_start >= quotaForm.period_end)),
))

async function saveQuota(): Promise<void> {
  if (!detail.value || quotaInvalid.value) return
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
    if (response.entity) detail.value = response.entity
  })
  if (result.ok) quotaDialogOpen.value = false
}

function openAdjustmentDialog(): void {
  adjustmentSubmission.reset()
  adjustmentForm.dimension = 'TOKEN_LIMIT'
  adjustmentForm.delta = ''
  adjustmentForm.reason = ''
  adjustmentForm.idempotency_key = crypto.randomUUID()
  adjustmentDialogOpen.value = true
}

const adjustmentInvalid = computed(() => {
  if (!adjustmentForm.delta.trim() || !adjustmentForm.reason.trim()) return true
  const delta = Number(adjustmentForm.delta)
  if (!Number.isFinite(delta) || delta === 0) return true
  return adjustmentForm.dimension === 'TOKEN_LIMIT' && !Number.isInteger(delta)
})

async function saveAdjustment(): Promise<void> {
  if (!detail.value || adjustmentInvalid.value) return
  const result = await adjustmentSubmission.submit(async () => {
    const response = await adjustApplicationQuota(detail.value!.id, {
      dimension: adjustmentForm.dimension,
      delta: adjustmentForm.delta.trim(),
      reason: adjustmentForm.reason.trim(),
      idempotency_key: adjustmentForm.idempotency_key,
      quota_version: detail.value!.quota.version,
    })
    if (response.entity) detail.value = response.entity
  })
  if (result.ok) {
    adjustmentDialogOpen.value = false
    await loadAdjustments()
  }
}

function openResetDialog(): void {
  if (!detail.value) return
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
    : Number(detail.value.quota.amount_used) <= 0))

async function saveReset(): Promise<void> {
  if (!detail.value || resetInvalid.value) return
  const result = await resetSubmission.submit(async () => {
    const response = await resetApplicationQuotaUsage(detail.value!.id, {
      dimension: resetForm.dimension,
      reason: resetForm.reason.trim(),
      confirmation_code: resetForm.confirmation_code,
      idempotency_key: resetForm.idempotency_key,
      quota_version: detail.value!.quota.version,
    })
    if (response.entity) detail.value = response.entity
  })
  if (result.ok) {
    resetDialogOpen.value = false
    await loadAdjustments()
  }
}

async function loadAdjustments(): Promise<void> {
  if (!canViewQuota.value) return
  adjustmentsLoading.value = true
  adjustmentsLoadError.value = null
  try {
    adjustments.value = await fetchApplicationQuotaAdjustments(id.value)
  } catch (error) {
    adjustmentsLoadError.value = error
  } finally {
    adjustmentsLoading.value = false
  }
}

async function openModelDialog(): Promise<void> {
  if (!detail.value) return
  modelSubmission.reset()
  selectedModelIds.value = detail.value.models
    .filter((item) => item.enabled)
    .map((item) => item.virtual_model_id)
  modelReason.value = ''
  modelDialogOpen.value = true
  if (availableModels.value.length) return
  modelsLoading.value = true
  modelsLoadError.value = null
  try {
    const page = await fetchModelAliases({ enabled: true, page: 1, page_size: 100, sort: 'alias' })
    availableModels.value = page.items
  } catch (error) {
    modelsLoadError.value = error
  } finally {
    modelsLoading.value = false
  }
}

async function saveModels(): Promise<void> {
  if (!detail.value || !modelReason.value.trim()) return
  const result = await modelSubmission.submit(async () => {
    const response = await updateApplicationModels(detail.value!.id, {
      virtual_model_ids: selectedModelIds.value,
      application_version: detail.value!.version,
      reason: modelReason.value.trim(),
    })
    if (response.entity) detail.value = response.entity
  })
  if (result.ok) modelDialogOpen.value = false
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    detail.value = await fetchApplication(id.value)
    await loadAdjustments()
  } catch (error) {
    loadError.value = error
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="lai-page">
    <PageState v-if="loading" status="loading" />
    <PageState v-else-if="loadError || !detail" status="error" :error="loadError" @retry="load" />

    <template v-else>
      <div class="detail-header">
        <div>
          <div class="title-line">
            <h1 class="lai-page-title">{{ detail.name }}</h1>
            <span class="status" :class="`status-${detail.status.toLowerCase()}`">{{ statusLabel[detail.status] }}</span>
          </div>
          <p><span class="lai-cell-mono">{{ detail.code }}</span> · {{ environmentLabel[detail.environment] }} · {{ detail.owner_name }}</p>
        </div>
        <div v-if="canManage" class="header-actions">
          <RouterLink v-if="detail.status !== 'ARCHIVED'" :to="`/ui/applications/${detail.id}/settings`" class="lai-btn">编辑</RouterLink>
          <button v-if="detail.status === 'ACTIVE'" class="lai-btn" type="button" @click="openStatusDialog('DISABLED')">停用</button>
          <button v-else-if="detail.status === 'DISABLED'" class="lai-btn lai-btn-primary" type="button" @click="openStatusDialog('ACTIVE')">启用</button>
          <button v-if="detail.status === 'DISABLED'" class="lai-btn" type="button" @click="openStatusDialog('ARCHIVED')">归档</button>
        </div>
      </div>

      <div v-if="detail.status === 'DISABLED'" class="notice warning">应用已停用。所有应用密钥应停止新调用，历史调用与费用记录继续保留。</div>
      <div v-else-if="detail.status === 'ARCHIVED'" class="notice">应用已归档且不可恢复编辑，历史治理与调用快照仍保留。</div>

      <div class="metric-grid">
        <div class="metric"><span>活跃密钥</span><strong>{{ detail.active_key_count }}</strong><small>仅统计未撤销且有效的应用密钥</small></div>
        <div class="metric"><span>授权模型</span><strong>{{ detail.models.filter((item) => item.enabled).length }}</strong><small>调用仅允许使用已授权虚拟模型</small></div>
        <div class="metric"><span>Token 使用</span><strong>{{ usageText(detail.quota.tokens_used, detail.quota.tokens_reserved, detail.quota.token_limit) }}</strong><small>已用与预占合并展示</small></div>
        <div class="metric"><span>金额使用</span><strong>{{ amountText() }}</strong><small>按价格快照归属到本应用</small></div>
      </div>

      <div class="workspace-grid">
        <div class="main-column">
          <div class="lai-card">
            <div class="card-heading"><h2 class="lai-card-title">接入信息</h2><span>OpenAI 兼容协议</span></div>
            <div class="lai-summary-grid">
              <div class="lai-summary-item"><span class="lai-summary-label">应用编码</span><span class="lai-cell-mono">{{ detail.code }}</span></div>
              <div class="lai-summary-item"><span class="lai-summary-label">API 地址</span><span class="lai-cell-mono">/v1/chat/completions</span></div>
              <div class="lai-summary-item"><span class="lai-summary-label">最近调用</span>{{ formatDateTime(detail.last_called_at, store.timezone, '尚未调用') }}</div>
              <div class="lai-summary-item"><span class="lai-summary-label">活跃应用密钥</span>{{ detail.active_key_count }}</div>
            </div>
            <p class="card-note">业务系统只持有平台签发的应用密钥，不接触供应商 Key。应用密钥原文只应在创建或轮换成功时显示一次。</p>
          </div>

          <ApplicationKeyPanel
            :application-id="detail.id"
            :application-active="detail.status === 'ACTIVE'"
            @changed="load"
          />

          <div class="lai-card">
            <div class="card-heading">
              <h2 class="lai-card-title">可用虚拟模型</h2>
              <button v-if="canManageModels && detail.status !== 'ARCHIVED'" type="button" class="lai-btn lai-btn-small" @click="openModelDialog">管理授权</button>
              <span v-else>{{ detail.models.filter((item) => item.enabled).length }} 个</span>
            </div>
            <div v-if="detail.models.length" class="model-list">
              <div v-for="model in detail.models" :key="model.id" class="model-row">
                <div><strong>{{ model.virtual_model_code || model.virtual_model_id }}</strong><small>请求 model 字段</small></div>
                <span :class="model.enabled ? 'enabled-text' : 'disabled-text'">{{ model.enabled ? '已授权' : '已停用' }}</span>
              </div>
            </div>
            <p v-else class="empty-inline">尚未授权虚拟模型，应用当前无法完成模型调用。</p>
          </div>

          <div class="lai-card">
            <div class="card-heading"><h2 class="lai-card-title">调用与成本</h2></div>
            <div class="shortcut-grid">
              <RouterLink :to="{ path: '/ui/traces', query: { application: detail.code } }" class="shortcut"><strong>调用记录</strong><span>查看请求、Attempt、耗时、结果与错误原因</span></RouterLink>
              <RouterLink :to="{ path: '/ui/usage', query: { application: detail.code } }" class="shortcut"><strong>用量与成本</strong><span>查看 Token、预算消耗和应用成本归属</span></RouterLink>
            </div>
          </div>
        </div>

        <aside>
          <div class="lai-card">
            <div class="card-heading">
              <h2 class="lai-card-title">额度与速率</h2>
              <div v-if="canManageQuota && detail.status !== 'ARCHIVED'" class="compact-actions">
                <button type="button" class="lai-btn lai-btn-small" @click="openAdjustmentDialog">人工增减</button>
                <button
                  type="button"
                  class="lai-btn lai-btn-small"
                  :disabled="detail.quota.tokens_used <= 0 && Number(detail.quota.amount_used) <= 0"
                  @click="openResetDialog"
                >重置用量</button>
                <button type="button" class="lai-btn lai-btn-small" @click="openQuotaDialog">编辑策略</button>
              </div>
            </div>
            <dl class="property-list">
              <div><dt>Token 额度</dt><dd>{{ usageText(detail.quota.tokens_used, detail.quota.tokens_reserved, detail.quota.token_limit) }}</dd></div>
              <div><dt>金额预算</dt><dd>{{ amountText() }}</dd></div>
              <div><dt>RPM</dt><dd>{{ detail.quota.rpm ?? '不限' }}</dd></div>
              <div><dt>TPM</dt><dd>{{ detail.quota.tpm == null ? '不限' : detail.quota.tpm.toLocaleString() }}</dd></div>
              <div><dt>结算周期</dt><dd>{{ periodLabel[detail.quota.period_type] }}</dd></div>
            </dl>
            <div v-if="canViewQuota" class="adjustment-history">
              <div class="history-heading">
                <h3>最近额度流水</h3>
                <button type="button" class="lai-btn lai-btn-text" :disabled="adjustmentsLoading" @click="loadAdjustments">刷新</button>
              </div>
              <p v-if="adjustmentsLoading" class="history-state">正在加载…</p>
              <p v-else-if="adjustmentsLoadError" class="history-state error-text">流水加载失败，请重试。</p>
              <ul v-else-if="adjustments.length" class="adjustment-list">
                <li v-for="item in adjustments.slice(0, 5)" :key="item.id">
                  <div><strong>{{ adjustmentLabel[item.dimension] }}</strong><time>{{ formatDateTime(item.effective_at, store.timezone) }} · {{ item.operator_id }}</time></div>
                  <p><span class="lai-cell-mono">{{ item.before_value }} → {{ item.after_value }}</span><span>{{ item.reason }}</span></p>
                </li>
              </ul>
              <p v-else class="history-state">暂无额度调整或重置记录。</p>
            </div>
          </div>
          <div class="lai-card">
            <h2 class="lai-card-title">基本信息</h2>
            <dl class="property-list">
              <div><dt>负责人</dt><dd>{{ detail.owner_name }}（{{ detail.owner_id }}）</dd></div>
              <div><dt>所属部门</dt><dd>{{ detail.department || '—' }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ formatDateTime(detail.created_at, store.timezone) }}</dd></div>
              <div><dt>更新时间</dt><dd>{{ formatDateTime(detail.updated_at, store.timezone) }}</dd></div>
              <div v-if="detail.description"><dt>说明</dt><dd>{{ detail.description }}</dd></div>
            </dl>
          </div>
        </aside>
      </div>
    </template>

    <div v-if="quotaDialogOpen && detail" class="lai-dialog-overlay" @click.self="quotaDialogOpen = false">
      <div class="lai-dialog governance-dialog" role="dialog" aria-modal="true" aria-labelledby="application-quota-title">
        <h2 id="application-quota-title" class="lai-dialog-title">调整额度与速率</h2>
        <p class="lai-dialog-message">新上限不能低于已用与预占；当前周期已有用量时不能直接修改币种或周期。</p>
        <div class="governance-grid">
          <FormField label="Token 额度" :error="quotaSubmission.fieldMessages.value.token_limit">
            <div class="limit-control"><label><input v-model="quotaForm.token_limited" type="checkbox"> 限制</label><input v-model.number="quotaForm.token_limit" class="lai-input" type="number" min="1" :disabled="!quotaForm.token_limited"></div>
          </FormField>
          <FormField label="金额预算" :error="quotaSubmission.fieldMessages.value.amount_limit">
            <div class="amount-control"><label><input v-model="quotaForm.amount_limited" type="checkbox"> 限制</label><input v-model="quotaForm.amount_limit" class="lai-input" inputmode="decimal" :disabled="!quotaForm.amount_limited"><input v-model="quotaForm.currency" class="lai-input currency" maxlength="3" aria-label="币种"></div>
          </FormField>
          <FormField label="RPM" hint="每分钟最大请求数" :error="quotaSubmission.fieldMessages.value.rpm">
            <div class="limit-control"><label><input v-model="quotaForm.rpm_limited" type="checkbox"> 限制</label><input v-model.number="quotaForm.rpm" class="lai-input" type="number" min="1" :disabled="!quotaForm.rpm_limited"></div>
          </FormField>
          <FormField label="TPM" hint="每分钟最大 Token 数" :error="quotaSubmission.fieldMessages.value.tpm">
            <div class="limit-control"><label><input v-model="quotaForm.tpm_limited" type="checkbox"> 限制</label><input v-model.number="quotaForm.tpm" class="lai-input" type="number" min="1" :disabled="!quotaForm.tpm_limited"></div>
          </FormField>
          <FormField label="额度周期" required :error="quotaSubmission.fieldMessages.value.period_type">
            <select v-model="quotaForm.period_type" class="lai-select full-control">
              <option value="LIFECYCLE">应用生命周期</option><option value="DAY">每日</option>
              <option value="MONTH">每月</option><option value="CUSTOM">自定义</option>
            </select>
          </FormField>
          <template v-if="quotaForm.period_type === 'CUSTOM'">
            <FormField label="开始时间" required><input v-model="quotaForm.period_start" class="lai-input" type="datetime-local"></FormField>
            <FormField label="结束时间" required :error="quotaSubmission.fieldMessages.value.period_end"><input v-model="quotaForm.period_end" class="lai-input" type="datetime-local"></FormField>
          </template>
          <FormField class="wide-field" label="调整原因" required :error="quotaSubmission.fieldMessages.value.reason">
            <textarea v-model="quotaForm.reason" class="lai-input status-reason" maxlength="500" rows="3" placeholder="必填，将写入审计记录" />
          </FormField>
        </div>
        <p v-if="quotaSubmission.conflictError.value" class="lai-form-message-error">额度策略已变化，请关闭弹窗并刷新后重试。</p>
        <p v-else-if="quotaSubmission.errorText.value" class="lai-form-message-error">{{ quotaSubmission.errorText.value }}</p>
        <div class="lai-dialog-actions">
          <button type="button" class="lai-btn" :disabled="quotaSubmission.submitting.value" @click="quotaDialogOpen = false">取消</button>
          <button type="button" class="lai-btn lai-btn-primary" :disabled="quotaSubmission.submitting.value || quotaInvalid" @click="saveQuota">{{ quotaSubmission.submitting.value ? '保存中…' : '保存调整' }}</button>
        </div>
      </div>
    </div>

    <div v-if="adjustmentDialogOpen && detail" class="lai-dialog-overlay" @click.self="adjustmentDialogOpen = false">
      <div class="lai-dialog" role="dialog" aria-modal="true" aria-labelledby="application-adjustment-title">
        <h2 id="application-adjustment-title" class="lai-dialog-title">人工增减额度</h2>
        <p class="lai-dialog-message">正数增加上限，负数扣减上限；调整后不能低于已用与预占。重复提交由幂等键保护。</p>
        <label class="lai-dialog-field">
          <span>调整维度</span>
          <select v-model="adjustmentForm.dimension" class="lai-select full-control">
            <option value="TOKEN_LIMIT">Token 额度</option>
            <option value="AMOUNT_LIMIT">金额预算</option>
          </select>
        </label>
        <label class="lai-dialog-field">
          <span>增减值</span>
          <input v-model="adjustmentForm.delta" class="lai-input" inputmode="decimal" placeholder="例如 50000 或 -100">
        </label>
        <label class="lai-dialog-field">
          <span>调整原因</span>
          <textarea v-model="adjustmentForm.reason" class="lai-input status-reason" maxlength="500" rows="3" placeholder="必填，将写入额度流水与审计记录" />
        </label>
        <p v-if="adjustmentSubmission.conflictError.value" class="lai-form-message-error">额度版本或幂等键发生冲突，请刷新后重试。</p>
        <p v-else-if="adjustmentSubmission.errorText.value" class="lai-form-message-error">{{ adjustmentSubmission.errorText.value }}</p>
        <div class="lai-dialog-actions">
          <button type="button" class="lai-btn" :disabled="adjustmentSubmission.submitting.value" @click="adjustmentDialogOpen = false">取消</button>
          <button type="button" class="lai-btn lai-btn-primary" :disabled="adjustmentSubmission.submitting.value || adjustmentInvalid" @click="saveAdjustment">{{ adjustmentSubmission.submitting.value ? '提交中…' : '确认调整' }}</button>
        </div>
      </div>
    </div>

    <div v-if="resetDialogOpen && detail" class="lai-dialog-overlay" @click.self="resetDialogOpen = false">
      <div class="lai-dialog" role="dialog" aria-modal="true" aria-labelledby="application-reset-title">
        <h2 id="application-reset-title" class="lai-dialog-title">重置应用用量</h2>
        <p class="warning">这是高风险操作。只清零所选维度的当前已用量，预占、历史调用和用量账本不会删除；保存后应用可重新消耗相应预算。</p>
        <label class="lai-dialog-field">
          <span>重置维度</span>
          <select v-model="resetForm.dimension" class="lai-select full-control">
            <option value="TOKEN_USAGE" :disabled="detail.quota.tokens_used <= 0">Token 已用量（当前 {{ detail.quota.tokens_used.toLocaleString() }}）</option>
            <option value="AMOUNT_USAGE" :disabled="Number(detail.quota.amount_used) <= 0">金额已用量（当前 {{ detail.quota.amount_used }} {{ detail.quota.currency }}）</option>
          </select>
        </label>
        <label class="lai-dialog-field">
          <span>重置原因</span>
          <textarea v-model="resetForm.reason" class="lai-input status-reason" maxlength="500" rows="3" placeholder="必填，将写入额度流水与审计记录" />
        </label>
        <label class="lai-dialog-field">
          <span>输入应用编码 <code>{{ detail.code }}</code> 确认</span>
          <input v-model="resetForm.confirmation_code" class="lai-input lai-cell-mono" autocomplete="off" :placeholder="detail.code">
        </label>
        <p v-if="resetSubmission.conflictError.value" class="lai-form-message-error">额度版本或幂等键发生冲突，请刷新后重试。</p>
        <p v-else-if="resetSubmission.errorText.value" class="lai-form-message-error">{{ resetSubmission.errorText.value }}</p>
        <div class="lai-dialog-actions">
          <button type="button" class="lai-btn" :disabled="resetSubmission.submitting.value" @click="resetDialogOpen = false">取消</button>
          <button type="button" class="lai-btn lai-btn-primary" :disabled="resetSubmission.submitting.value || resetInvalid" @click="saveReset">{{ resetSubmission.submitting.value ? '重置中…' : '确认重置' }}</button>
        </div>
      </div>
    </div>

    <div v-if="modelDialogOpen && detail" class="lai-dialog-overlay" @click.self="modelDialogOpen = false">
      <div class="lai-dialog governance-dialog" role="dialog" aria-modal="true" aria-labelledby="application-model-title">
        <h2 id="application-model-title" class="lai-dialog-title">管理模型授权</h2>
        <p class="lai-dialog-message">未授权的模型会在调用进入路由前被拒绝。取消授权不会改写历史调用记录。</p>
        <PageState v-if="modelsLoading" status="loading" />
        <PageState v-else-if="modelsLoadError" status="error" :error="modelsLoadError" @retry="openModelDialog" />
        <div v-else-if="availableModels.length" class="model-options">
          <label v-for="model in availableModels" :key="model.id" class="model-option">
            <input v-model="selectedModelIds" type="checkbox" :value="model.id">
            <span><strong>{{ model.display_name }}</strong><small>{{ model.alias }}</small></span>
          </label>
        </div>
        <p v-else class="empty-inline">当前没有已启用的虚拟模型。保存后应用将没有可调用模型。</p>
        <label class="lai-dialog-field"><span>变更原因</span><textarea v-model="modelReason" class="lai-input status-reason" maxlength="500" rows="3" placeholder="必填，将写入审计记录" /></label>
        <p v-if="modelSubmission.conflictError.value" class="lai-form-message-error">应用授权版本已变化，请关闭弹窗并刷新后重试。</p>
        <p v-else-if="modelSubmission.errorText.value" class="lai-form-message-error">{{ modelSubmission.errorText.value }}</p>
        <div class="lai-dialog-actions">
          <button type="button" class="lai-btn" :disabled="modelSubmission.submitting.value" @click="modelDialogOpen = false">取消</button>
          <button type="button" class="lai-btn lai-btn-primary" :disabled="modelSubmission.submitting.value || !modelReason.trim() || modelsLoading" @click="saveModels">{{ modelSubmission.submitting.value ? '保存中…' : '保存授权' }}</button>
        </div>
      </div>
    </div>

    <div v-if="statusDialogOpen && detail" class="lai-dialog-overlay" @click.self="statusDialogOpen = false">
      <div class="lai-dialog" role="dialog" aria-modal="true" aria-labelledby="application-status-title">
        <h2 id="application-status-title" class="lai-dialog-title">{{ statusLabel[targetStatus] }}应用</h2>
        <p class="lai-dialog-message">{{ targetStatus === 'DISABLED' ? '停用后应立即拒绝该应用的新调用。' : targetStatus === 'ARCHIVED' ? '归档是终态，必须先停用应用。' : '启用后应用可按密钥、模型权限与额度策略接入。' }}</p>
        <label class="lai-dialog-field"><span>变更原因</span><textarea v-model="statusReason" class="lai-input status-reason" maxlength="500" rows="3" placeholder="必填，将写入审计记录" /></label>
        <p v-if="statusSubmission.conflictError.value" class="lai-form-message-error">应用版本已变化，请刷新后再操作。</p>
        <p v-else-if="statusSubmission.errorText.value" class="lai-form-message-error">{{ statusSubmission.errorText.value }}</p>
        <div class="lai-dialog-actions">
          <button type="button" class="lai-btn" :disabled="statusSubmission.submitting.value" @click="statusDialogOpen = false">取消</button>
          <button type="button" class="lai-btn lai-btn-primary" :disabled="statusSubmission.submitting.value || !statusReason.trim()" @click="applyStatus">{{ statusSubmission.submitting.value ? '处理中…' : '确认' }}</button>
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
.model-list { border-top: 1px solid #e6eaf0; }.model-row { display: flex; align-items: center; justify-content: space-between; padding: 11px 0; border-bottom: 1px solid #e6eaf0; }.model-row div { display: flex; flex-direction: column; gap: 3px; }.model-row small { color: #667085; }.enabled-text { color: #166534; }.disabled-text { color: #667085; }
.shortcut-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.shortcut { display: flex; flex-direction: column; gap: 6px; padding: 14px; color: #172033; border: 1px solid #e6eaf0; border-radius: 6px; }.shortcut:hover { border-color: #2563eb; }.shortcut span { color: #667085; font-size: 13px; }
.property-list { margin: 0; }.property-list div { padding: 10px 0; border-bottom: 1px solid #e6eaf0; }.property-list div:last-child { border: 0; }.property-list dt { margin-bottom: 3px; color: #667085; font-size: 12px; }.property-list dd { margin: 0; overflow-wrap: anywhere; }
.status-reason { width: 100%; max-width: none; height: auto; margin-top: 6px; padding: 8px 10px; resize: vertical; }
.lai-btn-small { min-height: 30px; padding: 4px 10px; font-size: 12px; }
.compact-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
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
