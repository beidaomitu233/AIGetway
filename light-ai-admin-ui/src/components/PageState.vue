<script setup lang="ts">
import { computed } from 'vue'
import { ApiError, TimeoutError, toErrorMessage } from '@/api/errors'

const props = defineProps<{
  status: 'loading' | 'empty' | 'error'
  error?: unknown
  /** 覆盖默认文案（empty 的空态提示或 error 的补充说明）。 */
  message?: string
}>()

defineEmits<{ retry: [] }>()

const errorText = computed(() => toErrorMessage(props.error))

const errorDetail = computed(() => {
  const error = props.error
  if (error instanceof ApiError) {
    return `${error.code} · 请求ID ${error.requestId}`
  }
  if (error instanceof TimeoutError) {
    return 'TIMEOUT · 未收到服务响应'
  }
  return ''
})
</script>

<template>
  <div v-if="status === 'loading'" class="lai-skeleton" role="status" aria-label="加载中">
    <div v-for="n in 5" :key="n" class="lai-skeleton-row"></div>
  </div>
  <div v-else-if="status === 'empty'" class="lai-empty">
    <p class="lai-empty-text">{{ message ?? '暂无数据' }}</p>
  </div>
  <div v-else class="lai-error" role="alert">
    <p class="lai-error-text">{{ errorText }}</p>
    <p v-if="errorDetail" class="lai-error-meta">{{ errorDetail }}</p>
    <button type="button" class="lai-btn" @click="$emit('retry')">重试</button>
  </div>
</template>
