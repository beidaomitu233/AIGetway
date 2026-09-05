<script setup lang="ts">
// 恢复决策抽屉（FE-022，附录 4.3.2.3/4.3.5.2）：按动作筛选，
// 展示来源 Attempt、退避等待与累计预算；无正文，trace_id 跳转 Trace 详情。
import { ref, shallowRef, watch } from 'vue'
import PageState from '@/components/PageState.vue'
import ListPager from '@/components/ListPager.vue'
import {
  fetchRecoveryDecisions,
  type RecoveryDecision,
  type ReliabilityPolicyListItem,
} from '@/api/reliabilityPolicies'
import { isAbortError } from '@/api/errors'
import { recoveryActionLabel } from '@/app/display'

const props = defineProps<{
  open: boolean
  policy: ReliabilityPolicyListItem | null
}>()

const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const actionFilter = ref('')
const page = ref(1)
const pageSize = ref(20)
const items = shallowRef<RecoveryDecision[]>([])
const total = ref(0)
const status = ref<'loading' | 'ready' | 'error'>('loading')
const refreshing = ref(false)
const error = shallowRef<unknown>(null)

let seq = 0
let controller: AbortController | null = null

async function load(): Promise<void> {
  if (!props.policy) return
  const current = ++seq
  controller?.abort()
  controller = new AbortController()
  if (items.value.length > 0) refreshing.value = true
  try {
    const result = await fetchRecoveryDecisions(
      props.policy.id,
      {
        action: actionFilter.value === '' ? undefined : actionFilter.value,
        page: page.value,
        page_size: pageSize.value,
      },
      controller.signal,
    )
    if (current !== seq) return
    items.value = result.items
    total.value = result.total
    error.value = null
    status.value = 'ready'
    refreshing.value = false
  } catch (e) {
    if (current !== seq || isAbortError(e)) return
    error.value = e
    status.value = 'error'
    refreshing.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      page.value = 1
      void load()
    }
  },
)

function close(): void {
  emit('update:open', false)
}

const actionOptions: { value: string; label: string }[] = [
  { value: '', label: '全部动作' },
  { value: 'RETRY', label: recoveryActionLabel('RETRY') },
  { value: 'CREDENTIAL_FAILOVER', label: recoveryActionLabel('CREDENTIAL_FAILOVER') },
  { value: 'FALLBACK', label: recoveryActionLabel('FALLBACK') },
  { value: 'FAIL', label: recoveryActionLabel('FAIL') },
]

function budgetText(row: RecoveryDecision): string {
  return `重试 ${row.retries_used} / 换密钥 ${row.credential_failovers_used} / 切换 ${row.fallbacks_used}`
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
        class="lai-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="近期恢复决策"
      >
        <div class="lai-drawer-header">
          <h2 class="lai-drawer-title">
            近期恢复决策：{{ policy?.name ?? '' }}
          </h2>
          <button
            type="button"
            class="lai-btn"
            @click="close"
          >
            关闭
          </button>
        </div>

        <select
          v-model="actionFilter"
          class="lai-input lai-filter-select"
          @change="page = 1; load()"
        >
          <option
            v-for="item in actionOptions"
            :key="item.value"
            :value="item.value"
          >
            {{ item.label }}
          </option>
        </select>

        <PageState
          v-if="status === 'loading'"
          status="loading"
        />
        <PageState
          v-else-if="status === 'error'"
          status="error"
          :error="error"
          @retry="load"
        />
        <PageState
          v-else-if="items.length === 0"
          status="empty"
          message="没有匹配的恢复决策"
        />
        <template v-else>
          <div class="lai-table-wrap">
            <table class="lai-table">
              <thead>
                <tr>
                  <th>trace_id</th>
                  <th>动作</th>
                  <th>原因码</th>
                  <th>来源 Attempt</th>
                  <th>等待</th>
                  <th>累计预算</th>
                  <th>剩余总超时</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in items"
                  :key="row.id"
                >
                  <td class="lai-cell-mono">
                    <RouterLink
                      :to="`/ui/traces/${row.trace_id}`"
                      class="lai-link"
                    >
                      {{ row.trace_id }}
                    </RouterLink>
                  </td>
                  <td>{{ recoveryActionLabel(row.action) }}</td>
                  <td class="lai-cell-mono">
                    {{ row.reason_code }}
                  </td>
                  <td>
                    <span
                      v-if="row.source_attempt"
                      class="lai-cell-mono"
                    >
                      #{{ row.source_attempt.sequence }} {{ row.source_attempt.attempt_type }}
                      <span class="lai-cell-sub">{{ row.source_attempt.error_code ?? '—' }}</span>
                    </span>
                    <span v-else>—</span>
                  </td>
                  <td>{{ row.scheduled_delay_ms }} ms</td>
                  <td>{{ budgetText(row) }}</td>
                  <td>{{ row.remaining_timeout_ms }} ms</td>
                </tr>
              </tbody>
            </table>
          </div>
          <ListPager
            :page="page"
            :page-size="pageSize"
            :total="total"
            :disabled="refreshing"
            @update:page="page = $event; load()"
            @update:page-size="pageSize = $event; page = 1; load()"
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
  width: 860px;
  max-width: 94vw;
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
.lai-filter-select {
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
.lai-cell-sub {
  display: block;
  font-size: 12px;
  color: #57606a;
}
.lai-link {
  color: #0969da;
}
</style>
