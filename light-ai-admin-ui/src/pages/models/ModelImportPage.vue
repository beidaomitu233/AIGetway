<script setup lang="ts">
// 模型导入向导（FE-016，附录 4.2.5.2）：选择 Provider 与来源 → 勾选候选模型 → 提交导入；
// 未知能力显示“待补充”，导入默认停用；结果按 created/skipped/failed 逐项展示。
import { computed, onMounted, ref, shallowRef } from 'vue'
import PageState from '@/components/PageState.vue'
import FormField from '@/components/FormField.vue'
import {
  fetchProviderOptions,
  fetchProviderCredentials,
  fetchAvailableModels,
  importProviderModels,
  type ImportResult,
  type ProviderModelImportCandidate,
  type ProviderOption,
} from '@/api/providerModels'
import { toErrorMessage } from '@/api/errors'
import { isAbortError } from '@/api/errors'


const providers = ref<ProviderOption[]>([])
const providerId = ref('')
const source = ref<'PROVIDER_API' | 'ADAPTER_PRESET'>('PROVIDER_API')
const credentialId = ref('')
const keyword = ref('')
const applyKnownDefaults = ref(true)
const importEnabled = ref(false)

const credentialOptions = ref<{ id: string; label: string }[]>([])
const credentialLoading = ref(false)
const candidates = shallowRef<ProviderModelImportCandidate[]>([])
const selectedModelIds = ref<string[]>([])
const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const submitError = ref('')
const result = shallowRef<ImportResult | null>(null)

const sourceOptions = [
  { value: 'PROVIDER_API', label: 'Provider API（实时拉取）' },
  { value: 'ADAPTER_PRESET', label: 'Adapter 预置目录' },
]

const canLoadCandidates = computed(() => {
  if (providerId.value === '') return false
  if (source.value === 'PROVIDER_API' && credentialId.value === '') return false
  return true
})
const filteredCandidates = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (text === '') return candidates.value
  return candidates.value.filter(
    (item) => item.model_id.toLowerCase().includes(text) || (item.display_name ?? '').toLowerCase().includes(text),
  )
})
const selectedExisting = computed(() =>
  filteredCandidates.value.filter((item) => selectedModelIds.value.includes(item.model_id) && item.existing).length,
)
const canSubmit = computed(() => selectedModelIds.value.length > 0 && !submitting.value)

function abilityText(value: boolean | null | undefined, trueText: string, falseText: string): string {
  return value == null ? '待补充' : value ? trueText : falseText
}

async function onProviderChange(): Promise<void> {
  credentialId.value = ''
  credentialOptions.value = []
  candidates.value = []
  selectedModelIds.value = []
  result.value = null
  if (providerId.value === '') return
  credentialLoading.value = true
  try {
    const options = await fetchProviderCredentials(providerId.value)
    credentialOptions.value = options.map((item) => ({
      id: item.id,
      label: `${item.name}（${item.pool_name}）`,
    }))
  } catch (e) {
    loadError.value = toErrorMessage(e)
  } finally {
    credentialLoading.value = false
  }
}

async function loadCandidates(): Promise<void> {
  if (!canLoadCandidates.value) return
  loading.value = true
  loadError.value = ''
  const controller = new AbortController()
  try {
    const withCredential = source.value === 'PROVIDER_API' ? { credential_id: credentialId.value } : {}
    const withKeyword = keyword.value.trim() === '' ? {} : { keyword: keyword.value.trim() }
    const list = await fetchAvailableModels(providerId.value, { source: source.value, ...withCredential, ...withKeyword }, controller.signal)
    candidates.value = list
    selectedModelIds.value = []
  } catch (e) {
    if (!isAbortError(e)) loadError.value = toErrorMessage(e)
  } finally {
    loading.value = false
  }
}

function toggleCandidate(modelId: string, checked: boolean): void {
  selectedModelIds.value = checked
    ? [...selectedModelIds.value, modelId]
    : selectedModelIds.value.filter((item) => item !== modelId)
}

function toggleAll(checked: boolean): void {
  selectedModelIds.value = checked ? filteredCandidates.value.filter((item) => !item.existing).map((item) => item.model_id) : []
}

