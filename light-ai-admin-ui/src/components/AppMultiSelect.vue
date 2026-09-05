<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

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

const root = ref<HTMLDivElement | null>(null)
const open = ref(false)

function onDocumentClick(event: MouseEvent): void {
  if (open.value && root.value && !root.value.contains(event.target as Node)) {
    open.value = false
  }
}
onMounted(() => document.addEventListener('click', onDocumentClick))
onUnmounted(() => document.removeEventListener('click', onDocumentClick))

const selectedLabels = computed(() => {
  if (props.modelValue.length === 0) return props.placeholder
  return props.modelValue
    .map((value) => props.options.find((o) => o.value === value)?.label ?? value)
    .join('、')
})

function toggle(value: string): void {
  const next = props.modelValue.includes(value)
    ? props.modelValue.filter((v) => v !== value)
    : [...props.modelValue, value]
  emit('update:modelValue', next)
}

function clear(): void {
  emit('update:modelValue', [])
}
</script>

<template>
  <div
    ref="root"
    class="lai-multiselect"
    :class="{ 'lai-multiselect-open': open }"
  >
    <button
      type="button"
      class="lai-select lai-multiselect-trigger"
      :disabled="props.disabled"
      @click="open = !open"
    >
      <span :class="{ 'lai-multiselect-placeholder': modelValue.length === 0 }">
        {{ selectedLabels }}
      </span>
      <span class="lai-multiselect-arrow">▾</span>
    </button>
    <div
      v-if="open"
      class="lai-multiselect-panel"
    >
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        class="lai-multiselect-option"
        @click.stop="toggle(option.value)"
      >
        <input
          type="checkbox"
          :checked="modelValue.includes(option.value)"
          tabindex="-1"
        >
        <span>{{ option.label }}</span>
      </button>
      <button
        v-if="modelValue.length > 0"
        type="button"
        class="lai-btn lai-btn-text lai-multiselect-clear"
        @click.stop="clear"
      >
        清空
      </button>
    </div>
  </div>
</template>
