import { describe, expect, it } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { routes } from '@/app/router'
import { navSections } from '@/app/navConfig'

describe('FE-212 旧池入口迁移', () => {
  it.each(['/ui/credential-pools', '/ui/credential-pools/new', '/ui/credential-pools/old-id', '/ui/credential-pools/old-id/edit'])('旧入口 %s 返回渠道列表，不猜测旧 ID 映射', async (path) => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    await router.push(path)
    expect(router.currentRoute.value.path).toBe('/ui/channels')
  })
  it('导航只保留渠道作为 Key 管理入口', () => {
    expect(navSections.flatMap((section) => section.items).some((item) => item.to.includes('credential-pools'))).toBe(false)
  })
})
