<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { onBeforeRouteUpdate } from 'vue-router'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { ApiError, isAbortError } from '@/api/errors'
import { positiveInteger, validIpRule } from './applicationValues'
import ApplicationKeySecretDialog from './ApplicationKeySecretDialog.vue'
import PageState from '@/components/PageState.vue'
import { formatDateTime } from '@/app/display'
import { Permission } from '@/app/permissions'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useFormSubmit } from '@/composables/useFormSubmit'
import {
  changeApplicationKeyStatus, createApplicationKey, fetchApplicationKeys,
  revokeApplicationKey, rotateApplicationKey,
  type ApplicationKeySecretResult, type ApplicationKeyView, type ApplicationModelPermission,
} from '@/api/applications'

const props = defineProps<{
  applicationId: string
  applicationActive: boolean
  applicationRpm: number | null
  applicationTpm: number | null
  applicationModels: ApplicationModelPermission[]
}>()
const emit = defineEmits<{ changed: [] }>()
type KeyAction = 'enable' | 'disable' | 'rotate' | 'revoke'
const store = useBootstrapStore()
const keys = ref<ApplicationKeyView[]>([])
const loading = ref(true)
const loadError = ref<unknown>(null)
const createOpen = ref(false)
const actionKey = ref<ApplicationKeyView | null>(null)
const action = ref<KeyAction | null>(null)
const reason = ref('')
const secret = ref<ApplicationKeySecretResult | null>(null)
let scopeVersion = 0
let loadVersion = 0
let controller: AbortController | null = null
const refreshing = ref(false)
const canView = computed(() => store.can(Permission.applicationKeyView))
const canManage = computed(() => store.can(Permission.applicationKeyManage))
const form = reactive({
  name: '', expires_at: '', rpm: null as number | null | string, tpm: null as number | null,
  ip_allowlist: '', virtual_model_ids: [] as string[],
})
const { submitting, errorText, conflictError, submit, reset } = useFormSubmit()
const labels: Record<string, string> = {
  ACTIVE: '有效', DISABLED: '已停用', EXPIRED: '已过期', REVOKED: '已撤销',
}

async function load(): Promise<void> {
  const sequence = ++loadVersion
  controller?.abort()
  controller = new AbortController()
  refreshing.value = true
  loadError.value = null
  try {
    if (!canView.value) throw new ApiError(403, { code: 'ACCESS_DENIED', type: 'permission', message: '无权查看应用密钥' }, 'local-permission')
    const result = await fetchApplicationKeys(props.applicationId, controller.signal)
    if (sequence === loadVersion) keys.value = result
  } catch (error) { if (sequence === loadVersion && !isAbortError(error)) loadError.value = error }
  finally { if (sequence === loadVersion) { loading.value = false; refreshing.value = false } }
}
const ipRules = computed(() => form.ip_allowlist.split(/[\n,]/).map(item => item.trim()).filter(Boolean))
const validationMessage = computed(() => {
  if (form.name.trim().length < 2 || form.name.trim().length > 64) return '名称需要 2—64 个字符'
  if (form.expires_at && (!Number.isFinite(Date.parse(form.expires_at)) || Date.parse(form.expires_at) <= Date.now())) return '有效期必须晚于当前时间'
  for (const [value, cap] of [[form.rpm, props.applicationRpm], [form.tpm, props.applicationTpm]] as const) {
    if (value !== null && value !== '' && (!positiveInteger(value) || (cap !== null && value > cap))) return '独立 RPM/TPM 必须为正整数，且不能高于应用限制'
  }
  if (ipRules.value.some(value => !validIpRule(value))) return '请输入有效的 IPv4、IPv6 或 CIDR'
  if (form.virtual_model_ids.some(id => !props.applicationModels.some(model => model.enabled && model.virtual_model_id === id))) return '只能选择应用已授权模型'
  return ''
})

function openCreate(): void {
  if (!canManage.value || !props.applicationActive || submitting.value || secret.value) return
  Object.assign(form, {
    name: '', expires_at: '', rpm: null, tpm: null, ip_allowlist: '', virtual_model_ids: [],
  })
  reset()
  createOpen.value = true
}

