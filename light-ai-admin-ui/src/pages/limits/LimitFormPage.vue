<script setup lang="ts">
// 限流策略新建/编辑表单（FE-019，附录 4.3.1.2）。
// scope 创建后只读；REJECT 不提交队列字段；启用要求至少一个限额；空值保留 null。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import {
  createLimitPolicy,
  fetchLimitPolicy,
  fetchScopeOptions,
  updateLimitPolicy,
  type LimitPolicyDetail,
  type ScopeOption,
  type ScopeType,
} from '@/api/limitPolicies'

const route = useRoute()
const router = useRouter()
const policyId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isEdit = computed(() => policyId.value !== '')

const loading = ref(true)
const loadError = ref<unknown>(null)

const form = reactive({
  name: '',
  scope_type: 'MODEL_ALIAS' as ScopeType,
  scope_id: '',
  rpm_limit: '',
  tpm_limit: '',
  concurrent_limit: '',
  overflow_strategy: 'REJECT' as 'REJECT' | 'QUEUE',
  queue_timeout_ms: '5000',
  queue_max_size: '1000',
  enabled: true,
})
const version = ref<number | null>(null)
const baseline = ref('')
const dirty = ref(false)
const { submitting, conflictError, errorText, submit: doSubmit } = useFormSubmit()

const scopeOptions = ref<ScopeOption[]>([])
const scopeLoading = ref(false)
const scopeError = ref('')

