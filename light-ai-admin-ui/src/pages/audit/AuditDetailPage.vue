<script setup lang="ts">
// 审计详情（FE-048，附录 4.5.6.4）：脱敏字段 diff、request_id 关联与失败信息；
// 敏感字段仅显示“已脱敏”，不出现原文。
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'
import { fetchAuditLog, type AuditLogDetail } from '@/api/auditLogs'
import { isAbortError } from '@/api/errors'

const route = useRoute()
const auditId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))

const loading = ref(true)
const loadError = ref<unknown>(null)
const detail = shallowRef<AuditLogDetail | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    detail.value = await fetchAuditLog(auditId.value)
  } catch (e) {
    if (!isAbortError(e)) loadError.value = e
  } finally {
    loading.value = false
  }
}
onMounted(() => void load())

const resultText = computed(() =>
  detail.value?.result === 'SUCCEEDED' ? '成功' : detail.value?.result === 'FAILED' ? '失败' : (detail.value?.result ?? '—'),
)

function fieldValue(value: string | null): string {
  if (value === null || value === '') return '—'
  return value
}
</script>

<template>
  <section class="lai-page">
    <div class="lai-page-header">
      <h1 class="lai-page-title">审计详情</h1>
      <RouterLink to="/ui/audit-logs" class="lai-btn">返回列表</RouterLink>
    </div>

    <PageState v-if="loading" status="loading" />
    <PageState v-else-if="loadError" status="error" :error="loadError" @retry="load" />
    <template v-else-if="detail">
      <div class="lai-detail-card">
        <h2 class="lai-section-title">操作信息</h2>
        <dl class="lai-dl">
          <dt>request_id</dt>
          <dd class="lai-cell-mono">{{ detail.request_id }}</dd>
          <dt>操作人</dt>
          <dd>{{ detail.operator_name }}（{{ detail.operator_role }}）</dd>
          <dt>操作</dt>
          <dd class="lai-cell-mono">{{ detail.operation }}</dd>
          <dt>对象</dt>
          <dd>{{ detail.entity_name }}（{{ detail.entity_type }}）</dd>
          <dt>结果</dt>
          <dd>{{ resultText }}</dd>
          <dt>操作原因</dt>
          <dd>{{ detail.operation_reason ?? '—' }}</dd>
          <dt>发生时间</dt>
          <dd>{{ detail.created_at }}</dd>
          <dt>耗时</dt>
          <dd>{{ detail.duration_ms }} ms</dd>
          <dt>来源形态</dt>
          <dd>{{ detail.source_mode }}</dd>
          <dt>版本变更</dt>
          <dd class="lai-cell-mono">{{ detail.before_version ?? '—' }} → {{ detail.after_version ?? '—' }}</dd>
          <dt>变更摘要</dt>
          <dd>{{ detail.change_summary || '—' }}</dd>
        </dl>
      </div>

      <div v-if="detail.result === 'FAILED'" class="lai-detail-card">
        <h2 class="lai-section-title">失败信息</h2>
        <dl class="lai-dl">
          <dt>错误码</dt>
          <dd class="lai-cell-mono">{{ detail.error_code ?? '—' }}</dd>
          <dt>错误摘要</dt>
          <dd>{{ detail.error_summary ?? '—' }}</dd>
        </dl>
        <p class="lai-hint">失败审计与业务回滚独立记录，可通过 request_id 在审计列表中关联同请求的其他记录。</p>
      </div>

      <div class="lai-detail-card">
        <h2 class="lai-section-title">字段变更（服务端脱敏）</h2>
        <PageState v-if="detail.changed_fields.length === 0" status="empty" message="本次操作无字段级变更" />
        <table v-else class="lai-table">
          <thead>
            <tr>
              <th>字段</th>
              <th>变更前</th>
              <th>变更后</th>
              <th>敏感</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in detail.changed_fields" :key="item.field_name">
              <td class="lai-cell-mono">{{ item.field_name }}</td>
              <td class="lai-cell-mono">{{ item.sensitive ? '已脱敏' : fieldValue(item.before_value) }}</td>
              <td class="lai-cell-mono">{{ item.sensitive ? '已脱敏' : fieldValue(item.after_value) }}</td>
              <td>{{ item.sensitive ? '是' : '否' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.lai-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
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
  grid-template-columns: 140px 1fr;
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
.lai-cell-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
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
.lai-hint {
  font-size: 12px;
  color: #57606a;
}
</style>
