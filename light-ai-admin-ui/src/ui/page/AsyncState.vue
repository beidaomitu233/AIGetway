<script setup lang="ts">
import { Empty, Skeleton } from 'ant-design-vue'
import RequestError from './RequestError.vue'

withDefaults(
  defineProps<{
    status: 'loading' | 'empty' | 'error'
    error?: unknown
    message?: string
  }>(),
  { message: '暂无数据' },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="status === 'loading'" class="lai-async-loading" role="status" aria-label="加载中">
    <Skeleton active :paragraph="{ rows: 3 }" />
  </div>
  <Empty v-else-if="status === 'empty'" :description="message" />
  <RequestError v-else :error="error" @retry="$emit('retry')" />
</template>

<style scoped>
.lai-async-loading {
  padding: 8px 0;
}
</style>
