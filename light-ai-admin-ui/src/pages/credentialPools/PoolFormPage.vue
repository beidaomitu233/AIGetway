<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import VersionConflictBanner from '@/components/VersionConflictBanner.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import { Permission } from '@/app/permissions'
import { selectionStrategyLabels } from '@/app/display'
import {
  type CredentialPoolDetail,
  type PoolSavePayload,
  type SelectionStrategy,
  createPool,
  getPool,
  updatePool,
} from '@/api/credentialPools'
import { listProviders } from '@/api/providers'

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()

const poolId = computed(() => (route.params.id as string | undefined) ?? null)
const isEdit = computed(() => poolId.value !== null)
const canManage = computed(() => store.can(Permission.credentialManage))

const loadState = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)

const form = reactive({
  provider_id: '',
  name: '',
  selection_strategy: 'LEAST_CONCURRENT' as SelectionStrategy,
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

const strategyOptions = Object.entries(selectionStrategyLabels).map(([value, label]) => ({
  value,
  label,
}))

const providerOptions = ref<Array<{ value: string; label: string }>>([])
onMounted(async () => {
  try {
    const result = await listProviders({ page: 1, page_size: 100 }, new AbortController().signal)
    providerOptions.value = result.items.map((provider) => ({
      value: provider.id,
      label: provider.name,
    }))
  } catch {
    providerOptions.value = []
  }
  if (!isEdit.value) {
    loadState.value = 'ready'
    return
  }
  try {
    const detail: CredentialPoolDetail = await getPool(poolId.value!)
    form.provider_id = detail.provider_id
    form.name = detail.name
    form.selection_strategy = detail.selection_strategy as SelectionStrategy
    form.enabled = detail.enabled
    version.value = detail.version
    dirty.value = false
    loadState.value = 'ready'
  } catch (e) {
    loadError.value = e
    loadState.value = 'error'
  }
})

function validate(): boolean {
  const errors: Record<string, string> = {}
  if (form.provider_id === '') {
    errors.provider_id = '请选择 Provider'
  }
  const name = form.name.trim()
  if (name.length < 2 || name.length > 64) {
    errors.name = '名称长度为 2—64 字符'
  }
  localErrors.value = errors
  return Object.keys(errors).length === 0
}

async function reloadLatest(): Promise<void> {
  if (poolId.value === null) return
  reset()
  try {
    const detail = await getPool(poolId.value)
    form.name = detail.name
    form.selection_strategy = detail.selection_strategy as SelectionStrategy
    form.enabled = detail.enabled
    version.value = detail.version
    dirty.value = false
  } catch {
    router.push({ name: 'pool-list' })
  }
}

async function save(): Promise<void> {
  if (!validate()) return
  // 路由在保存跳转后变化，先固化当前编辑态，避免误走编辑分支。
  const editing = isEdit.value
  const targetId = poolId.value
  const payload: PoolSavePayload = {
    provider_id: form.provider_id,
    name: form.name.trim(),
    selection_strategy: form.selection_strategy,
    enabled: form.enabled,
  }
  const outcome = await submit(async () => {
    if (editing) {
      const result = await updatePool(targetId!, { ...payload, version: version.value! })
      version.value = result.version
    } else {
      const result = await createPool(payload)
      dirty.value = false
      void store.refreshDraftSummary()
      await router.push({ name: 'pool-detail', params: { id: result.id } })
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
      {{ isEdit ? '编辑凭证池' : '新建凭证池' }}
    </h1>

    <PageState
      v-if="loadState === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="loadState === 'error'"
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

      <FormField
        label="Provider"
        for-id="pool-provider"
        required
        :error="fieldError('provider_id')"
        :hint="isEdit ? 'Provider 创建后只读' : '必须指向未删除的 Provider'"
      >
        <select
          id="pool-provider"
          v-model="form.provider_id"
          class="lai-select"
          :disabled="isEdit"
          @change="markDirty"
        >
          <option value="">
            请选择
          </option>
          <option
            v-for="option in providerOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </FormField>

      <FormField
        label="名称"
        for-id="pool-name"
        required
        :error="fieldError('name')"
        hint="同一 Provider 下唯一"
      >
        <input
          id="pool-name"
          v-model="form.name"
          class="lai-input"
          type="text"
          maxlength="64"
          @input="markDirty"
        >
      </FormField>

      <FormField
        label="选择策略"
        for-id="pool-strategy"
        required
        :error="fieldError('selection_strategy')"
      >
        <select
          id="pool-strategy"
          v-model="form.selection_strategy"
          class="lai-select"
          @change="markDirty"
        >
          <option
            v-for="option in strategyOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </FormField>

      <FormField
        label="启用"
        for-id="pool-enabled"
        hint="停用前需完成影响分析；发布后影响新请求"
      >
        <input
          id="pool-enabled"
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
