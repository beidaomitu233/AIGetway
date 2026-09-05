<script setup lang="ts">
// 候选新增/编辑弹窗（FE-018，附录 4.2.8.2）：模型按 Provider 分组选择，
// 凭证池只显示与模型同 Provider 的池；编辑时模型只读。
import { computed, ref, watch } from 'vue'
import { toErrorMessage } from '@/api/errors'
import type { CredentialPoolOption, RouteCandidateDetail } from '@/api/modelAliases'

export interface ModelGroupOption {
  providerName: string
  models: { id: string; label: string; supportStream: boolean; contextWindow: number | null }[]
}

const props = withDefaults(
  defineProps<{
    open: boolean
    aliasId: string
    /** 编辑时传入候选；新建传 null。 */
    candidate: RouteCandidateDetail | null
    modelGroups: ModelGroupOption[]
    submitting?: boolean
    error?: unknown
    /** 凭证池选项按所选模型加载（调用方提供异步函数）。 */
    loadPools: (modelId: string) => Promise<CredentialPoolOption[]>
  }>(),
  {
    submitting: false,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [command: { provider_model_id: string; credential_pool_id: string; priority: number; weight: number; enabled: boolean; version?: number | undefined }]
}>()

const providerModelId = ref('')
const credentialPoolId = ref('')
const priority = ref(10)
const weight = ref(1)
const enabled = ref(true)
const poolOptions = ref<CredentialPoolOption[]>([])
const poolsLoading = ref(false)
const poolsError = ref('')

const isEdit = computed(() => props.candidate !== null)

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    poolsError.value = ''
    if (props.candidate) {
      providerModelId.value = props.candidate.provider_model_id
      credentialPoolId.value = props.candidate.credential_pool_id
      priority.value = props.candidate.priority
      weight.value = props.candidate.weight
      enabled.value = props.candidate.enabled
      await refreshPoolOptions(providerModelId.value)
    } else {
      providerModelId.value = ''
      credentialPoolId.value = ''
      priority.value = 10
      weight.value = 1
      enabled.value = true
      poolOptions.value = []
    }
  },
  { immediate: true },
)

async function onModelChange(): Promise<void> {
  credentialPoolId.value = ''
  poolOptions.value = []
  await refreshPoolOptions(providerModelId.value)
}

async function refreshPoolOptions(modelId: string): Promise<void> {
  if (modelId === '') {
    poolOptions.value = []
    return
  }
  poolsLoading.value = true
  poolsError.value = ''
  try {
    poolOptions.value = await props.loadPools(modelId)
  } catch (e) {
    poolsError.value = toErrorMessage(e)
  } finally {
    poolsLoading.value = false
  }
}

const modelInvalid = computed(() => providerModelId.value === '')
const poolInvalid = computed(() => credentialPoolId.value === '')
const priorityInvalid = computed(() => !Number.isInteger(priority.value) || priority.value < 1 || priority.value > 100)
const weightInvalid = computed(() => !Number.isInteger(weight.value) || weight.value < 1 || weight.value > 100)
const confirmDisabled = computed(
  () => props.submitting || modelInvalid.value || poolInvalid.value || priorityInvalid.value || weightInvalid.value,
)

function confirm(): void {
  if (confirmDisabled.value) return
  emit('confirm', {
    provider_model_id: providerModelId.value,
    credential_pool_id: credentialPoolId.value,
    priority: priority.value,
    weight: weight.value,
    enabled: enabled.value,
    version: isEdit.value ? props.candidate?.version : undefined,
  })
}

function close(): void {
  emit('update:open', false)
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="close"
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="isEdit ? '编辑候选' : '新增候选'"
      >
        <h2 class="lai-dialog-title">
          {{ isEdit ? '编辑候选' : '新增候选' }}
        </h2>

        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="lai-candidate-model"
          >Provider 模型<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <select
            id="lai-candidate-model"
            v-model="providerModelId"
            class="lai-input lai-select"
            :disabled="isEdit"
            @change="onModelChange"
          >
            <option
              value=""
              disabled
            >
              请选择模型
            </option>
            <optgroup
              v-for="group in modelGroups"
              :key="group.providerName"
              :label="group.providerName"
            >
              <option
                v-for="item in group.models"
                :key="item.id"
                :value="item.id"
              >
                {{ item.label }}
              </option>
            </optgroup>
          </select>
          <p
            v-if="isEdit"
            class="lai-form-hint"
          >
            模型创建后不可更换
          </p>
        </div>

        <div class="lai-form-field">
          <label
            class="lai-form-label"
            for="lai-candidate-pool"
          >凭证池<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <select
            id="lai-candidate-pool"
            v-model="credentialPoolId"
            class="lai-input lai-select"
            :disabled="providerModelId === ''"
          >
            <option
              value=""
              disabled
            >
              {{ poolsLoading ? '加载中…' : '请选择凭证池' }}
            </option>
            <option
              v-for="item in poolOptions"
              :key="item.id"
              :value="item.id"
            >
              {{ item.name }}（可用凭证 {{ item.credential_available }}）
            </option>
          </select>
          <p
            v-if="poolsError"
            class="lai-form-message-error"
          >
            {{ poolsError }}
          </p>
          <p
            v-else-if="providerModelId !== '' && poolOptions.length === 0 && !poolsLoading"
            class="lai-form-hint"
          >
            该模型所属 Provider 下没有可用凭证池
          </p>
        </div>

        <div class="lai-candidate-grid">
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-candidate-priority"
            >priority（1—100，越小越优先）</label>
            <input
              id="lai-candidate-priority"
              v-model.number="priority"
              class="lai-input"
              type="number"
              min="1"
              max="100"
            >
            <p
              v-if="priorityInvalid"
              class="lai-form-message-error"
            >
              范围为 1—100
            </p>
          </div>
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-candidate-weight"
            >weight（1—100）</label>
            <input
              id="lai-candidate-weight"
              v-model.number="weight"
              class="lai-input"
              type="number"
              min="1"
              max="100"
            >
            <p
              v-if="weightInvalid"
              class="lai-form-message-error"
            >
              范围为 1—100
            </p>
          </div>
        </div>

        <label class="lai-candidate-switch">
          <input
            v-model="enabled"
            type="checkbox"
          >
          启用（发布后参与路由）
        </label>

        <p
          v-if="error"
          class="lai-form-message-error"
          role="alert"
        >
          {{ toErrorMessage(error) }}
        </p>

        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            :disabled="submitting"
            @click="close"
          >
            取消
          </button>
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="confirmDisabled"
            @click="confirm"
          >
            {{ submitting ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lai-select {
  width: 100%;
  padding: 6px 8px;
}
.lai-candidate-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.lai-candidate-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  margin: 4px 0;
}
</style>
