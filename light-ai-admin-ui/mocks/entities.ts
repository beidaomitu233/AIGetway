import type { Connect } from 'vite'
import type { ServerResponse } from 'node:http'

/**
 * FE-P02 契约夹具：Provider 与 Credential Pool 实体内存存储。
 * 仅用于本地开发与页面验收（BE-P02 未交付）；后端完成后整体删除 mocks/。
 */

export interface MockProvider {
  id: string
  name: string
  type: string
  base_url: string
  proxy_url: string | null
  connect_timeout_ms: number
  read_timeout_ms: number
  default_headers: Record<string, string>
  connection_status: string
  last_check_at: string | null
  last_check_latency_ms: number | null
  last_error_code: string | null
  enabled: boolean
  draft_changed: boolean
  version: number
  created_by: string
  created_at: string
  updated_by: string
  updated_at: string
}

export interface MockPool {
  id: string
  provider_id: string
  name: string
  selection_strategy: string
  credential_total: number
  credential_available: number
  current_concurrency: number
  rpm_used: number
  tpm_used: number
  status: string
  enabled: boolean
  draft_changed: boolean
  version: number
  created_by: string
  created_at: string
  updated_by: string
  updated_at: string
}

export interface MockCredential {
  id: string
  pool_id: string
  name: string
  masked_value: string
  secret_ref_display: string | null
  secret_source: string
  weight: number
  rpm_limit: number | null
  tpm_limit: number | null
  concurrent_limit: number | null
  current_concurrency: number
  health_status: string
  rate_limit_reset_at: string | null
  last_success_at: string | null
  last_check_at: string | null
  enabled: boolean
  draft_changed: boolean
  version: number
}

let idSeq = 100
function nextId(prefix: string): string {
  idSeq += 1
  return `${prefix}-${idSeq}`
}

function nowIso(): string {
  return new Date().toISOString()
}

const provider: MockProvider = {
  id: 'prov-001',
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
  created_by: 'user-admin',
  created_at: '2026-09-01T08:00:00Z',
  updated_by: 'user-admin',
  updated_at: '2026-09-04T09:30:00Z',
}

const provider2: MockProvider = {
  ...provider,
  id: 'prov-002',
  name: 'DeepSeek 备用',
  type: 'DEEPSEEK',
  base_url: 'https://api.deepseek.com/v1/',
  connection_status: 'UNKNOWN',
  last_check_at: null,
  last_check_latency_ms: null,
  enabled: false,
  draft_changed: true,
  version: 5,
  updated_at: '2026-09-05T01:10:00Z',
}

const pool1: MockPool = {
  id: 'pool-001',
  provider_id: 'prov-001',
  name: 'OpenAI 主池',
  selection_strategy: 'LEAST_CONCURRENT',
  credential_total: 3,
  credential_available: 2,
  current_concurrency: 2,
  rpm_used: 18,
  tpm_used: 40210,
  status: 'PARTIAL_AVAILABLE',
  enabled: true,
  draft_changed: false,
  version: 2,
  created_by: 'user-admin',
  created_at: '2026-09-01T09:00:00Z',
  updated_by: 'user-ops',
  updated_at: '2026-09-04T15:00:00Z',
}

const pool2: MockPool = {
  ...pool1,
  id: 'pool-002',
  provider_id: 'prov-002',
  name: 'DeepSeek 备用池',
  selection_strategy: 'WEIGHTED_RANDOM',
  credential_total: 0,
  credential_available: 0,
  current_concurrency: 0,
  rpm_used: 0,
  tpm_used: 0,
  status: 'DISABLED',
  enabled: false,
  draft_changed: true,
  version: 4,
}

