import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import { routes } from '@/app/router'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import { dataEnvelope, installJsonFetchStub, pageEnvelope, type FetchStub } from './helpers/fetchStub'

const application = {
  id: '4a9b72f1-1225-42fd-b304-33d1884e9695',
  code: 'customer-service-prod',
  name: '智能客服生产环境',
  department: '客户成功部',
  owner_id: 'user-admin',
  owner_name: '系统管理员',
  environment: 'PROD',
  description: '企业客服系统',
  status: 'ACTIVE',
  active_key_count: 1,
  model_count: 1,
  token_limit: 1_000_000,
  tokens_used: 1200,
  tokens_reserved: 100,
  amount_limit: '1000',
  amount_used: '12.5',
  amount_reserved: '0.5',
  currency: 'CNY',
  rpm: 60,
  tpm: 100_000,
  last_called_at: '2026-09-08T08:00:00Z',
  created_at: '2026-09-01T08:00:00Z',
  updated_at: '2026-09-08T08:00:00Z',
  version: 2,
  quota: {
    id: 'quota-1', token_limit: 1_000_000, tokens_used: 1200, tokens_reserved: 100,
    amount_limit: '1000', amount_used: '12.5', amount_reserved: '0.5', currency: 'CNY',
    rpm: 60, tpm: 100_000, period_type: 'MONTH', period_start: null, period_end: null, version: 1,
  },
  models: [{ id: 'permission-1', virtual_model_id: 'alias-1', virtual_model_code: 'chat-default', enabled: true, version: 1 }],
}

async function mountPage(path: string) {
  setActivePinia(createPinia())
  const store = useBootstrapStore()
  store.$patch({
    status: 'ready',
    userId: 'user-admin',
    displayName: '系统管理员',
    timezone: 'Asia/Shanghai',
    roles: ['SYSTEM_ADMIN'],
    permissions: [...bootstrapFixtures.SYSTEM_ADMIN.permissions],
  })
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('Application pages（V2 应用中心）', () => {
  let stub: FetchStub

  afterEach(() => stub?.restore())

  it('列表以应用为中心展示额度、模型和密钥摘要', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/applications')) return pageEnvelope([application])
      return undefined
    })
    const { wrapper } = await mountPage('/ui/applications')
    expect(wrapper.text()).toContain('智能客服生产环境')
    expect(wrapper.text()).toContain('customer-service-prod')
    expect(wrapper.text()).toContain('1,300 / 1,000,000')
    expect(wrapper.text()).toContain('13.00 / 1000.00 CNY')
  })

  it('创建应用提交基本信息、初始额度与模型授权', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/model-aliases')) {
        return pageEnvelope([{ id: 'alias-1', alias: 'chat-default', display_name: '默认对话', enabled: true }])
      }
      if (method === 'POST' && url.pathname.endsWith('/admin/applications')) {
        return dataEnvelope({ id: application.id, version: 1, entity: application, draft_changed: false, draft_revision: null, request_id: 'req-1' })
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) {
        return dataEnvelope(application)
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) {
        return dataEnvelope([])
      }
      return undefined
    })
    const { wrapper, router } = await mountPage('/ui/applications/new')
    await wrapper.find('input[name="name"]').setValue('智能客服生产环境')
    await wrapper.find('input[name="code"]').setValue('customer-service-prod')
    await wrapper.find('input[type="checkbox"][value="alias-1"]').setValue(true)
    expect(wrapper.find('[data-test="save-application"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await flushPromises()
    const createCall = stub.calls.find((call) => call.method === 'POST' && call.url.endsWith('/admin/applications'))
    expect(createCall?.body).toMatchObject({
      code: 'customer-service-prod', owner_id: 'user-admin', token_limit: 1_000_000,
      amount_limit: '1000', rpm: 60, tpm: 100_000, virtual_model_ids: ['alias-1'],
    })
    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe(`/ui/applications/${application.id}`)
    })
  })

  it('详情聚合接入信息、治理策略与应用范围调用入口', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) return dataEnvelope(application)
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) return dataEnvelope([])
      return undefined
    })
    const { wrapper } = await mountPage(`/ui/applications/${application.id}`)
    const text = wrapper.text()
    expect(text).toContain('OpenAI 兼容协议')
    expect(text).toContain('/v1/chat/completions')
    expect(text).toContain('chat-default')
    expect(text).toContain('额度与速率')
    expect(wrapper.find('a[href*="application=customer-service-prod"]').exists()).toBe(true)
  })

  it('在应用详情调整额度并替换模型授权', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) {
        return dataEnvelope(application)
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) {
        return dataEnvelope([])
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/model-aliases')) {
        return pageEnvelope([
          { id: 'alias-1', alias: 'chat-default', display_name: '默认对话', enabled: true },
          { id: 'alias-2', alias: 'chat-backup', display_name: '备用对话', enabled: true },
        ])
      }
      if (method === 'PUT' && url.pathname.endsWith(`/admin/applications/${application.id}/quota`)) {
        return dataEnvelope({
          id: application.id,
          version: 2,
          entity: { ...application, quota: { ...application.quota, token_limit: 2_000_000, version: 2 } },
          draft_changed: false,
          draft_revision: null,
          request_id: 'req-quota',
        })
      }
      if (method === 'PUT' && url.pathname.endsWith(`/admin/applications/${application.id}/models`)) {
        return dataEnvelope({
          id: application.id,
          version: 3,
          entity: { ...application, version: 3 },
          draft_changed: false,
          draft_revision: null,
          request_id: 'req-model',
        })
      }
      return undefined
    })
    const { wrapper } = await mountPage(`/ui/applications/${application.id}`)

    const quotaButton = wrapper.findAll('button').find((button) => button.text() === '调整')!
    await quotaButton.trigger('click')
    const quotaDialog = wrapper.find('[aria-labelledby="application-quota-title"]')
    await quotaDialog.find('input[type="number"]').setValue('2000000')
    await quotaDialog.find('textarea').setValue('扩大生产额度')
    await quotaDialog.findAll('button').find((button) => button.text() === '保存调整')!.trigger('click')
    await flushPromises()
    expect(stub.calls.find((call) => call.method === 'PUT' && call.url.endsWith('/quota'))?.body)
      .toMatchObject({ token_limit: 2_000_000, version: 1, reason: '扩大生产额度' })

    const modelButton = wrapper.findAll('button').find((button) => button.text() === '管理授权')!
    await modelButton.trigger('click')
    await flushPromises()
    const modelDialog = wrapper.find('[aria-labelledby="application-model-title"]')
    await modelDialog.find('input[type="checkbox"][value="alias-2"]').setValue(true)
    await modelDialog.find('textarea').setValue('增加备用模型')
    await modelDialog.findAll('button').find((button) => button.text() === '保存授权')!.trigger('click')
    await flushPromises()
    expect(stub.calls.find((call) => call.method === 'PUT' && call.url.endsWith('/models'))?.body)
      .toMatchObject({
        virtual_model_ids: ['alias-1', 'alias-2'],
        application_version: 2,
        reason: '增加备用模型',
      })
  })
})
