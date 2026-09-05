<script setup lang="ts">
import { computed } from 'vue'
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
</script>

<template>
  <span class="lai-status-text">{{ text }}</span>
</template>
