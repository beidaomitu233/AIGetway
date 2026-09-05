import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import CredentialPanel from '@/components/credentials/CredentialPanel.vue'
import CredentialFormDialog from '@/components/credentials/CredentialFormDialog.vue'
import SecretInput from '@/components/SecretInput.vue'
import { createPinia, setActivePinia } from 'pinia'
import type { CredentialListItem } from '@/api/credentials'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const credentialRows: CredentialListItem[] = [
  {
    id: 'cred-1',
    pool_id: 'pool-1',
    name: 'openai-key-1',
    masked_value: 'sk-****abcd',
    secret_source: 'INLINE_ENCRYPTED',
    weight: 1,
    rpm_limit: null,
    tpm_limit: null,
    concurrent_limit: null,
    current_concurrency: 0,
    health_status: 'HEALTHY',
    rate_limit_reset_at: null,
    last_success_at: '2026-09-05T10:00:00Z',
    last_check_at: '2026-09-05T10:00:00Z',
    enabled: true,
    draft_changed: false,
    version: 2,
  },
]

function stubFetch(routes: [RegExp, (url: URL, method: string) => Response][]): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    const parsed = new URL(url, 'http://localhost')
    const method = init?.method ?? 'GET'
    for (const [pattern, handler] of routes) {
      if (pattern.test(parsed.pathname)) {
        return Promise.resolve(handler(parsed, method))
      }
    }
    return Promise.resolve(jsonResponse(404, { error: { code: 'OBJECT_NOT_FOUND', type: 'api', message: 'x', retryable: false } }))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  document.body.innerHTML = ''
})

const listRoutes: [RegExp, (url: URL, method: string) => Response][] = [
  [/\/admin\/credential-pools\/pool-1\/credentials$/, (_url, method) =>
    method === 'GET'
      ? jsonResponse(200, { data: { items: credentialRows, total: 1, page: 1, page_size: 20, sort: 'name', query_started_at: 'q', data_updated_at: 'u' } })
      : jsonResponse(200, { data: { id: 'cred-new', version: 1, entity: null, draft_changed: true, draft_revision: null, request_id: 'r1' } })],
]

describe('CredentialPanel（FE-013/014）', () => {
  it('列表渲染掩码值且不出现密钥原文', async () => {
    stubFetch(listRoutes)
    const wrapper = mount(CredentialPanel, {
      props: { poolId: 'pool-1', canManage: true, canCheck: true },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('openai-key-1')
    expect(text).toContain('sk-****abcd')
    expect(wrapper.html()).not.toContain('sk-real-secret')
  })

  it('无管理权限时不渲染新增与行内编辑按钮', async () => {
    stubFetch(listRoutes)
    const wrapper = mount(CredentialPanel, {
      props: { poolId: 'pool-1', canManage: false, canCheck: false },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('新增 Credential')
    expect(wrapper.text()).not.toContain('轮换密钥')
  })

  it('删除被 CAPACITY_IN_USE 拒绝时保留对象并提示', async () => {
    const fetchMock = stubFetch([
      ...listRoutes,
      [/\/admin\/credentials\/cred-1$/, (_url, method) =>
        method === 'DELETE'
          ? jsonResponse(409, { error: { code: 'CAPACITY_IN_USE', type: 'conflict', message: '占用中', retryable: false } })
          : jsonResponse(200, { data: {} })],
    ])
    const wrapper = mount(CredentialPanel, {
      props: { poolId: 'pool-1', canManage: true, canCheck: true },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    const deleteButton = wrapper.findAll('button').find((button) => button.text() === '删除')!
    await deleteButton.trigger('click')
    await flushPromises()
    const confirmButton = wrapper.findAll('.lai-dialog button').find((button) => button.text() === '确认')
    await confirmButton!.trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.some(([, init]) => (init?.method ?? 'GET') === 'DELETE' && String(init?.body).includes('cred-1') === false)).toBe(true)
    expect(wrapper.text()).toContain('正在被运行中的调用占用')
    expect(wrapper.text()).toContain('openai-key-1')
  })

  it('10 秒轮询在组件卸载后停止', async () => {
    vi.useFakeTimers()
    const fetchMock = stubFetch(listRoutes)
    const wrapper = mount(CredentialPanel, {
      props: { poolId: 'pool-1', canManage: false, canCheck: false },
      global: { stubs: { teleport: true } },
    })
    await vi.advanceTimersByTimeAsync(0)
    const initialCalls = fetchMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(20000)
    const polledCalls = fetchMock.mock.calls.length
    expect(polledCalls).toBeGreaterThan(initialCalls)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(30000)
    expect(fetchMock.mock.calls.length).toBe(polledCalls)
  })
})

describe('CredentialFormDialog（FE-013）', () => {
  function setInputValue(selector: string, value: string): void {
    const input = document.querySelector(selector) as HTMLInputElement
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
  }

  function clickButton(text: string): void {
    const buttons = [...document.querySelectorAll('.lai-dialog button')]
    const target = buttons.find((button) => button.textContent?.trim() === text) as HTMLButtonElement
    target.click()
  }

  it('INLINE 新建两次密钥不一致时禁用保存', async () => {
    const wrapper = mount(CredentialFormDialog, {
      props: { open: true, credential: null },
      attachTo: document.body,
    })
    setInputValue('#lai-cred-name', 'test-key')
    setInputValue('#lai-cred-secret', 'secret-1')
    setInputValue('#lai-cred-secret-confirm', 'secret-2')
    await flushPromises()
    const saveButton = [...document.querySelectorAll('.lai-dialog button')].find(
      (button) => button.textContent?.trim() === '保存',
    ) as HTMLButtonElement
    expect(saveButton.disabled).toBe(true)
    wrapper.unmount()
  })

  it('编辑时来源只读且不要求密钥', async () => {
    const wrapper = mount(CredentialFormDialog, {
      props: {
        open: true,
        credential: { ...credentialRows[0]!, secret_source: 'INLINE_ENCRYPTED' as const },
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(document.querySelector('#lai-cred-secret')).toBeNull()
    expect(document.body.textContent).toContain('创建后不可切换')
    const saveButton = [...document.querySelectorAll('.lai-dialog button')].find(
      (button) => button.textContent?.trim() === '保存',
    ) as HTMLButtonElement
    expect(saveButton.disabled).toBe(false)
    wrapper.unmount()
  })

  it('空限额提交为 null，卸载时清空密钥', async () => {
    const wrapper = mount(CredentialFormDialog, {
      props: { open: true, credential: null },
      attachTo: document.body,
    })
    setInputValue('#lai-cred-name', 'test-key')
    setInputValue('#lai-cred-secret', 'secret-1')
    setInputValue('#lai-cred-secret-confirm', 'secret-1')
    await flushPromises()
    clickButton('保存')
    await flushPromises()
    const emitted = wrapper.emitted('confirm')!
    const command = emitted[0]![0] as { rpm_limit: unknown; secret_value: string }
    expect(command.rpm_limit).toBeNull()
    expect(command.secret_value).toBe('secret-1')
    const secretInputs = wrapper.findAllComponents(SecretInput)
    wrapper.unmount()
    expect(secretInputs[0]!.emitted('update:modelValue')!.at(-1)).toEqual([''])
  })
})