export const mockDb = {
  providers: [provider, provider2] as MockProvider[],
  pools: [pool1, pool2] as MockPool[],
  credentials: [
    {
      id: 'cred-001',
      pool_id: 'pool-001',
      name: '主密钥 A',
      masked_value: 'sk-****a1b2',
      secret_ref_display: null,
      secret_source: 'INLINE_ENCRYPTED',
      weight: 1,
      rpm_limit: 500,
      tpm_limit: 200000,
      concurrent_limit: 10,
      current_concurrency: 2,
      health_status: 'HEALTHY',
      rate_limit_reset_at: null,
      last_success_at: '2026-09-05T03:12:00Z',
      last_check_at: '2026-09-05T02:00:00Z',
      enabled: true,
      draft_changed: false,
      version: 1,
    },
    {
      id: 'cred-002',
      pool_id: 'pool-001',
      name: '企业代理引用',
      masked_value: 'env:OPENAI_KEY_2',
      secret_ref_display: 'vault://prod/openai-key-2',
      secret_source: 'EXTERNAL_REF',
      weight: 2,
      rpm_limit: null,
      tpm_limit: null,
      concurrent_limit: null,
      current_concurrency: 0,
      health_status: 'RATE_LIMITED',
      rate_limit_reset_at: '2026-09-05T04:00:00Z',
      last_success_at: '2026-09-05T03:00:00Z',
      last_check_at: '2026-09-05T02:30:00Z',
      enabled: true,
      draft_changed: false,
      version: 2,
    },
    {
      id: 'cred-003',
      pool_id: 'pool-001',
      name: '备份密钥',
      masked_value: 'sk-****9f8e',
      secret_ref_display: null,
      secret_source: 'INLINE_ENCRYPTED',
      weight: 1,
      rpm_limit: null,
      tpm_limit: null,
      concurrent_limit: null,
      current_concurrency: 0,
      health_status: 'INVALID',
      rate_limit_reset_at: null,
      last_success_at: null,
      last_check_at: '2026-09-04T20:00:00Z',
      enabled: false,
      draft_changed: true,
      version: 3,
    },
  ] as MockCredential[],
  checkRecords: [] as Array<Record<string, unknown>>,
}

export function providerListItem(row: MockProvider): Record<string, unknown> {
  return {
    ...row,
    provider_model_count: row.id === 'prov-001' ? 2 : 0,
    credential_pool_count: mockDb.pools.filter((pool) => pool.provider_id === row.id).length,
  }
}

export function providerDetail(row: MockProvider): Record<string, unknown> {
  return {
    ...row,
    recent_check_records: mockDb.checkRecords.filter(
      (record) => record.target_id === row.id,
    ).slice(0, 10),
  }
}

export function poolDetail(row: MockPool): Record<string, unknown> {
  return {
    ...withProviderName(row),
    route_candidate_count: 2,
    model_alias_count: 1,
  }
}

export function credentialListItem(row: MockCredential): Record<string, unknown> {
  return { ...row }
}

function matchesKeyword(keyword: string, values: Array<string | null>): boolean {
  return values.some((value) => (value ?? '').toLowerCase().includes(keyword.toLowerCase()))
}

