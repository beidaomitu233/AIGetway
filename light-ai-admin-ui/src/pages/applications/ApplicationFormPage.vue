<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { Button, Card, Checkbox, CheckboxGroup, Input, Select } from 'ant-design-vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Permission } from '@/app/permissions'
import { ApiError, isAbortError } from '@/api/errors'
import { positiveAmount, positiveInteger, validPeriod, applicationEnvironmentLabels } from './applicationValues'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import PageHeader from '@/ui/page/PageHeader.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { useBootstrapStore } from '@/stores/bootstrap'
import {
  createApplication,
  fetchApplication,
  fetchApplicationModelOptionsForCreate,
  updateApplication,
  type ApplicationEnvironment,
  type ApplicationDetail,
  type ApplicationModelOption,
} from '@/api/applications'

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()
const applicationId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isEdit = computed(() => applicationId.value !== '')

const loading = ref(true)
const canManage = computed(() => store.can(Permission.applicationManage))
const editable = ref(true)
let controller: AbortController | null = null
let loadSequence = 0
const loadError = ref<unknown>(null)
/** FE-202/205：创建前授权候选取自 /applications/model-options，不能用配置视图 /virtual-models 替代。 */
const modelOptions = ref<ApplicationModelOption[]>([])
const modelsLoadError = ref<unknown>(null)
const version = ref<string | null>(null)
const latest = ref<ApplicationDetail | null>(null)
const latestLoading = ref(false)
const latestError = ref<unknown>(null)
const baseline = ref('')
const dirty = ref(false)
const validationMessage = ref('')

const form = reactive({
  code: '',
  name: '',
  department: '',
  owner_id: store.userId,
  owner_name: store.displayName,
  environment: 'PROD' as ApplicationEnvironment,
  description: '',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
  token_limited: false,
  token_limit: null as number | null,
  amount_limited: false,
  amount_limit: '',
  currency: '',
  rpm_limited: false,
  rpm: null as number | null,
  tpm_limited: false,
  tpm: null as number | null,
  period_type: 'MONTH' as 'LIFECYCLE' | 'DAY' | 'MONTH' | 'CUSTOM',
  period_start: '',
  period_end: '',
  virtual_model_ids: [] as string[],
})

function setTokenLimit(value: number | string | null): void { form.token_limit = value == null || value === '' ? null : Number(value) }
function setRpm(value: number | string | null): void { form.rpm = value == null || value === '' ? null : Number(value) }
function setTpm(value: number | string | null): void { form.tpm = value == null || value === '' ? null : Number(value) }
function setText(field: 'name' | 'code' | 'department' | 'owner_id' | 'owner_name' | 'description' | 'amount_limit' | 'currency' | 'period_start' | 'period_end', value: string): void {
  form[field] = value
  onInput()
}

