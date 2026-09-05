<script setup lang="ts">
// 可靠性策略新建/编辑表单（FE-021，附录 4.3.2.2）。
// Alias 创建后只读；首 Token 超时必须小于总超时；fallback 关闭时 max_fallbacks 强制 0；
// 失败率界面百分比 1.00—100.00，提交转换为 0.01—1 小数（接口 0—1）。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import {
  createReliabilityPolicy,
  fetchReliabilityPolicy,
  updateReliabilityPolicy,
  type ReliabilityPolicyDetail,
} from '@/api/reliabilityPolicies'
import { fetchModelAliases } from '@/api/modelAliases'

const route = useRoute()
const router = useRouter()
const policyId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isEdit = computed(() => policyId.value !== '')

const loading = ref(true)
const loadError = ref<unknown>(null)

const form = reactive({
  name: '',
  alias_id: '',
  connect_timeout_ms: '3000',
  first_token_timeout_ms: '30000',
  total_timeout_ms: '120000',
  max_retries: '1',
  max_credential_failovers: '1',
  initial_backoff_ms: '200',
  backoff_multiplier: '2.00',
  jitter_percent: '20',
  respect_retry_after: true,
  max_retry_after_ms: '5000',
  fallback_enabled: true,
  max_fallbacks: '2',
  circuit_window_seconds: '60',
  circuit_min_requests: '20',
  circuit_failure_rate_percent: '50.00',
  circuit_open_seconds: '30',
  circuit_half_open_probes: '3',
  circuit_half_open_successes: '2',
  enabled: true,
})
const version = ref<number | null>(null)
const baseline = ref('')
const dirty = ref(false)
const { submitting, conflictError, errorText, submit: doSubmit } = useFormSubmit()

const aliasOptions = ref<{ id: string; label: string }[]>([])
const aliasLoading = ref(false)

function intField(value: string, min: number, max: number): boolean {
  return !/^\d+$/.test(value) || Number(value) < min || Number(value) > max
}

const nameInvalid = computed(() => form.name.trim().length < 2 || form.name.trim().length > 64)
const aliasInvalid = computed(() => form.alias_id === '')
const connectInvalid = computed(() => intField(form.connect_timeout_ms, 100, 60000))
const firstTokenInvalid = computed(() => intField(form.first_token_timeout_ms, 1000, 300000))
const totalInvalid = computed(() => intField(form.total_timeout_ms, 1000, 600000))
const firstTokenRelationInvalid = computed(() => Number(form.first_token_timeout_ms) >= Number(form.total_timeout_ms))
const retriesInvalid = computed(() => intField(form.max_retries, 0, 5))
const failoversInvalid = computed(() => intField(form.max_credential_failovers, 0, 10))
const backoffInvalid = computed(() => intField(form.initial_backoff_ms, 0, 10000))
const multiplierInvalid = computed(() => {
  const value = Number(form.backoff_multiplier)
  return !/^\d+(\.\d{1,2})?$/.test(form.backoff_multiplier) || value < 1 || value > 5
})
const jitterInvalid = computed(() => intField(form.jitter_percent, 0, 100))
const retryAfterInvalid = computed(() => intField(form.max_retry_after_ms, 0, 60000))
const fallbacksInvalid = computed(() => intField(form.max_fallbacks, 0, 10))
const circuitWindowInvalid = computed(() => intField(form.circuit_window_seconds, 10, 600))
const circuitMinInvalid = computed(() => intField(form.circuit_min_requests, 1, 10000))
const circuitRateInvalid = computed(() => {
  if (!/^\d+(\.\d{1,2})?$/.test(form.circuit_failure_rate_percent)) return true
  const value = Number(form.circuit_failure_rate_percent)
  return value < 1 || value > 100
})
const circuitOpenInvalid = computed(() => intField(form.circuit_open_seconds, 1, 3600))
const probesInvalid = computed(() => intField(form.circuit_half_open_probes, 1, 100))
const successesInvalid = computed(() => {
  if (intField(form.circuit_half_open_successes, 1, 100)) return true
  return Number(form.circuit_half_open_successes) > Number(form.circuit_half_open_probes)
})
const formInvalid = computed(
  () =>
    nameInvalid.value ||
    aliasInvalid.value ||
    connectInvalid.value ||
    firstTokenInvalid.value ||
    totalInvalid.value ||
    firstTokenRelationInvalid.value ||
    retriesInvalid.value ||
    failoversInvalid.value ||
    backoffInvalid.value ||
    multiplierInvalid.value ||
    jitterInvalid.value ||
    retryAfterInvalid.value ||
    fallbacksInvalid.value ||
    circuitWindowInvalid.value ||
    circuitMinInvalid.value ||
    circuitRateInvalid.value ||
    circuitOpenInvalid.value ||
    probesInvalid.value ||
    successesInvalid.value,
)

function snapshot(): string {
  return JSON.stringify(form)
}
function markClean(): void {
  baseline.value = snapshot()
  dirty.value = false
}
function onInput(): void {
  dirty.value = snapshot() !== baseline.value
}
useDirtyGuard(() => dirty.value)

