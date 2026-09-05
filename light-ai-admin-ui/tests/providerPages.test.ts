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
  type JsonMockResponse,
} from './helpers/fetchStub'

const adminProvider = {
  id: 'prov-1',
  name: 'OpenAI 生产',
  type: 'OPENAI',
  base_url: 'https://api.openai.com/v1/',
  proxy_url: null,
  connection_status: 'AVAILABLE',
  last_check_at: '2026-09-05T02:00:00Z',
  last_check_latency_ms: 430,
  last_error_code: null,
  provider_model_count: 2,
  credential_pool_count: 1,
  enabled: true,
  draft_changed: true,
  version: 3,
  updated_at: '2026-09-05T01:00:00Z',
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
    { global: { plugins: [router] } },
  )
  await flushPromises()
  return { wrapper, router }
}

describe('ProviderListPage（FE-007）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
  })

  it('列表渲染状态与聚合字段，筛选参数进入 URL', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/providers')) {
        return pageEnvelope([adminProvider])
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/providers', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('OpenAI 生产')
    expect(text).toContain('可用')
    expect(text).toContain('待发布')

    const keyword = wrapper.find('input[type="text"]')
    await keyword.setValue('openai')
    const statusTrigger = wrapper
      .findAll('.lai-multiselect-trigger')
      .find((button) => button.text().includes('连接状态'))
    await statusTrigger!.trigger('click')
    const availableOption = wrapper
      .findAll('.lai-multiselect-option')
      .find((option) => option.text() === '可用')
    await availableOption!.trigger('click')
    await flushPromises()
    await flushPromises()
    const listCall = stub.calls.filter((call) => call.url.includes('/admin/providers?')).at(-1)
    expect(listCall).toBeDefined()
    expect(listCall!.url).toContain('keyword=openai')
    expect(listCall!.url).toContain('connection_status=AVAILABLE')
  })

  it('管理员可见编辑/停用/删除，只读角色仅有查看', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/providers')) {
        return pageEnvelope([adminProvider])
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const adminWrapper = await mountPage('/ui/providers', 'SYSTEM_ADMIN')
    expect(adminWrapper.wrapper.text()).toContain('编辑')
    expect(adminWrapper.wrapper.text()).toContain('删除')

    const viewerWrapper = await mountPage('/ui/providers', 'VIEWER')
    expect(viewerWrapper.wrapper.text()).toContain('查看')
    expect(viewerWrapper.wrapper.text()).not.toContain('删除')
    expect(viewerWrapper.wrapper.text()).not.toContain('新建 Provider')
  })

  it('列表错误状态不显示为空列表并可重试', async () => {
    let fail = true
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/providers')) {
        if (fail) {
          return errorEnvelope(503, 'CONFIG_DATA_UNAVAILABLE', '配置数据暂不可读', { retryable: true })
        }
        return pageEnvelope([adminProvider])
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/providers', 'SYSTEM_ADMIN')
    expect(wrapper.text()).toContain('配置数据暂不可读')
    fail = false
    const retry = wrapper.findAll('button').find((button) => button.text() === '重试')
    await retry!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('OpenAI 生产')
  })
})

describe('ProviderFormPage（FE-008）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
  })

  function bootStub(putResponses: JsonMockResponse[] = []) {
    let putCount = 0
    stub = installJsonFetchStub(({ url, method, body }) => {
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/providers/prov-1')) {
        return dataEnvelope({
          ...adminProvider,
          connect_timeout_ms: 3000,
          read_timeout_ms: 120000,
          default_headers: {},
          created_by: 'a',
          created_at: '2026-09-01T00:00:00Z',
          updated_by: 'a',
          updated_at: '2026-09-05T01:00:00Z',
          recent_check_records: [],
        })
      }
      if (method === 'PUT' && url.pathname.endsWith('/admin/providers/prov-1')) {
        return putResponses[putCount++] ?? dataEnvelope({ id: 'prov-1', version: 4, entity: null, draft_changed: true, draft_revision: 9, request_id: 'r1' })
      }
      if (method === 'POST' && url.pathname.endsWith('/admin/providers')) {
        expect(body.name).toBe('合法名称')
        return dataEnvelope({ id: 'prov-2', version: 1, entity: null, draft_changed: true, draft_revision: 9, request_id: 'r1' })
      }
      return undefined
    })
  }

  it('读取超时小于连接超时、名称过短时不提交', async () => {
    bootStub()
    const { wrapper } = await mountPage('/ui/providers/new', 'SYSTEM_ADMIN')
    await wrapper.find('#provider-name').setValue('短')
    await wrapper.find('#provider-base-url').setValue('https://api.example.com/v1/')
    await wrapper.find('#provider-connect-timeout').setValue('5000')
    await wrapper.find('#provider-read-timeout').setValue('3000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(stub.calls.filter((call) => call.method === 'POST')).toHaveLength(0)
    expect(wrapper.text()).toContain('名称长度为 2—64 字符')
    expect(wrapper.text()).toContain('读取超时不能小于连接超时')
  })

  it('合法新建提交成功后跳转详情', async () => {
    bootStub()
    const { wrapper, router } = await mountPage('/ui/providers/new', 'SYSTEM_ADMIN')
    await wrapper.find('#provider-name').setValue('合法名称')
    await wrapper.find('#provider-type').setValue('OPENAI')
    await wrapper.find('#provider-base-url').setValue('https://api.example.com/v1/')
    await wrapper.find('form').trigger('submit')
    await vi.waitFor(() => {
      expect(router.currentRoute.value.name).toBe('provider-detail')
    })
    expect(router.currentRoute.value.params.id).toBe('prov-2')
  })

  it('版本冲突保留编辑值并展示服务端版本', async () => {
    bootStub([
      errorEnvelope(409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改', { current_version: 7 }),
    ])
    const { wrapper } = await mountPage('/ui/providers/prov-1/edit', 'SYSTEM_ADMIN')
    await flushPromises()
    await wrapper.find('#provider-name').setValue('本地新名称')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('配置已被其他管理员修改，当前编辑内容已保留')
    expect(wrapper.text()).toContain('服务端最新版本：7')
    expect((wrapper.find('#provider-name').element as HTMLInputElement).value).toBe('本地新名称')
    // 冲突后保存按钮禁用，避免覆盖最新版本
    const saveButton = wrapper.findAll('button').find((button) => button.text() === '保存')
    expect(saveButton!.attributes('disabled')).toBeDefined()
  })
})
