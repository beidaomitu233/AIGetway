<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError, TimeoutError, toErrorMessage } from '@/api/errors'

/** 表单保存出现版本冲突时展示：保留用户输入，提供重新加载动作。 */
const props = defineProps<{
  error: ApiError | null
}>()

const emit = defineEmits<{ reload: [] }>()

const visible = computed(() => props.error !== null)
const serverVersionText = computed(() =>
  props.error?.serverVersion !== undefined ? String(props.error.serverVersion) : '—',
)
const detailText = computed(() => {
  if (props.error instanceof TimeoutError) {
    return 'TIMEOUT · 未收到服务响应，请先核对服务器保存结果'
  }
  if (props.error instanceof ApiError) {
    return `${props.error.code} · 请求ID ${props.error.requestId}`
  }
  return ''
})
const messageText = computed(() =>
  props.error ? toErrorMessage(props.error) : '',
)
const expanded = ref(false)

watch(visible, (value) => {
  if (!value) expanded.value = false
})
</script>

<template>
  <div
    v-if="visible"
    class="lai-conflict-banner"
    role="alert"
  >
    <p class="lai-conflict-title">
      配置已被其他管理员修改，当前编辑内容已保留
    </p>
    <p class="lai-conflict-meta">
      {{ messageText }}
    </p>
    <p class="lai-conflict-meta">
      服务端最新版本：{{ serverVersionText }}
      <template v-if="detailText">
        · {{ detailText }}
      </template>
    </p>
    <div class="lai-conflict-actions">
      <button
        type="button"
        class="lai-btn"
        @click="emit('reload')"
      >
        加载最新版本（放弃本地修改）
      </button>
    </div>
  </div>
</template>
