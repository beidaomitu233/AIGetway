<script setup lang="ts">
// 额度流水页（FE-222，FRONTEND_PLAN 第 2 节观测路由 /usage/adjustments）：
// 按应用查看额度调整与用量重置流水；应用范围由服务端按身份裁剪。
// 金额为十进制字符串，直接展示不做浮点运算。
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { Button, Card, Select, Table } from 'ant-design-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { useRoute, useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import {
  type ApplicationQuotaAdjustment,
  type ApplicationListItem,
  fetchApplicationQuotaAdjustments,
  fetchApplications,
} from '@/api/applications'
import { isAbortError } from '@/api/errors'
import { formatDateTime } from '@/app/display'

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()

const dimensionLabels: Record<string, string> = {
  TOKEN_LIMIT: 'Token 上限',
  AMOUNT_LIMIT: '金额上限',
  TOKEN_USAGE_RESET: 'Token 用量重置',
  AMOUNT_USAGE_RESET: '金额用量重置',
}

const applications = shallowRef<ApplicationListItem[]>([])
const applicationsState = ref<'loading' | 'ready' | 'error'>('loading')
const applicationsError = ref<unknown>(null)

const selectedApplicationId = ref('')
const adjustments = shallowRef<ApplicationQuotaAdjustment[]>([])
const adjustmentsState = ref<'loading' | 'ready' | 'error'>('loading')
const adjustmentsError = ref<unknown>(null)

let applicationSeq = 0
let adjustmentSeq = 0
let applicationController: AbortController | null = null
let adjustmentController: AbortController | null = null

async function loadApplications(): Promise<void> {
  const sequence = ++applicationSeq
  applicationController?.abort()
  applicationController = new AbortController()
  applicationsState.value = 'loading'
  applicationsError.value = null
  try {
    const result = await fetchApplications(
      { page: 1, page_size: 100, sort: '-updated_at' },
      applicationController.signal,
    )
    if (sequence !== applicationSeq) return
    applications.value = result.items
    applicationsState.value = 'ready'
    const requested = typeof route.query.application_id === 'string' ? route.query.application_id : ''
    const initial = result.items.find((item) => item.id === requested) ?? result.items[0]
    if (initial) {
      selectApplication(initial.id)
    } else {
      adjustments.value = []
      adjustmentsState.value = 'ready'
    }
  } catch (e) {
    if (sequence !== applicationSeq || isAbortError(e)) return
    applicationsError.value = e
    applicationsState.value = 'error'
  }
}

async function loadAdjustments(applicationId: string): Promise<void> {
  const sequence = ++adjustmentSeq
  adjustmentController?.abort()
  adjustmentController = new AbortController()
  adjustmentsState.value = 'loading'
  adjustmentsError.value = null
  try {
    const items = await fetchApplicationQuotaAdjustments(applicationId, adjustmentController.signal)
    if (sequence !== adjustmentSeq || selectedApplicationId.value !== applicationId) return
    adjustments.value = items
    adjustmentsState.value = 'ready'
  } catch (e) {
    if (sequence !== adjustmentSeq || isAbortError(e)) return
    adjustmentsError.value = e
    adjustmentsState.value = 'error'
  }
}

function selectApplication(applicationId: string): void {
  if (selectedApplicationId.value === applicationId) return
  selectedApplicationId.value = applicationId
  void router.replace({ query: { ...route.query, application_id: applicationId } })
  void loadAdjustments(applicationId)
}

function onApplicationChange(value: unknown): void {
  const applicationId = Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
  if (applicationId !== '') selectApplication(applicationId)
}

function retryAdjustments(): void {
  if (selectedApplicationId.value !== '') void loadAdjustments(selectedApplicationId.value)
}

onMounted(() => {
  void loadApplications()
})

onUnmounted(() => {
  applicationSeq++
  adjustmentSeq++
  applicationController?.abort()
  adjustmentController?.abort()
})

// 浏览器后退/前进还原应用选择
watch(() => route.query.application_id, (value) => {
  if (applicationsState.value !== 'ready') return
  const id = typeof value === 'string' ? value : ''
  if (id !== '' && id !== selectedApplicationId.value && applications.value.some((item) => item.id === id)) {
    selectApplication(id)
  }
})

const selectedApplication = computed(
  () => applications.value.find((item) => item.id === selectedApplicationId.value) ?? null,
)

const applicationOptions = computed(() =>
  applications.value.map((item) => ({
    value: item.id,
    label: `${item.name}（${item.code}）`,
    disabled: item.status === 'ARCHIVED',
  })),
)

const adjustmentColumns: ColumnsType<ApplicationQuotaAdjustment> = [
  { title: '生效时间', key: 'effective_at' },
  { title: '维度', key: 'dimension' },
  { title: '调整前', key: 'before_value' },
  { title: '变化', key: 'delta_value' },
  { title: '调整后', key: 'after_value' },
  { title: '原因', key: 'reason' },
  { title: '操作人', key: 'operator_id' },
  { title: '创建时间', key: 'created_at' },
]

function adjustmentValue(record: Record<string, unknown>, key: unknown): unknown {
  return typeof key === 'string' ? record[key] : ''
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        额度流水
      </h1>
      <div class="lai-row-actions">
        <RouterLink
          :to="{ name: 'usage' }"
        >
          <Button>返回用量与成本</Button>
        </RouterLink>
      </div>
    </div>

    <PageState
      v-if="applicationsState === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="applicationsState === 'error'"
      status="error"
      :error="applicationsError"
      @retry="loadApplications"
    />
    <PageState
      v-else-if="applications.length === 0"
      status="empty"
      message="当前身份没有可见的应用，无法查看额度流水"
    />
    <template v-else>
      <div class="lai-filter-bar">
        <Select
          class="lai-filter-select"
          aria-label="选择应用"
          :value="selectedApplicationId === '' ? undefined : selectedApplicationId"
          :options="applicationOptions"
          @change="onApplicationChange"
        />
      </div>

      <Card :bordered="false" class="lai-card">
        <h2 class="lai-card-title">
          调整与重置记录
        </h2>
        <p
          v-if="selectedApplication"
          class="lai-card-hint"
        >
          当前应用：{{ selectedApplication.name }}（{{ selectedApplication.code }}）· 流水按生效时间倒序展示
        </p>
        <PageState
          v-if="adjustmentsState === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="adjustmentsState === 'error'"
          status="error"
          :error="adjustmentsError"
          @retry="retryAdjustments"
        />
        <template v-else>
          <div class="lai-table-wrap">
            <Table
              :data-source="adjustments"
              :columns="adjustmentColumns"
              row-key="id"
              :pagination="false"
              :locale="{ emptyText: '暂无额度调整或重置记录' }"
              size="middle"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'effective_at'">{{ formatDateTime(record.effective_at, store.timezone) }}</template>
                <template v-else-if="column.key === 'dimension'">{{ dimensionLabels[record.dimension] ?? record.dimension }}</template>
                <template v-else-if="['before_value', 'delta_value', 'after_value', 'operator_id'].includes(String(column.key))"><span class="lai-mono">{{ adjustmentValue(record, column.key) }}</span></template>
                <template v-else-if="column.key === 'created_at'">{{ formatDateTime(record.created_at, store.timezone) }}</template>
                <template v-else>{{ adjustmentValue(record, column.key) }}</template>
              </template>
            </Table>
          </div>
        </template>
      </Card>
    </template>
  </section>
</template>
