import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import { routes } from '@/app/router'
import { useBootstrapStore } from '@/stores/bootstrap'
import { bootstrapFixtures } from '../mocks/fixtures/bootstrap'
import {
  dataEnvelope,
  installJsonFetchStub,
  type FetchStub,
} from './helpers/fetchStub'

const runtimeConfigPayload = {
  version: 5,
  timezone: 'Asia/Shanghai',
  timezone_locked: true,
  trace_retention_days: 30,
  usage_retention_days: 365,
  audit_retention_days: 365,
  diagnostic_sample_retention_days: 7,
  dashboard_refresh_seconds: 30,
  max_message_chars: 100000,
  max_request_chars: 500000,
  diagnostic_sampling_enabled: false,
  diagnostic_sample_rate: '0',
  diagnostic_sample_max_chars: 1000,
  client_ip_recording_enabled: false,
  trusted_proxy_cidrs: ['10.0.0.0/8'],
  publish_instance_timeout_seconds: 60,
  instance_stale_seconds: 60,
  current_snapshot_no: 13,
  published_at: '2026-09-05T09:00:00Z',
  draft_changed: false,
  draft_revision: 37,
  last_modified_by_name: '系统管理员',
  updated_at: '2026-09-05T09:00:00Z',
}

const accessRow = {
  id: 'ac-1',
  name: '计费服务 Token',
  application: 'app-billing',
  masked_token: 'lai_abcd****wxyz',
  allowed_alias_count: 1,
  ip_rule_count: 0,
  status: 'ACTIVE',
  expires_at: new Date(Date.now() + 3 * 24 * 3600_000).toISOString(),
  rotation_generation: 1,
  last_used_at: '2026-09-05T08:00:00Z',
  last_used_ip: null,
  trace_count_24h: 42,
  updated_at: '2026-09-05T08:30:00Z',
  version: 2,
}

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

describe('RuntimeConfigPage（FE-043/044）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  function handler() {
    return ({ url, method, body }: { url: URL; method: string; body?: Record<string, unknown> }) => {
      const base = bootstrapHandler()({ url, method })
      if (base) return base
      if (method === 'GET' && url.pathname.endsWith('/admin/runtime-config')) {
        return dataEnvelope(runtimeConfigPayload)
      }
      if (method === 'POST' && url.pathname.endsWith('/admin/runtime-config/retention-impact')) {
        return dataEnvelope({
          impact_version: 'impact-1',
          estimated_at: '2026-09-05T10:00:00Z',
          expires_at: new Date(Date.now() + 600_000).toISOString(),
          target_values: {
            trace_retention_days: 10,
            usage_retention_days: 365,
            audit_retention_days: 365,
            diagnostic_sample_retention_days: 7,
          },
          counts: { trace: 128, usage: 96, audit: 2100, sample: 18 },
          earliest_remaining_at: '2026-08-26T00:00:00Z',
        })
      }
      if (method === 'PUT' && url.pathname.endsWith('/admin/runtime-config')) {
        expect(body?.version).toBe(5)
        return dataEnvelope({
          id: 'runtime-config',
          version: 6,
          entity: null,
          draft_changed: true,
          draft_revision: 38,
          request_id: 'r1',
        })
      }
      return undefined
    }
  }

  it('时区锁定只读，各区块字段展示', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/runtime-config', 'SYSTEM_ADMIN')
    const timezone = wrapper.find('input[value="Asia/Shanghai"]')
    expect(timezone.exists()).toBe(true)
    expect(timezone.attributes('readonly')).toBeDefined()
    const text = wrapper.text()
    expect(text).toContain('活动快照')
    expect(text).toContain('#13')
    expect(text).toContain('诊断采样')
    expect(text).toContain('发布协调')
  })

  it('关闭采样时采样率强制为 0，缩短保留期需先估算影响', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/runtime-config', 'SYSTEM_ADMIN')
    // 缩短 Trace 保留期触发影响确认
    await wrapper.find('#rt-trace').setValue('10')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('缩短保留期需先估算影响并确认')
    // 未估算时 PUT 不会发出
    expect(stub.calls.filter((call) => call.method === 'PUT')).toHaveLength(0)
    // 估算后展示删除数量
    const estimate = wrapper.findAll('button').find((button) => button.text() === '估算保留影响')
    await estimate!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('预计删除 Trace')
    expect(wrapper.text()).toContain('128')
  })

  it('估算后保存携带 confirmed_impact_version，成功显示待发布', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/runtime-config', 'SYSTEM_ADMIN')
    await wrapper.find('#rt-trace').setValue('10')
    await wrapper.findAll('button').find((button) => button.text() === '估算保留影响')!.trigger('click')
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await flushPromises()
    const putCall = stub.calls.find((call) => call.method === 'PUT')
    expect(putCall).toBeDefined()
    expect(putCall!.body.confirmed_impact_version).toBe('impact-1')
  })

  it('组合校验失败不提交（usage 小于 trace）', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/runtime-config', 'SYSTEM_ADMIN')
    // trace=100 在 1—365 内；usage=50 在 30—3650 内但小于 trace
    await wrapper.find('#rt-trace').setValue('100')
    await wrapper.find('#rt-usage').setValue('50')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('不得小于 Trace 保留期')
    expect(stub.calls.filter((call) => call.method === 'PUT')).toHaveLength(0)
  })
})

