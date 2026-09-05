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

const draftState = {
  base_snapshot_no: 12,
  draft_revision: 36,
  change_count: 3,
  status: 'EDITABLE',
  first_modified_at: '2026-09-05T01:00:00Z',
  last_modified_at: '2026-09-05T03:00:00Z',
}

const draftSummary = {
  total_count: 3,
  create_count: 1,
  update_count: 1,
  enable_count: 0,
  disable_count: 1,
  delete_count: 0,
  by_entity_type: { provider: 2, model_alias: 1 },
}

const draftChanges = [
  {
    id: 'dc-1',
    entity_type: 'provider',
    entity_id: 'prov-102',
    entity_name: 'Gemini 测试',
    change_type: 'CREATE',
    changed_fields: [
      { field: 'name', before_value: null, after_value: 'Gemini 测试', sensitive: false },
    ],
    dependency_summary: [],
    revertable: true,
    revert_blockers: [],
    modified_by: 'u1',
    modified_by_name: '系统管理员',
    modified_at: '2026-09-05T01:30:00Z',
    entity_version: 1,
  },
  {
    id: 'dc-2',
    entity_type: 'provider',
    entity_id: 'prov-001',
    entity_name: 'OpenAI 生产',
    change_type: 'UPDATE',
    changed_fields: [
      { field: 'read_timeout_ms', before_value: '120000', after_value: '90000', sensitive: false },
      { field: 'api_key', before_value: null, after_value: null, sensitive: true },
    ],
    dependency_summary: [],
    revertable: true,
    revert_blockers: [],
    modified_by: 'u2',
    modified_by_name: '运维人员',
    modified_at: '2026-09-05T02:30:00Z',
    entity_version: 4,
  },
  {
    id: 'dc-3',
    entity_type: 'model_alias',
    entity_id: 'alias-1',
    entity_name: 'chat-default',
    change_type: 'DISABLE',
    changed_fields: [],
    dependency_summary: [{ entity_type: 'route_candidate', entity_id: 'cand-5', entity_name: '候选 5' }],
    revertable: false,
    revert_blockers: ['route_candidate:cand-5'],
    modified_by: 'u1',
    modified_by_name: '系统管理员',
    modified_at: '2026-09-05T03:00:00Z',
    entity_version: 2,
  },
]

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

