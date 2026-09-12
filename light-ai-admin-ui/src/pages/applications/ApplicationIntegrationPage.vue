<script setup lang="ts">
// 应用内开发接入页（PRD 9.2.9）：从应用详情进入，只展示当前应用已授权模型、
// 统一 Base URL、认证方式、应用当前限制、示例与在线测试；示例密钥使用占位符。
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import CodeSamplePanel from '@/pages/developer/CodeSamplePanel.vue'
import ChatTestPanel from '@/pages/developer/ChatTestPanel.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { fetchApplication, type ApplicationDetail } from '@/api/applications'
import {
  fetchDeveloperContext,
  type AccessMode,
  type AuthenticationType,
  type DeveloperAccessContext,
  type DeveloperAliasSummary,
} from '@/api/developerAccess'
import { isAbortError } from '@/api/errors'

const route = useRoute()
const store = useBootstrapStore()
const canTest = store.can(Permission.developerTest)

const authLabels: Record<AuthenticationType, string> = {
  NONE: '无认证（本地）',
  HOST_CONTEXT: '宿主上下文认证',
  BEARER_TOKEN: 'Bearer Token',
}
const modeByRuntime: Record<string, AccessMode> = {
  LOCAL_RUNTIME: 'LOCAL_RUNTIME',
  EMBEDDED: 'EMBEDDED',
  STANDALONE_SERVER: 'STANDALONE_CLIENT',
}

