<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, Card, Input, Select } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { normalizeResourceUrl, headersValid } from '@/utils/resourceValidation'
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
  provider_type: '',
  base_url: '',
  proxy: '',
  timeouts: {
    connect_ms: 3000,
    read_ms: 120000,
    stream_idle_ms: 120000,
  },
  headers: {} as Record<string, string>,
  priority: 10,
  weight: 1,
})
const headerValid = ref(true)
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

function onTypeChange(value: unknown): void {
  const providerType = Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
  form.provider_type = providerType
  const adapter = store.adapters.find((item) => item.provider_type === providerType)
  if (adapter && form.base_url === '') {
    form.base_url = adapter.default_base_url
  }
  markDirty()
}

function setTextField(field: 'name' | 'base_url' | 'proxy', value: string): void {
  form[field] = value
  markDirty()
}

function setTimeoutField(field: 'connect_ms' | 'read_ms' | 'stream_idle_ms', value: string): void {
  form.timeouts[field] = value === '' ? 0 : Number(value)
  markDirty()
}

function setCountField(field: 'priority' | 'weight', value: string): void {
  form[field] = value === '' ? 0 : Number(value)
  markDirty()
}

function validate(): boolean {
  const errors: Record<string, string> = {}
  const name = form.name.trim()
  if (name.length < 2 || name.length > 64) {
    errors.name = '名称长度为 2—64 字符'
  }
  if (!isEdit.value && !adapterOptions.value.some((option) => option.value === form.provider_type)) {
    errors.provider_type = '请选择渠道类型'
  }
  if (normalizeResourceUrl(form.base_url) === null) {
    errors.base_url = '必须为合法的 http(s) 绝对地址，且不含认证信息、查询参数与片段'
  }
  if (form.proxy.trim() && normalizeResourceUrl(form.proxy) === null) {
    errors.proxy = '代理必须为不含认证信息、查询参数与片段的 http(s) 地址'
  }
  if (!headerValid.value || !headersValid(Object.entries(form.headers).map(([key, value]) => ({ key, value })))) {
    errors.headers = '请修正请求头，禁止携带认证信息'
  }
  if (!Number.isInteger(form.timeouts.connect_ms) || form.timeouts.connect_ms < 100 || form.timeouts.connect_ms > 60000) {
    errors.connect_ms = '连接超时为 100—60000 的整数'
  }
  if (
    !Number.isInteger(form.timeouts.read_ms) ||
    form.timeouts.read_ms < 1000 ||
    form.timeouts.read_ms > 600000
  ) {
    errors.read_ms = '读取超时为 1000—600000 的整数'
  } else if (form.timeouts.read_ms < form.timeouts.connect_ms) {
    errors.read_ms = '读取超时不能小于连接超时'
  }
  if (!Number.isInteger(form.timeouts.stream_idle_ms) || form.timeouts.stream_idle_ms < 1000 || form.timeouts.stream_idle_ms > 600000) {
    errors.stream_idle_ms = '流式空闲超时为 1000—600000 的整数'
  }
  if (!Number.isInteger(form.priority) || form.priority < 1 || form.priority > 100) {
    errors.priority = '优先级为 1—100 的整数'
  }
  if (!Number.isInteger(form.weight) || form.weight < 1 || form.weight > 100) {
    errors.weight = '权重为 1—100 的整数'
  }
  localErrors.value = errors
  return Object.keys(errors).length === 0
}

function applyDetail(detail: ProviderDetail): void {
  loadedDetail.value = detail
  form.name = detail.name
  form.provider_type = detail.provider_type
  form.base_url = detail.base_url
  form.proxy = detail.proxy ?? ''
  form.timeouts = { ...detail.timeouts }
  form.headers = { ...detail.headers }
  form.priority = detail.priority
  form.weight = detail.weight
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
    loadState.value = 'ready'
    loadError.value = null
  } catch (error) {
    loadError.value = error
    loadState.value = 'error'
  }
}

