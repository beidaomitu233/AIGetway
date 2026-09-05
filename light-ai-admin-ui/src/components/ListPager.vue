<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  page: number
  pageSize: number
  total: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
</script>

<template>
  <div class="lai-pager">
    <span class="lai-pager-total">共 {{ total }} 条</span>
    <label class="lai-pager-size">
      每页
      <select
        class="lai-input lai-select"
        :value="pageSize"
        :disabled="disabled"
        @change="emit('update:pageSize', Number(($event.target as HTMLSelectElement).value))"
      >
        <option :value="10">10</option>
        <option :value="20">20</option>
        <option :value="50">50</option>
      </select>
    </label>
    <span class="lai-pager-nav">
      <button
        type="button"
        class="lai-btn"
        :disabled="disabled || page <= 1"
        @click="emit('update:page', page - 1)"
      >
        上一页
      </button>
      <span class="lai-pager-count">{{ page }} / {{ totalPages }}</span>
      <button
        type="button"
        class="lai-btn"
        :disabled="disabled || page >= totalPages"
        @click="emit('update:page', page + 1)"
      >
        下一页
      </button>
    </span>
  </div>
</template>

<style scoped>
.lai-pager {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  font-size: 13px;
  color: #57606a;
}
.lai-pager-size {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.lai-select {
  width: auto;
  padding: 4px 8px;
}
.lai-pager-nav {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.lai-pager-count {
  min-width: 56px;
  text-align: center;
}
</style>
