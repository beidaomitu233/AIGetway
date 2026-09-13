<script setup lang="ts">
import { computed } from 'vue'
import { Alert } from 'ant-design-vue'
import { ApiError, TimeoutError, toErrorMessage } from '@/api/errors'

const props = defineProps<{ error: ApiError | null }>()
defineEmits<{ reload: [] }>()

const description = computed(() => {
  if (!props.error) return ''
  const version = props.error.serverVersion === undefined ? '—' : String(props.error.serverVersion)
  const detail = props.error instanceof TimeoutError
    ? 'TIMEOUT · 未收到服务响应，请先核对服务器保存结果'
    : `${props.error.code} · 请求ID ${props.error.requestId}`
  return `${toErrorMessage(props.error)}；服务端最新版本：${version}；${detail}`
})
</script>

<template>
  <div v-if="error" class="lai-conflict-alert">
    <Alert
      type="warning"
      show-icon
      message="配置已被其他管理员修改，当前编辑内容已保留"
      :description="description"
    />
    <button class="lai-btn" type="button" @click="$emit('reload')">加载最新版本</button>
  </div>
</template>

<style scoped>
.lai-conflict-alert {
  display: grid;
  gap: 10px;
}
</style>
