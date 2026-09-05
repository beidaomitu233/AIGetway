<script setup lang="ts">
import { computed } from 'vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { navSections, type NavSection } from '@/app/navConfig'
import { displayLabel, runtimeModeLabel } from '@/app/display'
import { RoleLabels } from '@/app/permissions'

const store = useBootstrapStore()

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
</script>

<template>
  <div class="lai-app">
    <aside class="lai-side">
      <div class="lai-side-brand">轻享 AI</div>
      <nav class="lai-nav" aria-label="主导航">
        <div v-for="section in visibleSections" :key="section.title" class="lai-nav-section">
          <div class="lai-nav-section-title">{{ section.title }}</div>
          <RouterLink
            v-for="item in section.items"
            :key="item.to"
            :to="item.to"
            class="lai-nav-item"
          >
            {{ item.title }}
          </RouterLink>
        </div>
      </nav>
    </aside>
    <div class="lai-body">
      <header class="lai-topbar">
        <span class="lai-topbar-mode">{{ runtimeModeLabel(store.runtimeMode) }}</span>
        <span class="lai-topbar-item">当前快照 #{{ store.currentSnapshotNo ?? '—' }}</span>
        <RouterLink to="/ui/config/drafts" class="lai-topbar-link">
          待发布变更（{{ store.draftChangeCount }}）
        </RouterLink>
        <span class="lai-topbar-user">{{ store.displayName }}</span>
        <span class="lai-topbar-role">{{ roleText }}</span>
      </header>
      <main class="lai-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
