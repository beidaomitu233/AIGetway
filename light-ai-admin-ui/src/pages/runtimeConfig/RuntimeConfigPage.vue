<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import VersionConflictBanner from '@/components/VersionConflictBanner.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { Permission } from '@/app/permissions'
import { formatDateTime } from '@/app/display'
import {
  type RetentionImpactResult,
  type RuntimeConfigDetail,
  fetchRetentionImpact,
  fetchRuntimeConfig,
  updateRuntimeConfig,
} from '@/api/runtimeConfig'

const store = useBootstrapStore()
const router = useRouter()

const canManage = computed(() => store.can(Permission.runtimeConfigManage))

const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const loaded = ref<RuntimeConfigDetail | null>(null)

const form = reactive({
  timezone: '',
  trace_retention_days: 30,
  usage_retention_days: 365,
  audit_retention_days: 365,
  diagnostic_sample_retention_days: 7,
  dashboard_refresh_seconds: 30,
  max_message_chars: 100000,
  max_request_chars: 500000,
  diagnostic_sampling_enabled: false,
  diagnostic_sample_rate: '0',
  diagnostic_sample_max_chars: 1000,
  client_ip_recording_enabled: false,
  trusted_proxy_cidrs: [] as string[],
  newProxyCidr: '',
  publish_instance_timeout_seconds: 60,
  instance_stale_seconds: 60,
})
const version = ref<number | null>(null)
const localErrors = ref<Record<string, string>>({})

const { submitting, conflictError, errorText, submit, reset } = useFormSubmit()

const dirty = ref(false)
function markDirty(): void {
  dirty.value = true
}
useDirtyGuard(() => dirty.value && state.value === 'ready')

// —— 保留影响（FE-044）——
const impact = ref<RetentionImpactResult | null>(null)
const impactLoading = ref(false)
const impactError = ref('')

const retentionKeys = [
  'trace_retention_days',
  'usage_retention_days',
  'audit_retention_days',
  'diagnostic_sample_retention_days',
] as const

function retentionChanged(): boolean {
  if (!loaded.value || !impact.value) return false
  return retentionKeys.some((key) => form[key] !== impact.value!.target_values[key])
}

function impactExpired(): boolean {
  if (!impact.value) return false
  return new Date(impact.value.expires_at).getTime() < Date.now()
}

const impactUsable = computed(
  () => impact.value !== null && !impactExpired() && !retentionChanged(),
)

async function estimateImpact(): Promise<void> {
  if (impactLoading.value) return
  impactLoading.value = true
  impactError.value = ''
  try {
    impact.value = await fetchRetentionImpact({
      trace_retention_days: form.trace_retention_days,
      usage_retention_days: form.usage_retention_days,
      audit_retention_days: form.audit_retention_days,
      diagnostic_sample_retention_days: form.diagnostic_sample_retention_days,
    })
  } catch (e) {
    impactError.value = e instanceof Error ? `${e.message}` : '估算失败'
  } finally {
    impactLoading.value = false
  }
}

function applyDetail(detail: RuntimeConfigDetail): void {
  loaded.value = detail
  form.timezone = detail.timezone
  form.trace_retention_days = detail.trace_retention_days
  form.usage_retention_days = detail.usage_retention_days
  form.audit_retention_days = detail.audit_retention_days
  form.diagnostic_sample_retention_days = detail.diagnostic_sample_retention_days
  form.dashboard_refresh_seconds = detail.dashboard_refresh_seconds
  form.max_message_chars = detail.max_message_chars
  form.max_request_chars = detail.max_request_chars
  form.diagnostic_sampling_enabled = detail.diagnostic_sampling_enabled
  form.diagnostic_sample_rate = detail.diagnostic_sample_rate
  form.diagnostic_sample_max_chars = detail.diagnostic_sample_max_chars
  form.client_ip_recording_enabled = detail.client_ip_recording_enabled
  form.trusted_proxy_cidrs = [...detail.trusted_proxy_cidrs]
  form.publish_instance_timeout_seconds = detail.publish_instance_timeout_seconds
  form.instance_stale_seconds = detail.instance_stale_seconds
  version.value = detail.version
  impact.value = null
  dirty.value = false
}

