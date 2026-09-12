<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Permission } from '@/app/permissions'
import { ApiError, isAbortError } from '@/api/errors'
import { positiveAmount, positiveInteger, validPeriod, applicationEnvironmentLabels } from './applicationValues'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { useBootstrapStore } from '@/stores/bootstrap'
import {
  createApplication,
  fetchApplication,
  updateApplication,
  type ApplicationEnvironment,
  type ApplicationDetail,
} from '@/api/applications'
import { fetchModelAliases, type ModelAliasListItem } from '@/api/modelAliases'

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()
const applicationId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isEdit = computed(() => applicationId.value !== '')

const loading = ref(true)
const reviewing = ref(false)
const canManage = computed(() => store.can(Permission.applicationManage))
const editable = ref(true)
let controller: AbortController | null = null
let loadSequence = 0
const loadError = ref<unknown>(null)
const aliases = ref<ModelAliasListItem[]>([])
const version = ref<string | null>(null)
const latest = ref<ApplicationDetail | null>(null)
const latestLoading = ref(false)
const latestError = ref<unknown>(null)
const baseline = ref('')
const dirty = ref(false)

const form = reactive({
  code: '',
  name: '',
  department: '',
  owner_id: store.userId,
  owner_name: store.displayName,
  environment: 'PROD' as ApplicationEnvironment,
  description: '',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
  token_limited: true,
  token_limit: null as number | null,
  amount_limited: true,
  amount_limit: '',
  currency: '',
  rpm_limited: true,
  rpm: null as number | null,
  tpm_limited: true,
  tpm: null as number | null,
  period_type: 'MONTH' as 'LIFECYCLE' | 'DAY' | 'MONTH' | 'CUSTOM',
  period_start: '',
  period_end: '',
  virtual_model_ids: [] as string[],
})

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
    || !/^[A-Za-z]{3}$/.test(form.currency.trim())
    || (form.rpm_limited && !positiveInteger(form.rpm))
    || (form.tpm_limited && !positiveInteger(form.tpm))
    || customPeriodInvalid.value
    || form.virtual_model_ids.some(id => !aliases.value.some(model => model.id === id && model.enabled))
  ))
))
const unlimited = computed(() => !isEdit.value && (!form.token_limited || !form.amount_limited || !form.rpm_limited || !form.tpm_limited))

function snapshot(): string {
  return JSON.stringify(form)
}

