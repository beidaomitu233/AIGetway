<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: Record<string, string>
    disabled?: boolean
  }>(),
  {
    disabled: false,
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: Record<string, string>] }>()

/** 禁止写入 default_headers 的认证与 Cookie 类请求头（不区分大小写）。 */
const FORBIDDEN_HEADERS = [
  'authorization',
  'x-api-key',
  'api-key',
  'cookie',
  'set-cookie',
  'proxy-authorization',
  'x-auth-token',
  'x-goog-api-key',
  'anthropic-api-key',
]

interface HeaderRow {
  key: string
  value: string
}

const rows = computed<HeaderRow[]>(() =>
  Object.entries(props.modelValue).map(([key, value]) => ({ key, value })),
)

const duplicateKeys = computed(() => {
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const row of rows.value) {
    const normalized = row.key.trim().toLowerCase()
    if (normalized === '') continue
    if (seen.has(normalized)) duplicates.add(normalized)
    seen.add(normalized)
  }
  return duplicates
})

const forbiddenKeys = computed(() =>
  rows.value.filter((row) => FORBIDDEN_HEADERS.includes(row.key.trim().toLowerCase())).map((row) => row.key),
)

const overLimit = computed(() => rows.value.length > 20)

function emitRows(next: HeaderRow[]): void {
  const result: Record<string, string> = {}
  for (const row of next) {
    if (row.key.trim() === '') continue
    result[row.key.trim()] = row.value
  }
  emit('update:modelValue', result)
}

function onKeyChange(index: number, key: string): void {
  const next = rows.value.map((row, i) => (i === index ? { ...row, key } : row))
  emitRows(next)
}

function onValueChange(index: number, value: string): void {
  const next = rows.value.map((row, i) => (i === index ? { ...row, value } : row))
  emitRows(next)
}

function addRow(): void {
  emitRows([...rows.value, { key: '', value: '' }])
}

function removeRow(index: number): void {
  emitRows(rows.value.filter((_, i) => i !== index))
}

function rowError(row: HeaderRow): string {
  const normalized = row.key.trim().toLowerCase()
  if (normalized !== '' && FORBIDDEN_HEADERS.includes(normalized)) return '禁止认证与 Cookie 类请求头'
  if (normalized !== '' && duplicateKeys.value.has(normalized)) return '键名重复（不区分大小写）'
  return ''
}
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
      v-if="overLimit"
      class="lai-form-message-error"
    >
      请求头最多 20 项
    </p>
    <button
      type="button"
      class="lai-btn"
      :disabled="props.disabled || overLimit"
      @click="addRow"
    >
      添加请求头
    </button>
    <p
      v-if="forbiddenKeys.length > 0"
      class="lai-form-message-error"
    >
      含禁止请求头：{{ forbiddenKeys.join('、') }}
    </p>
  </div>
</template>