onMounted(async () => {
  state.value = 'loading'
  try {
    applyDetail(await fetchRuntimeConfig())
    state.value = 'ready'
  } catch (e) {
    loadError.value = e
    state.value = 'error'
  }
})

async function reloadLatest(): Promise<void> {
  reset()
  try {
    applyDetail(await fetchRuntimeConfig())
  } catch {
    router.push({ name: 'overview' })
  }
}

const CIDR_PATTERN =
  /^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(\/\d{1,2})?|[0-9a-fA-F:]+(\/\d{1,3})?)$/

function validate(): boolean {
  const errors: Record<string, string> = {}
  if (form.trace_retention_days < 1 || form.trace_retention_days > 365) {
    errors.trace_retention_days = '1—365'
  }
  if (form.usage_retention_days < 30 || form.usage_retention_days > 3650) {
    errors.usage_retention_days = '30—3650'
  } else if (form.usage_retention_days < form.trace_retention_days) {
    errors.usage_retention_days = '不得小于 Trace 保留期'
  }
  if (form.audit_retention_days < 365 || form.audit_retention_days > 3650) {
    errors.audit_retention_days = '365—3650'
  }
  if (form.diagnostic_sample_retention_days < 1 || form.diagnostic_sample_retention_days > 30) {
    errors.diagnostic_sample_retention_days = '1—30'
  } else if (form.diagnostic_sample_retention_days > form.trace_retention_days) {
    errors.diagnostic_sample_retention_days = '不得超过 Trace 保留期'
  }
  if (form.dashboard_refresh_seconds < 10 || form.dashboard_refresh_seconds > 300) {
    errors.dashboard_refresh_seconds = '10—300'
  }
  if (form.max_message_chars < 1000 || form.max_message_chars > 1000000) {
    errors.max_message_chars = '1000—1000000'
  }
  if (form.max_request_chars < form.max_message_chars || form.max_request_chars > 5000000) {
    errors.max_request_chars = '不小于单条上限且不超过 5000000'
  }
  const rate = Number(form.diagnostic_sample_rate)
  if (
    !Number.isFinite(rate) ||
    rate < 0 ||
    rate > 1 ||
    (form.diagnostic_sampling_enabled === false && rate !== 0)
  ) {
    errors.diagnostic_sample_rate = '0—1；关闭采样时必须为 0'
  }
  if (form.diagnostic_sample_max_chars < 100 || form.diagnostic_sample_max_chars > 10000) {
    errors.diagnostic_sample_max_chars = '100—10000'
  }
  if (form.publish_instance_timeout_seconds < 10 || form.publish_instance_timeout_seconds > 300) {
    errors.publish_instance_timeout_seconds = '10—300'
  }
  if (form.instance_stale_seconds < 30 || form.instance_stale_seconds > 600) {
    errors.instance_stale_seconds = '30—600'
  }
  for (const key of Object.keys(errors)) {
    if (errors[key] === undefined) delete errors[key]
  }
  localErrors.value = errors
  return Object.keys(errors).length === 0
}

const shorteningRetention = computed(() => {
  if (!loaded.value) return false
  return (
    form.trace_retention_days < loaded.value.trace_retention_days ||
    form.usage_retention_days < loaded.value.usage_retention_days ||
    form.audit_retention_days < loaded.value.audit_retention_days ||
    form.diagnostic_sample_retention_days < loaded.value.diagnostic_sample_retention_days
  )
})

