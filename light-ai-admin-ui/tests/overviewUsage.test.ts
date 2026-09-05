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
  type FetchStub,
} from './helpers/fetchStub'

async function mountPage(path: string, role: keyof typeof bootstrapFixtures) {
  setActivePinia(createPinia())
  const store = useBootstrapStore()
  store.$patch({
    status: 'ready',
    permissions: [...bootstrapFixtures[role].permissions],
    roles: [...bootstrapFixtures[role].roles],
    adapters: [...(bootstrapFixtures.SYSTEM_ADMIN.adapters ?? [])],
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
  return ({ url, method }: { url: URL; method: string; body?: Record<string, unknown> }) => {
    if (method === 'GET' && url.pathname.endsWith('/admin/bootstrap')) {
      return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
    }
    return undefined
  }
}

describe('OverviewPage（FE-031~033）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  function handler() {
    return ({ url, method }: { url: URL; method: string }) => {
      const base = bootstrapHandler()({ url, method, body: {} })
      if (base) return base
      if (method === 'GET' && url.pathname.endsWith('/admin/overview/filters')) {
        return dataEnvelope({
          applications: ['app-demo'],
          aliases: [{ id: 'alias-1', name: 'chat-default' }],
          providers: [{ id: 'prov-001', name: 'OpenAI 生产' }],
          currencies: ['USD', 'CNY'],
        })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/overview/summary')) {
        return dataEnvelope({
          request_count: 428,
          success_count: 401,
          failure_count: 19,
          stream_interrupted_count: 2,
          cancelled_count: 3,
          active_count: 3,
          success_rate: 0.95,
          average_total_ms: 1840,
          p95_first_token_ms: 720,
          total_tokens: 98450,
          actual_tokens: 80220,
          estimated_tokens: 18230,
          costs: [
            { currency: 'USD', amount: '1.84230000' },
            { currency: 'CNY', amount: '3.10000000' },
          ],
          retry_count: 12,
          credential_failover_count: 5,
          fallback_count: 7,
          open_circuit_count: 1,
          unavailable_candidate_count: 2,
          data_updated_at: '2026-09-05T10:00:00Z',
        })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/overview/trends')) {
        const start = url.searchParams.get('start_at') ?? '2026-09-05T00:00:00Z'
        const startMs = new Date(start).getTime()
        return dataEnvelope({
          points: [
            {
              bucket_start: start,
              bucket_end: new Date(startMs + 3600_000).toISOString(),
              request_count: 40,
              success_count: 38,
              failure_count: 2,
              success_rate: 0.95,
              average_total_ms: 1500,
              p95_first_token_ms: 600,
              actual_tokens: 9000,
              estimated_tokens: 800,
              total_tokens: 9800,
              costs: [{ currency: 'USD', amount: '0.08000000' }],
              retry_count: 0,
              fallback_count: 0,
            },
            {
              bucket_start: new Date(startMs + 3600_000).toISOString(),
              bucket_end: new Date(startMs + 2 * 3600_000).toISOString(),
              request_count: 0,
              success_count: 0,
              failure_count: 0,
              success_rate: null,
              average_total_ms: null,
              p95_first_token_ms: null,
              actual_tokens: 0,
              estimated_tokens: 0,
              total_tokens: 0,
              costs: [{ currency: 'USD', amount: '0.00000000' }],
              retry_count: 0,
              fallback_count: 0,
            },
          ],
          data_updated_at: '2026-09-05T10:00:00Z',
        })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/overview/exceptions')) {
        return dataEnvelope({
          summary: {
            open_circuit_count: 1,
            half_open_circuit_count: 1,
            unavailable_candidate_count: 2,
            invalid_credential_count: 1,
            recent_failure_trace_count: 21,
          },
          items: [
            {
              item_type: 'CIRCUIT',
              object_id: 'circuit-1',
              object_name: 'gpt-4o + sk-****a1b2',
              status: 'OPEN',
              error_code: 'PROVIDER_SERVER_ERROR',
              error_summary: '连续失败触发熔断',
              occurrence_count: 14,
              latest_at: '2026-09-05T09:40:00Z',
              provider_name: 'OpenAI 生产',
              model_name: 'gpt-4o',
              alias_name: 'chat-default',
            },
            {
              item_type: 'TRACE',
              object_id: 'trace-failed-003',
              object_name: 'trace-failed-003',
              status: 'FAILED',
              error_code: 'ALL_CANDIDATES_FAILED',
              error_summary: '所有候选尝试均失败',
              occurrence_count: 1,
              latest_at: '2026-09-05T09:55:00Z',
              provider_name: 'OpenAI 生产',
              model_name: 'gpt-4o',
              alias_name: 'chat-default',
            },
          ],
          data_updated_at: '2026-09-05T10:00:00Z',
        })
      }
      return undefined
    }
  }

  it('摘要展示成功率、分币种费用与状态钻取入口', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/overview', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('请求数')
    expect(text).toContain('428')
    expect(text).toContain('95.00%')
    expect(text).toContain('费用（USD）')
    expect(text).toContain('1.84230000')
    expect(text).toContain('费用（CNY）')
    // 不做跨币种总额：两个币种独立展示
    expect(text).not.toContain('4.94230000')
  })

  it('趋势渲染多桶序列，空桶成功率显示为空而非 0', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/overview', 'SYSTEM_ADMIN')
    expect(wrapper.find('.lai-chart').exists()).toBe(true)
    expect(wrapper.find('.lai-chart-bucket').exists()).toBe(true)
  })

  it('异常定位展示类型、状态、次数并可按类型过滤', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/overview', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('OPEN 熔断 1')
    expect(text).toContain('gpt-4o + sk-****a1b2')
    expect(text).toContain('PROVIDER_SERVER_ERROR')

    const chip = wrapper.findAll('button').find((button) => button.text() === 'OPEN 熔断 1')
    await chip!.trigger('click')
    const rows = wrapper.findAll('tbody tr')
    expect(rows.length).toBe(1)
    expect(rows[0]!.text()).toContain('熔断')
  })

  it('指标切换复用桶数据只重绘；503 保留已有数据', async () => {
    let fail = false
    stub = installJsonFetchStub((context) => {
      const { url, method } = context
      if (method === 'GET' && url.pathname.endsWith('/admin/overview/summary')) {
        if (fail) {
          return errorEnvelope(503, 'OBSERVATION_DATA_UNAVAILABLE', '观测数据暂不可读', { retryable: true })
        }
        return dataEnvelope({
          request_count: 428,
          success_count: 401,
          failure_count: 19,
          stream_interrupted_count: 2,
          cancelled_count: 3,
          active_count: 3,
          success_rate: 0.95,
          average_total_ms: 1840,
          p95_first_token_ms: 720,
          total_tokens: 98450,
          actual_tokens: 80220,
          estimated_tokens: 18230,
          costs: [{ currency: 'USD', amount: '1.84230000' }],
          retry_count: 12,
          credential_failover_count: 5,
          fallback_count: 7,
          open_circuit_count: 1,
          unavailable_candidate_count: 2,
          data_updated_at: '2026-09-05T10:00:00Z',
        })
      }
      const base = handler()(context)
      if (base) return base
      return undefined
    })
    const { wrapper } = await mountPage('/ui/overview', 'SYSTEM_ADMIN')
    expect(wrapper.text()).toContain('428')
    fail = true
    // 手动刷新失败保留上次数据
    const refresh = wrapper.findAll('button').find((button) => button.text() === '手动刷新')
    await refresh!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('观测数据暂不可读')
    expect(wrapper.text()).toContain('428')
  })
})

