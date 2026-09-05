<script setup lang="ts">
import { useRoute } from 'vue-router'
import PageState from '@/components/PageState.vue'

const route = useRoute()
</script>

<template>
  <section class="lai-page">
    <h1 class="lai-page-title">
      {{ route.meta.title }}
    </h1>
    <PageState status="empty" />
  </section>
</template>