export function handleProviderApi(req: Connect.IncomingMessage, url: URL, res: ServerResponse): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')

  const providerMatch = path.match(/^\/providers(?:\/([^/]+))?(?:\/([a-z-]+))?$/)
  if (!providerMatch) return false
  const [, id, action] = providerMatch

  if (!id && method === 'GET') {
    const keyword = url.searchParams.get('keyword') ?? ''
    const typeFilter = url.searchParams.getAll('type')
    const statusFilter = url.searchParams.getAll('connection_status')
    const enabled = url.searchParams.get('enabled')
    const draftChanged = url.searchParams.get('draft_changed')
    const page = Number(url.searchParams.get('page') ?? '1')
    const pageSize = Number(url.searchParams.get('page_size') ?? '20')
    let rows = mockDb.providers
    if (keyword !== '') rows = rows.filter((row) => matchesKeyword(keyword, [row.name, row.base_url]))
    if (typeFilter.length > 0) rows = rows.filter((row) => typeFilter.includes(row.type))
    if (statusFilter.length > 0) rows = rows.filter((row) => statusFilter.includes(row.connection_status))
    if (enabled === 'true' || enabled === 'false') rows = rows.filter((row) => String(row.enabled) === enabled)
    if (draftChanged === 'true' || draftChanged === 'false')
      rows = rows.filter((row) => String(row.draft_changed) === draftChanged)
    const start = (page - 1) * pageSize
    sendPage(res, {
      items: rows.slice(start, start + pageSize).map(providerListItem),
      total: rows.length,
      page,
      page_size: pageSize,
      sort: url.searchParams.get('sort') ?? 'updated_at',
    })
    return true
  }

  if (!id && method === 'POST') {
    void readBody(req).then((body) => {
      const row: MockProvider = {
        id: nextId('prov'),
        name: String(body.name ?? ''),
        type: String(body.type ?? ''),
        base_url: String(body.base_url ?? ''),
        proxy_url: body.proxy_url === null || body.proxy_url === undefined ? null : String(body.proxy_url),
        connect_timeout_ms: Number(body.connect_timeout_ms ?? 3000),
        read_timeout_ms: Number(body.read_timeout_ms ?? 120000),
        default_headers: (body.default_headers ?? {}) as Record<string, string>,
        connection_status: 'UNKNOWN',
        last_check_at: null,
        last_check_latency_ms: null,
        last_error_code: null,
        enabled: body.enabled !== false,
        draft_changed: true,
        version: 1,
        created_by: 'user-admin',
        created_at: nowIso(),
        updated_by: 'user-admin',
        updated_at: nowIso(),
      }
      mockDb.providers.unshift(row)
      sendJson(res, 201, operationResult(row.id, row.version, true))
    })
    return true
  }

  const row = mockDb.providers.find((item) => item.id === id)
  if (!row) {
    sendError(res, 404, 'OBJECT_NOT_FOUND', '对象不存在或已删除')
    return true
  }

  if (id && !action && method === 'GET') {
    sendJson(res, 200, { data: providerDetail(row) })
    return true
  }
  if (id && !action && method === 'PUT') {
    void readBody(req).then((body) => {
      if (body.version !== row.version) {
        sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return
      }
      Object.assign(row, {
        name: body.name,
        base_url: body.base_url,
        proxy_url: body.proxy_url ?? null,
        connect_timeout_ms: body.connect_timeout_ms,
        read_timeout_ms: body.read_timeout_ms,
        default_headers: body.default_headers ?? {},
        enabled: body.enabled,
        version: row.version + 1,
        draft_changed: true,
        updated_at: nowIso(),
      })
      sendJson(res, 200, operationResult(row.id, row.version, true))
    })
    return true
  }
  if (id && !action && method === 'DELETE') {
    void readBody(req).then((body) => {
      if (typeof body.version === 'number' && body.version !== row.version) {
        sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return
      }
      if (mockDb.pools.some((pool) => pool.provider_id === row.id)) {
        sendError(res, 409, 'OBJECT_IN_USE', '对象仍被其他配置引用，不能删除')
        return
      }
      mockDb.providers = mockDb.providers.filter((item) => item.id !== row.id)
      sendJson(res, 200, operationResult(row.id, row.version, true))
    })
    return true
  }
  if (action === 'impact' && method === 'GET') {
    const operation = url.searchParams.get('operation') ?? 'DISABLE'
    const references = mockDb.pools
      .filter((pool) => pool.provider_id === row.id)
      .map((pool) => ({
        entity_type: 'credential_pool',
        id: pool.id,
        name: pool.name,
        relation: '凭证池归属',
      }))
    sendJson(res, 200, {
      data: {
        impact_version: `impact-${row.id}-${row.version}-${operation}`,
        entity_type: 'provider',
        entity_id: row.id,
        references,
        affected_alias_ids: [],
        can_delete: references.length === 0,
        blockers: references.length === 0 ? [] : ['存在关联凭证池'],
      },
    })
    return true
  }
  if (action === 'check' && method === 'POST') {
    void readBody(req).then((body) => {
      const record = {
        id: nextId('chk'),
        target_type: 'PROVIDER',
        target_id: row.id,
        status: 'SUCCEEDED',
        started_at: nowIso(),
        ended_at: nowIso(),
        total_ms: 386,
        trace_id: null,
        usage: body.mode === 'MINIMAL_CHAT' ? { total_tokens: 14 } : null,
        error_code: null,
        error_summary: null,
        provider_request_id: 'req-mock-0001',
      }
      mockDb.checkRecords.unshift(record)
      row.connection_status = 'AVAILABLE'
      row.last_check_at = record.started_at
      row.last_check_latency_ms = record.total_ms
      row.last_error_code = null
      sendJson(res, 200, { data: record })
    })
    return true
  }
  if (action === 'enable' && method === 'POST') {
    void readBody(req).then((body) => {
      if (body.version !== row.version) {
        sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return
      }
      row.enabled = true
      row.version += 1
      row.draft_changed = true
      row.updated_at = nowIso()
      sendJson(res, 200, operationResult(row.id, row.version, true))
    })
    return true
  }
  if (action === 'disable' && method === 'POST') {
    void readBody(req).then((body) => {
      if (body.version !== row.version) {
        sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
        return
      }
      row.enabled = false
      row.version += 1
      row.draft_changed = true
      row.updated_at = nowIso()
      sendJson(res, 200, operationResult(row.id, row.version, true))
    })
    return true
  }
  return false
}

