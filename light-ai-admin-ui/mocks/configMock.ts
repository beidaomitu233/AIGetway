import type { ServerResponse } from 'node:http'
import type { Connect } from 'vite'

/**
 * FE-P07 契约夹具：草稿状态/变更/撤销/校验/发布 Mock（附录 4.5.6.1）。
 * 敏感字段只返回 sensitive=true 占位；PUBLISHING 状态拒绝写命令。
 */

let draftRevision = 36
let draftStatus: 'EDITABLE' | 'PUBLISHING' = 'EDITABLE'
let seq = 10

const draftState = () => ({
  base_snapshot_no: 12,
  draft_revision: draftRevision,
  change_count: 3,
  status: draftStatus,
  first_modified_at: '2026-09-05T01:00:00Z',
  last_modified_at: '2026-09-05T03:00:00Z',
})

const draftChanges = [
  {
    id: 'dc-1',
    entity_type: 'provider',
    entity_id: 'prov-102',
    entity_name: 'Gemini 测试',
    change_type: 'CREATE',
    changed_fields: [
      { field: 'name', before_value: null, after_value: 'Gemini 测试', sensitive: false },
      { field: 'base_url', before_value: null, after_value: 'https://generativelanguage.googleapis.com/v1beta/', sensitive: false },
    ],
    dependency_summary: [],
    revertable: true,
    revert_blockers: [],
    modified_by: 'user-admin',
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
    dependency_summary: [{ entity_type: 'credential_pool', entity_id: 'pool-001', entity_name: 'OpenAI 主池' }],
    revertable: true,
    revert_blockers: [],
    modified_by: 'user-ops',
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
    changed_fields: [{ field: 'enabled', before_value: 'true', after_value: 'false', sensitive: false }],
    dependency_summary: [{ entity_type: 'route_candidate', entity_id: 'cand-5', entity_name: '候选 5' }],
    revertable: false,
    revert_blockers: ['route_candidate:cand-5'],
    modified_by: 'user-admin',
    modified_by_name: '系统管理员',
    modified_at: '2026-09-05T03:00:00Z',
    entity_version: 2,
  },
]

const validationOk = () => ({
  validation_id: `val-${seq}`,
  status: 'PASSED',
  base_snapshot_no: 12,
  target_snapshot_no: 13,
  draft_revision: draftRevision,
  content_checksum: 'a3f1c2e4b5d67890a3f1c2e4b5d67890a3f1c2e4b5d67890a3f1c2e4b5d67890',
  validated_at: new Date().toISOString(),
  expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
  change_summary: '新增 Provider 1 项，修改 1 项，停用 Alias 1 项',
  affected_alias_ids: ['alias-1'],
  issues: [
    {
      code: 'CONNECTION_CHECK_STALE',
      severity: 'WARNING',
      entity_type: 'credential',
      entity_id: 'cred-003',
      entity_name: '备份密钥',
      field_path: null,
      message: '最近 24 小时无成功检测记录',
      suggestion: '发布前执行连接检测',
      related_entity_ids: [],
    },
  ],
})

const validationFailed = () => ({
  ...validationOk(),
  status: 'FAILED',
  issues: [
    {
      code: 'ALIAS_NO_AVAILABLE_CANDIDATE',
      severity: 'ERROR',
      entity_type: 'model_alias',
      entity_id: 'alias-1',
      entity_name: 'chat-default',
      field_path: 'candidates',
      message: '启用的 Alias 没有可用候选',
      suggestion: '为其配置启用候选或停用该 Alias',
      related_entity_ids: ['cand-5'],
    },
    ...validationOk().issues,
  ],
})

