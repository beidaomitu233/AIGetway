<script setup lang="ts">
import { Card, Progress, Tag } from 'ant-design-vue'
import type { ApplicationQuotaPolicy } from '@/api/applications'
import { remainingAmount, tokenRemainingText } from './applicationValues'
import { formatDateTime } from '@/app/display'
defineProps<{ quota: ApplicationQuotaPolicy; timezone: string }>()

function usagePercent(used: string | number | null | undefined, reserved: string | number | null | undefined, limit: string | number | null | undefined) {
  if (limit == null) return 0
  try {
    const total = BigInt(String(used ?? 0)) + BigInt(String(reserved ?? 0))
    const max = BigInt(String(limit))
    if (max <= 0n) return 0
    return Math.min(100, Number((total * 100n) / max))
  } catch {
    return 0
  }
}

function amountUsagePercent(used: string | number | null | undefined, reserved: string | number | null | undefined, limit: string | number | null | undefined) {
  if (limit == null) return 0
  const toNumber = (value: string | number | null | undefined) => Number(value ?? 0)
  const max = toNumber(limit)
  if (!Number.isFinite(max) || max <= 0) return 0
  return Math.min(100, Math.round(((toNumber(used) + toNumber(reserved)) / max) * 100))
}
</script>
<template>
  <Card :bordered="false" class="lai-card quota-summary-card">
    <template #title>
      <div class="quota-summary-title">
        <span>额度使用明细</span>
        <Tag color="blue">{{ quota.currency }}</Tag>
      </div>
    </template>
    <div class="quota-progress-grid">
      <div class="quota-progress-item">
        <div class="quota-progress-label"><span>Token</span><strong>{{ tokenRemainingText(quota.tokens_used, quota.tokens_reserved, quota.token_limit) }}</strong></div>
        <Progress :percent="usagePercent(quota.tokens_used, quota.tokens_reserved, quota.token_limit)" :show-info="false" size="small" />
      </div>
      <div class="quota-progress-item">
        <div class="quota-progress-label"><span>金额（{{ quota.currency }}）</span><strong>{{ remainingAmount(quota.amount_used, quota.amount_reserved, quota.amount_limit) }}</strong></div>
        <Progress :percent="amountUsagePercent(quota.amount_used, quota.amount_reserved, quota.amount_limit)" :show-info="false" status="active" size="small" />
      </div>
    </div>
    <div class="lai-table-wrap">
    <table
      class="lai-table"
      aria-label="额度使用明细"
    >
      <thead><tr><th>维度</th><th>上限</th><th>已用</th><th>预占</th><th>剩余</th></tr></thead>
      <tbody>
        <tr><th>Token</th><td>{{ quota.token_limit ?? '不限' }}</td><td>{{ quota.tokens_used }}</td><td>{{ quota.tokens_reserved }}</td><td>{{ tokenRemainingText(quota.tokens_used, quota.tokens_reserved, quota.token_limit) }}</td></tr>
        <tr><th>金额（{{ quota.currency }}）</th><td>{{ quota.amount_limit ?? '不限' }}</td><td>{{ quota.amount_used }}</td><td>{{ quota.amount_reserved }}</td><td>{{ remainingAmount(quota.amount_used, quota.amount_reserved, quota.amount_limit) }}</td></tr>
      </tbody>
    </table>
    <p>当前周期结束：{{ formatDateTime(quota.period_end, timezone, '未返回周期结束时间') }}</p>
    </div>
  </Card>
</template>

<style scoped>
.quota-summary-card { border: 1px solid var(--lai-border); box-shadow: 0 8px 24px rgba(37, 99, 235, .06); }
.quota-summary-title { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.quota-progress-grid { display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:16px; margin-bottom:18px; }
.quota-progress-item { padding:12px 14px; border:1px solid var(--lai-border); border-radius:10px; background:var(--lai-surface-muted); }
.quota-progress-label { display:flex; align-items:center; justify-content:space-between; margin-bottom:8px; color:var(--lai-text-secondary); font-size:13px; }
.quota-progress-label strong { color:var(--lai-text); font-size:14px; }
@media (max-width: 720px) { .quota-progress-grid { grid-template-columns: 1fr; } }
</style>
