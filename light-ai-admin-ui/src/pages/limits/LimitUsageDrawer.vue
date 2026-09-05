<script setup lang="ts">
// 实时用量与 FIFO 队列抽屉（FE-020，附录 4.3.5.1）：只读展示当前窗口用量与等待记录；
// CAPACITY_STATE_UNAVAILABLE 时保留上次成功数据并标明更新时间；V1.0 不提供管理端取消排队。
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import {
  fetchLimitQueue,
  fetchLimitUsage,
  type LimitPolicyListItem,
  type LimitUsageSnapshot,
  type QueueEntry,
} from '@/api/limitPolicies'
import { isAbortError } from '@/api/errors'
import { counterStoreStatusLabel, queueStatusLabel } from '@/app/display'
import { toErrorMessage } from '@/api/errors'

const props = defineProps<{
  open: boolean
  policy: LimitPolicyListItem | null
}>()

const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const usage = shallowRef<LimitUsageSnapshot | null>(null)
const usageError = ref('')
const items = shallowRef<QueueEntry[]>([])
const total = ref(0)
const statusFilter = ref('')
const page = ref(1)
const pageSize = ref(10)
const listStatus = ref<'loading' | 'ready' | 'error'>('loading')
const listError = shallowRef<unknown>(null)
const refreshing = ref(false)

let seq = 0
let controller: AbortController | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null

async function loadUsage(): Promise<void> {
  if (!props.policy) return
  try {
    usage.value = await fetchLimitUsage(props.policy.id)
    usageError.value = ''
  } catch (e) {
    // CAPACITY_STATE_UNAVAILABLE：保留上次成功数据并提示（附录 4.3.5.1）
    usageError.value = toErrorMessage(e)
  }
}

async function loadQueue(): Promise<void> {
  if (!props.policy) return
  const current = ++seq
  controller?.abort()
  controller = new AbortController()
  if (items.value.length > 0) refreshing.value = true
  try {
    const result = await fetchLimitQueue(
      props.policy.id,
      {
        status: statusFilter.value === '' ? undefined : statusFilter.value,
        page: page.value,
        page_size: pageSize.value,
      },
      controller.signal,
    )
    if (current !== seq) return
    items.value = result.items
    total.value = result.total
    listError.value = null
    listStatus.value = 'ready'
    refreshing.value = false
  } catch (e) {
    if (current !== seq || isAbortError(e)) return
    listError.value = e
    listStatus.value = 'error'
    refreshing.value = false
  }
}

function load(): void {
  void loadUsage()
  void loadQueue()
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      page.value = 1
      load()
    }
  },
)

onMounted(() => {
  refreshTimer = setInterval(() => {
    if (props.open && document.visibilityState === 'visible') load()
  }, 5000)
})
onBeforeUnmount(() => {
  if (refreshTimer !== null) clearInterval(refreshTimer)
  controller?.abort()
})

function percent(used: number, limit: number | null): string {
  if (limit == null || limit === 0) return '—'
  return `${Math.round((used / limit) * 100)}%`
}

function close(): void {
  emit('update:open', false)
}

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'WAITING', label: queueStatusLabel('WAITING') },
  { value: 'ACQUIRED', label: queueStatusLabel('ACQUIRED') },
  { value: 'TIMEOUT', label: queueStatusLabel('TIMEOUT') },
  { value: 'REJECTED', label: queueStatusLabel('REJECTED') },
  { value: 'CANCELLED', label: queueStatusLabel('CANCELLED') },
]

