<script setup lang="ts">
defineProps<{
  title?: string
  description?: string
}>()
</script>

<template>
  <section class="lai-page-section">
    <div v-if="title || description" class="lai-page-section-heading">
      <h2 v-if="title">{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
    </div>
    <slot />
  </section>
</template>

<style scoped>
.lai-page-section {
  display: grid;
  gap: 14px;
}

.lai-page-section-heading h2 {
  margin: 0;
  color: var(--lai-color-text);
  font-size: 16px;
}

.lai-page-section-heading p {
  margin: 4px 0 0;
  color: var(--lai-color-text-secondary);
  font-size: 13px;
}
</style>
