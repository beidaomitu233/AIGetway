<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Button, Result } from 'ant-design-vue'

const router = useRouter()
</script>

<template>
  <section class="lai-page lai-boot-page">
    <Result
      status="403"
      title="无访问权限"
      sub-title="当前身份没有访问该页面的权限。如需访问，请联系管理员开通相应角色。"
    >
      <template #extra>
        <Button class="lai-btn" @click="router.back()">返回上一页</Button>
      </template>
    </Result>
  </section>
</template>
