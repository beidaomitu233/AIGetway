import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { routes } from '@/app/router'
import { setupRouterGuards } from '@/app/routerGuards'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function buildRouter(): Router {
  const router = createRouter({ history: createMemoryHistory(), routes })
  setupRouterGuards(router)
  return router
}

describe('router guards', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('首次导航自动加载 bootstrap 并放行有权页面', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.SYSTEM_ADMIN })),
    )
    vi.stubGlobal('fetch', fetchMock)
    const router = buildRouter()
    await router.push('/ui/providers')
    await router.isReady()
    const store = useBootstrapStore()
    expect(store.status).toBe('ready')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('provider-list')
  })

  it('无权限访问跳转 403 并携带来源路径', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.VIEWER }))),
    )
    const router = buildRouter()
    await router.push('/ui/providers/new')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('forbidden')
    expect(router.currentRoute.value.query.from).toBe('/ui/providers/new')
  })

  it('身份失效（ACCESS_DENIED）进入 forbidden 页', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          jsonResponse(403, {
            error: { code: 'ACCESS_DENIED', type: 'auth', message: '未认证', retryable: false },
          }),
        ),
      ),
    )
    const router = buildRouter()
    await router.push('/ui/overview')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('未知地址进入 404', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.SYSTEM_ADMIN }))),
    )
    const router = buildRouter()
    await router.push('/ui/no-such-page')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('not-found-fallback')
  })
})