async function save(): Promise<void> {
  if (!validate()) return
  if (shorteningRetention.value && !impactUsable.value) {
    localErrors.value = { retention: '缩短保留期需先估算影响并确认' }
    return
  }
  const outcome = await submit(async () => {
    const result = await updateRuntimeConfig({
      version: version.value!,
      timezone: form.timezone,
      trace_retention_days: form.trace_retention_days,
      usage_retention_days: form.usage_retention_days,
      audit_retention_days: form.audit_retention_days,
      diagnostic_sample_retention_days: form.diagnostic_sample_retention_days,
      dashboard_refresh_seconds: form.dashboard_refresh_seconds,
      max_message_chars: form.max_message_chars,
      max_request_chars: form.max_request_chars,
      diagnostic_sampling_enabled: form.diagnostic_sampling_enabled,
      diagnostic_sample_rate: form.diagnostic_sampling_enabled ? form.diagnostic_sample_rate : '0',
      diagnostic_sample_max_chars: form.diagnostic_sample_max_chars,
      client_ip_recording_enabled: form.client_ip_recording_enabled,
      trusted_proxy_cidrs: [...form.trusted_proxy_cidrs],
      publish_instance_timeout_seconds: form.publish_instance_timeout_seconds,
      instance_stale_seconds: form.instance_stale_seconds,
      ...(shorteningRetention.value && impactUsable.value
        ? { confirmed_impact_version: impact.value!.impact_version }
        : {}),
    })
    version.value = result.version
    void store.refreshDraftSummary()
  })
  if (outcome.ok) {
    dirty.value = false
    await reloadLatest()
  }
}

function addProxyCidr(): void {
  const value = form.newProxyCidr.trim()
  if (value === '' || !CIDR_PATTERN.test(value) || form.trusted_proxy_cidrs.length >= 100) return
  if (!form.trusted_proxy_cidrs.includes(value)) {
    form.trusted_proxy_cidrs.push(value)
    markDirty()
  }
  form.newProxyCidr = ''
}

function removeProxyCidr(index: number): void {
  form.trusted_proxy_cidrs.splice(index, 1)
  markDirty()
}

