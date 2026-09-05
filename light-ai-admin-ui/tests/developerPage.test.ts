import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import DeveloperAccessPage from '@/pages/developer/DeveloperAccessPage.vue'
import ChatTestPanel from '@/pages/developer/ChatTestPanel.vue'
import CodeSamplePanel from '@/pages/developer/CodeSamplePanel.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import type { DeveloperAliasSummary } from '@/api/developerAccess'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function sseResponse(): Response {
  const events = [
    { event: 'START', trace_id: 'tr-1', sequence: 0, model: 'chat-default', provider: 'OpenAI', provider_model: 'gpt-4o' },
    { event: 'DELTA', trace_id: 'tr-1', sequence: 1, model: '', provider: 'OpenAI', provider_model: 'gpt-4o', delta: '你好' },
    {
      event: 'USAGE', trace_id: 'tr-1', sequence: 2, model: '', provider: 'OpenAI', provider_model: 'gpt-4o',
      usage: { prompt_tokens: 5, completion_tokens: 2, total_tokens: 7 },
      cost: { amount: '0.00000140', currency: 'USD', estimated: false },
    },
    { event: 'DONE', trace_id: 'tr-1', sequence: 3, model: '', provider: 'OpenAI', provider_model: 'gpt-4o', finish_reason: 'stop', total_ms: 800 },
  ]
  const text = events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('')
  return new Response(text, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

type Route = [RegExp, (url: URL, method: string) => Response]

function stubFetch(routes: Route[]): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    const parsed = new URL(url, 'http://localhost')
    const method = init?.method ?? 'GET'
    for (const [pattern, handler] of routes) {
      if (pattern.test(parsed.pathname)) return Promise.resolve(handler(parsed, method))
    }
    return Promise.resolve(jsonResponse(404, { error: { code: 'OBJECT_NOT_FOUND', type: 'api', message: 'x', retryable: false } }))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
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

const alias: DeveloperAliasSummary = contextPayload.available_models[0]!

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

const pageRoutes = [{ path: '/ui/developer-access', component: DeveloperAccessPage }]

async function mountAt(component: unknown, path: string, routes = pageRoutes): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes: routes as never })
  void router.push(path)
  await router.isReady()
  const wrapper = mount(component as never, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('DeveloperAccessPage（FE-049）', () => {
  it('渲染连接信息、Alias 选择与模型摘要', async () => {
    stubFetch([[/\/admin\/developer-access\/context$/, () => jsonResponse(200, { data: contextPayload })]])
    const { wrapper } = await mountAt(DeveloperAccessPage, '/ui/developer-access')
    const text = wrapper.text()
    expect(text).toContain('独立部署')
    expect(text).toContain('https://your-deployment.example.com')
    expect(text).toContain('Bearer Token')
    expect(text).toContain('默认对话（chat-default）')
    expect(text).toContain('支持流式')
  })

  it('无可用模型时显示空态', async () => {
    stubFetch([[/\/admin\/developer-access\/context$/, () =>
      jsonResponse(200, { data: { ...contextPayload, available_models: [], selected_alias_id: null } })]])
    const { wrapper } = await mountAt(DeveloperAccessPage, '/ui/developer-access')
    expect(wrapper.text()).toContain('没有可用的已发布模型别名')
  })

  it('切换 Alias 重新拉取 context 并清旧输出', async () => {
    const fetchMock = stubFetch([[/\/admin\/developer-access\/context$/, (url) =>
      jsonResponse(200, {
        data: {
          ...contextPayload,
          selected_alias_id: url.searchParams.get('alias_id') ?? 'alias-1',
          available_models: contextPayload.available_models.map((item) =>
            item.alias_id === (url.searchParams.get('alias_id') ?? 'alias-1')
              ? item
              : { ...item, display_name: '摘要生成' },
          ),
        },
      })]])
    const { wrapper } = await mountAt(DeveloperAccessPage, '/ui/developer-access')
    const select = wrapper.find('select[aria-label="选择 Model Alias"]')
    await select.setValue('alias-2')
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === 'GET').length).toBeGreaterThanOrEqual(2)
    expect(wrapper.text()).toContain('#12')
  })
})

