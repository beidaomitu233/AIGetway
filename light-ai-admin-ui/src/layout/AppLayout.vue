<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ApiOutlined,
  AppstoreOutlined,
  BarChartOutlined,
  BookOutlined,
  CloudServerOutlined,
  ControlOutlined,
  DashboardOutlined,
  FileSearchOutlined,
  FundOutlined,
  KeyOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SettingOutlined,
  SafetyOutlined,
  ToolOutlined,
} from '@ant-design/icons-vue'
import {
  Avatar,
  Badge,
  Breadcrumb,
  Button,
  Layout,
  Menu,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'ant-design-vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { navSections, type NavItem, type NavSection } from '@/app/navConfig'
import { displayLabel, runtimeModeLabel } from '@/app/display'
import { RoleLabels } from '@/app/permissions'

const store = useBootstrapStore()
const route = useRoute()
const router = useRouter()
const collapsed = ref(false)

const visibleSections = computed<NavSection[]>(() =>
  navSections
    .map((section) => ({
      title: section.title,
      items: section.items.filter((item) => store.can(item.permission)),
    }))
    .filter((section) => section.items.length > 0),
)

const roleText = computed(
  () => store.roles.map((role) => displayLabel(RoleLabels, role)).join(' / ') || '—',
)

const breadcrumbs = computed(() => {
  const seen = new Set<string>()
  return route.matched
    .map((record) => String(record.meta.title ?? ''))
    .filter((title) => title && !seen.has(title) && seen.add(title))
})

const iconMap: Record<string, Component> = {
  运行摘要: DashboardOutlined,
  应用: AppstoreOutlined,
  渠道: CloudServerOutlined,
  上游模型: ApiOutlined,
  虚拟模型与路由: ControlOutlined,
  限流策略: ToolOutlined,
  可靠性策略: SettingOutlined,
  熔断状态: FundOutlined,
  调用记录: FileSearchOutlined,
  用量与成本: BarChartOutlined,
  额度流水: FundOutlined,
  待发布变更: BookOutlined,
  配置发布: SettingOutlined,
  运行参数: ToolOutlined,
  旧访问凭证: KeyOutlined,
  审计日志: FileSearchOutlined,
  风险控制: SafetyOutlined,
  接入说明与测试: ApiOutlined,
}

function itemIcon(item: NavItem): Component {
  return iconMap[item.title] ?? AppstoreOutlined
}

function go(to: string): void {
  void router.push(to)
}

function toggleCollapsed(): void {
  collapsed.value = !collapsed.value
}
</script>

<template>
  <Layout class="lai-layout">
    <Layout.Sider
      :collapsed="collapsed"
      :width="248"
      :collapsed-width="72"
      theme="light"
      class="lai-sider"
      breakpoint="lg"
      collapsible
      :trigger="null"
    >
      <div
        class="lai-brand"
        aria-label="轻享 AI 管理后台"
      >
        <div class="lai-brand-mark">
          AI
        </div>
        <div
          v-if="!collapsed"
          class="lai-brand-copy"
        >
          <Typography.Title :level="4">
            轻享 AI
          </Typography.Title>
          <Typography.Text>企业 AI 中台</Typography.Text>
        </div>
      </div>
      <Menu
        :selected-keys="[route.path]"
        mode="inline"
        theme="light"
        class="lai-menu"
        aria-label="主导航"
      >
        <Menu.ItemGroup
          v-for="section in visibleSections"
          :key="section.title"
          :title="collapsed ? undefined : section.title"
        >
          <Menu.Item
            v-for="item in section.items"
            :key="item.to"
            @click="go(item.to)"
          >
            <template #icon>
              <component :is="itemIcon(item)" />
            </template>
            {{ item.title }}
          </Menu.Item>
        </Menu.ItemGroup>
      </Menu>
    </Layout.Sider>

    <Layout class="lai-main">
      <Layout.Header class="lai-header">
        <Space
          :size="16"
          class="lai-header-left"
        >
          <Tooltip :title="collapsed ? '展开导航' : '收起导航'">
            <Button
              type="text"
              class="lai-collapse-button"
              :aria-label="collapsed ? '展开导航' : '收起导航'"
              @click="toggleCollapsed"
            >
              <template #icon>
                <component :is="collapsed ? MenuUnfoldOutlined : MenuFoldOutlined" />
              </template>
            </Button>
          </Tooltip>
          <Breadcrumb class="lai-breadcrumb">
            <Breadcrumb.Item
              v-for="title in breadcrumbs"
              :key="title"
            >
              {{ title }}
            </Breadcrumb.Item>
          </Breadcrumb>
        </Space>
        <Space
          :size="16"
          class="lai-header-right"
        >
          <Tag color="blue">
            {{ runtimeModeLabel(store.runtimeMode) }}
          </Tag>
          <span class="lai-snapshot">当前快照 #{{ store.currentSnapshotNo ?? '—' }}</span>
          <Badge
            :count="store.draftChangeCount"
            :overflow-count="99"
            :offset="[-4, 4]"
          >
            <Button
              type="text"
              @click="go('/ui/config/drafts')"
            >
              待发布变更（{{ store.draftChangeCount }}）
            </Button>
          </Badge>
          <Tooltip :title="`${store.displayName} · ${roleText}`">
            <Space :size="8">
              <Avatar class="lai-avatar">
                {{ store.displayName.slice(0, 1) || 'U' }}
              </Avatar>
              <span class="lai-user-name">{{ store.displayName || '未命名用户' }}</span>
            </Space>
          </Tooltip>
        </Space>
      </Layout.Header>
      <Layout.Content class="lai-content">
        <RouterView />
      </Layout.Content>
    </Layout>
  </Layout>
</template>

<style scoped>
.lai-layout {
  min-height: 100vh;
  background: var(--lai-color-layout);
}

.lai-sider {
  border-inline-end: 1px solid var(--lai-color-border);
  background: var(--lai-color-surface);
}

.lai-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 72px;
  padding: 0 20px;
  border-bottom: 1px solid var(--lai-color-border);
}