async function save(): Promise<void> {
  if (!canManage.value || submitting.value || conflictError.value || !validate()) return
  // 路由在保存跳转后变化，先固化当前编辑态，避免误走编辑分支。
  const editing = isEdit.value
  const targetId = providerId.value
  const payload: ProviderSavePayload = {
    name: form.name.trim(),
    provider_type: form.provider_type,
    base_url: normalizeResourceUrl(form.base_url)!,
    proxy: form.proxy.trim() === '' ? null : form.proxy.trim(),
    timeouts: { ...form.timeouts },
    headers: { ...form.headers },
    priority: form.priority,
    weight: form.weight,
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
      {{ isEdit ? '编辑 渠道' : '新建 渠道' }}
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
    <Card
      v-else
      :bordered="false"
      class="provider-form-card"
    >
    <form
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
        <Input
          id="provider-name"
          :value="form.name"
          name="name"
          :maxlength="64"
          @update:value="(value: string) => setTextField('name', value)"
        />
      </FormField>

      <FormField
        label="类型"
        for-id="provider-type"
        :required="!isEdit"
        :error="fieldError('provider_type')"
        :hint="isEdit ? '类型创建后只读' : '必须来自当前实例已加载的 Adapter'"
      >
        <Select
          id="provider-type"
          class="provider-type-select"
          :value="form.provider_type === '' ? undefined : form.provider_type"
          :options="adapterOptions"
          placeholder="请选择"
          :disabled="isEdit"
          @update:value="onTypeChange"
        />
      </FormField>

      <FormField
        label="服务地址"
        for-id="provider-base-url"
        required
        :error="fieldError('base_url')"
        hint="不得包含认证信息；服务端验证 DNS、目标网络及重定向，保存后需检测"
      >
        <Input
          id="provider-base-url"
          :value="form.base_url"
          name="base_url"
          type="url"
          @update:value="(value: string) => setTextField('base_url', value)"
        />
      </FormField>

      <FormField
        label="代理地址"
        for-id="provider-proxy"
        :error="fieldError('proxy')"
        hint="空值直连"
      >
        <Input
          id="provider-proxy"
          :value="form.proxy"
          name="proxy"
          type="url"
          @update:value="(value: string) => setTextField('proxy', value)"
        />
      </FormField>

      <div class="lai-form-grid">
        <FormField
          label="连接超时（毫秒）"
          for-id="provider-connect-timeout"
          required
          :error="fieldError('connect_ms')"
        >
          <Input
            id="provider-connect-timeout"
            :value="String(form.timeouts.connect_ms)"
            name="connect_ms"
            type="number"
            :min="100"
            :max="60000"
            @update:value="(value: string) => setTimeoutField('connect_ms', value)"
          />
        </FormField>
        <FormField
          label="读取超时（毫秒）"
          for-id="provider-read-timeout"
          required
          :error="fieldError('read_ms')"
        >
          <Input
            id="provider-read-timeout"
            :value="String(form.timeouts.read_ms)"
            name="read_ms"
            type="number"
            :min="1000"
            :max="600000"
            @update:value="(value: string) => setTimeoutField('read_ms', value)"
          />
        </FormField>
        <FormField
          label="流式空闲超时（毫秒）"
          for-id="provider-stream-idle-timeout"
          required
          :error="fieldError('stream_idle_ms')"
        >
          <Input
            id="provider-stream-idle-timeout"
            :value="String(form.timeouts.stream_idle_ms)"
            name="stream_idle_ms"
            type="number"
            :min="1000"
            :max="600000"
            @update:value="(value: string) => setTimeoutField('stream_idle_ms', value)"
          />
        </FormField>
        <FormField
          label="优先级"
          for-id="provider-priority"
          required
          :error="fieldError('priority')"
        >
          <Input
            id="provider-priority"
            :value="String(form.priority)"
            name="priority"
            type="number"
            :min="1"
            :max="100"
            @update:value="(value: string) => setCountField('priority', value)"
          />
        </FormField>
        <FormField
          label="权重"
          for-id="provider-weight"
          required
          :error="fieldError('weight')"
        >
          <Input
            id="provider-weight"
            :value="String(form.weight)"
            name="weight"
            type="number"
            :min="1"
            :max="100"
            @update:value="(value: string) => setCountField('weight', value)"
          />
        </FormField>
      </div>

      <FormField
        label="请求头"
        for-id="provider-headers"
        :error="fieldError('headers')"
        hint="禁止认证与 Cookie 类请求头；最多 20 项"
      >
        <KeyValueEditor
          v-model="form.headers"
          :disabled="submitting || !canManage"
          @validity="headerValid = $event"
          @update:model-value="markDirty"
        />
      </FormField>

      <p
        v-if="errorText"
        class="lai-form-message-error"
        role="alert"
      >
        {{ errorText }}
      </p>

      <div class="lai-form-actions">
        <Button
          :disabled="submitting"
          @click="cancel"
        >
          取消
        </Button>
        <Button
          v-if="canManage"
          type="primary"
          html-type="submit"
          :disabled="submitting || !headerValid || conflictError !== null"
        >
          {{ submitting ? '保存中…' : '保存' }}
        </Button>
      </div>
    </form>
    </Card>
  </section>
</template>

<style scoped>
.provider-form-card { border: 1px solid var(--lai-border); box-shadow: 0 8px 24px rgba(37, 99, 235, .05); }
.provider-form-card :deep(.ant-card-body) { padding: 20px; }
</style>