describe('DraftsPage（FE-037/038）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  function handler(overrides?: { status?: string }) {
    return ({ url, method }: { url: URL; method: string; body?: Record<string, unknown> }) => {
      const base = bootstrapHandler()({ url, method })
      if (base) return base
      if (method === 'GET' && url.pathname.endsWith('/admin/config/draft-state')) {
        return dataEnvelope({ ...draftState, status: overrides?.status ?? 'EDITABLE' })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/config/draft-changes/summary')) {
        return dataEnvelope(draftSummary)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/config/draft-changes')) {
        return pageOf(draftChanges)
      }
      return undefined
    }
  }


  function pageOf(items: unknown[]) {
    return {
      status: 200,
      body: {
        data: {
          items,
          total: items.length,
          page: 1,
          page_size: 20,
          sort: 'modified_at',
          query_started_at: '2026-09-05T10:00:00Z',
          data_updated_at: '2026-09-05T10:00:01Z',
        },
      },
    }
  }

  it('展示草稿状态、摘要数量与按实体分组列表', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/config/drafts', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('基线快照')
    expect(text).toContain('#12')
    expect(text).toContain('草稿修订号')
    expect(text).toContain('全部 3')
    expect(text).toContain('新增 1')
    // 同对象一行的分组标题
    expect(text).toContain('Provider（2）')
    expect(text).toContain('模型别名（1）')
    expect(text).toContain('Gemini 测试')
    // 展开停用项后可见关联对象
    const diffButtons = wrapper.findAll('button').filter((button) => button.text() === '查看差异')
    await diffButtons[2]!.trigger('click')
    expect(wrapper.text()).toContain('候选 5')
  })

  it('敏感字段只显示占位，不展示前后值', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/config/drafts', 'SYSTEM_ADMIN')
    // 展开第二项（UPDATE，含敏感字段）
    const allDiffButtons = wrapper.findAll('button').filter((button) => button.text() === '查看差异')
    await allDiffButtons[1]!.trigger('click')
    const text = wrapper.text()
    expect(text).toContain('敏感字段已变更')
    expect(text).not.toContain('api_key')
    expect(text).toContain('read_timeout_ms')
  })

  it('revertable=false 展示阻塞原因且撤销按钮禁用', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/config/drafts', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('不可撤销：route_candidate:cand-5')
    const item = wrapper.findAll('.lai-draft-item').at(-1)!
    const revertButton = item.findAll('button').find((button) => button.text() === '撤销')
    expect(revertButton!.attributes('disabled')).toBeDefined()
  })

  it('单项撤销携带 version 与 draft_revision，成功后刷新', async () => {
    stub = installJsonFetchStub((context) => {
      const base = handler()(context)
      if (base) return base
      const { url, method } = context
      if (method === 'POST' && url.pathname.includes('/revert')) {
        expect(context.body.version).toBe(1)
        expect(context.body.draft_revision).toBe(36)
        expect(context.body.reason).toBe('误操作')
        return dataEnvelope({ ...draftState, draft_revision: 37 })
      }
      return undefined
    })
    const { wrapper } = await mountPage('/ui/config/drafts', 'SYSTEM_ADMIN')
    const firstItem = wrapper.findAll('.lai-draft-item')[0]!
    await firstItem.findAll('button').find((button) => button.text() === '撤销')!.trigger('click')
    expect(wrapper.find('.lai-dialog').exists()).toBe(true)
    await wrapper.find('#lai-dialog-reason').setValue('误操作')
    const confirmButton = wrapper.findAll('button').find((button) => button.text() === '确认')
    await confirmButton!.trigger('click')
    await flushPromises()
    await flushPromises()
    const revertCall = stub.calls.find((call) => call.url.includes('/revert'))
    expect(revertCall).toBeDefined()
  })

  it('全部撤销要求固定确认文本 REVERT ALL', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/config/drafts', 'SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '全部撤销')!.trigger('click')
    const buttons = wrapper.findAll('button')
    const confirmButton = buttons.filter((button) => button.text() === '确认').at(-1)!
    expect((confirmButton.element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.find('#lai-dialog-confirm-text').setValue('REVERT ALL')
    await wrapper.find('#lai-dialog-reason').setValue('整批重来')
    const confirmButton2 = wrapper.findAll('button').filter((button) => button.text() === '确认').at(-1)!
    expect((confirmButton2.element as HTMLButtonElement).disabled).toBe(false)
  })

  it('PUBLISHING 状态隐藏撤销与发布入口', async () => {
    stub = installJsonFetchStub(handler({ status: 'PUBLISHING' }))
    const { wrapper } = await mountPage('/ui/config/drafts', 'SYSTEM_ADMIN')
    const text = wrapper.text()
    expect(text).toContain('发布进行中')
    expect(wrapper.findAll('button').some((button) => button.text() === '全部撤销')).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === '校验并发布')).toBe(false)
  })

  it('只读角色不显示撤销与发布操作', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/config/drafts', 'VIEWER')
    expect(wrapper.findAll('button').some((button) => button.text() === '全部撤销')).toBe(false)
    expect(wrapper.findAll('button').filter((button) => button.text() === '撤销').length).toBe(0)
  })
})

