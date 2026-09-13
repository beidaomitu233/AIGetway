<script setup lang="ts">
import AsyncState from '@/ui/page/AsyncState.vue'

defineProps<{
  status: 'loading' | 'empty' | 'error'
  error?: unknown
  message?: string
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <AsyncState
    :status="status"
    :error="error"
    :message="message"
    @retry="$emit('retry')"
  />
</template>
