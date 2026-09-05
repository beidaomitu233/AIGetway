<script setup lang="ts">
// Provider Model 详情页（FE-015，附录 4.2.6.3）：状态摘要、关联 Alias、最近检测记录与操作。
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import CheckCommandDialog from '@/components/CheckCommandDialog.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { checkStatusLabel, connectionStatusLabel } from '@/app/display'
import { fetchProviderCredentials, fetchProviderModel, fetchEntityImpact, checkProviderModel } from '@/api/providerModels'
import type { ProviderModelDetail, ProviderCredentialOption } from '@/api/providerModels'
import type { ProviderCheckCommand, ProviderCheckRecord } from '@/api/credentials'
import { request } from '@/api/http'
import type { ImpactReference, ManagementOperationResult } from '@/api/contracts'
import { ApiError, toErrorMessage } from '@/api/errors'

const route = useRoute()
const router = useRouter()
const modelId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))

const store = useBootstrapStore()
const canManage = store.can(Permission.modelManage)
const canCheck = store.can(Permission.providerCheck)

const loading = ref(true)
const loadError = ref<unknown>(null)
const detail = shallowRef<ProviderModelDetail | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    detail.value = await fetchProviderModel(modelId.value)
  } catch (e) {
    loadError.value = e
  } finally {
    loading.value = false
  }
}
onMounted(() => void load())

const checkOpen = ref(false)
const checkSubmitting = ref(false)
const checkError = shallowRef<unknown>(null)
const checkResult = shallowRef<ProviderCheckRecord | null>(null)
const credentialOptions = shallowRef<ProviderCredentialOption[]>([])

async function openCheck(): Promise<void> {
  checkOpen.value = true
  checkError.value = null
  checkResult.value = null
  try {
    credentialOptions.value = await fetchProviderCredentials(detail.value?.provider_id ?? '')
  } catch {
    credentialOptions.value = []
  }
}

async function submitCheck(command: ProviderCheckCommand): Promise<void> {
  checkSubmitting.value = true
  checkError.value = null
  try {
    checkResult.value = await checkProviderModel(modelId.value, command)
  } catch (e) {
    checkError.value = e
  } finally {
    checkSubmitting.value = false
  }
}

const disableOpen = ref(false)
const disableLoading = ref(false)
const disableImpact = shallowRef<ImpactReference[]>([])
const disableImpactVersion = ref('')

async function openDisable(): Promise<void> {
  disableLoading.value = true
  try {
    const impact = await fetchEntityImpact<{ impact_version: string; references: ImpactReference[] }>(
      `/provider-models/${modelId.value}/impact`,
      'DISABLE',
    )
    disableImpact.value = impact.references
    disableImpactVersion.value = impact.impact_version
    disableOpen.value = true
  } catch (e) {
    actionMessage.value = toErrorMessage(e)
  } finally {
    disableLoading.value = false
  }
}

async function submitDisable(): Promise<void> {
  disableLoading.value = true
  try {
    await request<ManagementOperationResult>({
      path: `/provider-models/${modelId.value}/disable`,
      method: 'POST',
      body: { version: detail.value?.version, confirmed_impact_version: disableImpactVersion.value },
    })
    disableOpen.value = false
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'IMPACT_ANALYSIS_EXPIRED') {
      actionMessage.value = '影响分析已过期，请重新确认'
      disableOpen.value = false
    } else {
      actionMessage.value = toErrorMessage(e)
      disableOpen.value = false
    }
  } finally {
    disableLoading.value = false
  }
}

const deleteOpen = ref(false)
const deleteLoading = ref(false)
const deleteImpact = shallowRef<ImpactReference[]>([])
const deleteImpactVersion = ref('')
const actionMessage = ref('')

async function openDelete(): Promise<void> {
  deleteLoading.value = true
  try {
    const impact = await fetchEntityImpact<{ impact_version: string; references: ImpactReference[] }>(
      `/provider-models/${modelId.value}/impact`,
      'DELETE',
    )
    deleteImpact.value = impact.references
    deleteImpactVersion.value = impact.impact_version
    deleteOpen.value = true
  } catch (e) {
    actionMessage.value = toErrorMessage(e)
  } finally {
    deleteLoading.value = false
  }
}

