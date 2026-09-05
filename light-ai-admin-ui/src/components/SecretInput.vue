<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    modelValue: string
    disabled?: boolean
    placeholder?: string
    name?: string
  }>(),
  {
    disabled: false,
    placeholder: '',
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const visible = ref(false)
const inputType = computed(() => (visible.value ? 'text' : 'password'))

function onInput(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

/** 密钥仅组件内存保存：组件销毁时清空，不进入存储或日志。 */
onUnmounted(() => {
  emit('update:modelValue', '')
})
</script>

<template>
  <div class="lai-secret">
    <input
      v-bind="$attrs"
      class="lai-input lai-secret-input"
      :type="inputType"
      :value="modelValue"
      :disabled="props.disabled"
      :placeholder="props.placeholder"
      :name="props.name"
      autocomplete="new-password"
      spellcheck="false"
      @input="onInput"
    >
    <button
      type="button"
      class="lai-btn lai-btn-text"
      @click="visible = !visible"
    >
      {{ visible ? '隐藏' : '显示' }}
    </button>
  </div>
</template>
