<script setup lang="ts">
import { Pagination as AntPagination } from 'ant-design-vue'

defineProps<{
  page: number
  pageSize: number
  total: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
}>()
</script>

<template>
  <div class="lai-pager">
    <span class="lai-pager-total">共 {{ total }} 条</span>
    <AntPagination
      :current="page"
      :page-size="pageSize"
      :total="total"
      :disabled="disabled"
      :show-size-changer="true"
      :page-size-options="['10', '20', '50']"
      show-quick-jumper
      @change="(nextPage, nextPageSize) => { emit('update:page', nextPage); emit('update:pageSize', nextPageSize) }"
    />
  </div>
</template>

<style scoped>
.lai-pager {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  color: var(--lai-color-text-secondary);
  font-size: 13px;
}
</style>