const nameInvalid = computed(() => form.name.trim().length < 2 || form.name.trim().length > 64)
const scopeInvalid = computed(() => form.scope_id === '')
const rpmInvalid = computed(() => form.rpm_limit !== '' && (!/^\d+$/.test(form.rpm_limit) || Number(form.rpm_limit) < 1 || Number(form.rpm_limit) > 1000000000))
const tpmInvalid = computed(() => form.tpm_limit !== '' && (!/^\d+$/.test(form.tpm_limit) || Number(form.tpm_limit) < 1 || !Number.isSafeInteger(Number(form.tpm_limit))))
const concurrentInvalid = computed(() => form.concurrent_limit !== '' && (!/^\d+$/.test(form.concurrent_limit) || Number(form.concurrent_limit) < 1 || Number(form.concurrent_limit) > 100000))
const noLimitSet = computed(() => form.rpm_limit === '' && form.tpm_limit === '' && form.concurrent_limit === '')
const enableBlocked = computed(() => form.enabled && noLimitSet.value)
const queueTimeoutInvalid = computed(
  () => form.overflow_strategy === 'QUEUE' && (!/^\d+$/.test(form.queue_timeout_ms) || Number(form.queue_timeout_ms) < 1 || Number(form.queue_timeout_ms) > 60000),
)
const queueMaxInvalid = computed(
  () => form.overflow_strategy === 'QUEUE' && (!/^\d+$/.test(form.queue_max_size) || Number(form.queue_max_size) < 1 || Number(form.queue_max_size) > 100000),
)
const formInvalid = computed(
  () =>
    nameInvalid.value ||
    scopeInvalid.value ||
    rpmInvalid.value ||
    tpmInvalid.value ||
    concurrentInvalid.value ||
    (form.overflow_strategy === 'QUEUE' && (queueTimeoutInvalid.value || queueMaxInvalid.value)),
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

async function loadScopeOptions(): Promise<void> {
  scopeLoading.value = true
  scopeError.value = ''
  try {
    scopeOptions.value = await fetchScopeOptions(form.scope_type)
  } catch {
    scopeError.value = '作用对象加载失败，请稍后重试'
  } finally {
    scopeLoading.value = false
  }
}

function onScopeTypeChange(): void {
  if (isEdit.value) return
  form.scope_id = ''
  void loadScopeOptions()
}

function applyDetail(detail: LimitPolicyDetail): void {
  form.name = detail.name
  form.scope_type = detail.scope_type
  form.scope_id = detail.scope_id
  form.rpm_limit = detail.rpm_limit?.toString() ?? ''
  form.tpm_limit = detail.tpm_limit?.toString() ?? ''
  form.concurrent_limit = detail.concurrent_limit?.toString() ?? ''
  form.overflow_strategy = detail.overflow_strategy
  form.queue_timeout_ms = detail.queue_timeout_ms?.toString() ?? '5000'
  form.queue_max_size = '1000'
  form.enabled = detail.enabled
  version.value = detail.version
  markClean()
}

async function onSubmit(): Promise<void> {
  if (formInvalid.value || enableBlocked.value) return
  const queueFields =
    form.overflow_strategy === 'QUEUE'
      ? { queue_timeout_ms: Number(form.queue_timeout_ms), queue_max_size: Number(form.queue_max_size) }
      : { queue_timeout_ms: null, queue_max_size: null }
  const command = {
    name: form.name.trim(),
    scope_type: form.scope_type,
    scope_id: form.scope_id,
    rpm_limit: form.rpm_limit === '' ? null : Number(form.rpm_limit),
    tpm_limit: form.tpm_limit === '' ? null : Number(form.tpm_limit),
    concurrent_limit: form.concurrent_limit === '' ? null : Number(form.concurrent_limit),
    overflow_strategy: form.overflow_strategy,
    ...queueFields,
    enabled: form.enabled,
    version: isEdit.value ? (version.value ?? undefined) : undefined,
  }
  const outcome = await doSubmit(async () => {
    if (isEdit.value) {
      await updateLimitPolicy(policyId.value, command)
    } else {
      await createLimitPolicy(command)
    }
  })
  if (outcome.ok) {
    dirty.value = false
    void router.push('/ui/limit-policies')
  }
}

onMounted(async () => {
  try {
    if (isEdit.value) {
      applyDetail(await fetchLimitPolicy(policyId.value))
    } else {
      await loadScopeOptions()
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
      {{ isEdit ? '编辑限流策略' : '新建限流策略' }}
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

      <div class="lai-form-grid">
        <FormField
          label="范围类型"
          required
          :hint="isEdit ? '创建后不可修改' : ''"
        >
          <select
            v-model="form.scope_type"
            class="lai-input lai-select"
            :disabled="isEdit"
            @change="onScopeTypeChange"
          >
            <option value="MODEL_ALIAS">
              模型别名
            </option>
            <option value="PROVIDER_MODEL">
              模型
            </option>
            <option value="CREDENTIAL">
              凭证
            </option>
          </select>
        </FormField>
        <FormField
          label="作用对象"
          required
          :hint="scopeLoading ? '加载中…' : isEdit ? '创建后不可修改' : ''"
          :error="scopeInvalid ? '请选择作用对象' : scopeError"
        >
          <select
            v-model="form.scope_id"
            class="lai-input lai-select"
            :disabled="isEdit || scopeLoading"
          >
            <option
              value=""
              disabled
            >
              请选择作用对象
            </option>
            <option
              v-for="item in scopeOptions"
              :key="item.id"
              :value="item.id"
            >
              {{ item.label }}
            </option>
          </select>
        </FormField>
      </div>

      <div class="lai-form-grid">
        <FormField
          label="RPM 上限"
          :error="rpmInvalid ? '空或 1—1000000000 的正整数' : ''"
          hint="空为不限制"
        >
          <input
            v-model="form.rpm_limit"
            class="lai-input"
            type="text"
            inputmode="numeric"
            placeholder="不限制"
          >
        </FormField>
        <FormField
          label="TPM 上限"
          :error="tpmInvalid ? '空或正整数' : ''"
          hint="空为不限制"
        >
          <input
            v-model="form.tpm_limit"
            class="lai-input"
            type="text"
            inputmode="numeric"
            placeholder="不限制"
          >
        </FormField>
        <FormField
          label="并发上限"
          :error="concurrentInvalid ? '空或 1—100000' : ''"
          hint="空为不限制"
        >
          <input
            v-model="form.concurrent_limit"
            class="lai-input"
            type="text"
            inputmode="numeric"
            placeholder="不限制"
          >
        </FormField>
      </div>

      <div class="lai-form-grid">
        <FormField
          label="溢出策略"
          required
        >
          <select
            v-model="form.overflow_strategy"
            class="lai-input lai-select"
          >
            <option value="REJECT">
              直接拒绝
            </option>
            <option value="QUEUE">
              进入排队
            </option>
          </select>
        </FormField>
        <template v-if="form.overflow_strategy === 'QUEUE'">
          <FormField
            label="排队超时（毫秒，1—60000）"
            required
            :error="queueTimeoutInvalid ? '范围为 1—60000' : ''"
          >
            <input
              v-model="form.queue_timeout_ms"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="队列长度上限（1—100000）"
            required
            :error="queueMaxInvalid ? '范围为 1—100000' : ''"
          >
            <input
              v-model="form.queue_max_size"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
        </template>
        <FormField label="窗口宽度">
          <input
            class="lai-input"
            type="text"
            value="60 秒（固定）"
            disabled
          >
        </FormField>
      </div>

      <label class="lai-switch">
        <input
          v-model="form.enabled"
          type="checkbox"
        >
        启用（要求至少设置一个上限）
      </label>
      <p
        v-if="enableBlocked"
        class="lai-form-message-error"
      >
        至少一个 RPM、TPM、并发上限非空时才允许启用
      </p>

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
          :disabled="submitting || formInvalid || enableBlocked"
        >
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.lai-form {
  max-width: 720px;
}
.lai-form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 4px 16px;
  margin: 8px 0;
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
  margin: 8px 0;
}
.lai-form-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
