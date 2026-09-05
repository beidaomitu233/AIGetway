<script setup lang="ts">
// 接入说明页（FE-049，附录 4.6.1）：连接信息、Alias 选择、模型摘要、
// 示例面板与在线测试；开发仅授权 Alias，无已发布模型显示空态。
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import CodeSamplePanel from './CodeSamplePanel.vue'
import ChatTestPanel from './ChatTestPanel.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { fetchDeveloperContext, type AccessMode, type AuthenticationType, type DeveloperAccessContext } from '@/api/developerAccess'
import { isAbortError } from '@/api/errors'

const route = useRoute()
const store = useBootstrapStore()
const canTest = store.can(Permission.developerTest)

const runtimeModeLabels: Record<string, string> = {
  LOCAL_RUNTIME: '本地 Runtime',
  EMBEDDED: '嵌入模式',
  STANDALONE_SERVER: '独立部署',
}

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

const loading = ref(true)
const loadError = ref<unknown>(null)
const context = shallowRef<DeveloperAccessContext | null>(null)
const aliasHint = ref('')

const selectedAliasId = ref<string | null>(null)
let seq = 0
let controller: AbortController | null = null

async function loadContext(aliasId?: string): Promise<void> {
  const current = ++seq
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  try {
    const data = await fetchDeveloperContext(aliasId, controller.signal)
    if (current !== seq) return
    context.value = data
    loadError.value = null
    const requested = typeof route.query.alias_id === 'string' ? route.query.alias_id : null
    if (aliasId && data.selected_alias_id && data.selected_alias_id !== aliasId) {
      aliasHint.value = '所选 Alias 无权限或已停用，已回退到第一个可用项'
    } else if (requested && data.selected_alias_id !== requested) {
      aliasHint.value = 'URL 指定的 Alias 无权限或已停用，已回退到第一个可用项'
    } else {
      aliasHint.value = ''
    }
    selectedAliasId.value = data.selected_alias_id
  } catch (e) {
    if (current !== seq || isAbortError(e)) return
    loadError.value = e
  } finally {
    if (current === seq) loading.value = false
  }
}

onMounted(() => {
  const requested = typeof route.query.alias_id === 'string' ? route.query.alias_id : undefined
  void loadContext(requested)
})

watch(selectedAliasId, (value, previous) => {
  if (value && value !== previous && previous !== null) {
    void loadContext(value)
  }
})

const selectedAlias = computed(
  () => context.value?.available_models.find((item) => item.alias_id === selectedAliasId.value) ?? null,
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

const requestFields = [
  { field: 'model', type: 'string', required: '是', rule: '已发布且授权的 Model Alias' },
  { field: 'messages', type: 'array', required: '是', rule: 'system ≤1 且为首项；至少 1 个 user；内容非空' },
  { field: 'stream', type: 'boolean', required: '否', rule: '默认 true；同步测试固定 false' },
  { field: 'temperature', type: 'decimal', required: '否', rule: '模型声明范围内；请求值优先于模型默认值' },
  { field: 'top_p', type: 'decimal', required: '否', rule: '0—1；模型声明范围内' },
  { field: 'max_tokens', type: 'integer', required: '否', rule: '显式超上限被过滤；缺省按模型默认值' },
]

async function copyBaseUrl(): Promise<void> {
  const url = context.value?.api_base_url
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
  } catch {
    // 复制失败不提示错误
  }
}
</script>

