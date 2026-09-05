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
} from './helpers/fetchStub'

const detail = {
  id: 'prov-1',
  name: 'OpenAI 生产',
  type: 'OPENAI',
  base_url: 'https://api.openai.com/v1/',
  proxy_url: null,
  connect_timeout_ms: 3000,
  read_timeout_ms: 120000,
  default_headers: { 'X-Env': 'production' },
  connection_status: 'AVAILABLE',
  last_check_at: '2026-09-05T02:00:00Z',
  last_check_latency_ms: 430,
  last_error_code: null,
  enabled: true,
  draft_changed: false,
  version: 3,
  created_by: 'admin',
  created_at: '2026-09-01T00:00:00Z',
  updated_by: 'admin',
  updated_at: '2026-09-05T01:00:00Z',
  recent_check_records: [
    {
      id: 'chk-1',
      target_type: 'PROVIDER',
      target_id: 'prov-1',
      status: 'FAILED',
      started_at: '2026-09-05T01:00:00Z',
      ended_at: '2026-09-05T01:00:01Z',
      total_ms: 1200,
      trace_id: null,
      usage: null,
      error_code: 'PROVIDER_AUTH_FAILED',
      error_summary: '鉴权失败',
      provider_request_id: null,
    },
  ],
}

async function mountDetail(role: keyof typeof bootstrapFixtures) {
  setActivePinia(createPinia())
  const store = useBootstrapStore()
  store.$patch({
    status: 'ready',
    permissions: [...bootstrapFixtures[role].permissions],
    roles: [...bootstrapFixtures[role].roles],
    adapters: [...(bootstrapFixtures.SYSTEM_ADMIN.adapters ?? [])],
  })
  const router = createRouter({ history: createMemoryHistory(), routes })
  void router.push('/ui/providers/prov-1')
  await router.isReady()
  const wrapper = mount(
    { template: '<RouterView />' },
    { global: { plugins: [router], stubs: { teleport: true } } },
  )
  await flushPromises()
  return { wrapper, router }
}

