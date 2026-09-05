<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import StatusText from '@/components/StatusText.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { Permission } from '@/app/permissions'
import { formatDateTime } from '@/app/display'
import {
  type PublishRecordDetail,
  type RuntimeInstance,
  fetchPublishRecord,
  fetchRuntimeInstances,
} from '@/api/config'
import { isAbortError } from '@/api/errors'

const route = useRoute()
const store = useBootstrapStore()

const canViewInstances = computed(
  () => store.can(Permission.publishManage) || store.can(Permission.providerCheck),
)

const state = ref<'loading' | 'ready' | 'error'>('loading')
const loadError = ref<unknown>(null)
const record = ref<PublishRecordDetail | null>(null)
const instances = ref<RuntimeInstance[]>([])

const publishStatusLabels: Record<string, string> = {
  PREPARING: '准备中',
  ACTIVATING: '激活中',
  SUCCEEDED: '成功',
  PARTIAL_FAILED: '部分失败（等待收敛）',
  FAILED: '失败',
}

const instanceStatusLabels: Record<string, string> = {
  PENDING: '等待中',
  PREPARING: '准备中',
  READY: '已就绪',
  ACTIVATING: '激活中',
  LOADED: '已加载',
  FAILED: '失败',
  TIMED_OUT: '超时',
}

async function load(): Promise<void> {
  const controller = new AbortController()
  state.value = 'loading'
  loadError.value = null
  try {
    record.value = await fetchPublishRecord(route.params.id as string, controller.signal)
    const loaders: Array<Promise<void>> = []
    if (canViewInstances.value) {
      loaders.push(
        fetchRuntimeInstances({ page: 1, page_size: 50 }, controller.signal)
          .then((result) => {
            instances.value = result.items
          })
          .catch(() => {
            instances.value = []
          }),
      )
    }
    await Promise.all(loaders)
    state.value = 'ready'
  } catch (e) {
    if (isAbortError(e)) return
    loadError.value = e
    state.value = 'error'
  }
}
onMounted(load)

</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">
        发布详情
      </h1>
      <RouterLink
        :to="{ name: 'publish' }"
        class="lai-btn"
      >
        返回发布
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
    <template v-else-if="record">
      <div class="lai-card">
        <h2 class="lai-card-title">
          发布信息
        </h2>
        <div class="lai-summary-grid">
          <div class="lai-summary-item">
            <span class="lai-summary-label">状态</span>
            <StatusText
              :value="record.status"
              :labels="publishStatusLabels"
            />
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">快照</span>#{{ record.from_snapshot_no }} →
            #{{ record.target_snapshot_no }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">发布人</span>{{ record.published_by_name }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">发布时间</span>{{ formatDateTime(record.published_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">首轮完成</span>{{ formatDateTime(record.first_round_completed_at, store.timezone) }}
          </div>
          <div class="lai-summary-item">
            <span class="lai-summary-label">收敛时间</span>{{ formatDateTime(record.converged_at, store.timezone) }}
          </div>
          <div class="lai-summary-item lai-summary-wide">
            <span class="lai-summary-label">发布说明</span>{{ record.publish_note || '—' }}
          </div>
          <div class="lai-summary-item lai-summary-wide">
            <span class="lai-summary-label">内容摘要</span>
            <span class="lai-mono">{{ record.content_checksum }}</span>
          </div>
        </div>
      </div>

      <div class="lai-card">
        <h2 class="lai-card-title">
          实例结果
        </h2>
        <table class="lai-table">
          <thead>
            <tr>
              <th>实例</th>
              <th>模式 / 版本</th>
              <th>能力</th>
              <th>状态</th>
              <th>快照</th>
              <th>重试</th>
              <th>加载耗时</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="instance in record.instance_results"
              :key="instance.instance_id"
            >
              <td>
                {{ instance.instance_id }}
                <span
                  v-if="instances.find((item) => item.instance_id === instance.instance_id)"
                  class="lai-related-meta"
                >
                  当前 #{{ instances.find((item) => item.instance_id === instance.instance_id)!.active_snapshot_no }}
                </span>
              </td>
              <td>{{ instance.runtime_mode }} / {{ instance.runtime_version }}</td>
              <td>
                <span class="lai-related-meta">
                  schema {{ instance.supported_schema_versions.join(', ') }} ·
                  {{ instance.loaded_adapter_types.join(', ') }}
                </span>
              </td>
              <td>
                <StatusText
                  :value="instance.status"
                  :labels="instanceStatusLabels"
                />
              </td>
              <td>#{{ instance.from_snapshot_no }} → #{{ instance.target_snapshot_no }}</td>
              <td>{{ instance.retry_count }}</td>
              <td>{{ instance.load_duration_ms === null ? '—' : `${instance.load_duration_ms} ms` }}</td>
              <td>
                <template v-if="instance.error_code">
                  {{ instance.error_code }} · {{ instance.error_summary ?? '' }}
                </template>
                <template v-else>
                  —
                </template>
              </td>
            </tr>
          </tbody>
        </table>
        <p
          v-if="!canViewInstances"
          class="lai-card-hint"
        >
          当前角色不展示在线实例清单。
        </p>
      </div>
    </template>
  </section>
</template>
