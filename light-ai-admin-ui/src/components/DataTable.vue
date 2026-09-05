<script setup lang="ts" generic="T">
import { computed } from 'vue'

export interface TableColumn {
  key: string
  label: string
  width?: string
  /** 支持排序的列按服务端 sort 值排序。 */
  sortValue?: string
}

const props = defineProps<{
  columns: TableColumn[]
  rows: T[]
  rowKey: (row: T) => string
  sort?: string
  loading?: boolean
}>()

const emit = defineEmits<{ 'sort-change': [sortValue: string] }>()

const slots = defineSlots<{
  [K in string]?: (props: { row: T }) => unknown
}>()

const columnSlots = computed(() => props.columns.filter((c) => !!slots[c.key]))

function onHeaderClick(column: TableColumn): void {
  if (!column.sortValue) return
  emit('sort-change', column.sortValue)
}
</script>

<template>
  <div class="lai-table-wrap">
    <table class="lai-table">
      <thead>
        <tr>
          <th
            v-for="column in columns"
            :key="column.key"
            :style="column.width ? { width: column.width } : undefined"
            :class="{ 'lai-th-sortable': !!column.sortValue, 'lai-th-active': sort && column.sortValue === sort }"
            @click="onHeaderClick(column)"
          >
            {{ column.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in rows"
          :key="rowKey(row)"
        >
          <td
            v-for="column in columns"
            :key="column.key"
          >
            <slot
              v-if="columnSlots.some((c) => c.key === column.key)"
              :name="column.key"
              :row="row"
            />
            <template v-else>
              {{ (row as Record<string, unknown>)[column.key] ?? '—' }}
            </template>
          </td>
        </tr>
        <tr v-if="rows.length === 0 && !loading">
          <td
            :colspan="columns.length"
            class="lai-table-empty"
          >
            暂无数据
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
