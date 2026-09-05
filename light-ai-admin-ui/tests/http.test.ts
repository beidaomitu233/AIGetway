import { afterEach, describe, expect, it, vi } from 'vitest'
import { request, registerCsrfToken } from '@/api/http'
import { initRuntimeConfig } from '@/app/runtimeConfig'
import { ApiError, TimeoutError, isAbortError } from '@/api/errors'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function rejectionOf(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise
  } catch (error) {
    return error
  }
  throw new Error('期望请求失败，但请求成功返回')
}

function stubFetch(implementation: (url: string, init: RequestInit) => Promise<Response>): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(implementation)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
  registerCsrfToken(undefined)
  window.history.replaceState(null, '', '/')
})

describe('request', () => {
  it('成功响应解包 data', async () => {
    initRuntimeConfig()
    const fetchMock = stubFetch(() =>
      Promise.resolve(jsonResponse(200, { data: { id: 'p-1', version: 2 } })),
    )
    const data = await request<{ id: string; version: number }>({ path: '/providers/p-1' })
    expect(data).toEqual({ id: 'p-1', version: 2 })
    expect(fetchMock).toHaveBeenCalledWith('/admin/providers/p-1', expect.anything())
  })

  it('查询参数序列化：跳过空值并展开数组', async () => {
    initRuntimeConfig()
    const fetchMock = stubFetch(() => Promise.resolve(jsonResponse(200, { data: {} })))
    await request({
      path: '/providers',
      query: { keyword: 'openai', page: 2, enabled: true, empty: '', missing: null, tags: ['a', 'b'] },
    })
    const url = fetchMock.mock.calls[0][0] as string
    expect(url).toBe('/admin/providers?keyword=openai&page=2&enabled=true&tags=a&tags=b')
  })

  it('400 字段错误按字段定位', async () => {
    initRuntimeConfig()
    stubFetch(() =>
      Promise.resolve(
        jsonResponse(400, {
          error: {
            code: 'FIELD_VALIDATION_FAILED',
            type: 'validation',
            message: '字段校验失败',
            retryable: false,
            errors: [
              { field: 'base_url', code: 'INVALID_URL', message: '地址格式不正确' },
              { field: 'base_url', code: 'TOO_LONG', message: '长度超限' },
              { field: 'name', code: 'REQUIRED', message: '名称必填' },
            ],
          },
        }),
      ),
    )
    const error = (await rejectionOf(
      request({ path: '/providers', method: 'POST', body: {} }),
    )) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('FIELD_VALIDATION_FAILED')
    expect(error.fieldMessages.get('base_url')).toBe('地址格式不正确')
    expect(error.fieldMessages.get('name')).toBe('名称必填')
  })

  it('409 版本冲突携带服务端最新版本', async () => {
    initRuntimeConfig()
    stubFetch(() =>
      Promise.resolve(
        jsonResponse(409, {
          error: {
            code: 'CONFIG_VERSION_CONFLICT',
            type: 'conflict',
            message: '对象已被其他管理员修改',
            retryable: false,
            current_version: 7,
          },
        }),
      ),
    )
    const error = (await rejectionOf(request({ path: '/providers/p-1', method: 'PUT', body: {} }))) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.isVersionConflict).toBe(true)
    expect(error.serverVersion).toBe(7)
  })

  it('503 可重试错误保留 retryable 标记', async () => {
    initRuntimeConfig()
    stubFetch(() =>
      Promise.resolve(
        jsonResponse(503, {
          error: { code: 'CONFIG_DATA_UNAVAILABLE', type: 'availability', message: '配置数据暂不可读', retryable: true },
        }),
      ),
    )
    const error = (await rejectionOf(request({ path: '/providers' }))) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.retryable).toBe(true)
  })

  it('每个请求携带关联 ID，服务端 request_id 优先', async () => {
    initRuntimeConfig()
    const fetchMock = stubFetch(() =>
      Promise.resolve(
        jsonResponse(400, {
          error: { code: 'FIELD_VALIDATION_FAILED', type: 'validation', message: '失败', request_id: 'srv-req-1' },
        }),
      ),
    )
    const error = (await rejectionOf(request({ path: '/providers' }))) as ApiError
    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>
    expect(headers['X-Request-Id']).toBeTruthy()
    expect(error.requestId).toBe('srv-req-1')
  })

  it('会话 CSRF Token 仅写请求携带', async () => {
    initRuntimeConfig()
    const fetchMock = stubFetch(() => Promise.resolve(jsonResponse(200, { data: {} })))
    registerCsrfToken('csrf-token-1')
    await request({ path: '/providers' })
    await request({ path: '/providers', method: 'POST', body: {} })
    const getHeaders = fetchMock.mock.calls[0][1].headers as Record<string, string>
    const postHeaders = fetchMock.mock.calls[1][1].headers as Record<string, string>
    expect(getHeaders['X-CSRF-Token']).toBeUndefined()
    expect(postHeaders['X-CSRF-Token']).toBe('csrf-token-1')
  })

  it('外部取消抛出 AbortError，可被调用方识别', async () => {
    initRuntimeConfig()
    const controller = new AbortController()
    stubFetch(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init.signal!.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          )
        }),
    )
    const pending = request({ path: '/providers', signal: controller.signal })
    controller.abort()
    const error = await rejectionOf(pending)
    expect(isAbortError(error)).toBe(true)
  })

  it('超时产生 TimeoutError 而非错误弹窗语义', async () => {
    initRuntimeConfig()
    stubFetch(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init.signal!.addEventListener('abort', () => reject(init.signal!.reason))
        }),
    )
    const error = (await rejectionOf(request({ path: '/providers', timeoutMs: 10 }))) as TimeoutError
    expect(error).toBeInstanceOf(TimeoutError)
  })
})
