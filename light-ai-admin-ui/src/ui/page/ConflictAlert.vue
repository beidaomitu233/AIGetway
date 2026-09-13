<script setup lang="ts">
import { computed } from 'vue'
import { Alert, Button } from 'ant-design-vue'
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
  <Alert
    v-if="error"
    type="warning"
    show-icon
    message="配置已被其他管理员修改，当前编辑内容已保留"
    :description="description"
  >
    <template #action>
      <Button size="small" @click="$emit('reload')">加载最新版本</Button>
    </template>
  </Alert>
</template>
