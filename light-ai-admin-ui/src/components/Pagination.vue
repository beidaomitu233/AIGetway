<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  page: number
  pageSize: number
  total: number
}>()

const emit = defineEmits<{
  'page-change': [page: number]
  'page-size-change': [pageSize: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const pageNumbers = computed<number[]>(() => {
  const pages: number[] = []
  const start = Math.max(1, props.page - 2)
  const end = Math.min(totalPages.value, start + 4)
  for (let i = Math.max(1, end - 4); i <= end; i += 1) pages.push(i)
  return pages
})
const pageSizeOptions = [10, 20, 50]

function go(page: number): void {
  if (page < 1 || page > totalPages.value || page === props.page) return
  emit('page-change', page)
}
</script>

<template>
  <div
    v-if="total > 0"
    class="lai-pagination"
  >
    <span class="lai-pagination-total">共 {{ total }} 条</span>
    <button
      type="button"
      class="lai-btn lai-pagination-btn"
      :disabled="page <= 1"
      @click="go(page - 1)"
    >
      上一页
    </button>
    <button
      v-for="n in pageNumbers"
      :key="n"
      type="button"
      class="lai-btn lai-pagination-btn"
      :class="{ 'lai-btn-primary': n === page }"
      @click="go(n)"
    >
      {{ n }}
    </button>
    <button
      type="button"
      class="lai-btn lai-pagination-btn"
      :disabled="page >= totalPages"
      @click="go(page + 1)"
    >
      下一页
    </button>
    <select
      class="lai-select"
      :value="pageSize"
      aria-label="每页条数"
      @change="emit('page-size-change', Number(($event.target as HTMLSelectElement).value))"
    >
      <option
        v-for="n in pageSizeOptions"
        :key="n"
        :value="n"
      >
        {{ n }} 条/页
      </option>
    </select>
  </div>
</template>
