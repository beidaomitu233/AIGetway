<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <header class="lai-page-header">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="lai-page-header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.lai-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

h1 {
  margin: 0;
  color: var(--lai-color-text);
  font-size: 24px;
  line-height: 1.35;
}

p {
  margin: 6px 0 0;
  color: var(--lai-color-text-secondary);
  font-size: 14px;
}

.lai-page-header-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
</style>