async function submitDelete(): Promise<void> {
  deleteLoading.value = true
  try {
    await request<ManagementOperationResult>({
      path: `/provider-models/${modelId.value}`,
      method: 'DELETE',
      body: { version: detail.value?.version, confirmed_impact_version: deleteImpactVersion.value },
    })
    deleteOpen.value = false
    void router.push('/ui/provider-models')
  } catch (e) {
    deleteOpen.value = false
    actionMessage.value =
      e instanceof ApiError && e.code === 'OBJECT_IN_USE' ? '模型仍被候选引用，无法删除' : toErrorMessage(e)
  } finally {
    deleteLoading.value = false
  }
}

function toggleEnabled(): void {
  if (!detail.value) return
  if (detail.value.enabled) {
    void openDisable()
    return
  }
  actionMessage.value = ''
  void request<ManagementOperationResult>({
    path: `/provider-models/${modelId.value}/enable`,
    method: 'POST',
    body: { version: detail.value.version },
  }).then(() => load())
    .catch((e: unknown) => {
      actionMessage.value = toErrorMessage(e)
    })
}

const aliasSummary = computed(() => detail.value?.related_aliases ?? [])
const recentChecks = computed(() => detail.value?.recent_checks ?? [])

function goUsage(): void {
  void router.push({ path: '/ui/usage', query: { provider_model_id: modelId.value } })
}
function goTraces(): void {
  void router.push({ path: '/ui/traces', query: { provider_model_id: modelId.value } })
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        模型详情
      </h1>
      <div class="lai-page-actions">
        <button
          type="button"
          class="lai-btn"
          @click="goUsage"
        >
          查看用量
        </button>
        <button
          type="button"
          class="lai-btn"
          @click="goTraces"
        >
          查看调用
        </button>
        <RouterLink
          v-if="canManage"
          :to="`/ui/provider-models/${modelId}/edit`"
          class="lai-btn"
        >
          编辑
        </RouterLink>
        <button
          v-if="canCheck"
          type="button"
          class="lai-btn lai-btn-primary"
          @click="openCheck"
        >
          检测
        </button>
      </div>
    </div>

    <PageState
      v-if="loading"
      status="loading"
    />
    <PageState
      v-else-if="loadError"
      status="error"
      :error="loadError"
      @retry="load"
    />
    <template v-else-if="detail">
      <p
        v-if="actionMessage"
        class="lai-form-message-error"
        role="alert"
      >
        {{ actionMessage }}
      </p>

      <div class="lai-detail-grid">
        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            基础信息
          </h2>
          <dl class="lai-dl">
            <dt>展示名称</dt><dd>{{ detail.display_name }}</dd>
            <dt>模型标识</dt><dd class="lai-cell-mono">
              {{ detail.model_id }}
            </dd>
            <dt>Provider</dt><dd>{{ detail.provider_name }}</dd>
            <dt>上下文 / 最大输出</dt>
            <dd>{{ detail.context_window?.toLocaleString('zh-CN') ?? '待补充' }} / {{ detail.max_output_tokens?.toLocaleString('zh-CN') ?? '待补充' }}</dd>
            <dt>流式 / system</dt>
            <dd>
              {{ detail.support_stream == null ? '待补充' : detail.support_stream ? '支持' : '不支持' }} /
              {{ detail.support_system_message == null ? '待补充' : detail.support_system_message ? '支持' : '不支持' }}
            </dd>
            <dt>价格（输入/输出）</dt>
            <dd class="lai-cell-mono">
              {{ detail.input_price }} / {{ detail.output_price }}（每 {{ detail.price_unit }} tokens · {{ detail.currency }}）
            </dd>
            <dt>启停</dt><dd>{{ detail.enabled ? '启用' : '停用' }}</dd>
            <dt>待发布</dt><dd>{{ detail.draft_changed ? '待发布' : '—' }}</dd>
          </dl>
        </div>

        <div class="lai-detail-card">
          <h2 class="lai-section-title">
            运行状态
          </h2>
          <dl class="lai-dl">
            <dt>连接状态</dt><dd>{{ connectionStatusLabel(detail.connection_status) }}</dd>
            <dt>最近检测</dt><dd>{{ detail.last_check_at ?? '未检测' }}</dd>
            <dt>最近错误码</dt><dd>{{ detail.last_error_code ?? '—' }}</dd>
          </dl>
          <button
            v-if="canManage"
            type="button"
            class="lai-btn"
            :disabled="disableLoading"
            @click="toggleEnabled"
          >
            {{ detail.enabled ? '停用' : '启用' }}
          </button>
          <button
            v-if="canManage"
            type="button"
            class="lai-btn lai-btn-danger"
            @click="openDelete"
          >
            删除
          </button>
        </div>
      </div>

      <div class="lai-detail-card">
        <h2 class="lai-section-title">
          关联 Alias
        </h2>
        <PageState
          v-if="aliasSummary.length === 0"
          status="empty"
          message="暂无候选引用"
        />
        <table
          v-else
          class="lai-table"
        >
          <thead>
            <tr><th>Alias</th><th>priority</th><th>weight</th><th>凭证池</th><th>候选状态</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="item in aliasSummary"
              :key="item.id"
            >
              <td>
                <RouterLink
                  :to="`/ui/model-aliases/${item.alias_id}`"
                  class="lai-link"
                >
                  {{ item.alias }}
                </RouterLink>
              </td>
              <td>{{ item.priority }}</td>
              <td>{{ item.weight }}</td>
              <td>{{ item.credential_pool_name }}</td>
              <td>{{ item.candidate_status }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="lai-detail-card">
        <h2 class="lai-section-title">
          最近检测记录
        </h2>
        <PageState
          v-if="recentChecks.length === 0"
          status="empty"
          message="未检测"
        />
        <table
          v-else
          class="lai-table"
        >
          <thead>
            <tr><th>检查时间</th><th>结果</th><th>耗时</th><th>失败摘要</th></tr>
          </thead>
          <tbody>
            <tr
              v-for="item in recentChecks"
              :key="item.id"
            >
              <td>{{ item.started_at }}</td>
              <td>{{ checkStatusLabel(item.status) }}</td>
              <td>{{ item.total_ms }} ms</td>
              <td>{{ item.error_summary ?? item.error_code ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <CheckCommandDialog
      v-model:open="checkOpen"
      title="检测模型"
      :target-label="`目标：${detail?.display_name ?? ''}（${detail?.model_id ?? ''}）`"
      :credential-options="credentialOptions.map((item) => ({ id: item.id, label: `${item.name}（${item.pool_name}）` }))"
      require-credential
      :submitting="checkSubmitting"
      :result="checkResult"
      :error="checkError"
      @confirm="submitCheck"
    />
    <ConfirmDialog
      v-model:open="disableOpen"
      title="停用模型"
      :message="`确认停用「${detail?.display_name ?? ''}」？停用并发布后，引用它的候选不再进入新请求。`"
      :impact="disableImpact"
      danger
      :loading="disableLoading"
      @confirm="submitDisable"
    />
    <ConfirmDialog
      v-model:open="deleteOpen"
      title="删除模型"
      :message="`确认删除「${detail?.display_name ?? ''}」？存在候选引用时将被拒绝。`"
      :impact="deleteImpact"
      danger
      :loading="deleteLoading"
      @confirm="submitDelete"
    />
  </section>
</template>

<style scoped>
.lai-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lai-page-actions {
  display: flex;
  gap: 8px;
}
.lai-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
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
  grid-template-columns: 160px 1fr;
  gap: 4px 12px;
  font-size: 13px;
  margin: 0 0 8px;
}
.lai-dl dt {
  color: #57606a;
}
.lai-dl dd {
  margin: 0;
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
  white-space: nowrap;
}
.lai-table th {
  color: #57606a;
  background: #f6f8fa;
}
</style>
