<template>
  <section class="lai-page-card">
    <slot />
  </section>
</template>

<style scoped>
.lai-page-card {
  border: 1px solid var(--lai-color-border);
  border-radius: var(--lai-radius-card);
  background: var(--lai-color-surface);
  box-shadow: var(--lai-shadow-card);
  padding: 20px;
}
</style>
