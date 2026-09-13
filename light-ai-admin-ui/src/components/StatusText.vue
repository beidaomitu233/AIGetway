<script setup lang="ts">
import { computed } from 'vue'
import { Tag } from 'ant-design-vue'
import { displayLabel } from '@/app/display'

const props = withDefaults(
  defineProps<{
    value: string | null | undefined
    labels: Record<string, string>
    /** 空值占位文本，如“未检测”“暂无”。 */
    placeholder?: string
  }>(),
  {
    placeholder: '—',
  },
)

const text = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') {
    return props.placeholder
  }
  return displayLabel(props.labels, props.value)
})

const color = computed(() => {
  const value = props.value ?? ''
  if (/^(ACTIVE|HEALTHY|OK|SUCCEEDED|SUCCESS|CLOSED)$/i.test(value)) return 'green'
  if (/^(DISABLED|DEGRADED|PENDING|OPEN)$/i.test(value)) return 'orange'
  if (/(ERROR|FAILED|REVOKED|BLOCKED|TIMEOUT)/i.test(value)) return 'red'
  return 'default'
})
</script>

<template>
  <Tag :color="color" class="lai-status-text">{{ text }}</Tag>
</template>
