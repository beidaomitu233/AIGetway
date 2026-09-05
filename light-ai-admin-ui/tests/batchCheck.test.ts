import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import BatchCheckPanel from '@/pages/models/BatchCheckPanel.vue'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function jobObject(status: string, completed: number) {
  return {
    id: 'job-1',
    status,
    total_count: 2,
    completed_count: completed,
    success_count: completed,
    failure_count: 0,
    cancelled_count: 0,
    started_at: '2026-09-05T10:00:00Z',
    ended_at: null,
    command: { provider_model_ids: ['m-1', 'm-2'], credential_id: 'cred-1', mode: 'MINIMAL_CHAT', timeout_ms: 10000 },
  }
}

function itemRows(status: string) {
  return [
    { id: 'i-1', provider_model_id: 'm-1', provider_model_name: 'M1', sequence: 1, status: 'SUCCEEDED', check_record_id: 'c1', error_code: null },
    { id: 'i-2', provider_model_id: 'm-2', provider_model_name: 'M2', sequence: 2, status, check_record_id: null, error_code: null },
  ]
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  document.body.innerHTML = ''
})

const models = [
  { id: 'm-1', label: 'M1' },
  { id: 'm-2', label: 'M2' },
]
const credentialOptions = [{ id: 'cred-1', label: 'key-1' }]

function mountPanel(): ReturnType<typeof mount> {
  return mount(BatchCheckPanel, {
    props: { open: true, models, credentialOptions, providerName: 'OpenAI' },
    global: { stubs: { teleport: true } },
  })
}

describe('BatchCheckPanel（FE-016）', () => {
  it('提交后按轮询推进到终态并停止轮询', async () => {
    vi.useFakeTimers()
    let pollCount = 0
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (String(url).endsWith('/admin/provider-models/batch-check') && method === 'POST') {
        return Promise.resolve(jsonResponse(200, { data: jobObject('RUNNING', 0) }))
      }
      if (String(url).includes('/admin/batch-check-jobs/job-1')) {
        pollCount += 1
        const completed = Math.min(pollCount, 2)
        const status = completed >= 2 ? 'SUCCEEDED' : 'RUNNING'
        const itemStatus = completed >= 1 ? 'SUCCEEDED' : 'PENDING'
        return Promise.resolve(jsonResponse(200, { data: { job: jobObject(status, completed), items: itemRows(itemStatus) } }))
      }
      return Promise.resolve(jsonResponse(404, { error: { code: 'OBJECT_NOT_FOUND', type: 'api', message: 'x', retryable: false } }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mountPanel()
    await wrapper.find('select').setValue('cred-1')
    await wrapper.findAll('button').find((button) => button.text() === '开始检测')!.trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()
    expect(wrapper.text()).toContain('0 / 2')

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.text()).toContain('1 / 2')

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.text()).toContain('2 / 2')
    expect(wrapper.text()).toContain('已完成')

    const callsAtTerminal = fetchMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(10000)
    await flushPromises()
    expect(fetchMock.mock.calls.length).toBe(callsAtTerminal)
  })

  it('取消任务后进入 CANCELLED', async () => {
    vi.useFakeTimers()
    let cancelled = false
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        const method = init?.method ?? 'GET'
        if (String(url).endsWith('/admin/provider-models/batch-check') && method === 'POST') {
          return Promise.resolve(jsonResponse(200, { data: jobObject('RUNNING', 0) }))
        }
        if (String(url).endsWith('/admin/batch-check-jobs/job-1/cancel') && method === 'POST') {
          cancelled = true
          return Promise.resolve(jsonResponse(200, { data: jobObject('CANCELLED', 0) }))
        }
        if (String(url).includes('/admin/batch-check-jobs/job-1')) {
          const status = cancelled ? 'CANCELLED' : 'RUNNING'
          const itemStatus = cancelled ? 'CANCELLED' : 'PENDING'
          return Promise.resolve(jsonResponse(200, { data: { job: jobObject(status, 0), items: itemRows(itemStatus) } }))
        }
        return Promise.resolve(jsonResponse(404, { error: { code: 'OBJECT_NOT_FOUND', type: 'api', message: 'x', retryable: false } }))
      }),
    )

    const wrapper = mountPanel()
    await wrapper.find('select').setValue('cred-1')
    await wrapper.findAll('button').find((button) => button.text() === '开始检测')!.trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '取消任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已取消')
  })

  it('未选凭证时禁止开始', () => {
    const wrapper = mountPanel()
    const startButton = wrapper.findAll('button').find((button) => button.text() === '开始检测')!
    expect((startButton.element as HTMLButtonElement).disabled).toBe(true)
  })
})
