import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import AliasFormPage from '@/pages/aliases/AliasFormPage.vue'
import AliasDetailPage from '@/pages/aliases/AliasDetailPage.vue'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
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

const aliasDetail = {
  data: {
    id: 'alias-1', version: 5, draft_changed: true, alias: 'chat-default', display_name: '默认对话',
    description: '业务默认入口', route_strategy: 'PRIORITY_WEIGHTED', enabled: true, candidate_count: 1,
    available_candidate_count: 1, stream_candidate_count: 1, request_count_24h: 10, success_rate_24h: 99.0,
    p95_total_ms_24h: 1200, current_snapshot_no: 3, updated_by: 'user-admin', updated_at: '2026-09-05T10:00:00Z',
  },
}

const candidateRows = {
  data: [
    {
      id: 'cand-1', version: 2, draft_changed: false, alias_id: 'alias-1', provider_id: 'prov-1',
      provider_name: 'OpenAI', provider_model_id: 'model-1', provider_model_display_name: 'GPT-4o',
      provider_model_id_label: 'gpt-4o', credential_pool_id: 'pool-1', credential_pool_name: 'openai-main',
      priority: 10, weight: 1, enabled: true, support_stream: true, support_system_message: true,
      context_window: 128000, current_concurrency: 0, runtime_status: 'AVAILABLE', excluded_reason: null,
    },
    {
      id: 'cand-2', version: 1, draft_changed: false, alias_id: 'alias-1', provider_id: 'prov-1',
      provider_name: 'OpenAI', provider_model_id: 'model-2', provider_model_display_name: 'GPT-4o mini',
      provider_model_id_label: 'gpt-4o-mini', credential_pool_id: 'pool-1', credential_pool_name: 'openai-main',
      priority: 20, weight: 1, enabled: true, support_stream: true, support_system_message: true,
      context_window: 128000, current_concurrency: 0, runtime_status: 'AVAILABLE', excluded_reason: null,
    },
  ],
}

const detailRoutes: Route[] = [
  [/\/admin\/model-aliases\/alias-1$/, () => jsonResponse(200, aliasDetail)],
  [/\/admin\/model-aliases\/alias-1\/candidates$/, () => jsonResponse(200, candidateRows)],
  [/\/admin\/model-aliases\/alias-1\/candidates\/reorder$/, (_url, method) => {
    if (method === 'PUT') {
      return jsonResponse(409, { error: { code: 'CONFIG_VERSION_CONFLICT', type: 'conflict', message: '已被修改', retryable: false } })
    }
    return jsonResponse(404, { error: { code: 'OBJECT_NOT_FOUND', type: 'api', message: 'x', retryable: false } })
  }],
]

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const pageRoutes = [
  { path: '/ui/model-aliases/new', component: AliasFormPage },
  { path: '/ui/model-aliases/:id/edit', component: AliasFormPage },
  { path: '/ui/model-aliases/:id', component: AliasDetailPage },
]

async function mountAt(path: string): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes: pageRoutes })
  void router.push(path)
  await router.isReady()
  const store = useBootstrapStore()
  store.$patch({
    status: 'ready',
    permissions: [...bootstrapFixtures.SYSTEM_ADMIN.permissions],
    roles: [...bootstrapFixtures.SYSTEM_ADMIN.roles],
  })
  const wrapper = mount(AliasFormPage.path !== '' && path.endsWith('/edit') || path.endsWith('/new') ? AliasFormPage : AliasDetailPage, {
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('AliasFormPage（FE-017）', () => {
  it('非法 alias 禁止保存', async () => {
    stubFetch([])
    const { wrapper } = await mountAt('/ui/model-aliases/new')
    const aliasInput = wrapper.find('input[maxlength="64"]')
    await aliasInput.setValue('a')
    await wrapper.find('input[maxlength="64"]:not([disabled]) + * , form input[type="text"]')
    const inputs = wrapper.findAll('form input[type="text"]')
    await inputs[1]!.setValue('默认对话')
    const saveButton = wrapper.findAll('button').find((button) => button.text() === '保存')!
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('2—64 字符，仅字母、数字、点、短横线、下划线')
  })

  it('编辑模式下 alias 只读', async () => {
    stubFetch([[/\/admin\/model-aliases\/alias-1$/, () => jsonResponse(200, aliasDetail)]])
    const { wrapper } = await mountAt('/ui/model-aliases/alias-1/edit')
    const aliasInput = wrapper.find('input[maxlength="64"]')
    expect((aliasInput.element as HTMLInputElement).disabled).toBe(true)
    expect((aliasInput.element as HTMLInputElement).value).toBe('chat-default')
  })
})

describe('AliasDetailPage 候选重排（FE-018）', () => {
  it('版本冲突时整批不提交并还原本地编辑', async () => {
    const fetchMock = stubFetch(detailRoutes)
    const { wrapper } = await mountAt('/ui/model-aliases/alias-1')
    const priorityInput = wrapper.find('input[type="number"]')
    await priorityInput.setValue('15')
    const saveButton = wrapper.findAll('button').find((button) => button.text() === '保存排序')!
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(false)
    await saveButton.trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT' && String(init?.body).includes('reorder') === false && String(init?.body).includes('cand-1'))).toBe(true)
    expect(wrapper.text()).toContain('整批未提交')
    const reverted = wrapper.find('input[type="number"]')
    expect((reverted.element as HTMLInputElement).value).toBe('10')
  })
})
