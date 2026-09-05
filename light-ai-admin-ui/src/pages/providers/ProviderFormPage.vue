<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import KeyValueEditor from '@/components/KeyValueEditor.vue'
import VersionConflictBanner from '@/components/VersionConflictBanner.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { Permission } from '@/app/permissions'
import {
  type ProviderDetail,
  type ProviderSavePayload,
  createProvider,
  getProvider,
  updateProvider,
} from '@/api/providers'

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()

const providerId = computed(() => (route.params.id as string | undefined) ?? null)
const isEdit = computed(() => providerId.value !== null)
const canManage = computed(() => store.can(Permission.providerManage))

const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const loadedDetail = ref<ProviderDetail | null>(null)

const form = reactive({
  name: '',
  type: '',
  base_url: '',
  proxy_url: '',
  connect_timeout_ms: 3000,
  read_timeout_ms: 120000,
  default_headers: {} as Record<string, string>,
  enabled: true,
})
const version = ref<number | null>(null)
const localErrors = ref<Record<string, string>>({})

const { submitting, fieldMessages, conflictError, errorText, submit, reset } =
  useFormSubmit()

const dirty = ref(false)
function markDirty(): void {
  dirty.value = true
}

useDirtyGuard(() => dirty.value && loadState.value === 'ready')

const adapterOptions = computed(() =>
  store.adapters.map((adapter) => ({
    value: adapter.provider_type,
    label: adapter.provider_type,
    baseUrl: adapter.default_base_url,
  })),
)

function onTypeChange(type: string): void {
  form.type = type
  const adapter = store.adapters.find((item) => item.provider_type === type)
  if (adapter && form.base_url === '') {
    form.base_url = adapter.default_base_url
  }
  markDirty()
}

function normalizeBaseUrl(raw: string): string | null {
  const trimmed = raw.trim().replace(/\/+$/, '')
  let parsed: URL
  try {
    parsed = new URL(trimmed)
  } catch {
    return null
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return null
  return trimmed
}

function validate(): boolean {
  const errors: Record<string, string> = {}
  const name = form.name.trim()
  if (name.length < 2 || name.length > 64) {
    errors.name = '名称长度为 2—64 字符'
  }
  if (!isEdit.value && form.type === '') {
    errors.type = '请选择 Provider 类型'
  }
  if (normalizeBaseUrl(form.base_url) === null) {
    errors.base_url = '必须为合法的 http(s) 绝对地址'
  }
  if (form.proxy_url.trim() !== '') {
    try {
      const parsed = new URL(form.proxy_url.trim())
      if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        errors.proxy_url = '代理仅支持 http 或 https 协议'
      }
    } catch {
      errors.proxy_url = '代理地址格式不正确'
    }
  }
  if (!Number.isInteger(form.connect_timeout_ms) || form.connect_timeout_ms < 100 || form.connect_timeout_ms > 60000) {
    errors.connect_timeout_ms = '连接超时为 100—60000 的整数'
  }
  if (
    !Number.isInteger(form.read_timeout_ms) ||
    form.read_timeout_ms < 1000 ||
    form.read_timeout_ms > 600000
  ) {
    errors.read_timeout_ms = '读取超时为 1000—600000 的整数'
  } else if (form.read_timeout_ms < form.connect_timeout_ms) {
    errors.read_timeout_ms = '读取超时不能小于连接超时'
  }
  localErrors.value = errors
  return Object.keys(errors).length === 0
}

function applyDetail(detail: ProviderDetail): void {
  loadedDetail.value = detail
  form.name = detail.name
  form.type = detail.type
  form.base_url = detail.base_url
  form.proxy_url = detail.proxy_url ?? ''
  form.connect_timeout_ms = detail.connect_timeout_ms
  form.read_timeout_ms = detail.read_timeout_ms
  form.default_headers = { ...detail.default_headers }
  form.enabled = detail.enabled
  version.value = detail.version
  dirty.value = false
}

onMounted(async () => {
  if (!isEdit.value) {
    loadState.value = 'ready'
    return
  }
  loadState.value = 'loading'
  try {
    applyDetail(await getProvider(providerId.value!))
    loadState.value = 'ready'
  } catch (e) {
    loadError.value = e
    loadState.value = 'error'
  }
})

async function reloadLatest(): Promise<void> {
  if (providerId.value === null) return
  reset()
  try {
    applyDetail(await getProvider(providerId.value))
  } catch {
    router.push({ name: 'provider-list' })
  }
}

