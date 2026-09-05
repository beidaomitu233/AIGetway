import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { mount } from '@vue/test-utils'
import AppLayout from '@/layout/AppLayout.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { routes } from '@/app/router'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'

function buildRouter(): Router {
  return createRouter({ history: createMemoryHistory(), routes })
}

async function mountLayout(): Promise<ReturnType<typeof mount>> {
  const router = buildRouter()
  await router.push('/ui/overview')
  await router.isReady()
  const wrapper = mount(AppLayout, { global: { plugins: [router] } })
  return wrapper
}

describe('AppLayout 四角色导航', () => {
  it.each([
    {
      role: 'SYSTEM_ADMIN',
      expects: ['运行摘要', 'Provider', '凭证池', '模型', '模型别名', '限流策略', '熔断状态', 'Trace', '待发布变更', '配置发布', '访问凭证', '审计日志'],
      excludes: [],
    },
    {
      role: 'OPERATOR',
      expects: ['运行摘要', 'Provider', '凭证池', '模型', '模型别名', '熔断状态', 'Trace', '访问凭证', '审计日志'],
      excludes: [],
    },
    {
      role: 'DEVELOPER',
      expects: ['运行摘要', 'Provider', '模型', '模型别名', '限流策略', 'Trace', '待发布变更', '接入说明与测试'],
      excludes: ['凭证池', '审计日志'],
    },
    {
      role: 'VIEWER',
      expects: ['运行摘要', 'Provider', '模型', '模型别名', '限流策略', 'Trace', '待发布变更'],
      excludes: ['凭证池', '访问凭证', '审计日志'],
    },
  ])('$role 只能看到授权导航', async ({ role, expects, excludes }) => {
    setActivePinia(createPinia())
    const store = useBootstrapStore()
    store.$patch({
      status: 'ready',
      permissions: [...bootstrapFixtures[role].permissions],
      roles: [...bootstrapFixtures[role].roles],
    })
    const wrapper = await mountLayout()
    const text = wrapper.text()
    for (const item of expects) {
      expect(text).toContain(item)
    }
    for (const item of excludes) {
      expect(text).not.toContain(item)
    }
  })

  it('顶栏展示运行模式、快照、待发布数量与用户', async () => {
    setActivePinia(createPinia())
    const store = useBootstrapStore()
    store.$patch({
      status: 'ready',
      displayName: '系统管理员',
      roles: ['SYSTEM_ADMIN'],
      runtimeMode: 'STANDALONE_SERVER',
      currentSnapshotNo: 12,
      draftChangeCount: 3,
    })
    const wrapper = await mountLayout()
    const text = wrapper.text()
    expect(text).toContain('独立部署')
    expect(text).toContain('当前快照 #12')
    expect(text).toContain('待发布变更（3）')
    expect(text).toContain('系统管理员')
  })

  it('未知运行模式按服务端原值安全显示', async () => {
    setActivePinia(createPinia())
    useBootstrapStore().$patch({ status: 'ready', runtimeMode: 'FUTURE_MODE' })
    const wrapper = await mountLayout()
    expect(wrapper.text()).toContain('FUTURE_MODE')
  })
})
