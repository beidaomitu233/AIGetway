<script setup lang="ts">
defineProps<{
  label: string
  forId?: string
  required?: boolean
  /** 该字段的校验错误信息。 */
  error?: string
  hint?: string
}>()
</script>

<template>
  <div
    class="lai-form-field"
    :class="{ 'lai-form-field-error': !!error }"
  >
    <label
      :for="forId"
      class="lai-form-label"
    >
      {{ label }}
      <span
        v-if="required"
        class="lai-required"
        aria-hidden="true"
      >*</span>
    </label>
    <slot />
    <p
      v-if="error"
      class="lai-form-message-error"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-else-if="hint"
      class="lai-form-hint"
    >
      {{ hint }}
    </p>
  </div>
</template>