async function save(): Promise<void> {
  if (!validate()) return
  // 路由在保存跳转后变化，先固化当前编辑态，避免误走编辑分支。
  const editing = isEdit.value
  const targetId = providerId.value
  const payload: ProviderSavePayload = {
    name: form.name.trim(),
    type: form.type,
    base_url: normalizeBaseUrl(form.base_url)!,
    proxy_url: form.proxy_url.trim() === '' ? null : form.proxy_url.trim(),
    connect_timeout_ms: form.connect_timeout_ms,
    read_timeout_ms: form.read_timeout_ms,
    default_headers: form.default_headers,
    enabled: form.enabled,
  }
  const outcome = await submit(async () => {
    if (editing) {
      const result = await updateProvider(targetId!, { ...payload, version: version.value! })
      version.value = result.version
    } else {
      const result = await createProvider(payload)
      dirty.value = false
      void store.refreshDraftSummary()
      await router.push({ name: 'provider-detail', params: { id: result.id } })
    }
  })
  if (outcome.ok && editing) {
    dirty.value = false
    await reloadLatest()
  }
}

function cancel(): void {
  if (dirty.value && !window.confirm('有未保存的修改，离开将丢失。确认离开？')) return
  dirty.value = false
  router.back()
}

function fieldError(field: string): string | undefined {
  return localErrors.value[field] ?? fieldMessages.value[field]
}
</script>


<template>
  <section class="lai-page">
    <h1 class="lai-page-title">
      {{ isEdit ? '编辑 Provider' : '新建 Provider' }}
    </h1>

    <PageState
      v-if="loadState === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="loadState === 'error'"
      status="error"
      :error="loadError"
      @retry="reloadLatest()"
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

      <FormField
        label="名称"
        for-id="provider-name"
        required
        :error="fieldError('name')"
      >
        <input
          id="provider-name"
          v-model="form.name"
          class="lai-input"
          type="text"
          maxlength="64"
          @input="markDirty"
        >
      </FormField>

      <FormField
        label="类型"
        for-id="provider-type"
        :required="!isEdit"
        :error="fieldError('type')"
        :hint="isEdit ? '类型创建后只读' : '必须来自当前实例已加载的 Adapter'"
      >
        <select
          id="provider-type"
          class="lai-select"
          :value="form.type"
          :disabled="isEdit"
          @change="onTypeChange(($event.target as HTMLSelectElement).value)"
        >
          <option value="">
            请选择
          </option>
          <option
            v-for="option in adapterOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </FormField>

      <FormField
        label="服务地址"
        for-id="provider-base-url"
        required
        :error="fieldError('base_url')"
        hint="HTTPS 为默认要求；保存后建议重新检测"
      >
        <input
          id="provider-base-url"
          v-model="form.base_url"
          class="lai-input"
          type="url"
          @input="markDirty"
        >
      </FormField>

      <FormField
        label="代理地址"
        for-id="provider-proxy"
        :error="fieldError('proxy_url')"
        hint="空值直连"
      >
        <input
          id="provider-proxy"
          v-model="form.proxy_url"
          class="lai-input"
          type="url"
          @input="markDirty"
        >
      </FormField>

      <div class="lai-form-grid">
        <FormField
          label="连接超时（毫秒）"
          for-id="provider-connect-timeout"
          required
          :error="fieldError('connect_timeout_ms')"
        >
          <input
            id="provider-connect-timeout"
            v-model.number="form.connect_timeout_ms"
            class="lai-input"
            type="number"
            min="100"
            max="60000"
            @input="markDirty"
          >
        </FormField>
        <FormField
          label="读取超时（毫秒）"
          for-id="provider-read-timeout"
          required
          :error="fieldError('read_timeout_ms')"
        >
          <input
            id="provider-read-timeout"
            v-model.number="form.read_timeout_ms"
            class="lai-input"
            type="number"
            min="1000"
            max="600000"
            @input="markDirty"
          >
        </FormField>
      </div>

      <FormField
        label="默认请求头"
        for-id="provider-headers"
        :error="fieldError('default_headers')"
        hint="禁止认证与 Cookie 类请求头；最多 20 项"
      >
        <KeyValueEditor
          v-model="form.default_headers"
          @update:model-value="markDirty"
        />
      </FormField>

      <FormField
        label="启用"
        for-id="provider-enabled"
        hint="停用只改变草稿，发布后影响路由"
      >
        <input
          id="provider-enabled"
          v-model="form.enabled"
          type="checkbox"
          class="lai-checkbox"
          @change="markDirty"
        >
      </FormField>

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
          @click="cancel"
        >
          取消
        </button>
        <button
          v-if="canManage"
          type="submit"
          class="lai-btn lai-btn-primary"
          :disabled="submitting || conflictError !== null"
        >
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </div>
    </form>
  </section>
</template>