function openAction(key: ApplicationKeyView, next: KeyAction): void {
  if (!canManage.value || submitting.value || secret.value || !['ACTIVE', 'DISABLED', 'EXPIRED'].includes(key.status)) return
  actionKey.value = key
  action.value = next
  reason.value = ''
  reset()
}

async function createKey(): Promise<void> {
  if (!canManage.value || !props.applicationActive || validationMessage.value || submitting.value) return
  const generation = scopeVersion
  const outcome = await submit(async () => {
    const result = await createApplicationKey(props.applicationId, {
      name: form.name.trim(),
      expires_at: form.expires_at ? new Date(form.expires_at).toISOString() : null,
      rpm: typeof form.rpm === 'number' ? form.rpm : null,
      tpm: typeof form.tpm === 'number' ? form.tpm : null,
      ip_allowlist: form.ip_allowlist.split(/[\n,]/).map((item) => item.trim()).filter(Boolean),
      virtual_model_ids: form.virtual_model_ids,
    })
    if (generation === scopeVersion) secret.value = result
  })
  if (outcome.ok && generation === scopeVersion) {
    createOpen.value = false
    await load()
  }
}

async function applyAction(): Promise<void> {
  if (!canManage.value || !actionKey.value || !action.value || !reason.value.trim() || reason.value.length > 500 || submitting.value) return
  const generation = scopeVersion
  const current = actionKey.value
  const selectedAction = action.value
  const outcome = await submit(async () => {
    if (selectedAction === 'rotate') {
      const result = await rotateApplicationKey(props.applicationId, current.id, {
        version: current.version, reason: reason.value.trim(),
      })
      if (generation === scopeVersion) secret.value = result
    } else if (selectedAction === 'revoke') {
      await revokeApplicationKey(props.applicationId, current.id, {
        version: current.version, reason: reason.value.trim(),
      })
    } else {
      await changeApplicationKeyStatus(props.applicationId, current.id, {
        status: selectedAction === 'enable' ? 'ACTIVE' : 'DISABLED',
        version: current.version,
        reason: reason.value.trim(),
      })
    }
  })
  if (outcome.ok && generation === scopeVersion) {
    actionKey.value = null
    action.value = null
    await load()
    if (selectedAction !== 'rotate') emit('changed')
  }
}

function actionTitle(value: KeyAction): string {
  return {
    enable: '启用密钥',
    disable: '停用密钥',
    rotate: '轮换密钥',
    revoke: '撤销密钥',
  }[value]
}

function actionMessage(value: KeyAction): string {
  return {
    enable: '启用后，新请求可再次使用此密钥；仍受应用状态、有效期和治理策略约束。',
    disable: '停用后新请求会立即被拒绝，后续可以重新启用。',
    rotate: '轮换后旧密钥立即失效，新原文只显示一次。',
    revoke: '撤销不可恢复，新调用会立即被拒绝。',
  }[value]
}

function modelScopeText(key: ApplicationKeyView): string {
  if (!key.virtual_model_ids.length) return '继承应用全部'
  const labelsById = new Map(props.applicationModels.map((model) => [
    model.virtual_model_id, model.virtual_model_code || model.virtual_model_id,
  ]))
  return key.virtual_model_ids.map((id) => labelsById.get(id) || id).join('、')
}

function closeSecret(): void {
  secret.value = null
  emit('changed')
}
function clearScope(): void {
  ++scopeVersion
  ++loadVersion
  controller?.abort()
  secret.value = null
  keys.value = []
  actionKey.value = null
  createOpen.value = false
}
useDirtyGuard(() => secret.value !== null || submitting.value)
onBeforeRouteUpdate((to, from) => to.params.id === from.params.id || (!secret.value && !submitting.value) || window.confirm('密钥仅显示一次，切换应用前请确认已安全保存。继续切换？'))
watch(() => [props.applicationId, store.userId, canView.value, canManage.value], () => {
  clearScope()
  loading.value = true
  void load()
}, { immediate: true })
onScopeDispose(clearScope)
</script>

