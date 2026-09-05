<script setup lang="ts">
// Model Alias 新建/编辑表单（FE-017，附录 4.2.7.2）。
// alias 创建后只读：2—64 字符，仅字母、数字、点、短横线、下划线。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FormField from '@/components/FormField.vue'
import PageState from '@/components/PageState.vue'
import { useDirtyGuard } from '@/composables/useDirtyGuard'
import { useFormSubmit } from '@/composables/useFormSubmit'
import {
  createModelAlias,
  fetchModelAlias,
  updateModelAlias,
} from '@/api/modelAliases'

const route = useRoute()
const router = useRouter()
const aliasRecordId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isEdit = computed(() => aliasRecordId.value !== '')

const loading = ref(true)
const loadError = ref<unknown>(null)

const form = reactive({
  alias: '',
  display_name: '',
  description: '',
  enabled: true,
})
const version = ref<number | null>(null)
const baseline = ref('')
const dirty = ref(false)
const { submitting, conflictError, errorText, submit: doSubmit } = useFormSubmit()

const ALIAS_PATTERN = /^[A-Za-z0-9._-]{2,64}$/

const aliasInvalid = computed(() => !ALIAS_PATTERN.test(form.alias))
const displayNameInvalid = computed(() => form.display_name.trim().length < 2 || form.display_name.trim().length > 64)
const descriptionInvalid = computed(() => form.description.length > 500)
const formInvalid = computed(
  () => (isEdit.value ? false : aliasInvalid.value) || displayNameInvalid.value || descriptionInvalid.value,
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

async function onSubmit(): Promise<void> {
  if (formInvalid.value) return
  const description = form.description.trim() === '' ? null : form.description.trim()
  let savedId = ''
  const outcome = await doSubmit(async () => {
    if (isEdit.value) {
      await updateModelAlias(aliasRecordId.value, {
        display_name: form.display_name.trim(),
        description,
        enabled: form.enabled,
        version: version.value!,
      })
      savedId = aliasRecordId.value
    } else {
      const result = await createModelAlias({
        alias: form.alias,
        display_name: form.display_name.trim(),
        description,
        enabled: form.enabled,
      })
      savedId = result.id
    }
  })
  if (outcome.ok) {
    dirty.value = false
    void router.push(`/ui/model-aliases/${savedId}`)
  }
}

onMounted(async () => {
  try {
    if (isEdit.value) {
      const detail = await fetchModelAlias(aliasRecordId.value)
      form.alias = detail.alias
      form.display_name = detail.display_name
      form.description = detail.description ?? ''
      form.enabled = detail.enabled
      version.value = detail.version
    }
    markClean()
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
      {{ isEdit ? '编辑模型别名' : '新建 Model Alias' }}
    </h1>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
      @retry="() => router.go(0)"
    />

    <form
      v-else
      class="lai-form"
      @submit.prevent="onSubmit"
      @input="onInput"
    >
      <FormField
        label="alias"
        required
        :hint="isEdit ? 'alias 创建后不可修改；如需更名请创建新 Alias 并迁移接入方' : '业务调用入口，创建后不可修改'"
        :error="!isEdit && aliasInvalid ? '2—64 字符，仅字母、数字、点、短横线、下划线' : ''"
      >
        <input
          v-model="form.alias"
          class="lai-input lai-mono"
          type="text"
          maxlength="64"
          spellcheck="false"
          :disabled="isEdit"
        >
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
        label="描述"
        :error="descriptionInvalid ? '最多 500 字符' : ''"
        hint="最多 500 字符，不用于模型 Prompt"
      >
        <textarea
          v-model="form.description"
          class="lai-input lai-textarea"
          rows="4"
          maxlength="500"
        />
      </FormField>

      <FormField label="路由策略">
        <input
          class="lai-input"
          type="text"
          value="PRIORITY_WEIGHTED"
          disabled
        >
      </FormField>

      <label class="lai-switch">
        <input
          v-model="form.enabled"
          type="checkbox"
        >
        启用（发布时必须至少有一个启用且引用完整的候选）
      </label>

      <p
        v-if="conflictError"
        class="lai-form-message-error"
        role="alert"
      >
        配置已被其他管理员修改（最新版本 {{ conflictError?.serverVersion ?? '未知' }}）。您的输入已保留，请刷新后重试。
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
  max-width: 640px;
}
.lai-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-textarea {
  resize: vertical;
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