describe('AccessListPage（FE-045~047）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  function handler() {
    return ({ url, method, body }: { url: URL; method: string; body?: Record<string, unknown> }) => {
      const base = bootstrapHandler()({ url, method })
      if (base) return base
      if (method === 'GET' && url.pathname.endsWith('/admin/access-credentials')) {
        return {
          status: 200,
          body: {
            data: {
              items: [accessRow],
              total: 1,
              page: 1,
              page_size: 20,
              sort: '-updated_at',
              query_started_at: 'x',
              data_updated_at: 'x',
            },
          },
        }
      }
      if (method === 'GET' && url.pathname.includes('/admin/access-credentials/')) {
        return dataEnvelope({
          ...accessRow,
          allowed_alias_ids: ['alias-1'],
          ip_allowlist: [],
          created_at: '2026-09-04T10:00:00Z',
          disabled_at: null,
          recent_traces: [],
          audit_summary: [],
        })
      }
      if (method === 'POST' && url.pathname.endsWith('/admin/access-credentials')) {
        return dataEnvelope({
          credential: accessRow,
          token_value: 'lai_newTokenValue1234567890',
          issued_at: '2026-09-05T10:00:00Z',
          rotation_generation: 1,
        })
      }
      if (method === 'POST' && url.pathname.endsWith('/rotate')) {
        expect(body?.reason).toBeTruthy()
        return dataEnvelope({
          credential: accessRow,
          token_value: 'lai_rotatedValue0987654321',
          issued_at: '2026-09-05T10:10:00Z',
          rotation_generation: 2,
        })
      }
      if (method === 'POST' && url.pathname.endsWith('/disable')) {
        return dataEnvelope({
          id: 'ac-1',
          version: 3,
          entity: null,
          draft_changed: false,
          draft_revision: null,
          request_id: 'r1',
        })
      }
      return undefined
    }
  }

  it('Standalone 模式展示列表，Embedded 显示空态', async () => {
    stub = installJsonFetchStub(handler())
    const standalone = await mountPage('/ui/access-credentials', 'SYSTEM_ADMIN')
    expect(standalone.wrapper.text()).toContain('计费服务 Token')
    expect(standalone.wrapper.text()).toContain('lai_abcd****wxyz')
    expect(standalone.wrapper.text()).toContain('1')

    const embedded = await mountPage('/ui/access-credentials', 'SYSTEM_ADMIN', 'EMBEDDED')
    expect(embedded.wrapper.text()).toContain('当前运行模式不使用 Standalone Access Credential')
  })

  it('创建后 Token 弹窗展示，未勾选保存前不能关闭', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/access-credentials', 'SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '创建访问凭证')!.trigger('click')
    await wrapper.find('#ac-name').setValue('新服务 Token')
    await wrapper.find('#ac-app').setValue('app-new')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '创建并签发 Token')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('lai_newTokenValue1234567890')
    expect(wrapper.text()).toContain('该 Token 仅本次显示')
    const closeButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '已安全保存，关闭')!
    expect((closeButton.element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.find('.lai-token-saved input').setValue(true)
    const enabledClose = wrapper
      .findAll('button')
      .find((button) => button.text() === '已安全保存，关闭')!
    expect((enabledClose.element as HTMLButtonElement).disabled).toBe(false)
    await enabledClose.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('lai_newTokenValue1234567890')
  })

  it('轮换要求原因，成功后弹出新 Token', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/access-credentials', 'SYSTEM_ADMIN')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '轮换')!.trigger('click')
    const confirm = wrapper.findAll('button').filter((button) => button.text() === '确认').at(-1)!
    expect((confirm.element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.find('#lai-dialog-reason').setValue('泄露风险')
    const confirm2 = wrapper.findAll('button').filter((button) => button.text() === '确认').at(-1)!
    await confirm2.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('lai_rotatedValue0987654321')
  })

  it('停用需原因并携带 version', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/access-credentials', 'SYSTEM_ADMIN')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '停用')!.trigger('click')
    await wrapper.find('#lai-dialog-reason').setValue('密钥泄露')
    const confirm = wrapper.findAll('button').filter((button) => button.text() === '确认').at(-1)!
    await confirm.trigger('click')
    await flushPromises()
    await flushPromises()
    const disableCall = stub.calls.find((call) => call.url.endsWith('/disable'))
    expect(disableCall).toBeDefined()
    expect(disableCall!.body.version).toBe(2)
    expect(disableCall!.body.reason).toBe('密钥泄露')
  })
})

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
    const resultSelect = wrapper.findAll('select').find((select) => select.attributes('aria-label') === '结果')
    await resultSelect!.setValue('FAILED')
    await flushPromises()
    const listCall = stub.calls.filter((call) => call.url.includes('/admin/audit-logs?')).at(-1)!
    expect(listCall.url).toContain('result=FAILED')
  })
})
