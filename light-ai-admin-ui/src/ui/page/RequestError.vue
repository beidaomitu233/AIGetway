<script setup lang="ts">
import { computed } from 'vue'
import { Alert, Button } from 'ant-design-vue'
import { ApiError, TimeoutError, toErrorMessage } from '@/api/errors'

const props = defineProps<{ error?: unknown }>()
defineEmits<{ retry: [] }>()

const description = computed(() => {
  if (props.error instanceof ApiError) {
    return `${toErrorMessage(props.error)} · ${props.error.code} · 请求ID ${props.error.requestId}`
  }
  if (props.error instanceof TimeoutError) {
    return '请求超时，未收到服务响应。请稍后重试。'
  }
  return toErrorMessage(props.error)
})
</script>

<template>
  <Alert
    type="error"
    show-icon
    message="加载失败"
    :description="description"
  >
    <template #action>
      <Button size="small" @click="$emit('retry')">重试</Button>
    </template>
  </Alert>
</template>
