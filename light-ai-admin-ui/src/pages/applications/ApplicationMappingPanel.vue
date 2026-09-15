<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Button, Card, Input, Select, Tag, Textarea } from 'ant-design-vue'
import PageState from '@/components/PageState.vue'
import { ApiError, isAbortError } from '@/api/errors'
import {
  bulkCreateApplicationMappings,
  fetchApplicationMappings,
  replaceApplicationMappings,
  validateApplicationMappings,
  type ApplicationMapping,
  type ApplicationMappingPayload,
  type ApplicationMappingsView,
} from '@/api/applications'
import { fetchProviderOptions, type ProviderOption } from '@/api/providerModels'
import { useFormSubmit } from '@/composables/useFormSubmit'

const props = defineProps<{
  active: boolean
  applicationId: string
  applicationVersion: string
  canManage: boolean
}>()

const emit = defineEmits<{
  changed: []
}>()

const view = ref<ApplicationMappingsView | null>(null)
const loading = ref(false)
const loadError = ref<unknown>(null)
const editing = ref(false)
const bulkOpen = ref(false)
const saving = useFormSubmit()
const draft = ref<ApplicationMappingPayload[]>([])
const reason = ref('')
const validationIssues = ref<string[]>([])
const channels = ref<ProviderOption[]>([])
const channelsLoading = ref(false)
const channelsError = ref<unknown>(null)
const selectedChannels = ref<string[]>([])
const bulkQuery = ref('')

const activeMappings = computed(() => (view.value?.mappings ?? []).filter(item => item.status === 'ACTIVE'))
const targetCount = computed(() => activeMappings.value.reduce((count, item) => count + item.targets.filter(target => target.status === 'ACTIVE').length, 0))
const applicationVersionNumber = computed(() => Number(props.applicationVersion))

function cloneMappings(items: ApplicationMapping[]): ApplicationMappingPayload[] {
  return items.map(item => ({
    id: item.id,
    public_model_name: item.public_model_name,
    status: item.status,
    targets: item.targets.map(target => ({
      channel_id: target.channel_id,
      upstream_model_id: target.upstream_model_id,
      upstream_model_name: target.upstream_model_name,
      priority: target.priority,
      weight: target.weight,
      status: target.status,
      policy_json: target.policy_json,
    })),
  }))
}

async function load(): Promise<void> {
  if (!props.active || !props.applicationId) return
  loading.value = true
  loadError.value = null
  try {
    view.value = await fetchApplicationMappings(props.applicationId)
  } catch (error) {
    if (!isAbortError(error)) loadError.value = error
  } finally {
    loading.value = false
  }
}

async function ensureChannels(): Promise<void> {
  if (channels.value.length > 0 || channelsLoading.value) return
  channelsError.value = null
  channelsLoading.value = true
  try {
    channels.value = (await fetchProviderOptions()) ?? []
  } catch (error) {
    if (!isAbortError(error)) channelsError.value = error
  } finally {
    channelsLoading.value = false
  }
}

function openEditor(): void {
  if (!props.canManage) return
  void ensureChannels()
  draft.value = cloneMappings(view.value?.mappings ?? [])
  validationIssues.value = []
  reason.value = ''
  editing.value = true
}

function addMapping(): void {
  draft.value.push({
    id: null,
    public_model_name: '',
    status: 'ACTIVE',
    targets: [],
  })
}

function removeMapping(index: number): void {
  draft.value.splice(index, 1)
}

function addTarget(mapping: ApplicationMappingPayload): void {
  mapping.targets.push({
    channel_id: selectedChannels.value[0] ?? channels.value[0]?.id ?? '',
    upstream_model_id: null,
    upstream_model_name: '',
    priority: 10,
    weight: 1,
    status: 'ACTIVE',
    policy_json: null,
  })
}

function removeTarget(mapping: ApplicationMappingPayload, index: number): void {
  mapping.targets.splice(index, 1)
}

async function openBulk(): Promise<void> {
  if (!props.canManage) return
  bulkOpen.value = true
  channelsError.value = null
  if (channels.value.length > 0) return
  await ensureChannels()
}

function mergeBulk(items: ApplicationMappingPayload[]): void {
  for (const item of items) {
    const existing = draft.value.find(mapping => mapping.public_model_name === item.public_model_name)
    if (!existing) {
      draft.value.push(item)
      continue
    }
    for (const target of item.targets) {
      const duplicate = existing.targets.some(current =>
        current.channel_id === target.channel_id
        && (current.upstream_model_id ?? current.upstream_model_name) === (target.upstream_model_id ?? target.upstream_model_name))
      if (!duplicate) existing.targets.push(target)
    }
  }
}

