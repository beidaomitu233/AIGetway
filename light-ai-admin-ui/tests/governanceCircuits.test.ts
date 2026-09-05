import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import CircuitDetailPage from '@/pages/circuits/CircuitDetailPage.vue'
import CircuitListPage from '@/pages/circuits/CircuitListPage.vue'
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

function openCircuitRow() {
  return {
    id: 'cir-1', provider_id: 'prov-1', provider_name: 'OpenAI', provider_model_id: 'model-1',
    provider_model_name: 'GPT-4o', credential_id: 'cred-1', credential_name: 'openai-key-1',
    credential_masked_value: 'sk-****abcd', state: 'OPEN', state_version: 7, open_source: 'AUTO',
    sample_count: 24, failure_count: 14, failure_rate: '0.5833', half_open_in_flight: 0,
    half_open_success_count: 0, opened_at: '2026-09-05T10:00:00Z', next_probe_at: '2026-09-05T10:01:00Z',
    last_error_code: 'NETWORK_ERROR', updated_at: '', manual_reason: null, manual_open_until: null,
    operator: null,
    policy_snapshot: { policy_id: 'rp-1', snapshot_no: 12, circuit_window_seconds: 60, circuit_min_requests: 20, circuit_failure_rate: '0.5000', circuit_open_seconds: 30, circuit_half_open_probes: 3, circuit_half_open_successes: 2 },
    window_samples: [{ trace_id: 'tr-fail-1', attempt_id: 'at-f1', ended_at: '', error_code: 'NETWORK_ERROR', total_ms: 3010 }],
    recent_probes: [],
    pending_command: null,
  }
}

const detailRoutes: Route[] = [
  [/\/admin\/circuits\/cir-1$/, () => jsonResponse(200, { data: openCircuitRow() })],
  [/\/admin\/circuits\/cir-1\/events$/, () =>
    jsonResponse(200, {
      data: {
        items: [
          { id: 'ce-1', event_key: 'ek-1', from_state: 'CLOSED', to_state: 'OPEN', trigger_type: 'AUTO_THRESHOLD', trigger_trace_id: 'tr-fail-1', command_id: null, error_code: 'NETWORK_ERROR', reason: '阈值触发', occurred_at: '' },
        ],
        total: 1, page: 1, page_size: 50, sort: '', query_started_at: '', data_updated_at: '',
      },
    })],
]

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

async function mountAt(component: unknown, path: string, routes: { path: string; component: unknown }[]): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes: routes as never })
  void router.push(path)
  await router.isReady()
  const wrapper = mount(component as never, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

const pageRoutes = [
  { path: '/ui/circuits', component: CircuitListPage },
  { path: '/ui/circuits/:id', component: CircuitDetailPage },
]

describe('CircuitDetailPage（FE-023/024）', () => {
  it('展示状态、阈值、事件与失败样本', async () => {
    stubFetch(detailRoutes)
    const { wrapper } = await mountAt(CircuitDetailPage, '/ui/circuits/cir-1', pageRoutes)
    const text = wrapper.text()
    expect(text).toContain('state_version 7')
    expect(text).toContain('打开')
    expect(text).toContain('58.33%')
    expect(text).toContain('阈值触发')
    expect(text).toContain('tr-fail-1')
    expect(text).toContain('NETWORK_ERROR')
  })

  function fillReason(value: string): void {
    const input = document.querySelector('#lai-circuit-reason') as HTMLTextAreaElement
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
  }

  function clickDialogButton(text: string): void {
    const buttons = [...document.querySelectorAll('.lai-dialog button')]
    const target = buttons.find((button) => button.textContent?.trim() === text) as HTMLButtonElement
    target.click()
  }

  it('CAS 冲突提示最新版本并要求重新确认', async () => {
    stubFetch([
      ...detailRoutes,
      [/\/admin\/circuits\/cir-1\/recover$/, () =>
        jsonResponse(409, { error: { code: 'CIRCUIT_STATE_CONFLICT', type: 'conflict', message: '状态已变化', retryable: false, current_state_version: 9 } })],
    ])
    const { wrapper } = await mountAt(CircuitDetailPage, '/ui/circuits/cir-1', pageRoutes)
    await wrapper.findAll('button').find((button) => button.text() === '人工恢复')!.trigger('click')
    await flushPromises()
    fillReason('误判，恢复')
    await flushPromises()
    clickDialogButton('确认')
    await flushPromises()
    expect(document.body.textContent).toContain('熔断状态已被其他操作更新')
    expect(document.body.textContent).toContain('9')
  })

  it('人工恢复成功后展示更新后的状态', async () => {
    let recovered = false
    stubFetch([
      [/\/admin\/circuits\/cir-1$/, () =>
        jsonResponse(200, {
          data: recovered
            ? { ...openCircuitRow(), state: 'CLOSED', state_version: 8, open_source: null, opened_at: null, next_probe_at: null, failure_count: 0, sample_count: 0, manual_reason: null }
            : openCircuitRow(),
        })],
      [/\/admin\/circuits\/cir-1\/events$/, () =>
        jsonResponse(200, { data: { items: [], total: 0, page: 1, page_size: 50, sort: '', query_started_at: '', data_updated_at: '' } })],
      [/\/admin\/circuits\/cir-1\/recover$/, () => {
        recovered = true
        return jsonResponse(200, { data: { ...openCircuitRow(), state: 'CLOSED', state_version: 8, open_source: null, opened_at: null, next_probe_at: null, failure_count: 0, sample_count: 0, manual_reason: null } })
      }],
    ])
    const { wrapper } = await mountAt(CircuitDetailPage, '/ui/circuits/cir-1', pageRoutes)
    await wrapper.findAll('button').find((button) => button.text() === '人工恢复')!.trigger('click')
    await flushPromises()
    fillReason('误判，恢复')
    await flushPromises()
    clickDialogButton('确认')
    await flushPromises()
    expect(wrapper.text()).toContain('已提交人工恢复')
    expect(wrapper.text()).toContain('state_version 8')
  })

  it('pending_command 未收敛时显示待定提示', async () => {
    stubFetch([
      [/\/admin\/circuits\/cir-1$/, () =>
        jsonResponse(200, { data: { ...openCircuitRow(), pending_command: { command_id: 'cmd-1', action: 'MANUAL_OPEN', status: 'PENDING', created_at: '' } } })],
      [/\/admin\/circuits\/cir-1\/events$/, () =>
        jsonResponse(200, { data: { items: [], total: 0, page: 1, page_size: 50, sort: '', query_started_at: '', data_updated_at: '' } })],
    ])
    const { wrapper } = await mountAt(CircuitDetailPage, '/ui/circuits/cir-1', pageRoutes)
    expect(wrapper.text()).toContain('待收敛命令')
  })
})

describe('CircuitListPage（FE-023）', () => {
  it('OPEN 排序靠前且无凭证权限时显示受限凭证', async () => {
    const store = useBootstrapStore()
    store.$patch({ roles: ['VIEWER'], permissions: [...bootstrapFixtures.VIEWER.permissions] })
    stubFetch([
      [/\/admin\/circuits$/, () =>
        jsonResponse(200, {
          data: {
            items: [openCircuitRow()],
            total: 1, page: 1, page_size: 20, sort: 'state_priority', query_started_at: '', data_updated_at: '',
          },
        })],
    ])
    const { wrapper } = await mountAt(CircuitListPage, '/ui/circuits', pageRoutes)
    const text = wrapper.text()
    expect(text).toContain('受限凭证')
    expect(text).not.toContain('openai-key-1')
    expect(text).toContain('24（失败 14）')
    expect(text).toContain('查看详情')
  })
})
