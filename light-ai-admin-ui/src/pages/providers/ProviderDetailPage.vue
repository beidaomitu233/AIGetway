<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import StatusText from '@/components/StatusText.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import CheckDialog from '@/components/CheckDialog.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useLifecycleActions } from '@/composables/useLifecycleActions'
import {
  checkStatusLabels,
  connectionStatusLabels,
  formatDateTime,
  formatDuration,
  poolStatusLabels,
} from '@/app/display'
import { Permission } from '@/app/permissions'
import {
  type ProviderCheckRecord,
  type ProviderDetail,
  checkProvider,
  disableProvider,
  enableProvider,
  deleteProvider,
  getProvider,
  getProviderImpact,
} from '@/api/providers'
import { listProviderModels } from '@/api/providerModels'
import { listPools } from '@/api/credentialPools'
import { ApiError, isAbortError } from '@/api/errors'

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()

const providerId = computed(() => route.params.id as string)
const canManage = computed(() => store.can(Permission.providerManage))
const canCheck = computed(() => store.can(Permission.providerCheck))

const state = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const detail = ref<ProviderDetail | null>(null)
const relatedPools = ref<Array<{ id: string; name: string; status: string }>>([])
const relatedModels = ref<Array<{ id: string; display_name: string; model_id: string; connection_status: string }>>([])

let loadController: AbortController | null = null

async function load(): Promise<void> {
  loadController?.abort()
  loadController = new AbortController()
  state.value = 'loading'
  error.value = null
  try {
    const signal = loadController.signal
    const [detailData, pools, models] = await Promise.all([
      getProvider(providerId.value, signal),
      listPools({ provider_id: providerId.value, page: 1, page_size: 10 }, signal).catch(() => null),
      listProviderModels({ provider_id: providerId.value, page: 1, page_size: 10 }, signal).catch(() => null),
    ])
    detail.value = detailData
    relatedPools.value = pools ? pools.items : []
    relatedModels.value = models ? models.items : []
    state.value = 'ready'
  } catch (e) {
    if (isAbortError(e)) return
    error.value = e
    state.value = 'error'
  }
}
onMounted(load)
onMounted(() => void store.refreshDraftSummary())
watch(providerId, () => void load())

const lifecycle = useLifecycleActions({
  getImpact: getProviderImpact,
  enable: (id, version) => enableProvider(id, version),
  disable: (id, version, confirmed) => disableProvider(id, version, confirmed),
  remove: (id, version, confirmed) => deleteProvider(id, version, confirmed),
  onChanged: () => {
    void load()
    void store.refreshDraftSummary()
  },
})

const checkOpen = ref(false)
const checkLoading = ref(false)
const checkResult = ref<ProviderCheckRecord | null>(null)
const checkErrorText = ref('')

const checkTarget = computed(() => ({
  models: relatedModels.value.map((model) => ({ id: model.id, label: model.display_name })),
  credentials: [],
}))

function openCheck(): void {
  checkResult.value = null
  checkErrorText.value = ''
  checkOpen.value = true
}

// 列表页“检测”跳转到详情并自动打开检测弹窗
watch(
  () => route.query.check,
  (value) => {
    if (value === '1' && state.value === 'ready') {
      openCheck()
      void router.replace({ query: { ...route.query, check: undefined } })
    }
  },
  { immediate: true },
)

async function submitCheck(command: Parameters<typeof checkProvider>[1]): Promise<void> {
  checkLoading.value = true
  checkErrorText.value = ''
  try {
    checkResult.value = await checkProvider(providerId.value, command)
    await load()
  } catch (e) {
    if (e instanceof ApiError) {
      checkErrorText.value = `${e.message}（${e.code}）`
    } else {
      checkErrorText.value = '检测请求失败，请稍后重试'
    }
  } finally {
    checkLoading.value = false
  }
}