const { submitting, fieldMessages, conflictError, errorText, submit, reset } = useFormSubmit()
const CODE_PATTERN = /^[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?$/

const codeError = computed(() => {
  if (isEdit.value) return ''
  if (!form.code) return '请输入应用编码'
  return CODE_PATTERN.test(form.code) ? '' : '使用 1—64 位小写字母、数字和连字符，首位必须是字母'
})
const customPeriodInvalid = computed(() => !validPeriod(form.period_type, form.period_start, form.period_end))
const formInvalid = computed(() => Boolean(
  !canManage.value || !editable.value || loading.value || loadError.value || codeError.value
  || (isEdit.value && !dirty.value)
  || form.name.trim().length < 2 || form.name.trim().length > 128
  || !form.owner_id.trim() || form.owner_id.length > 128
  || !form.owner_name.trim() || form.owner_name.length > 128
  || !Object.hasOwn(applicationEnvironmentLabels, form.environment)
  || form.department.length > 128 || form.description.length > 1000
  || (!isEdit.value && (
    !['ACTIVE', 'DISABLED'].includes(form.status)
    || (form.token_limited && !positiveInteger(form.token_limit))
    || (form.amount_limited && !positiveAmount(form.amount_limit))
    || (form.amount_limited && !/^[A-Za-z]{3}$/.test(form.currency.trim()))
    || (form.rpm_limited && !positiveInteger(form.rpm))
    || (form.tpm_limited && !positiveInteger(form.tpm))
    || customPeriodInvalid.value
    || form.virtual_model_ids.some(id => !modelOptions.value.some(option => option.virtual_model_id === id))
  ))
))
const unlimited = computed(() => !isEdit.value && (!form.token_limited || !form.amount_limited || !form.rpm_limited || !form.tpm_limited))
const saveDisabled = computed(() => submitting.value || !canManage.value || !editable.value || loading.value || (isEdit.value && (formInvalid.value || conflictError.value !== null)))

function snapshot(): string {
  return JSON.stringify(form)
}

function onInput(): void {
  dirty.value = snapshot() !== baseline.value
  validationMessage.value = ''
}

function markClean(): void {
  baseline.value = snapshot()
  dirty.value = false
}

function asOffsetDateTime(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

async function onSubmit(): Promise<void> {
  if (submitting.value) return
  if (formInvalid.value || conflictError.value) {
    validationMessage.value = '请先补全必填信息，并修正已启用限制项的格式。'
    return
  }
  validationMessage.value = ''
  const sequence = loadSequence
  let savedId = applicationId.value
  const outcome = await submit(async () => {
    if (isEdit.value) {
      const result = await updateApplication(applicationId.value, {
        name: form.name.trim(),
        department: form.department.trim() || null,
        owner_id: form.owner_id.trim(),
        owner_name: form.owner_name.trim(),
        environment: form.environment,
        description: form.description.trim() || null,
        version: version.value!,
      })
      savedId = result.id
      return
    }
    const result = await createApplication({
      code: form.code.trim(),
      name: form.name.trim(),
      department: form.department.trim() || null,
      owner_id: form.owner_id.trim(),
      owner_name: form.owner_name.trim(),
      environment: form.environment,
      description: form.description.trim() || null,
      status: form.status,
      token_limit: form.token_limited ? form.token_limit : null,
      amount_limit: form.amount_limited ? form.amount_limit.trim() : null,
      currency: form.currency.trim().toUpperCase(),
      rpm: form.rpm_limited ? form.rpm : null,
      tpm: form.tpm_limited ? form.tpm : null,
      period_type: form.period_type,
      period_start: form.period_type === 'CUSTOM' ? asOffsetDateTime(form.period_start) : null,
      period_end: form.period_type === 'CUSTOM' ? asOffsetDateTime(form.period_end) : null,
      virtual_model_ids: form.virtual_model_ids,
    })
    savedId = result.id
  })
  if (outcome.ok && sequence === loadSequence) {
    dirty.value = false
    void router.push(`/ui/applications/${savedId}`)
  }
}

useDirtyGuard(() => dirty.value || submitting.value)
onBeforeRouteUpdate(() => !dirty.value || window.confirm('有未保存的修改，切换应用将丢失。确认继续？'))

async function compareLatest(): Promise<void> {
  if (!isEdit.value || latestLoading.value) return
  const sequence = loadSequence
  latestLoading.value = true
  latestError.value = null
  try {
    const result = await fetchApplication(applicationId.value, controller?.signal)
    if (sequence === loadSequence) latest.value = result
  } catch (error) { if (sequence === loadSequence) latestError.value = error }
  finally { if (sequence === loadSequence) latestLoading.value = false }
}
function acceptVersion(): void {
  if (!latest.value) return
  version.value = latest.value.version
  editable.value = ['ACTIVE', 'DISABLED'].includes(latest.value.status)
  reset()
  latest.value = null
}
async function load(): Promise<void> {
  const sequence = ++loadSequence
  controller?.abort()
  controller = new AbortController()
  const signal = controller.signal
  loading.value = true
  loadError.value = null
  modelsLoadError.value = null
  latest.value = null
  latestLoading.value = false
  latestError.value = null
  reset()
  try {
    if (!canManage.value) throw new ApiError(403, { code: 'ACCESS_DENIED', type: 'permission', message: '无权编辑应用' }, 'local-permission')
    if (isEdit.value) {
      const detail = await fetchApplication(applicationId.value, signal)
      if (sequence !== loadSequence) return
      Object.assign(form, { code: detail.code, name: detail.name, department: detail.department ?? '', owner_id: detail.owner_id, owner_name: detail.owner_name, environment: detail.environment, description: detail.description ?? '' })
      editable.value = ['ACTIVE', 'DISABLED'].includes(detail.status)
      version.value = detail.version
    } else {
      modelOptions.value = []
      Object.assign(form, { code: '', name: '', department: '', owner_id: store.userId, owner_name: store.displayName, environment: 'PROD', description: '', status: 'ACTIVE', token_limited: false, token_limit: null, amount_limited: false, amount_limit: '', currency: '', rpm_limited: false, rpm: null, tpm_limited: false, tpm: null, period_type: 'MONTH', period_start: '', period_end: '', virtual_model_ids: [] })
      editable.value = true
      version.value = null
      // 候选属于局部数据：加载失败只影响模型选择，不阻断基本信息与额度的录入。
      const options = await fetchApplicationModelOptionsForCreate(signal).catch(error => {
        if (!isAbortError(error)) modelsLoadError.value = error
        return null
      })
      if (sequence !== loadSequence) return
      if (options) {
        modelOptions.value = options
        modelsLoadError.value = null
      }
    }
    markClean()
  } catch (error) {
    if (sequence === loadSequence && !isAbortError(error)) loadError.value = error
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}
watch(() => [applicationId.value, store.userId, canManage.value], () => { void load() }, { immediate: true })
onScopeDispose(() => { ++loadSequence; controller?.abort() })
</script>

<template>
  <section class="lai-page application-form-page">
    <PageHeader
      :title="isEdit ? '编辑应用' : '新建应用'"
      description="一个应用对应一个企业系统接入点，独立管理凭证、模型权限、额度和速率。"
    />

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
      @retry="load"
    />

    <Card v-else :bordered="false" class="application-form-surface">
      <form
        @submit.prevent="onSubmit"
        @input="onInput"
        @change="onInput"
      >
      <p
        v-if="!editable"
        role="alert"
      >
        当前应用状态不允许编辑。
      </p>
      <fieldset
        :disabled="submitting || !editable"
        class="application-fields"
      >
        <Card :bordered="false" class="lai-card form-section">
          <h2 class="lai-card-title">
            基本信息
          </h2>
          <div class="form-grid">
            <FormField
              label="应用名称"
              required
              :error="form.name.trim().length < 2 ? '至少输入 2 个字符' : fieldMessages.name"
            >
              <Input
                :value="form.name"
                name="name"
                :maxlength="128"
                placeholder="例如：智能客服生产环境"
                @update:value="(value: string) => setText('name', value)"
              />
            </FormField>
            <FormField
              label="应用编码"
              required
              :hint="isEdit ? '创建后不可修改' : '用于调用归属与应用范围标识，创建后不可修改'"
              :error="codeError || fieldMessages.code"
            >
              <Input
                :value="form.code"
                name="code"
                :maxlength="64"
                placeholder="customer-service-prod"
                :disabled="isEdit"
                @update:value="(value: string) => setText('code', value)"
              />
            </FormField>
            <FormField
              label="所属部门"
              :error="fieldMessages.department"
            >
              <Input
                :value="form.department"
                name="department"
                :maxlength="128"
                placeholder="例如：客户成功部"
                @update:value="(value: string) => setText('department', value)"
              />
            </FormField>
            <FormField
              label="运行环境"
              required
              :error="fieldMessages.environment"
            >
              <Select
                v-model:value="form.environment"
                class="full-control"
                :options="[
                  { value: 'DEV', label: '开发' },
                  { value: 'TEST', label: '测试' },
                  { value: 'STAGING', label: '预发布' },
                  { value: 'PROD', label: '生产' },
                ]"
              />
            </FormField>
            <FormField
              label="负责人账号"
              required
              :error="fieldMessages.owner_id"
            >
              <Input
                :value="form.owner_id"
                name="owner_id"
                :maxlength="128"
                @update:value="(value: string) => setText('owner_id', value)"
              />
            </FormField>
            <FormField
              label="负责人名称"
              required
              :error="fieldMessages.owner_name"
            >
              <Input
                :value="form.owner_name"
                name="owner_name"
                :maxlength="128"
                @update:value="(value: string) => setText('owner_name', value)"
              />
            </FormField>
            <FormField
              class="wide-field"
              label="应用说明"
              :error="fieldMessages.description"
            >
              <Input.TextArea
                :value="form.description"
                name="description"
                :rows="3"
                :maxlength="1000"
                placeholder="说明接入系统、业务场景和维护边界"
                @update:value="(value: string) => setText('description', value)"
              />
            </FormField>
            <FormField
              v-if="!isEdit"
              label="初始状态"
              required
            >
              <Select
                v-model:value="form.status"
                class="full-control"
                :options="[
                  { value: 'ACTIVE', label: '启用' },
                  { value: 'DISABLED', label: '停用' },
                ]"
              />
            </FormField>
          </div>
        </Card>

        <Card
          v-if="!isEdit"
          :bordered="false"
          class="lai-card form-section"
        >
          <h2 class="lai-card-title">
            初始额度与限流
          </h2>
          <p class="section-help">
            额度与并发计数以应用为归属；关闭某项限制表示该项不限额。
          </p>
          <div class="form-grid">
            <FormField
              label="Token 额度"
              :error="form.token_limited && !positiveInteger(form.token_limit) ? '请输入正整数' : fieldMessages.token_limit"
            >
              <div class="limit-control">
                <input
                  v-model="form.token_limited"
                  name="token_limited"
                  type="checkbox"
                  class="lai-visually-hidden"
                  aria-hidden="true"
                  tabindex="-1"
                  @change="onInput"
                >
                <Checkbox v-model:checked="form.token_limited" @change="onInput">限制</Checkbox>
                <Input
                  :value="form.token_limit == null ? '' : String(form.token_limit)"
                  type="number"
                  name="token_limit"
                  :min="1"
                  :disabled="!form.token_limited"
                  @update:value="(value: string) => { setTokenLimit(value); onInput() }"
                />
              </div>
            </FormField>
            <FormField
              label="金额预算"
              :error="form.amount_limited && !positiveAmount(form.amount_limit) ? '请输入正金额' : fieldMessages.amount_limit"
            >
              <div class="amount-control">
                <input
                  v-model="form.amount_limited"
                  name="amount_limited"
                  type="checkbox"
                  class="lai-visually-hidden"
                  aria-hidden="true"
                  tabindex="-1"
                  @change="onInput"
                >
                <Checkbox v-model:checked="form.amount_limited" @change="onInput">限制</Checkbox>
                <Input
                  :value="form.amount_limit"
                  name="amount_limit"
                  inputmode="decimal"
                  :disabled="!form.amount_limited"
                  @update:value="(value: string) => setText('amount_limit', value)"
                />
                <Input
                  :value="form.currency"
                  name="currency"
                  :maxlength="3"
                  aria-label="币种"
                  @update:value="(value: string) => setText('currency', value)"
                />
              </div>
            </FormField>
            <FormField
              label="RPM"
              hint="每分钟最大请求数"
              :error="form.rpm_limited && !positiveInteger(form.rpm) ? '请输入正整数' : fieldMessages.rpm"
            >
              <div class="limit-control">
                <input
                  v-model="form.rpm_limited"
                  name="rpm_limited"
                  type="checkbox"
                  class="lai-visually-hidden"
                  aria-hidden="true"
                  tabindex="-1"
                  @change="onInput"
                >
                <Checkbox v-model:checked="form.rpm_limited" @change="onInput">限制</Checkbox>
                <Input
                  :value="form.rpm == null ? '' : String(form.rpm)"
                  type="number"
                  name="rpm"
                  :min="1"
                  :disabled="!form.rpm_limited"
                  @update:value="(value: string) => { setRpm(value); onInput() }"
                />
              </div>
            </FormField>
            <FormField
              label="TPM"
              hint="每分钟最大 Token 数"
              :error="form.tpm_limited && !positiveInteger(form.tpm) ? '请输入正整数' : fieldMessages.tpm"
            >
              <div class="limit-control">
                <input
                  v-model="form.tpm_limited"
                  name="tpm_limited"
                  type="checkbox"
                  class="lai-visually-hidden"
                  aria-hidden="true"
                  tabindex="-1"
                  @change="onInput"
                >
                <Checkbox v-model:checked="form.tpm_limited" @change="onInput">限制</Checkbox>
                <Input
                  :value="form.tpm == null ? '' : String(form.tpm)"
                  type="number"
                  name="tpm"
                  :min="1"
                  :disabled="!form.tpm_limited"
                  @update:value="(value: string) => { setTpm(value); onInput() }"
                />
              </div>
            </FormField>
            <FormField
              label="额度周期"
              required
              :error="fieldMessages.period_type"
            >
              <Select
                v-model:value="form.period_type"
                class="full-control"
                :options="[
                  { value: 'LIFECYCLE', label: '应用生命周期' },
                  { value: 'DAY', label: '每日' },
                  { value: 'MONTH', label: '每月' },
                  { value: 'CUSTOM', label: '自定义' },
                ]"
              />
            </FormField>
            <div
              v-if="form.period_type === 'CUSTOM'"
              class="custom-period"
            >
              <FormField
                label="开始时间"
                required
              >
                <Input
                  :value="form.period_start"
                  type="datetime-local"
                  @update:value="(value: string) => setText('period_start', value)"
                />
              </FormField>
              <FormField
                label="结束时间"
                required
                :error="customPeriodInvalid ? '结束时间必须晚于开始时间' : fieldMessages.period_end"
              >
                <Input
                  :value="form.period_end"
                  type="datetime-local"
                  @update:value="(value: string) => setText('period_end', value)"
                />
              </FormField>
            </div>
          </div>
        </Card>

        <Card
          v-if="!isEdit"
          :bordered="false"
          class="lai-card form-section"
        >
          <h2 class="lai-card-title">
            可用虚拟模型
          </h2>
          <p class="section-help">
            应用只能调用已授权模型；候选来自活动配置快照中已发布且存在可用路由候选的虚拟模型，模型后续可在应用详情中单独管理。
          </p>
          <PageState
            v-if="modelsLoadError"
            status="error"
            :error="modelsLoadError"
            message="活动配置快照当前无法读取，请稍后重试。"
            @retry="load"
          />
          <CheckboxGroup
            v-else-if="modelOptions.length"
            v-model:value="form.virtual_model_ids"
            class="model-options"
          >
            <Checkbox
              v-for="option in modelOptions"
              :key="option.virtual_model_id"
              :value="option.virtual_model_id"
              class="model-option"
            >
              <span><strong>{{ option.code }}</strong><small>{{ option.max_output_tokens === null ? '未声明输出上限' : `候选上限 ${option.max_output_tokens}` }} · {{ option.allow_stream === null ? '流式能力未知' : option.allow_stream ? '支持流式' : '不支持流式' }}</small></span>
            </Checkbox>
          </CheckboxGroup>
          <p
            v-else
            class="empty-inline"
          >
            当前没有可授权的虚拟模型：活动快照中缺少已发布且存在可用路由候选的模型，可先创建应用，发布模型后再授权。
          </p>
        </Card>
      </fieldset>
      <p
        v-if="unlimited"
        class="lai-form-message-error"
        role="alert"
      >
        已选择无限制：相应维度不会主动阻止超量调用，请确认企业预算与运行风险。
      </p>
      <p
        v-if="conflictError"
        class="lai-form-message-error"
        role="alert"
      >
        应用已被其他管理员修改，您的输入已保留。请读取最新版本并对比后再保存。
      </p>
      <div
        v-if="conflictError && isEdit"
        class="lai-card"
      >
        <Button
          html-type="button"
          class="lai-btn"
          :disabled="latestLoading"
          @click="compareLatest"
        >
          读取最新版本并对比
        </Button>
        <PageState
          v-if="latestError"
          status="error"
          :error="latestError"
          @retry="compareLatest"
        />
        <div
          v-if="latest"
          class="lai-table-wrap"
        >
          <table
            class="lai-table"
            aria-label="版本差异"
          >
            <thead><tr><th>字段</th><th>当前输入</th><th>服务器最新值</th></tr></thead>
            <tbody>
              <tr
                v-for="field in (['name', 'department', 'owner_id', 'owner_name', 'environment', 'description'] as const)"
                :key="field"
              >
                <th>{{ field }}</th><td>{{ form[field] }}</td><td>{{ latest[field] }}</td>
              </tr>
            </tbody>
          </table>
          <Button
            html-type="button"
            class="lai-btn"
            @click="acceptVersion"
          >
            已核对，保留输入并使用最新版本
          </Button>
        </div>
      </div>
      <p
        v-if="!conflictError && errorText"
        class="lai-form-message-error"
        role="alert"
      >
        {{ errorText }}
      </p>
      <p v-if="validationMessage" class="lai-form-message-error" role="alert">
        {{ validationMessage }}
      </p>
      <div class="form-actions">
        <Button
          html-type="button"
          class="lai-btn"
          :disabled="submitting"
          @click="router.back()"
        >
          取消
        </Button>
        <Button
          html-type="submit"
          data-test="save-application"
          class="lai-btn lai-btn-primary"
          :disabled="saveDisabled"
        >
          {{ submitting ? '保存中…' : '保存应用' }}
        </Button>
      </div>
      </form>
    </Card>
  </section>
