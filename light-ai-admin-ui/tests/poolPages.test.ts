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

const pool = {
  id: 'pool-1',
  provider_id: 'prov-1',
  provider_name: 'OpenAI 生产',
  name: 'OpenAI 主池',
  selection_strategy: 'WEIGHTED_RANDOM',
  credential_total: 3,
  credential_available: 2,
  current_concurrency: 2,
  rpm_used: 18,
  tpm_used: 40210,
  status: 'PARTIAL_AVAILABLE',
  enabled: true,
  draft_changed: true,
  version: 2,
  updated_at: '2026-09-05T01:00:00Z',
}

const poolDetail = {
  ...pool,
  provider_name: undefined,
  route_candidate_count: 2,
  model_alias_count: 1,
  created_by: 'admin',
  created_at: '2026-09-01T00:00:00Z',
  updated_by: 'admin',
}

const credential = {
  id: 'cred-1',
  pool_id: 'pool-1',
  name: '主密钥',
  masked_value: 'sk-****a1b2',
  secret_ref_display: null,
  secret_source: 'INLINE_ENCRYPTED',
  weight: 1,
  rpm_limit: 500,
  tpm_limit: null,
  concurrent_limit: null,
  current_concurrency: 2,
  health_status: 'HEALTHY',
  rate_limit_reset_at: null,
  last_success_at: '2026-09-05T03:00:00Z',
  last_check_at: null,
  enabled: true,
  draft_changed: false,
  version: 1,
}

async function mountPage(path: string, role: keyof typeof bootstrapFixtures) {
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

describe('PoolListPage（FE-011）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
  })

  function handler() {
    return ({ url, method }: { url: URL; method: string }) => {
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/credential-pools')) {
        return pageEnvelope([pool])
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/providers')) {
        return pageEnvelope([{ id: 'prov-1', name: 'OpenAI 生产' }])
      }
      return undefined
    }
  }

  it('列表展示策略中文名称、实时容量与待发布标记', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/credential-pools', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('OpenAI 主池')
    expect(text).toContain('加权随机')
    expect(text).toContain('部分可用')
    expect(text).toContain('待发布')
    expect(text).toContain('18')
  })

  it('无权限角色不显示新建与删除', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/credential-pools', 'VIEWER')
    expect(wrapper.text()).not.toContain('新建凭证池')
    expect(wrapper.findAll('button').some((button) => button.text() === '删除')).toBe(false)
  })

  it('列表错误不误报为空', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/credential-pools')) {
        return errorEnvelope(503, 'CAPACITY_STATE_UNAVAILABLE', '容量状态存储不可用', { retryable: true })
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/credential-pools', 'SYSTEM_ADMIN')
    expect(wrapper.text()).toContain('容量状态存储不可用')
    expect(wrapper.text()).not.toContain('暂无数据')
  })
})

describe('PoolFormPage（FE-011）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  it('新建缺少 Provider 或名称过短不提交', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/providers')) {
        return pageEnvelope([{ id: 'prov-1', name: 'OpenAI 生产' }])
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/credential-pools/new', 'SYSTEM_ADMIN')
    await wrapper.find('#pool-name').setValue('A')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('请选择 Provider')
    expect(wrapper.text()).toContain('名称长度为 2—64 字符')
  })

  it('编辑模式 Provider 只读且名称回填', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/providers')) {
        return pageEnvelope([{ id: 'prov-1', name: 'OpenAI 生产' }])
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/credential-pools/pool-1')) {
        return dataEnvelope(poolDetail)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/credential-pools/pool-1/edit', 'SYSTEM_ADMIN')
    await flushPromises()
    const providerSelect = wrapper.find('#pool-provider')
    expect((providerSelect.element as HTMLSelectElement).disabled).toBe(true)
    expect((wrapper.find('#pool-name').element as HTMLInputElement).value).toBe('OpenAI 主池')
    expect((wrapper.find('#pool-strategy').element as HTMLSelectElement).value).toBe('WEIGHTED_RANDOM')
  })
})

describe('PoolDetailPage（FE-012）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
  })

  function handler() {
    return ({ url, method }: { url: URL; method: string }) => {
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/credential-pools/pool-1')) {
        return dataEnvelope(poolDetail)
      }
      if (method === 'GET' && url.pathname.includes('/admin/credential-pools/pool-1/credentials')) {
        return pageEnvelope([credential])
      }
      return undefined
    }
  }

  it('管理员可见容量摘要与 Credential 列表', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/credential-pools/pool-1', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('容量摘要')
    expect(text).toContain('40210')
    expect(text).toContain('Credential')
    expect(text).toContain('主密钥')
    expect(text).toContain('sk-****a1b2')
    expect(text).toContain('加密存储')
    expect(text).toContain('不限制')
  })

  it('只读角色不加载 Credential 列表', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/credential-pools/pool-1', 'VIEWER')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('容量摘要')
    expect(text).not.toContain('主密钥')
    expect(stub.calls.filter((call) => call.url.includes('/credentials'))).toHaveLength(0)
  })
})
