<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <section class="lai-page lai-boot-page">
    <h1 class="lai-page-title">无访问权限</h1>
    <p class="lai-page-text">当前身份没有访问该页面的权限。如需访问，请联系管理员开通相应角色。</p>
    <button type="button" class="lai-btn" @click="router.back()">返回上一页</button>
  </section>
</template>
