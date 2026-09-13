<script setup lang="ts">
import { Select } from 'ant-design-vue'

const props = withDefaults(
  defineProps<{
    /** 全部可选项：{ value, label }。 */
    options: Array<{ value: string; label: string }>
    modelValue: string[]
    placeholder?: string
    disabled?: boolean
  }>(),
  {
    placeholder: '全部',
    disabled: false,
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

function onChange(value: unknown): void {
  emit('update:modelValue', Array.isArray(value) ? value.map((item) => String(item)) : [])
}
</script>

<template>
  <Select
    class="lai-filter-select app-multi-select"
    mode="multiple"
    :value="props.modelValue"
    :options="props.options"
    :placeholder="props.placeholder"
    :disabled="props.disabled"
    :max-tag-count="2"
    allow-clear
    @change="onChange"
  />
</template>
