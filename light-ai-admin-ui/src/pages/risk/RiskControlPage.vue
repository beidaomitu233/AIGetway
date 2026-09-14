<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Alert, Button, Card, Input, InputNumber, Select, Switch, Tag } from 'ant-design-vue'
import PageState from '@/components/PageState.vue'
import { ApiError, isAbortError } from '@/api/errors'
import {
  fetchRiskApplications,
  fetchRiskEvents,
  fetchRiskPolicy,
  replaceRiskPolicy,
  type RiskApplication,
  type RiskEvent,
  type RiskEventFilters,
  type RiskKeywordRule,
  type RiskPolicy,
} from '@/api/riskControl'
import { Permission } from '@/app/permissions'
import { useBootstrapStore } from '@/stores/bootstrap'

const store = useBootstrapStore()
const canManage = computed(() => store.can(Permission.riskControlManage))
const policy = ref<RiskPolicy | null>(null)
const applications = ref<RiskApplication[]>([])
const events = ref<RiskEvent[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref<unknown>(null)
const saveError = ref('')
const eventError = ref('')
const eventLoading = ref(false)
const reason = ref('')
const newKeyword = reactive<{
  keyword: string
  match_type: 'EXACT' | 'CONTAINS'
  action: 'BLOCK' | 'RECORD'
  application_id?: string
}>({ keyword: '', match_type: 'CONTAINS', action: 'BLOCK', application_id: undefined })
const eventFilters = reactive<RiskEventFilters>({
  limit: 50,
  application_id: '',
  event_type: '',
  from: '',
  to: '',
})

function eventQuery(): RiskEventFilters {
  return {
    limit: 50,
    application_id: eventFilters.application_id || undefined,
    event_type: eventFilters.event_type || undefined,
    from: eventFilters.from || undefined,
    to: eventFilters.to || undefined,
  }
}

async function loadEvents(): Promise<void> {
  eventLoading.value = true
  eventError.value = ''
  try {
    events.value = await fetchRiskEvents(eventQuery())
  } catch (e) {
    if (!isAbortError(e)) eventError.value = e instanceof ApiError ? e.message : '风险事件加载失败，请稍后重试'
  } finally {
    eventLoading.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [nextPolicy, nextApps] = await Promise.all([fetchRiskPolicy(), fetchRiskApplications()])
    policy.value = nextPolicy
    applications.value = nextApps
    await loadEvents()
  } catch (e) {
    if (!isAbortError(e)) error.value = e
  } finally {
    loading.value = false
  }
}

function addKeyword(): void {
  if (!policy.value || !newKeyword.keyword.trim() || !canManage.value) return
  policy.value.keywords.push({
    id: `new-${Date.now()}`,
    keyword: newKeyword.keyword.trim(),
    match_type: newKeyword.match_type,
    ignore_case: true,
    application_id: newKeyword.application_id ?? null,
    action: newKeyword.action,
    enabled: true,
  })
  newKeyword.keyword = ''
  newKeyword.application_id = undefined
}

function removeKeyword(rule: RiskKeywordRule): void {
  if (policy.value) policy.value.keywords = policy.value.keywords.filter(item => item.id !== rule.id)
}

function setRequestThreshold(value: number | string | null): void {
  if (policy.value) policy.value.anomaly_request_threshold = value === null || value === '' ? null : Number(value)
}

function setTokenThreshold(value: number | string | null): void {
  if (policy.value) policy.value.anomaly_token_threshold = value === null || value === '' ? null : Number(value)
}

function setAmountThreshold(value: string | number | null): void {
  if (policy.value) policy.value.anomaly_amount_threshold = value === null || value === '' ? null : String(value)
}

function applicationLabel(applicationId: string | null): string {
  if (!applicationId) return '全部应用'
  const app = applications.value.find(item => item.id === applicationId)
  return app ? `${app.name}（${app.code}）` : applicationId
}

async function save(): Promise<void> {
  if (!policy.value || !canManage.value || !reason.value.trim()) return
  saving.value = true
  saveError.value = ''
  try {
    policy.value = await replaceRiskPolicy({
      version: policy.value.version,
      enabled: policy.value.enabled,
      keyword_action: policy.value.keyword_action,
      anomaly_window_seconds: policy.value.anomaly_window_seconds,
      anomaly_request_threshold: policy.value.anomaly_request_threshold,
      anomaly_token_threshold: policy.value.anomaly_token_threshold,
      anomaly_amount_threshold: policy.value.anomaly_amount_threshold,
      anomaly_block_seconds: policy.value.anomaly_block_seconds,
      whitelist_mode: policy.value.whitelist_mode,
      keywords: policy.value.keywords.map(({ id, ...rule }) => ({ ...rule, id: id.startsWith('new-') ? null : id })),
      whitelist_application_ids: policy.value.whitelist_application_ids,
      reason: reason.value.trim(),
    })
    reason.value = ''
    await loadEvents()
  } catch (e) {
    saveError.value = e instanceof ApiError ? e.message : '风险策略保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <div>
        <h1 class="lai-page-title">风险控制</h1>
        <p class="lai-page-subtitle">在上游调用前统一检查关键词、异常消耗和应用白名单。</p>
      </div>
      <Button :loading="loading" @click="load">刷新</Button>
    </div>
    <PageState v-if="loading" status="loading" />
    <PageState v-else-if="error" status="error" :error="error" @retry="load" />
    <template v-else-if="policy">
      <Alert v-if="saveError" type="error" show-icon :message="saveError" />
      <Card :bordered="false" class="lai-card">
        <div class="card-heading">
          <div>
            <h2 class="lai-card-title">总开关与版本</h2>
            <p class="lai-card-hint">保存后生成新的风险策略版本，历史命中事件保留。</p>
          </div>
          <Tag color="blue">v{{ policy.version }}</Tag>
        </div>
        <label class="lai-inline-field"><span>启用风险控制</span><Switch v-model:checked="policy.enabled" :disabled="!canManage" /></label>
      </Card>

      <Card :bordered="false" class="lai-card">
        <h2 class="lai-card-title">关键词屏蔽</h2>
        <p class="lai-card-hint">只记录规则 ID、应用和 request_id，不保存完整请求正文。</p>
        <div class="risk-form-row">
          <Input v-model:value="newKeyword.keyword" :disabled="!canManage" placeholder="关键词" @press-enter="addKeyword" />
          <Select v-model:value="newKeyword.match_type" :disabled="!canManage" :options="[{ value: 'CONTAINS', label: '包含' }, { value: 'EXACT', label: '精确' }]" />
          <Select v-model:value="newKeyword.action" :disabled="!canManage" :options="[{ value: 'BLOCK', label: '命中即阻断' }, { value: 'RECORD', label: '仅记录' }]" />
          <Select
            v-model:value="newKeyword.application_id"
            :disabled="!canManage"
            allow-clear
            :options="applications.map(item => ({ value: item.id, label: `${item.name}（${item.code}）` }))"
            placeholder="作用于全部应用"
          />
          <Button type="primary" :disabled="!canManage || !newKeyword.keyword.trim()" @click="addKeyword">添加规则</Button>
        </div>
        <div v-if="policy.keywords.length" class="risk-rule-list">
          <div v-for="rule in policy.keywords" :key="rule.id" class="risk-rule">
            <span>
              <strong>{{ rule.keyword }}</strong>
              <small>{{ rule.match_type === 'EXACT' ? '精确' : '包含' }} · {{ rule.action === 'BLOCK' ? '阻断' : '记录' }} · {{ applicationLabel(rule.application_id) }}</small>
            </span>
            <Button v-if="canManage" type="link" danger @click="removeKeyword(rule)">删除</Button>
          </div>
        </div>
        <p v-else class="empty-inline">暂无关键词规则。</p>
      </Card>

      <Card :bordered="false" class="lai-card">
        <h2 class="lai-card-title">短时间异常消耗</h2>
        <p class="lai-card-hint">请求数阈值留空表示不启用该维度；共享窗口不可用时新请求会被拒绝。</p>
        <div class="risk-form-grid">
          <label><span>时间窗口（秒）</span><InputNumber v-model:value="policy.anomaly_window_seconds" :min="1" :max="86400" :disabled="!canManage" /></label>
          <label><span>请求数阈值</span><InputNumber :value="policy.anomaly_request_threshold ?? undefined" :min="1" :disabled="!canManage" @update:value="setRequestThreshold" /></label>
          <label><span>Token 阈值</span><InputNumber :value="policy.anomaly_token_threshold ?? undefined" :min="1" :disabled="!canManage" @update:value="setTokenThreshold" /></label>
          <label><span>金额阈值</span><Input :value="policy.anomaly_amount_threshold ?? undefined" :disabled="!canManage" placeholder="例如 100.00" @update:value="setAmountThreshold" /></label>
          <label><span>阻断时长（秒）</span><InputNumber v-model:value="policy.anomaly_block_seconds" :min="1" :max="86400" :disabled="!canManage" /></label>
        </div>
      </Card>

      <Card :bordered="false" class="lai-card">
        <h2 class="lai-card-title">应用白名单</h2>
        <p class="lai-card-hint">强制模式下，不在白名单中的应用在上游调用前拒绝。</p>
        <div class="risk-form-row">
          <Select v-model:value="policy.whitelist_mode" :disabled="!canManage" :options="[{ value: 'OFF', label: '关闭' }, { value: 'RECORD', label: '仅记录' }, { value: 'ENFORCE', label: '强制阻断' }]" />
          <Select v-model:value="policy.whitelist_application_ids" mode="multiple" :disabled="!canManage" :options="applications.map(item => ({ value: item.id, label: `${item.name}（${item.code}）` }))" placeholder="选择允许调用的应用" />
        </div>
      </Card>

      <Card :bordered="false" class="lai-card">
        <div class="card-heading">
          <h2 class="lai-card-title">风险事件</h2>
          <span v-if="eventLoading">加载中…</span>
          <span v-else>{{ events.length }} 条</span>
        </div>
        <div class="risk-form-row risk-event-filters">
          <Select v-model:value="eventFilters.application_id" allow-clear placeholder="全部应用" :options="applications.map(item => ({ value: item.id, label: `${item.name}（${item.code}）` }))" />
          <Select v-model:value="eventFilters.event_type" allow-clear placeholder="全部类型" :options="[{ value: 'KEYWORD', label: '关键词' }, { value: 'ANOMALY_CONSUMPTION', label: '异常消耗' }, { value: 'APPLICATION_NOT_WHITELISTED', label: '白名单' }]" />
          <Input v-model:value="eventFilters.from" placeholder="开始时间（ISO）" />
          <Input v-model:value="eventFilters.to" placeholder="结束时间（ISO）" />
          <Button :loading="eventLoading" @click="loadEvents">筛选</Button>
        </div>
        <Alert v-if="eventError" type="error" show-icon :message="eventError" />
        <div v-if="!eventLoading && !eventError && events.length" class="risk-event-list">
          <div v-for="event in events" :key="event.id" class="risk-event">
            <Tag :color="event.action === 'BLOCK' ? 'red' : 'orange'">{{ event.action }}</Tag>
            <span>{{ event.event_type }} · {{ event.reason || '策略命中' }}</span>
            <small>{{ event.request_id || '无 request_id' }}</small>
          </div>
        </div>
        <p v-else-if="!eventLoading && !eventError" class="empty-inline">暂无风险命中事件。</p>
      </Card>

      <div v-if="canManage" class="lai-page-footer">
        <Input v-model:value="reason" placeholder="填写本次策略变更原因（必填）" />
        <Button type="primary" :loading="saving" :disabled="saving || !reason.trim()" @click="save">保存风险策略</Button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.risk-form-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap }
.risk-form-row > * { min-width: 160px }
.risk-form-grid { display: grid; grid-template-columns: repeat(5, minmax(150px, 1fr)); gap: 12px }
.risk-form-grid label { display: flex; flex-direction: column; gap: 6px }
.risk-rule-list, .risk-event-list { border-top: 1px solid #e6eaf0 }
.risk-rule, .risk-event { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid #e6eaf0 }
.risk-rule span { display: flex; flex-direction: column; gap: 3px; flex: 1 }
.risk-rule small, .risk-event small { color: #667085 }
.lai-page-footer { display: flex; gap: 12px; justify-content: flex-end; margin-top: 16px }
@media (max-width: 900px) { .risk-form-grid { grid-template-columns: 1fr 1fr } }
</style>

