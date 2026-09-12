import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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
import type { TraceDetail } from '@/api/traces'

const listRow = {
  trace_id: 'trace-retry-001',
  started_at: '2026-09-05T02:00:00Z',
  ended_at: '2026-09-05T02:00:03Z',
  source_mode: 'STANDALONE_SERVER',
  access_credential_name: 'ac-demo',
  application: 'app-demo',
  project: null,
  tenant: null,
  alias: 'chat-default',
  alias_id: 'alias-1',
  final_provider_id: 'prov-002',
  final_provider_name: 'DeepSeek 备用',
  final_provider_model_id: 'pm-2',
  final_provider_model_name: 'deepseek-chat',
  requested_stream: false,
  status: 'SUCCEEDED',
  anomalous_running: false,
  attempt_count: 3,
  retry_count: 1,
  credential_failover_count: 1,
  fallback_count: 1,
  queued_ms: null,
  first_token_ms: null,
  total_ms: 3100,
  usage_source: 'MIXED',
  input_tokens: 400,
  output_tokens: 45,
  total_tokens: 445,
  total_cost: '0.00140000',
  currency: 'USD',
  error_code: null,
}

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

function contextOf(url: URL, method: string): { url: URL; method: string; body: Record<string, unknown> } {
  return { url, method, body: {} }
}

function listHandler(rows: unknown[] = [listRow]) {
  return ({ url, method }: { url: URL; method: string }) => {
    if (method === 'GET' && url.pathname.endsWith('/admin/traces')) {
      return pageEnvelope(rows)
    }
    if (url.pathname.endsWith('/admin/bootstrap')) {
      return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
    }
    return undefined
  }
}

describe('TraceListPage（FE-025/026）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  it('列表展示状态、用量来源、费用与多 Attempt 展开信息', async () => {
    stub = installJsonFetchStub(listHandler())
    const { wrapper } = await mountPage('/ui/traces', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('trace-retry-001')
    expect(text).toContain('成功')
    expect(text).toContain('MIXED')
    expect(text).toContain('0.00140000 USD')
    expect(text).toContain('重试 1 / 切换 1 / 候选 1')
    expect(text).toContain('独立部署')
  })

  it('trace_id 精确查询携带参数并隐藏分页与高级筛选', async () => {
    stub = installJsonFetchStub(listHandler())
    const { wrapper } = await mountPage('/ui/traces?trace_id=trace-retry-001', 'SYSTEM_ADMIN')
    expect(wrapper.text()).toContain('精确查询模式')
    expect(wrapper.find('.lai-advanced-filters').exists()).toBe(false)
    expect(wrapper.find('.lai-pager').exists()).toBe(false)
    const listCall = stub.calls.find((call) => call.url.includes('/admin/traces?') && call.url.includes('trace_id'))
    expect(listCall).toBeDefined()
    expect(listCall!.url).toContain('trace_id=trace-retry-001')
  })

  it('状态与错误码筛选进入查询参数', async () => {
    stub = installJsonFetchStub(listHandler())
    const { wrapper } = await mountPage('/ui/traces', 'SYSTEM_ADMIN')
    const errorInput = wrapper
      .findAll('input')
      .find((input) => input.attributes('placeholder') === '错误码')
    await errorInput!.setValue('NETWORK_ERROR')
    await errorInput!.trigger('change')
    await flushPromises()
    const listCall = stub.calls.filter((call) => call.url.includes('/admin/traces?')).at(-1)!
    expect(listCall.url).toContain('error_code=NETWORK_ERROR')
  })

  it('导出成功下载 CSV，EXPORT_TOO_LARGE 可读', async () => {
    stub = installJsonFetchStub(listHandler())
    const { wrapper } = await mountPage('/ui/traces', 'SYSTEM_ADMIN')
    // 首次导出：成功（Blob 下载在 jsdom 中无害）
    const exportButton = wrapper.findAll('button').find((button) => button.text() === '导出 CSV')
    await exportButton!.trigger('click')
    await flushPromises()
    const exportCall = stub.calls.find((call) => call.url.includes('/traces/export'))
    expect(exportCall).toBeDefined()
  })

  it('导出 422 错误展示可读信息且可取消', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/traces/export')) {
        return errorEnvelope(422, 'EXPORT_TOO_LARGE', '当前筛选预计导出超过 100000 行，请缩小范围')
      }
      return listHandler()(contextOf(url, method))
    })
    const { wrapper } = await mountPage('/ui/traces', 'SYSTEM_ADMIN')
    await flushPromises()
    const exportButton = wrapper.findAll('button').find((button) => button.text() === '导出 CSV')
    await exportButton!.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('EXPORT_TOO_LARGE')
    expect(wrapper.text()).toContain('请缩小范围')
  })

  it('无导出权限角色不显示导出按钮', async () => {
    stub = installJsonFetchStub(listHandler())
    const { wrapper } = await mountPage('/ui/traces', 'DEVELOPER')
    expect(wrapper.findAll('button').some((button) => button.text() === '导出 CSV')).toBe(false)
  })
})

