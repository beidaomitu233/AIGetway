<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    hint?: string
    status?: 'default' | 'success' | 'warning' | 'error'
  }>(),
  { status: 'default' },
)
</script>

<template>
  <article class="lai-metric-card" :class="`lai-metric-${status}`">
    <span class="lai-metric-label">{{ label }}</span>
    <strong class="lai-metric-value">{{ value }}</strong>
    <span v-if="hint" class="lai-metric-hint">{{ hint }}</span>
  </article>
</template>

<style scoped>
.lai-metric-card {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 18px 20px;
  border: 1px solid var(--lai-color-border);
  border-radius: var(--lai-radius-card);
  background: var(--lai-color-surface);
  box-shadow: var(--lai-shadow-card);
}

.lai-metric-label,
.lai-metric-hint {
  color: var(--lai-color-text-secondary);
  font-size: 13px;
}

.lai-metric-value {
  color: var(--lai-color-text);
  font-size: 26px;
  line-height: 1.1;
}

.lai-metric-success .lai-metric-value { color: var(--lai-color-success); }
.lai-metric-warning .lai-metric-value { color: var(--lai-color-warning); }
.lai-metric-error .lai-metric-value { color: var(--lai-color-error); }
</style>