async function submitImport(): Promise<void> {
  if (!canSubmit.value) return
  submitting.value = true
  submitError.value = ''
  try {
    const importCredential = source.value === 'PROVIDER_API' ? { credential_id: credentialId.value } : {}
    result.value = await importProviderModels({
      provider_id: providerId.value,
      source: source.value,
      ...importCredential,
      model_ids: [...selectedModelIds.value],
      apply_known_defaults: applyKnownDefaults.value,
      enabled: importEnabled.value,
    })
  } catch (e) {
    submitError.value = toErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

function restart(): void {
  result.value = null
  candidates.value = []
  selectedModelIds.value = []
}

onMounted(async () => {
  try {
    providers.value = await fetchProviderOptions()
  } catch (e) {
    loadError.value = toErrorMessage(e)
  }
})
</script>

<template>
  <section class="lai-page">
    <h1 class="lai-page-title">
      导入模型
    </h1>

    <PageState
      v-if="providers.length === 0 && !loadError"
      status="loading"
    />
    <p
      v-else-if="loadError"
      class="lai-form-message-error"
      role="alert"
    >
      {{ loadError }}
    </p>

    <template v-if="result">
      <div class="lai-import-result">
        <h2 class="lai-section-title">
          导入结果
        </h2>
        <p>成功 {{ result.created.length }} 项 · 跳过 {{ result.skipped.length }} 项 · 失败 {{ result.failed.length }} 项</p>
        <div
          v-if="result.created.length > 0"
          class="lai-result-group"
        >
          <h3>已创建</h3>
          <ul>
            <li
              v-for="item in result.created"
              :key="item.model_id"
            >
              <RouterLink
                :to="`/ui/provider-models/${item.id}`"
                class="lai-link"
              >
                {{ item.model_id }}
              </RouterLink>
            </li>
          </ul>
        </div>
        <div
          v-if="result.skipped.length > 0"
          class="lai-result-group"
        >
          <h3>已跳过（已存在）</h3>
          <ul>
            <li
              v-for="item in result.skipped"
              :key="item.model_id"
            >
              {{ item.model_id }}：{{ item.reason }}
            </li>
          </ul>
        </div>
        <div
          v-if="result.failed.length > 0"
          class="lai-result-group"
        >
          <h3>失败</h3>
          <ul>
            <li
              v-for="item in result.failed"
              :key="item.model_id"
            >
              {{ item.model_id }}：{{ item.error }}
            </li>
          </ul>
        </div>
        <div class="lai-form-actions">
          <button
            type="button"
            class="lai-btn"
            @click="restart"
          >
            继续导入
          </button>
          <RouterLink
            to="/ui/provider-models"
            class="lai-btn lai-btn-primary"
          >
            返回模型列表
          </RouterLink>
        </div>
      </div>
    </template>

    <form
      v-else
      class="lai-import-form"
      @submit.prevent="submitImport"
    >
      <fieldset class="lai-fieldset">
        <legend class="lai-legend">
          1. 选择来源
        </legend>
        <div class="lai-form-grid">
          <FormField
            label="Provider"
            required
          >
            <select
              v-model="providerId"
              class="lai-input lai-select"
              @change="onProviderChange"
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
            label="来源"
            required
          >
            <select
              v-model="source"
              class="lai-input lai-select"
              @change="candidates = []; selectedModelIds = []"
            >
              <option
                v-for="item in sourceOptions"
                :key="item.value"
                :value="item.value"
              >
                {{ item.label }}
              </option>
            </select>
          </FormField>
          <FormField
            v-if="source === 'PROVIDER_API'"
            label="凭证"
            required
            :hint="credentialLoading ? '加载中…' : '只显示同 Provider 的非停用凭证'"
          >
            <select
              v-model="credentialId"
              class="lai-input lai-select"
            >
              <option
                value=""
                disabled
              >
                请选择凭证
              </option>
              <option
                v-for="item in credentialOptions"
                :key="item.id"
                :value="item.id"
              >
                {{ item.label }}
              </option>
            </select>
          </FormField>
          <FormField label="关键字">
            <input
              v-model="keyword"
              class="lai-input"
              type="text"
              placeholder="过滤 model_id / 名称"
              @input="loadCandidates"
            >
          </FormField>
        </div>
        <button
          type="button"
          class="lai-btn lai-btn-primary"
          :disabled="!canLoadCandidates || loading"
          @click="loadCandidates"
        >
          {{ loading ? '加载中…' : '获取模型列表' }}
        </button>
      </fieldset>

      <fieldset
        v-if="candidates.length > 0"
        class="lai-fieldset"
      >
        <legend class="lai-legend">
          2. 选择模型
        </legend>
        <div class="lai-import-toolbar">
          <label class="lai-switch">
            <input
              type="checkbox"
              :checked="filteredCandidates.length > 0 && selectedModelIds.length === filteredCandidates.filter((item) => !item.existing).length"
              @change="toggleAll(($event.target as HTMLInputElement).checked)"
            >
            全选未存在的模型
          </label>
          <span>已选 {{ selectedModelIds.length }} 项<template v-if="selectedExisting > 0">（含已存在 {{ selectedExisting }} 项，将被跳过）</template></span>
        </div>
        <div class="lai-table-wrap">
          <table class="lai-table">
            <thead>
              <tr>
                <th class="lai-col-check" />
                <th>model_id</th>
                <th>状态</th>
                <th>tokenizer</th>
                <th>上下文</th>
                <th>最大输出</th>
                <th>流式</th>
                <th>system</th>
                <th>temperature</th>
                <th>top_p</th>
                <th>stop</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in filteredCandidates"
                :key="item.model_id"
                :class="{ 'lai-row-existing': item.existing }"
              >
                <td>
                  <input
                    type="checkbox"
                    :checked="selectedModelIds.includes(item.model_id)"
                    :aria-label="`选择 ${item.model_id}`"
                    @change="toggleCandidate(item.model_id, ($event.target as HTMLInputElement).checked)"
                  >
                </td>
                <td class="lai-cell-mono">
                  {{ item.model_id }}
                </td>
                <td>{{ item.existing ? '已存在' : '新模型' }}</td>
                <td>{{ item.tokenizer_family ?? '待补充' }}</td>
                <td>{{ item.context_window?.toLocaleString('zh-CN') ?? '待补充' }}</td>
                <td>{{ item.max_output_tokens?.toLocaleString('zh-CN') ?? '待补充' }}</td>
                <td>{{ abilityText(item.support_stream, '支持', '不支持') }}</td>
                <td>{{ abilityText(item.support_system_message, '支持', '不支持') }}</td>
                <td>{{ abilityText(item.support_temperature, '支持', '不支持') }}</td>
                <td>{{ abilityText(item.support_top_p, '支持', '不支持') }}</td>
                <td>{{ abilityText(item.support_stop, '支持', '不支持') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="lai-hint">
          能力值为“待补充”的模型导入后保持停用，需在编辑页补齐能力后才能启用；
          预置默认值来自 Adapter 版本，不代表 Provider 实时承诺。
        </p>
      </fieldset>

      <fieldset
        v-if="candidates.length > 0"
        class="lai-fieldset"
      >
        <legend class="lai-legend">
          3. 导入选项
        </legend>
        <label class="lai-switch">
          <input
            v-model="applyKnownDefaults"
            type="checkbox"
          >
          应用已知默认值（false 时能力字段留空，逐个补全）
        </label>
        <label class="lai-switch">
          <input
            v-model="importEnabled"
            type="checkbox"
          >
          导入后启用（仅当能力完整时可用，默认关闭）
        </label>
        <p
          v-if="submitError"
          class="lai-form-message-error"
          role="alert"
        >
          {{ submitError }}
        </p>
        <div class="lai-form-actions">
          <button
            type="submit"
            class="lai-btn lai-btn-primary"
            :disabled="!canSubmit"
          >
            {{ submitting ? '导入中…' : `导入 ${selectedModelIds.length} 个模型` }}
          </button>
        </div>
      </fieldset>
    </form>
  </section>
</template>

<style scoped>
.lai-import-form,
.lai-import-result {
  max-width: 1080px;
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
  margin: 4px 24px 4px 0;
}
.lai-import-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 13px;
}
.lai-table-wrap {
  overflow-x: auto;
}
.lai-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.lai-table th,
.lai-table td {
  text-align: left;
  padding: 6px 10px;
  border-bottom: 1px solid #d8dee4;
  white-space: nowrap;
}
.lai-table th {
  color: #57606a;
  background: #f6f8fa;
}
.lai-col-check {
  width: 32px;
}
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-row-existing {
  color: #57606a;
}
.lai-hint {
  font-size: 12px;
  color: #57606a;
}
.lai-result-group {
  margin: 8px 0;
}
.lai-result-group h3 {
  font-size: 13px;
  margin: 0 0 4px;
}
.lai-result-group ul {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
}
.lai-form-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.lai-link {
  color: #0969da;
}
</style>
