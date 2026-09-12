<script setup lang="ts">
import { ref, watch } from 'vue'
const props = defineProps<{ value: string }>()
defineEmits<{ close: [] }>()
const copied = ref(false)
const copying = ref(false)
const copyError = ref('')
watch(() => props.value, () => { copied.value = false; copyError.value = '' })
async function copy(): Promise<void> {
  if (copying.value) return
  copying.value = true
  copyError.value = ''
  try { await navigator.clipboard.writeText(props.value); copied.value = true }
  catch { copied.value = false; copyError.value = '复制失败，请手动选择密钥并保存。' }
  finally { copying.value = false }
}
</script>
<template>
  <div class="lai-dialog-overlay">
    <div
      class="lai-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="application-secret-title"
    >
      <h2
        id="application-secret-title"
        class="lai-dialog-title"
      >
        请立即保存应用密钥
      </h2>
      <p>这是唯一一次显示完整密钥。关闭后平台无法找回，只能重新轮换。</p>
      <code class="secret-value">{{ value }}</code>
      <p
        v-if="copyError"
        role="alert"
      >
        {{ copyError }}
      </p>
      <div class="lai-dialog-actions">
        <button
          type="button"
          class="lai-btn"
          :disabled="copying"
          @click="copy"
        >
          {{ copied ? '已复制' : '复制密钥' }}
        </button>
        <button
          type="button"
          class="lai-btn lai-btn-primary"
          @click="$emit('close')"
        >
          我已保存
        </button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.secret-value { display: block; overflow-wrap: anywhere; padding: 14px; margin: 14px 0; background: #f6f8fb; }
</style>