<template>
  <div class="lai-card">
    <div class="key-heading">
      <div>
        <h2 class="lai-card-title">
          应用密钥
        </h2>
        <p>业务系统使用应用密钥调用平台，上游供应商 Key 不会暴露给应用。</p>
      </div>
      <button
        v-if="canManage"
        type="button"
        class="lai-btn lai-btn-primary"
        :disabled="!applicationActive || submitting || secret !== null"
        @click="openCreate"
      >
        签发密钥
      </button>
    </div>
    <p
      v-if="refreshing && !loading"
      role="status"
    >
      刷新中…
    </p>
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
    <div
      v-else-if="keys.length"
      class="lai-table-wrap"
    >
      <table class="lai-table key-table">
        <thead>
          <tr>
            <th>名称</th><th>密钥</th><th>状态</th><th>模型范围</th><th>RPM / TPM</th><th>有效期</th><th>最近使用</th><th v-if="canManage">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="key in keys"
            :key="key.id"
          >
            <td><strong>{{ key.name }}</strong><small>第 {{ key.rotation_generation }} 代</small></td>
            <td class="lai-cell-mono">
              {{ key.masked_value }}
            </td>
            <td>{{ labels[key.status] || key.status }}</td>
            <td class="model-scope">
              {{ modelScopeText(key) }}
            </td>
            <td>{{ key.rpm ?? '继承应用' }} / {{ key.tpm == null ? '继承应用' : key.tpm.toLocaleString() }}</td>
            <td>{{ formatDateTime(key.expires_at, store.timezone, '长期有效') }}</td>
            <td>{{ formatDateTime(key.last_used_at, store.timezone, '尚未使用') }}</td>
            <td v-if="canManage">
              <span
                v-if="['ACTIVE', 'DISABLED', 'EXPIRED'].includes(key.status)"
                class="key-actions"
              >
                <button
                  v-if="key.status === 'ACTIVE'"
                  :data-test="`key-disable-${key.id}`"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openAction(key, 'disable')"
                >停用</button>
                <button
                  v-if="key.status === 'DISABLED'"
                  :data-test="`key-enable-${key.id}`"
                  type="button"
                  class="lai-btn lai-btn-text"
                  :disabled="!applicationActive"
                  @click="openAction(key, 'enable')"
                >启用</button>
                <button
                  v-if="key.status === 'ACTIVE'"
                  type="button"
                  class="lai-btn lai-btn-text"
                  @click="openAction(key, 'rotate')"
                >轮换</button>
                <button
                  :data-test="`key-revoke-${key.id}`"
                  type="button"
                  class="lai-btn lai-btn-text danger"
                  @click="openAction(key, 'revoke')"
                >撤销</button>
              </span>
              <span v-else>—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p
      v-else
      class="empty-inline"
    >
      尚未签发应用密钥。签发后，密钥原文只会显示一次。
    </p>
  </div>

  <div
    v-if="createOpen"
    class="lai-dialog-overlay"
    @click.self="!submitting && (createOpen = false)"
  >
    <form
      class="lai-dialog"
      role="dialog"
      aria-modal="true"
      aria-label="签发应用密钥"
      @submit.prevent="createKey"
    >
      <h2 class="lai-dialog-title">
        签发应用密钥
      </h2>
      <label class="dialog-field"><span>名称</span><input
        v-model="form.name"
        class="lai-input"
        maxlength="64"
        placeholder="例如：生产服务"
      ></label>
      <label class="dialog-field"><span>有效期（可选）</span><input
        v-model="form.expires_at"
        class="lai-input"
        type="datetime-local"
      ></label>
      <div class="dialog-grid">
        <label class="dialog-field"><span>独立 RPM</span><input
          v-model.number="form.rpm"
          class="lai-input"
          type="number"
          min="1"
          placeholder="继承应用"
        ></label>
        <label class="dialog-field"><span>独立 TPM</span><input
          v-model.number="form.tpm"
          class="lai-input"
          type="number"
          min="1"
          placeholder="继承应用"
        ></label>
      </div>
      <label class="dialog-field"><span>IP 白名单（可选）</span><textarea
        v-model="form.ip_allowlist"
        class="lai-input textarea"
        rows="3"
        placeholder="每行一个 IP 或 CIDR"
      /></label>
      <fieldset class="model-field">
        <legend>虚拟模型子集（可选）</legend>
        <p>不选择表示继承应用全部授权；选择后只能调用所选模型。</p>
        <label
          v-for="model in applicationModels"
          :key="model.virtual_model_id"
          class="model-option"
        >
          <input
            v-model="form.virtual_model_ids"
            type="checkbox"
            :value="model.virtual_model_id"
          >
          <span>{{ model.virtual_model_code || model.virtual_model_id }}</span>
        </label>
        <span
          v-if="!applicationModels.length"
          class="empty-inline"
        >应用尚未授权虚拟模型。</span>
      </fieldset>
      <p class="warning">
        创建成功后请立即复制并安全保存，关闭窗口后无法再次查看原文。
      </p>
      <p
        v-if="validationMessage"
        role="alert"
      >
        {{ validationMessage }}
      </p>
      <p
        v-if="errorText"
        class="lai-form-message-error"
      >
        {{ errorText }}
      </p>
      <div class="lai-dialog-actions">
        <button
          type="button"
          class="lai-btn"
          :disabled="submitting"
          @click="createOpen = false"
        >
          取消
        </button><button
          type="submit"
          class="lai-btn lai-btn-primary"
          :disabled="submitting || !!validationMessage || !canManage || !applicationActive"
        >
          {{ submitting ? '签发中…' : '签发' }}
        </button>
      </div>
    </form>
  </div>

  <div
    v-if="actionKey && action"
    class="lai-dialog-overlay"
    @click.self="!submitting && (actionKey = null)"
  >
    <form
      class="lai-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="application-key-action-title"
      @submit.prevent="applyAction"
    >
      <h2
        id="application-key-action-title"
        class="lai-dialog-title"
      >
        {{ actionTitle(action) }}
      </h2>
      <p class="lai-dialog-message">
        {{ actionMessage(action) }}
      </p>
      <label class="dialog-field"><span>操作原因</span><textarea
        v-model="reason"
        class="lai-input textarea"
        rows="3"
        maxlength="500"
        placeholder="必填，将写入审计记录"
      /></label>
      <p
        v-if="conflictError"
        class="lai-form-message-error"
      >
        密钥版本已变化，请刷新后重试。
      </p>
      <p
        v-else-if="errorText"
        class="lai-form-message-error"
      >
        {{ errorText }}
      </p>
      <div class="lai-dialog-actions">
        <button
          type="button"
          class="lai-btn"
          :disabled="submitting"
          @click="actionKey = null"
        >
          取消
        </button><button
          type="submit"
          class="lai-btn lai-btn-primary"
          :disabled="submitting || !reason.trim()"
        >
          {{ submitting ? '处理中…' : '确认' }}
        </button>
      </div>
    </form>
  </div>

  <ApplicationKeySecretDialog
    v-if="secret"
    :value="secret.secret"
    @close="closeSecret"
  />
</template>

<style scoped>
.key-heading { display:flex; justify-content:space-between; gap:20px; margin-bottom:14px; }
.key-heading p, .empty-inline { color:#667085; font-size:13px; }
.key-heading .lai-card-title { margin-bottom:4px; }
.key-table { min-width:940px; }
.key-table small { display:block; margin-top:3px; color:#667085; }
.model-scope { max-width:220px; overflow-wrap:anywhere; }
.key-actions { display:flex; }.danger { color:#b42318; }
.dialog-field { display:flex; flex-direction:column; gap:6px; margin:12px 0; color:#475467; }
.dialog-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
.dialog-field .lai-input { width:100%; max-width:none; }
.textarea { height:auto; padding:8px 10px; resize: vertical; }
.warning { padding:10px; color:#92400e; background:#fffbeb; border-radius:6px; font-size:13px; }
.model-field { margin:12px 0; padding:10px 12px; border:1px solid #d0d5dd; border-radius:6px; }
.model-field legend { padding:0 4px; color:#475467; }
.model-field p { margin:0 0 8px; color:#667085; font-size:12px; }
.model-option { display:flex; align-items:center; gap:8px; margin:7px 0; }
</style>
