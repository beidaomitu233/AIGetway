import type { Router } from 'vue-router'
import { useBootstrapStore } from '@/stores/bootstrap'

/** 导航守卫：加载 bootstrap，按权限拦截页面并跳转 403。 */
export function setupRouterGuards(router: Router): void {
  router.beforeEach(async (to) => {
    if (to.meta.public) return true
    const store = useBootstrapStore()
    if (store.status !== 'ready') {
      await store.load()
    }
    if (store.status === 'forbidden') {
      if (to.name === 'forbidden') return true
      return { name: 'forbidden', query: { from: to.fullPath } }
    }
    if (store.status === 'error') {
      // App 按 store.status 渲染全屏错误态，导航目标不再重要。
      return true
    }
    const permission = to.meta.permission as string | undefined
    if (permission && !store.can(permission)) {
      return { name: 'forbidden', query: { from: to.fullPath } }
    }
    return true
  })
}
