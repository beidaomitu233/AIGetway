<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * Token 一次性展示弹窗（附录 4.5.4.3）：
 * 阻断式，要求勾选"已安全保存"后才能关闭；关闭即无法再次取得原文。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    tokenValue: string
    issuedAt: string | null
    rotationGeneration: number
    timezone: string
    copyState?: string
  }>(),
  {
    copyState: '',
  },
)

const emit = defineEmits<{ 'update:open': [value: boolean]; copied: [] }>()

const saved = ref(false)

watch(
  () => props.open,
  (open) => {
    if (open) saved.value = false
  },
)

const canClose = computed(() => saved.value)

function close(): void {
  if (!canClose.value) return
  emit('update:open', false)
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="lai-dialog-overlay"
      @keydown.esc.prevent
    >
      <div
        class="lai-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
      >
        <h2 class="lai-dialog-title">
          {{ title }}
        </h2>
        <p class="lai-dialog-message lai-check-fail">
          该 Token 仅本次显示，关闭后无法再次取得原文；遗失时只能执行轮换。
        </p>
        <div class="lai-token-box">
          <code class="lai-token-value">{{ tokenValue }}</code>
          <button
            type="button"
            class="lai-btn lai-btn-text"
            @click="emit('copied')"
          >
            {{ copyState || '复制' }}
          </button>
        </div>
        <p class="lai-related-meta">
          签发时间：{{ issuedAt ?? '—' }} · 代次：{{ rotationGeneration }}
        </p>
        <label class="lai-warning-label lai-token-saved">
          <input
            v-model="saved"
            type="checkbox"
            class="lai-checkbox"
          >
          我已将 Token 保存在安全位置
        </label>
        <div class="lai-dialog-actions">
          <button
            type="button"
            class="lai-btn lai-btn-primary"
            :disabled="!canClose"
            @click="close"
          >
            已安全保存，关闭
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