const instanceResults = (targetNo: number, mode: 'normal' | 'partial') => [
  {
    instance_id: 'instance-a',
    runtime_mode: 'STANDALONE_SERVER',
    runtime_version: '1.0.0',
    supported_schema_versions: ['1'],
    loaded_adapter_types: ['OPENAI', 'DEEPSEEK'],
    from_snapshot_no: 12,
    target_snapshot_no: targetNo,
    status: 'LOADED',
    retry_count: 0,
    load_duration_ms: 820,
    error_code: null,
    error_summary: null,
    updated_at: new Date().toISOString(),
  },
  {
    instance_id: 'instance-b',
    runtime_mode: 'STANDALONE_SERVER',
    runtime_version: '1.0.0',
    supported_schema_versions: ['1'],
    loaded_adapter_types: ['OPENAI', 'DEEPSEEK'],
    from_snapshot_no: 12,
    target_snapshot_no: targetNo,
    status: mode === 'partial' ? 'TIMED_OUT' : 'LOADED',
    retry_count: mode === 'partial' ? 1 : 0,
    load_duration_ms: mode === 'partial' ? null : 950,
    error_code: mode === 'partial' ? null : null,
    error_summary: null,
    updated_at: new Date().toISOString(),
  },
]

let lastPublishRecord: Record<string, unknown> | null = null

function readBody(req: Connect.IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (chunk) => {
      raw += String(chunk)
    })
    req.on('end', () => {
      try {
        resolve(raw === '' ? {} : (JSON.parse(raw) as Record<string, unknown>))
      } catch {
        resolve({})
      }
    })
  })
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify(body))
}

function sendError(res: ServerResponse, status: number, code: string, message: string): void {
  sendJson(res, status, { error: { code, type: 'api', message, retryable: false } })
}

