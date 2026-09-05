<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import StatusText from '@/components/StatusText.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import CredentialPanel from '@/components/credentials/CredentialPanel.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { useLifecycleActions } from '@/composables/useLifecycleActions'
import {
  formatDateTime,
  poolStatusLabels,
  selectionStrategyLabels,
} from '@/app/display'
import { Permission } from '@/app/permissions'
import {
  type CredentialPoolDetail,
  deletePool,
  disablePool,
  enablePool,
  getPool,
  getPoolImpact,
} from '@/api/credentialPools'
import { isAbortError } from '@/api/errors'

const CAPACITY_REFRESH_MS = 10000

const route = useRoute()
const router = useRouter()
const store = useBootstrapStore()

const poolId = computed(() => route.params.id as string)
const canManage = computed(() => store.can(Permission.credentialManage))
const canCheck = computed(() => store.can(Permission.credentialCheck))
// 开发与只读角色不加载 Credential 列表（4.2.4）。
const canViewCredentials = computed(() => store.can(Permission.credentialView))

const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const detail = ref<CredentialPoolDetail | null>(null)

let capacityTimer: number | null = null
let loadController: AbortController | null = null

async function load(): Promise<void> {
  loadController?.abort()
  loadController = new AbortController()
  state.value = 'loading'
  loadError.value = null
  try {
    detail.value = await getPool(poolId.value, loadController.signal)
    state.value = 'ready'
  } catch (e) {
    if (isAbortError(e)) return
    loadError.value = e
    state.value = 'error'
  }
}

function startCapacityTimer(): void {
  stopCapacityTimer()
  capacityTimer = window.setInterval(() => {
    if (document.hidden) return
    if (detail.value) {
      void load()
    }
  }, CAPACITY_REFRESH_MS)
}

function stopCapacityTimer(): void {
  if (capacityTimer !== null) {
    window.clearInterval(capacityTimer)
    capacityTimer = null
  }
}

onMounted(() => {
  void load()
  startCapacityTimer()
})
onUnmounted(() => {
  stopCapacityTimer()
  loadController?.abort()
})
watch(poolId, () => void load())

const lifecycle = useLifecycleActions({
  getImpact: getPoolImpact,
  enable: (id, version) => enablePool(id, version),
  disable: (id, version, confirmed) => disablePool(id, version, confirmed),
  remove: (id, version, confirmed) => deletePool(id, version, confirmed),
  onChanged: () => {
    void load()
    void store.refreshDraftSummary()
  },
})

</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        凭证池详情
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
          <RouterLink
            v-if="canManage"
            :to="{ name: 'pool-edit', params: { id: detail.id } }"
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
            <span class="lai-summary-label">Provider</span>
            <RouterLink
              :to="{ name: 'provider-detail', params: { id: detail.provider_id } }"
              class="lai-link"
            >
              {{ detail.provider_name }}
            </RouterLink>
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">选择策略</span>
            {{ selectionStrategyLabels[detail.selection_strategy] ?? detail.selection_strategy }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">启用状态</span>{{ detail.enabled ? '启用' : '停用' }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">版本</span>{{ detail.version }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">变更状态</span>
            <RouterLink
              v-if="detail.draft_changed"
              to="/ui/config/drafts"
              class="lai-link"
            >
              待发布
            </RouterLink>
            <template v-else>
              —
            </template>
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          容量摘要
        </h2>
        <p class="lai-card-hint">
          每 10 秒自动刷新，实时数据不进入配置草稿。
        </p>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">状态</span>
            <StatusText
              :value="detail.status"
              :labels="poolStatusLabels"
            />
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">凭证总数</span>{{ detail.credential_total }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">可用凭证</span>{{ detail.credential_available }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">当前并发</span>{{ detail.current_concurrency }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">RPM 已用</span>{{ detail.rpm_used }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">TPM 已用</span>{{ detail.tpm_used }}
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          引用关系
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">引用候选数</span>{{ detail.route_candidate_count }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">涉及模型别名数</span>{{ detail.model_alias_count }}
          </div>
        </div>
      </div>

      <div
        v-if="canViewCredentials"
        class="lai-card"
      >
        <CredentialPanel
          :pool-id="detail.id"
          :provider-id="detail.provider_id"
          :can-manage="canManage"
          :can-check="canCheck"
        />
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
  </section>
</template>