describe('ProviderDetailPage（FE-009/FE-010）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
  })

  function baseHandler() {
    return ({ url, method }: { url: URL; method: string }) => {
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/providers/prov-1')) {
        return dataEnvelope(detail)
      }
      if (url.pathname.includes('/admin/credential-pools')) {
        return pageEnvelope([])
      }
      if (url.pathname.includes('/admin/provider-models')) {
        return pageEnvelope([
          { id: 'pm-1', display_name: 'GPT-4o', model_id: 'gpt-4o', connection_status: 'AVAILABLE', enabled: true, draft_changed: false },
        ])
      }
      return undefined
    }
  }

  it('详情展示状态摘要、基础配置、检测记录与审计信息', async () => {
    stub = installJsonFetchStub(baseHandler())
    const { wrapper } = await mountDetail('SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('最近检测')
    expect(text).toContain('可用')
    expect(text).toContain('430 ms')
    expect(text).toContain('X-Env: production')
    expect(text).toContain('PROVIDER_AUTH_FAILED · 鉴权失败')
    expect(text).toContain('关联模型')
    expect(text).toContain('GPT-4o')
    expect(text).toContain('审计信息')
  })

  it('检测提交命令并展示结果与耗时', async () => {
    stub = installJsonFetchStub((context) => {
      const base = baseHandler()(context)
      if (base) return base
      const { url, method } = context
      if (method === 'POST' && url.pathname.endsWith('/admin/providers/prov-1/check')) {
        return dataEnvelope({
          id: 'chk-2',
          target_type: 'PROVIDER',
          target_id: 'prov-1',
          status: 'SUCCEEDED',
          started_at: '2026-09-05T03:00:00Z',
          ended_at: '2026-09-05T03:00:01Z',
          total_ms: 386,
          trace_id: null,
          usage: { total_tokens: 14 },
          error_code: null,
          error_summary: null,
          provider_request_id: 'req-001',
        })
      }
      return undefined
    })
    const { wrapper } = await mountDetail('SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '检测')!.trigger('click')
    expect(wrapper.find('.lai-dialog').exists()).toBe(true)
    // 默认最小对话检测需要选择模型
    const confirmButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '开始检测')!
    expect((confirmButton.element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.find('#lai-check-model').setValue('pm-1')
    const enabledConfirm = wrapper
      .findAll('button')
      .find((button) => button.text() === '开始检测')!
    expect((enabledConfirm.element as HTMLButtonElement).disabled).toBe(false)
    await enabledConfirm.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('检测结果：')
    expect(wrapper.text()).toContain('成功')
    expect(wrapper.text()).toContain('386 ms')
    expect(wrapper.text()).toContain('Provider Request ID：req-001')
    const checkCall = stub.calls.find((call) => call.url.includes('/check'))
    expect(checkCall).toBeDefined()
    expect(checkCall!.body.mode).toBe('MINIMAL_CHAT')
    expect(checkCall!.body.provider_model_id).toBe('pm-1')
    expect(checkCall!.body.timeout_ms).toBe(10000)
  })

  it('停用需影响分析确认，取消不提交命令', async () => {
    stub = installJsonFetchStub((context) => {
      const base = baseHandler()(context)
      if (base) return base
      const { url, method } = context
      if (method === 'GET' && url.pathname.endsWith('/impact')) {
        return dataEnvelope({
          impact_version: 'impact-1',
          entity_type: 'provider',
          entity_id: 'prov-1',
          references: [
            { entity_type: 'credential_pool', id: 'pool-1', name: 'OpenAI 主池', relation: '凭证池归属' },
          ],
          affected_alias_ids: ['alias-1'],
          can_delete: false,
          blockers: [],
        })
      }
      return undefined
    })
    const { wrapper } = await mountDetail('SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '停用')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('停用确认')
    expect(wrapper.text()).toContain('credential_pool · OpenAI 主池（凭证池归属）')
    await wrapper.findAll('button').find((button) => button.text() === '取消')!.trigger('click')
    expect(stub.calls.filter((call) => call.url.endsWith('/disable'))).toHaveLength(0)
  })

  it('停用确认携带 confirmed_impact_version，影响过期重新确认', async () => {
    let impactCount = 0
    let disableCount = 0
    stub = installJsonFetchStub((context) => {
      const base = baseHandler()(context)
      if (base) return base
      const { url, method } = context
      if (method === 'GET' && url.pathname.endsWith('/impact')) {
        impactCount += 1
        return dataEnvelope({
          impact_version: `impact-${impactCount}`,
          entity_type: 'provider',
          entity_id: 'prov-1',
          references: [],
          affected_alias_ids: [],
          can_delete: false,
          blockers: [],
        })
      }
      if (method === 'POST' && url.pathname.endsWith('/disable')) {
        disableCount += 1
        if (disableCount === 1) {
          return errorEnvelope(409, 'IMPACT_ANALYSIS_EXPIRED', '引用关系已变化')
        }
        return dataEnvelope({ id: 'prov-1', version: 4, entity: null, draft_changed: true, draft_revision: 9, request_id: 'r' })
      }
      return undefined
    })
    const { wrapper } = await mountDetail('SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '停用')!.trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认')!.trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('引用关系已变化，需要重新确认影响')
    })
    const confirmButton = wrapper.findAll('button').find((button) => button.text() === '确认')
    await confirmButton!.trigger('click')
    await flushPromises()
    await flushPromises()
    const disableCalls = stub.calls.filter((call) => call.url.endsWith('/disable'))
    expect(disableCalls).toHaveLength(2)
    expect(disableCalls[1].body.confirmed_impact_version).toBe('impact-2')
    expect(disableCalls[1].body.version).toBe(3)
  })

  it('删除被引用对象展示阻塞原因且不提交', async () => {
    stub = installJsonFetchStub((context) => {
      const base = baseHandler()(context)
      if (base) return base
      const { url, method } = context
      if (method === 'GET' && url.pathname.endsWith('/impact')) {
        return dataEnvelope({
          impact_version: 'impact-del-1',
          entity_type: 'provider',
          entity_id: 'prov-1',
          references: [
            { entity_type: 'credential_pool', id: 'pool-1', name: 'OpenAI 主池', relation: '凭证池归属' },
          ],
          affected_alias_ids: [],
          can_delete: false,
          blockers: ['存在关联凭证池'],
        })
      }
      return undefined
    })
    const { wrapper } = await mountDetail('SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('删除确认')
    expect(wrapper.text()).toContain('阻断原因：存在关联凭证池')
    // 确认提交后服务端拒绝 OBJECT_IN_USE，对话框显示错误且对象保留
    stub = installJsonFetchStub((context) => {
      const base = baseHandler()(context)
      if (base) return base
      const { url, method } = context
      if (method === 'GET' && url.pathname.endsWith('/impact')) {
        return dataEnvelope({
          impact_version: 'impact-del-1',
          entity_type: 'provider',
          entity_id: 'prov-1',
          references: [
            { entity_type: 'credential_pool', id: 'pool-1', name: 'OpenAI 主池', relation: '凭证池归属' },
          ],
          affected_alias_ids: [],
          can_delete: false,
          blockers: ['存在关联凭证池'],
        })
      }
      return undefined
    })
    const confirmButton = wrapper.findAll('button').find((button) => button.text() === '确认')
    await confirmButton!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('删除成功')
  })

  it('只读角色不展示管理动作与检测按钮', async () => {
    stub = installJsonFetchStub(baseHandler())
    const { wrapper } = await mountDetail('VIEWER')
    const text = wrapper.text()
    expect(text).toContain('最近检测')
    expect(text).not.toContain('检测记录按钮')
    expect(wrapper.findAll('button').some((button) => button.text() === '停用')).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === '删除')).toBe(false)
  })
})
