import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import LimitFormPage from '@/pages/limits/LimitFormPage.vue'
import ReliabilityFormPage from '@/pages/reliabilities/ReliabilityFormPage.vue'
import LimitListPage from '@/pages/limits/LimitListPage.vue'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'

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

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useBootstrapStore()
  store.$patch({
    status: 'ready',
    permissions: [...bootstrapFixtures.SYSTEM_ADMIN.permissions],
    roles: [...bootstrapFixtures.SYSTEM_ADMIN.roles],
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

import type { RouteRecordRaw } from 'vue-router'

async function mountAt(component: unknown, path: string, routes: { path: string; component: unknown }[]): Promise<ReturnType<typeof mount>> {
  const router = createRouter({ history: createMemoryHistory(), routes: routes as unknown as RouteRecordRaw[] })
  void router.push(path)
  await router.isReady()
  const wrapper = mount(component as never, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

const scopeRoutes: Route[] = [
  [/\/admin\/model-aliases$/, () =>
    jsonResponse(200, { data: { items: [{ id: 'alias-1', alias: 'chat-default', display_name: '默认对话' }], total: 1, page: 1, page_size: 100, sort: 'alias', query_started_at: '', data_updated_at: '' } })],
]

describe('LimitFormPage（FE-019）', () => {
  const pageRoutes = [
    { path: '/ui/limit-policies/new', component: LimitFormPage },
    { path: '/ui/limit-policies/:id/edit', component: LimitFormPage },
  ]

  it('REJECT 时隐藏队列字段；全空限额启用被阻止', async () => {
    const wrapper = await mountAt(LimitFormPage, '/ui/limit-policies/new', pageRoutes)
    expect(wrapper.text()).not.toContain('排队超时')
    expect(wrapper.text()).toContain('至少一个 RPM、TPM、并发上限非空时才允许启用')
    const saveButton = wrapper.findAll('button').find((button) => button.text() === '保存')!
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('QUEUE 时必填队列参数，合法表单提交空限额为 null', async () => {
    const fetchMock = stubFetch([
      ...scopeRoutes,
      [/\/admin\/limit-policies$/, (_url, method) =>
        method === 'POST'
          ? jsonResponse(200, { data: { id: 'lp-new', version: 1, entity: null, draft_changed: true, draft_revision: null, request_id: 'r' } })
          : jsonResponse(200, { data: { items: [], total: 0, page: 1, page_size: 20, sort: '', query_started_at: '', data_updated_at: '' } })],
    ])
    const wrapper = await mountAt(LimitFormPage, '/ui/limit-policies/new', pageRoutes)
    await wrapper.find('input[maxlength="64"]').setValue('alias-guard')
    const selects = wrapper.findAll('select')
    await selects[1]!.setValue('alias-1')
    await flushPromises()
    await selects[2]!.setValue('QUEUE')
    await flushPromises()
    const numeric = wrapper.findAll('input[inputmode="numeric"]')
    // RPM、TPM、并发、queue_timeout_ms、queue_max_size
    await numeric[0]!.setValue('100')
    await numeric[3]!.setValue('5000')
    await numeric[4]!.setValue('1000')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post).toBeTruthy()
    const body = JSON.parse(String(post![1]!.body)) as { rpm_limit: unknown; overflow_strategy: string; queue_timeout_ms: number }
    expect(body.rpm_limit).toBe(100)
    expect(body.overflow_strategy).toBe('QUEUE')
    expect(body.queue_timeout_ms).toBe(5000)
  })
})

describe('LimitListPage（FE-019/020）', () => {
  it('列表展示用量百分比、溢出策略与计数存储状态', async () => {
    stubFetch([
      [/\/admin\/limit-policies$/, () =>
        jsonResponse(200, {
          data: {
            items: [
              {
                id: 'lp-1', version: 2, draft_changed: false, name: 'alias-chat-guard', scope_type: 'MODEL_ALIAS',
                scope_id: 'alias-1', scope_name: '默认对话（chat-default）', rpm_limit: 1000, tpm_limit: 200000,
                concurrent_limit: 50, rpm_used: 320, tpm_reserved: 48000, tpm_confirmed: 41000, concurrency_used: 6,
                queue_length: 2, queue_max_size: 1000, overflow_strategy: 'QUEUE', window_end: 'w',
                counter_store_status: 'OK', enabled: true, updated_at: '', rpm: 0,
              },
            ],
            total: 1, page: 1, page_size: 20, sort: 'updated_at', query_started_at: '', data_updated_at: '',
          },
        })],
    ])
    const wrapper = await mountAt(LimitListPage, '/ui/limit-policies', [{ path: '/ui/limit-policies', component: LimitListPage }])
    const text = wrapper.text()
    expect(text).toContain('320 / 1000')
    expect(text).toContain('32%')
    expect(text).toContain('直接拒绝')
    expect(text).toContain('进入排队')
    expect(text).toContain('排队 2 / 1000')
  })
})

describe('ReliabilityFormPage（FE-021）', () => {
  const pageRoutes = [
    { path: '/ui/reliability-policies/new', component: ReliabilityFormPage },
    { path: '/ui/reliability-policies/:id/edit', component: ReliabilityFormPage },
  ]

  it('首 Token 超时不小于总超时时阻止保存', async () => {
    stubFetch([[/\/admin\/model-aliases$/, () =>
      jsonResponse(200, { data: { items: [{ id: 'alias-1', alias: 'chat-default', display_name: '默认对话' }], total: 1, page: 1, page_size: 100, sort: 'alias', query_started_at: '', data_updated_at: '' } })]])
    const wrapper = await mountAt(ReliabilityFormPage, '/ui/reliability-policies/new', pageRoutes)
    const inputs = wrapper.findAll('input[type="text"]')
    // name, connect, first_token, total
    await inputs[0]!.setValue('chat-reliability')
    await inputs[1]!.setValue('3000')
    await inputs[2]!.setValue('120000')
    await inputs[3]!.setValue('120000')
    await wrapper.findAll('select')[0]!.setValue('alias-1')
    await flushPromises()
    expect(wrapper.text()).toContain('必须小于总超时')
    const saveButton = wrapper.findAll('button').find((button) => button.text() === '保存')!
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('失败率百分比提交转换为 0—1 小数', async () => {
    const fetchMock = stubFetch([
      [/\/admin\/model-aliases$/, () =>
        jsonResponse(200, { data: { items: [{ id: 'alias-1', alias: 'chat-default', display_name: '默认对话' }], total: 1, page: 1, page_size: 100, sort: 'alias', query_started_at: '', data_updated_at: '' } })],
      [/\/admin\/reliability-policies$/, (_url, method) =>
        method === 'POST'
          ? jsonResponse(200, { data: { id: 'rp-new', version: 1, entity: null, draft_changed: true, draft_revision: null, request_id: 'r' } })
          : jsonResponse(200, { data: { items: [], total: 0, page: 1, page_size: 20, sort: '', query_started_at: '', data_updated_at: '' } })],
    ])
    const wrapper = await mountAt(ReliabilityFormPage, '/ui/reliability-policies/new', pageRoutes)
    const inputs = wrapper.findAll('input[type="text"]')
    await inputs[0]!.setValue('chat-reliability')
    await wrapper.findAll('select')[0]!.setValue('alias-1')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    const body = JSON.parse(String(post![1]!.body)) as { circuit_failure_rate: string }
    expect(body.circuit_failure_rate).toBe('0.5000')
  })
})