const id = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const detail = ref<ApplicationDetail | null>(null)
const context = shallowRef<DeveloperAccessContext | null>(null)
const loading = ref(true)
const loadError = ref<unknown>(null)
const selectedAliasId = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    const [application, developerContext] = await Promise.all([
      fetchApplication(id.value),
      fetchDeveloperContext(undefined),
    ])
    detail.value = application
    context.value = developerContext
    selectedAliasId.value = authorizedModels.value.length > 0
      ? authorizedModels.value[0]!.alias_id
      : null
  } catch (error) {
    if (isAbortError(error)) return
    loadError.value = error
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 应用已授权且当前确实可调用的虚拟模型（取应用授权集合与已发布模型的交集）。 */
const authorizedModels = computed<DeveloperAliasSummary[]>(() => {
  const codes = new Set(
    (detail.value?.models ?? []).filter((item) => item.enabled)
      .map((item) => item.virtual_model_code)
      .filter((code): code is string => Boolean(code)),
  )
  const models = context.value?.available_models ?? []
  return models.filter((model) => codes.has(model.alias))
})

const selectedAlias = computed(
  () => authorizedModels.value.find((item) => item.alias_id === selectedAliasId.value) ?? null,
)

const accessMode = computed<AccessMode | null>(() => {
  if (!context.value) return null
  return modeByRuntime[context.value.runtime_mode] ?? null
})

const baseUrlText = computed(() => {
  const mode = context.value?.runtime_mode
  if (!context.value) return '—'
  if (mode === 'EMBEDDED') return '进程内调用'
  if (mode === 'LOCAL_RUNTIME') return '本地 Runtime'
  return context.value.api_base_url ?? '—'
})

const quotaText = computed(() => {
  const quota = detail.value?.quota
  if (!quota) return '—'
  const token = quota.token_limit === null ? '不限' : quota.token_limit.toLocaleString()
  const amount = quota.amount_limit === null ? '不限' : `${quota.amount_limit} ${quota.currency}`
  const rpm = quota.rpm === null ? '不限' : String(quota.rpm)
  const tpm = quota.tpm === null ? '不限' : quota.tpm.toLocaleString()
  return `Token ${token} · 金额 ${amount} · RPM ${rpm} · TPM ${tpm}`
})

const errorRows = [
  { code: 'FIELD_VALIDATION_FAILED', http: '400', retry: '否', note: '请求字段不合法，按 errors 定位字段' },
  { code: 'ACCESS_DENIED', http: '403', retry: '否', note: '应用或密钥未授权该虚拟模型' },
  { code: 'MODEL_ALIAS_NOT_FOUND', http: '404', retry: '否', note: '虚拟模型不存在或未发布' },
  { code: 'MODEL_CAPABILITY_NOT_SUPPORTED', http: '422', retry: '否', note: '候选不支持请求的能力（如流式或 system）' },
  { code: 'APPLICATION_MODEL_CONSTRAINT_VIOLATED', http: '403', retry: '否', note: '请求参数超过应用为该模型配置的上限' },
  { code: 'APPLICATION_TOKEN_QUOTA_EXHAUSTED', http: '429', retry: '否', note: '本周期 Token 额度已耗尽' },
  { code: 'CAPACITY_LIMITED', http: '429', retry: '是', note: '容量不足；响应带 retry_after_ms' },
  { code: 'TOTAL_TIMEOUT', http: '504', retry: '否', note: '总超时，已取消进行中的请求' },
]
</script>

<template>
  <section class="lai-page">
    <div class="integration-header">
      <div>
        <h1 class="lai-page-title">
          开发接入
        </h1>
        <p v-if="detail">
          <span class="lai-cell-mono">{{ detail.code }}</span> · {{ detail.name }}
        </p>
      </div>
      <RouterLink
        v-if="detail"
        :to="`/ui/applications/${detail.id}`"
        class="lai-btn"
      >
        返回应用详情
      </RouterLink>
    </div>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError || !detail"
      status="error"
      :error="loadError"
      @retry="load"
    />
    <template v-else>
      <div class="lai-detail-card">
        <h2 class="lai-section-title">
          连接信息
        </h2>
        <dl class="lai-dl">
          <dt>统一 Base URL</dt>
          <dd class="lai-cell-mono">
            {{ baseUrlText }}
          </dd>
          <dt>认证方式</dt>
          <dd>Authorization: Bearer &lt;应用密钥&gt;（{{ authLabels[context?.authentication_type ?? 'BEARER_TOKEN'] }}）</dd>
          <dt>请求路径</dt>
          <dd class="lai-cell-mono">
            GET /v1/models · POST /v1/chat/completions
          </dd>
          <dt>应用当前限制</dt>
          <dd>{{ quotaText }}</dd>
          <dt>可用虚拟模型</dt>
          <dd>{{ authorizedModels.length }} 个</dd>
        </dl>
        <p class="lai-note">
          业务系统只持有应用密钥，上游供应商 Key 不下发。示例中的密钥位置固定为占位符，请勿把真实密钥写入代码仓库或浏览器存储。
        </p>
      </div>

      <p
        v-if="detail.active_key_count === 0"
        class="lai-form-message-error"
        role="alert"
        data-testid="no-active-key"
      >
        该应用当前没有活动密钥，测试与业务调用都会因认证失败被拒绝；请先在应用详情“接入密钥”页签签发应用密钥。
      </p>

      <PageState
        v-if="authorizedModels.length === 0"
        status="empty"
        message="该应用尚未授权任何可调用的虚拟模型，请先在“可用模型”页签完成授权"
      />
      <template v-else>
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            模型选择
          </h2>
          <select
            v-model="selectedAliasId"
            class="lai-input lai-dev-select"
            aria-label="选择应用已授权模型"
          >
            <option
              v-for="item in authorizedModels"
              :key="item.alias_id"
              :value="item.alias_id"
            >
              {{ item.display_name }}（{{ item.alias }}）
            </option>
          </select>
          <dl
            v-if="selectedAlias"
            class="lai-dl"
          >
            <dt>流式 / system</dt>
            <dd>{{ selectedAlias.support_stream ? '支持流式' : '不支持流式' }} / {{ selectedAlias.support_system_message ? '支持 system' : '不支持 system' }}</dd>
            <dt>上下文 / 最大输出</dt>
            <dd>{{ selectedAlias.context_window?.toLocaleString('zh-CN') ?? '—' }} / {{ selectedAlias.max_output_tokens?.toLocaleString('zh-CN') ?? '—' }}</dd>
          </dl>
        </div>

        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            调用示例
          </h2>
          <CodeSamplePanel
            :alias-id="selectedAliasId"
            :mode="accessMode"
          />
        </div>

        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            在线测试
          </h2>
          <p class="lai-note">
            测试请求与正式请求共用同一准入、路由与结算链路，并标记调用来源为管理端测试。
          </p>
          <ChatTestPanel
            :alias="selectedAlias"
            :can-test="canTest"
          />
        </div>
      </template>

      <div class="lai-detail-card">
        <h2 class="lai-section-title">
          错误处理
        </h2>
        <table class="lai-table">
          <thead>
            <tr><th>code</th><th>HTTP</th><th>可重试</th><th>说明</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="row in errorRows"
              :key="row.code"
            >
              <td class="lai-cell-mono">
                {{ row.code }}
              </td>
              <td>{{ row.http }}</td>
              <td>{{ row.retry }}</td>
              <td>{{ row.note }}</td>
            </tr>
          </tbody>
        </table>
        <p class="lai-note">
          错误响应统一为 error 对象，包含 code、message、request_id、retryable；不返回密钥、认证头或上游凭证。
        </p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.integration-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.integration-header p { margin: 4px 0 0; color: #667085; }
.lai-detail-card { padding: 16px; margin-bottom: 12px; background: #fff; border: 1px solid #e6eaf0; border-radius: 6px; }
.lai-section-title { margin: 0 0 12px; font-size: 15px; font-weight: 500; color: #172033; }
.lai-dl { display: grid; grid-template-columns: 140px minmax(0, 1fr); gap: 6px 12px; margin: 0; font-size: 13px; }
.lai-dl dt { color: #667085; }
.lai-dl dd { margin: 0; overflow-wrap: anywhere; }
.lai-dev-select { width: 100%; margin-bottom: 12px; }
.lai-note { margin: 12px 0 0; color: #667085; font-size: 12px; }
.lai-cell-mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.lai-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.lai-table th, .lai-table td { padding: 8px 10px; text-align: left; border-bottom: 1px solid #e6eaf0; }
.lai-table th { color: #667085; background: #f6f8fb; }
</style>