async function loadAliases(): Promise<void> {
  aliasLoading.value = true
  try {
    const page = await fetchModelAliases({ page: 1, page_size: 100, sort: 'alias' })
    aliasOptions.value = page.items.map((item) => ({ id: item.id, label: `${item.display_name}（${item.alias}）` }))
  } finally {
    aliasLoading.value = false
  }
}

function applyDetail(detail: ReliabilityPolicyDetail): void {
  form.name = detail.name
  form.alias_id = detail.alias_id
  form.connect_timeout_ms = String(detail.connect_timeout_ms)
  form.first_token_timeout_ms = String(detail.first_token_timeout_ms)
  form.total_timeout_ms = String(detail.total_timeout_ms)
  form.max_retries = String(detail.max_retries)
  form.max_credential_failovers = String(detail.max_credential_failovers)
  form.initial_backoff_ms = String(detail.initial_backoff_ms)
  form.backoff_multiplier = detail.backoff_multiplier
  form.jitter_percent = String(detail.jitter_percent)
  form.respect_retry_after = detail.respect_retry_after
  form.max_retry_after_ms = String(detail.max_retry_after_ms)
  form.fallback_enabled = detail.fallback_enabled
  form.max_fallbacks = String(detail.max_fallbacks)
  form.circuit_window_seconds = String(detail.circuit_window_seconds)
  form.circuit_min_requests = String(detail.circuit_min_requests)
  form.circuit_failure_rate_percent = (Number(detail.circuit_failure_rate) * 100).toFixed(2)
  form.circuit_open_seconds = String(detail.circuit_open_seconds)
  form.circuit_half_open_probes = String(detail.circuit_half_open_probes)
  form.circuit_half_open_successes = String(detail.circuit_half_open_successes)
  form.enabled = detail.enabled
  version.value = detail.version
  markClean()
}

async function onSubmit(): Promise<void> {
  if (formInvalid.value) return
  const command = {
    name: form.name.trim(),
    alias_id: form.alias_id,
    connect_timeout_ms: Number(form.connect_timeout_ms),
    first_token_timeout_ms: Number(form.first_token_timeout_ms),
    total_timeout_ms: Number(form.total_timeout_ms),
    max_retries: Number(form.max_retries),
    max_credential_failovers: Number(form.max_credential_failovers),
    initial_backoff_ms: Number(form.initial_backoff_ms),
    backoff_multiplier: form.backoff_multiplier,
    jitter_percent: Number(form.jitter_percent),
    respect_retry_after: form.respect_retry_after,
    max_retry_after_ms: Number(form.max_retry_after_ms),
    fallback_enabled: form.fallback_enabled,
    max_fallbacks: form.fallback_enabled ? Number(form.max_fallbacks) : 0,
    circuit_window_seconds: Number(form.circuit_window_seconds),
    circuit_min_requests: Number(form.circuit_min_requests),
    circuit_failure_rate: (Number(form.circuit_failure_rate_percent) / 100).toFixed(4),
    circuit_open_seconds: Number(form.circuit_open_seconds),
    circuit_half_open_probes: Number(form.circuit_half_open_probes),
    circuit_half_open_successes: Number(form.circuit_half_open_successes),
    enabled: form.enabled,
    version: isEdit.value ? (version.value ?? undefined) : undefined,
  }
  const outcome = await doSubmit(async () => {
    if (isEdit.value) {
      await updateReliabilityPolicy(policyId.value, command)
    } else {
      await createReliabilityPolicy(command)
    }
  })
  if (outcome.ok) {
    dirty.value = false
    void router.push('/ui/reliability-policies')
  }
}