describe('ChatTestPanel（FE-051/052/053）', () => {
  it('只读身份禁用测试输入与提交', () => {
    const wrapper = mount(ChatTestPanel, { props: { alias, canTest: false } })
    expect(wrapper.text()).toContain('当前角色无测试权限')
    expect((wrapper.find('#lai-test-user').element as HTMLTextAreaElement).disabled).toBe(true)
    const submitButton = wrapper.findAll('button').find((button) => button.text().includes('发起'))!
    expect((submitButton.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('空 user_message 禁止提交并提示', async () => {
    const wrapper = mount(ChatTestPanel, { props: { alias, canTest: true } })
    await flushPromises()
    const submitButton = wrapper.findAll('button').find((button) => button.text().includes('发起'))!
    expect((submitButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('user_message 必填')
    await wrapper.find('#lai-test-user').setValue('你好')
    await flushPromises()
    expect(wrapper.text()).not.toContain('user_message 必填')
  })

  it('同步测试渲染 response 与 trace 链接', async () => {
    const fetchMock = stubFetch([[/\/admin\/developer-access\/test\/chat$/, (_url, method) =>
      method === 'POST'
        ? jsonResponse(200, {
            data: {
              response: {
                id: 'tr-sync-1', model: 'chat-default',
                choices: [{ index: 0, message: { role: 'assistant', content: '你好：回复' }, finish_reason: 'stop' }],
                usage: { prompt_tokens: 12, completion_tokens: 20, total_tokens: 32 },
                light_ai: { trace_id: 'tr-sync-1', provider: 'OpenAI', provider_model: 'gpt-4o', cost: { amount: '0.00001280', currency: 'USD', estimated: false }, snapshot_no: 12 },
              },
              trace_id: 'tr-sync-1',
              total_ms: 1230,
            },
          })
        : jsonResponse(400, { error: { code: 'X', type: 'api', message: 'x', retryable: false } })]])
    const wrapper = mount(ChatTestPanel, { props: { alias, canTest: true } })
    await wrapper.find('#lai-test-user').setValue('你好')
    await wrapper.find('input[type="checkbox"]').setValue(false)
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(String(post![1]!.body)).stream).toBe(false)
    const output = wrapper.find('[data-testid="sync-output"]')
    expect(output.text()).toContain('你好：回复')
    expect(wrapper.text()).toContain('tr-sync-1')
    expect(wrapper.text()).toContain('1230 ms')
  })

  it('流式测试按 sequence 追加并显示 Usage 与完成', async () => {
    stubFetch([[/\/admin\/developer-access\/test\/chat\/stream$/, () => sseResponse()]])
    const wrapper = mount(ChatTestPanel, { props: { alias, canTest: true } })
    await wrapper.find('#lai-test-user').setValue('你好')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const output = wrapper.find('[data-testid="stream-output"]')
    expect(output.text()).toContain('你好')
    expect(wrapper.text()).toContain('7')
    expect(wrapper.text()).toContain('stop')
    expect(wrapper.text()).toContain('tr-1')
  })

  it('流内错误保留已收文本且不显示成功', async () => {
    const streamText = 'data: {"event":"START","trace_id":"tr-9","sequence":0,"model":"m","provider":"p","provider_model":"pm"}\n\ndata: {"event":"DELTA","trace_id":"tr-9","sequence":1,"model":"","provider":"p","provider_model":"pm","delta":"部分"}\n\ndata: {"error":{"code":"TOTAL_TIMEOUT","type":"timeout","message":"总超时","retryable":false}}\n\n'
    stubFetch([[/\/admin\/developer-access\/test\/chat\/stream$/, () => new Response(streamText, { status: 200 })]])
    const wrapper = mount(ChatTestPanel, { props: { alias, canTest: true } })
    await wrapper.find('#lai-test-user').setValue('你好')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-testid="stream-output"]').text()).toContain('部分')
    expect(wrapper.text()).toContain('总超时')
    expect(wrapper.text()).toContain('TOTAL_TIMEOUT')
    expect(wrapper.text()).not.toContain('finish_reason：stop')
  })

  it('取消测试后不再追加内容', async () => {
    vi.useFakeTimers()
    let sourceController: ReadableStreamDefaultController<Uint8Array> | null = null
    const body = new ReadableStream<Uint8Array>({
      start(control) {
        sourceController = control
        control.enqueue(new TextEncoder().encode('data: {"event":"START","trace_id":"tr-2","sequence":0,"model":"m","provider":"p","provider_model":"pm"}\n\n'))
      },
    })
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(body, { status: 200 }))))
    const wrapper = mount(ChatTestPanel, { props: { alias, canTest: true } })
    await wrapper.find('#lai-test-user').setValue('你好')
    await flushPromises()
    const pending = wrapper.find('form').trigger('submit')
    await flushPromises()
    const cancelButton = wrapper.findAll('button').find((button) => button.text() === '取消测试')!
    await cancelButton.trigger('click')
    sourceController!.enqueue(new TextEncoder().encode('data: {"event":"DELTA","trace_id":"tr-2","sequence":1,"model":"","provider":"p","provider_model":"pm","delta":"迟到"}\n\n'))
    await pending
    await flushPromises()
    expect(wrapper.find('[data-testid="stream-output"]').text()).not.toContain('迟到')
    vi.useRealTimers()
  })
})

describe('CodeSamplePanel（FE-050）', () => {
  it('加载示例并提示占位符', async () => {
    stubFetch([[/\/admin\/developer-access\/code-sample$/, () =>
      jsonResponse(200, {
        data: {
          language: 'java', filename: 'Example.java',
          content: 'LightAiClient client = LightAiClient.builder()\n    .accessToken("lai_your_token")\n    .build();',
          alias_id: 'alias-1', mode: 'STANDALONE_CLIENT', sample_type: 'SYNC',
        },
      })]])
    const wrapper = mount(CodeSamplePanel, { props: { aliasId: 'alias-1', mode: 'STANDALONE_CLIENT' } })
    await flushPromises()
    const code = wrapper.find('[data-testid="code-sample"]')
    expect(code.text()).toContain('lai_your_token')
    expect(code.text()).toContain('LightAiClient')
    expect(wrapper.text()).toContain('替换以下占位符')
  })

  it('复制按钮写入剪贴板并保留换行', async () => {
    const content = 'line1\nline2\nline3'
    const writeText = vi.fn(() => Promise.resolve())
    Object.assign(navigator, { clipboard: { writeText } })
    stubFetch([[/\/admin\/developer-access\/code-sample$/, () =>
      jsonResponse(200, { data: { language: 'bash', filename: null, content, alias_id: 'alias-1', mode: 'STANDALONE_CLIENT', sample_type: 'HTTP' } })]])
    const wrapper = mount(CodeSamplePanel, { props: { aliasId: 'alias-1', mode: 'STANDALONE_CLIENT' } })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '复制示例')!.trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(content)
    expect(wrapper.text()).toContain('已复制')
  })
})
