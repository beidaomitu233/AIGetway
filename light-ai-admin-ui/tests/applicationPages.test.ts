import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { routes } from '@/app/router'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import { dataEnvelope, installJsonFetchStub, pageEnvelope, type FetchStub } from './helpers/fetchStub'

import { application } from './fixtures/application'

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

enableAutoUnmount(afterEach)

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
    for (const [name, value] of Object.entries({ token_limit: '1000000', amount_limit: '1000', currency: 'CNY', rpm: '60', tpm: '100000' })) await wrapper.get('input[name="' + name + '"]').setValue(value)
    expect(wrapper.find('[data-test="save-application"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('form').trigger('submit')
    expect(stub.calls.some(call => call.method === 'POST')).toBe(false)
    expect(wrapper.text()).toContain('确认应用信息')
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
    }, { timeout: 3_000 })
  })

  it('详情聚合接入信息、治理策略与应用范围调用入口', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) return dataEnvelope(application)
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) return dataEnvelope([])
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/quota/adjustments`)) return dataEnvelope([])
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

  it('可停用并重新启用应用密钥', async () => {
    let keyStatus: 'ACTIVE' | 'DISABLED' = 'ACTIVE'
    let keyVersion = 1
    const key = () => ({
      id: 'key-1', application_id: application.id, name: '生产接入',
      masked_value: 'lai_****abcd', ip_allowlist: [], expires_at: null,
      rpm: 30, tpm: 50_000, status: keyStatus,
      last_used_at: null, last_used_ip_masked: null,
      issued_at: '2026-09-08T08:00:00Z', rotated_at: null, revoked_at: null,
      rotation_generation: 1, version: keyVersion, virtual_model_ids: [],
    })
    stub = installJsonFetchStub(({ url, method, body }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) {
        return dataEnvelope(application)
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) {
        return dataEnvelope([key()])
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/quota/adjustments`)) {
        return dataEnvelope([])
      }
      if (method === 'POST' && url.pathname.endsWith(`/admin/applications/${application.id}/keys/key-1/status`)) {
        const payload = body as { status: 'ACTIVE' | 'DISABLED' }
        keyStatus = payload.status
        keyVersion += 1
        return dataEnvelope({
          id: 'key-1', version: keyVersion, entity: key(),
          draft_changed: false, draft_revision: null, request_id: 'req-key-status',
        })
      }
      return undefined
    })
    const { wrapper } = await mountPage(`/ui/applications/${application.id}`)

    await wrapper.find('[data-test="key-disable-key-1"]').trigger('click')
    let actionDialog = wrapper.find('[aria-labelledby="application-key-action-title"]')
    await actionDialog.find('textarea').setValue('排查异常调用')
    await actionDialog.trigger('submit')
    await flushPromises()

    expect(stub.calls.find((call) => call.method === 'POST' && call.url.endsWith('/keys/key-1/status'))?.body)
      .toEqual({ status: 'DISABLED', version: 1, reason: '排查异常调用' })
    expect(wrapper.find('[data-test="key-enable-key-1"]').exists()).toBe(true)

    await wrapper.find('[data-test="key-enable-key-1"]').trigger('click')
    actionDialog = wrapper.find('[aria-labelledby="application-key-action-title"]')
    await actionDialog.find('textarea').setValue('排查完成恢复')
    await actionDialog.trigger('submit')
    await flushPromises()

    const statusCalls = stub.calls.filter((call) => call.method === 'POST' && call.url.endsWith('/keys/key-1/status'))
    expect(statusCalls.at(-1)?.body)
      .toEqual({ status: 'ACTIVE', version: 2, reason: '排查完成恢复' })
    expect(wrapper.find('[data-test="key-disable-key-1"]').exists()).toBe(true)
  })

  it('签发密钥时可将模型权限收紧为应用授权子集', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) {
        return dataEnvelope(application)
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) {
        return dataEnvelope([])
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/quota/adjustments`)) {
        return dataEnvelope([])
      }
      if (method === 'POST' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) {
        return dataEnvelope({
          key_id: 'key-new', application_id: application.id, key_value: 'lai_test-once',
          masked_value: 'lai_****once', issued_at: '2026-09-09T02:00:00Z',
          rotation_generation: 1, version: 1,
        })
      }
      return undefined
    })
    const { wrapper } = await mountPage(`/ui/applications/${application.id}`)

    await wrapper.findAll('button').find((button) => button.text() === '签发密钥')!.trigger('click')
    const createForm = wrapper.findAll('form').find((form) => form.text().includes('签发应用密钥'))!
    await createForm.find('input.lai-input').setValue('仅客服模型')
    await createForm.find('input[type="checkbox"][value="alias-1"]').setValue(true)
    await createForm.trigger('submit')
    await flushPromises()

    expect(stub.calls.find((call) => call.method === 'POST' && call.url.endsWith('/keys'))?.body)
      .toMatchObject({ name: '仅客服模型', virtual_model_ids: ['alias-1'] })
    expect(wrapper.text()).toContain('这是唯一一次显示完整密钥')
  })

  it('在应用详情调整额度并替换模型授权', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) {
        return dataEnvelope(application)
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) {
        return dataEnvelope([])
      }
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/quota/adjustments`)) {
        return dataEnvelope([{
          id: 'adjustment-1', application_id: application.id, dimension: 'TOKEN_LIMIT',
          before_value: '500000', delta_value: '500000', after_value: '1000000',
          reason: '初始扩容', effective_at: '2026-09-08T07:00:00Z',
          operator_id: 'user-admin', created_at: '2026-09-08T07:00:00Z',
        }])
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
      if (method === 'POST' && url.pathname.endsWith(`/admin/applications/${application.id}/quota/adjustments`)) {
        return dataEnvelope({
          id: application.id,
          version: 3,
          entity: {
            ...application,
            quota: { ...application.quota, token_limit: 2_500_000, version: 3 },
          },
          draft_changed: false,
          draft_revision: null,
          request_id: 'req-adjustment',
        })
      }
      if (method === 'POST' && url.pathname.endsWith(`/admin/applications/${application.id}/quota/reset`)) {
        return dataEnvelope({
          id: application.id,
          version: 4,
          entity: {
            ...application,
            quota: { ...application.quota, token_limit: 2_500_000, tokens_used: 0, version: 4 },
          },
          draft_changed: false,
          draft_revision: null,
          request_id: 'req-reset',
        })
      }
      return undefined
    })
    const { wrapper } = await mountPage(`/ui/applications/${application.id}`)

    const quotaButton = wrapper.findAll('button').find((button) => button.text() === '编辑策略')!
    await quotaButton.trigger('click')
    const quotaDialog = wrapper.find('[aria-labelledby="application-quota-title"]')
    await quotaDialog.find('input[type="number"]').setValue('2000000')
    await quotaDialog.find('textarea').setValue('扩大生产额度')
    await quotaDialog.findAll('button').find((button) => button.text() === '保存调整')!.trigger('click')
    await flushPromises()
    expect(stub.calls.find((call) => call.method === 'PUT' && call.url.endsWith('/quota'))?.body)
      .toMatchObject({ token_limit: 2_000_000, version: 1, reason: '扩大生产额度' })

    const adjustmentButton = wrapper.findAll('button').find((button) => button.text() === '人工增减')!
    await adjustmentButton.trigger('click')
    const adjustmentDialog = wrapper.find('[aria-labelledby="application-adjustment-title"]')
    await adjustmentDialog.find('input').setValue('500000')
    await adjustmentDialog.find('textarea').setValue('活动期间临时扩容')
    await adjustmentDialog.findAll('button').find((button) => button.text() === '确认调整')!.trigger('click')
    await flushPromises()
    expect(stub.calls.find((call) => call.method === 'POST' && call.url.endsWith('/quota/adjustments'))?.body)
      .toMatchObject({
        dimension: 'TOKEN_LIMIT', delta: '500000', quota_version: 2,
        reason: '活动期间临时扩容',
      })

    expect(wrapper.text()).toContain('Token 额度调整')
    const resetButton = wrapper.findAll('button').find((button) => button.text() === '重置用量')!
    await resetButton.trigger('click')
    const resetDialog = wrapper.find('[aria-labelledby="application-reset-title"]')
    await resetDialog.find('textarea').setValue('新结算周期人工重置')
    await resetDialog.find('input').setValue('customer-service-prod')
    await resetDialog.findAll('button').find((button) => button.text() === '确认重置')!.trigger('click')
    await flushPromises()
    expect(stub.calls.find((call) => call.method === 'POST' && call.url.endsWith('/quota/reset'))?.body)
      .toMatchObject({
        dimension: 'TOKEN_USAGE', confirmation_code: 'customer-service-prod', quota_version: 3,
        reason: '新结算周期人工重置',
      })

    const modelButton = wrapper.findAll('button').find((button) => button.text() === '管理授权')!
    await modelButton.trigger('click')
    await flushPromises()
    const modelDialog = wrapper.find('[aria-labelledby="application-model-title"]')
    await modelDialog.find('input[type="checkbox"][value="alias-2"]').setValue(true)
    const aliasTwoGroup = modelDialog.findAll('.model-option-group')
      .find((group) => group.find('input[type="checkbox"][value="alias-2"]').exists())!
    await aliasTwoGroup.find('input[type="number"]').setValue('256')
    await aliasTwoGroup.find('select').setValue('deny')
    await modelDialog.find('textarea').setValue('增加备用模型')
    await modelDialog.findAll('button').find((button) => button.text() === '保存授权')!.trigger('click')
    await flushPromises()
    expect(stub.calls.find((call) => call.method === 'PUT' && call.url.endsWith('/models'))?.body)
      .toMatchObject({
        virtual_model_ids: ['alias-1', 'alias-2'],
        constraints: [{
          virtual_model_id: 'alias-2', max_output_tokens: 256, stream_allowed: false,
        }],
        application_version: 2,
        reason: '增加备用模型',
      })
  })

  it('详情页签切换写入 URL 并按需加载应用成员', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) return dataEnvelope(application)
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/keys`)) return dataEnvelope([])
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/quota/adjustments`)) return dataEnvelope([])
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}/members`)) {
        return dataEnvelope([
          { id: 'member-1', subject_id: 'user-admin', subject_name: '系统管理员', role: 'OWNER' },
        ])
      }
      return undefined
    })
    const { wrapper, router } = await mountPage(`/ui/applications/${application.id}`)

    expect(wrapper.findAll('.detail-tab').map((tab) => tab.text())).toEqual([
      '概览', '接入密钥', '可用模型', '额度与速率', '调用记录', '用量成本', '成员与审计',
    ])

    const membersTab = wrapper.findAll('.detail-tab').find((tab) => tab.text() === '成员与审计')!
    await membersTab.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.tab).toBe('members')
    expect(wrapper.text()).toContain('系统管理员')
    expect(wrapper.text()).toContain('负责人')
    expect(stub.calls.some((call) => call.method === 'GET' && call.url.endsWith('/members'))).toBe(true)
  })

  it('应用内开发接入页只列出该应用已授权的虚拟模型', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith(`/admin/applications/${application.id}`)) return dataEnvelope(application)
      if (method === 'GET' && url.pathname.endsWith('/admin/developer-access/context')) {
        return dataEnvelope({
          runtime_mode: 'STANDALONE_SERVER',
          api_base_url: 'https://gateway.example.com',
          authentication_type: 'BEARER_TOKEN',
          sdk_version: '0.1.0',
          server_version: '0.1.0',
          current_snapshot_no: 7,
          selected_alias_id: 'alias-1',
          available_models: [
            {
              alias_id: 'alias-1', alias: 'chat-default', display_name: '默认对话',
              support_stream: true, support_system_message: true,
              context_window: 8000, max_output_tokens: 1024,
            },
            {
              alias_id: 'alias-9', alias: 'not-granted', display_name: '未授权模型',
              support_stream: true, support_system_message: true,
              context_window: 8000, max_output_tokens: 1024,
            },
          ],
        })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/developer-access/code-sample')) {
        return dataEnvelope({
          language: 'curl', filename: null, content: 'curl https://gateway.example.com',
          alias_id: 'alias-1', mode: 'STANDALONE_CLIENT', sample_type: 'SYNC',
        })
      }
      return undefined
    })
    const { wrapper } = await mountPage(`/ui/applications/${application.id}/integration`)

    const modelSelect = wrapper.find('select[aria-label="选择应用已授权模型"]')
    expect(modelSelect.findAll('option').map((option) => option.text())).toEqual(['默认对话（chat-default）'])
    expect(wrapper.text()).toContain('https://gateway.example.com')
    expect(wrapper.text()).toContain('APPLICATION_MODEL_CONSTRAINT_VIOLATED')
    expect(wrapper.text()).not.toContain('未授权模型')
  })
})
