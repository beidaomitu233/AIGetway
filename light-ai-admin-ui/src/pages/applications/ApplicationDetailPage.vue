<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import ApplicationKeyPanel from './ApplicationKeyPanel.vue'
import { formatDateTime } from '@/app/display'
import { Permission } from '@/app/permissions'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useFormSubmit } from '@/composables/useFormSubmit'
import {
  changeApplicationStatus,
  fetchApplication,
  type ApplicationDetail,
  type ApplicationStatus,
} from '@/api/applications'

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
const { submitting, errorText, conflictError, submit } = useFormSubmit()

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
  statusDialogOpen.value = true
}

async function applyStatus(): Promise<void> {
  if (!detail.value || !statusReason.value.trim()) return
  const result = await submit(async () => {
    const response = await changeApplicationStatus(detail.value!.id, {
      status: targetStatus.value,
      version: detail.value!.version,
      reason: statusReason.value.trim(),
    })
    if (response.entity) detail.value = response.entity
  })
  if (result.ok) statusDialogOpen.value = false
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    detail.value = await fetchApplication(id.value)
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
            <div class="card-heading"><h2 class="lai-card-title">可用虚拟模型</h2><span>{{ detail.models.length }} 个</span></div>
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
            <h2 class="lai-card-title">额度与速率</h2>
            <dl class="property-list">
              <div><dt>Token 额度</dt><dd>{{ usageText(detail.quota.tokens_used, detail.quota.tokens_reserved, detail.quota.token_limit) }}</dd></div>
              <div><dt>金额预算</dt><dd>{{ amountText() }}</dd></div>
              <div><dt>RPM</dt><dd>{{ detail.quota.rpm ?? '不限' }}</dd></div>
              <div><dt>TPM</dt><dd>{{ detail.quota.tpm == null ? '不限' : detail.quota.tpm.toLocaleString() }}</dd></div>
              <div><dt>结算周期</dt><dd>{{ periodLabel[detail.quota.period_type] }}</dd></div>
            </dl>
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

    <div v-if="statusDialogOpen && detail" class="lai-dialog-overlay" @click.self="statusDialogOpen = false">
      <div class="lai-dialog" role="dialog" aria-modal="true" aria-labelledby="application-status-title">
        <h2 id="application-status-title" class="lai-dialog-title">{{ statusLabel[targetStatus] }}应用</h2>
        <p class="lai-dialog-message">{{ targetStatus === 'DISABLED' ? '停用后应立即拒绝该应用的新调用。' : targetStatus === 'ARCHIVED' ? '归档是终态，必须先停用应用。' : '启用后应用可按密钥、模型权限与额度策略接入。' }}</p>
        <label class="lai-dialog-field"><span>变更原因</span><textarea v-model="statusReason" class="lai-input status-reason" maxlength="500" rows="3" placeholder="必填，将写入审计记录" /></label>
        <p v-if="conflictError" class="lai-form-message-error">应用版本已变化，请刷新后再操作。</p>
        <p v-else-if="errorText" class="lai-form-message-error">{{ errorText }}</p>
        <div class="lai-dialog-actions">
          <button type="button" class="lai-btn" :disabled="submitting" @click="statusDialogOpen = false">取消</button>
          <button type="button" class="lai-btn lai-btn-primary" :disabled="submitting || !statusReason.trim()" @click="applyStatus">{{ submitting ? '处理中…' : '确认' }}</button>
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
@media (max-width: 1100px) { .metric-grid { grid-template-columns: repeat(2, 1fr); } .workspace-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .detail-header { align-items: flex-start; flex-direction: column; } .metric-grid, .shortcut-grid { grid-template-columns: 1fr; } }
</style>
