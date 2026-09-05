import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ModelFormPage from '@/pages/models/ModelFormPage.vue'
import ModelImportPage from '@/pages/models/ModelImportPage.vue'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function opOk(): Response {
  return jsonResponse(200, {
    data: { id: 'model-new', version: 1, entity: null, draft_changed: true, draft_revision: null, request_id: 'r' },
  })
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

const providerRoutes: Route[] = [
  [/\/admin\/providers$/, () =>
    jsonResponse(200, { data: { items: [{ id: 'prov-1', name: 'OpenAI', type: 'OPENAI', enabled: true }], total: 1, page: 1, page_size: 100, sort: 'name', query_started_at: 'q', data_updated_at: 'u' } })],
]

async function mountForm(route = '/ui/provider-models/new'): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/ui/provider-models/new', component: ModelFormPage },
      { path: '/ui/provider-models/:id/edit', component: ModelFormPage, props: true },
    ],
  })
  void router.push(route)
  await router.isReady()
  const wrapper = mount(ModelFormPage, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('ModelFormPage（FE-015）', () => {
  it('上下文不大于最大输出时禁止保存并提示', async () => {
    stubFetch(providerRoutes)
    const { wrapper } = await mountForm()
    await wrapper.find('select').setValue('prov-1')
    await wrapper.find('input[maxlength="64"]').setValue('GPT-4o')
    const monoInputs = wrapper.findAll('input[spellcheck="false"]')
    await monoInputs[0]!.setValue('gpt-4o')
    await wrapper.find('input[placeholder="启用模型必填"]').setValue('100')
    const windows = wrapper.findAll('input[placeholder="启用模型必填"]')
    await windows[0]!.setValue('100')
    await windows[1]!.setValue('100')
    await flushPromises()
    const saveButton = wrapper.findAll('button').find((button) => button.text() === '保存')!
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('需为正整数且小于上下文窗口')
  })

  it('能力不完整时启用被阻止', async () => {
    stubFetch(providerRoutes)
    const { wrapper } = await mountForm()
    await wrapper.find('select').setValue('prov-1')
    await wrapper.find('input[maxlength="64"]').setValue('GPT-4o')
    expect(wrapper.text()).toContain('启用模型必须补齐 Tokenizer、上下文窗口和最大输出')
  })

  it('合法表单提交创建命令并保留价格字符串', async () => {
    const fetchMock = stubFetch([
      ...providerRoutes,
      [/\/admin\/provider-models$/, (_url, method) => (method === 'POST' ? opOk() : jsonResponse(200, { data: { items: [], total: 0, page: 1, page_size: 20, sort: '', query_started_at: '', data_updated_at: '' } }))],
    ])
    const { wrapper } = await mountForm()
    await wrapper.find('select').setValue('prov-1')
    await wrapper.find('input[maxlength="64"]').setValue('GPT-4o')
    const mono = wrapper.findAll('input[spellcheck="false"]')
    await mono[0]!.setValue('gpt-4o')
    await mono[1]!.setValue('o200k')
    const windows = wrapper.findAll('input[placeholder="启用模型必填"]')
    await windows[0]!.setValue('128000')
    await windows[1]!.setValue('16384')
    const prices = wrapper.findAll('input[inputmode="decimal"]')
    await prices[0]!.setValue('2.50000000')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post).toBeTruthy()
    const body = JSON.parse(String(post![1]!.body)) as { input_price: string; display_name: string }
    expect(body.input_price).toBe('2.50000000')
    expect(body.display_name).toBe('GPT-4o')
  })

  it('409 版本冲突保留用户输入', async () => {
    stubFetch([
      ...providerRoutes,
      [/\/admin\/provider-models\/model-1$/, (_url, method) =>
        method === 'PUT'
          ? jsonResponse(409, { error: { code: 'CONFIG_VERSION_CONFLICT', type: 'conflict', message: '已被修改', retryable: false, current_version: 9 } })
          : jsonResponse(200, {
              data: {
                id: 'model-1', version: 3, draft_changed: false, provider_id: 'prov-1', provider_name: 'OpenAI',
                display_name: '旧名称', model_id: 'gpt-4o', model_type: 'CHAT_TEXT', tokenizer_family: 'o200k',
                context_window: 128000, max_output_tokens: 16384, support_stream: true, support_system_message: true,
                support_temperature: true, temperature_min: '0', temperature_max: '2', support_top_p: true,
                top_p_min: '0', top_p_max: '1', support_stop: true, max_stop_sequences: 4, max_stop_length: 128,
                default_temperature: null, default_top_p: null, default_max_tokens: null, default_stop: [],
                input_price: '1.00000000', output_price: '2.00000000', price_unit: 1000000, currency: 'USD',
                connection_status: 'AVAILABLE', last_check_at: null, last_error_code: null, route_candidate_count: 0,
                enabled: true, related_aliases: [], recent_checks: [], created_at: '', updated_at: '',
              },
            })],
    ])
    const { wrapper } = await mountForm('/ui/provider-models/model-1/edit')
    await flushPromises()
    const nameInput = wrapper.find('input[maxlength="64"]')
    expect((nameInput.element as HTMLInputElement).value).toBe('旧名称')
    await nameInput.setValue('新名称')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect((nameInput.element as HTMLInputElement).value).toBe('新名称')
    expect(wrapper.text()).toContain('配置已被其他管理员修改')
    expect(wrapper.text()).toContain('9')
  })
})