</template>

<style scoped>
.application-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
.application-form-page { max-width: 1040px; }
.form-heading p, .section-help { color: #667085; font-size: 14px; }
.application-form-surface { border: 1px solid var(--lai-color-border); border-radius: var(--lai-radius-card); box-shadow: var(--lai-shadow-card); }
.form-section { margin-bottom: 16px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 28px; }
.wide-field { grid-column: 1 / -1; }
.full-control, .lai-input { width: 100%; max-width: none; }
.lai-textarea { height: auto; padding: 8px 10px; resize: vertical; }
.lai-mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.limit-control, .amount-control { display: grid; grid-template-columns: 64px 1fr; align-items: center; gap: 8px; }
.amount-control { grid-template-columns: 64px 1fr 68px; }
.limit-control label, .amount-control label { white-space: nowrap; color: #475467; font-size: 13px; }
.currency { text-transform: uppercase; }
.custom-period { grid-column: 1 / -1; display: grid; grid-template-columns: 1fr 1fr; gap: 28px; }
.model-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.model-option { display: flex; align-items: flex-start; gap: 10px; padding: 12px; border: 1px solid #e6eaf0; border-radius: 6px; background: #fff; }
.model-option span { display: flex; flex-direction: column; gap: 3px; }
.model-option small, .empty-inline { color: #667085; }
.form-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 8px 0 28px; }
@media (max-width: 800px) { .form-grid, .model-options, .custom-period { grid-template-columns: 1fr; } .wide-field, .custom-period { grid-column: auto; } }
</style>