describe('PublishPage（FE-039~041）', () => {
  let stub: FetchStub

  afterEach(() => {
    stub?.restore()
    vi.restoreAllMocks()
  })

  const validationOk = {
    validation_id: 'val-1',
    status: 'PASSED',
    base_snapshot_no: 12,
    target_snapshot_no: 13,
    draft_revision: 36,
    content_checksum: 'a3f1',
    validated_at: '2026-09-05T10:00:00Z',
    expires_at: new Date(Date.now() + 600_000).toISOString(),
    change_summary: 'change',
    affected_alias_ids: ['alias-1'],
    issues: [
      {
        code: 'CONNECTION_CHECK_STALE',
        severity: 'WARNING',
        entity_type: 'credential',
        entity_id: 'cred-3',
        entity_name: '备份密钥',
        field_path: null as string | null,
        message: '最近 24 小时无成功检测记录',
        suggestion: '发布前检测',
        related_entity_ids: [],
      },
    ],
  }

  function handler(overrides?: { validation?: 'ok' | 'failed' | 'expired' }) {
    const validation = { ...validationOk }
    if (overrides?.validation === 'failed') {
      validation.status = 'FAILED'
      validation.issues = [
        {
          code: 'ALIAS_NO_AVAILABLE_CANDIDATE',
          severity: 'ERROR',
          entity_type: 'model_alias',
          entity_id: 'alias-1',
          entity_name: 'chat-default',
          field_path: 'candidates',
          message: '启用的 Alias 没有可用候选',
          suggestion: '配置候选',
          related_entity_ids: [],
        },
      ]
    }
    if (overrides?.validation === 'expired') {
      validation.expires_at = new Date(Date.now() - 60_000).toISOString() as unknown as string
    }
    return ({ url, method, body }: { url: URL; method: string; body?: Record<string, unknown> }) => {
      const base = bootstrapHandler()({ url, method })
      if (base) return base
      if (method === 'GET' && url.pathname.endsWith('/admin/config/draft-state')) {
        return dataEnvelope(draftState)
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/config/draft-changes/summary')) {
        return dataEnvelope(draftSummary)
      }
      if (method === 'POST' && url.pathname.endsWith('/admin/config/validate')) {
        return dataEnvelope(validation)
      }
      if (method === 'POST' && url.pathname.endsWith('/admin/config/publish')) {
        expect(body?.acknowledged_warning_ids).toEqual(['CONNECTION_CHECK_STALE'])
        expect(body?.validation_id).toBe('val-1')
        return dataEnvelope({
          id: 'pub-1',
          snapshot_no: 13,
          from_snapshot_no: 12,
          target_snapshot_no: 13,
          status: 'SUCCEEDED',
          published_by_name: '系统管理员',
          publish_note: '首次发布',
          published_at: '2026-09-05T10:05:00Z',
          completed_at: '2026-09-05T10:05:05Z',
          duration_ms: 5000,
          draft_revision: 36,
          content_checksum: 'a3f1',
          change_summary: 'change',
          affected_alias_ids: ['alias-1'],
          acknowledged_warning_ids: ['CONNECTION_CHECK_STALE'],
          instance_results: [
            {
              instance_id: 'instance-a',
              runtime_mode: 'STANDALONE_SERVER',
              runtime_version: '1.0.0',
              supported_schema_versions: ['1'],
              loaded_adapter_types: ['OPENAI'],
              from_snapshot_no: 12,
              target_snapshot_no: 13,
              status: 'LOADED',
              retry_count: 0,
              load_duration_ms: 800,
              error_code: null,
              error_summary: null,
              updated_at: '2026-09-05T10:05:04Z',
            },
          ],
          first_round_completed_at: '2026-09-05T10:05:05Z',
          converged_at: '2026-09-05T10:05:05Z',
        })
      }
      if (method === 'GET' && url.pathname.endsWith('/admin/config/publish-records')) {
        return pageOf([])
      }
      return undefined
    }
  }


  function pageOf(items: unknown[]) {
    return {
      status: 200,
      body: {
        data: {
          items,
          total: items.length,
          page: 1,
          page_size: 10,
          sort: '-published_at',
          query_started_at: '2026-09-05T10:00:00Z',
          data_updated_at: '2026-09-05T10:00:01Z',
        },
      },
    }
  }

  it('ERROR 校验问题阻止进入确认步骤', async () => {
    stub = installJsonFetchStub(handler({ validation: 'failed' }))
    const { wrapper } = await mountPage('/ui/config/publish', 'SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '开始校验')!.trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('ALIAS_NO_AVAILABLE_CANDIDATE')
    expect(text).toContain('ERROR')
    expect(wrapper.findAll('button').some((button) => button.text() === '提交发布')).toBe(false)
  })

  it('完整流程：校验通过 → 勾选警告 → 提交发布 → 进度展示', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/config/publish', 'SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '开始校验')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第 2 步')
    // 未勾选警告时提交禁用
    const submit = wrapper.findAll('button').find((button) => button.text() === '提交发布')!
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
    // 勾选警告
    await wrapper.find('input[type="checkbox"]').setValue(true)
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
    // 填写发布说明后可提交
    await wrapper.find('#publish-note').setValue('首次发布')
    expect((submit.element as HTMLButtonElement).disabled).toBe(false)
    await submit.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('第 3 步')
    expect(wrapper.text()).toContain('instance-a')
    expect(wrapper.text()).toContain('已加载')
  })

  it('校验过期提示重新校验', async () => {
    stub = installJsonFetchStub(handler({ validation: 'expired' }))
    const { wrapper } = await mountPage('/ui/config/publish', 'SYSTEM_ADMIN')
    await wrapper.findAll('button').find((button) => button.text() === '开始校验')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('校验已过期')
  })

  it('只读角色不显示校验按钮', async () => {
    stub = installJsonFetchStub(handler())
    const { wrapper } = await mountPage('/ui/config/publish', 'VIEWER')
    expect(wrapper.findAll('button').some((button) => button.text() === '开始校验')).toBe(false)
    expect(wrapper.text()).toContain('发布历史')
  })
})
