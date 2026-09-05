<script setup lang="ts">
// 系统默认可靠性策略只读面板（FE-021，附录 4.3.2.3 查看默认策略）。
import { ref, shallowRef, watch } from 'vue'
import PageState from '@/components/PageState.vue'
import { fetchReliabilityDefault, type ReliabilityPolicyDetail } from '@/api/reliabilityPolicies'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const loading = ref(false)
const error = shallowRef<unknown>(null)
const detail = shallowRef<ReliabilityPolicyDetail | null>(null)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    loading.value = true
    error.value = null
    fetchReliabilityDefault()
      .then((data) => {
        detail.value = data
      })
      .catch((e: unknown) => {
        error.value = e
      })
      .finally(() => {
        loading.value = false
      })
  },
)

function close(): void {
  emit('update:open', false)
}

function row(label: string, value: string | number): { label: string; value: string } {
  return { label, value: String(value) }
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
        class="lai-dialog lai-default-panel"
        role="dialog"
        aria-modal="true"
        aria-label="系统默认可靠性策略"
      >
        <h2 class="lai-dialog-title">
          系统默认可靠性策略
        </h2>
        <p class="lai-dialog-message">
          停用策略的 Alias 使用以下内置默认值。来源标识 SYSTEM_DEFAULT，不允许编辑。
        </p>

        <PageState
          v-if="loading"
          status="loading"
        />
        <PageState
          v-else-if="error"
          status="error"
          :error="error"
          @retry="() => emit('update:open', false)"
        />
        <dl
          v-else-if="detail"
          class="lai-default-grid"
        >
          <div
            v-for="item in [
              row('连接超时', `${detail.connect_timeout_ms} ms`),
              row('首 Token 超时', `${detail.first_token_timeout_ms} ms`),
              row('总超时', `${detail.total_timeout_ms} ms`),
              row('最大重试', detail.max_retries),
              row('最大换密钥', detail.max_credential_failovers),
              row('初始退避', `${detail.initial_backoff_ms} ms`),
              row('退避倍数', detail.backoff_multiplier),
              row('抖动比例', `${detail.jitter_percent}%`),
              row('尊重 Retry-After', detail.respect_retry_after ? '是' : '否'),
              row('最大 Retry-After', `${detail.max_retry_after_ms} ms`),
              row('允许 Fallback', detail.fallback_enabled ? `是（${detail.max_fallbacks} 次）` : '否（0 次）'),
              row('熔断窗口', `${detail.circuit_window_seconds}s / ${detail.circuit_min_requests} 次`),
              row('失败率阈值', `${(Number(detail.circuit_failure_rate) * 100).toFixed(2)}%`),
              row('OPEN 时长', `${detail.circuit_open_seconds}s`),
              row('半开探测数', detail.circuit_half_open_probes),
              row('半开成功数', detail.circuit_half_open_successes),
            ]"
            :key="item.label"
            class="lai-default-row"
          >
            <dt>{{ item.label }}</dt>
            <dd>{{ item.value }}</dd>
          </div>
        </dl>

        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn"
            @click="close"
          >
            关闭
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lai-default-panel {
  width: 560px;
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 96px);
  overflow: auto;
}
.lai-default-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 24px;
  margin: 8px 0;
}
.lai-default-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid #eaeef2;
  font-size: 13px;
}
.lai-default-row dt {
  color: #57606a;
}
.lai-default-row dd {
  margin: 0;
}
</style>