export function handleConfigApi(req: Connect.IncomingMessage, url: URL, res: ServerResponse): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')
  if (!path.startsWith('/config/')) return false

  if (path === '/config/draft-state' && method === 'GET') {
    sendJson(res, 200, { data: draftState() })
    return true
  }
  if (path === '/config/draft-changes/summary' && method === 'GET') {
    sendJson(res, 200, {
      data: {
        total_count: 3,
        create_count: 1,
        update_count: 1,
        enable_count: 0,
        disable_count: 1,
        delete_count: 0,
        by_entity_type: { provider: 2, model_alias: 1 },
      },
    })
    return true
  }
  if (path === '/config/draft-changes' && method === 'GET') {
    const changeType = url.searchParams.getAll('change_type')
    let rows = draftChanges
    if (changeType.length > 0) rows = rows.filter((row) => changeType.includes(row.change_type))
    sendJson(res, 200, {
      data: {
        items: rows,
        total: rows.length,
        page: 1,
        page_size: 20,
        sort: 'modified_at',
        query_started_at: '2026-09-05T10:00:00Z',
        data_updated_at: '2026-09-05T10:00:01Z',
      },
    })
    return true
  }

  const revertMatch = path.match(/^\/config\/draft-changes\/([^/]+)\/([^/]+)\/revert$/)
  if (revertMatch && method === 'POST') {
    void readBody(req).then((body) => {
      if (draftStatus === 'PUBLISHING') {
        sendError(res, 409, 'CONFIG_PUBLISH_IN_PROGRESS', '已有发布占用草稿锁')
        return
      }
      if (typeof body.draft_revision === 'number' && body.draft_revision !== draftRevision) {
        sendError(res, 409, 'CONFIG_DRAFT_CHANGED', '草稿修订号已变化')
        return
      }
      const change = draftChanges.find(
        (row) => row.entity_type === revertMatch[1] && row.entity_id === revertMatch[2],
      )
      if (change && !change.revertable) {
        sendError(res, 409, 'DRAFT_REVERT_BLOCKED', '其他草稿对象仍引用目标对象')
        return
      }
      seq += 1
      draftRevision += 1
      sendJson(res, 200, { data: draftState() })
    })
    return true
  }
  if (path === '/config/draft-changes/revert-all' && method === 'POST') {
    void readBody(req).then((body) => {
      if (String(body.confirmation_text ?? '') !== 'REVERT ALL') {
        sendError(res, 400, 'CONFIRMATION_TEXT_MISMATCH', '确认文本不匹配')
        return
      }
      if (typeof body.draft_revision === 'number' && body.draft_revision !== draftRevision) {
        sendError(res, 409, 'CONFIG_DRAFT_CHANGED', '草稿修订号已变化')
        return
      }
      draftRevision += 1
      seq += 1
      sendJson(res, 200, { data: { ...draftState(), change_count: 0 } })
    })
    return true
  }
  if (path === '/config/validate' && method === 'POST') {
    void readBody(req).then((body) => {
      if (draftStatus === 'PUBLISHING') {
        sendError(res, 409, 'CONFIG_PUBLISH_IN_PROGRESS', '已有发布占用草稿锁')
        return
      }
      if (typeof body.draft_revision === 'number' && body.draft_revision !== draftRevision) {
        sendError(res, 409, 'CONFIG_DRAFT_CHANGED', '草稿修订号已变化')
        return
      }
      // 失败场景由查询参数触发（测试夹具约定）
      const failMode = url.searchParams.get('fail') === '1'
      sendJson(res, 200, { data: failMode ? validationFailed() : validationOk() })
    })
    return true
  }
  if (path === '/config/publish' && method === 'POST') {
    void readBody(req).then((body) => {
      if (draftStatus === 'PUBLISHING') {
        sendError(res, 409, 'CONFIG_PUBLISH_IN_PROGRESS', '已有发布占用草稿锁')
        return
      }
      if (typeof body.draft_revision === 'number' && body.draft_revision !== draftRevision) {
        sendError(res, 409, 'CONFIG_DRAFT_CHANGED', '草稿修订号已变化')
        return
      }
      const acknowledged = Array.isArray(body.acknowledged_warning_ids)
        ? (body.acknowledged_warning_ids as string[])
        : []
      if (acknowledged.length < 1) {
        sendError(res, 400, 'FIELD_VALIDATION_FAILED', '警告确认不完整')
        return
      }
      seq += 1
      draftStatus = 'PUBLISHING'
      draftRevision += 1
      const partial = url.searchParams.get('partial') === '1'
      lastPublishRecord = {
        id: `pub-${seq}`,
        snapshot_no: 13,
        from_snapshot_no: 12,
        target_snapshot_no: 13,
        status: partial ? 'PARTIAL_FAILED' : 'SUCCEEDED',
        published_by_name: '系统管理员',
        publish_note: String(body.publish_note ?? ''),
        published_at: new Date().toISOString(),
        completed_at: new Date(Date.now() + 5000).toISOString(),
        duration_ms: 5200,
        draft_revision: draftRevision - 1,
        content_checksum: validationOk().content_checksum,
        change_summary: validationOk().change_summary,
        affected_alias_ids: ['alias-1'],
        acknowledged_warning_ids: acknowledged,
        instance_results: instanceResults(13, partial ? 'partial' : 'normal'),
        first_round_completed_at: new Date(Date.now() + 5000).toISOString(),
        converged_at: partial ? null : new Date(Date.now() + 5000).toISOString(),
      }
      // 模拟激活完成后草稿解锁
      draftStatus = 'EDITABLE'
      sendJson(res, 200, { data: lastPublishRecord })
    })
    return true
  }
  if (path === '/config/publish-records' && method === 'GET') {
    const items = lastPublishRecord
      ? [
          {
            id: lastPublishRecord.id,
            snapshot_no: lastPublishRecord.snapshot_no,
            from_snapshot_no: lastPublishRecord.from_snapshot_no,
            status: lastPublishRecord.status,
            published_by_name: lastPublishRecord.published_by_name,
            publish_note: lastPublishRecord.publish_note,
            published_at: lastPublishRecord.published_at,
            completed_at: lastPublishRecord.completed_at,
            duration_ms: lastPublishRecord.duration_ms,
          },
        ]
      : []
    sendJson(res, 200, {
      data: {
        items,
        total: items.length,
        page: 1,
        page_size: 10,
        sort: '-published_at',
        query_started_at: '2026-09-05T10:00:00Z',
        data_updated_at: '2026-09-05T10:00:01Z',
      },
    })
    return true
  }
  return false
}
