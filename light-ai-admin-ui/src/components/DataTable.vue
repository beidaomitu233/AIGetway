<script setup lang="ts" generic="T">
import { computed } from 'vue'
import { Table } from 'ant-design-vue'
import type { ColumnType, SorterResult } from 'ant-design-vue/es/table/interface'

export interface TableColumn {
  key: string
  label: string
  width?: string
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

const antColumns = computed<ColumnType<T>[]>(() =>
  props.columns.map((column) => ({
    key: column.key,
    dataIndex: column.key,
    title: column.label,
    width: column.width,
    sorter: !!column.sortValue,
    sortOrder: props.sort === column.sortValue ? 'ascend' : undefined,
  })),
)

function onChange(
  _pagination: unknown,
  _filters: unknown,
  sorter: SorterResult<T> | SorterResult<T>[],
): void {
  const currentSorter = Array.isArray(sorter) ? sorter[0] : sorter
  const column = props.columns.find((item) => item.key === String(currentSorter?.columnKey ?? ''))
  if (column?.sortValue) emit('sort-change', column.sortValue)
}

function hasSlot(key: unknown): key is string {
  return typeof key === 'string' && !!slots[key]
}
</script>

<template>
  <div class="lai-table-wrap">
    <Table
      class="lai-table"
      :columns="antColumns"
      :data-source="rows"
      :row-key="rowKey"
      :loading="loading"
      :pagination="false"
      @change="onChange"
    >
      <template #bodyCell="{ column, record }">
        <slot
          v-if="hasSlot(column.key)"
          :name="column.key"
          :row="record as T"
        />
        <template v-else>
          {{ (record as Record<string, unknown>)[String(column.key)] ?? '—' }}
        </template>
      </template>
      <template #emptyText>
        <span class="lai-table-empty">暂无数据</span>
      </template>
    </Table>
  </div>
</template>

<style scoped>
.lai-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.lai-table :deep(.ant-table) {
  min-width: 680px;
}

.lai-table :deep(.ant-table-thead > tr > th) {
  background: #f7f9fc;
  color: var(--lai-color-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.lai-table :deep(.ant-table-tbody > tr:hover > td) {
  background: #f7faff;
}
</style>