describe('UsagePage（FE-034~036）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  const summaryPayload = {
    query_fingerprint: 'fp-1',
    data_updated_at: '2026-09-05T10:00:00Z',
    request_count: 1284,
    success_count: 1202,
    failure_count: 64,
    cancelled_count: 12,
    queued_count: 6,
    stream_count: 300,
    stream_interrupted_count: 4,
    success_rate: 0.9462,
    attempt_count: 1302,
    initial_count: 1284,
    retry_count: 10,
    credential_failover_count: 4,
    fallback_count: 4,
    half_open_probe_count: 0,
    total_tokens: 512300,
    input_tokens: 400100,
    output_tokens: 112200,
    actual_tokens: 460100,
    estimated_tokens: 52200,
    actual_token_rate: 0.8981,
    costs: [
      { currency: 'USD', input_cost: '1.00025000', output_cost: '1.12200000', total_cost: '2.12225000' },
      { currency: 'CNY', input_cost: '2.00000000', output_cost: '2.50000000', total_cost: '4.50000000' },
    ],
  }

  const trendPayload = {
    query_fingerprint: 'fp-1',
    data_updated_at: '2026-09-05T10:00:00Z',
    points: [
      {
        bucket_start: '2026-08-30T00:00:00Z',
        bucket_end: '2026-08-31T00:00:00Z',
        request_count: 150,
        success_count: 145,
        failure_count: 5,
        success_rate: 0.966,
        attempt_count: 152,
        initial_count: 150,
        retry_count: 2,
        credential_failover_count: 0,
        fallback_count: 0,
        half_open_probe_count: 0,
        actual_tokens: 50000,
        estimated_tokens: 4000,
        total_tokens: 54000,
        costs: [{ currency: 'USD', input_cost: '0.4', output_cost: '0.5', total_cost: '0.9' }],
      },
    ],
  }

  const groupPayload = {
    query_fingerprint: 'fp-1',
    data_updated_at: '2026-09-05T10:00:00Z',
    total: 2,
    page: 1,
    page_size: 20,
    rows: [
      {
        dimension_type: 'ALIAS',
        dimension_id: 'alias-1',
        dimension_name: 'chat-default',
        currency: 'USD',
        request_count: 900,
        success_count: 880,
        failure_count: 20,
        success_rate: 0.977,
        attempt_count: 910,
        initial_count: 900,
        retry_count: 6,
        credential_failover_count: 2,
        fallback_count: 2,
        half_open_probe_count: 0,
        actual_tokens: 300000,
        estimated_tokens: 30000,
        total_tokens: 330000,
        input_cost: '0.66000000',
        output_cost: '0.94000000',
        total_cost: '1.60000000',
        request_share: 0.7,
        token_share: 0.64,
        cost_share: 0.75,
      },
      {
        dimension_type: 'ALIAS',
        dimension_id: 'alias-2',
        dimension_name: 'embed-docs',
        currency: 'CNY',
        request_count: 384,
        success_count: 322,
        failure_count: 44,
        success_rate: 0.838,
        attempt_count: 392,
        initial_count: 384,
        retry_count: 4,
        credential_failover_count: 2,
        fallback_count: 2,
        half_open_probe_count: 0,
        actual_tokens: 160100,
        estimated_tokens: 22200,
        total_tokens: 182300,
        input_cost: '2.00000000',
        output_cost: '2.50000000',
        total_cost: '4.50000000',
        request_share: 0.3,
        token_share: 0.36,
        cost_share: 0.25,
      },
    ],
  }

  function handler(overrides?: { fingerprint?: string; exportStatus?: number }) {
    return ({ url, method }: { url: URL; method: string }) => {
      const base = bootstrapHandler()({ url, method, body: {} })
      if (base) return base
      if (method === 'GET' && url.pathname.endsWith('/admin/usage/summary')) {
        return dataEnvelope({ ...summaryPayload, query_fingerprint: 'fp-1' })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/usage/trends')) {
        return dataEnvelope({ ...trendPayload, query_fingerprint: 'fp-1' })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/usage/groups')) {
        return dataEnvelope({
          ...groupPayload,
          // 仅 groups 指纹不同：模拟响应竞态下的不一致
          query_fingerprint: overrides?.fingerprint ?? 'fp-1',
        })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/usage/export')) {
        return errorEnvelope(422, 'EXPORT_TOO_LARGE', '当前筛选预计导出超过 100000 行，请缩小范围')
      }
      return undefined
    }
  }

  it('摘要、趋势、分组同 fingerprint 渲染，取最早更新时间', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/usage', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('1284')
    expect(text).toContain('2.12225000')
    expect(text).toContain('4.50000000')
    expect(text).toContain('chat-default')
    expect(text).toContain('embed-docs')
    expect(text).toContain('70.0%')
    // 三接口并发：三个 usage 调用
    expect(stub.calls.filter((call) => call.url.includes('/admin/usage/')).length).toBe(3)
  })

  it('fingerprint 不一致时整组丢弃并提示重新查询', async () => {
    stub = installJsonFetchStub(handler({ fingerprint: 'fp-other' }))
    const { wrapper } = await mountPage('/ui/usage', 'SYSTEM_ADMIN')
    await flushPromises()
    expect(wrapper.text()).toContain('查询一致性校验未通过')
    expect(wrapper.text()).not.toContain('1284')
  })

  it('分组维度按角色过滤：只读不可见凭证维度', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/usage', 'VIEWER')
    const groupSelect = wrapper.findAll('select').find((select) => select.attributes('aria-label') === '分组维度')
    const optionTexts = groupSelect!.findAll('option').map((option) => option.text())
    expect(optionTexts).not.toContain('凭证')
    expect(optionTexts).not.toContain('凭证池')
  })

  it('导出 422 错误可读展示', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/usage', 'SYSTEM_ADMIN')
    await flushPromises()
    const exportButton = wrapper.findAll('button').find((button) => button.text() === '导出 CSV')
    await exportButton!.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('EXPORT_TOO_LARGE')
  })

  it('无导出权限不显示导出按钮', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/usage', 'VIEWER')
    expect(wrapper.findAll('button').some((button) => button.text() === '导出 CSV')).toBe(false)
  })
})
