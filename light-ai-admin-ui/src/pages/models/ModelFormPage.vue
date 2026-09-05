<script setup lang="ts">
// Provider Model 新建/编辑表单（FE-015，附录 4.2.6.1）。
// 能力开关关闭时隐藏对应范围与默认值；价格保持字符串精度；启用要求能力字段完整。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { fetchProviderModel, createProviderModel, updateProviderModel, fetchProviderOptions } from '@/api/providerModels'
import type { ProviderModelCommand, ProviderModelDetail, ProviderOption } from '@/api/providerModels'

const route = useRoute()
const router = useRouter()
const modelId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isEdit = computed(() => modelId.value !== '')

const loading = ref(true)
const loadError = ref<unknown>(null)
const providers = ref<ProviderOption[]>([])

const form = reactive({
  provider_id: '',
  display_name: '',
  model_id: '',
  tokenizer_family: '',
  context_window: '',
  max_output_tokens: '',
  support_stream: true,
  support_system_message: true,
  support_temperature: false,
  temperature_min: '',
  temperature_max: '',
  support_top_p: false,
  top_p_min: '',
  top_p_max: '',
  support_stop: false,
  max_stop_sequences: '',
  max_stop_length: '',
  default_temperature: '',
  default_top_p: '',
  default_max_tokens: '',
  default_stop: [] as string[],
  input_price: '0',
  output_price: '0',
  price_unit: 1000000,
  currency: 'USD',
  enabled: true,
})
const version = ref<number | null>(null)
const baseline = ref('')

const { submitting, conflictError, errorText, submit: doSubmit, reset: resetSubmit } = useFormSubmit()

function positiveInt(value: string): boolean {
  return /^\d+$/.test(value) && Number(value) >= 1
}

const displayNameInvalid = computed(() => form.display_name.trim().length < 2 || form.display_name.trim().length > 64)
const modelIdInvalid = computed(() => form.model_id.length < 1 || form.model_id.length > 128)
const contextInvalid = computed(() => form.context_window !== '' && !positiveInt(form.context_window))
const outputInvalid = computed(
  () => form.max_output_tokens !== '' && (!positiveInt(form.max_output_tokens) || (form.context_window !== '' && Number(form.max_output_tokens) >= Number(form.context_window))),
)
const temperatureRangeInvalid = computed(() => {
  if (!form.support_temperature) return false
  if (form.temperature_min === '' || form.temperature_max === '') return true
  return Number(form.temperature_max) < Number(form.temperature_min)
})
const topPRangeInvalid = computed(() => {
  if (!form.support_top_p) return false
  if (form.top_p_min === '' || form.top_p_max === '') return true
  const min = Number(form.top_p_min)
  const max = Number(form.top_p_max)
  return min < 0 || max < 0 || min > 1 || max > 1 || max < min
})
const stopRangeInvalid = computed(() => {
  if (!form.support_stop) return false
  const seq = Number(form.max_stop_sequences)
  const len = Number(form.max_stop_length)
  return !positiveInt(form.max_stop_sequences) || seq > 4 || !positiveInt(form.max_stop_length) || len > 128
})
const defaultTemperatureInvalid = computed(() => {
  if (form.default_temperature === '') return false
  if (form.support_temperature && form.temperature_min !== '' && form.temperature_max !== '') {
    const value = Number(form.default_temperature)
    return value < Number(form.temperature_min) || value > Number(form.temperature_max)
  }
  return !/^-?\d+(\.\d{1,4})?$/.test(form.default_temperature)
})
const defaultTopPInvalid = computed(() => {
  if (form.default_top_p === '') return false
  const value = Number(form.default_top_p)
  if (value < 0 || value > 1) return true
  if (form.support_top_p && form.top_p_min !== '' && form.top_p_max !== '') {
    return value < Number(form.top_p_min) || value > Number(form.top_p_max)
  }
  return false
})
const defaultMaxTokensInvalid = computed(
  () => form.default_max_tokens !== '' && (!positiveInt(form.default_max_tokens) || (form.max_output_tokens !== '' && Number(form.default_max_tokens) > Number(form.max_output_tokens))),
)
const stopListInvalid = computed(() => {
  if (!form.support_stop) return false
  if (form.max_stop_sequences !== '' && form.default_stop.length > Number(form.max_stop_sequences)) return true
  if (form.max_stop_length !== '' && form.default_stop.some((item) => item.length > Number(form.max_stop_length))) return true
  return new Set(form.default_stop).size !== form.default_stop.length
})
const priceInvalid = computed(() => {
  const pattern = /^\d+(\.\d{1,8})?$/
  return !pattern.test(form.input_price) || !pattern.test(form.output_price)
})
const currencyInvalid = computed(() => !/^[A-Z]{3}$/.test(form.currency))

