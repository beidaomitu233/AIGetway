<script setup lang="ts">
import { Button, Result } from 'ant-design-vue'
</script>

<template>
  <section class="lai-page lai-boot-page">
    <Result
      status="404"
      title="页面不存在"
      sub-title="请求的地址没有对应页面。"
    >
      <template #extra>
        <RouterLink to="/ui/overview" class="lai-btn">
          <Button type="primary">返回概览</Button>
        </RouterLink>
      </template>
    </Result>
  </section>
</template>