export function handlePoolApi(req: Connect.IncomingMessage, url: URL, res: ServerResponse): boolean {
  const method = req.method ?? 'GET'
  const path = url.pathname.replace(/^\/(?:[^/]+\/)?admin/, '')

  const poolMatch = path.match(/^\/credential-pools(?:\/([^/]+))?(?:\/(impact|enable|disable))?$/)
  if (poolMatch) {
    const [, id, action] = poolMatch
    if (!id && method === 'GET') {
      const keyword = url.searchParams.get('keyword') ?? ''
      const providerFilter = url.searchParams.getAll('provider_id')
      const statusFilter = url.searchParams.getAll('status')
      const enabled = url.searchParams.get('enabled')
      const page = Number(url.searchParams.get('page') ?? '1')
      const pageSize = Number(url.searchParams.get('page_size') ?? '20')
      let rows = mockDb.pools
      if (keyword !== '') rows = rows.filter((row) => matchesKeyword(keyword, [row.name]))
      if (providerFilter.length > 0) rows = rows.filter((row) => providerFilter.includes(row.provider_id))
      if (statusFilter.length > 0) rows = rows.filter((row) => statusFilter.includes(row.status))
      if (enabled === 'true' || enabled === 'false')
        rows = rows.filter((row) => String(row.enabled) === enabled)
      const start = (page - 1) * pageSize
      sendPage(res, {
        items: rows.slice(start, start + pageSize).map(withProviderName),
        total: rows.length,
        page,
        page_size: pageSize,
        sort: url.searchParams.get('sort') ?? 'updated_at',
      })
      return true
    }
    if (!id && method === 'POST') {
      void readBody(req).then((body) => {
        const provider = mockDb.providers.find((item) => item.id === body.provider_id)
        if (!provider) {
          sendError(res, 422, 'OBJECT_REFERENCE_INVALID', '引用的 Provider 不存在')
          return
        }
        const row: MockPool = {
          id: nextId('pool'),
          provider_id: String(body.provider_id ?? ''),
          name: String(body.name ?? ''),
          selection_strategy: String(body.selection_strategy ?? 'LEAST_CONCURRENT'),
          credential_total: 0,
          credential_available: 0,
          current_concurrency: 0,
          rpm_used: 0,
          tpm_used: 0,
          status: body.enabled === false ? 'DISABLED' : 'UNAVAILABLE',
          enabled: body.enabled !== false,
          draft_changed: true,
          version: 1,
          created_by: 'user-admin',
          created_at: nowIso(),
          updated_by: 'user-admin',
          updated_at: nowIso(),
        }
        mockDb.pools.unshift(row)
        sendJson(res, 201, operationResult(row.id, row.version, true))
      })
      return true
    }
    const row = mockDb.pools.find((item) => item.id === id)
    if (!row) {
      sendError(res, 404, 'OBJECT_NOT_FOUND', '对象不存在或已删除')
      return true
    }
    if (id && !action && method === 'GET') {
      sendJson(res, 200, { data: poolDetail(row) })
      return true
    }
    if (id && !action && method === 'PUT') {
      void readBody(req).then((body) => {
        if (body.version !== row.version) {
          sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
          return
        }
        Object.assign(row, {
          name: body.name,
          selection_strategy: body.selection_strategy,
          enabled: body.enabled,
          version: row.version + 1,
          draft_changed: true,
          updated_at: nowIso(),
        })
        sendJson(res, 200, operationResult(row.id, row.version, true))
      })
      return true
    }
    if (id && !action && method === 'DELETE') {
      void readBody(req).then((body) => {
        if (typeof body.version === 'number' && body.version !== row.version) {
          sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
          return
        }
        if (mockDb.credentials.some((credential) => credential.pool_id === row.id)) {
          sendError(res, 409, 'OBJECT_IN_USE', '池内仍存在 Credential，不能删除')
          return
        }
        mockDb.pools = mockDb.pools.filter((item) => item.id !== row.id)
        sendJson(res, 200, operationResult(row.id, row.version, true))
      })
      return true
    }
    if (action === 'impact' && method === 'GET') {
      sendJson(res, 200, {
        data: {
          impact_version: `impact-${row.id}-${row.version}-${url.searchParams.get('operation') ?? 'DISABLE'}`,
          entity_type: 'credential_pool',
          entity_id: row.id,
          references: [],
          affected_alias_ids: [],
          can_delete: true,
          blockers: [],
        },
      })
      return true
    }
    if ((action === 'enable' || action === 'disable') && method === 'POST') {
      void readBody(req).then((body) => {
        if (body.version !== row.version) {
          sendError(res, 409, 'CONFIG_VERSION_CONFLICT', '对象已被其他管理员修改')
          return
        }
        row.enabled = action === 'enable'
        row.version += 1
        row.draft_changed = true
        row.status = row.enabled ? 'UNAVAILABLE' : 'DISABLED'
        row.updated_at = nowIso()
        sendJson(res, 200, operationResult(row.id, row.version, true))
      })
      return true
    }
    return false
  }

  const credentialMatch = path.match(/^\/credential-pools\/([^/]+)\/credentials$/)
  if (credentialMatch) {
    const poolId = credentialMatch[1]
    const page = Number(url.searchParams.get('page') ?? '1')
    const pageSize = Number(url.searchParams.get('page_size') ?? '50')
    const healthStatus = url.searchParams.getAll('health_status')
    const enabled = url.searchParams.get('enabled')
    let rows = mockDb.credentials.filter((credential) => credential.pool_id === poolId)
    if (healthStatus.length > 0)
      rows = rows.filter((row) => healthStatus.includes(row.health_status))
    if (enabled === 'true' || enabled === 'false')
      rows = rows.filter((row) => String(row.enabled) === enabled)
    const start = (page - 1) * pageSize
    sendPage(res, {
      items: rows.slice(start, start + pageSize).map(credentialListItem),
      total: rows.length,
      page,
      page_size: pageSize,
      sort: url.searchParams.get('sort') ?? 'name',
    })
    return true
  }
  return false
}

function withProviderName(row: MockPool): Record<string, unknown> {
  const provider = mockDb.providers.find((item) => item.id === row.provider_id)
  return { ...row, provider_name: provider?.name ?? row.provider_id }
}

function operationResult(id: string, version: number, draftChanged: boolean): unknown {
  return {
    data: {
      id,
      version,
      entity: null,
      draft_changed: draftChanged,
      draft_revision: 35,
      request_id: `mock-${Date.now()}`,
    },
  }
}

function sendPage(res: ServerResponse, page: Record<string, unknown>): void {
  sendJson(res, 200, { data: page })
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(JSON.stringify(body))
}

function sendError(res: ServerResponse, status: number, code: string, message: string): void {
  sendJson(res, status, {
    error: { code, type: 'api', message, retryable: false },
  })
}

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