const capabilityIncomplete = computed(
  () =>
    form.enabled &&
    (form.tokenizer_family.trim() === '' ||
      form.context_window === '' ||
      form.max_output_tokens === ''),
)
const formInvalid = computed(
  () =>
    displayNameInvalid.value ||
    modelIdInvalid.value ||
    contextInvalid.value ||
    outputInvalid.value ||
    temperatureRangeInvalid.value ||
    topPRangeInvalid.value ||
    stopRangeInvalid.value ||
    defaultTemperatureInvalid.value ||
    defaultTopPInvalid.value ||
    defaultMaxTokensInvalid.value ||
    stopListInvalid.value ||
    priceInvalid.value ||
    currencyInvalid.value ||
    capabilityIncomplete.value ||
    form.provider_id === '',
)

const dirty = ref(false)
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

function applyDetail(detail: ProviderModelDetail): void {
  form.provider_id = detail.provider_id
  form.display_name = detail.display_name
  form.model_id = detail.model_id
  form.tokenizer_family = detail.tokenizer_family ?? ''
  form.context_window = detail.context_window?.toString() ?? ''
  form.max_output_tokens = detail.max_output_tokens?.toString() ?? ''
  form.support_stream = detail.support_stream ?? true
  form.support_system_message = detail.support_system_message ?? true
  form.support_temperature = detail.support_temperature ?? false
  form.temperature_min = detail.temperature_min ?? ''
  form.temperature_max = detail.temperature_max ?? ''
  form.support_top_p = detail.support_top_p ?? false
  form.top_p_min = detail.top_p_min ?? ''
  form.top_p_max = detail.top_p_max ?? ''
  form.support_stop = detail.support_stop ?? false
  form.max_stop_sequences = detail.max_stop_sequences?.toString() ?? ''
  form.max_stop_length = detail.max_stop_length?.toString() ?? ''
  form.default_temperature = detail.default_temperature ?? ''
  form.default_top_p = detail.default_top_p ?? ''
  form.default_max_tokens = detail.default_max_tokens?.toString() ?? ''
  form.default_stop = [...detail.default_stop]
  form.input_price = detail.input_price
  form.output_price = detail.output_price
  form.price_unit = detail.price_unit
  form.currency = detail.currency
  form.enabled = detail.enabled
  version.value = detail.version
  markClean()
}

function buildCommand(): ProviderModelCommand {
  return {
    provider_id: form.provider_id,
    display_name: form.display_name.trim(),
    model_id: form.model_id,
    tokenizer_family: form.tokenizer_family.trim() === '' ? null : form.tokenizer_family.trim(),
    context_window: form.context_window === '' ? null : Number(form.context_window),
    max_output_tokens: form.max_output_tokens === '' ? null : Number(form.max_output_tokens),
    support_stream: form.support_stream,
    support_system_message: form.support_system_message,
    support_temperature: form.support_temperature,
    temperature_min: form.support_temperature && form.temperature_min !== '' ? form.temperature_min : null,
    temperature_max: form.support_temperature && form.temperature_max !== '' ? form.temperature_max : null,
    support_top_p: form.support_top_p,
    top_p_min: form.support_top_p && form.top_p_min !== '' ? form.top_p_min : null,
    top_p_max: form.support_top_p && form.top_p_max !== '' ? form.top_p_max : null,
    support_stop: form.support_stop,
    max_stop_sequences: form.support_stop && form.max_stop_sequences !== '' ? Number(form.max_stop_sequences) : null,
    max_stop_length: form.support_stop && form.max_stop_length !== '' ? Number(form.max_stop_length) : null,
    default_temperature: form.default_temperature === '' ? null : form.default_temperature,
    default_top_p: form.default_top_p === '' ? null : form.default_top_p,
    default_max_tokens: form.default_max_tokens === '' ? null : Number(form.default_max_tokens),
    default_stop: [...new Set(form.default_stop)],
    input_price: form.input_price,
    output_price: form.output_price,
    price_unit: form.price_unit,
    currency: form.currency,
    enabled: form.enabled,
    version: isEdit.value ? (version.value ?? undefined) : undefined,
  }
}