const headerRows = computed(() => Object.entries(detail.value?.default_headers ?? {}))
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        Provider 详情
      </h1>
      <div class="lai-row-actions">
        <button
          type="button"
          class="lai-btn"
          @click="router.back()"
        >
          返回
        </button>
        <template v-if="detail">
          <button
            v-if="canCheck"
            type="button"
            class="lai-btn"
            @click="openCheck"
          >
            检测
          </button>
          <RouterLink
            v-if="canManage"
            :to="{ name: 'provider-edit', params: { id: detail.id } }"
            class="lai-btn"
          >
            编辑
          </RouterLink>
          <button
            v-if="canManage && !lifecycle.isBusy(detail.id)"
            type="button"
            class="lai-btn"
            @click="detail.enabled ? lifecycle.requestDisable(detail.id, detail.version) : lifecycle.enable(detail.id, detail.version)"
          >
            {{ detail.enabled ? '停用' : '启用' }}
          </button>
          <button
            v-if="canManage"
            type="button"
            class="lai-btn lai-btn-danger"
            @click="lifecycle.requestDelete(detail.id, detail.version)"
          >
            删除
          </button>
        </template>
      </div>
    </div>

    <p
      v-if="lifecycle.actionError"
      class="lai-form-message-error"
      role="alert"
    >
      {{ lifecycle.actionError }}
    </p>

    <PageState
      v-if="state === 'loading'"
      status="loading"
    />
    <PageState
      v-else-if="state === 'error'"
      status="error"
      :error="error"
      @retry="load"
    />
    <template v-else-if="detail">
      <div class="lai-card">
        <h2 class="lai-card-title">
          最近检测
        </h2>
        <p class="lai-card-hint">
          连接状态来自最近一次检测结果，配置发布状态见变更标记。
        </p>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">连接状态</span>
            <StatusText
              :value="detail.connection_status"
              :labels="connectionStatusLabels"
              placeholder="未检测"
            />
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">最近检测时间</span>
            {{ formatDateTime(detail.last_check_at, store.timezone, '未检测') }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">检测耗时</span>
            {{ formatDuration(detail.last_check_latency_ms) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">最近错误码</span>
            {{ detail.last_error_code ?? '—' }}
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          基础配置
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">名称</span>{{ detail.name }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">类型</span>{{ detail.type }}
          </div>
          <div class="lai-summary-item lai-summary-wide">
            <span class="lai-summary-label">服务地址</span>{{ detail.base_url }}
          </div>
          <div class="lai-summary-item lai-summary-wide">
            <span class="lai-summary-label">代理地址</span>{{ detail.proxy_url ?? '直连' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">连接超时</span>{{ detail.connect_timeout_ms }} ms
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">读取超时</span>{{ detail.read_timeout_ms }} ms
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">启用状态</span>{{ detail.enabled ? '启用' : '停用' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">版本</span>{{ detail.version }}
          </div>
        </div>
        <template v-if="headerRows.length > 0">
          <h3 class="lai-subsection-title">
            默认请求头
          </h3>
          <ul class="lai-kv-list">
            <li
              v-for="[key, value] in headerRows"
              :key="key"
            >
              {{ key }}: {{ value }}
            </li>
          </ul>
        </template>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          关联凭证池
        </h2>
        <ul
          v-if="relatedPools.length > 0"
          class="lai-related-list"
        >
          <li
            v-for="pool in relatedPools"
            :key="pool.id"
          >
            <RouterLink
              :to="{ name: 'pool-detail', params: { id: pool.id } }"
              class="lai-link"
            >
              {{ pool.name }}
            </RouterLink>
            <span class="lai-related-meta">
              <StatusText
                :value="pool.status"
                :labels="poolStatusLabels"
              />
            </span>
          </li>
        </ul>
        <p
          v-else
          class="lai-related-empty"
        >
          暂无关联凭证池
        </p>
        <RouterLink
          v-if="relatedPools.length === 10"
          :to="{ name: 'pool-list', query: { provider_id: detail.id } }"
          class="lai-link"
        >
          查看全部凭证池
        </RouterLink>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          关联模型
        </h2>
        <ul
          v-if="relatedModels.length > 0"
          class="lai-related-list"
        >
          <li
            v-for="model in relatedModels"
            :key="model.id"
          >
            <span class="lai-related-name">{{ model.display_name }}</span>
            <span class="lai-related-meta">{{ model.model_id }} · {{ model.connection_status }}</span>
          </li>
        </ul>
        <p
          v-else
          class="lai-related-empty"
        >
          暂无关联模型
        </p>
        <RouterLink
          v-if="relatedModels.length === 10"
          :to="{ name: 'model-list', query: { provider_id: detail.id } }"
          class="lai-link"
        >
          查看全部模型
        </RouterLink>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          检测记录
        </h2>
        <table
          v-if="detail.recent_check_records.length > 0"
          class="lai-table"
        >
          <thead>
            <tr>
              <th>检查时间</th>
              <th>结果</th>
              <th>耗时</th>
              <th>失败摘要</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="record in detail.recent_check_records"
              :key="record.id"
            >
              <td>{{ formatDateTime(record.started_at, store.timezone) }}</td>
              <td>
                <StatusText
                  :value="record.status"
                  :labels="checkStatusLabels"
                />
              </td>
              <td>{{ formatDuration(record.total_ms) }}</td>
              <td>
                <template v-if="record.error_code">
                  {{ record.error_code }} · {{ record.error_summary ?? '' }}
                </template>
                <template v-else>
                  —
                </template>
              </td>
            </tr>
          </tbody>
        </table>
        <p
          v-else
          class="lai-related-empty"
        >
          未检测
        </p>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          审计信息
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">创建人</span>{{ detail.created_by }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">创建时间</span>{{ formatDateTime(detail.created_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">更新人</span>{{ detail.updated_by }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">更新时间</span>{{ formatDateTime(detail.updated_at, store.timezone) }}
          </div>
        </div>
      </div>
    </template>

    <ConfirmDialog
      :open="lifecycle.dialog.open"
      :title="lifecycle.dialog.title"
      :message="lifecycle.dialog.message"
      :impact="lifecycle.dialog.impact?.references"
      :blockers="lifecycle.dialog.impact?.blockers"
      :error-text="lifecycle.dialog.errorText"
      :danger="lifecycle.dialog.operation === 'DELETE'"
      :loading="lifecycle.dialog.loading || lifecycle.dialog.submitting"
      @update:open="(value: boolean) => !value && lifecycle.closeDialog()"
      @confirm="lifecycle.confirmImpact()"
    />

    <CheckDialog
      :open="checkOpen"
      title="检测 Provider"
      :target="checkTarget"
      :loading="checkLoading"
      :result="checkResult"
      :error-text="checkErrorText"
      @update:open="checkOpen = $event"
      @submit="submitCheck"
    />
  </section>
</template>