function fieldError(field: string): string | undefined {
  return localErrors.value[field]
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        运行参数
      </h1>
      <button
        type="button"
        class="lai-btn"
        @click="router.back()"
      >
        返回
      </button>
    </div>

    <PageState
      v-if="state === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="state === 'error'"
      status="error"
      :error="loadError"
      @retry="reloadLatest"
    />
    <form
      v-else
      class="lai-form"
      novalidate
      @submit.prevent="save"
    >
      <VersionConflictBanner
        :error="conflictError"
        @reload="reloadLatest"
      />

      <div class="lai-card">
        <h2 class="lai-card-title">
          配置状态
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">活动快照</span>#{{ loaded!.current_snapshot_no }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">发布时间</span>{{ formatDateTime(loaded!.published_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">变更状态</span>{{ loaded!.draft_changed ? '待发布' : '已发布一致' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">草稿修订号</span>{{ loaded!.draft_revision }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">最近修改人</span>{{ loaded!.last_modified_by_name }}
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          时间与保留
        </h2>
        <div class="lai-form-grid">
          <div class="lai-form-field">
            <span class="lai-form-label">时区</span>
            <input
              class="lai-input"
              type="text"
              :value="form.timezone"
              readonly
            >
            <p class="lai-form-hint">
              已存在聚合数据，时区锁定不可修改
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('trace_retention_days') }"
          >
            <label
              class="lai-form-label"
              for="rt-trace"
            >Trace 保留天数</label>
            <input
              id="rt-trace"
              v-model.number="form.trace_retention_days"
              class="lai-input"
              type="number"
              min="1"
              max="365"
              @input="markDirty"
            >
            <p
              v-if="fieldError('trace_retention_days')"
              class="lai-form-message-error"
            >
              {{ fieldError('trace_retention_days') }}
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('usage_retention_days') }"
          >
            <label
              class="lai-form-label"
              for="rt-usage"
            >Usage 保留天数</label>
            <input
              id="rt-usage"
              v-model.number="form.usage_retention_days"
              class="lai-input"
              type="number"
              min="30"
              max="3650"
              @input="markDirty"
            >
            <p
              v-if="fieldError('usage_retention_days')"
              class="lai-form-message-error"
            >
              {{ fieldError('usage_retention_days') }}
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('audit_retention_days') }"
          >
            <label
              class="lai-form-label"
              for="rt-audit"
            >审计保留天数</label>
            <input
              id="rt-audit"
              v-model.number="form.audit_retention_days"
              class="lai-input"
              type="number"
              min="365"
              max="3650"
              @input="markDirty"
            >
            <p
              v-if="fieldError('audit_retention_days')"
              class="lai-form-message-error"
            >
              {{ fieldError('audit_retention_days') }}
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('dashboard_refresh_seconds') }"
          >
            <label
              class="lai-form-label"
              for="rt-refresh"
            >刷新间隔（秒）</label>
            <input
              id="rt-refresh"
              v-model.number="form.dashboard_refresh_seconds"
              class="lai-input"
              type="number"
              min="10"
              max="300"
              @input="markDirty"
            >
            <p
              v-if="fieldError('dashboard_refresh_seconds')"
              class="lai-form-message-error"
            >
              {{ fieldError('dashboard_refresh_seconds') }}
            </p>
          </div>
        </div>
        <div
          v-if="shorteningRetention"
          class="lai-retention-impact"
        >
          <p class="lai-card-hint">
            缩短保留期会提前删除数据；保存前必须完成影响估算。
          </p>
          <button
            type="button"
            class="lai-btn"
            :disabled="impactLoading"
            @click="estimateImpact"
          >
            {{ impactLoading ? '估算中…' : '估算保留影响' }}
          </button>
          <p
            v-if="impactError"
            class="lai-form-message-error"
            role="alert"
          >
            {{ impactError }}
          </p>
          <div
            v-if="impact && impactUsable"
            class="lai-summary-grid lai-retention-grid"
          >
            <div class="lai-summary-item">
              <span class="lai-summary-label">预计删除 Trace</span>{{ impact.counts.trace }}
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">预计删除 Usage 聚合</span>{{ impact.counts.usage }}
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">预计删除审计</span>{{ impact.counts.audit }}
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">预计删除样本</span>{{ impact.counts.sample }}
            </div>
            <div class="lai-summary-item">
              <span class="lai-summary-label">最早保留至</span>{{ formatDateTime(impact.earliest_remaining_at, store.timezone) }}
            </div>
          </div>
          <p
            v-if="impact && !impactUsable"
            class="lai-form-message-error"
            role="alert"
          >
            影响估算已过期或目标参数已变化，请重新估算。
          </p>
          <p
            v-if="fieldError('retention')"
            class="lai-form-message-error"
            role="alert"
          >
            {{ fieldError('retention') }}
          </p>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          请求限制
        </h2>
        <div class="lai-form-grid">
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('max_message_chars') }"
          >
            <label
              class="lai-form-label"
              for="rt-msg"
            >单条消息字符上限</label>
            <input
              id="rt-msg"
              v-model.number="form.max_message_chars"
              class="lai-input"
              type="number"
              min="1000"
              max="1000000"
              @input="markDirty"
            >
            <p
              v-if="fieldError('max_message_chars')"
              class="lai-form-message-error"
            >
              {{ fieldError('max_message_chars') }}
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('max_request_chars') }"
          >
            <label
              class="lai-form-label"
              for="rt-req"
            >请求总字符上限</label>
            <input
              id="rt-req"
              v-model.number="form.max_request_chars"
              class="lai-input"
              type="number"
              min="1000"
              max="5000000"
              @input="markDirty"
            >
            <p
              v-if="fieldError('max_request_chars')"
              class="lai-form-message-error"
            >
              {{ fieldError('max_request_chars') }}
            </p>
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          诊断采样
        </h2>
        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="rt-sampling"
          >启用诊断采样</label>
          <input
            id="rt-sampling"
            v-model="form.diagnostic_sampling_enabled"
            type="checkbox"
            class="lai-checkbox"
            @change="markDirty"
          >
        </div>
        <div
          v-if="form.diagnostic_sampling_enabled"
          class="lai-form-grid"
        >
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('diagnostic_sample_rate') }"
          >
            <label
              class="lai-form-label"
              for="rt-rate"
            >采样率（0—1）</label>
            <input
              id="rt-rate"
              v-model="form.diagnostic_sample_rate"
              class="lai-input"
              type="text"
              @input="markDirty"
            >
            <p
              v-if="fieldError('diagnostic_sample_rate')"
              class="lai-form-message-error"
            >
              {{ fieldError('diagnostic_sample_rate') }}
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('diagnostic_sample_retention_days') }"
          >
            <label
              class="lai-form-label"
              for="rt-sample-retention"
            >样本保留天数</label>
            <input
              id="rt-sample-retention"
              v-model.number="form.diagnostic_sample_retention_days"
              class="lai-input"
              type="number"
              min="1"
              max="30"
              @input="markDirty"
            >
            <p
              v-if="fieldError('diagnostic_sample_retention_days')"
              class="lai-form-message-error"
            >
              {{ fieldError('diagnostic_sample_retention_days') }}
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('diagnostic_sample_max_chars') }"
          >
            <label
              class="lai-form-label"
              for="rt-sample-chars"
            >样本字符上限</label>
            <input
              id="rt-sample-chars"
              v-model.number="form.diagnostic_sample_max_chars"
              class="lai-input"
              type="number"
              min="100"
              max="10000"
              @input="markDirty"
            >
            <p
              v-if="fieldError('diagnostic_sample_max_chars')"
              class="lai-form-message-error"
            >
              {{ fieldError('diagnostic_sample_max_chars') }}
            </p>
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          来源 IP
        </h2>
        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="rt-ip"
          >记录来源 IP 到 Trace</label>
          <input
            id="rt-ip"
            v-model="form.client_ip_recording_enabled"
            type="checkbox"
            class="lai-checkbox"
            @change="markDirty"
          >
        </div>
        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="rt-proxy"
          >可信代理 CIDR（最多 100 项）</label>
          <div class="lai-kv-row">
            <input
              id="rt-proxy"
              v-model="form.newProxyCidr"
              class="lai-input lai-filter-keyword"
              type="text"
              placeholder="IPv4、IPv6 或 CIDR"
              @keydown.enter.prevent="addProxyCidr"
            >
            <button
              type="button"
              class="lai-btn"
              :disabled="form.trusted_proxy_cidrs.length >= 100"
              @click="addProxyCidr"
            >
              添加
            </button>
          </div>
          <ul class="lai-related-list">
            <li
              v-for="(cidr, index) in form.trusted_proxy_cidrs"
              :key="cidr"
            >
              {{ cidr }}
              <button
                type="button"
                class="lai-btn lai-btn-text"
                @click="removeProxyCidr(index)"
              >
                移除
              </button>
            </li>
            <li
              v-if="form.trusted_proxy_cidrs.length === 0"
              class="lai-related-meta"
            >
              未配置
            </li>
          </ul>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          发布协调
        </h2>
        <div class="lai-form-grid">
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('publish_instance_timeout_seconds') }"
          >
            <label
              class="lai-form-label"
              for="rt-pub-timeout"
            >发布实例时限（秒）</label>
            <input
              id="rt-pub-timeout"
              v-model.number="form.publish_instance_timeout_seconds"
              class="lai-input"
              type="number"
              min="10"
              max="300"
              @input="markDirty"
            >
            <p
              v-if="fieldError('publish_instance_timeout_seconds')"
              class="lai-form-message-error"
            >
              {{ fieldError('publish_instance_timeout_seconds') }}
            </p>
          </div>
          <div
            class="lai-form-field"
            :class="{ 'lai-form-field-error': fieldError('instance_stale_seconds') }"
          >
            <label
              class="lai-form-label"
              for="rt-stale"
            >实例失联阈值（秒）</label>
            <input
              id="rt-stale"
              v-model.number="form.instance_stale_seconds"
              class="lai-input"
              type="number"
              min="30"
              max="600"
              @input="markDirty"
            >
            <p
              v-if="fieldError('instance_stale_seconds')"
              class="lai-form-message-error"
            >
              {{ fieldError('instance_stale_seconds') }}
            </p>
          </div>
        </div>
      </div>

      <p
        v-if="errorText"
        class="lai-form-message-error"
        role="alert"
      >
        {{ errorText }}
      </p>

      <div class="lai-form-actions">
        <button
          type="button"
          class="lai-btn"
          :disabled="submitting"
          @click="reloadLatest"
        >
          重置未保存输入
        </button>
        <button
          v-if="canManage"
          type="submit"
          class="lai-btn lai-btn-primary"
          :disabled="submitting || conflictError !== null"
        >
          {{ submitting ? '保存中…' : '保存草稿' }}
        </button>
      </div>
    </form>
  </section>
</template>