.lai-brand-mark {
  display: grid;
  flex: 0 0 36px;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--lai-color-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.lai-brand-copy {
  min-width: 0;
}

.lai-brand-copy :deep(.ant-typography) {
  display: block;
  margin: 0;
  white-space: nowrap;
}

.lai-brand-copy :deep(.ant-typography-title) {
  color: var(--lai-color-text);
  font-size: 16px;
}

.lai-brand-copy :deep(.ant-typography:not(.ant-typography-title)) {
  color: var(--lai-color-text-secondary);
  font-size: 12px;
}

.lai-menu {
  border-inline-end: 0;
  padding: 12px 10px;
}

.lai-menu :deep(.ant-menu-item-group-title) {
  padding: 12px 14px 6px;
  color: var(--lai-color-text-secondary);
  font-size: 12px;
}

.lai-main {
  min-width: 0;
  background: var(--lai-color-layout);
}

.lai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 28px;
  border-bottom: 1px solid var(--lai-color-border);
  background: rgba(255, 255, 255, 0.96);
}

.lai-header-left,
.lai-header-right {
  min-width: 0;
}

.lai-breadcrumb {
  white-space: nowrap;
}

.lai-collapse-button {
  color: var(--lai-color-text-secondary);
}

.lai-snapshot {
  color: var(--lai-color-text-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.lai-avatar {
  background: #dbeafe;
  color: var(--lai-color-primary);
  font-weight: 600;
}

.lai-user-name {
  color: var(--lai-color-text);
  font-size: 13px;
  white-space: nowrap;
}

.lai-content {
  min-height: calc(100vh - 64px);
  padding: 28px;
}

@media (max-width: 900px) {
  .lai-header {
    padding: 0 16px;
  }

  .lai-snapshot {
    display: none;
  }

  .lai-content {
    padding: 16px;
  }
}
</style>
