<script setup lang="ts">
import { Button, Input, Select } from 'ant-design-vue'
// 候选新增/编辑弹窗（FE-018，附录 4.2.8.2）：模型按 渠道 分组选择，
// 所属渠道只显示与模型模型所属渠道；编辑时模型只读。
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
    /** 所属渠道选项按所选模型加载（调用方提供异步函数）。 */
    loadPools: (modelId: string) => Promise<CredentialPoolOption[]>
  }>(),
  {
    submitting: false,
  },
)

const emit = defineEmits<{
  'update:open': [value: boolean]
  confirm: [command: { upstream_model_id: string; channel_id: string; priority: number; weight: number; enabled: boolean; version?: number | undefined }]
}>()

const providerModelId = ref('')
const credentialPoolId = ref('')
const priority = ref(10)
const weight = ref(1)
const enabled = ref(true)
const poolOptions = ref<CredentialPoolOption[]>([])
const poolSelectOptions = computed(() =>
  poolOptions.value.map((item) => ({ value: item.id, label: `${item.name}（启用 Key ${item.credential_available}）` })),
)
const groupedModelOptions = computed(() =>
  props.modelGroups.map((group) => ({
    label: group.providerName,
    options: group.models.map((item) => ({ value: item.id, label: item.label })),
  })),
)
const poolsLoading = ref(false)
const poolsError = ref('')

let poolRequest = 0
const isEdit = computed(() => props.candidate !== null)

watch(
  () => props.open,
  async (open) => {
    if (!open) { poolRequest++; poolsLoading.value = false; return }
    poolsError.value = ''
    if (props.candidate) {
      providerModelId.value = props.candidate.upstream_model_id
      credentialPoolId.value = props.candidate.channel_id
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
  const sequence = ++poolRequest
  if (modelId === '') {
    poolOptions.value = []
    return
  }
  poolsLoading.value = true
  poolsError.value = ''
  try {
    const options = await props.loadPools(modelId)
    if (sequence === poolRequest && props.open) poolOptions.value = options
  } catch (e) {
    if (sequence === poolRequest && props.open) poolsError.value = toErrorMessage(e)
  } finally {
    if (sequence === poolRequest) poolsLoading.value = false
  }
}

const modelInvalid = computed(() => providerModelId.value === '')
const poolInvalid = computed(() => credentialPoolId.value === '')
const priorityInvalid = computed(() => !Number.isInteger(priority.value) || priority.value < 1 || priority.value > 100)
const weightInvalid = computed(() => !Number.isInteger(weight.value) || weight.value < 0 || weight.value > 100)
const confirmDisabled = computed(
  () => !props.open || props.submitting || poolsLoading.value || !!poolsError.value || !poolOptions.value.some((pool) => pool.id === credentialPoolId.value && pool.credential_available > 0 && pool.status === 'ACTIVE') || modelInvalid.value || poolInvalid.value || priorityInvalid.value || weightInvalid.value,
)

function confirm(): void {
  if (confirmDisabled.value) return
  emit('confirm', {
    upstream_model_id: providerModelId.value,
    channel_id: credentialPoolId.value,
    priority: priority.value,
    weight: weight.value,
    enabled: enabled.value,
    version: isEdit.value ? props.candidate?.version : undefined,
  })
}

function close(): void {
  if (props.submitting) return
  poolRequest++
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
          >上游模型<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <Select
            id="lai-candidate-model"
            v-model:value="providerModelId"
            :disabled="isEdit"
            placeholder="请选择模型"
            :options="groupedModelOptions"
            @update:value="onModelChange"
          />
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
          >所属渠道<span
            class="lai-required"
            aria-hidden="true"
          >*</span></label>
          <Select
            id="lai-candidate-pool"
            v-model:value="credentialPoolId"
            :disabled="providerModelId === ''"
            :placeholder="poolsLoading ? '加载中…' : '请选择所属渠道'"
            :options="poolSelectOptions"
          />
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
            该模型所属渠道 下没有可用所属渠道
          </p>
        </div>

        <div class="lai-candidate-grid">
          <div class="lai-form-field">
            <label
              class="lai-form-label"
              for="lai-candidate-priority"
            >priority（1—100，越小越优先）</label>
            <Input id="lai-candidate-priority" :value="String(priority ?? '')" @update:value="(v: string) => { priority = v === '' ? 0 : Number(v) }" type="number" min="1" max="100" />
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
            >权重（0—100）</label>
            <Input id="lai-candidate-weight" :value="String(weight ?? '')" @update:value="(v: string) => { weight = v === '' ? 0 : Number(v) }" type="number" min="0" max="100" />
            <p
              v-if="weightInvalid"
              class="lai-form-message-error"
            >
              范围为 0—100
            </p>
          </div>
        </div>

        <p class="lai-form-hint">
          权重仅在同优先级比较；零权重不接收普通流量。保存仅形成草稿。
        </p>
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
          <Button
            :disabled="submitting"
            @click="close"
          >
            取消
          </Button>
          <Button
            type="primary"
            :disabled="confirmDisabled"
            @click="confirm"
          >
            {{ submitting ? '保存中…' : '保存' }}
          </Button>
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
