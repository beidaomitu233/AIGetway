<script setup lang="ts">
import { App as AntApp, ConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { useBootstrapStore } from '@/stores/bootstrap'
import AppLayout from '@/layout/AppLayout.vue'
import PageState from '@/components/PageState.vue'
import ForbiddenPage from '@/pages/forbidden/ForbiddenPage.vue'
import { lightAiTheme } from '@/design/theme'

const store = useBootstrapStore()
</script>

<template>
  <ConfigProvider
    :locale="zhCN"
    :theme="lightAiTheme"
    component-size="middle"
  >
    <AntApp>
      <div class="light-ai-ui">
        <div
          v-if="store.status === 'idle' || store.status === 'loading'"
          class="lai-boot"
        >
          <PageState status="loading" />
        </div>
        <div
          v-else-if="store.status === 'error'"
          class="lai-boot"
        >
          <PageState
            status="error"
            :error="store.error"
            @retry="store.load()"
          />
        </div>
        <ForbiddenPage v-else-if="store.status === 'forbidden'" />
        <AppLayout v-else />
      </div>
    </AntApp>
  </ConfigProvider>
</template>