async function applyBulk(): Promise<void> {
  if (!selectedChannels.value.length) return
  channelsError.value = null
  try {
    const items = await bulkCreateApplicationMappings(props.applicationId, {
      channel_ids: selectedChannels.value,
      query: bulkQuery.value.trim() || undefined,
      limit: 500,
    })
    mergeBulk(items)
    bulkOpen.value = false
    editing.value = true
  } catch (error) {
    channelsError.value = error
  }
}

async function save(): Promise<void> {
  if (!props.canManage || !reason.value.trim() || !Number.isSafeInteger(applicationVersionNumber.value)) return
  validationIssues.value = []
  const payload = {
    application_version: applicationVersionNumber.value,
    mappings: draft.value,
  }
  try {
    const validation = await validateApplicationMappings(props.applicationId, payload)
    if (!validation.valid) {
      validationIssues.value = validation.issues
      return
    }
    let saved: ApplicationMappingsView | null = null
    const result = await saving.submit(async () => {
      const response = await replaceApplicationMappings(props.applicationId, {
        ...payload,
        reason: reason.value.trim(),
      })
      saved = response.entity
    })
    if (!result.ok) return
    editing.value = false
    view.value = saved ?? view.value
    emit('changed')
  } catch (error) {
    if (error instanceof ApiError) validationIssues.value = [error.message]
    else validationIssues.value = ['模型映射保存失败，请稍后重试']
  }
}

watch(() => [props.active, props.applicationId, props.applicationVersion], () => {
  if (props.active) void load()
}, { immediate: true })
</script>

