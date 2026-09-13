<script setup lang="ts">
import { Button, Checkbox, Input, Select } from 'ant-design-vue'
// 上游模型 新建/编辑表单（FE-015，附录 4.2.6.1）。
// 能力开关关闭时隐藏对应范围与默认值；价格保持字符串精度；启用要求能力字段完整。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { fetchProviderModel, createProviderModel, updateProviderModel, fetchProviderOptions } from '@/api/providerModels'
import type { ProviderModelCommand, ProviderModelDetail, ProviderOption } from '@/api/providerModels'

const store = useBootstrapStore()
const canManage = computed(() => store.can(Permission.modelManage))
const route = useRoute()
const router = useRouter()
const modelId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isEdit = computed(() => modelId.value !== '')

const loading = ref(true)
const loadError = ref<unknown>(null)
const providers = ref<ProviderOption[]>([])

const triStateOptions = [
  { value: 'null', label: '待确认' },
  { value: 'true', label: '支持' },
  { value: 'false', label: '不支持' },
]

function triState(value: boolean | null): string {
  return value == null ? 'null' : String(value)
}

function setTriState(key: 'support_stream' | 'support_system_message' | 'support_temperature' | 'support_top_p' | 'support_stop', v: unknown): void {
  const state = Array.isArray(v) ? String(v[0] ?? '') : String(v ?? '')
  form[key] = state === 'null' ? null : state === 'true'
}

const channelOptions = computed(() =>
  providers.value.map((item) => ({ value: item.id, label: `${item.name}（${item.provider_type}）` })),
)

const form = reactive({
  channel_id: '',
  display_name: '',
  model_id: '',
  tokenizer_family: '',
  context_window: '',
  max_output_tokens: '',
  support_stream: null as boolean | null,
  support_system_message: null as boolean | null,
  support_temperature: null as boolean | null,
  temperature_min: '',
  temperature_max: '',
  support_top_p: null as boolean | null,
  top_p_min: '',
  top_p_max: '',
  support_stop: null as boolean | null,
  max_stop_sequences: '',
  max_stop_length: '',
  default_temperature: '',
  default_top_p: '',
  default_max_tokens: '',
  default_stop: [] as string[],
  input_price: '',
  output_price: '',
  price_unit: 1000000,
  currency: 'USD',
  enabled: true,
})
const version = ref<number | null>(null)
const baseline = ref('')

const { submitting, conflictError, errorText, submit: doSubmit, reset: resetSubmit } = useFormSubmit()

function positiveInt(value: string): boolean {
  return /^\d+$/.test(value) && Number.isSafeInteger(Number(value)) && Number(value) >= 1
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
  return !Number.isFinite(Number(form.temperature_min)) || !Number.isFinite(Number(form.temperature_max)) || Number(form.temperature_max) < Number(form.temperature_min)
})
const topPRangeInvalid = computed(() => {
  if (!form.support_top_p) return false
  if (form.top_p_min === '' || form.top_p_max === '') return true
  const min = Number(form.top_p_min)
  const max = Number(form.top_p_max)
  return !Number.isFinite(min) || !Number.isFinite(max) || min < 0 || max < 0 || min > 1 || max > 1 || max < min
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
    return !Number.isFinite(value) || value < Number(form.temperature_min) || value > Number(form.temperature_max)
  }
  return !/^-?\d+(\.\d{1,4})?$/.test(form.default_temperature)
})
const defaultTopPInvalid = computed(() => {
  if (form.default_top_p === '') return false
  const value = Number(form.default_top_p)
  if (!Number.isFinite(value) || value < 0 || value > 1) return true
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
  const pattern = /^\d{1,12}(\.\d{1,8})?$/
  return [form.input_price, form.output_price].some((price) => price === '' ? form.enabled : !pattern.test(price))
})
const currencyInvalid = computed(() => !/^[A-Z]{3}$/.test(form.currency))

const capabilityIncomplete = computed(
  () =>
    form.enabled &&
    (form.tokenizer_family.trim() === '' ||
      form.context_window === '' ||
      form.max_output_tokens === '' ||
      [form.support_stream, form.support_system_message, form.support_temperature, form.support_top_p, form.support_stop].some((value) => value === null)),
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
    ![1000, 1000000].includes(form.price_unit) ||
    currencyInvalid.value ||
    capabilityIncomplete.value ||
    form.channel_id === '',
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
  form.channel_id = detail.channel_id
  form.display_name = detail.display_name
  form.model_id = detail.model_id
  form.tokenizer_family = detail.tokenizer_family ?? ''
  form.context_window = detail.context_window?.toString() ?? ''
  form.max_output_tokens = detail.max_output_tokens?.toString() ?? ''
  form.support_stream = detail.support_stream ?? null
  form.support_system_message = detail.support_system_message ?? null
  form.support_temperature = detail.support_temperature ?? null
  form.temperature_min = detail.temperature_min ?? ''
  form.temperature_max = detail.temperature_max ?? ''
  form.support_top_p = detail.support_top_p ?? null
  form.top_p_min = detail.top_p_min ?? ''
  form.top_p_max = detail.top_p_max ?? ''
  form.support_stop = detail.support_stop ?? null
  form.max_stop_sequences = detail.max_stop_sequences?.toString() ?? ''
  form.max_stop_length = detail.max_stop_length?.toString() ?? ''
  form.default_temperature = detail.default_temperature ?? ''
  form.default_top_p = detail.default_top_p ?? ''
  form.default_max_tokens = detail.default_max_tokens?.toString() ?? ''
  form.default_stop = [...detail.default_stop]
  form.input_price = detail.input_price ?? ''
  form.output_price = detail.output_price ?? ''
  form.price_unit = detail.price_unit
  form.currency = detail.currency
  form.enabled = detail.enabled
  version.value = detail.version
  markClean()
}

