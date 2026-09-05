import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useBootstrapStore } from '@/stores/bootstrap'
import { registerCsrfToken } from '@/api/http'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  registerCsrfToken(undefined)
})

describe('bootstrap store', () => {
  it('加载成功后缓存身份、权限与运行信息', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.SYSTEM_ADMIN }))),
    )
    const store = useBootstrapStore()
    await store.load()
    expect(store.status).toBe('ready')
    expect(store.displayName).toBe('系统管理员')
    expect(store.roles).toEqual(['SYSTEM_ADMIN'])
    expect(store.runtimeMode).toBe('STANDALONE_SERVER')
    expect(store.currentSnapshotNo).toBe(12)
    expect(store.draftChangeCount).toBe(3)
    expect(store.can('provider.manage')).toBe(true)
    expect(store.can('publish.manage')).toBe(true)
    expect(store.can('nonexistent.permission')).toBe(false)
  })

  it('重复加载只请求一次', async () => {
    setActivePinia(createPinia())
    const fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.SYSTEM_ADMIN })),
    )
    vi.stubGlobal('fetch', fetchMock)
    const store = useBootstrapStore()
    await Promise.all([store.load(), store.load(), store.load()])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('身份被拒绝时进入 forbidden 并清空缓存', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          jsonResponse(403, {
            error: { code: 'ACCESS_DENIED', type: 'auth', message: '未认证管理身份', retryable: false },
          }),
        ),
      ),
    )
    const store = useBootstrapStore()
    await store.load()
    expect(store.status).toBe('forbidden')
    expect(store.userId).toBe('')
    expect(store.permissions).toEqual([])
    expect(store.can('provider.view')).toBe(false)
  })

  it('网络或服务异常进入 error 状态并可重试', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('network down'))),
    )
    const store = useBootstrapStore()
    await store.load()
    expect(store.status).toBe('error')
    expect(store.error).toBeInstanceOf(Error)

    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.VIEWER }))),
    )
    await store.load()
    expect(store.status).toBe('ready')
    expect(store.can('provider.manage')).toBe(false)
    expect(store.can('provider.view')).toBe(true)
  })

  it('invalidate 清空全部缓存并允许重新加载', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.OPERATOR }))),
    )
    const store = useBootstrapStore()
    await store.load()
    registerCsrfToken('token-1')
    store.invalidate()
    expect(store.status).toBe('idle')
    expect(store.permissions).toEqual([])
    expect(store.displayName).toBe('')

    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(200, { data: bootstrapFixtures.DEVELOPER }))),
    )
    await store.load()
    expect(store.status).toBe('ready')
    expect(store.applicationScope).toEqual(['app-demo'])
    expect(store.allowedAliasIds).toEqual(['alias-chat'])
  })
})
