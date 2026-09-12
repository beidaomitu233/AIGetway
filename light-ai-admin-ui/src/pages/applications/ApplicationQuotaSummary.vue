<script setup lang="ts">
import type { ApplicationQuotaPolicy } from '@/api/applications'
import { remainingAmount } from './applicationValues'
import { formatDateTime } from '@/app/display'
defineProps<{ quota: ApplicationQuotaPolicy; timezone: string }>()
</script>
<template>
  <div class="lai-table-wrap">
    <table class="lai-table" aria-label="额度使用明细">
      <thead><tr><th>维度</th><th>上限</th><th>已用</th><th>预占</th><th>剩余</th></tr></thead>
      <tbody>
        <tr><th>Token</th><td>{{ quota.token_limit ?? '不限' }}</td><td>{{ quota.tokens_used }}</td><td>{{ quota.tokens_reserved }}</td><td>{{ quota.token_limit === null ? '不限' : quota.token_limit - quota.tokens_used - quota.tokens_reserved }}</td></tr>
        <tr><th>金额（{{ quota.currency }}）</th><td>{{ quota.amount_limit ?? '不限' }}</td><td>{{ quota.amount_used }}</td><td>{{ quota.amount_reserved }}</td><td>{{ remainingAmount(quota.amount_used, quota.amount_reserved, quota.amount_limit) }}</td></tr>
      </tbody>
    </table>
    <p>当前周期结束：{{ formatDateTime(quota.period_end, timezone, '未返回周期结束时间') }}</p>
  </div>
</template>