function onInput(): void {
  dirty.value = snapshot() !== baseline.value
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
  if (formInvalid.value || conflictError.value || submitting.value) return
  if (!reviewing.value) { reviewing.value = true; return }
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
  reviewing.value = false
  latest.value = null
}
async function load(): Promise<void> {
  const sequence = ++loadSequence
  controller?.abort()
  controller = new AbortController()
  const signal = controller.signal
  loading.value = true
  loadError.value = null
  reviewing.value = false
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
      Object.assign(form, { code: '', name: '', department: '', owner_id: store.userId, owner_name: store.displayName, environment: 'PROD', description: '', status: 'ACTIVE', token_limited: true, token_limit: null, amount_limited: true, amount_limit: '', currency: '', rpm_limited: true, rpm: null, tpm_limited: true, tpm: null, period_type: 'MONTH', period_start: '', period_end: '', virtual_model_ids: [] })
      editable.value = true
      version.value = null
      const page = await fetchModelAliases({ enabled: true, page: 1, page_size: 100, sort: 'alias' }, signal)
      if (sequence !== loadSequence) return
      aliases.value = page.items
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
    <div class="form-heading">
      <div>
        <h1 class="lai-page-title">
          {{ isEdit ? '编辑应用' : '新建应用' }}
        </h1>
        <p>一个应用对应一个企业系统接入点，独立管理凭证、模型权限、额度和速率。</p>
      </div>
    </div>

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

    <form
      v-else
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
        :disabled="submitting || reviewing || !editable"
        class="application-fields"
      >
        <div class="lai-card form-section">
          <h2 class="lai-card-title">
            基本信息
          </h2>
          <div class="form-grid">
            <FormField
              label="应用名称"
              required
              :error="form.name.trim().length < 2 ? '至少输入 2 个字符' : fieldMessages.name"
            >
              <input
                v-model="form.name"
                name="name"
                class="lai-input"
                maxlength="128"
                placeholder="例如：智能客服生产环境"
              >
            </FormField>
            <FormField
              label="应用编码"
              required
              :hint="isEdit ? '创建后不可修改' : '用于调用归属与应用范围标识，创建后不可修改'"
              :error="codeError || fieldMessages.code"
            >
              <input
                v-model="form.code"
                name="code"
                class="lai-input lai-mono"
                maxlength="64"
                placeholder="customer-service-prod"
                :disabled="isEdit"
              >
            </FormField>
            <FormField
              label="所属部门"
              :error="fieldMessages.department"
            >
              <input
                v-model="form.department"
                name="department"
                class="lai-input"
                maxlength="128"
                placeholder="例如：客户成功部"
              >
            </FormField>
            <FormField
              label="运行环境"
              required
              :error="fieldMessages.environment"
            >
              <select
                v-model="form.environment"
                name="environment"
                class="lai-select full-control"
              >
                <option value="DEV">
                  开发
                </option><option value="TEST">
                  测试
                </option>
                <option value="STAGING">
                  预发布
                </option><option value="PROD">
                  生产
                </option>
              </select>
            </FormField>
            <FormField
              label="负责人账号"
              required
              :error="fieldMessages.owner_id"
            >
              <input
                v-model="form.owner_id"
                name="owner_id"
                class="lai-input"
                maxlength="128"
              >
            </FormField>
            <FormField
              label="负责人名称"
              required
              :error="fieldMessages.owner_name"
            >
              <input
                v-model="form.owner_name"
                name="owner_name"
                class="lai-input"
                maxlength="128"
              >
            </FormField>
            <FormField
              class="wide-field"
              label="应用说明"
              :error="fieldMessages.description"
            >
              <textarea
                v-model="form.description"
                name="description"
                class="lai-input lai-textarea"
                rows="3"
                maxlength="1000"
                placeholder="说明接入系统、业务场景和维护边界"
              />
            </FormField>
            <FormField
              v-if="!isEdit"
              label="初始状态"
              required
            >
              <select
                v-model="form.status"
                name="status"
                class="lai-select full-control"
              >
                <option value="ACTIVE">
                  启用
                </option><option value="DISABLED">
                  停用
                </option>
              </select>
            </FormField>
          </div>
        </div>

        <div
          v-if="!isEdit"
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
              :error="fieldMessages.token_limit"
            >
              <div class="limit-control">
                <label><input
                  v-model="form.token_limited"
                  type="checkbox"
                > 限制</label><input
                  v-model.number="form.token_limit"
                  name="token_limit"
                  class="lai-input"
                  type="number"
                  min="1"
                  :disabled="!form.token_limited"
                >
              </div>
            </FormField>
            <FormField
              label="金额预算"
              :error="fieldMessages.amount_limit"
            >
              <div class="amount-control">
                <label><input
                  v-model="form.amount_limited"
                  type="checkbox"
                > 限制</label><input
                  v-model="form.amount_limit"
                  name="amount_limit"
                  class="lai-input"
                  inputmode="decimal"
                  :disabled="!form.amount_limited"
                ><input
                  v-model="form.currency"
                  name="currency"
                  class="lai-input currency"
                  maxlength="3"
                  aria-label="币种"
                >
              </div>
            </FormField>
            <FormField
              label="RPM"
              hint="每分钟最大请求数"
              :error="fieldMessages.rpm"
            >
              <div class="limit-control">
                <label><input
                  v-model="form.rpm_limited"
                  type="checkbox"
                > 限制</label><input
                  v-model.number="form.rpm"
                  name="rpm"
                  class="lai-input"
                  type="number"
                  min="1"
                  :disabled="!form.rpm_limited"
                >
              </div>
            </FormField>
            <FormField
              label="TPM"
              hint="每分钟最大 Token 数"
              :error="fieldMessages.tpm"
            >
              <div class="limit-control">
                <label><input
                  v-model="form.tpm_limited"
                  type="checkbox"
                > 限制</label><input
                  v-model.number="form.tpm"
                  name="tpm"
                  class="lai-input"
                  type="number"
                  min="1"
                  :disabled="!form.tpm_limited"
                >
              </div>
            </FormField>
            <FormField
              label="额度周期"
              required
              :error="fieldMessages.period_type"
            >
              <select
                v-model="form.period_type"
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
            <div
              v-if="form.period_type === 'CUSTOM'"
              class="custom-period"
            >
              <FormField
                label="开始时间"
                required
              >
                <input
                  v-model="form.period_start"
                  class="lai-input"
                  type="datetime-local"
                >
              </FormField>
              <FormField
                label="结束时间"
                required
                :error="customPeriodInvalid ? '结束时间必须晚于开始时间' : fieldMessages.period_end"
              >
                <input
                  v-model="form.period_end"
                  class="lai-input"
                  type="datetime-local"
                >
              </FormField>
            </div>
          </div>
        </div>

        <div
          v-if="!isEdit"
          class="lai-card form-section"
        >
          <h2 class="lai-card-title">
            可用虚拟模型
          </h2>
          <p class="section-help">
            应用只能调用已授权模型；模型后续可在应用详情中单独管理。
          </p>
          <div
            v-if="aliases.length"
            class="model-options"
          >
            <label
              v-for="alias in aliases"
              :key="alias.id"
              class="model-option"
            >
              <input
                v-model="form.virtual_model_ids"
                type="checkbox"
                :value="alias.id"
              >
              <span><strong>{{ alias.display_name }}</strong><small>{{ alias.alias }}</small></span>
            </label>
          </div>
          <p
            v-else
            class="empty-inline"
          >
            当前没有已启用的虚拟模型，可先创建应用，配置模型后再授权。
          </p>
        </div>
      </fieldset>
      <p
        v-if="unlimited"
        class="lai-form-message-error"
        role="alert"
      >
        已选择无限制：相应维度不会主动阻止超量调用，请确认企业预算与运行风险。
      </p>
      <div
        v-if="reviewing"
        class="lai-card"
        aria-label="保存摘要"
      >
        <h2>确认应用信息</h2>
        <p>{{ form.name }} · {{ form.code }} · {{ form.owner_name }} · {{ applicationEnvironmentLabels[form.environment] }}</p>
        <p v-if="!isEdit">
          Token：{{ form.token_limited ? form.token_limit : '不限' }}；金额：{{ form.amount_limited ? form.amount_limit : '不限' }} {{ form.currency }}；RPM：{{ form.rpm_limited ? form.rpm : '不限' }}；TPM：{{ form.tpm_limited ? form.tpm : '不限' }}；模型：{{ form.virtual_model_ids.length }} 个
        </p>
        <button
          type="button"
          class="lai-btn"
          :disabled="submitting"
          @click="reviewing = false"
        >
          返回修改
        </button>
      </div>
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
        <button
          type="button"
          class="lai-btn"
          :disabled="latestLoading"
          @click="compareLatest"
        >
          读取最新版本并对比
        </button>
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
          <button
            type="button"
            class="lai-btn"
            @click="acceptVersion"
          >
            已核对，保留输入并使用最新版本
          </button>
        </div>
      </div>
      <p
        v-if="!conflictError && errorText"
        class="lai-form-message-error"
        role="alert"
      >
        {{ errorText }}
      </p>
      <div class="form-actions">
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
          data-test="save-application"
          class="lai-btn lai-btn-primary"
          :disabled="submitting || formInvalid || conflictError !== null"
        >
          {{ submitting ? '保存中…' : reviewing ? '确认保存' : '检查并保存' }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.application-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
.application-form-page { max-width: 1040px; }
.form-heading p, .section-help { color: #667085; font-size: 14px; }
.form-heading { margin-bottom: 20px; }
.form-heading .lai-page-title { margin-bottom: 6px; }
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
