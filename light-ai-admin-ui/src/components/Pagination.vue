<script setup lang="ts">
import { Pagination as AntPagination, Select } from 'ant-design-vue'

defineProps<{
  page: number
  pageSize: number
  total: number
}>()

const emit = defineEmits<{
  'page-change': [page: number]
  'page-size-change': [pageSize: number]
}>()

function onPageChange(page: number): void {
  emit('page-change', page)
}

function onPageSizeChange(pageSize: unknown): void {
  if (typeof pageSize === 'number') emit('page-size-change', pageSize)
}
</script>

<template>
  <div v-if="total > 0" class="lai-pagination">
    <span class="lai-pagination-total">共 {{ total }} 条</span>
    <AntPagination
      :current="page"
      :page-size="pageSize"
      :total="total"
      :show-size-changer="false"
      :show-less-items="true"
      show-quick-jumper
      @change="onPageChange"
    />
    <Select
      :value="pageSize"
      aria-label="每页条数"
      :options="[10, 20, 50].map(value => ({ value, label: `${value} 条/页` }))"
      @change="onPageSizeChange"
    />
  </div>
</template>

<style scoped>
.lai-pagination {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
}

.lai-pagination-total {
  color: var(--lai-color-text-secondary);
  font-size: 13px;
  white-space: nowrap;
}
</style>
