import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import { Select } from 'ant-design-vue'
import { routes } from '@/app/router'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import {
  dataEnvelope,
  installJsonFetchStub,
  type FetchStub,
} from './helpers/fetchStub'

const auditRow = {
  id: 'aud-1',
  created_at: '2026-09-05T09:00:00Z',
  request_id: 'req-audit-001',
  operator_id: 'user-admin',
  operator_name: '系统管理员',
  operator_role: 'SYSTEM_ADMIN',
  operation: 'UPDATE',
  operation_reason: null,
  entity_type: 'provider',
  entity_id: 'prov-001',
  entity_name: 'OpenAI 生产',
  change_summary: 'read_timeout_ms',
  source_mode: 'ADMIN_UI',
  result: 'SUCCEEDED',
  error_code: null,
  duration_ms: 45,
}

async function mountPage(path: string, role: keyof typeof bootstrapFixtures, mode = 'STANDALONE_SERVER') {
  setActivePinia(createPinia())
  const store = useBootstrapStore()
  store.$patch({
    status: 'ready',
    permissions: [...bootstrapFixtures[role].permissions],
    roles: [...bootstrapFixtures[role].roles],
    runtimeMode: mode,
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

function bootstrapHandler() {
  return ({ url, method }: { url: URL; method: string }) => {
    if (method === 'GET' && url.pathname.endsWith('/admin/bootstrap')) {
      return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
    }
    return undefined
  }
}

describe('AuditListPage（FE-048）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  function handler() {
    return ({ url, method }: { url: URL; method: string }) => {
      const base = bootstrapHandler()({ url, method })
      if (base) return base
      if (method === 'GET' && url.pathname.endsWith('/admin/audit-logs')) {
        if (url.searchParams.get('request_id') === 'req-audit-001') {
          return {
            status: 200,
            body: { data: { items: [auditRow], total: 1, page: 1, page_size: 20, sort: '-created_at', query_started_at: 'x', data_updated_at: 'x' } },
          }
        }
        if (url.searchParams.get('result') === 'FAILED') {
          return {
            status: 200,
            body: { data: { items: [], total: 0, page: 1, page_size: 20, sort: '-created_at', query_started_at: 'x', data_updated_at: 'x' } },
          }
        }
        return {
          status: 200,
          body: { data: { items: [auditRow], total: 1, page: 1, page_size: 20, sort: '-created_at', query_started_at: 'x', data_updated_at: 'x' } },
        }
      }
      if (method === 'GET' && url.pathname.includes('/admin/audit-logs/aud-1')) {
        return dataEnvelope({
          ...auditRow,
          client_ip: '10.1.1.2',
          user_agent: 'Mozilla/5.0',
          before_version: 4,
          after_version: 5,
          changed_fields: [
            { field_name: 'read_timeout_ms', before_value: '120000', after_value: '90000', sensitive: false },
            { field_name: 'api_key', before_value: null, after_value: null, sensitive: true },
          ],
          error_summary: null,
        })
      }
      return undefined
    }
  }

  it('详情展示脱敏 diff 与 request_id，敏感字段不出现原文', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/audit-logs/aud-1', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('req-audit-001')
    expect(text).toContain('read_timeout_ms')
    expect(text).toContain('120000')
    expect(text).toContain('已脱敏')
    expect(text).not.toContain('sk-live')
    expect(text).toContain('4 → 5')
  })

  it('列表展示操作人、变更字段摘要与结果', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/audit-logs', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('系统管理员')
    expect(text).toContain('read_timeout_ms')
    expect(text).toContain('成功')
  })

  it('request_id 精确查询进入查询参数', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/audit-logs', 'SYSTEM_ADMIN')
    const inputs = wrapper.findAll('input')
    const requestInput = inputs.find((input) => input.attributes('placeholder') === 'request_id 精确查询')
    await requestInput!.setValue('req-audit-001')
    await requestInput!.trigger('change')
    await flushPromises()
    const listCall = stub.calls.filter((call) => call.url.includes('/admin/audit-logs?')).at(-1)!
    expect(listCall.url).toContain('request_id=req-audit-001')
  })

  it('详情抽屉展示脱敏 diff，敏感字段无值', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/audit-logs', 'SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '详情')!.trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('request_id')
    expect(text).toContain('req-audit-001')
    expect(text).toContain('sensitive=true（已脱敏）')
    expect(text).toContain('read_timeout_ms')
  })

  it('失败记录筛选进入查询参数', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/audit-logs', 'SYSTEM_ADMIN')
    const resultSelect = wrapper.findAllComponents(Select).find((component) => component.attributes('aria-label') === '结果')
    expect(resultSelect).toBeDefined()
    ;(resultSelect!.vm as unknown as { $emit: (event: string, ...args: unknown[]) => void }).$emit('change', 'FAILED')
    await flushPromises()
    const listCall = stub.calls.filter((call) => call.url.includes('/admin/audit-logs?')).at(-1)!
    expect(listCall.url).toContain('result=FAILED')
  })
})