describe('ModelImportPage（FE-016）', () => {
  const importRoutes: Route[] = [
    ...providerRoutes,
    [/\/admin\/credential-pools$/, () =>
      jsonResponse(200, { data: { items: [{ id: 'pool-1', name: 'openai-main' }], total: 1, page: 1, page_size: 100, sort: 'name', query_started_at: '', data_updated_at: '' } })],
    [/\/admin\/credential-pools\/pool-1\/credentials$/, () =>
      jsonResponse(200, { data: { items: [{ id: 'cred-1', name: 'key-1', enabled: true }], total: 1, page: 1, page_size: 100, sort: 'name', query_started_at: '', data_updated_at: '' } })],
    [/\/admin\/providers\/prov-1\/available-models$/, () =>
      jsonResponse(200, {
        data: [
          { model_id: 'gpt-4.1', display_name: 'GPT-4.1', existing: true, source: 'ADAPTER_PRESET', tokenizer_family: 'o200k', context_window: 1000000, max_output_tokens: 32768, support_stream: true, support_system_message: true, support_temperature: true, support_top_p: true, support_stop: true },
          { model_id: 'gpt-5-mini', display_name: null, existing: false, source: 'PROVIDER_API', tokenizer_family: null, context_window: null, max_output_tokens: null, support_stream: null, support_system_message: null, support_temperature: null, support_top_p: null, support_stop: null },
        ],
      })],
  ]

  async function mountWizard(extraRoutes: Route[] = []): Promise<ReturnType<typeof mount>> {
    stubFetch([...importRoutes, ...extraRoutes])
    const wrapper = mount(ModelImportPage)
    await flushPromises()
    await wrapper.find('select').setValue('prov-1')
    await flushPromises()
    const selects = wrapper.findAll('select')
    await selects[1]!.setValue('PROVIDER_API')
    await selects[2]!.setValue('cred-1')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '获取模型列表')!.trigger('click')
    await flushPromises()
    return wrapper
  }

  it('未知能力显示待补充且已存在模型有标记', async () => {
    const wrapper = await mountWizard()
    const text = wrapper.text()
    expect(text).toContain('gpt-5-mini')
    expect(text).toContain('待补充')
    expect(text).toContain('已存在')
  })

  it('提交导入返回逐项结果', async () => {
    const wrapper = await mountWizard([
      [/\/admin\/provider-models\/import$/, () =>
        jsonResponse(200, {
          data: {
            created: [{ model_id: 'gpt-4.1', id: 'm-9', version: 1 }],
            skipped: [{ model_id: 'gpt-5-mini', reason: '已存在' }],
            failed: [],
          },
        })],
    ])
    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    for (const box of checkboxes.slice(1)) {
      if (!(box.element as HTMLInputElement).checked) await box.setValue(true)
    }
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('成功 1 项')
    expect(text).toContain('跳过 1 项')
    expect(text).toContain('失败 0 项')
  })
})