<template>
  <section class="lai-page">
    <h1 class="lai-page-title">
      接入说明与测试
    </h1>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
      @retry="loadContext()"
    />
    <PageState
      v-else-if="!context || context.available_models.length === 0"
      status="empty"
      message="当前身份没有可用的已发布模型别名"
    />
    <template v-else>
      <p
        v-if="aliasHint"
        class="lai-form-message-error"
        role="alert"
      >
        {{ aliasHint }}
      </p>

      <div class="lai-dev-grid">
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            连接信息
          </h2>
          <dl class="lai-dl">
            <dt>运行形态</dt>
            <dd>{{ runtimeModeLabels[context.runtime_mode] ?? context.runtime_mode }}</dd>
            <dt>API 地址</dt>
            <dd class="lai-cell-mono">
              {{ baseUrlText }}
              <button
                v-if="context.runtime_mode === 'STANDALONE_SERVER' && context.api_base_url"
                type="button"
                class="lai-btn lai-btn-text"
                @click="copyBaseUrl"
              >
                复制
              </button>
            </dd>
            <dt>认证方式</dt>
            <dd>{{ authLabels[context.authentication_type] ?? context.authentication_type }}</dd>
            <dt>SDK / 服务版本</dt>
            <dd class="lai-cell-mono">
              {{ context.sdk_version }} / {{ context.server_version }}
            </dd>
            <dt>当前快照</dt>
            <dd>
              #{{ context.current_snapshot_no ?? '—' }}
              <RouterLink
                v-if="context.current_snapshot_no != null"
                to="/ui/config/publish"
                class="lai-link"
              >
                发布记录
              </RouterLink>
            </dd>
          </dl>
        </div>

        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            模型选择
          </h2>
          <select
            v-model="selectedAliasId"
            class="lai-input lai-dev-select"
            aria-label="选择 Model Alias"
          >
            <option
              v-for="item in context.available_models"
              :key="item.alias_id"
              :value="item.alias_id"
            >
              {{ item.display_name }}（{{ item.alias }}）
            </option>
          </select>
          <dl
            v-if="selectedAlias"
            class="lai-dl lai-dev-summary"
          >
            <dt>展示名称</dt>
            <dd>{{ selectedAlias.display_name }}</dd>
            <dt>流式 / system</dt>
            <dd>
              {{ selectedAlias.support_stream ? '支持流式' : '不支持流式' }} /
              {{ selectedAlias.support_system_message ? '支持 system' : '不支持 system' }}
            </dd>
            <dt>上下文 / 最大输出</dt>
            <dd>
              {{ selectedAlias.context_window?.toLocaleString('zh-CN') ?? '—' }} /
              {{ selectedAlias.max_output_tokens?.toLocaleString('zh-CN') ?? '—' }}
            </dd>
          </dl>
        </div>
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

      <div class="lai-detail-grid">
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            请求字段说明
          </h2>
          <table class="lai-table">
            <thead>
              <tr><th>字段</th><th>类型</th><th>必填</th><th>规则</th></tr>
            </thead>
            <tbody>
              <tr
                v-for="item in requestFields"
                :key="item.field"
              >
                <td class="lai-cell-mono">
                  {{ item.field }}
                </td>
                <td>{{ item.type }}</td>
                <td>{{ item.required }}</td>
                <td>{{ item.rule }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            常见错误
          </h2>
          <table class="lai-table">
            <thead>
              <tr><th>code</th><th>HTTP</th><th>可重试</th><th>说明</th></tr>
            </thead>
            <tbody>
              <tr>
                <td class="lai-cell-mono">
                  FIELD_VALIDATION_FAILED
                </td><td>400</td><td>否</td><td>请求字段不合法，按 errors 定位字段</td>
              </tr>
              <tr>
                <td class="lai-cell-mono">
                  MODEL_ALIAS_NOT_FOUND
                </td><td>404</td><td>否</td><td>Alias 不存在或未发布</td>
              </tr>
              <tr>
                <td class="lai-cell-mono">
                  MODEL_CAPABILITY_NOT_SUPPORTED
                </td><td>422</td><td>否</td><td>候选不支持请求的能力（如流式或 system）</td>
              </tr>
              <tr>
                <td class="lai-cell-mono">
                  CAPACITY_LIMITED
                </td><td>429</td><td>是</td><td>容量不足；带 retry_after_ms</td>
              </tr>
              <tr>
                <td class="lai-cell-mono">
                  CONTEXT_WINDOW_EXCEEDED
                </td><td>422</td><td>否</td><td>输入与 max_tokens 超出上下文窗口</td>
              </tr>
              <tr>
                <td class="lai-cell-mono">
                  TOTAL_TIMEOUT
                </td><td>504</td><td>否</td><td>总超时，已取消进行中的请求</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="lai-detail-card">
        <ChatTestPanel
          :alias="selectedAlias"
          :can-test="canTest"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.lai-dev-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 12px;
  margin: 12px 0;
}
.lai-detail-card {
  border: 1px solid #d8dee4;
  border-radius: 6px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
.lai-section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 8px;
}
.lai-dl {
  display: grid;
  grid-template-columns: 130px 1fr;
  gap: 4px 12px;
  font-size: 13px;
  margin: 0;
}
.lai-dl dt {
  color: #57606a;
}
.lai-dl dd {
  margin: 0;
}
.lai-dev-select {
  width: 100%;
  padding: 6px 8px;
  margin-bottom: 8px;
}
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.lai-link {
  color: #0969da;
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
}
.lai-table th {
  color: #57606a;
  background: #f6f8fa;
}
.lai-dev-summary {
  margin-top: 8px;
}
</style>
