<script setup lang="ts">
// 额度流水页（FE-222，FRONTEND_PLAN 第 2 节观测路由 /usage/adjustments）：
// 按应用查看额度调整与用量重置流水；应用范围由服务端按身份裁剪。
// 金额为十进制字符串，直接展示不做浮点运算。
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
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

function onApplicationChange(event: Event): void {
  selectApplication((event.target as HTMLSelectElement).value)
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
    id: item.id,
    label: `${item.name}（${item.code}）`,
    disabled: item.status === 'ARCHIVED',
  })),
)
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        额度流水
      </h1>
      <div class="lai-row-actions">
        <RouterLink
          class="lai-btn"
          :to="{ name: 'usage' }"
        >
          返回用量与成本
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
        <select
          class="lai-select"
          aria-label="选择应用"
          :value="selectedApplicationId"
          @change="onApplicationChange"
        >
          <option
            v-for="option in applicationOptions"
            :key="option.id"
            :value="option.id"
            :disabled="option.disabled"
          >
            {{ option.label }}
          </option>
        </select>
      </div>

      <div class="lai-card">
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
            <table class="lai-table">
              <thead>
                <tr>
                  <th>生效时间</th>
                  <th>维度</th>
                  <th>调整前</th>
                  <th>变化</th>
                  <th>调整后</th>
                  <th>原因</th>
                  <th>操作人</th>
                  <th>创建时间</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="item in adjustments"
                  :key="item.id"
                >
                  <td>{{ formatDateTime(item.effective_at, store.timezone) }}</td>
                  <td>{{ dimensionLabels[item.dimension] ?? item.dimension }}</td>
                  <td class="lai-mono">
                    {{ item.before_value }}
                  </td>
                  <td class="lai-mono">
                    {{ item.delta_value }}
                  </td>
                  <td class="lai-mono">
                    {{ item.after_value }}
                  </td>
                  <td>{{ item.reason }}</td>
                  <td class="lai-mono">
                    {{ item.operator_id }}
                  </td>
                  <td>{{ formatDateTime(item.created_at, store.timezone) }}</td>
                </tr>
                <tr v-if="adjustments.length === 0">
                  <td
                    colspan="8"
                    class="lai-table-empty"
                  >
                    暂无额度调整或重置记录
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
    </template>
  </section>
</template>