async function onSubmit(): Promise<void> {
  if (formInvalid.value) return
  const command = buildCommand()
  let savedId = ''
  const outcome = await doSubmit(async () => {
    if (isEdit.value) {
      await updateProviderModel(modelId.value, command)
      savedId = modelId.value
    } else {
      const result = await createProviderModel(command)
      savedId = result.id
    }
  })
  if (outcome.ok) {
    dirty.value = false
    void router.push(`/ui/provider-models/${savedId}`)
  }
}

function addStop(): void {
  form.default_stop.push('')
}
function removeStop(index: number): void {
  form.default_stop.splice(index, 1)
}

async function reload(): Promise<void> {
  if (!isEdit.value) return
  resetSubmit()
  loading.value = true
  try {
    applyDetail(await fetchProviderModel(modelId.value))
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    providers.value = await fetchProviderOptions()
    if (isEdit.value) {
      await reload()
    } else {
      markClean()
      loading.value = false
    }
  } catch (e) {
    loadError.value = e
    loading.value = false
  }
})
</script>

<template>
  <section class="lai-page">
    <h1 class="lai-page-title">
      {{ isEdit ? '编辑模型' : '新建模型' }}
    </h1>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
      @retry="reload"
    />

    <form
      v-else
      class="lai-form"
      @submit.prevent="onSubmit"
      @input="onInput"
    >
      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          基础信息
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="Provider"
            required
            :error="form.provider_id === '' && loadError ? '请选择 Provider' : ''"
          >
            <select
              v-model="form.provider_id"
              class="lai-input lai-select"
              :disabled="isEdit"
            >
              <option
                value=""
                disabled
              >
                请选择 Provider
              </option>
              <option
                v-for="item in providers"
                :key="item.id"
                :value="item.id"
              >
                {{ item.name }}（{{ item.type }}）
              </option>
            </select>
          </FormField>
          <FormField
            label="展示名称"
            required
            :error="displayNameInvalid ? '长度为 2—64 字符' : ''"
          >
            <input
              v-model="form.display_name"
              class="lai-input"
              type="text"
              maxlength="64"
            >
          </FormField>
          <FormField
            label="模型标识"
            required
            :error="modelIdInvalid ? '长度为 1—128 字符，保持大小写' : ''"
          >
            <input
              v-model="form.model_id"
              class="lai-input"
              type="text"
              maxlength="128"
              spellcheck="false"
            >
          </FormField>
          <FormField label="模型类型">
            <input
              class="lai-input"
              type="text"
              value="CHAT_TEXT"
              disabled
            >
          </FormField>
        </div>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          能力
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="Tokenizer"
            :hint="'需为当前 Provider Adapter 声明的 TokenEstimator'"
            :error="form.enabled && form.tokenizer_family.trim() === '' ? '启用模型必须填写' : ''"
          >
            <input
              v-model="form.tokenizer_family"
              class="lai-input"
              type="text"
              spellcheck="false"
            >
          </FormField>
          <FormField
            label="上下文窗口"
            :error="contextInvalid ? '需为正整数' : outputInvalid ? '' : ''"
          >
            <input
              v-model="form.context_window"
              class="lai-input"
              type="text"
              inputmode="numeric"
              placeholder="启用模型必填"
            >
          </FormField>
          <FormField
            label="最大输出 Token"
            :error="outputInvalid ? '需为正整数且小于上下文窗口' : ''"
          >
            <input
              v-model="form.max_output_tokens"
              class="lai-input"
              type="text"
              inputmode="numeric"
              placeholder="启用模型必填"
            >
          </FormField>
        </div>
        <label class="lai-switch"><input
          v-model="form.support_stream"
          type="checkbox"
        > 支持流式</label>
        <label class="lai-switch"><input
          v-model="form.support_system_message"
          type="checkbox"
        > 支持 system 消息</label>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          参数能力
        </legend>
        <label class="lai-switch"><input
          v-model="form.support_temperature"
          type="checkbox"
        > 支持 temperature</label>
        <div
          v-if="form.support_temperature"
          class="lai-form-grid"
        >
          <FormField
            label="temperature 下限"
            required
          >
            <input
              v-model="form.temperature_min"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
          <FormField
            label="temperature 上限"
            required
            :error="temperatureRangeInvalid ? '上限不能小于下限' : ''"
          >
            <input
              v-model="form.temperature_max"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
        </div>
        <label class="lai-switch"><input
          v-model="form.support_top_p"
          type="checkbox"
        > 支持 top_p</label>
        <div
          v-if="form.support_top_p"
          class="lai-form-grid"
        >
          <FormField
            label="top_p 下限（0—1）"
            required
          >
            <input
              v-model="form.top_p_min"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
          <FormField
            label="top_p 上限（0—1）"
            required
            :error="topPRangeInvalid ? '需在 0—1 内且不小于下限' : ''"
          >
            <input
              v-model="form.top_p_max"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
        </div>
        <label class="lai-switch"><input
          v-model="form.support_stop"
          type="checkbox"
        > 支持 stop</label>
        <div
          v-if="form.support_stop"
          class="lai-form-grid"
        >
          <FormField
            label="stop 序列上限（1—4）"
            required
            :error="stopRangeInvalid ? '需为 1—4 或 1—128 内的正整数' : ''"
          >
            <input
              v-model="form.max_stop_sequences"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
          <FormField
            label="stop 单项长度上限（1—128）"
            required
          >
            <input
              v-model="form.max_stop_length"
              class="lai-input"
              type="text"
              inputmode="numeric"
            >
          </FormField>
        </div>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          默认生成参数
        </legend>
        <div class="lai-form-grid">
          <FormField
            v-if="form.support_temperature"
            label="默认 temperature"
            :error="defaultTemperatureInvalid ? '需在模型范围内' : ''"
          >
            <input
              v-model="form.default_temperature"
              class="lai-input"
              type="text"
              inputmode="decimal"
              placeholder="空为不设置"
            >
          </FormField>
          <FormField
            v-if="form.support_top_p"
            label="默认 top_p"
            :error="defaultTopPInvalid ? '需在模型范围内（0—1）' : ''"
          >
            <input
              v-model="form.default_top_p"
              class="lai-input"
              type="text"
              inputmode="decimal"
              placeholder="空为不设置"
            >
          </FormField>
          <FormField
            label="默认 max_tokens"
            :error="defaultMaxTokensInvalid ? '不能超过最大输出 Token' : ''"
          >
            <input
              v-model="form.default_max_tokens"
              class="lai-input"
              type="text"
              inputmode="numeric"
              placeholder="空为不设置"
            >
          </FormField>
        </div>
        <FormField
          v-if="form.support_stop"
          label="默认 stop 列表"
          :error="stopListInvalid ? '项数或长度超限，且不能重复' : ''"
        >
          <div class="lai-stop-list">
            <div
              v-for="(_, index) in form.default_stop"
              :key="index"
              class="lai-stop-row"
            >
              <input
                v-model="form.default_stop[index]"
                class="lai-input"
                type="text"
                maxlength="128"
              >
              <button
                type="button"
                class="lai-btn lai-btn-text"
                @click="removeStop(index)"
              >
                移除
              </button>
            </div>
            <button
              type="button"
              class="lai-btn"
              @click="addStop"
            >
              添加一项
            </button>
          </div>
        </FormField>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          价格
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="输入价格"
            required
            :error="priceInvalid ? '需为不小于 0 的金额（最多 8 位小数）' : ''"
          >
            <input
              v-model="form.input_price"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
          <FormField
            label="输出价格"
            required
          >
            <input
              v-model="form.output_price"
              class="lai-input"
              type="text"
              inputmode="decimal"
            >
          </FormField>
          <FormField label="价格单位">
            <select
              v-model="form.price_unit"
              class="lai-input lai-select"
            >
              <option :value="1000">
                每 1000 tokens
              </option>
              <option :value="1000000">
                每 1000000 tokens
              </option>
            </select>
          </FormField>
          <FormField
            label="币种"
            required
            :error="currencyInvalid ? 'ISO 4217 三位代码' : ''"
          >
            <input
              v-model="form.currency"
              class="lai-input"
              type="text"
              maxlength="3"
              spellcheck="false"
            >
          </FormField>
        </div>
      </fieldset>

      <div class="lai-form-footer">
        <label class="lai-switch">
          <input
            v-model="form.enabled"
            type="checkbox"
          >
          启用（发布后参与路由）
        </label>
        <p
          v-if="capabilityIncomplete"
          class="lai-form-message-error"
        >
          启用模型必须补齐 Tokenizer、上下文窗口和最大输出
        </p>
        <p
          v-if="conflictError"
          class="lai-form-message-error"
          role="alert"
        >
          配置已被其他管理员修改（最新版本 {{ conflictError?.serverVersion ?? '未知' }}）。您的输入已保留，
          <button
            type="button"
            class="lai-btn lai-btn-text"
            @click="reload"
          >
            加载最新后重填
          </button>
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
.lai-stop-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lai-stop-row {
  display: flex;
  gap: 8px;
}
.lai-form-footer {
  border-top: 1px solid #d8dee4;
  padding-top: 12px;
}
.lai-form-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