function buildCommand(): ProviderModelCommand {
  return {
    channel_id: form.channel_id,
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
    input_price: form.input_price || null,
    output_price: form.output_price || null,
    price_unit: form.price_unit,
    currency: form.currency,
    enabled: form.enabled,
    version: isEdit.value ? (version.value ?? undefined) : undefined,
  }
}

async function onSubmit(): Promise<void> {
  if (!canManage.value || submitting.value || conflictError.value || formInvalid.value) return
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
    void router.push(`/ui/models/upstream/${savedId}`)
  }
}

function addStop(): void {
  form.default_stop.push('')
}
function removeStop(index: number): void {
  form.default_stop.splice(index, 1)
}

async function reload(): Promise<void> {
  resetSubmit()
  loading.value = true
  loadError.value = null
  try {
    providers.value = await fetchProviderOptions()
    if (isEdit.value) applyDetail(await fetchProviderModel(modelId.value))
    else markClean()
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}
onMounted(reload)
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
            label="渠道"
            required
            :error="form.channel_id === '' && loadError ? '请选择 渠道' : ''"
          >
            <Select
              v-model:value="form.channel_id"
              :disabled="isEdit"
              placeholder="请选择 渠道"
              :options="channelOptions"
            />
          </FormField>
          <FormField
            label="展示名称"
            required
            :error="displayNameInvalid ? '长度为 2—64 字符' : ''"
          >
            <Input v-model:value="form.display_name" type="text" :maxlength="64" />
          </FormField>
          <FormField
            label="模型标识"
            required
            :error="modelIdInvalid ? '长度为 1—128 字符，保持大小写' : ''"
          >
            <Input v-model:value="form.model_id" type="text" :maxlength="128" spellcheck="false" />
          </FormField>
          <FormField label="模型类型">
            <Input value="CHAT_TEXT" disabled />
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
            :hint="'需为当前 渠道 Adapter 声明的 TokenEstimator'"
            :error="form.enabled && form.tokenizer_family.trim() === '' ? '启用模型必须填写' : ''"
          >
            <Input v-model:value="form.tokenizer_family" type="text" spellcheck="false" />
          </FormField>
          <FormField
            label="上下文窗口"
            :error="contextInvalid ? '需为正整数' : outputInvalid ? '' : ''"
          >
            <Input v-model:value="form.context_window" type="text" inputmode="numeric" placeholder="启用模型必填" />
          </FormField>
          <FormField
            label="最大输出 Token"
            :error="outputInvalid ? '需为正整数且小于上下文窗口' : ''"
          >
            <Input v-model:value="form.max_output_tokens" type="text" inputmode="numeric" placeholder="启用模型必填" />
          </FormField>
        </div>
        <label class="lai-switch">支持流式
          <Select
              :value="triState(form.support_stream)"
              aria-label="stream"
              :options="triStateOptions"
              @update:value="(v) => setTriState('support_stream', v)"
            />
        </label>
        <label class="lai-switch">支持 system 消息
          <Select
              :value="triState(form.support_system_message)"
              aria-label="system_message"
              :options="triStateOptions"
              @update:value="(v) => setTriState('support_system_message', v)"
            />
        </label>
      </fieldset>

      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          参数能力
        </legend>
        <label class="lai-switch">支持 temperature
          <Select
              :value="triState(form.support_temperature)"
              aria-label="temperature"
              :options="triStateOptions"
              @update:value="(v) => setTriState('support_temperature', v)"
            />
        </label>
        <div
          v-if="form.support_temperature"
          class="lai-form-grid"
        >
          <FormField
            label="temperature 下限"
            required
          >
            <Input v-model:value="form.temperature_min" type="text" inputmode="decimal" />
          </FormField>
          <FormField
            label="temperature 上限"
            required
            :error="temperatureRangeInvalid ? '上限不能小于下限' : ''"
          >
            <Input v-model:value="form.temperature_max" type="text" inputmode="decimal" />
          </FormField>
        </div>
        <label class="lai-switch">支持 top_p
          <Select
              :value="triState(form.support_top_p)"
              aria-label="top_p"
              :options="triStateOptions"
              @update:value="(v) => setTriState('support_top_p', v)"
            />
        </label>
        <div
          v-if="form.support_top_p"
          class="lai-form-grid"
        >
          <FormField
            label="top_p 下限（0—1）"
            required
          >
            <Input v-model:value="form.top_p_min" type="text" inputmode="decimal" />
          </FormField>
          <FormField
            label="top_p 上限（0—1）"
            required
            :error="topPRangeInvalid ? '需在 0—1 内且不小于下限' : ''"
          >
            <Input v-model:value="form.top_p_max" type="text" inputmode="decimal" />
          </FormField>
        </div>
        <label class="lai-switch">支持 stop
          <Select
              :value="triState(form.support_stop)"
              aria-label="stop"
              :options="triStateOptions"
              @update:value="(v) => setTriState('support_stop', v)"
            />
        </label>
        <div
          v-if="form.support_stop"
          class="lai-form-grid"
        >
          <FormField
            label="stop 序列上限（1—4）"
            required
            :error="stopRangeInvalid ? '需为 1—4 或 1—128 内的正整数' : ''"
          >
            <Input v-model:value="form.max_stop_sequences" type="text" inputmode="numeric" />
          </FormField>
          <FormField
            label="stop 单项长度上限（1—128）"
            required
          >
            <Input v-model:value="form.max_stop_length" type="text" inputmode="numeric" />
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
            <Input v-model:value="form.default_temperature" type="text" inputmode="decimal" placeholder="空为不设置" />
          </FormField>
          <FormField
            v-if="form.support_top_p"
            label="默认 top_p"
            :error="defaultTopPInvalid ? '需在模型范围内（0—1）' : ''"
          >
            <Input v-model:value="form.default_top_p" type="text" inputmode="decimal" placeholder="空为不设置" />
          </FormField>
          <FormField
            label="默认 max_tokens"
            :error="defaultMaxTokensInvalid ? '不能超过最大输出 Token' : ''"
          >
            <Input v-model:value="form.default_max_tokens" type="text" inputmode="numeric" placeholder="空为不设置" />
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
              <Input v-model:value="form.default_stop[index]" type="text" :maxlength="128" />
              <Button
                type="link"
                @click="removeStop(index)"
              >
                移除
              </Button>
            </div>
            <Button
              @click="addStop"
            >
              添加一项
            </Button>
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
            <Input v-model:value="form.input_price" type="text" inputmode="decimal" />
          </FormField>
          <FormField
            label="输出价格"
            required
          >
            <Input v-model:value="form.output_price" type="text" inputmode="decimal" />
          </FormField>
          <FormField label="价格单位">
            <Select v-model:value="form.price_unit" :options="[{ value: '1000', label: '每 1000 tokens', disabled: false }, { value: '1000000', label: '每 1000000 tokens', disabled: false }]" />
          </FormField>
          <FormField
            label="币种"
            required
            :error="currencyInvalid ? 'ISO 4217 三位代码' : ''"
          >
            <Input v-model:value="form.currency" type="text" :maxlength="3" spellcheck="false" />
          </FormField>
        </div>
      </fieldset>

      <div class="lai-form-footer">
        <Checkbox v-model:checked="form.enabled">启用（发布后参与路由）</Checkbox>
        <p
          v-if="capabilityIncomplete"
          class="lai-form-message-error"
        >
          启用模型必须补齐 Tokenizer、上下文窗口和最大输出，并明确声明各项能力及价格
        </p>
        <p
          v-if="conflictError"
          class="lai-form-message-error"
          role="alert"
        >
          配置已被其他管理员修改（最新版本 {{ conflictError?.serverVersion ?? '未知' }}）。您的输入已保留，
          <Button
            type="link"
            @click="reload"
          >
            加载最新后重填
          </Button>
        </p>
        <p
          v-else-if="errorText"
          class="lai-form-message-error"
          role="alert"
        >
          {{ errorText }}
        </p>
        <div class="lai-form-actions">
          <Button
            :disabled="submitting"
            @click="router.back()"
          >
            取消
          </Button>
          <Button
            v-if="canManage"
            type="primary"
            :disabled="submitting || formInvalid"
            html-type="submit"
          >
            {{ submitting ? '保存中…' : '保存' }}
          </Button>
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
  min-inline-size: 0;
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
  grid-template-columns: repeat(auto-fill, minmax(min(240px, 100%), 1fr));
  gap: 4px 16px;
  margin-bottom: 8px;
}
.lai-select {
  width: 100%;
  padding: 6px 8px;
}
.lai-switch {
  display: inline-flex;
  flex-wrap: wrap;
  max-width: 100%;
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
