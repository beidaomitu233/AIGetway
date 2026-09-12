import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ApplicationIntegrationPage from '@/pages/applications/ApplicationIntegrationPage.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const contextPayload = {
  runtime_mode: 'STANDALONE_SERVER',
  api_base_url: 'https://your-deployment.example.com',
  authentication_type: 'BEARER_TOKEN',
  sdk_version: '1.0.0',
  server_version: '1.0.0',
  current_snapshot_no: 12,
  selected_alias_id: 'alias-1',
  available_models: [
    {
      alias_id: 'alias-1', alias: 'chat-default', display_name: '默认对话',
      support_stream: true, support_system_message: true, context_window: 128000, max_output_tokens: 16384,
    },
    {
      alias_id: 'alias-2', alias: 'summary', display_name: '摘要生成',
      support_stream: false, support_system_message: false, context_window: 64000, max_output_tokens: 8192,
    },
  ],
}

const codeSamplePayload = {
  language: 'java',
  filename: 'ChatDemo.java',
  content: '// 示例代码，密钥位置为占位符 lai_your_token',
  alias_id: 'alias-1',
  mode: 'STANDALONE_CLIENT',
  sample_type: 'SYNC',
}

function applicationPayload(activeKeyCount: number) {
  return {
    id: 'app-1',
    code: 'app-demo',
    name: '演示应用',
    department: '平台部',
    owner_id: 'owner-1',
    owner_name: '张三',
    environment: 'PRODUCTION',
    description: null,
    status: 'ACTIVE',
    active_key_count: activeKeyCount,
    quota: {
      id: 'quota-1',
      token_limit: '1000000',
      tokens_used: '200000',
      tokens_reserved: '0',
      amount_limit: '100.00000000',
      amount_used: '12.50000000',
      amount_reserved: '0.00000000',
      currency: 'USD',
      rpm: 60,
      tpm: 100000,
      period_type: 'MONTH',
      period_start: '2026-09-01T00:00:00Z',
      period_end: null,
      version: '2',
    },
    models: [
      {
        id: 'perm-1',
        virtual_model_id: 'vm-1',
        virtual_model_code: 'chat-default',
        enabled: true,
        max_output_tokens: null,
        allow_stream: null,
        version: 1,
      },
    ],
    last_called_at: '2026-09-11T08:00:00Z',
    created_at: '2026-09-01T08:00:00Z',
    updated_at: '2026-09-11T08:00:00Z',
    version: 4,
  }
}

type Route = [RegExp, (url: URL, method: string) => Response]

function stubFetch(routes: Route[]): void {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    const parsed = new URL(url, 'http://localhost')
    const method = init?.method ?? 'GET'
    for (const [pattern, handler] of routes) {
      if (pattern.test(parsed.pathname)) return Promise.resolve(handler(parsed, method))
    }
    return Promise.resolve(jsonResponse(404, { error: { code: 'OBJECT_NOT_FOUND', type: 'api', message: 'x', retryable: false } }))
  })
  vi.stubGlobal('fetch', fetchMock)
}

function pageRoutes(): Route[] {
  return [
    [/\/admin\/bootstrap$/, () => jsonResponse(200, { data: bootstrapFixtures.DEVELOPER })],
    [/\/admin\/applications\/app-1$/, () => jsonResponse(200, { data: applicationPayload(2) })],
    [/\/admin\/developer-access\/context$/, () => jsonResponse(200, { data: contextPayload })],
    [/\/admin\/developer-access\/code-sample$/, () => jsonResponse(200, { data: codeSamplePayload })],
  ]
}

describe('ApplicationIntegrationPage（FE-224）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const store = useBootstrapStore()
    store.$patch({
      status: 'ready',
      permissions: [...bootstrapFixtures.DEVELOPER.permissions],
      roles: [...bootstrapFixtures.DEVELOPER.roles],
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  async function mountAt(path: string): Promise<ReturnType<typeof mount>> {
    stubFetch(pageRoutes())
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/ui/applications/:id/integration', component: ApplicationIntegrationPage },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ] as never })
    void router.push(path)
    await router.isReady()
    const wrapper = mount(ApplicationIntegrationPage, { global: { plugins: [router] } })
    await flushPromises()
    return wrapper
  }

  it('展示连接信息、已授权模型选择与调用示例', async () => {
    const wrapper = await mountAt('/ui/applications/app-1/integration')
    const text = wrapper.text()
    expect(text).toContain('统一 Base URL')
    expect(text).toContain('your-deployment.example.com')
    expect(text).toContain('默认对话（chat-default）')
    expect(text).toContain('1,000,000')
    expect(text).toContain('调用示例')
    expect(text).toContain('在线测试')
    expect(wrapper.find('[data-testid="no-active-key"]').exists()).toBe(false)
  })

  it('无活动密钥时提示先签发密钥（FE-224）', async () => {
    stubFetch([
      [/\/admin\/bootstrap$/, () => jsonResponse(200, { data: bootstrapFixtures.DEVELOPER })],
      [/\/admin\/applications\/app-1\?no-keys=1$/, () => jsonResponse(200, { data: applicationPayload(0) })],
      [/\/admin\/applications\/app-1$/, () => jsonResponse(200, { data: applicationPayload(0) })],
      [/\/admin\/developer-access\/context$/, () => jsonResponse(200, { data: contextPayload })],
      [/\/admin\/developer-access\/code-sample$/, () => jsonResponse(200, { data: codeSamplePayload })],
    ])
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/ui/applications/:id/integration', component: ApplicationIntegrationPage },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ] as never })
    void router.push('/ui/applications/app-1/integration')
    await router.isReady()
    const wrapper = mount(ApplicationIntegrationPage, { global: { plugins: [router] } })
    await flushPromises()
    const warning = wrapper.find('[data-testid="no-active-key"]')
    expect(warning.exists()).toBe(true)
    expect(warning.text()).toContain('没有活动密钥')
    expect(warning.text()).toContain('接入密钥')
  })

  it('应用未授权模型时显示空态并隐藏测试面板（FE-224）', async () => {
    stubFetch([
      [/\/admin\/bootstrap$/, () => jsonResponse(200, { data: bootstrapFixtures.DEVELOPER })],
      [/\/admin\/applications\/app-1$/, () => jsonResponse(200, { data: { ...applicationPayload(2), models: [] } })],
      [/\/admin\/developer-access\/context$/, () => jsonResponse(200, { data: contextPayload })],
    ])
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/ui/applications/:id/integration', component: ApplicationIntegrationPage },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ] as never })
    void router.push('/ui/applications/app-1/integration')
    await router.isReady()
    const wrapper = mount(ApplicationIntegrationPage, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('该应用尚未授权任何可调用的虚拟模型')
    expect(wrapper.text()).not.toContain('在线测试')
  })
})
