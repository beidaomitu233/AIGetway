<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { formatDateTime } from '@/app/display'
import { type AccessCredentialDetail, fetchAccessCredential } from '@/api/accessCredentials'
import { isAbortError } from '@/api/errors'

const route = useRoute()
const store = useBootstrapStore()

const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const detail = ref<AccessCredentialDetail | null>(null)

async function load(): Promise<void> {
  const controller = new AbortController()
  state.value = 'loading'
  loadError.value = null
  try {
    detail.value = await fetchAccessCredential(route.params.id as string, controller.signal)
    state.value = 'ready'
  } catch (e) {
    if (isAbortError(e)) return
    loadError.value = e
    state.value = 'error'
  }
}
onMounted(load)

const statusLabels: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '已停用',
  EXPIRED: '已过期',
}

const scopeText = computed(() =>
  detail.value?.allowed_alias_ids.length === 0
    ? '全部已发布 Alias'
    : (detail.value?.allowed_alias_ids.length ?? 0) + ' 个 Alias',
)

const ipText = computed(() =>
  detail.value?.ip_allowlist.length === 0
    ? '不限制'
    : (detail.value?.ip_allowlist.length ?? 0) + ' 条规则',
)
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        访问凭证详情
      </h1>
      <RouterLink
        :to="{ name: 'access-list' }"
        class="lai-btn"
      >
        返回列表
      </RouterLink>
    </div>

    <PageState
      v-if="state === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="state === 'error'"
      status="error"
      :error="loadError"
      @retry="load"
    />
    <template v-else-if="detail">
      <div class="lai-card">
        <h2 class="lai-card-title">
          基础信息
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">名称</span>{{ detail.name }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">Token（脱敏）</span>
            <span class="lai-mono">{{ detail.masked_token }}</span>
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">应用</span>{{ detail.application }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">Alias 范围</span>{{ scopeText }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">IP 限制</span>{{ ipText }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">状态</span>{{ statusLabels[detail.status] ?? detail.status }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">有效期至</span>{{ formatDateTime(detail.expires_at, store.timezone, '长期有效') }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">Token 代次</span>{{ detail.rotation_generation }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">最近使用</span>{{ formatDateTime(detail.last_used_at, store.timezone, '未使用') }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">24h 调用</span>{{ detail.trace_count_24h }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">更新时间</span>{{ formatDateTime(detail.updated_at, store.timezone) }}
          </div>
        </div>
        <ul
          v-if="detail.ip_allowlist.length > 0"
          class="lai-related-list"
        >
          <li
            v-for="ip in detail.ip_allowlist"
            :key="ip"
            class="lai-mono"
          >
            {{ ip }}
          </li>
        </ul>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          最近 Trace（10 条）
        </h2>
        <table
          v-if="detail.recent_traces.length > 0"
          class="lai-table"
        >
          <thead>
            <tr>
              <th>时间</th>
              <th>Trace ID</th>
              <th>Alias</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="trace in detail.recent_traces"
              :key="trace.trace_id"
            >
              <td>{{ formatDateTime(trace.started_at, store.timezone) }}</td>
              <td>
                <RouterLink
                  :to="{ name: 'trace-detail', params: { traceId: trace.trace_id } }"
                  class="lai-link lai-mono"
                >
                  {{ trace.trace_id.slice(0, 18) }}{{ trace.trace_id.length > 18 ? '…' : '' }}
                </RouterLink>
              </td>
              <td>{{ trace.alias }}</td>
              <td>{{ trace.status }}</td>
            </tr>
          </tbody>
        </table>
        <p
          v-else
          class="lai-related-empty"
        >
          最近 24 小时无调用
        </p>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          最近审计摘要
        </h2>
        <table
          v-if="detail.audit_summary.length > 0"
          class="lai-table"
        >
          <thead>
            <tr>
              <th>时间</th>
              <th>操作</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(item, index) in detail.audit_summary"
              :key="index"
            >
              <td>{{ formatDateTime(item.created_at, store.timezone) }}</td>
              <td>{{ item.operation }}</td>
              <td>{{ item.result }}</td>
            </tr>
          </tbody>
        </table>
        <p
          v-else
          class="lai-related-empty"
        >
          暂无审计记录
        </p>
      </div>
    </template>
  </section>
</template>