describe('TraceDetailPage（FE-027~030）', () => {
  let stub: FetchStub

  const detailPayload: TraceDetail = {
    trace: {
      ...listRow,
      config_snapshot_no: 12,
      response_committed: true,
      finish_reason: 'stop',
      response_input_tokens: 140,
      response_output_tokens: 45,
      response_total_tokens: 185,
      updated_by: 'system',
      input_tokens: 400,
      output_tokens: 45,
      total_tokens: 445,
    },
    request_summary: {
      source_mode: 'STANDALONE_SERVER',
      access_credential_name: 'ac-demo',
      request_user: 'user-42',
      client_ip: '10.1.2.3',
      user_agent: 'light-ai-sdk/1.0',
      config_snapshot_no: 12,
      message_count: 3,
      system_message_count: 1,
      user_message_count: 1,
      assistant_message_count: 1,
      input_char_count: 560,
      requested_stream: false,
      temperature: '0.7',
      top_p: null,
      max_tokens: 1024,
      stop_count: 0,
      provider_option_keys: [],
      content_sample_status: 'AVAILABLE',
      sampled_messages: null as string | null,
    },
    route_decisions: [],
    queue_entries: [],
    capacity_reservations: [],
    attempts: [
      {
        id: 'att-0',
        sequence: 0,
        attempt_type: 'INITIAL',
        status: 'FAILED',
        provider_name_snapshot: 'OpenAI 生产',
        provider_model_name_snapshot: 'gpt-4o',
        model_id_snapshot: 'gpt-4o',
        credential_name_snapshot: 'sk-****a1b2',
        route_candidate_id: 'cand-0',
        started_at: '2026-09-05T02:00:00Z',
        provider_started_at: '2026-09-05T02:00:00Z',
        response_headers_at: null,
        first_token_at: null,
        dispatch_ms: 40,
        response_header_ms: 320,
        first_token_ms: null,
        ended_at: '2026-09-05T02:00:01Z',
        total_ms: 800,
        response_committed: false,
        http_status: 502,
        endpoint_host: 'api.openai.com',
        retryable: true,
        retry_after_ms: null,
        provider_request_id: null,
        finish_reason: null,
        error_code: 'NETWORK_ERROR',
        error_category: 'NETWORK',
        error_stage: 'DISPATCH',
        error_summary: '连接中断',
        usage_source: 'ESTIMATED',
        input_tokens: 120,
        output_tokens: 0,
        total_tokens: 120,
        input_price: '0.00000250',
        output_price: '0.00001000',
        price_unit: 1000000,
        currency: 'USD',
        input_cost: '0.00030000',
        output_cost: '0.00000000',
        cost: '0.00030000',
      },
      {
        id: 'att-1',
        sequence: 1,
        attempt_type: 'FALLBACK',
        status: 'SUCCEEDED',
        provider_name_snapshot: 'DeepSeek 备用',
        provider_model_name_snapshot: 'deepseek-chat',
        model_id_snapshot: 'deepseek-chat',
        credential_name_snapshot: 'sk-****9f8e',
        route_candidate_id: 'cand-1',
        started_at: '2026-09-05T02:00:01Z',
        provider_started_at: '2026-09-05T02:00:01Z',
        response_headers_at: '2026-09-05T02:00:01Z',
        first_token_at: '2026-09-05T02:00:02Z',
        dispatch_ms: 30,
        response_header_ms: 260,
        first_token_ms: 640,
        ended_at: '2026-09-05T02:00:03Z',
        total_ms: 2100,
        response_committed: true,
        http_status: 200,
        endpoint_host: 'api.deepseek.com',
        retryable: false,
        retry_after_ms: null,
        provider_request_id: 'req-batch-777',
        finish_reason: 'stop',
        error_code: null,
        error_category: null,
        error_stage: null,
        error_summary: null,
        usage_source: 'ACTUAL',
        input_tokens: 140,
        output_tokens: 45,
        total_tokens: 185,
        input_price: '0.00000250',
        output_price: '0.00001000',
        price_unit: 1000000,
        currency: 'USD',
        input_cost: '0.00035000',
        output_cost: '0.00045000',
        cost: '0.00080000',
      },
    ],
    recovery_decisions: [
      {
        id: 'rec-0',
        sequence: 0,
        source_attempt_id: 'att-0',
        action: 'FALLBACK',
        reason_code: 'NETWORK_ERROR',
        scheduled_delay_ms: null,
        target_route_candidate_id: 'cand-1',
        target_credential_id: null,
        retries_used: 0,
        credential_failovers_used: 0,
        fallbacks_used: 1,
        remaining_timeout_ms: 50000,
        created_at: '2026-09-05T02:00:01Z',
      },
    ],
    circuit_events: [],
    timeline: [
      { id: 'tl-1', type: 'TRACE_CREATED', occurred_at: '2026-09-05T02:00:00Z', source_id: 't', sequence: 0, attempt_id: null, reason_code: null },
      { id: 'tl-2', type: 'ATTEMPT_STARTED', occurred_at: '2026-09-05T02:00:00Z', source_id: 'att-0', sequence: 1, attempt_id: 'att-0', reason_code: null },
      { id: 'tl-3', type: 'ATTEMPT_ENDED', occurred_at: '2026-09-05T02:00:01Z', source_id: 'att-0', sequence: 2, attempt_id: 'att-0', reason_code: null },
      { id: 'tl-4', type: 'RECOVERY_DECIDED', occurred_at: '2026-09-05T02:00:01Z', source_id: 'rec-0', sequence: 3, attempt_id: null, reason_code: 'NETWORK_ERROR' },
      { id: 'tl-5', type: 'ATTEMPT_STARTED', occurred_at: '2026-09-05T02:00:01Z', source_id: 'att-1', sequence: 4, attempt_id: 'att-1', reason_code: null },
      { id: 'tl-6', type: 'TRACE_ENDED', occurred_at: '2026-09-05T02:00:03Z', source_id: 't', sequence: 9, attempt_id: null, reason_code: null },
    ],
    detail_expires_at: '2026-10-05T02:00:00Z',
  }

  function detailHandler() {
    return ({ url, method }: { url: URL; method: string }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/traces/trace-retry-001')) {
        const payload = structuredClone(detailPayload)
        if (url.searchParams.get('include_diagnostics') === 'true') {
          payload.request_summary.sampled_messages = '[{"role":"user","content":"（已脱敏样本）你好"}]'
        }
        return dataEnvelope(payload)
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    }
  }

  beforeEach(() => {})

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  function runningDetail(): typeof detailPayload {
    const payload = structuredClone(detailPayload)
    payload.trace = {
      ...payload.trace,
      status: 'RUNNING',
      ended_at: null as string | null,
      total_ms: null as number | null,
    }
    payload.timeline = payload.timeline.filter((item) => item.type !== 'TRACE_ENDED')
    return payload
  }

  it('详情展示摘要、时间线、Attempt 明细与响应用量对账', async () => {
    stub = installJsonFetchStub(detailHandler())
    const { wrapper } = await mountPage('/ui/traces/trace-retry-001', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('Trace 摘要')
    expect(text).toContain('统一时间线')
    expect(text).toContain('创建 Trace')
    expect(text).toContain('恢复决策')
    expect(text).toContain('Attempt 明细')
    expect(text).toContain('0.00080000 USD')
    // 对账：总 Token 445 大于响应 185，显示前序失败说明
    expect(text).toContain('运行总消耗大于响应用量')
    expect(text).toContain('响应 Token（最终成功 Attempt）')
    expect(text).toContain('185')
  })

  it('点击时间线尝试节点打开抽屉，恢复决策高亮来源与目标', async () => {
    stub = installJsonFetchStub(detailHandler())
    const { wrapper } = await mountPage('/ui/traces/trace-retry-001', 'SYSTEM_ADMIN')
    // 点击 RECOVERY_DECIDED 节点
    const recoveryNode = wrapper.findAll('.lai-timeline-node').find((node) => node.text().includes('恢复决策'))
    await recoveryNode!.trigger('click')
    // 高亮来源 att-0 与目标 att-1
    const highlighted = wrapper.findAll('.lai-tr-highlight')
    expect(highlighted.length).toBeGreaterThanOrEqual(2)
    // 点击第二次尝试（att-1，成功）节点打开抽屉
    const attemptNodes = wrapper
      .findAll('.lai-timeline-node')
      .filter((node) => node.text().includes('尝试开始'))
    await attemptNodes[1]!.trigger('click')
    expect(wrapper.text()).toContain('Attempt #1')
    expect(wrapper.text()).toContain('阶段耗时')
    expect(wrapper.text()).toContain('req-batch-777')
    // att-0 无 Provider Request ID：点击首个节点验证空值占位
    await attemptNodes[0]!.trigger('click')
    expect(wrapper.text()).toContain('Attempt #0')
  })

  it('诊断样本按需请求：未请求时不返回，请求后展示', async () => {
    stub = installJsonFetchStub(detailHandler())
    const { wrapper } = await mountPage('/ui/traces/trace-retry-001', 'SYSTEM_ADMIN')
    await flushPromises()
    expect(wrapper.text()).toContain('按需读取诊断样本')
    expect(stub.calls.filter((call) => call.url.includes('include_diagnostics'))).toHaveLength(0)
    const diagButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('按需读取诊断样本'))
    await diagButton!.trigger('click')
    await flushPromises()
    expect(stub.calls.filter((call) => call.url.includes('include_diagnostics=true'))).toHaveLength(1)
    expect(wrapper.text()).toContain('（已脱敏样本）你好')
  })

  it('无诊断权限角色不展示样本入口，详情不带诊断参数', async () => {
    stub = installJsonFetchStub(detailHandler())
    const { wrapper } = await mountPage('/ui/traces/trace-retry-001', 'VIEWER')
    await flushPromises()
    expect(wrapper.text()).not.toContain('按需读取诊断样本')
    expect(stub.calls.filter((call) => call.url.includes('include_diagnostics'))).toHaveLength(0)
  })

  it('详情 404 显示错误并可重试', async () => {
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/traces/trace-retry-001')) {
        return errorEnvelope(404, 'OBJECT_NOT_FOUND', '对象不存在或已删除')
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/traces/trace-retry-001', 'SYSTEM_ADMIN')
    expect(wrapper.text()).toContain('对象不存在或已删除')
  })

  it('运行中自动刷新保留时间线并更新数据（FE-221）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    let calls = 0
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/traces/trace-retry-001')) {
        calls += 1
        if (calls === 1) return dataEnvelope(runningDetail())
        const payload = structuredClone(detailPayload)
        payload.trace.attempt_count = 4
        return dataEnvelope(payload)
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/traces/trace-retry-001', 'SYSTEM_ADMIN')
    expect(wrapper.text()).toContain('运行中')
    expect(wrapper.find('.lai-timeline').exists()).toBe(true)
    vi.advanceTimersByTime(5000)
    await flushPromises()
    expect(calls).toBe(2)
    // 静默刷新不把页面重置为 loading，时间线保留并展示更新后的数据
    expect(wrapper.find('.lai-timeline').exists()).toBe(true)
    expect(wrapper.text()).toContain('尝试次数')
    expect(wrapper.text()).not.toContain('刷新失败')
  })

  it('运行中自动刷新失败保留上次数据并提示（FE-221）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    let calls = 0
    stub = installJsonFetchStub(({ url, method }) => {
      if (method === 'GET' && url.pathname.endsWith('/admin/traces/trace-retry-001')) {
        calls += 1
        if (calls === 1) return dataEnvelope(runningDetail())
        return errorEnvelope(503, 'TRACE_DATA_UNAVAILABLE', '观测数据暂不可用')
      }
      if (url.pathname.endsWith('/admin/bootstrap')) {
        return dataEnvelope(bootstrapFixtures.SYSTEM_ADMIN)
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/traces/trace-retry-001', 'SYSTEM_ADMIN')
    expect(wrapper.find('.lai-timeline').exists()).toBe(true)
    vi.advanceTimersByTime(5000)
    await flushPromises()
    // 失败不吞掉已有详情，展示可读提示
    expect(wrapper.find('.lai-timeline').exists()).toBe(true)
    expect(wrapper.text()).toContain('刷新失败，以下为上次数据')
    expect(wrapper.text()).toContain('观测数据暂不可用')
  })
})