onMounted(async () => {
  try {
    await loadAliases()
    if (isEdit.value) {
      applyDetail(await fetchReliabilityPolicy(policyId.value))
    } else {
      markClean()
    }
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="lai-page">
    <h1 class="lai-page-title">
      {{ isEdit ? '编辑可靠性策略' : '新建可靠性策略' }}
    </h1>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
    />

    <form
      v-else
      class="lai-form"
      @submit.prevent="onSubmit"
      @input="onInput"
    >
      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          基础
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="名称"
            required
            :error="nameInvalid ? '长度为 2—64 字符，全局唯一' : ''"
          >
            <input
              v-model="form.name"
              class="lai-input"
              type="text"
              maxlength="64"
            >
          </FormField>
          <FormField
            label="Model Alias"
            required
            :hint="aliasLoading ? '加载中…' : isEdit ? '创建后不可修改；同一 Alias 最多一份启用策略' : ''"
            :error="aliasInvalid ? '请选择 Alias' : ''"
          >
            <select
              v-model="form.alias_id"
              class="lai-input lai-select"
              :disabled="isEdit || aliasLoading"
            >
              <option
                value=""
                disabled
              >
                请选择 Alias
              </option>
              <option
                v-for="item in aliasOptions"
                :key="item.id"
                :value="item.id"
              >
                {{ item.label }}
              </option>
            </select>
          </FormField>
        </div>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          超时
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="连接超时（100—60000ms）"
            required
            :error="connectInvalid ? '范围为 100—60000' : ''"
          >
            <input
              v-model="form.connect_timeout_ms"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="首 Token 超时（1000—300000ms）"
            required
            :error="firstTokenInvalid ? '范围为 1000—300000' : firstTokenRelationInvalid ? '必须小于总超时' : ''"
            hint="只影响流式请求"
          >
            <input
              v-model="form.first_token_timeout_ms"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="总超时（1000—600000ms）"
            required
            :error="totalInvalid ? '范围为 1000—600000' : ''"
            hint="覆盖排队、退避和全部 Attempt"
          >
            <input
              v-model="form.total_timeout_ms"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
        </div>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          重试与退避
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="最大重试（0—5）"
            required
            :error="retriesInvalid ? '范围为 0—5' : ''"
          >
            <input
              v-model="form.max_retries"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="最大换密钥（0—10）"
            required
            :error="failoversInvalid ? '范围为 0—10' : ''"
          >
            <input
              v-model="form.max_credential_failovers"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="初始退避（0—10000ms）"
            required
            :error="backoffInvalid ? '范围为 0—10000' : ''"
          >
            <input
              v-model="form.initial_backoff_ms"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="退避倍数（1.00—5.00）"
            required
            :error="multiplierInvalid ? '范围为 1.00—5.00' : ''"
          >
            <input
              v-model="form.backoff_multiplier"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
          <FormField
            label="抖动比例（0—100%）"
            required
            :error="jitterInvalid ? '范围为 0—100' : ''"
          >
            <input
              v-model="form.jitter_percent"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="最大 Retry-After（0—60000ms）"
            :error="retryAfterInvalid ? '范围为 0—60000' : ''"
          >
            <input
              v-model="form.max_retry_after_ms"
              class="lai-input"
              type="text"
              inputmode="numeric"
              :disabled="!form.respect_retry_after"
            >
          </FormField>
        </div>
        <label class="lai-switch">
          <input
            v-model="form.respect_retry_after"
            type="checkbox"
          >
          尊重 Provider Retry-After
        </label>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          Fallback
        </legend>
        <label class="lai-switch">
          <input
            v-model="form.fallback_enabled"
            type="checkbox"
          >
          允许切换候选
        </label>
        <div
          v-if="form.fallback_enabled"
          class="lai-form-grid"
        >
          <FormField
            label="最大 Fallback（0—10）"
            required
            :error="fallbacksInvalid ? '范围为 0—10' : ''"
          >
            <input
              v-model="form.max_fallbacks"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
        </div>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          熔断
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="统计窗口（10—600s）"
            required
            :error="circuitWindowInvalid ? '范围为 10—600' : ''"
          >
            <input
              v-model="form.circuit_window_seconds"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="最小请求数（1—10000）"
            required
            :error="circuitMinInvalid ? '范围为 1—10000' : ''"
          >
            <input
              v-model="form.circuit_min_requests"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="失败率阈值（1.00—100.00%）"
            required
            :error="circuitRateInvalid ? '范围为 1.00—100.00' : ''"
          >
            <input
              v-model="form.circuit_failure_rate_percent"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
          <FormField
            label="OPEN 时长（1—3600s）"
            required
            :error="circuitOpenInvalid ? '范围为 1—3600' : ''"
          >
            <input
              v-model="form.circuit_open_seconds"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="半开探测数（1—100）"
            required
            :error="probesInvalid ? '范围为 1—100' : ''"
          >
            <input
              v-model="form.circuit_half_open_probes"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="半开成功数（≤探测数）"
            required
            :error="successesInvalid ? '不能大于探测数' : ''"
          >
            <input
              v-model="form.circuit_half_open_successes"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
        </div>
      </fieldset>

      <label class="lai-switch">
        <input
          v-model="form.enabled"
          type="checkbox"
        >
        启用（停用后 Alias 使用系统默认策略）
      </label>

      <p
        v-if="conflictError"
        class="lai-form-message-error"
        role="alert"
      >
        配置已被其他管理员修改（最新版本 {{ conflictError.serverVersion ?? '未知' }}）。您的输入已保留，请刷新后重试。
      </p>
      <p
        v-else-if="errorText"
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
          @click="router.back()"
        >
          取消
        </button>
        <button
          type="submit"
          class="lai-btn lai-btn-primary"
          :disabled="submitting || formInvalid"
        >
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.lai-form {
  max-width: 880px;
}
.lai-fieldset {
  border: 1px solid #d8dee4;
  border-radius: 6px;
  margin: 0 0 16px;
  padding: 12px 16px 16px;
}
.lai-legend {
  font-weight: 600;
  padding: 0 6px;
}
.lai-form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 4px 16px;
  margin-bottom: 8px;
}
.lai-select {
  width: 100%;
  padding: 6px 8px;
}
.lai-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  margin: 8px 24px 4px 0;
}
.lai-form-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
