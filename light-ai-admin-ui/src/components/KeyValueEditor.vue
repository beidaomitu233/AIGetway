<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { headerRowError, headersValid, type HeaderRow } from '@/utils/resourceValidation'
const props = withDefaults(defineProps<{ modelValue: Record<string, string>; disabled?: boolean }>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, string>]; validity: [valid: boolean] }>()
const rows = ref<HeaderRow[]>([])
let lastEmitted: Record<string, string> | null = null
watch(() => props.modelValue, (value) => {
  if (value === lastEmitted) return
  rows.value = Object.entries(value).map(([key, value]) => ({ key, value }))
  emit('validity', headersValid(rows.value))
}, { immediate: true })
const atLimit = computed(() => rows.value.length >= 20)
function publish(): void {
  const valid = headersValid(rows.value)
  emit('validity', valid)
  // Keep invalid/blank rows locally; never silently collapse duplicates into a payload.
  if (!valid) return
  lastEmitted = Object.fromEntries(rows.value.map((row) => [row.key.trim(), row.value]))
  emit('update:modelValue', lastEmitted)
}
function onKeyChange(index: number, key: string): void {
  rows.value[index]!.key = key
  publish()
}
function onValueChange(index: number, value: string): void {
  rows.value[index]!.value = value
  publish()
}
function addRow(): void {
  if (props.disabled || atLimit.value) return
  rows.value.push({ key: '', value: '' })
  publish()
}
function removeRow(index: number): void {
  rows.value.splice(index, 1)
  publish()
}
function rowError(row: HeaderRow): string { return headerRowError(row, rows.value) }
</script>

<template>
  <div class="lai-kv">
    <div
      v-for="(row, index) in rows"
      :key="index"
      class="lai-kv-row"
    >
      <input
        class="lai-input lai-kv-key"
        type="text"
        :value="row.key"
        :disabled="props.disabled"
        placeholder="请求头名称"
        @input="onKeyChange(index, ($event.target as HTMLInputElement).value)"
      >
      <input
        class="lai-input lai-kv-value"
        type="text"
        :value="row.value"
        :disabled="props.disabled"
        placeholder="值"
        @input="onValueChange(index, ($event.target as HTMLInputElement).value)"
      >
      <button
        type="button"
        class="lai-btn lai-btn-text"
        :disabled="props.disabled"
        @click="removeRow(index)"
      >
        移除
      </button>
      <span
        v-if="rowError(row)"
        class="lai-form-message-error lai-kv-error"
      >
        {{ rowError(row) }}
      </span>
    </div>
    <p
      v-if="atLimit"
      class="lai-form-message-error"
    >
      请求头最多 20 项
    </p>
    <button
      type="button"
      class="lai-btn"
      :disabled="props.disabled || atLimit"
      @click="addRow"
    >
      添加请求头
    </button>
  </div>
</template>