<template>
  <Card :bordered="false" class="lai-card mapping-panel">
    <div class="card-heading">
      <div>
        <h2 class="lai-card-title">
          模型映射
        </h2>
        <p class="lai-card-hint">
          应用请求中的模型名映射到渠道真实模型；同一模型可以配置多个目标，用优先级和权重控制主备与分流。
        </p>
      </div>
      <div class="compact-actions">
        <Button size="small" :disabled="loading" @click="load">刷新</Button>
        <Button v-if="canManage" size="small" @click="openBulk">从渠道目录批量添加</Button>
        <Button v-if="canManage" type="primary" size="small" @click="openEditor">编辑映射</Button>
      </div>
    </div>

    <PageState v-if="loading" status="loading" />
    <PageState v-else-if="loadError" status="error" :error="loadError" @retry="load" />
    <template v-else>
      <div class="mapping-summary">
        <span>当前版本 {{ view?.revision ?? 0 }}</span>
        <span>{{ activeMappings.length }} 个对外模型</span>
        <span>{{ targetCount }} 个启用目标</span>
      </div>
      <div v-if="activeMappings.length" class="mapping-list">
        <div v-for="mapping in activeMappings" :key="mapping.id" class="mapping-row">
          <div>
            <strong>{{ mapping.public_model_name }}</strong>
            <small>{{ mapping.targets.length }} 个目标 · 版本 {{ mapping.version }}</small>
          </div>
          <div class="mapping-targets">
            <Tag v-for="target in mapping.targets.filter(item => item.status === 'ACTIVE')" :key="target.id">
              P{{ target.priority }} / W{{ target.weight }} · {{ target.upstream_model_name }}
            </Tag>
          </div>
        </div>
      </div>
      <p v-else class="empty-inline">
        尚未配置模型映射。可从渠道目录批量生成同名映射，再按应用需要调整目标。
      </p>
    </template>
    <p class="card-note">
      保存前会校验渠道归属、模型引用、重复目标、优先级和权重；旧版本保留用于运行中请求和历史审计追溯。
    </p>
  </Card>

  <div v-if="bulkOpen" class="lai-dialog-overlay" @click.self="bulkOpen = false">
    <div class="lai-dialog mapping-dialog" role="dialog" aria-modal="true" aria-labelledby="mapping-bulk-title">
      <h2 id="mapping-bulk-title" class="lai-dialog-title">从渠道目录批量生成映射</h2>
      <p class="lai-dialog-message">选择渠道并可选填模型关键词，系统只生成草案，不会直接保存。</p>
      <p v-if="channelsError" class="lai-form-message-error" role="alert">渠道目录读取失败，请重试。</p>
      <label class="lai-dialog-field">
        <span>渠道</span>
        <Select
          v-model:value="selectedChannels"
          mode="multiple"
          :loading="channelsLoading"
          :options="channels.map(channel => ({ value: channel.id, label: channel.name }))"
          placeholder="请选择一个或多个渠道"
        />
      </label>
      <label class="lai-dialog-field">
        <span>模型关键词</span>
        <Input v-model:value="bulkQuery" placeholder="留空表示读取所选渠道的全部模型" />
      </label>
      <div class="lai-dialog-actions">
        <Button @click="bulkOpen = false">取消</Button>
        <Button type="primary" :loading="channelsLoading" :disabled="!selectedChannels.length" @click="applyBulk">生成草案</Button>
      </div>
    </div>
  </div>

  <div v-if="editing" class="lai-dialog-overlay" @click.self="editing = false">
    <div class="lai-dialog mapping-dialog" role="dialog" aria-modal="true" aria-labelledby="mapping-editor-title">
      <h2 id="mapping-editor-title" class="lai-dialog-title">编辑模型映射</h2>
      <p class="lai-dialog-message">对外模型名是应用请求中的稳定名称；目标支持优先级、权重、启停和重试/熔断策略 JSON。</p>
      <div class="mapping-editor">
        <div v-for="(mapping, mappingIndex) in draft" :key="mapping.id ?? 'new-' + mappingIndex" class="mapping-editor-row">
          <div class="mapping-editor-heading">
            <Input v-model:value="mapping.public_model_name" placeholder="对外模型名，例如 assistant" />
            <Select v-model:value="mapping.status" :options="[{ value: 'ACTIVE', label: '启用' }, { value: 'DISABLED', label: '停用' }]" />
            <Button danger size="small" @click="removeMapping(mappingIndex)">删除</Button>
          </div>
          <div v-for="(target, targetIndex) in mapping.targets" :key="target.id ?? targetIndex" class="mapping-target-editor">
            <Select v-model:value="target.channel_id" :options="channels.map(channel => ({ value: channel.id, label: channel.name }))" placeholder="渠道" />
            <Input :value="target.upstream_model_id ?? ''" placeholder="真实模型 ID（可选）" @update:value="(value: string) => { target.upstream_model_id = value }" />
            <Input v-model:value="target.upstream_model_name" placeholder="真实模型名" />
            <Input v-model:value="target.priority" type="number" min="1" max="1000" placeholder="优先级" />
            <Input v-model:value="target.weight" type="number" min="1" max="1000" placeholder="权重" />
            <Select v-model:value="target.status" :options="[{ value: 'ACTIVE', label: '启用' }, { value: 'DISABLED', label: '停用' }]" />
            <Input :value="target.policy_json ?? ''" placeholder="策略 JSON（可选）" @update:value="(value: string) => { target.policy_json = value }" />
            <Button danger size="small" @click="removeTarget(mapping, targetIndex)">移除目标</Button>
          </div>
          <Button size="small" @click="addTarget(mapping)">新增目标</Button>
        </div>
        <Button type="dashed" block @click="addMapping">新增映射</Button>
      </div>
      <label class="lai-dialog-field">
        <span>变更原因</span>
        <Textarea v-model:value="reason" :rows="3" :maxlength="500" placeholder="必填，将写入审计记录" />
      </label>
      <p v-if="validationIssues.length" class="lai-form-message-error" role="alert">{{ validationIssues.join('；') }}</p>
      <div class="lai-dialog-actions">
        <Button :disabled="saving.submitting.value" @click="editing = false">取消</Button>
        <Button type="primary" :loading="saving.submitting.value" :disabled="saving.submitting.value || !reason.trim()" @click="save">保存映射</Button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mapping-dialog { width: min(980px, calc(100vw - 32px)); max-width: 980px; }
.mapping-summary { display: flex; gap: 18px; margin-bottom: 12px; color: #667085; font-size: 13px; }
.mapping-list { border-top: 1px solid #e6eaf0; }
.mapping-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid #e6eaf0; }
.mapping-row > div:first-child { display: flex; flex-direction: column; gap: 4px; }
.mapping-row small { color: #667085; }
.mapping-targets { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.mapping-editor { max-height: 420px; overflow: auto; margin-bottom: 14px; }
.mapping-editor-row { padding: 12px 0; border-bottom: 1px solid #e6eaf0; }
.mapping-editor-heading, .mapping-target-editor { display: grid; grid-template-columns: 1fr 140px auto; gap: 8px; align-items: center; margin-bottom: 8px; }
.mapping-target-editor { grid-template-columns: 150px 1fr 1fr 90px 90px 110px 1fr auto; }
@media (max-width: 900px) { .mapping-target-editor { grid-template-columns: 1fr 1fr; } .mapping-row { align-items: flex-start; flex-direction: column; } .mapping-targets { justify-content: flex-start; } }
</style>



