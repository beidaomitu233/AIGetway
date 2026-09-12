import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import { routes } from '@/app/router'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import {
  dataEnvelope,
  errorEnvelope,
  installJsonFetchStub,
  pageEnvelope,
  type FetchStub,
} from './helpers/fetchStub'

const applicationRows = [
  {
    id: 'app-1',
    code: 'app-demo',
    name: '演示应用',
    department: '平台部',
    owner_id: 'owner-1',
    owner_name: '张三',
    environment: 'PRODUCTION',
    status: 'ACTIVE',
    model_count: 3,
    active_key_count: 2,
    token_limit: 1000000,
    tokens_used: 200000,
    tokens_reserved: 0,
    amount_limit: '100.00000000',
    amount_used: '12.50000000',
    amount_reserved: '0.00000000',
    currency: 'USD',
    rpm: 60,
    tpm: 100000,
    last_called_at: '2026-09-11T08:00:00Z',
    updated_at: '2026-09-11T08:00:00Z',
    version: 4,
  },
  {
    id: 'app-2',
    code: 'app-second',
    name: '第二个应用',
    department: null,
    owner_id: 'owner-2',
    owner_name: '李四',
    environment: 'TEST',
    status: 'ACTIVE',
    model_count: 1,
    active_key_count: 0,
    token_limit: null,
    tokens_used: 0,
    tokens_reserved: 0,
    amount_limit: null,
    amount_used: '0.00000000',
    amount_reserved: '0.00000000',
    currency: 'CNY',
    rpm: null,
    tpm: null,
    last_called_at: null,
    updated_at: '2026-09-10T08:00:00Z',
    version: 1,
  },
]

const adjustmentRows = [
  {
    id: 'adj-1',
    application_id: 'app-1',
    dimension: 'TOKEN_LIMIT',
    before_value: '1000000',
    delta_value: '500000',
    after_value: '1500000',
    reason: '扩大试点额度',
    effective_at: '2026-09-10T02:00:00Z',
    operator_id: 'admin-1',
    created_at: '2026-09-10T01:59:00Z',
  },
  {
    id: 'adj-2',
    application_id: 'app-1',
    dimension: 'AMOUNT_USAGE_RESET',
    before_value: '12.50000000',
    delta_value: '-12.50000000',
    after_value: '0',
    reason: '周期重置',
    effective_at: '2026-09-11T00:00:00Z',
    operator_id: 'admin-1',
    created_at: '2026-09-10T23:00:00Z',
  },
]

async function mountPage(path: string, role: keyof typeof bootstrapFixtures = 'SYSTEM_ADMIN') {
  setActivePinia(createPinia())
  const store = useBootstrapStore()
  store.$patch({
    status: 'ready',
    permissions: [...bootstrapFixtures[role].permissions],
    roles: [...bootstrapFixtures[role].roles],
    adapters: [...(bootstrapFixtures.SYSTEM_ADMIN.adapters ?? [])],
  })
  const router = createRouter({ history: createMemoryHistory(), routes })
  void router.push(path)
  await router.isReady()
  const wrapper = mount(
    { template: '<RouterView />' },
    { global: { plugins: [router], stubs: { teleport: true } } },
  )
  await flushPromises()
  return { wrapper, router }
}

function applicationsHandler(options: { adjustmentsStatus?: number } = {}) {
  return ({ url, method }: { url: URL; method: string }) => {
    if (method === 'GET' && url.pathname === '/admin/applications') {
      return pageEnvelope(applicationRows)
    }
    if (method === 'GET' && url.pathname.endsWith('/quota/adjustments')) {
      if (options.adjustmentsStatus !== undefined) {
        return errorEnvelope(options.adjustmentsStatus, 'CONFIG_DATA_UNAVAILABLE', '额度流水暂不可用')
      }
      const applicationId = url.pathname.split('/')[3]
      return dataEnvelope(adjustmentRows.filter((row) => row.application_id === applicationId))
    }
    if (url.pathname.endsWith('/admin/bootstrap')) {
      return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
    }
    return undefined
  }
}

describe('UsageAdjustmentsPage（FE-222）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  it('默认选择第一个应用并渲染调整与重置流水', async () => {
    stub = installJsonFetchStub(applicationsHandler())
    const { wrapper, router } = await mountPage('/ui/usage/adjustments')
    const text = wrapper.text()
    expect(text).toContain('演示应用（app-demo）')
    expect(text).toContain('Token 上限')
    expect(text).toContain('金额用量重置')
    expect(text).toContain('1500000')
    expect(text).toContain('-12.50000000')
    expect(text).toContain('扩大试点额度')
    expect(router.currentRoute.value.query.application_id).toBe('app-1')
    const adjustmentCall = stub.calls.find((call) => call.url.includes('/admin/applications/app-1/quota/adjustments'))
    expect(adjustmentCall).toBeDefined()
  })

  it('深链 application_id 直接加载对应应用流水', async () => {
    stub = installJsonFetchStub(applicationsHandler())
    await mountPage('/ui/usage/adjustments?application_id=app-2')
    const adjustmentCall = stub.calls.find((call) => call.url.includes('/admin/applications/app-2/quota/adjustments'))
    expect(adjustmentCall).toBeDefined()
  })

  it('切换应用加载对应流水并更新 URL', async () => {
    stub = installJsonFetchStub(applicationsHandler())
    const { wrapper, router } = await mountPage('/ui/usage/adjustments')
    const select = wrapper.find('select[aria-label="选择应用"]')
    await select.setValue('app-2')
    await flushPromises()
    const adjustmentCall = stub.calls
      .filter((call) => call.url.includes('/admin/applications/app-2/quota/adjustments'))
      .at(-1)
    expect(adjustmentCall).toBeDefined()
    expect(router.currentRoute.value.query.application_id).toBe('app-2')
    // app-2 无流水：显示空态而非错误
    expect(wrapper.text()).toContain('暂无额度调整或重置记录')
  })

  it('流水加载失败展示错误并可重试', async () => {
    let failing = true
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/quota/adjustments')) {
        if (failing) {
          return errorEnvelope(503, 'CONFIG_DATA_UNAVAILABLE', '额度流水暂不可用')
        }
        return dataEnvelope(adjustmentRows)
      }
      return applicationsHandler()({ url, method })
    })
    const { wrapper } = await mountPage('/ui/usage/adjustments')
    expect(wrapper.text()).toContain('额度流水暂不可用')
    failing = false
    const retryButton = wrapper.findAll('button').find((button) => button.text().includes('重试'))
    await retryButton!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Token 上限')
  })

  it('应用列表失败展示错误态', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname === '/admin/applications') {
        return errorEnvelope(503, 'CONFIG_DATA_UNAVAILABLE', '应用列表暂不可用')
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/usage/adjustments')
    expect(wrapper.text()).toContain('应用列表暂不可用')
  })

  it('无可见应用时显示空态', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname === '/admin/applications') {
        return pageEnvelope([])
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/usage/adjustments')
    expect(wrapper.text()).toContain('当前身份没有可见的应用')
  })
})
