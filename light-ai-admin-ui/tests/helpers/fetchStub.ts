import { vi } from 'vitest'

export interface JsonMockResponse {
  status: number
  body: unknown
}

export type JsonMockHandler = (context: {
  url: URL
  method: string
  body: Record<string, unknown>
}) => JsonMockResponse | undefined

export interface FetchStub {
  calls: Array<{ url: string; method: string; body: Record<string, unknown> }>
  restore: () => void
}

/** 按路径分发的 JSON fetch 桩：返回 undefined 时回退 404 错误信封。 */
export function installJsonFetchStub(handler: JsonMockHandler): FetchStub {
  const calls: FetchStub['calls'] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const urlText = typeof input === 'string' ? input : String(input)
    const url = new URL(urlText, 'http://localhost')
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: Record<string, unknown> = {}
    if (typeof init?.body === 'string' && init.body !== '') {
      try {
        body = JSON.parse(init.body) as Record<string, unknown>
      } catch {
        body = {}
      }
    }
    calls.push({ url: urlText, method, body })
    const matched = handler({ url, method, body })
    const status = matched?.status ?? 404
    const responseBody =
      matched?.body ??
      ({ error: { code: 'OBJECT_NOT_FOUND', type: 'api', message: 'not found', retryable: false } })
    return new Response(JSON.stringify(responseBody), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return {
    calls,
    restore: () => vi.unstubAllGlobals(),
  }
}

export function pageEnvelope(items: unknown[], total = items.length): JsonMockResponse {
  return {
    status: 200,
    body: {
      data: {
        items,
        total,
        page: 1,
        page_size: 20,
        sort: 'updated_at',
        query_started_at: '2026-09-05T10:00:00Z',
        data_updated_at: '2026-09-05T10:00:01Z',
      },
    },
  }
}

export function dataEnvelope(payload: unknown): JsonMockResponse {
  return { status: 200, body: { data: payload } }
}

export function errorEnvelope(status: number, code: string, message: string, extra?: Record<string, unknown>): JsonMockResponse {
  return {
    status,
    body: { error: { code, type: 'api', message, retryable: false, ...extra } },
  }
}