const usageRows = computed(() => {
  const snapshot = usage.value
  if (!snapshot) return []
  return [
    { label: 'RPM（已用 / 上限）', value: `${snapshot.rpm_used} / ${snapshot.rpm_limit ?? '不限制'}（${percent(snapshot.rpm_used, snapshot.rpm_limit)}）` },
    { label: 'TPM 预占', value: snapshot.tpm_limit == null ? '不限制' : `${snapshot.tpm_reserved} / ${snapshot.tpm_limit}（${percent(snapshot.tpm_reserved, snapshot.tpm_limit)}）` },
    { label: 'TPM 已确认', value: snapshot.tpm_limit == null ? '不限制' : `${snapshot.tpm_confirmed} / ${snapshot.tpm_limit}（${percent(snapshot.tpm_confirmed, snapshot.tpm_limit)}）` },
    { label: '并发（当前 / 上限）', value: `${snapshot.concurrency_used} / ${snapshot.concurrent_limit ?? '不限制'}` },
    { label: '排队长度', value: `${snapshot.queue_length}${snapshot.queue_max_size != null ? ` / ${snapshot.queue_max_size}` : ''}` },
    { label: '窗口结束', value: snapshot.window_end ?? '—' },
    { label: '计数存储', value: counterStoreStatusLabel(snapshot.counter_store_status) },
  ]
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc="close"
    >
      <div
        class="lai-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="实时用量与排队"
      >
        <div class="lai-drawer-header">
          <h2 class="lai-drawer-title">
            实时用量与排队：{{ policy?.name ?? '' }}
          </h2>
          <button
            type="button"
            class="lai-btn"
            @click="close"
          >
            关闭
          </button>
        </div>

        <p
          v-if="usageError"
          class="lai-form-message-error"
          role="alert"
        >
          {{ usageError }}（以下为最近一次成功数据）
        </p>

        <PageState
          v-if="!usage && !usageError"
          status="loading"
        />
        <dl
          v-else-if="usage"
          class="lai-usage-list"
        >
          <div
            v-for="row in usageRows"
            :key="row.label"
            class="lai-usage-row"
          >
            <dt>{{ row.label }}</dt>
            <dd class="lai-cell-mono">
              {{ row.value }}
            </dd>
          </div>
        </dl>
        <p
          v-if="usage"
          class="lai-updated-at"
        >
          数据更新时间：{{ usage.data_updated_at }}
        </p>

        <h3 class="lai-section-title">
          等待队列
        </h3>
        <select
          v-model="statusFilter"
          class="lai-input lai-queue-filter"
          @change="page = 1; loadQueue()"
        >
          <option
            v-for="item in statusOptions"
            :key="item.value"
            :value="item.value"
          >
            {{ item.label }}
          </option>
        </select>

        <PageState
          v-if="listStatus === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="listStatus === 'error'"
          status="error"
          :error="listError"
          @retry="loadQueue"
        />
        <PageState
          v-else-if="items.length === 0"
          status="empty"
          message="没有匹配的排队记录"
        />
        <template v-else>
          <div class="lai-table-wrap">
            <table class="lai-table">
              <thead>
                <tr>
                  <th>sequence</th>
                  <th>trace_id</th>
                  <th>状态</th>
                  <th>预估 Token</th>
                  <th>入队时间</th>
                  <th>截止时间</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in items"
                  :key="row.id"
                >
                  <td class="lai-cell-mono">
                    {{ row.sequence }}
                  </td>
                  <td class="lai-cell-mono">
                    <RouterLink
                      :to="`/ui/traces/${row.trace_id}`"
                      class="lai-link"
                    >
                      {{ row.trace_id }}
                    </RouterLink>
                  </td>
                  <td>{{ queueStatusLabel(row.status) }}</td>
                  <td>{{ row.estimated_tokens }}</td>
                  <td>{{ row.enqueued_at }}</td>
                  <td>{{ row.deadline_at ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <ListPager
            :page="page"
            :page-size="pageSize"
            :total="total"
            :disabled="refreshing"
            @update:page="page = $event; loadQueue()"
            @update:page-size="pageSize = $event; page = 1; loadQueue()"
          />
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lai-drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 640px;
  max-width: 90vw;
  background: #fff;
  box-shadow: -4px 0 16px rgb(0 0 0 / 10%);
  padding: 16px 20px;
  overflow: auto;
}
.lai-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.lai-drawer-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}
.lai-usage-list {
  margin: 8px 0 16px;
}
.lai-usage-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid #eaeef2;
  font-size: 13px;
}
.lai-usage-row dt {
  color: #57606a;
}
.lai-usage-row dd {
  margin: 0;
}
.lai-section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 12px 0 8px;
}
.lai-queue-filter {
  width: auto;
  padding: 4px 8px;
  margin-bottom: 8px;
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
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-link {
  color: #0969da;
}
.lai-updated-at {
  font-size: 12px;
  color: #57606a;
}
</style>
